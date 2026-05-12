package com.linkedin.davinci.store.rocksdb.merge;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serialization.avro.ChunkedValueManifestSerializer;
import com.linkedin.venice.storage.protocol.ChunkedValueManifest;
import com.linkedin.venice.utils.ByteUtils;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import org.apache.avro.io.BinaryDecoder;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Static helpers used by the materializing storage partitions
 * ({@code MaterializingRocksDBStoragePartition} and
 * {@code MaterializingReplicationMetadataRocksDBStoragePartition}) to apply / strip the
 * VT-merge experiment's kind-byte framing on writes and reads.
 *
 * <p>Lives in the same package as the parser/registry/fold context so the wire-format
 * details are localized to one package.
 */
public final class MaterializingFraming {
  private static final Logger LOGGER = LogManager.getLogger(MaterializingFraming.class);

  private static final int CHUNK_SCHEMA_ID = AvroProtocolDefinition.CHUNK.getCurrentProtocolVersion();
  private static final int CHUNK_MANIFEST_SCHEMA_ID =
      AvroProtocolDefinition.CHUNKED_VALUE_MANIFEST.getCurrentProtocolVersion();

  /**
   * Per-thread reusable Avro reader for {@link ChunkedValueManifest}. We use a SpecificDatumReader
   * directly (rather than the {@link ChunkedValueManifestSerializer}) so we can read from a
   * {@link ByteArrayInputStream} and observe {@code .available()} after the read — this is how we
   * detect operand bytes appended after the manifest by the {@code StringAppendOperator}. The
   * serializer's {@code byte[]} APIs construct an internal buffering decoder whose consumption
   * position is not externally observable.
   */
  private static final ThreadLocal<SpecificDatumReader<ChunkedValueManifest>> MANIFEST_READER =
      ThreadLocal.withInitial(() -> new SpecificDatumReader<>(ChunkedValueManifest.SCHEMA$));

  private MaterializingFraming() {
    // utility class
  }

  /**
   * Process-global flag indicating that the VT-merge experiment is active for batch-push value
   * checksum computation. Set by {@code RocksDBStorageEngineFactory} when the server-side
   * feature flag is on. Consulted by {@code PartitionConsumptionState.maybeUpdateExpectedChecksum}
   * to apply the framing transformation to the checksum so the upstream-computed checksum
   * matches the actual SST-file content (which is post-framing).
   *
   * <p>Static for the same reason {@link MaterializingFoldContextRegistry} is static — see its
   * Javadoc. The alternative is plumbing the flag through to PartitionConsumptionState via the
   * StoreIngestionTask constructor chain, which has a much wider blast radius.
   */
  private static volatile boolean FRAMING_ACTIVE_FOR_CHECKSUM = false;

  public static void setFramingActiveForChecksum(boolean active) {
    FRAMING_ACTIVE_FOR_CHECKSUM = active;
  }

  /**
   * @return {@code true} iff the framing transformation should be applied to {@code schemaId}'s
   *         value bytes when computing the expected SST-file checksum. Returns {@code false}
   *         for chunks/manifests (negative schemaId) which bypass framing.
   */
  public static boolean isFramingActiveForChecksum(int schemaId) {
    return FRAMING_ACTIVE_FOR_CHECKSUM && schemaId != CHUNK_SCHEMA_ID && schemaId != CHUNK_MANIFEST_SCHEMA_ID;
  }

  /**
   * Thread-local re-entry guard. Set to {@code true} for the duration of the partition's
   * outermost framing call; nested calls (e.g. when a parent class's
   * {@code put(byte[], byte[])} forwards to {@code put(byte[], ByteBuffer)} via a virtual
   * dispatch back into our override) see this flag set and bypass framing. Caller is
   * responsible for clearing it via try/finally.
   */
  private static final ThreadLocal<Boolean> FRAMING_IN_PROGRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

  /** {@code true} iff this thread is currently inside an outer framing call. */
  public static boolean isFramingInProgress() {
    return FRAMING_IN_PROGRESS.get();
  }

  /** Mark the start of a framing call; caller must invoke {@link #endFraming()} in a finally block. */
  public static void beginFraming() {
    FRAMING_IN_PROGRESS.set(Boolean.TRUE);
  }

  /** Clear the framing-in-progress marker. */
  public static void endFraming() {
    FRAMING_IN_PROGRESS.set(Boolean.FALSE);
  }

  /**
   * Decide whether to bypass framing on a put. Bypass iff:
   * <ul>
   *   <li>the value is too small to even contain a schemaId (typically internal Venice metadata), OR
   *   <li>the value's schemaId is the special chunk or manifest id (negative) — these are
   *       internal to chunking and must not be wrapped, OR
   *   <li>this thread is already inside an outer framing call (re-entry guard).
   * </ul>
   */
  public static boolean shouldBypassFraming(ByteBuffer valueBuffer) {
    if (FRAMING_IN_PROGRESS.get()) {
      return true;
    }
    if (valueBuffer == null || valueBuffer.remaining() < ByteUtils.SIZE_OF_INT) {
      return true;
    }
    int schemaId = readIntFromBuffer(valueBuffer);
    return schemaId == CHUNK_SCHEMA_ID || schemaId == CHUNK_MANIFEST_SCHEMA_ID;
  }

  /**
   * Same as {@link #shouldBypassFraming(ByteBuffer)} but for a byte[] starting at offset 0.
   */
  public static boolean shouldBypassFraming(byte[] value) {
    if (FRAMING_IN_PROGRESS.get()) {
      return true;
    }
    if (value == null || value.length < ByteUtils.SIZE_OF_INT) {
      return true;
    }
    int schemaId = ByteUtils.readInt(value, 0);
    return schemaId == CHUNK_SCHEMA_ID || schemaId == CHUNK_MANIFEST_SCHEMA_ID;
  }

  /** Read a 4-byte BE int starting at {@code valueBuffer.position()} without consuming it. */
  public static int readIntFromBuffer(ByteBuffer valueBuffer) {
    int p = valueBuffer.position();
    return (valueBuffer.get(p) & 0xff) << 24 | (valueBuffer.get(p + 1) & 0xff) << 16
        | (valueBuffer.get(p + 2) & 0xff) << 8 | (valueBuffer.get(p + 3) & 0xff);
  }

  /**
   * Build the framed put bytes: input shape {@code [schemaId][avro]} → output shape
   * {@code [schemaId][0x00][len:varint][avro]}.
   */
  public static byte[] frameForPut(ByteBuffer valueBuffer) {
    int schemaId = readIntFromBuffer(valueBuffer);
    int avroOff = valueBuffer.position() + ByteUtils.SIZE_OF_INT;
    int avroLen = valueBuffer.remaining() - ByteUtils.SIZE_OF_INT;
    byte[] avro = new byte[avroLen];
    if (valueBuffer.hasArray()) {
      System.arraycopy(valueBuffer.array(), valueBuffer.arrayOffset() + avroOff, avro, 0, avroLen);
    } else {
      ByteBuffer dup = valueBuffer.duplicate();
      dup.position(avroOff);
      dup.get(avro);
    }
    return ConcatBlobParser.frameBase(schemaId, avro);
  }

  /** byte[]-input version of {@link #frameForPut(ByteBuffer)}. */
  public static byte[] frameForPut(byte[] value) {
    if (value.length < ByteUtils.SIZE_OF_INT) {
      throw new VeniceException("frameForPut: value too short to contain schemaId");
    }
    int schemaId = ByteUtils.readInt(value, 0);
    byte[] avro = new byte[value.length - ByteUtils.SIZE_OF_INT];
    System.arraycopy(value, ByteUtils.SIZE_OF_INT, avro, 0, avro.length);
    return ConcatBlobParser.frameBase(schemaId, avro);
  }

  /**
   * Frame an operand-content blob (input: {@code [valueSchemaId][updateSchemaId][avro-WC-payload]})
   * as an on-disk operand record ({@code [0x01][len:varint][operand-content]}).
   */
  public static byte[] frameForMerge(ByteBuffer operand) {
    byte[] content = ByteUtils.extractByteArray(operand);
    return ConcatBlobParser.frameOperand(content);
  }

  /**
   * Convert raw on-disk bytes (potentially a concat blob with framing) into the
   * {@code [schemaId][avro]} shape downstream Avro decoders expect. Returns {@code null} if
   * input is null. Returns input unchanged if it's a chunk/manifest (negative schemaId) or
   * otherwise non-framed.
   *
   * @param raw the bytes returned by {@code rocksDB.get(key)}
   * @param storeNameAndVersion the version-topic name used to look up the fold context
   */
  public static byte[] materialize(byte[] raw, String storeNameAndVersion) {
    return materialize(raw, storeNameAndVersion, null);
  }

  /**
   * Variant of {@link #materialize(byte[], String)} that accepts both chunk-fetch and RMD-fetch
   * callbacks. RMD bytes (with 4-byte valueSchemaId prefix) are used by the fold path's V2
   * algorithm to seed cross-DC per-field-ts DCR. May be null for callers that don't have a
   * RMD partition (e.g. non-replication-metadata storage partitions).
   */
  public static byte[] materialize(
      byte[] raw,
      String storeNameAndVersion,
      Function<byte[], byte[]> chunkFetchFn,
      byte[] baseKey,
      Function<byte[], byte[]> rmdFetchFn) {
    return materializeInternal(raw, storeNameAndVersion, chunkFetchFn, baseKey, rmdFetchFn);
  }

  /**
   * Variant of {@link #materialize(byte[], String)} that accepts a chunk-fetch callback. When
   * the on-disk bytes are a chunked-manifest record with appended operand bytes (the post-H5-fix
   * shape exposed by the {@code testActiveActivePartialUpdate*} integration tests), this method
   * reassembles the chunked value via the callback, folds the operands onto it, and returns a
   * non-chunked materialized record (with the manifest's nested user-schemaId in the header).
   *
   * <p>If {@code chunkFetchFn} is {@code null}, the chunked-manifest+operands case falls back to
   * returning the raw bytes (current pre-fix behavior — preserves backward compatibility for
   * call sites that don't have a chunk-fetch capability).
   *
   * @param raw the bytes returned by {@code rocksDB.get(key)} for the top-level key
   * @param storeNameAndVersion the version-topic name used to look up the fold context
   * @param chunkFetchFn function fetching the raw bytes for a chunk key (suffixed key bytes →
   *     chunk value bytes including the 4-byte chunk-schemaId header). Bytes returned MUST be
   *     the raw on-disk bytes (no further materialization). May be {@code null}.
   */
  public static byte[] materialize(byte[] raw, String storeNameAndVersion, Function<byte[], byte[]> chunkFetchFn) {
    return materializeInternal(raw, storeNameAndVersion, chunkFetchFn, null, null);
  }

  private static byte[] materializeInternal(
      byte[] raw,
      String storeNameAndVersion,
      Function<byte[], byte[]> chunkFetchFn,
      byte[] baseKey,
      Function<byte[], byte[]> rmdFetchFn) {
    if (raw == null) {
      return null;
    }
    if (raw.length < 1) {
      return raw;
    }
    // Operand-only chain (no base yet): blob starts with 0x01 kind byte.
    if (raw[0] == ConcatBlobParser.KIND_OPERAND) {
      return foldOperandOnly(raw, storeNameAndVersion);
    }
    if (raw.length < ByteUtils.SIZE_OF_INT + 1) {
      return raw;
    }
    int schemaId = ByteUtils.readInt(raw, 0);
    if (schemaId < 0) {
      // Chunk or manifest — never framed via [KIND_BASE 0x00][len:varint] (the materializing
      // partition's shouldBypassFraming short-circuits for negative schemaIds).
      //
      // VT-merge experiment Phase C chunked-value fix: a CHUNK_MANIFEST may have operand bytes
      // appended by RocksDB's StringAppendOperator (when the leader's operand-UPDATE fast path
      // sends an operand for a chunked key). Detect that case and fold the operands onto the
      // reassembled chunked value. Individual chunks (CHUNK_SCHEMA_ID) never have operands
      // appended — operand-UPDATEs target only the top-level manifest key.
      if (schemaId == CHUNK_MANIFEST_SCHEMA_ID && chunkFetchFn != null) {
        return maybeFoldChunkedManifestWithOperands(raw, storeNameAndVersion, chunkFetchFn, baseKey, rmdFetchFn);
      }
      return raw;
    }
    if (raw[ByteUtils.SIZE_OF_INT] != ConcatBlobParser.KIND_BASE) {
      // Not a framed materialized blob (legacy data, or a corrupted state). Return raw.
      return raw;
    }
    return foldFramedBaseAndOperands(raw, storeNameAndVersion, baseKey, rmdFetchFn);
  }

  /**
   * Fold path for the chunked-manifest-plus-operands case. Reassembles the chunked value via
   * {@code chunkFetchFn} and applies operands via {@link MaterializingFoldContext#foldOperands}.
   *
   * <p>Returns:
   * <ul>
   *   <li>{@code raw} unchanged if the manifest has no appended operand bytes (the common case
   *       before the leader's operand fast-path produces operands onto chunked keys).</li>
   *   <li>{@code [manifest.schemaId][materializedAvro]} on successful fold — note the schemaId
   *       in the header is the USER's value schemaId stored INSIDE the manifest, NOT the
   *       chunked-manifest negative schemaId. This is important: downstream chunking-aware
   *       readers ({@code ChunkingUtils.getFromStorage}) check the first 4 bytes to decide
   *       chunked-vs-inline, so they need to see a positive id to treat the result as inline.</li>
   *   <li>{@code raw} unchanged if the fold context isn't registered yet (e.g. early boot) —
   *       the caller's read will see the original chunked-manifest shape and the operands will
   *       remain on disk; the next read after registration will fold correctly.</li>
   * </ul>
   */
  private static byte[] maybeFoldChunkedManifestWithOperands(
      byte[] raw,
      String storeNameAndVersion,
      Function<byte[], byte[]> chunkFetchFn) {
    return maybeFoldChunkedManifestWithOperands(raw, storeNameAndVersion, chunkFetchFn, null, null);
  }

  private static byte[] maybeFoldChunkedManifestWithOperands(
      byte[] raw,
      String storeNameAndVersion,
      Function<byte[], byte[]> chunkFetchFn,
      byte[] baseKey,
      Function<byte[], byte[]> rmdFetchFn) {
    // Deserialize the manifest using a stream-backed BinaryDecoder so we can detect trailing
    // bytes via the stream's remaining-bytes count. The manifest is encoded starting at
    // offset 4 (after the int32 schemaId header), same convention as ChunkedValueManifestSerializer.
    ByteArrayInputStream bis = new ByteArrayInputStream(raw, ByteUtils.SIZE_OF_INT, raw.length - ByteUtils.SIZE_OF_INT);
    // Use directBinaryDecoder to avoid read-ahead buffering — this guarantees bis.available()
    // accurately reflects unread bytes after manifest decoding.
    BinaryDecoder decoder = DecoderFactory.get().directBinaryDecoder(bis, null);
    ChunkedValueManifest manifest;
    try {
      manifest = MANIFEST_READER.get().read(null, decoder);
    } catch (IOException e) {
      throw new VeniceException(
          "MaterializingFraming.maybeFoldChunkedManifestWithOperands: failed to decode chunked-value-manifest; "
              + "storeVersion=" + storeNameAndVersion + " rawLen=" + raw.length,
          e);
    }
    int trailingLen = bis.available();
    if (trailingLen <= 0) {
      // Pure manifest, no appended operands. Return raw — the upper-layer ChunkingUtils handles
      // chunked reassembly normally.
      return raw;
    }
    // Parse the trailing region as an operand chain. The first byte is the structural delimiter
    // RocksDB's StringAppendOperator inserted between the manifest and the first operand.
    int trailingStart = raw.length - trailingLen;
    List<byte[]> operands = parseTrailingOperandChain(raw, trailingStart);
    if (operands.isEmpty()) {
      // Trailing bytes exist but didn't parse as operands — be conservative and return raw so the
      // chunking layer can attempt the standard reassembly (and surface a clear error if the bytes
      // are corrupt).
      LOGGER.warn(
          "MaterializingFraming: chunked manifest has {} trailing bytes that did not parse as operand chain; "
              + "returning raw bytes for storeVersion={}",
          trailingLen,
          storeNameAndVersion);
      return raw;
    }
    MaterializingFoldContext ctx = MaterializingFoldContextRegistry.get(storeNameAndVersion);
    if (ctx == null) {
      LOGGER.warn(
          "MaterializingFraming: no fold context registered for store-version {}; "
              + "returning raw chunked-manifest bytes (operands ignored on this read)",
          storeNameAndVersion);
      return raw;
    }
    // Reassemble the chunked value by fetching each chunk and stripping its 4-byte schemaId header.
    byte[] assembled = reassembleChunks(manifest, chunkFetchFn, storeNameAndVersion);
    // Apply operands via the fold context (decompresses base, applies WC, recompresses).
    byte[] baseRmd = (baseKey != null && rmdFetchFn != null) ? rmdFetchFn.apply(baseKey) : null;
    byte[] materializedAvro = ctx.foldOperands(manifest.schemaId, assembled, operands, baseRmd);
    if (materializedAvro == null) {
      return null; // WC delete tombstone
    }
    LOGGER.debug(
        "VT-merge chunked-manifest fold: storeVersion={} rawLen={} manifestSchemaId={} chunks={} opCount={} "
            + "assembledLen={} materializedLen={}",
        storeNameAndVersion,
        raw.length,
        manifest.schemaId,
        manifest.keysWithChunkIdSuffix.size(),
        operands.size(),
        assembled.length,
        materializedAvro.length);
    return prependSchemaId(manifest.schemaId, materializedAvro);
  }

  /**
   * Chunked-manifest-aware chain backstop helper. Used at MERGE time to detect when the existing
   * on-disk value for a key is a {@code [CHUNK_MANIFEST_SCHEMA_ID][manifest][delim][operand]...}
   * blob and fold the chain into a single inline materialized record — bounding the on-disk
   * chain depth and keeping subsequent reads fast.
   *
   * <p>Without this backstop, reads of a chunked-manifest with N appended operands pay an O(N)
   * fold cost on EVERY read (because {@link ChainLengthBackstop} can't parse chunked-manifest
   * blobs — they don't start with the {@code KIND_BASE} prefix it expects). Tests that stress
   * many operands per key (e.g. {@code testActiveActivePartialUpdateWithCompression}, 40 operands
   * with 10000-entry map merges) blow past the router's 1-sec client timeout.
   *
   * <p>Behavior:
   * <ul>
   *   <li>If {@code raw} is a chunked manifest with {@code >= threshold} appended operands,
   *       reassembles + folds and returns the framed inline-PUT bytes the caller should PUT to
   *       the top-level key. The old chunk-suffixed keys are NOT deleted (they become orphans);
   *       this is acceptable because future reads of the top-level key see the inline value with
   *       a positive schemaId and never read the chunks again.</li>
   *   <li>Otherwise returns {@code null} (no backstop needed).</li>
   * </ul>
   *
   * <p>The returned bytes are in the shape
   * {@code [manifest.schemaId][KIND_BASE 0x00][len:varint][materializedAvro]} — i.e. they're
   * already framed as a regular materialized base via {@link ConcatBlobParser#frameBase}, so the
   * caller can use them in a {@code super.put} directly without re-framing.
   */
  public static byte[] maybeBackstopChunkedManifestChain(
      byte[] raw,
      String storeNameAndVersion,
      Function<byte[], byte[]> chunkFetchFn,
      int threshold) {
    if (raw == null || raw.length < ByteUtils.SIZE_OF_INT + 1 || threshold <= 0 || chunkFetchFn == null) {
      return null;
    }
    int schemaId = ByteUtils.readInt(raw, 0);
    if (schemaId != CHUNK_MANIFEST_SCHEMA_ID) {
      return null;
    }
    // Deserialize manifest (same approach as the read-path fold).
    ByteArrayInputStream bis = new ByteArrayInputStream(raw, ByteUtils.SIZE_OF_INT, raw.length - ByteUtils.SIZE_OF_INT);
    BinaryDecoder decoder = DecoderFactory.get().directBinaryDecoder(bis, null);
    ChunkedValueManifest manifest;
    try {
      manifest = MANIFEST_READER.get().read(null, decoder);
    } catch (IOException e) {
      LOGGER.debug(
          "MaterializingFraming.maybeBackstopChunkedManifestChain: failed to decode manifest; "
              + "skipping backstop for storeVersion={}",
          storeNameAndVersion,
          e);
      return null;
    }
    int trailingLen = bis.available();
    if (trailingLen <= 0) {
      return null; // no operands appended
    }
    int trailingStart = raw.length - trailingLen;
    List<byte[]> operands = parseTrailingOperandChain(raw, trailingStart);
    if (operands.size() < threshold) {
      return null; // chain not yet at threshold
    }
    MaterializingFoldContext ctx = MaterializingFoldContextRegistry.get(storeNameAndVersion);
    if (ctx == null) {
      return null;
    }
    byte[] assembled = reassembleChunks(manifest, chunkFetchFn, storeNameAndVersion);
    byte[] materializedAvro = ctx.foldOperands(manifest.schemaId, assembled, operands);
    if (materializedAvro == null) {
      // WC delete tombstone — let the chain stay; next read returns null via the read-path fold.
      return null;
    }
    LOGGER.debug(
        "VT-merge chunked-manifest backstop: storeVersion={} rawLen={} manifestSchemaId={} chunks={} opCount={} "
            + "assembledLen={} materializedLen={}",
        storeNameAndVersion,
        raw.length,
        manifest.schemaId,
        manifest.keysWithChunkIdSuffix.size(),
        operands.size(),
        assembled.length,
        materializedAvro.length);
    return ConcatBlobParser.frameBase(manifest.schemaId, materializedAvro);
  }

  /**
   * Parse the operand chain starting at {@code start} where {@code raw[start]} is the
   * StringAppendOperator structural delimiter (typically {@code 0x01}). The format thereafter
   * matches {@link ConcatBlobParser}'s operand-chain rules.
   *
   * <p>Returns an empty list if the trailing bytes don't parse as an operand chain (so callers
   * can fall back to raw-byte return on malformed input).
   */
  private static List<byte[]> parseTrailingOperandChain(byte[] raw, int start) {
    if (start >= raw.length) {
      return Collections.emptyList();
    }
    try {
      List<byte[]> operands = new ArrayList<>();
      int cursor = start;
      while (cursor < raw.length) {
        // The leading byte is a structural delimiter inserted by StringAppendOperator.
        cursor++;
        if (cursor >= raw.length) {
          return Collections.emptyList();
        }
        if (raw[cursor] != ConcatBlobParser.KIND_OPERAND) {
          return Collections.emptyList();
        }
        cursor++;
        ConcatBlobParser.VarintResult vlen = ConcatBlobParser.readVarint(raw, cursor);
        cursor = vlen.nextOffset;
        int opLen = vlen.value;
        if (opLen < 0 || cursor + opLen > raw.length) {
          return Collections.emptyList();
        }
        byte[] op = new byte[opLen];
        System.arraycopy(raw, cursor, op, 0, opLen);
        operands.add(op);
        cursor += opLen;
      }
      return operands;
    } catch (Exception e) {
      LOGGER.warn("MaterializingFraming.parseTrailingOperandChain: parse failed at offset {}", start, e);
      return Collections.emptyList();
    }
  }

  /**
   * Reassemble the full value bytes by fetching each chunk and concatenating the chunk content
   * (skipping each chunk's 4-byte schemaId header — same convention as
   * {@code ChunkedValueInputStream}). Validates the assembled size against the manifest's
   * declared size.
   */
  private static byte[] reassembleChunks(
      ChunkedValueManifest manifest,
      Function<byte[], byte[]> chunkFetchFn,
      String storeNameAndVersion) {
    int numChunks = manifest.keysWithChunkIdSuffix.size();
    byte[][] chunks = new byte[numChunks][];
    int totalSize = 0;
    for (int i = 0; i < numChunks; i++) {
      ByteBuffer chunkKey = manifest.keysWithChunkIdSuffix.get(i);
      byte[] chunkKeyBytes = ByteUtils.extractByteArray(chunkKey);
      byte[] chunkValue = chunkFetchFn.apply(chunkKeyBytes);
      if (chunkValue == null) {
        throw new VeniceException(
            "MaterializingFraming.reassembleChunks: chunk " + i + " of " + numChunks + " not found; storeVersion="
                + storeNameAndVersion);
      }
      if (chunkValue.length <= ByteUtils.SIZE_OF_INT) {
        throw new VeniceException(
            "MaterializingFraming.reassembleChunks: chunk " + i + " is too short (len=" + chunkValue.length
                + "); storeVersion=" + storeNameAndVersion);
      }
      chunks[i] = chunkValue;
      totalSize += chunkValue.length - ByteUtils.SIZE_OF_INT;
    }
    if (totalSize != manifest.size) {
      throw new VeniceException(
          "MaterializingFraming.reassembleChunks: assembled size " + totalSize + " != manifest.size " + manifest.size
              + "; storeVersion=" + storeNameAndVersion);
    }
    byte[] assembled = new byte[totalSize];
    int offset = 0;
    for (byte[] chunk: chunks) {
      int chunkContentLen = chunk.length - ByteUtils.SIZE_OF_INT;
      System.arraycopy(chunk, ByteUtils.SIZE_OF_INT, assembled, offset, chunkContentLen);
      offset += chunkContentLen;
    }
    return assembled;
  }

  /** Read fold for the materialized-base-plus-zero-or-more-operands case. */
  private static byte[] foldFramedBaseAndOperands(byte[] raw, String storeNameAndVersion) {
    return foldFramedBaseAndOperands(raw, storeNameAndVersion, null, null);
  }

  /** Read fold for the materialized-base-plus-zero-or-more-operands case, with RMD plumbing. */
  private static byte[] foldFramedBaseAndOperands(
      byte[] raw,
      String storeNameAndVersion,
      byte[] baseKey,
      Function<byte[], byte[]> rmdFetchFn) {
    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
    byte[] avroBase = parsed.getBase();
    List<byte[]> operands = parsed.getOperands();
    int schemaId = parsed.getSchemaId();
    if (schemaId == ConcatBlobParser.NO_SCHEMA_ID_PRESENT) {
      throw new VeniceException(
          "MaterializingFraming.foldFramedBaseAndOperands: parsed blob unexpectedly has no schemaId");
    }
    if (operands.isEmpty()) {
      return prependSchemaId(schemaId, avroBase);
    }
    MaterializingFoldContext ctx = MaterializingFoldContextRegistry.get(storeNameAndVersion);
    LOGGER.debug(
        "VT-merge materialize: storeVersion={} rawLen={} baseLen={} opCount={} ctxNull={}",
        storeNameAndVersion,
        raw.length,
        avroBase.length,
        operands.size(),
        ctx == null);
    if (ctx == null) {
      // No fold context registered. We have a parsed base — return [schemaId][avroBase]
      // (i.e. the unfolded base bytes) so readers see the last materialized state instead of
      // the raw concat blob with operand kind bytes that would fail Avro decoding.
      LOGGER.warn(
          "MaterializingFraming: no fold context registered for store-version {}; "
              + "returning unfolded base bytes (operands ignored)",
          storeNameAndVersion);
      return prependSchemaId(schemaId, avroBase);
    }
    byte[] baseRmd = (baseKey != null && rmdFetchFn != null) ? rmdFetchFn.apply(baseKey) : null;
    byte[] materializedAvro = ctx.foldOperands(schemaId, avroBase, operands, baseRmd);
    if (materializedAvro == null) {
      return null; // tombstone via WC delete
    }
    return prependSchemaId(schemaId, materializedAvro);
  }

  /** Read fold for the operand-only edge case (first write was a merge, no base on disk). */
  private static byte[] foldOperandOnly(byte[] raw, String storeNameAndVersion) {
    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
    if (parsed.hasBase()) {
      throw new VeniceException(
          "MaterializingFraming.foldOperandOnly: parsed blob unexpectedly has base; logic error in materialize()");
    }
    List<byte[]> operands = parsed.getOperands();
    MaterializingFoldContext ctx = MaterializingFoldContextRegistry.get(storeNameAndVersion);
    if (ctx == null) {
      // No fold context registered (e.g. ingestion task hasn't started yet, or store-version not
      // ingested by this server). The raw bytes are kind-byte framed and would fail downstream
      // Avro decoding if returned as-is. Returning null is the safest fallback — the read path
      // treats it as a key-not-found, which is correct (we don't have a usable materialized
      // value to return) and lets the test's wait-for-non-deterministic-assertion loop retry
      // until ingestion has caught up and the context is registered.
      LOGGER.warn(
          "MaterializingFraming: no fold context registered for store-version {} (operand-only blob); "
              + "returning null (treated as missing key)",
          storeNameAndVersion);
      return null;
    }
    MaterializingFoldContext.FoldOnlyResult result = ctx.foldOperandOnly(operands);
    if (result.getBytes() == null) {
      return null;
    }
    return prependSchemaId(result.getSchemaId(), result.getBytes());
  }

  private static byte[] prependSchemaId(int schemaId, byte[] avro) {
    byte[] out = new byte[ByteUtils.SIZE_OF_INT + avro.length];
    ByteUtils.writeInt(out, schemaId, 0);
    System.arraycopy(avro, 0, out, ByteUtils.SIZE_OF_INT, avro.length);
    return out;
  }
}
