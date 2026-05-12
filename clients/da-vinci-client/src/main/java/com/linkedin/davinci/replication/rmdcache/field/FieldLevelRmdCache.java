package com.linkedin.davinci.replication.rmdcache.field;

import static com.linkedin.venice.schema.rmd.RmdConstants.TIMESTAMP_FIELD_POS;

import com.linkedin.venice.annotation.Threadsafe;
import com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;


/**
 * Per-partition, in-memory cache of per-field replication-metadata entries, used by the
 * VT-merge fold path to do cross-DC element-level DCR on the follower without going through
 * the leader's read-modify-write.
 *
 * <p>Per the design in {@code autoresearch/vt-merge-cross-dc-fix/GOAL.md} §4 and
 * {@code autoresearch/vt-merge-rmd-aware-fold/GOAL.md} §3-§4. This implementation focuses on
 * correctness; the lock-free primitive-long map optimization from the design spec can be
 * applied later if Phase 3 microbenchmark shows the {@link ConcurrentHashMap}-backed map
 * dominates per-fold cost.
 *
 * <p><b>State machine:</b> each key is in one of two modes:
 * <ul>
 *   <li><b>Mode 0 (whole-record-ts):</b> last touched by a wholesale PUT or DELETE. The cache
 *       holds a single {@code wholeRecordTs} for the key; no per-field entries. Incoming UPDATEs
 *       trigger a Mode 0 → Mode 1 transition (see {@link #synthesizeFromPut}).</li>
 *   <li><b>Mode 1 (per-field-ts):</b> last touched by a partial UPDATE. The cache holds per-field
 *       entries. A subsequent PUT triggers a Mode 1 → Mode 0 transition: all per-field entries
 *       are evicted; a single {@code wholeRecordTs} entry is installed.</li>
 * </ul>
 *
 * <p><b>Gotcha #1 (collection-field empty-at-PUT):</b> when synthesizing a Mode 0 → Mode 1 entry
 * for a collection field that was empty at PUT time (size == 0), the synthesized
 * {@code topLevelFieldTs} is set to {@link Long#MIN_VALUE} rather than the PUT's timestamp.
 * This avoids breaking the {@code isInPutOnlyState()} invariant in the V2 algorithm: an
 * empty collection with a non-{@code MIN_VALUE} top-level-ts would treat every later-added
 * element as having ts = PUT.ts, which is incorrect for elements that came in via subsequent
 * UPDATEs.
 *
 * <p><b>Gotcha #2 (scalar-field default-at-PUT):</b> when synthesizing a Mode 0 → Mode 1 entry
 * for a scalar field whose value at PUT time equals the schema default, the entry is marked
 * {@code populatedByPut=false} and given {@code topLevelFieldTs = Long.MIN_VALUE}. Subsequent
 * UPDATEs targeting this field then win regardless of timestamp — there is no DCR floor for
 * fields that the PUT did not explicitly set.
 */
@Threadsafe
public class FieldLevelRmdCache {
  /** Sentinel marking the per-key mode slot inside the {@link #perKeyFieldEntries} map. */
  static final int MODE_SENTINEL_ORDINAL = -1;

  /** Mode 0: whole-record-ts. The mode slot's entry holds the whole-record-ts in topLevelFieldTs. */
  public static final boolean MODE_WHOLE_RECORD = false;
  /** Mode 1: per-field-ts. */
  public static final boolean MODE_PER_FIELD = true;

  private final int partitionId;
  /**
   * Outer key: ByteArrayKey wrapper around the user key bytes. Inner key: field ordinal in the
   * value schema, with {@link #MODE_SENTINEL_ORDINAL} reserved for the mode + whole-record-ts
   * sentinel slot.
   *
   * <p>Inner map is a plain HashMap; concurrency on the inner map is guarded externally by
   * synchronizing on the outer key's value (the inner-map reference). This is fine for the
   * VT-merge use case where each (partition, key) is processed serially by the storage
   * partition's synchronized merge/put block.
   */
  private final ConcurrentHashMap<ByteArrayKey, Map<Integer, FieldRmdEntry>> perKeyFieldEntries =
      new ConcurrentHashMap<>();

  private final AtomicLong hitCount = new AtomicLong();
  private final AtomicLong missCount = new AtomicLong();
  private final AtomicLong droppedOperandCount = new AtomicLong();
  private final AtomicLong modeFlipToWholeRecordCount = new AtomicLong();
  private final AtomicLong modeFlipToPerFieldCount = new AtomicLong();

  public FieldLevelRmdCache(int partitionId) {
    this.partitionId = partitionId;
  }

  public int getPartitionId() {
    return partitionId;
  }

  public long getHitCount() {
    return hitCount.get();
  }

  public long getMissCount() {
    return missCount.get();
  }

  public long getDroppedOperandCount() {
    return droppedOperandCount.get();
  }

  public long getModeFlipToWholeRecordCount() {
    return modeFlipToWholeRecordCount.get();
  }

  public long getModeFlipToPerFieldCount() {
    return modeFlipToPerFieldCount.get();
  }

  public int size() {
    return perKeyFieldEntries.size();
  }

  /**
   * Look up the per-field entry for (key, fieldOrdinal). Returns null if absent (cache miss or
   * key is in mode 0).
   */
  public FieldRmdEntry getEntry(byte[] keyBytes, int fieldOrdinal) {
    Map<Integer, FieldRmdEntry> entries = perKeyFieldEntries.get(new ByteArrayKey(keyBytes));
    if (entries == null) {
      missCount.incrementAndGet();
      return null;
    }
    synchronized (entries) {
      FieldRmdEntry mode = entries.get(MODE_SENTINEL_ORDINAL);
      if (mode == null || !isPerFieldMode(mode)) {
        // Mode 0 or unknown — no per-field entry yet.
        missCount.incrementAndGet();
        return null;
      }
      FieldRmdEntry entry = entries.get(fieldOrdinal);
      if (entry == null) {
        missCount.incrementAndGet();
      } else {
        hitCount.incrementAndGet();
      }
      return entry;
    }
  }

  /**
   * Return the per-key mode entry, or null if the key is not in the cache.
   */
  public FieldRmdEntry getModeEntry(byte[] keyBytes) {
    Map<Integer, FieldRmdEntry> entries = perKeyFieldEntries.get(new ByteArrayKey(keyBytes));
    if (entries == null) {
      return null;
    }
    synchronized (entries) {
      return entries.get(MODE_SENTINEL_ORDINAL);
    }
  }

  /** Whether the key is currently tracked in mode 1 (per-field-ts). */
  public boolean isInPerFieldMode(byte[] keyBytes) {
    FieldRmdEntry mode = getModeEntry(keyBytes);
    return mode != null && isPerFieldMode(mode);
  }

  /**
   * Install a mode-0 entry for a key. Evicts any per-field entries.
   *
   * <p>Use case: a wholesale PUT or DELETE arrived for this key. After this call, the cache
   * reflects whole-record-ts mode with the given {@code wholeRecordTs}.
   */
  public void putModeZero(byte[] keyBytes, long wholeRecordTs) {
    ByteArrayKey key = new ByteArrayKey(keyBytes);
    Map<Integer, FieldRmdEntry> entries = perKeyFieldEntries.computeIfAbsent(key, k -> new HashMap<>());
    synchronized (entries) {
      FieldRmdEntry prior = entries.get(MODE_SENTINEL_ORDINAL);
      if (prior != null && isPerFieldMode(prior)) {
        modeFlipToWholeRecordCount.incrementAndGet();
      }
      entries.clear();
      entries.put(MODE_SENTINEL_ORDINAL, modeSentinelWholeRecord(wholeRecordTs));
    }
  }

  /**
   * Install per-field entries for a key (mode 1).
   *
   * <p>Use case: synthesize a Mode 0 → Mode 1 transition from a known base record. The caller
   * builds the per-field entries (typically via {@link #synthesizeFromPut}) and passes them in.
   *
   * <p>If the key was previously in mode 0, this transition is recorded.
   */
  public void putPerFieldEntries(byte[] keyBytes, Map<Integer, FieldRmdEntry> newEntries) {
    ByteArrayKey key = new ByteArrayKey(keyBytes);
    Map<Integer, FieldRmdEntry> entries = perKeyFieldEntries.computeIfAbsent(key, k -> new HashMap<>());
    synchronized (entries) {
      FieldRmdEntry prior = entries.get(MODE_SENTINEL_ORDINAL);
      if (prior == null || !isPerFieldMode(prior)) {
        modeFlipToPerFieldCount.incrementAndGet();
      }
      entries.clear();
      entries.put(MODE_SENTINEL_ORDINAL, modeSentinelPerField());
      entries.putAll(newEntries);
    }
  }

  /**
   * Update a single per-field entry in mode 1. The key must already be in mode 1; if not in the
   * cache or in mode 0, this is a no-op (caller should call {@link #putPerFieldEntries} first).
   */
  public void updateEntry(byte[] keyBytes, int fieldOrdinal, FieldRmdEntry entry) {
    Map<Integer, FieldRmdEntry> entries = perKeyFieldEntries.get(new ByteArrayKey(keyBytes));
    if (entries == null) {
      return;
    }
    synchronized (entries) {
      FieldRmdEntry mode = entries.get(MODE_SENTINEL_ORDINAL);
      if (mode == null || !isPerFieldMode(mode)) {
        return;
      }
      entries.put(fieldOrdinal, entry);
    }
  }

  /**
   * Remove all entries for a key. Use case: full DELETE that doesn't leave a tombstone in the
   * cache.
   */
  public void invalidate(byte[] keyBytes) {
    perKeyFieldEntries.remove(new ByteArrayKey(keyBytes));
  }

  /** Counter increment for an operand dropped at merge time (lost DCR for all touched fields). */
  public void recordDroppedOperand() {
    droppedOperandCount.incrementAndGet();
  }

  /**
   * Synthesize per-field entries from a base value record + a whole-record PUT timestamp.
   *
   * <p>Honors both gotchas from {@code GOAL.md} §4.2 and §4.3:
   * <ul>
   *   <li>Empty collection fields → {@code populatedByPut=false}, {@code topLevelFieldTs=Long.MIN_VALUE}</li>
   *   <li>Scalar fields equal to their schema default → {@code populatedByPut=false}, {@code topLevelFieldTs=Long.MIN_VALUE}</li>
   *   <li>Other fields → {@code populatedByPut=true}, {@code topLevelFieldTs=PUT.ts}</li>
   * </ul>
   *
   * @param baseValueRecord the deserialized base record from RocksDB; if null, all fields are
   *                        treated as default (populatedByPut=false for all)
   * @param valueSchema     the value schema (provides field defaults)
   * @param putTs           the wholesale PUT's timestamp
   * @return a map from fieldOrdinal → synthesized {@link FieldRmdEntry}
   */
  public static Map<Integer, FieldRmdEntry> synthesizeFromPut(
      GenericRecord baseValueRecord,
      Schema valueSchema,
      long putTs) {
    Map<Integer, FieldRmdEntry> out = new HashMap<>();
    java.util.List<Schema.Field> fields = valueSchema.getFields();
    for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
      Schema.Field field = fields.get(ordinal);
      Object value = baseValueRecord == null ? null : baseValueRecord.get(field.name());
      boolean populatedByPut = !isFieldAtDefaultOrEmpty(field, value);
      Schema fieldSchema = unwrapNullable(field.schema());
      Schema.Type type = fieldSchema.getType();
      if (type == Schema.Type.ARRAY || type == Schema.Type.MAP) {
        // Collection field: build a put-only-state CollectionRmdTimestamp with the proper
        // topLevelFieldTs respecting Gotcha #1.
        long topLevelTs = populatedByPut ? putTs : Long.MIN_VALUE;
        // We don't actually populate the RMD subtree here — the fold path will build one on the
        // fly from the seed when V2 is invoked. For now, we stash a put-only-state empty subtree
        // marker that the fold path can recognize and seed from.
        GenericRecord rec = buildSyntheticPutOnlyCollectionRmdRecord(topLevelTs);
        out.put(
            ordinal,
            FieldRmdEntry.forCollection(topLevelTs, populatedByPut, new CollectionRmdTimestamp<>(rec), rec));
      } else {
        long topLevelTs = populatedByPut ? putTs : Long.MIN_VALUE;
        out.put(ordinal, FieldRmdEntry.forScalar(topLevelTs, populatedByPut));
      }
    }
    return out;
  }

  /**
   * Build the per-field-ts portion of an RMD record from cached entries. The output record is
   * suitable as the {@code timestamp} field of an RMD record (per-field-ts branch), ready to be
   * passed to the V2 algorithm.
   *
   * <p>For fields not in the cache (e.g. recently added by schema evolution), uses the seed-RMD
   * default (topLevelTs=0, put-only state).
   */
  public static GenericRecord buildPerFieldTsRecord(
      Schema rmdSchema,
      Schema valueSchema,
      Map<Integer, FieldRmdEntry> entriesByOrdinal) {
    Schema tsUnion = rmdSchema.getFields().get(TIMESTAMP_FIELD_POS).schema();
    Schema perFieldTsSchema = tsUnion.getTypes().get(1);
    GenericRecord perFieldTs = new org.apache.avro.generic.GenericData.Record(perFieldTsSchema);
    java.util.List<Schema.Field> fields = valueSchema.getFields();
    for (int ordinal = 0; ordinal < fields.size(); ordinal++) {
      Schema.Field valueField = fields.get(ordinal);
      Schema fieldRmdSchema = perFieldTsSchema.getField(valueField.name()).schema();
      FieldRmdEntry entry = entriesByOrdinal == null ? null : entriesByOrdinal.get(ordinal);
      Object cell;
      if (fieldRmdSchema.getType() == Schema.Type.RECORD) {
        // Collection field's per-field RMD is itself a record. We always build a fresh per-field
        // collection RMD record against the proper RMD schema so V2 can pattern-match its
        // deletedElements list type correctly. We do, however, take topLevelTs from the cache
        // entry to preserve cross-DC DCR state.
        long topLevelTs = entry == null ? 0L : entry.getTopLevelFieldTs();
        if (entry != null && entry.getRmdSubtreeRecord() != null) {
          cell = copyCollectionRmdAgainstSchema(entry.getRmdSubtreeRecord(), fieldRmdSchema, topLevelTs);
        } else {
          cell = buildEmptyCollectionRmdRecord(fieldRmdSchema, topLevelTs);
        }
      } else {
        // Scalar field's per-field RMD is a Long.
        if (entry == null) {
          cell = 0L;
        } else {
          cell = entry.getTopLevelFieldTs();
        }
      }
      perFieldTs.put(valueField.name(), cell);
    }
    return perFieldTs;
  }

  // ---------------------------- helpers ----------------------------

  /**
   * Whether a field's value is at the schema default (for scalar) or empty (for collection).
   * Such fields are treated as "not populated by PUT" per Gotchas #1 and #2.
   */
  static boolean isFieldAtDefaultOrEmpty(Schema.Field field, Object value) {
    Schema fieldSchema = unwrapNullable(field.schema());
    Schema.Type type = fieldSchema.getType();
    if (value == null) {
      return true;
    }
    if (type == Schema.Type.ARRAY) {
      return ((java.util.Collection<?>) value).isEmpty();
    }
    if (type == Schema.Type.MAP) {
      return ((java.util.Map<?, ?>) value).isEmpty();
    }
    // Scalars: compare against schema default.
    Object defaultValue = com.linkedin.venice.utils.AvroSchemaUtils.getFieldDefault(field);
    if (defaultValue == null) {
      // No default declared; treat any value as populated (unless null).
      return value == null;
    }
    if (defaultValue instanceof CharSequence && value instanceof CharSequence) {
      return defaultValue.toString().equals(value.toString());
    }
    return defaultValue.equals(value);
  }

  /** Unwrap a Schema if it's a nullable union [null, T]; otherwise return the schema unchanged. */
  public static Schema unwrapNullable(Schema schema) {
    if (schema.getType() != Schema.Type.UNION) {
      return schema;
    }
    Schema nonNull = null;
    for (Schema branch: schema.getTypes()) {
      if (branch.getType() != Schema.Type.NULL) {
        if (nonNull != null) {
          // Complex union; return as-is (shouldn't happen for Venice value fields).
          return schema;
        }
        nonNull = branch;
      }
    }
    return nonNull != null ? nonNull : schema;
  }

  static boolean isPerFieldMode(FieldRmdEntry modeSentinel) {
    return modeSentinel != null && modeSentinel.isPopulatedByPut();
  }

  /**
   * Create a mode-1 (per-field-ts) sentinel slot entry. We encode "mode 1" as
   * {@code populatedByPut=true} on the sentinel — orthogonal use of the bit, since the sentinel
   * doesn't represent a real field.
   */
  static FieldRmdEntry modeSentinelPerField() {
    return FieldRmdEntry.forScalar(0L, true);
  }

  /**
   * Create a mode-0 (whole-record-ts) sentinel slot entry. We encode "mode 0" as
   * {@code populatedByPut=false} on the sentinel; the {@code topLevelFieldTs} carries the
   * whole-record-ts.
   */
  static FieldRmdEntry modeSentinelWholeRecord(long wholeRecordTs) {
    return FieldRmdEntry.forScalar(wholeRecordTs, false);
  }

  /**
   * Build a synthetic put-only-state collection RMD record (deletedElements typed as String). Used
   * by {@link #synthesizeFromPut} to seed collection-field entries when we don't yet have access
   * to the RMD-side schema for the field.
   */
  static GenericRecord buildSyntheticPutOnlyCollectionRmdRecord(long topLevelTs) {
    Schema syntheticSchema = SyntheticCollectionRmdSchema.SCHEMA;
    GenericRecord rec = new org.apache.avro.generic.GenericData.Record(syntheticSchema);
    rec.put(CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME, topLevelTs);
    rec.put(CollectionRmdTimestamp.TOP_LEVEL_COLO_ID_FIELD_NAME, -1);
    rec.put(CollectionRmdTimestamp.PUT_ONLY_PART_LENGTH_FIELD_NAME, 0);
    rec.put(CollectionRmdTimestamp.ACTIVE_ELEM_TS_FIELD_NAME, new java.util.ArrayList<Long>(0));
    rec.put(CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME, new java.util.ArrayList<>());
    rec.put(CollectionRmdTimestamp.DELETED_ELEM_TS_FIELD_NAME, new java.util.ArrayList<Long>(0));
    return rec;
  }

  /**
   * Copy a synthetic-schema collection RMD record into a fresh record built against the actual
   * RMD-side schema. Preserves topLevelTs / topLevelColoID / putOnlyPartLength / activeTs /
   * deletedTs values; rebuilds the deletedElements list against the proper element-type array
   * schema (typically the value field's element schema).
   */
  static GenericRecord copyCollectionRmdAgainstSchema(GenericRecord src, Schema targetSchema, long topLevelTs) {
    GenericRecord out = new org.apache.avro.generic.GenericData.Record(targetSchema);
    out.put(CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME, topLevelTs);
    Object srcColoId = src.get(CollectionRmdTimestamp.TOP_LEVEL_COLO_ID_FIELD_NAME);
    out.put(CollectionRmdTimestamp.TOP_LEVEL_COLO_ID_FIELD_NAME, srcColoId == null ? -1 : srcColoId);
    Object srcPartLen = src.get(CollectionRmdTimestamp.PUT_ONLY_PART_LENGTH_FIELD_NAME);
    out.put(CollectionRmdTimestamp.PUT_ONLY_PART_LENGTH_FIELD_NAME, srcPartLen == null ? 0 : srcPartLen);
    Object srcActiveTs = src.get(CollectionRmdTimestamp.ACTIVE_ELEM_TS_FIELD_NAME);
    out.put(
        CollectionRmdTimestamp.ACTIVE_ELEM_TS_FIELD_NAME,
        srcActiveTs == null
            ? new java.util.ArrayList<Long>(0)
            : new java.util.ArrayList<>((java.util.List<?>) srcActiveTs));
    Schema deletedElemSchema = targetSchema.getField(CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME).schema();
    org.apache.avro.generic.GenericData.Array<Object> deletedElems =
        new org.apache.avro.generic.GenericData.Array<>(0, deletedElemSchema);
    Object srcDeleted = src.get(CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME);
    if (srcDeleted instanceof java.util.List) {
      for (Object e: (java.util.List<?>) srcDeleted) {
        deletedElems.add(e);
      }
    }
    out.put(CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME, deletedElems);
    Object srcDeletedTs = src.get(CollectionRmdTimestamp.DELETED_ELEM_TS_FIELD_NAME);
    out.put(
        CollectionRmdTimestamp.DELETED_ELEM_TS_FIELD_NAME,
        srcDeletedTs == null
            ? new java.util.ArrayList<Long>(0)
            : new java.util.ArrayList<>((java.util.List<?>) srcDeletedTs));
    return out;
  }

  /**
   * Build an empty put-only collection RMD record using the provided RMD-side schema (so the
   * record has the right deletedElements element type for the value field).
   */
  static GenericRecord buildEmptyCollectionRmdRecord(Schema fieldRmdSchema, long topLevelTs) {
    GenericRecord rec = new org.apache.avro.generic.GenericData.Record(fieldRmdSchema);
    rec.put(CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME, topLevelTs);
    rec.put(CollectionRmdTimestamp.TOP_LEVEL_COLO_ID_FIELD_NAME, -1);
    rec.put(CollectionRmdTimestamp.PUT_ONLY_PART_LENGTH_FIELD_NAME, 0);
    rec.put(CollectionRmdTimestamp.ACTIVE_ELEM_TS_FIELD_NAME, new java.util.ArrayList<Long>(0));
    Schema deletedElemSchema = fieldRmdSchema.getField(CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME).schema();
    rec.put(
        CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME,
        new org.apache.avro.generic.GenericData.Array<>(0, deletedElemSchema));
    rec.put(CollectionRmdTimestamp.DELETED_ELEM_TS_FIELD_NAME, new java.util.ArrayList<Long>(0));
    return rec;
  }

  /**
   * Minimal generic collection-RMD schema for use when no value-side schema is yet known. The
   * deletedElements list uses Object items as a placeholder; fold-time code rebuilds the record
   * against the proper schema if it needs to.
   */
  private static final class SyntheticCollectionRmdSchema {
    private static final Schema SCHEMA = Schema.createRecord(
        "CollectionRmdSynthetic",
        null,
        "com.linkedin.davinci.replication.rmdcache.field",
        false,
        Arrays.asList(
            new Schema.Field(CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME, Schema.create(Schema.Type.LONG), null, 0L),
            new Schema.Field(
                CollectionRmdTimestamp.TOP_LEVEL_COLO_ID_FIELD_NAME,
                Schema.create(Schema.Type.INT),
                null,
                -1),
            new Schema.Field(
                CollectionRmdTimestamp.PUT_ONLY_PART_LENGTH_FIELD_NAME,
                Schema.create(Schema.Type.INT),
                null,
                0),
            new Schema.Field(
                CollectionRmdTimestamp.ACTIVE_ELEM_TS_FIELD_NAME,
                Schema.createArray(Schema.create(Schema.Type.LONG)),
                null,
                java.util.Collections.emptyList()),
            new Schema.Field(
                CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME,
                Schema.createArray(Schema.create(Schema.Type.STRING)),
                null,
                java.util.Collections.emptyList()),
            new Schema.Field(
                CollectionRmdTimestamp.DELETED_ELEM_TS_FIELD_NAME,
                Schema.createArray(Schema.create(Schema.Type.LONG)),
                null,
                java.util.Collections.emptyList())));
  }

  /**
   * Lightweight {@code byte[]}-as-key wrapper with content-based {@link #equals} and {@link #hashCode}.
   */
  static final class ByteArrayKey {
    final byte[] bytes;
    final int hash;

    ByteArrayKey(byte[] bytes) {
      this.bytes = bytes;
      this.hash = Arrays.hashCode(bytes);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof ByteArrayKey)) {
        return false;
      }
      return Arrays.equals(bytes, ((ByteArrayKey) o).bytes);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
