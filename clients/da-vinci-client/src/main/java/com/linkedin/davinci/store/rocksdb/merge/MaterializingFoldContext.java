package com.linkedin.davinci.store.rocksdb.merge;

import com.linkedin.davinci.kafka.consumer.StoreWriteComputeProcessor;
import com.linkedin.davinci.kafka.consumer.WriteComputeResult;
import com.linkedin.davinci.replication.rmdcache.field.FieldLevelRmdCache;
import com.linkedin.davinci.replication.rmdcache.field.FieldRmdEntry;
import com.linkedin.davinci.schema.merge.ValueAndRmd;
import com.linkedin.davinci.serializer.avro.MapOrderPreservingSerDeFactory;
import com.linkedin.davinci.serializer.avro.fast.MapOrderPreservingFastSerDeFactory;
import com.linkedin.venice.compression.VeniceCompressor;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.serializer.RecordDeserializer;
import com.linkedin.venice.utils.collections.BiIntKeyCache;
import com.linkedin.venice.utils.lazy.Lazy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Per-store context the {@link com.linkedin.davinci.store.rocksdb.MaterializingRocksDBStoragePartition}
 * uses to fold a concat-blob into a materialized base record on read.
 *
 * <p>Holds:
 * <ul>
 *   <li>a {@link ReadOnlySchemaRepository} for value-schema and write-compute-schema lookup
 *   <li>a {@link StoreWriteComputeProcessor} for applying WC operands to a base record
 *   <li>per-schemaId Avro deserializer cache for the base value bytes
 * </ul>
 *
 * <p>Lifecycle: created and {@link MaterializingFoldContextRegistry#register registered} by an
 * ingestion task when it first opens a store-version's storage; unregistered when the store-
 * version is closed. The storage partition looks up the context via the registry on each
 * {@code get} call. If no context is registered (e.g. during boot, or for store-versions where
 * no ingestion task is running), the storage partition falls back to returning raw bytes.
 *
 * <p>Per the VT-merge experiment {@code GOAL.md} §4 Phase B + §6.
 */
public final class MaterializingFoldContext {
  private static final Logger LOGGER = LogManager.getLogger(MaterializingFoldContext.class);
  private final String storeName;
  private final ReadOnlySchemaRepository schemaRepository;
  private final StoreWriteComputeProcessor wcProcessor;
  private final boolean fastAvroEnabled;
  /**
   * Compressor for this store-version's value bytes. May be {@code null} for tests or NO_OP
   * stores that don't compress at write time. When non-null, base value bytes are decompressed
   * before deserialization, and folded value bytes are recompressed before being returned.
   */
  private final Lazy<VeniceCompressor> compressor;

  /** Cache for value-schema deserializers, keyed by (writerSchemaId, readerSchemaId). */
  private final BiIntKeyCache<RecordDeserializer<GenericRecord>> valueDeserializerCache;

  public MaterializingFoldContext(
      String storeName,
      ReadOnlySchemaRepository schemaRepository,
      StoreWriteComputeProcessor wcProcessor,
      boolean fastAvroEnabled) {
    this(storeName, schemaRepository, wcProcessor, fastAvroEnabled, null);
  }

  public MaterializingFoldContext(
      String storeName,
      ReadOnlySchemaRepository schemaRepository,
      StoreWriteComputeProcessor wcProcessor,
      boolean fastAvroEnabled,
      Lazy<VeniceCompressor> compressor) {
    this.storeName = storeName;
    this.schemaRepository = schemaRepository;
    this.wcProcessor = wcProcessor;
    this.fastAvroEnabled = fastAvroEnabled;
    this.compressor = compressor;
    this.valueDeserializerCache = new BiIntKeyCache<>((writerId, readerId) -> {
      Schema writer = schemaRepository.getValueSchema(storeName, writerId).getSchema();
      Schema reader = schemaRepository.getValueSchema(storeName, readerId).getSchema();
      if (this.fastAvroEnabled) {
        return MapOrderPreservingFastSerDeFactory.getDeserializer(writer, reader);
      }
      return MapOrderPreservingSerDeFactory.getDeserializer(writer, reader);
    });
  }

  /**
   * Apply the operand chain to the base value bytes. Returns the materialized value bytes (Avro
   * encoded, no schema-id prefix). Caller is responsible for prepending the schemaId header.
   *
   * @param baseSchemaId schema id the base bytes were encoded under
   * @param baseValueBytes Avro-encoded base value (no schema-id prefix)
   * @param framedOperands list of operand-content blobs in arrival order; each blob is structured
   *     {@code [valueSchemaId : 4B BE][updateSchemaId : 4B BE][avro-WC-payload]}
   * @return Avro-encoded materialized value (no schema-id prefix)
   */
  public byte[] foldOperands(int baseSchemaId, byte[] baseValueBytes, List<byte[]> framedOperands) {
    if (framedOperands == null || framedOperands.isEmpty()) {
      return baseValueBytes;
    }
    long foldStartNs = System.nanoTime();
    // Decompress the base bytes if a compressor is configured (the base was written through the
    // store's compression strategy at batch-push or merge-conflict-fold time).
    byte[] decompressedBase = decompressBase(baseValueBytes);
    GenericRecord curr = deserializeValue(baseSchemaId, baseSchemaId, decompressedBase);
    byte[] lastUncompressed = decompressedBase;
    // Phase 3 (cross-DC fix): build the seed RMD ONCE from the base value record, honoring
    // Gotchas #1 and #2 (empty collections / scalar-at-schema-default → no DCR floor). Then
    // persist the RMD across operands so each operand's RMD mutation (e.g. list element ts
    // updates) is visible to the next operand. Use applyWriteComputeV2WithExternalRmd to plumb
    // the same ValueAndRmd container through the loop.
    Schema valueSchema = wcProcessor.getReaderValueSchema(baseSchemaId);
    Schema rmdSchema = wcProcessor.getReaderRmdSchema(baseSchemaId);
    Map<Integer, FieldRmdEntry> seedEntries =
        FieldLevelRmdCache.synthesizeFromPut(curr, valueSchema, 0L /* unknown PUT ts */);
    GenericRecord seedRmd = buildSeedRmdRecord(rmdSchema, valueSchema, seedEntries);
    final GenericRecord initialCurr = curr;
    ValueAndRmd<GenericRecord> valueAndRmd = new ValueAndRmd<>(Lazy.of(() -> initialCurr), seedRmd);
    long fallbackCounter = 0L;
    for (byte[] op: framedOperands) {
      OperandContent c = OperandContent.parse(op);
      fallbackCounter++;
      long modifyTs = (c.updateOperationTimestamp != OperandContent.NO_TIMESTAMP_AVAILABLE)
          ? c.updateOperationTimestamp
          : fallbackCounter;
      WriteComputeResult result = wcProcessor.applyWriteComputeV2WithExternalRmd(
          valueAndRmd,
          c.valueSchemaId,
          baseSchemaId,
          ByteBuffer.wrap(c.payload),
          c.updateSchemaId,
          c.updateSchemaId,
          modifyTs);
      curr = result.getUpdatedValue();
      if (curr == null) {
        // WC delete: return null tombstone bytes
        return null;
      }
      lastUncompressed = result.getUpdatedValueBytes();
      // The V2 algorithm mutates valueAndRmd in place; ensure the value tracked in the
      // container is the updated value so the next operand sees it.
      valueAndRmd.setValue(curr);
    }
    byte[] compressed = compressFolded(lastUncompressed);
    LOGGER.debug(
        "[TIMING-FOLD] foldOperands wallMs={} chainDepth={} baseBytesIn={} baseBytesDecompressed={} finalBytesUncompressed={} finalBytesCompressed={}",
        (System.nanoTime() - foldStartNs) / 1_000_000.0,
        framedOperands.size(),
        baseValueBytes == null ? -1 : baseValueBytes.length,
        decompressedBase == null ? -1 : decompressedBase.length,
        lastUncompressed == null ? -1 : lastUncompressed.length,
        compressed == null ? -1 : compressed.length);
    return compressed;
  }

  /**
   * Build the seed RMD record for the fold path. Returns an RMD record with the per-field-ts
   * branch selected, where each field's per-field-ts entry is derived from the base record
   * (Gotchas #1/#2 from GOAL.md §4.2/§4.3 applied via {@link FieldLevelRmdCache#synthesizeFromPut}).
   */
  private GenericRecord buildSeedRmdRecord(
      Schema rmdSchema,
      Schema valueSchema,
      Map<Integer, FieldRmdEntry> entriesByOrdinal) {
    GenericRecord perFieldTs = FieldLevelRmdCache.buildPerFieldTsRecord(rmdSchema, valueSchema, entriesByOrdinal);
    GenericRecord rmd = new org.apache.avro.generic.GenericData.Record(rmdSchema);
    rmd.put(com.linkedin.venice.schema.rmd.RmdConstants.TIMESTAMP_FIELD_NAME, perFieldTs);
    rmd.put(
        com.linkedin.venice.schema.rmd.RmdConstants.REPLICATION_CHECKPOINT_VECTOR_FIELD_NAME,
        java.util.Collections.emptyList());
    return rmd;
  }

  /**
   * Apply operands against an empty/default base when no base record is yet on disk. Used for
   * the operand-only edge case (key was first written via Merge before any Put).
   *
   * @param framedOperands list of operand-content blobs (see {@link #foldOperands})
   * @return (resolvedSchemaId, materialized value bytes) so the caller can frame with the
   *     schemaId. Returns null bytes for a WC-delete tombstone.
   */
  public FoldOnlyResult foldOperandOnly(List<byte[]> framedOperands) {
    if (framedOperands == null || framedOperands.isEmpty()) {
      throw new VeniceException("foldOperandOnly called with empty operand list");
    }
    long foldStartNs = System.nanoTime();
    // Use the first operand's value-schema-id as the resolution rule (per GOAL.md §6 "use the
    // latest WC schema id from the schema repo" — but the operand carries its own schema id, so
    // we use that).
    OperandContent firstOp = OperandContent.parse(framedOperands.get(0));
    int valueSchemaId = firstOp.valueSchemaId;

    // Build seed RMD against the value schema. No base record (operand-only), so all per-field
    // entries are "not populated by PUT" — operands win freely (Gotchas #1/#2 both apply).
    Schema valueSchema = wcProcessor.getReaderValueSchema(valueSchemaId);
    Schema rmdSchema = wcProcessor.getReaderRmdSchema(valueSchemaId);
    Map<Integer, FieldRmdEntry> seedEntries = FieldLevelRmdCache.synthesizeFromPut(null, valueSchema, 0L);
    GenericRecord seedRmd = buildSeedRmdRecord(rmdSchema, valueSchema, seedEntries);
    ValueAndRmd<GenericRecord> valueAndRmd = new ValueAndRmd<>(Lazy.of(() -> null), seedRmd);

    GenericRecord curr = null;
    int lastSchemaId = valueSchemaId;
    byte[] lastBytes = null;
    long fallbackCounter = 0L;
    for (byte[] op: framedOperands) {
      OperandContent c = OperandContent.parse(op);
      fallbackCounter++;
      long modifyTs = (c.updateOperationTimestamp != OperandContent.NO_TIMESTAMP_AVAILABLE)
          ? c.updateOperationTimestamp
          : fallbackCounter;
      WriteComputeResult result = wcProcessor.applyWriteComputeV2WithExternalRmd(
          valueAndRmd,
          c.valueSchemaId,
          valueSchemaId,
          ByteBuffer.wrap(c.payload),
          c.updateSchemaId,
          c.updateSchemaId,
          modifyTs);
      curr = result.getUpdatedValue();
      if (curr == null) {
        return new FoldOnlyResult(valueSchemaId, null);
      }
      lastBytes = result.getUpdatedValueBytes();
      valueAndRmd.setValue(curr);
    }
    byte[] compressed = compressFolded(lastBytes);
    LOGGER.debug(
        "[TIMING-FOLD-ONLY] foldOperandOnly wallMs={} chainDepth={} finalBytesUncompressed={} finalBytesCompressed={}",
        (System.nanoTime() - foldStartNs) / 1_000_000.0,
        framedOperands.size(),
        lastBytes == null ? -1 : lastBytes.length,
        compressed == null ? -1 : compressed.length);
    return new FoldOnlyResult(lastSchemaId, compressed);
  }

  /** Decompress the base bytes if a compressor is configured. */
  private byte[] decompressBase(byte[] baseValueBytes) {
    if (compressor == null) {
      return baseValueBytes;
    }
    VeniceCompressor c = compressor.get();
    if (c == null) {
      return baseValueBytes;
    }
    try {
      ByteBuffer decompressed = c.decompress(baseValueBytes, 0, baseValueBytes.length);
      byte[] out = new byte[decompressed.remaining()];
      decompressed.get(out);
      return out;
    } catch (IOException e) {
      throw new VeniceException("Failed to decompress base bytes during fold", e);
    }
  }

  /** Re-compress the folded bytes if a compressor is configured. */
  private byte[] compressFolded(byte[] foldedBytes) {
    if (foldedBytes == null) {
      return null;
    }
    if (compressor == null) {
      return foldedBytes;
    }
    VeniceCompressor c = compressor.get();
    if (c == null) {
      return foldedBytes;
    }
    try {
      ByteBuffer compressed = c.compress(ByteBuffer.wrap(foldedBytes), 0);
      // Compress may return a buffer with content starting at position 0; extract.
      byte[] out = new byte[compressed.remaining()];
      compressed.get(out);
      return out;
    } catch (IOException e) {
      throw new VeniceException("Failed to compress folded bytes during fold", e);
    }
  }

  /**
   * Deserialize Avro value bytes (no schema-id prefix) into a {@link GenericRecord} using the
   * cached deserializer for the schema-id pair.
   */
  GenericRecord deserializeValue(int writerSchemaId, int readerSchemaId, byte[] avroBytes) {
    return valueDeserializerCache.get(writerSchemaId, readerSchemaId).deserialize(avroBytes);
  }

  public String getStoreName() {
    return storeName;
  }

  /**
   * Result of folding an operand-only chain: the schema id under which the materialized record is
   * encoded, plus the bytes (or null if folded to a tombstone).
   */
  public static final class FoldOnlyResult {
    private final int schemaId;
    private final byte[] bytes;

    FoldOnlyResult(int schemaId, byte[] bytes) {
      this.schemaId = schemaId;
      this.bytes = bytes;
    }

    public int getSchemaId() {
      return schemaId;
    }

    public byte[] getBytes() {
      return bytes;
    }
  }

  /**
   * Wire format inside an operand "content" blob (the bytes wrapped by
   * {@link ConcatBlobParser}'s {@code [0x01][len:varint]} framing):
   * {@code [valueSchemaId : 4B BE][updateSchemaId : 4B BE][updateOperationTimestamp : 8B BE][avro-WC-payload]}.
   *
   * <p>The follower's UPDATE-consume path (in {@code StoreIngestionTask}) prepends the two
   * schema-id ints and the operand's real {@code updateOperationTimestamp} before calling
   * {@code storageEngine.merge(...)}. This carries the schema identity AND the operand's
   * logical timestamp through the on-disk representation so the read-fold path can:
   * <ol>
   *   <li>deserialize the operand against the correct WC schema, and</li>
   *   <li>pass the real operand timestamp to the V2 algorithm for cross-DC per-field DCR.</li>
   * </ol>
   *
   * <p>{@code updateOperationTimestamp} is the operand's logical timestamp — either the user-
   * specified value (when the producer used {@code VeniceObjectWithTimestamp}) or the wall-
   * clock {@code producerMetadata.messageTimestamp} otherwise. {@link #NO_TIMESTAMP_AVAILABLE}
   * is reserved as a "use chain-position fallback" sentinel for callers that don't have a
   * timestamp (e.g. unit-test scaffolding, legacy operands).
   */
  public static final class OperandContent {
    /**
     * Sentinel for callers that have no operand timestamp available (e.g. unit-test fixtures
     * that don't go through a real producer path). When this sentinel is present, the fold
     * path should fall back to the chain-position counter to preserve pre-Phase-A semantics.
     */
    public static final long NO_TIMESTAMP_AVAILABLE = Long.MIN_VALUE;

    /** Header size in bytes: 4 (valueSchemaId) + 4 (updateSchemaId) + 8 (updateOperationTimestamp) = 16. */
    public static final int HEADER_LENGTH = 16;

    public final int valueSchemaId;
    public final int updateSchemaId;
    public final long updateOperationTimestamp;
    public final byte[] payload;

    public OperandContent(int valueSchemaId, int updateSchemaId, long updateOperationTimestamp, byte[] payload) {
      this.valueSchemaId = valueSchemaId;
      this.updateSchemaId = updateSchemaId;
      this.updateOperationTimestamp = updateOperationTimestamp;
      this.payload = payload;
    }

    public static OperandContent parse(byte[] content) {
      if (content == null || content.length < HEADER_LENGTH) {
        throw new VeniceException("OperandContent: too short (" + (content == null ? 0 : content.length) + " bytes)");
      }
      int valueSchemaId =
          ((content[0] & 0xff) << 24) | ((content[1] & 0xff) << 16) | ((content[2] & 0xff) << 8) | (content[3] & 0xff);
      int updateSchemaId =
          ((content[4] & 0xff) << 24) | ((content[5] & 0xff) << 16) | ((content[6] & 0xff) << 8) | (content[7] & 0xff);
      long updateOpTs = ((long) (content[8] & 0xff) << 56) | ((long) (content[9] & 0xff) << 48)
          | ((long) (content[10] & 0xff) << 40) | ((long) (content[11] & 0xff) << 32)
          | ((long) (content[12] & 0xff) << 24) | ((long) (content[13] & 0xff) << 16)
          | ((long) (content[14] & 0xff) << 8) | ((long) (content[15] & 0xff));
      byte[] payload = new byte[content.length - HEADER_LENGTH];
      System.arraycopy(content, HEADER_LENGTH, payload, 0, payload.length);
      return new OperandContent(valueSchemaId, updateSchemaId, updateOpTs, payload);
    }

    /**
     * Build operand content bytes with explicit timestamp. Production callers should use this
     * overload and pass the operand's real logical timestamp (from
     * {@code KafkaMessageEnvelope.producerMetadata.logicalTimestamp} with a fallback to
     * {@code messageTimestamp}).
     */
    public static byte[] frame(int valueSchemaId, int updateSchemaId, long updateOperationTimestamp, byte[] payload) {
      byte[] out = new byte[HEADER_LENGTH + payload.length];
      out[0] = (byte) (valueSchemaId >>> 24);
      out[1] = (byte) (valueSchemaId >>> 16);
      out[2] = (byte) (valueSchemaId >>> 8);
      out[3] = (byte) valueSchemaId;
      out[4] = (byte) (updateSchemaId >>> 24);
      out[5] = (byte) (updateSchemaId >>> 16);
      out[6] = (byte) (updateSchemaId >>> 8);
      out[7] = (byte) updateSchemaId;
      out[8] = (byte) (updateOperationTimestamp >>> 56);
      out[9] = (byte) (updateOperationTimestamp >>> 48);
      out[10] = (byte) (updateOperationTimestamp >>> 40);
      out[11] = (byte) (updateOperationTimestamp >>> 32);
      out[12] = (byte) (updateOperationTimestamp >>> 24);
      out[13] = (byte) (updateOperationTimestamp >>> 16);
      out[14] = (byte) (updateOperationTimestamp >>> 8);
      out[15] = (byte) updateOperationTimestamp;
      System.arraycopy(payload, 0, out, HEADER_LENGTH, payload.length);
      return out;
    }

    /**
     * Backward-compatible overload for callers (mostly unit tests) that have no timestamp
     * available. Frames with {@link #NO_TIMESTAMP_AVAILABLE} so the fold path falls back to
     * the chain-position counter.
     */
    public static byte[] frame(int valueSchemaId, int updateSchemaId, byte[] payload) {
      return frame(valueSchemaId, updateSchemaId, NO_TIMESTAMP_AVAILABLE, payload);
    }
  }
}
