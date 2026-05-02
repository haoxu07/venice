package com.linkedin.davinci.store.rocksdb.merge;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.utils.ByteUtils;
import java.nio.ByteBuffer;
import java.util.List;
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

  private MaterializingFraming() {
    // utility class
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
      // Chunk or manifest — never framed.
      return raw;
    }
    if (raw[ByteUtils.SIZE_OF_INT] != ConcatBlobParser.KIND_BASE) {
      // Not a framed materialized blob (legacy data, or a corrupted state). Return raw.
      return raw;
    }
    return foldFramedBaseAndOperands(raw, storeNameAndVersion);
  }

  /** Read fold for the materialized-base-plus-zero-or-more-operands case. */
  private static byte[] foldFramedBaseAndOperands(byte[] raw, String storeNameAndVersion) {
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
    if (ctx == null) {
      LOGGER.warn(
          "MaterializingFraming: no fold context registered for store-version {}; returning raw "
              + "concat-blob bytes (downstream readers will fail)",
          storeNameAndVersion);
      return raw;
    }
    byte[] materializedAvro = ctx.foldOperands(schemaId, avroBase, operands);
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
      LOGGER.warn(
          "MaterializingFraming: no fold context registered for store-version {} (operand-only blob); "
              + "returning raw bytes",
          storeNameAndVersion);
      return raw;
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
