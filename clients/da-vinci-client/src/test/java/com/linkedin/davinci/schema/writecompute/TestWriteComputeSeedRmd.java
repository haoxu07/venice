package com.linkedin.davinci.schema.writecompute;

import static com.linkedin.venice.schema.rmd.RmdConstants.TIMESTAMP_FIELD_POS;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.ACTIVE_ELEM_TS_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.DELETED_ELEM_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.DELETED_ELEM_TS_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.PUT_ONLY_PART_LENGTH_FIELD_NAME;
import static com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME;
import static com.linkedin.venice.schema.writecompute.WriteComputeConstants.SET_DIFF;
import static com.linkedin.venice.schema.writecompute.WriteComputeConstants.SET_UNION;

import com.linkedin.avroutil1.compatibility.AvroCompatibilityHelper;
import com.linkedin.davinci.schema.merge.CollectionTimestampMergeRecordHelper;
import com.linkedin.davinci.schema.merge.ValueAndRmd;
import com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter;
import com.linkedin.venice.utils.lazy.Lazy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.testng.Assert;
import org.testng.annotations.Test;


/**
 * Regression-guard tests for {@link WriteComputeSeedRmd}. These tests verify:
 *
 * <ul>
 *   <li>The seed RMD has the correct structural shape (per-field-ts branch, every value-field
 *       represented, scalars as Long(0), collections as put-only CollectionRmdTimestamp).</li>
 *   <li>The V2 algorithm accepts the seed RMD and produces the same output as the V1
 *       algorithm on a deterministic SET_UNION workload (correctness parity).</li>
 *   <li>Multi-operand chains (the fold use case) produce results equivalent to applying the
 *       operands sequentially with V1.</li>
 *   <li>The schema cache is hit on repeated calls with the same (storeName, schemaId).</li>
 *   <li>The reset operation preserves the structural shape (so the fold loop can reuse one
 *       seed RMD allocation across operands).</li>
 * </ul>
 *
 * <p>Per the unit-test-first discipline in the GOAL doc, these tests run BEFORE the
 * production wire-in (MaterializingFoldContext) is changed.
 */
public class TestWriteComputeSeedRmd {
  private static final String STORE_NAME = "test_store";
  private static final int VALUE_SCHEMA_ID = 1;

  private static final String FLOAT_LIST_SCHEMA =
      "{\n" + "  \"type\": \"record\",\n" + "  \"name\": \"FloatList\",\n" + "  \"fields\": [\n"
          + "    {\"name\": \"floatArray\", \"type\": {\"type\": \"array\", \"items\": \"float\"}, \"default\": []}\n"
          + "  ]\n" + "}";

  private static final String MIXED_FIELDS_SCHEMA =
      "{\n" + "  \"type\": \"record\",\n" + "  \"name\": \"MixedFields\",\n" + "  \"fields\": [\n"
          + "    {\"name\": \"intField\", \"type\": \"int\", \"default\": 0},\n"
          + "    {\"name\": \"stringField\", \"type\": \"string\", \"default\": \"\"},\n"
          + "    {\"name\": \"arrayField\", \"type\": {\"type\": \"array\", \"items\": \"int\"}, \"default\": []},\n"
          + "    {\"name\": \"mapField\", \"type\": {\"type\": \"map\", \"values\": \"string\"}, \"default\": {}}\n"
          + "  ]\n" + "}";

  @Test
  public void testSeedRmdHasPerFieldTimestampBranch() {
    Schema valueSchema = AvroCompatibilityHelper.parse(FLOAT_LIST_SCHEMA);
    WriteComputeSeedRmd helper = new WriteComputeSeedRmd();
    Schema rmdSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);
    GenericRecord seed = helper.buildSeedRmd(rmdSchema, valueSchema);

    Object tsObj = seed.get(TIMESTAMP_FIELD_POS);
    Assert.assertNotNull(tsObj, "Seed RMD must have a non-null timestamp field");
    Assert.assertTrue(tsObj instanceof GenericRecord, "Seed RMD must use per-field-ts branch, not whole-record long");
    GenericRecord perFieldTs = (GenericRecord) tsObj;
    Assert.assertNotNull(perFieldTs.get("floatArray"), "Every value field must have an RMD entry");
  }

  @Test
  public void testSeedRmdScalarFieldsAreZeroLong() {
    Schema valueSchema = AvroCompatibilityHelper.parse(MIXED_FIELDS_SCHEMA);
    WriteComputeSeedRmd helper = new WriteComputeSeedRmd();
    Schema rmdSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);
    GenericRecord seed = helper.buildSeedRmd(rmdSchema, valueSchema);
    GenericRecord perFieldTs = (GenericRecord) seed.get(TIMESTAMP_FIELD_POS);

    Assert.assertEquals(perFieldTs.get("intField"), 0L, "Scalar field RMD entry is Long(0)");
    Assert.assertEquals(perFieldTs.get("stringField"), 0L, "Scalar field RMD entry is Long(0)");
    Assert.assertTrue(perFieldTs.get("arrayField") instanceof GenericRecord, "Array RMD is a record");
    Assert.assertTrue(perFieldTs.get("mapField") instanceof GenericRecord, "Map RMD is a record");
  }

  @Test
  public void testSeedRmdCollectionIsInPutOnlyState() {
    Schema valueSchema = AvroCompatibilityHelper.parse(FLOAT_LIST_SCHEMA);
    WriteComputeSeedRmd helper = new WriteComputeSeedRmd();
    Schema rmdSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);
    GenericRecord seed = helper.buildSeedRmd(rmdSchema, valueSchema);
    GenericRecord perFieldTs = (GenericRecord) seed.get(TIMESTAMP_FIELD_POS);
    GenericRecord collectionRmd = (GenericRecord) perFieldTs.get("floatArray");

    Assert.assertEquals(collectionRmd.get(TOP_LEVEL_TS_FIELD_NAME), 0L);
    Assert.assertEquals(collectionRmd.get(PUT_ONLY_PART_LENGTH_FIELD_NAME), 0);
    Assert.assertEquals(((List<?>) collectionRmd.get(ACTIVE_ELEM_TS_FIELD_NAME)).size(), 0);
    Assert.assertEquals(((List<?>) collectionRmd.get(DELETED_ELEM_FIELD_NAME)).size(), 0);
    Assert.assertEquals(((List<?>) collectionRmd.get(DELETED_ELEM_TS_FIELD_NAME)).size(), 0);
  }

  @Test
  public void testSchemaCacheHitOnRepeatedCalls() {
    Schema valueSchema = AvroCompatibilityHelper.parse(FLOAT_LIST_SCHEMA);
    WriteComputeSeedRmd helper = new WriteComputeSeedRmd();
    Schema firstSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);
    Schema secondSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);
    Assert.assertSame(firstSchema, secondSchema, "Schema must be returned from cache on second call");
    Assert.assertEquals(helper.rmdSchemaCacheSize(), 1, "Cache size must remain 1 after duplicate call");
  }

  /**
   * Regression-guard for the fundamental claim of the seed-RMD approach: V2 with seed RMD
   * produces the same result as V1 on a single SET_UNION operand.
   */
  @Test
  public void testV2WithSeedRmdMatchesV1OnSingleSetUnion() {
    Schema valueSchema = AvroCompatibilityHelper.parse(FLOAT_LIST_SCHEMA);
    Schema wcSchema = WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(valueSchema);
    WriteComputeProcessor processor = new WriteComputeProcessor(new CollectionTimestampMergeRecordHelper());
    WriteComputeSeedRmd helper = new WriteComputeSeedRmd();
    Schema rmdSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);

    GenericRecord baseV1 = makeBase(valueSchema, /* size= */ 100);
    GenericRecord baseV2 = makeBase(valueSchema, /* size= */ 100);
    GenericRecord wc = makeSetUnionRecord(wcSchema, /* fromInclusive= */ 100, /* toExclusive= */ 200);

    // V1 path (current production fold path).
    GenericRecord v1Result = processor.updateRecord(valueSchema, baseV1, wc);

    // V2 path with synthesized seed RMD.
    GenericRecord seedRmd = helper.buildSeedRmd(rmdSchema, valueSchema);
    ValueAndRmd<GenericRecord> v2Result = processor
        .updateRecordWithRmd(valueSchema, new ValueAndRmd<>(Lazy.of(() -> baseV2), seedRmd), wc, /*modifyTs*/ 1L, -1);

    List<Float> v1List = (List<Float>) v1Result.get("floatArray");
    List<Float> v2List = (List<Float>) v2Result.getValue().get("floatArray");

    Assert.assertEquals(
        new java.util.HashSet<>(v2List),
        new java.util.HashSet<>(v1List),
        "V2-with-seed-RMD must produce the same set of elements as V1 on the same SET_UNION operand");
    Assert.assertEquals(v2List.size(), v1List.size(), "V2 result size must equal V1 result size");
  }

  /**
   * Regression-guard for the multi-operand fold workload. This is the actual workload that
   * {@code testActiveActivePartialUpdateWithCompression} drives: a chain of N SET_UNION
   * operands appended sequentially. V2-with-seed-RMD applied with monotonically increasing
   * modifyTs must produce the same final state as V1 applied sequentially.
   */
  @Test
  public void testV2WithSeedRmdMatchesV1OnOperandChain() {
    Schema valueSchema = AvroCompatibilityHelper.parse(FLOAT_LIST_SCHEMA);
    Schema wcSchema = WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(valueSchema);
    WriteComputeProcessor processor = new WriteComputeProcessor(new CollectionTimestampMergeRecordHelper());
    WriteComputeSeedRmd helper = new WriteComputeSeedRmd();
    Schema rmdSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);

    final int operandCount = 8;
    final int elementsPerOperand = 50;

    // V1 path: apply each operand in sequence to the same base record.
    GenericRecord v1Result = makeBase(valueSchema, 0);
    for (int i = 0; i < operandCount; i++) {
      GenericRecord wc =
          makeSetUnionRecord(wcSchema, /*from=*/ i * elementsPerOperand, /*to=*/ (i + 1) * elementsPerOperand);
      v1Result = processor.updateRecord(valueSchema, v1Result, wc);
    }

    // V2 path: same chain, V2 with a fresh seed RMD per operand and monotonically increasing
    // modifyTs. This mirrors what MaterializingFoldContext.foldOperands does on read.
    GenericRecord v2Result = makeBase(valueSchema, 0);
    for (int i = 0; i < operandCount; i++) {
      GenericRecord wc =
          makeSetUnionRecord(wcSchema, /*from=*/ i * elementsPerOperand, /*to=*/ (i + 1) * elementsPerOperand);
      GenericRecord seedRmd = helper.buildSeedRmd(rmdSchema, valueSchema);
      final GenericRecord prevResult = v2Result;
      v2Result = processor
          .updateRecordWithRmd(valueSchema, new ValueAndRmd<>(Lazy.of(() -> prevResult), seedRmd), wc, i + 1L, -1)
          .getValue();
    }

    List<Float> v1List = (List<Float>) v1Result.get("floatArray");
    List<Float> v2List = (List<Float>) v2Result.get("floatArray");
    Assert.assertEquals(
        new java.util.HashSet<>(v2List),
        new java.util.HashSet<>(v1List),
        "V2-with-seed-RMD chain must produce the same set as V1 chain");
    Assert.assertEquals(v2List.size(), v1List.size(), "V2 chain result size must equal V1 chain result size");
    Assert.assertEquals(v2List.size(), operandCount * elementsPerOperand, "Total size must equal sum of operands");
  }

  /**
   * Reset functionality: after building a seed RMD and mutating it via a V2 call, resetting
   * must return it to put-only state with topLevelTs=0 — allowing reuse across fold-loop
   * iterations without re-allocating.
   */
  @Test
  public void testResetSeedRmdReturnsToPutOnlyState() {
    Schema valueSchema = AvroCompatibilityHelper.parse(FLOAT_LIST_SCHEMA);
    Schema wcSchema = WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(valueSchema);
    WriteComputeProcessor processor = new WriteComputeProcessor(new CollectionTimestampMergeRecordHelper());
    WriteComputeSeedRmd helper = new WriteComputeSeedRmd();
    Schema rmdSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);

    GenericRecord seed = helper.buildSeedRmd(rmdSchema, valueSchema);
    GenericRecord base = makeBase(valueSchema, 50);
    GenericRecord wc = makeSetUnionRecord(wcSchema, 50, 100);
    processor.updateRecordWithRmd(valueSchema, new ValueAndRmd<>(Lazy.of(() -> base), seed), wc, 1000L, -1);

    // Sanity-check seed has been mutated: V2's handleModifyPutOnlyList does NOT raise the
    // topLevelTs (it stays at 0); instead it populates the active-element-timestamps list
    // with one entry per *newly added* element at modify-ts=1000 (entries 50..99). Pre-existing
    // elements 0..49 keep their topLevelTs=0 anchor and stay in the put-only part of the list.
    GenericRecord perFieldTs = (GenericRecord) seed.get(TIMESTAMP_FIELD_POS);
    GenericRecord arrayRmd = (GenericRecord) perFieldTs.get("floatArray");
    @SuppressWarnings("unchecked")
    List<Long> activeTsList = (List<Long>) arrayRmd.get(ACTIVE_ELEM_TS_FIELD_NAME);
    Assert.assertFalse(activeTsList.isEmpty(), "Sanity: V2 must have populated the active-ts list");
    Assert.assertEquals(
        (long) activeTsList.get(activeTsList.size() - 1),
        1000L,
        "Sanity: the last active ts must equal the modify ts");

    helper.resetSeedRmd(seed, valueSchema);

    GenericRecord postResetPerField = (GenericRecord) seed.get(TIMESTAMP_FIELD_POS);
    GenericRecord postResetArrayRmd = (GenericRecord) postResetPerField.get("floatArray");
    Assert.assertEquals(postResetArrayRmd.get(TOP_LEVEL_TS_FIELD_NAME), 0L, "Reset must zero topLevelTs");
    Assert.assertEquals(postResetArrayRmd.get(PUT_ONLY_PART_LENGTH_FIELD_NAME), 0);
    Assert.assertEquals(((List<?>) postResetArrayRmd.get(ACTIVE_ELEM_TS_FIELD_NAME)).size(), 0);
    Assert.assertEquals(((List<?>) postResetArrayRmd.get(DELETED_ELEM_FIELD_NAME)).size(), 0);
    Assert.assertEquals(((List<?>) postResetArrayRmd.get(DELETED_ELEM_TS_FIELD_NAME)).size(), 0);
  }

  /**
   * SET_UNION + SET_DIFF interaction: V2-with-seed-RMD must drop diff-listed elements just
   * like V1, so that delete operations behave correctly in the fold path.
   */
  @Test
  public void testV2WithSeedRmdHandlesSetDiff() {
    Schema valueSchema = AvroCompatibilityHelper.parse(FLOAT_LIST_SCHEMA);
    Schema wcSchema = WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(valueSchema);
    WriteComputeProcessor processor = new WriteComputeProcessor(new CollectionTimestampMergeRecordHelper());
    WriteComputeSeedRmd helper = new WriteComputeSeedRmd();
    Schema rmdSchema = helper.getRmdSchema(STORE_NAME, VALUE_SCHEMA_ID, valueSchema);

    // Base has [0.0..9.0]; operand adds [10.0..14.0] and removes 5.0,6.0.
    GenericRecord base = makeBase(valueSchema, 10);
    Schema listOpsSchema = listOpsSchemaOf(wcSchema, "floatArray");
    GenericRecord listOps = new GenericData.Record(listOpsSchema);
    List<Float> toAdd = new ArrayList<>();
    for (int i = 10; i < 15; i++) {
      toAdd.add((float) i);
    }
    List<Float> toRemove = new ArrayList<>();
    toRemove.add(5.0f);
    toRemove.add(6.0f);
    listOps.put(SET_UNION, toAdd);
    listOps.put(SET_DIFF, toRemove);
    GenericRecord wc = new GenericData.Record(wcSchema);
    wc.put("floatArray", listOps);

    GenericRecord seedRmd = helper.buildSeedRmd(rmdSchema, valueSchema);
    GenericRecord result =
        processor.updateRecordWithRmd(valueSchema, new ValueAndRmd<>(Lazy.of(() -> base), seedRmd), wc, /*ts*/ 1L, -1)
            .getValue();

    List<Float> resultList = (List<Float>) result.get("floatArray");
    Assert.assertFalse(resultList.contains(5.0f), "5.0 must be removed by SET_DIFF");
    Assert.assertFalse(resultList.contains(6.0f), "6.0 must be removed by SET_DIFF");
    Assert.assertTrue(resultList.contains(10.0f), "10.0 must be present from SET_UNION");
    Assert.assertTrue(resultList.contains(14.0f), "14.0 must be present from SET_UNION");
    Assert.assertEquals(resultList.size(), 10 - 2 + 5, "Final size = base - removed + added");
  }

  // ----- helpers -----

  private GenericRecord makeBase(Schema valueSchema, int size) {
    GenericRecord base = new GenericData.Record(valueSchema);
    List<Float> existing = new ArrayList<>(size);
    for (int j = 0; j < size; j++) {
      existing.add((float) j);
    }
    base.put("floatArray", existing);
    return base;
  }

  private GenericRecord makeSetUnionRecord(Schema wcSchema, int fromInclusive, int toExclusive) {
    Schema listOpsSchema = listOpsSchemaOf(wcSchema, "floatArray");
    GenericRecord listOps = new GenericData.Record(listOpsSchema);
    List<Float> entries = new ArrayList<>(toExclusive - fromInclusive);
    for (int j = fromInclusive; j < toExclusive; j++) {
      entries.add((float) j);
    }
    listOps.put(SET_UNION, entries);
    listOps.put(SET_DIFF, Collections.emptyList());
    GenericRecord wc = new GenericData.Record(wcSchema);
    wc.put("floatArray", listOps);
    return wc;
  }

  private static Schema listOpsSchemaOf(Schema wcSchema, String fieldName) {
    Schema unionSchema = wcSchema.getField(fieldName).schema();
    for (Schema branch: unionSchema.getTypes()) {
      if (branch.getType() == Schema.Type.RECORD && branch.getField(SET_UNION) != null) {
        return branch;
      }
    }
    throw new IllegalStateException("Could not find ListOps branch in " + unionSchema);
  }
}
