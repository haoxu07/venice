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
    return foldOperands(baseSchemaId, baseValueBytes, framedOperands, null);
  }

  /**
   * Variant of {@link #foldOperands(int, byte[], List)} that accepts the base record's on-disk
   * RMD bytes (with the 4-byte valueSchemaId prefix, as returned by
   * {@code ReplicationMetadataRocksDBStoragePartition.getReplicationMetadata}). When non-null,
   * the fold path uses this RMD as the seed for the V2 algorithm — preserving cross-DC
   * per-field-ts DCR semantics from prior PUT/DELETE operations.
   *
   * <p>When {@code baseRmdBytes} is null, falls back to seeding from the base value record (which
   * cannot honor a non-zero DCR floor since the PUT ts is unknown without RMD).
   *
   * <p>Returns bytes encoded with the BASE schema id, not the latest schema id in the chain. Use
   * {@link #foldOperandsAndReturnSchemaId} if the caller needs the schemaId the result is encoded
   * under (relevant for schema-evolution cases where the chain contains operands with newer value
   * schemas than the base).
   */
  public byte[] foldOperands(
      int baseSchemaId,
      byte[] baseValueBytes,
      List<byte[]> framedOperands,
      byte[] baseRmdBytes) {
    FoldResult r = foldOperandsAndReturnSchemaId(baseSchemaId, baseValueBytes, framedOperands, baseRmdBytes);
    if (r == null) {
      return null;
    }
    return r.bytes;
  }

  /** As {@link #foldOperands(int, byte[], List, byte[])} but returns both bytes + schemaId. */
  public FoldResult foldOperandsAndReturnSchemaId(
      int baseSchemaId,
      byte[] baseValueBytes,
      List<byte[]> framedOperands,
      byte[] baseRmdBytes) {
    if (framedOperands == null || framedOperands.isEmpty()) {
      return new FoldResult(baseSchemaId, baseValueBytes);
    }
    long foldStartNs = System.nanoTime();
    // Schema evolution: the chain may contain operands with newer value schemas than the base.
    // Use the latest schema id (across base + all operands) as the READER so the result record
    // includes any newly-added fields. The base value is deserialized with the reader schema so
    // Avro automatically backfills new fields with their schema defaults.
    int readerSchemaId = baseSchemaId;
    for (byte[] op: framedOperands) {
      OperandContent c = OperandContent.parse(op);
      if (c.valueSchemaId > readerSchemaId) {
        readerSchemaId = c.valueSchemaId;
      }
    }
    // Decompress the base bytes if a compressor is configured (the base was written through the
    // store's compression strategy at batch-push or merge-conflict-fold time).
    byte[] decompressedBase = decompressBase(baseValueBytes);
    GenericRecord curr = deserializeValue(baseSchemaId, readerSchemaId, decompressedBase);
    byte[] lastUncompressed = decompressedBase;
    // Phase 3 (cross-DC fix): build the seed RMD ONCE from the base value record + on-disk RMD,
    // honoring Gotchas #1 and #2. Then persist the RMD across operands so each operand's RMD
    // mutation (e.g. list element ts updates) is visible to the next operand. Use
    // applyWriteComputeV2WithExternalRmd to plumb the same ValueAndRmd container through the loop.
    Schema valueSchema = wcProcessor.getReaderValueSchema(readerSchemaId);
    Schema rmdSchema = wcProcessor.getReaderRmdSchema(readerSchemaId);
    GenericRecord seedRmd = buildSeedRmdFromBaseAndOnDiskRmd(curr, valueSchema, rmdSchema, baseRmdBytes);
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
          readerSchemaId,
          ByteBuffer.wrap(c.payload),
          c.updateSchemaId,
          c.updateSchemaId,
          modifyTs);
      curr = result.getUpdatedValue();
      if (curr == null) {
        // WC delete: return null tombstone bytes
        return new FoldResult(readerSchemaId, null);
      }
      lastUncompressed = result.getUpdatedValueBytes();
      // The V2 algorithm mutates valueAndRmd in place; ensure the value tracked in the
      // container is the updated value so the next operand sees it.
      valueAndRmd.setValue(curr);
    }
    // result.getUpdatedValueBytes() is already serialized under readerSchemaId by V2 (which uses
    // the readerValueSchemaId for the result serializer), so no extra re-serialization needed.
    byte[] compressed = compressFolded(lastUncompressed);
    LOGGER.debug(
        "[TIMING-FOLD] foldOperands wallMs={} chainDepth={} baseBytesIn={} baseBytesDecompressed={} finalBytesUncompressed={} finalBytesCompressed={}",
        (System.nanoTime() - foldStartNs) / 1_000_000.0,
        framedOperands.size(),
        baseValueBytes == null ? -1 : baseValueBytes.length,
        decompressedBase == null ? -1 : decompressedBase.length,
        lastUncompressed == null ? -1 : lastUncompressed.length,
        compressed == null ? -1 : compressed.length);
    return new FoldResult(readerSchemaId, compressed);
  }

  /** Result of a fold: the schemaId the bytes are encoded under + the bytes themselves. */
  public static final class FoldResult {
    public final int schemaId;
    public final byte[] bytes;

    public FoldResult(int schemaId, byte[] bytes) {
      this.schemaId = schemaId;
      this.bytes = bytes;
    }
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
   * Build a seed RMD for V2 fold. Strategy:
   *
   * <ol>
   *   <li>If {@code baseRmdBytes} is non-null and parseable, decode it. If it has a per-field-ts
   *       RMD (mode 1), use it directly.</li>
   *   <li>If {@code baseRmdBytes} has whole-record-ts (mode 0), convert to mode 1 with each
   *       field's topLevelFieldTs set to the whole-record-ts, but applying Gotcha #1 (empty
   *       collection → MIN_VALUE) and Gotcha #2 (scalar at default → MIN_VALUE).</li>
   *   <li>If {@code baseRmdBytes} is null, fall back to synthesis from base value with PUT ts=0
   *       (no DCR floor on non-default fields; conservative).</li>
   * </ol>
   */
  private GenericRecord buildSeedRmdFromBaseAndOnDiskRmd(
      GenericRecord baseValueRecord,
      Schema valueSchema,
      Schema rmdSchema,
      byte[] baseRmdBytes) {
    if (baseRmdBytes != null && baseRmdBytes.length > 4) {
      try {
        GenericRecord onDiskRmd = deserializeOnDiskRmd(baseRmdBytes, rmdSchema);
        if (onDiskRmd != null) {
          Object tsObj = onDiskRmd.get(com.linkedin.venice.schema.rmd.RmdConstants.TIMESTAMP_FIELD_POS);
          if (tsObj instanceof GenericRecord) {
            // Already in per-field-ts mode 1. Apply Gotcha #1 to empty collection fields whose
            // topLevelFieldTs is non-zero: lower it to Long.MIN_VALUE so subsequent UPDATEs win.
            applyGotchaOneToCollectionFields((GenericRecord) tsObj, valueSchema, baseValueRecord);
            return onDiskRmd;
          } else if (tsObj instanceof Long) {
            // Mode 0: whole-record-ts. Convert to per-field with Gotcha #1/#2.
            long wholeRecordTs = (Long) tsObj;
            return convertWholeRecordTsToPerField(baseValueRecord, valueSchema, rmdSchema, wholeRecordTs);
          }
        }
      } catch (Exception e) {
        LOGGER.warn("VT-merge: failed to deserialize on-disk RMD, falling back to synthesis", e);
      }
    }
    // Fallback: synthesize from base value with PUT ts=0.
    Map<Integer, FieldRmdEntry> seedEntries = FieldLevelRmdCache.synthesizeFromPut(baseValueRecord, valueSchema, 0L);
    return buildSeedRmdRecord(rmdSchema, valueSchema, seedEntries);
  }

  /**
   * Convert a mode-0 (whole-record-ts) RMD into a mode-1 (per-field-ts) RMD where each field's
   * per-field-ts is the whole-record-ts, except: empty collection fields (Gotcha #1) and scalar
   * fields at schema default (Gotcha #2) get topLevelFieldTs=Long.MIN_VALUE.
   */
  private GenericRecord convertWholeRecordTsToPerField(
      GenericRecord baseValueRecord,
      Schema valueSchema,
      Schema rmdSchema,
      long wholeRecordTs) {
    Map<Integer, FieldRmdEntry> seedEntries =
        FieldLevelRmdCache.synthesizeFromPut(baseValueRecord, valueSchema, wholeRecordTs);
    return buildSeedRmdRecord(rmdSchema, valueSchema, seedEntries);
  }

  /**
   * Apply Gotcha #1 to a per-field-ts RMD that arrived from disk: if a collection field's value
   * is empty in the base record but its RMD has a non-MIN_VALUE topLevelFieldTs, lower the
   * topLevelFieldTs to {@link Long#MIN_VALUE}. This handles the case where a prior PUT set an
   * empty collection (e.g. via WC PUT_NEW_FIELD with []) and the RMD reflects that PUT's ts —
   * leaving it in place would block subsequent UPDATEs from adding elements.
   */
  private void applyGotchaOneToCollectionFields(
      GenericRecord perFieldTsRecord,
      Schema valueSchema,
      GenericRecord baseValueRecord) {
    if (baseValueRecord == null) {
      return;
    }
    for (Schema.Field valueField: valueSchema.getFields()) {
      Object value = baseValueRecord.get(valueField.name());
      if (value == null) {
        continue;
      }
      Object fieldRmd = perFieldTsRecord.get(valueField.name());
      if (!(fieldRmd instanceof GenericRecord)) {
        continue;
      }
      Schema fieldSchema = FieldLevelRmdCache.unwrapNullable(valueField.schema());
      Schema.Type type = fieldSchema.getType();
      if (type == Schema.Type.ARRAY) {
        if (((java.util.Collection<?>) value).isEmpty()) {
          ((GenericRecord) fieldRmd)
              .put(com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME, Long.MIN_VALUE);
        }
      } else if (type == Schema.Type.MAP) {
        if (((java.util.Map<?, ?>) value).isEmpty()) {
          ((GenericRecord) fieldRmd)
              .put(com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME, Long.MIN_VALUE);
        }
      }
    }
  }

  /**
   * Deserialize on-disk RMD bytes (with 4-byte valueSchemaId prefix) into a GenericRecord using
   * the supplied RMD schema. Returns null on parse failure (shape mismatch, etc.).
   */
  private GenericRecord deserializeOnDiskRmd(byte[] rmdBytesWithSchemaIdPrefix, Schema rmdSchema) {
    try {
      // Skip the 4-byte schemaId prefix; deserialize the rest as the RMD record.
      java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(rmdBytesWithSchemaIdPrefix);
      buf.position(4);
      org.apache.avro.io.BinaryDecoder decoder =
          org.apache.avro.io.DecoderFactory.get().binaryDecoder(buf.array(), buf.position(), buf.remaining(), null);
      org.apache.avro.generic.GenericDatumReader<GenericRecord> reader =
          new org.apache.avro.generic.GenericDatumReader<>(rmdSchema, rmdSchema);
      return reader.read(null, decoder);
    } catch (IOException e) {
      LOGGER.warn("VT-merge: deserializeOnDiskRmd failed", e);
      return null;
    }
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
    return foldOperandOnly(framedOperands, null);
  }

  /**
   * Variant of {@link #foldOperandOnly(List)} that accepts on-disk RMD bytes for the
   * operand-only case (no base record yet). Typically baseRmdBytes will be null in this case
   * but for completeness we accept it.
   */
  public FoldOnlyResult foldOperandOnly(List<byte[]> framedOperands, byte[] baseRmdBytes) {
    if (framedOperands == null || framedOperands.isEmpty()) {
      throw new VeniceException("foldOperandOnly called with empty operand list");
    }
    long foldStartNs = System.nanoTime();
    // Schema evolution: use the LATEST schema id across all operands as the reader, so the
    // result record has any newly-added fields.
    int valueSchemaId = -1;
    for (byte[] op: framedOperands) {
      OperandContent c = OperandContent.parse(op);
      if (c.valueSchemaId > valueSchemaId) {
        valueSchemaId = c.valueSchemaId;
      }
    }
    if (valueSchemaId == -1) {
      OperandContent firstOp = OperandContent.parse(framedOperands.get(0));
      valueSchemaId = firstOp.valueSchemaId;
    }

    // Build seed RMD against the value schema. No base record (operand-only), so all per-field
    // entries are "not populated by PUT" — operands win freely (Gotchas #1/#2 both apply). If an
    // on-disk RMD is provided (rare for operand-only), use it.
    Schema valueSchema = wcProcessor.getReaderValueSchema(valueSchemaId);
    Schema rmdSchema = wcProcessor.getReaderRmdSchema(valueSchemaId);
    GenericRecord seedRmd = buildSeedRmdFromBaseAndOnDiskRmd(null, valueSchema, rmdSchema, baseRmdBytes);
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
