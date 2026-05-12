package com.linkedin.davinci.schema.writecompute;

import static com.linkedin.venice.schema.rmd.RmdConstants.REPLICATION_CHECKPOINT_VECTOR_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.RmdConstants.TIMESTAMP_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.RmdConstants.TIMESTAMP_FIELD_POS;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.ACTIVE_ELEM_TS_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.DELETED_ELEM_TS_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.PUT_ONLY_PART_LENGTH_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.TOP_LEVEL_COLO_ID_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME;

import com.linkedin.venice.schema.rmd.RmdSchemaGenerator;
import com.linkedin.venice.utils.AvroSchemaUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;


/**
 * Builds "seed" replication-metadata records for the flag-on VT-merge fold path.
 *
 * <p>The flag-on path (server.vt.update.operand.enabled=true) appends raw UPDATE operands to a
 * per-key chain in RocksDB and folds them on read via
 * {@link com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext}. The fold currently
 * goes through the V1 algorithm (inherited from {@link com.linkedin.venice.schema.writecompute.WriteComputeHandlerV1}),
 * which contains an O(N×M) {@code .contains()} loop for SET_UNION over a list field. On a
 * 380K-float list with 39 operands that loop dominates per-read latency and triggers router
 * timeouts on {@code testActiveActivePartialUpdateWithCompression}.
 *
 * <p>The V2 algorithm in {@link com.linkedin.davinci.schema.writecompute.WriteComputeHandlerV2}
 * uses an {@link com.linkedin.davinci.utils.IndexedHashMap}-based active-element-timestamp index
 * which gives sub-quadratic add/remove. Empirically it is 10-58× faster on the same workload
 * (see {@code WriteComputeArrayApplyMicrobenchmark}). But V2 requires a
 * {@link com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp} input — the fold path has
 * none because flag-on intentionally bypasses the leader RMW that would have produced RMD.
 *
 * <p>This helper synthesizes a fresh per-field-timestamp RMD record in put-only state, with
 * {@code topLevelFieldTimestamp = 0} for every field. Since each operand carries
 * {@code modifyTimestamp >= 1}, the V2 algorithm's {@code ignoreIncomingRequest} check always
 * returns false; every operand wins; the V2 algorithm runs to completion. After the fold the
 * synthesized RMD is discarded — only the materialized value bytes are returned to the caller.
 *
 * <p>Cross-call cost is mitigated by caching the per-field-ts RMD schema per (storeName,
 * valueSchemaId) — the same RmdSchemaGenerator output every time. Each fold call still
 * allocates one fresh RMD record (V2 mutates the RMD in place during the fold).
 *
 * <p>NOTE: this is a per-fold-call seed; it does NOT persist RMD across folds. That trade-off
 * is acceptable for single-DC workloads where operand-order semantics within one chain are
 * sufficient. Cross-DC element-level DCR semantics would require a persistent per-(key, field)
 * RMD cache — see {@code autoresearch/vt-merge-rmd-aware-fold/GOAL.md} Phase 1/2.
 */
public final class WriteComputeSeedRmd {
  /**
   * Cache of (storeName, valueSchemaId) -> RMD schema. The RMD schema is deterministic from the
   * value schema, but {@link RmdSchemaGenerator#generateMetadataSchema} traverses the entire
   * schema and is non-trivial; cache once per value schema.
   */
  private final Map<SchemaCacheKey, Schema> rmdSchemaCache = new ConcurrentHashMap<>();

  /**
   * Returns the RMD schema for a given (storeName, valueSchemaId, valueSchema), generating and
   * caching it on first access.
   */
  public Schema getRmdSchema(String storeName, int valueSchemaId, Schema valueSchema) {
    SchemaCacheKey key = new SchemaCacheKey(storeName, valueSchemaId);
    Schema cached = rmdSchemaCache.get(key);
    if (cached != null) {
      return cached;
    }
    Schema generated = RmdSchemaGenerator.generateMetadataSchema(valueSchema, RmdSchemaGenerator.getLatestVersion());
    Schema prior = rmdSchemaCache.putIfAbsent(key, generated);
    return prior != null ? prior : generated;
  }

  /**
   * Build a fresh seed RMD record with the per-field-ts union branch selected and every per-field
   * timestamp initialized to {@code topLevelTs = 0} (put-only state for collection fields, plain
   * Long zero for scalar fields).
   *
   * @param rmdSchema the RMD schema for the value, obtained from {@link #getRmdSchema}
   * @param valueSchema the value schema; used to find each field's RMD type
   * @return a fresh RMD record, safe to mutate during a V2 fold
   */
  public GenericRecord buildSeedRmd(Schema rmdSchema, Schema valueSchema) {
    // The RMD timestamp field is a union: [long (whole-record-ts), per-field-ts record].
    // Use the per-field-ts branch (index 1).
    Schema tsUnion = rmdSchema.getFields().get(TIMESTAMP_FIELD_POS).schema();
    Schema perFieldTsSchema = tsUnion.getTypes().get(1);

    GenericRecord perFieldTs = new GenericData.Record(perFieldTsSchema);
    for (Schema.Field valueField: valueSchema.getFields()) {
      Schema fieldRmdSchema = perFieldTsSchema.getField(valueField.name()).schema();
      Object seed = buildFieldSeed(fieldRmdSchema, valueField.schema());
      perFieldTs.put(valueField.name(), seed);
    }

    GenericRecord rmd = new GenericData.Record(rmdSchema);
    rmd.put(TIMESTAMP_FIELD_NAME, perFieldTs);
    rmd.put(REPLICATION_CHECKPOINT_VECTOR_FIELD_NAME, Collections.emptyList());
    return rmd;
  }

  /**
   * Build the RMD entry for one value-schema field.
   *
   * <p>For ARRAY/MAP collection fields, returns a fresh {@code CollectionRmdTimestamp} in
   * put-only state with topLevelTs=0. For other fields (scalar, record, fixed, enum), returns
   * Long(0).
   *
   * <p>For UNION fields where the unwrap target is array/map, builds the collection RMD against
   * the unwrapped schema's RMD shape.
   */
  private Object buildFieldSeed(Schema fieldRmdSchema, Schema valueFieldSchema) {
    if (fieldRmdSchema.getType() == Schema.Type.RECORD) {
      // Collection RMD record (array or map field's per-field ts).
      // We don't pre-populate the put-only part length here because V2's handleModifyPutOnlyList
      // re-reads the actual current list size from the value record at run time. Setting
      // putOnlyPartLength=0 is safe; V2 only uses it as a "where does put-only partition end"
      // marker when transitioning out of put-only state.
      return buildPutOnlyCollectionRmd(fieldRmdSchema);
    }
    // Scalar field (long, int, string, boolean, float, double, bytes, enum, fixed, record, or
    // a union of these). RMD entry is Long(0).
    return 0L;
  }

  /**
   * Construct a {@code CollectionRmdTimestamp} generic record with:
   * <ul>
   *   <li>topLevelFieldTimestamp = 0
   *   <li>topLevelColoID = -1
   *   <li>putOnlyPartLength = 0 (V2 re-derives the actual partition from the current list)
   *   <li>activeElementTimestamps = []
   *   <li>deletedElementsIdentities = []
   *   <li>deletedElementTimestamps = []
   * </ul>
   *
   * <p>This record satisfies {@code isInPutOnlyState() == true}, which is the input contract
   * for {@code SortBasedCollectionFieldOpHandler.handleModifyList} fast path.
   */
  private GenericRecord buildPutOnlyCollectionRmd(Schema collectionTsSchema) {
    GenericRecord rmd = AvroSchemaUtils.createGenericRecord(collectionTsSchema);
    rmd.put(TOP_LEVEL_TS_FIELD_NAME, 0L);
    rmd.put(TOP_LEVEL_COLO_ID_FIELD_NAME, -1);
    rmd.put(PUT_ONLY_PART_LENGTH_FIELD_NAME, 0);
    rmd.put(ACTIVE_ELEM_TS_FIELD_NAME, new ArrayList<Long>(0));
    Schema deletedElemSchema = collectionTsSchema.getField(DELETED_ELEM_FIELD_NAME).schema();
    rmd.put(DELETED_ELEM_FIELD_NAME, new GenericData.Array<>(0, deletedElemSchema));
    rmd.put(DELETED_ELEM_TS_FIELD_NAME, new ArrayList<Long>(0));
    return rmd;
  }

  /**
   * Reset an existing seed RMD record (built via {@link #buildSeedRmd}) back to put-only state
   * with topLevelTs=0 for every field. Used by the fold path to reuse one allocation per fold
   * call rather than re-allocating per operand.
   *
   * <p>Safe to call only on a record that this helper itself built.
   */
  public void resetSeedRmd(GenericRecord rmd, Schema valueSchema) {
    GenericRecord perFieldTs = (GenericRecord) rmd.get(TIMESTAMP_FIELD_POS);
    for (Schema.Field valueField: valueSchema.getFields()) {
      Object existing = perFieldTs.get(valueField.name());
      if (existing instanceof GenericRecord) {
        // Collection field — reset in place.
        GenericRecord collRmd = (GenericRecord) existing;
        collRmd.put(TOP_LEVEL_TS_FIELD_NAME, 0L);
        collRmd.put(TOP_LEVEL_COLO_ID_FIELD_NAME, -1);
        collRmd.put(PUT_ONLY_PART_LENGTH_FIELD_NAME, 0);
        // Reset list contents but preserve the List references' schemas for Avro compat.
        @SuppressWarnings("unchecked")
        List<Long> activeTs = (List<Long>) collRmd.get(ACTIVE_ELEM_TS_FIELD_NAME);
        activeTs.clear();
        @SuppressWarnings("unchecked")
        List<Object> deletedElems = (List<Object>) collRmd.get(DELETED_ELEM_FIELD_NAME);
        deletedElems.clear();
        @SuppressWarnings("unchecked")
        List<Long> deletedTs = (List<Long>) collRmd.get(DELETED_ELEM_TS_FIELD_NAME);
        deletedTs.clear();
      } else {
        perFieldTs.put(valueField.name(), 0L);
      }
    }
  }

  /** Test/diagnostic accessor for the per-field-ts subrecord. */
  public static GenericRecord getPerFieldTimestampRecord(GenericRecord rmd) {
    return (GenericRecord) rmd.get(TIMESTAMP_FIELD_POS);
  }

  /** Approximate cache size, for tests and diagnostics. */
  int rmdSchemaCacheSize() {
    return rmdSchemaCache.size();
  }

  /** Composite cache key: per-storeName-per-valueSchemaId for the RMD schema. */
  private static final class SchemaCacheKey {
    private final String storeName;
    private final int valueSchemaId;

    SchemaCacheKey(String storeName, int valueSchemaId) {
      this.storeName = storeName;
      this.valueSchemaId = valueSchemaId;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (!(o instanceof SchemaCacheKey)) {
        return false;
      }
      SchemaCacheKey other = (SchemaCacheKey) o;
      return valueSchemaId == other.valueSchemaId && storeName.equals(other.storeName);
    }

    @Override
    public int hashCode() {
      return 31 * storeName.hashCode() + valueSchemaId;
    }
  }

  // ------------------------------------------------------------------------------------------
  // Helpers used by unit tests
  // ------------------------------------------------------------------------------------------

  /**
   * Build a HashMap from field names to whether they are collection (array/map) fields in
   * the value schema. Used by unit tests to assert the shape of the seed.
   */
  static Map<String, Boolean> fieldShape(Schema valueSchema) {
    Map<String, Boolean> shape = new HashMap<>();
    for (Schema.Field field: valueSchema.getFields()) {
      shape.put(field.name(), isCollection(field.schema()));
    }
    return shape;
  }

  private static boolean isCollection(Schema fieldSchema) {
    Schema.Type type = fieldSchema.getType();
    if (type == Schema.Type.UNION) {
      for (Schema branch: fieldSchema.getTypes()) {
        if (branch.getType() != Schema.Type.NULL && isCollection(branch)) {
          return true;
        }
      }
      return false;
    }
    return type == Schema.Type.ARRAY || type == Schema.Type.MAP;
  }
}
