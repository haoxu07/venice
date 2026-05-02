package com.linkedin.davinci.store.rocksdb;

import com.linkedin.davinci.stats.RocksDBMemoryStats;
import com.linkedin.davinci.store.StoragePartitionConfig;
import com.linkedin.davinci.store.rocksdb.merge.ConcatBlobParser;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContextRegistry;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.utils.ByteUtils;
import java.nio.ByteBuffer;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Subclass of {@link RocksDBStoragePartition} that wraps {@code put} and {@code merge} with the
 * VT-merge experiment's kind-byte framing, and folds concat blobs into materialized base bytes
 * on {@code get}.
 *
 * <h3>Wire format on disk (in this partition's underlying RocksDB)</h3>
 *
 * <p>Materialized record (from a {@code put}):
 * <pre>{@code [ schemaId : int32 BE ][ kind=0x00 : 1B ][ payload-len : varint ][ avro-base ] }</pre>
 *
 * <p>Operand record (from a {@code merge}):
 * <pre>{@code [ kind=0x01 : 1B ][ payload-len : varint ][ valueSchemaId : 4B BE ][ updateSchemaId : 4B BE ][ avro-WC-payload ] }</pre>
 *
 * <p>The two schema-id ints inside the operand framing are needed because the read-fold path
 * has to deserialize the operand bytes against the correct WC schema, and the leader's
 * {@code produceUpdateOperandToVT} forwards just the bytes — the schema ids would otherwise
 * be lost on the wire to RocksDB. The follower's {@code case UPDATE} prepends them via
 * {@link MaterializingFoldContext.OperandContent#frame(int, int, byte[])} before calling
 * {@code storageEngine.merge}.
 *
 * <p>RocksDB's {@code StringAppendOperator} concatenates successive {@code put}+{@code merge}
 * results with a single delimiter byte (whose value the parser is agnostic to). The
 * {@link ConcatBlobParser} walks the resulting blob using the kind bytes and varint lengths.
 *
 * <h3>Read fold</h3>
 *
 * <p>{@link #get(byte[])} uses the {@link MaterializingFoldContext} registered via
 * {@link MaterializingFoldContextRegistry} for this store-version to:
 * <ol>
 *   <li>parse the concat blob into {@code (base, operands[])},
 *   <li>deserialize the base avro bytes into a {@code GenericRecord},
 *   <li>apply each operand's WC payload via {@code WriteComputeProcessor.applyWriteCompute},
 *   <li>re-serialize the resulting record to Avro,
 *   <li>prepend the original {@code schemaId} so callers receive {@code [schemaId][avro]} —
 *       byte-equivalent shape to today's pre-experiment behavior.
 * </ol>
 *
 * <h3>Pass-through cases</h3>
 *
 * <ul>
 *   <li>Chunk writes ({@code schemaId == AvroProtocolDefinition.CHUNK.getCurrentProtocolVersion() == -10})
 *       — bypass framing: the chunk-reassembly path in {@code ChunkingUtils} expects raw chunk
 *       bytes with a 4-byte schemaId prefix.
 *   <li>Manifest writes ({@code schemaId == AvroProtocolDefinition.CHUNKED_VALUE_MANIFEST.getCurrentProtocolVersion() == -20})
 *       — bypass framing: the manifest is the user-visible value bytes for chunked records;
 *       framing it would corrupt the read path. Per {@code GOAL.md} §6 "the materializing wrapper
 *       must NOT touch the chunk-write path".
 *   <li>Reads of stored bytes whose first byte (after schemaId) is not {@code 0x00} and whose
 *       first byte is not {@code 0x01} — return raw, treating as legacy or non-framed data.
 * </ul>
 *
 * <p>Per the VT-merge experiment {@code GOAL.md} §4 Phase B.
 */
public class MaterializingRocksDBStoragePartition extends RocksDBStoragePartition {
  private static final Logger LOGGER = LogManager.getLogger(MaterializingRocksDBStoragePartition.class);

  private static final int CHUNK_SCHEMA_ID = AvroProtocolDefinition.CHUNK.getCurrentProtocolVersion();
  private static final int CHUNK_MANIFEST_SCHEMA_ID =
      AvroProtocolDefinition.CHUNKED_VALUE_MANIFEST.getCurrentProtocolVersion();

  public MaterializingRocksDBStoragePartition(
      StoragePartitionConfig storagePartitionConfig,
      RocksDBStorageEngineFactory factory,
      String dbDir,
      RocksDBMemoryStats rocksDBMemoryStats,
      RocksDBThrottler rocksDbThrottler,
      RocksDBServerConfig rocksDBServerConfig) {
    super(storagePartitionConfig, factory, dbDir, rocksDBMemoryStats, rocksDbThrottler, rocksDBServerConfig);
  }

  // -------- WRITE PATH --------

  @Override
  public synchronized void put(byte[] key, byte[] value) {
    put(key, ByteBuffer.wrap(value));
  }

  @Override
  public synchronized void put(byte[] key, ByteBuffer valueBuffer) {
    if (shouldBypassFraming(valueBuffer)) {
      super.put(key, valueBuffer);
      return;
    }
    // Wrap [schemaId][avro] -> [schemaId][0x00][len:varint][avro].
    byte[] framed = frameForPut(valueBuffer);
    super.put(key, ByteBuffer.wrap(framed));
  }

  @Override
  public synchronized void merge(byte[] key, ByteBuffer operand) {
    // The bytes in `operand` are the operand-content blob:
    //   [valueSchemaId : 4B BE][updateSchemaId : 4B BE][avro-WC-payload]
    // We frame them as [0x01][len:varint][operand-content] before calling rocksDB.merge.
    byte[] content = ByteUtils.extractByteArray(operand);
    byte[] framed = ConcatBlobParser.frameOperand(content);
    super.merge(key, ByteBuffer.wrap(framed));
  }

  // -------- READ PATH --------

  @Override
  public byte[] get(byte[] key) {
    byte[] raw = super.get(key);
    return materialize(raw);
  }

  @Override
  public ByteBuffer get(byte[] key, ByteBuffer valueToBePopulated) {
    // The base implementation reads via rocksDB.get(key, valueToBePopulated.array()) which won't
    // work for our framed bytes (downstream readers expect the materialized [schemaId][avro]
    // shape). We delegate to get(byte[]) and copy into the reusable buffer.
    byte[] raw = super.get(key);
    if (raw == null) {
      return null;
    }
    byte[] materialized = materialize(raw);
    if (materialized == null) {
      return null;
    }
    if (materialized.length > valueToBePopulated.capacity()) {
      valueToBePopulated = ByteBuffer.allocate(materialized.length);
    }
    valueToBePopulated.position(0);
    valueToBePopulated.put(materialized);
    valueToBePopulated.position(0);
    valueToBePopulated.limit(materialized.length);
    return valueToBePopulated;
  }

  @Override
  public byte[] get(ByteBuffer keyBuffer) {
    byte[] keyBytes = ByteUtils.extractByteArray(keyBuffer);
    byte[] raw = super.get(keyBytes);
    return materialize(raw);
  }

  /**
   * Bypass-the-fold accessor used by the sweeper (Phase D) to read the raw on-disk concat blob
   * without paying the materialization cost. Returns the same bytes that {@code rocksDB.get}
   * would return, including the kind-byte framing.
   */
  public byte[] getRaw(byte[] key) {
    return super.get(key);
  }

  // -------- INTERNAL HELPERS --------

  /**
   * Decide whether to bypass framing on a put. Bypass iff the value's schemaId is the special
   * chunk or manifest id (negative) — these are internal to chunking and must not be wrapped.
   */
  private static boolean shouldBypassFraming(ByteBuffer valueBuffer) {
    if (valueBuffer == null || valueBuffer.remaining() < ByteUtils.SIZE_OF_INT) {
      // Too small to even contain a schemaId — pass through.
      return true;
    }
    int schemaId = readIntFromBuffer(valueBuffer);
    return schemaId == CHUNK_SCHEMA_ID || schemaId == CHUNK_MANIFEST_SCHEMA_ID;
  }

  /** Read a 4-byte BE int starting at {@code valueBuffer.position()} without consuming it. */
  private static int readIntFromBuffer(ByteBuffer valueBuffer) {
    int p = valueBuffer.position();
    return (valueBuffer.get(p) & 0xff) << 24 | (valueBuffer.get(p + 1) & 0xff) << 16
        | (valueBuffer.get(p + 2) & 0xff) << 8 | (valueBuffer.get(p + 3) & 0xff);
  }

  /**
   * Build the framed put bytes: input shape {@code [schemaId][avro]} → output shape
   * {@code [schemaId][0x00][len:varint][avro]}.
   */
  private static byte[] frameForPut(ByteBuffer valueBuffer) {
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

  /**
   * Convert raw on-disk bytes (potentially a concat blob with framing) into the
   * {@code [schemaId][avro]} shape downstream Avro decoders expect. Returns {@code null} if
   * input is null. Returns input unchanged if it's a chunk/manifest (negative schemaId) or
   * otherwise non-framed.
   */
  private byte[] materialize(byte[] raw) {
    if (raw == null) {
      return null;
    }
    if (raw.length < 1) {
      return raw;
    }
    // Operand-only chain (no base yet): blob starts with 0x01 kind byte.
    if (raw[0] == ConcatBlobParser.KIND_OPERAND) {
      return foldOperandOnly(raw);
    }
    if (raw.length < ByteUtils.SIZE_OF_INT + 1) {
      // Too short for a framed materialized blob; return raw.
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
    return foldFramedBaseAndOperands(raw, schemaId);
  }

  /**
   * Read fold for the materialized-base-plus-zero-or-more-operands case.
   *
   * @param raw the on-disk blob
   * @param expectedSchemaId pre-parsed schemaId from the blob's first 4 bytes (used in the no-op
   *     case to short-circuit re-encoding)
   */
  private byte[] foldFramedBaseAndOperands(byte[] raw, int expectedSchemaId) {
    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
    byte[] avroBase = parsed.getBase();
    List<byte[]> operands = parsed.getOperands();
    int schemaId = parsed.getSchemaId();
    if (schemaId == ConcatBlobParser.NO_SCHEMA_ID_PRESENT) {
      throw new VeniceException(
          "MaterializingRocksDBStoragePartition.foldFramedBaseAndOperands: parsed blob unexpectedly has no schemaId");
    }
    if (operands.isEmpty()) {
      // Materialized only — return [schemaId][avro] shape directly.
      return prependSchemaId(schemaId, avroBase);
    }
    MaterializingFoldContext ctx = MaterializingFoldContextRegistry.get(storeNameAndVersion);
    if (ctx == null) {
      // Best-effort: log and return raw. Downstream avro decoding will fail loudly, surfacing
      // the missing registration. For sweeper read paths (which call getRaw) this branch is
      // never hit.
      LOGGER.warn(
          "MaterializingRocksDBStoragePartition: no fold context registered for store-version {}, "
              + "returning raw concat-blob bytes (downstream readers will fail)",
          storeNameAndVersion);
      return raw;
    }
    byte[] materializedAvro = ctx.foldOperands(schemaId, avroBase, operands);
    if (materializedAvro == null) {
      // Folded to a tombstone (write-compute delete). Return null so callers see "key absent".
      return null;
    }
    return prependSchemaId(schemaId, materializedAvro);
  }

  /** Read fold for the operand-only edge case (first write was a merge, no base on disk). */
  private byte[] foldOperandOnly(byte[] raw) {
    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
    if (parsed.hasBase()) {
      throw new VeniceException(
          "MaterializingRocksDBStoragePartition.foldOperandOnly: parsed blob unexpectedly has base; "
              + "logic error in materialize()");
    }
    List<byte[]> operands = parsed.getOperands();
    MaterializingFoldContext ctx = MaterializingFoldContextRegistry.get(storeNameAndVersion);
    if (ctx == null) {
      LOGGER.warn(
          "MaterializingRocksDBStoragePartition: no fold context registered for store-version {} "
              + "(operand-only blob); returning raw bytes",
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
