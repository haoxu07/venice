package com.linkedin.davinci.schema.writecompute;

import static com.linkedin.venice.schema.rmd.RmdConstants.TIMESTAMP_FIELD_POS;
import static com.linkedin.venice.schema.writecompute.WriteComputeConstants.SET_DIFF;
import static com.linkedin.venice.schema.writecompute.WriteComputeConstants.SET_UNION;

import com.linkedin.avroutil1.compatibility.AvroCompatibilityHelper;
import com.linkedin.davinci.schema.merge.CollectionTimestampBuilder;
import com.linkedin.davinci.schema.merge.CollectionTimestampMergeRecordHelper;
import com.linkedin.davinci.schema.merge.ValueAndRmd;
import com.linkedin.venice.schema.rmd.RmdSchemaGenerator;
import com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter;
import com.linkedin.venice.utils.lazy.Lazy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.testng.annotations.Test;


/**
 * Microbenchmark that compares per-call wall time of:
 *   (a) WriteComputeProcessor.updateRecord       — used by the read-path fold (V1 updateArray, O(N×M) contains-check)
 *   (b) WriteComputeProcessor.updateRecordWithRmd — used by the baseline AA leader RMW (V2 SortBasedCollectionFieldOpHandler)
 * on the same workload: appending 10,000 floats to an existing list of size N for N in {10K, 50K, 100K, 200K, 380K}.
 *
 * <p>The integration test {@code testActiveActivePartialUpdateWithCompression} drives this exact workload —
 * 39 sequential UPDATEs of 10K floats each. Flag-off (path (b)) passes; flag-on (path (a)) fails with read-side
 * SocketTimeoutException. This microbench isolates the apply step from infrastructure noise.
 */
public class WriteComputeArrayApplyMicrobenchmark {
  private static final String VALUE_SCHEMA_STR =
      "{\n" + "  \"type\": \"record\",\n" + "  \"name\": \"FloatList\",\n" + "  \"fields\": [\n"
          + "    {\"name\": \"floatArray\", \"type\": {\"type\": \"array\", \"items\": \"float\"}, \"default\": []}\n"
          + "  ]\n" + "}";

  private static final int OPERAND_SIZE = 10_000;
  private static final int[] BASE_SIZES = { 10_000, 50_000, 100_000, 200_000, 380_000 };
  private static final int WARMUP_ITERATIONS = 1;
  private static final int MEASURE_ITERATIONS = 3;

  @Test
  public void microbenchmarkArrayApply() {
    Schema valueSchema = AvroCompatibilityHelper.parse(VALUE_SCHEMA_STR);
    // convertFromValueRecordSchema returns the write-op record schema directly (not wrapped in a union).
    Schema writeComputeRecordSchema =
        WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(valueSchema);
    Schema floatArrayWcFieldSchema = writeComputeRecordSchema.getField("floatArray").schema();
    // The list-ops branch is the one containing SET_UNION + SET_DIFF
    Schema listOpsSchema = null;
    for (Schema branch: floatArrayWcFieldSchema.getTypes()) {
      if (branch.getType() == Schema.Type.RECORD && branch.getField(SET_UNION) != null) {
        listOpsSchema = branch;
        break;
      }
    }
    if (listOpsSchema == null) {
      throw new IllegalStateException("Could not locate ListOps branch in WC schema: " + floatArrayWcFieldSchema);
    }

    WriteComputeProcessor wcProcessor = new WriteComputeProcessor(new CollectionTimestampMergeRecordHelper());

    Schema rmdSchema = RmdSchemaGenerator.generateMetadataSchema(valueSchema, 1);

    System.out.println("=== WC Apply Microbenchmark ===");
    System.out.println("Adding " + OPERAND_SIZE + " floats to a list of size N via SET_UNION.");
    System.out.println(
        "Each measurement: median over " + MEASURE_ITERATIONS + " runs after " + WARMUP_ITERATIONS
            + " warmup iteration(s).");
    System.out.println();
    System.out
        .printf("%-12s %-22s %-22s %-10s%n", "baseSize", "V1 updateRecord(ms)", "V2 updateRecordWithRmd(ms)", "ratio");
    System.out
        .printf("%-12s %-22s %-22s %-10s%n", "--------", "-------------------", "--------------------------", "-----");

    for (int baseSize: BASE_SIZES) {
      double v1Median = measureV1(valueSchema, writeComputeRecordSchema, listOpsSchema, wcProcessor, baseSize);
      double v2Median =
          measureV2(valueSchema, rmdSchema, writeComputeRecordSchema, listOpsSchema, wcProcessor, baseSize);
      System.out.printf("%-12d %-22.2f %-22.2f %-10.1f%n", baseSize, v1Median, v2Median, v1Median / v2Median);
    }
  }

  private double measureV1(
      Schema valueSchema,
      Schema writeComputeRecordSchema,
      Schema listOpsSchema,
      WriteComputeProcessor wcProcessor,
      int baseSize) {
    List<Double> samples = new ArrayList<>();
    for (int i = 0; i < WARMUP_ITERATIONS + MEASURE_ITERATIONS; i++) {
      GenericRecord base = freshBaseRecord(valueSchema, baseSize);
      GenericRecord wcRecord = freshWcRecord(writeComputeRecordSchema, listOpsSchema, baseSize);
      long t0 = System.nanoTime();
      GenericRecord result = wcProcessor.updateRecord(valueSchema, base, wcRecord);
      long t1 = System.nanoTime();
      // Touch the result to prevent dead-code elimination.
      if (((List<?>) result.get("floatArray")).size() < baseSize) {
        throw new IllegalStateException("Result list smaller than base; unexpected");
      }
      if (i >= WARMUP_ITERATIONS) {
        samples.add((t1 - t0) / 1_000_000.0);
      }
    }
    Collections.sort(samples);
    return samples.get(samples.size() / 2);
  }

  private double measureV2(
      Schema valueSchema,
      Schema rmdSchema,
      Schema writeComputeRecordSchema,
      Schema listOpsSchema,
      WriteComputeProcessor wcProcessor,
      int baseSize) {
    List<Double> samples = new ArrayList<>();
    for (int i = 0; i < WARMUP_ITERATIONS + MEASURE_ITERATIONS; i++) {
      GenericRecord base = freshBaseRecord(valueSchema, baseSize);
      GenericRecord rmd = freshRmdRecord(rmdSchema);
      ValueAndRmd<GenericRecord> valueAndRmd = new ValueAndRmd<>(Lazy.of(() -> base), rmd);
      GenericRecord wcRecord = freshWcRecord(writeComputeRecordSchema, listOpsSchema, baseSize);
      long t0 = System.nanoTime();
      ValueAndRmd<GenericRecord> result =
          wcProcessor.updateRecordWithRmd(valueSchema, valueAndRmd, wcRecord, /*ts*/ 1000L, /*coloID*/ 0);
      long t1 = System.nanoTime();
      if (((List<?>) result.getValue().get("floatArray")).size() < baseSize) {
        throw new IllegalStateException("Result list smaller than base; unexpected");
      }
      if (i >= WARMUP_ITERATIONS) {
        samples.add((t1 - t0) / 1_000_000.0);
      }
    }
    Collections.sort(samples);
    return samples.get(samples.size() / 2);
  }

  private GenericRecord freshBaseRecord(Schema valueSchema, int baseSize) {
    GenericRecord base = new GenericData.Record(valueSchema);
    List<Float> existing = new ArrayList<>(baseSize);
    for (int j = 0; j < baseSize; j++) {
      existing.add((float) j);
    }
    base.put("floatArray", existing);
    return base;
  }

  private GenericRecord freshWcRecord(Schema writeComputeRecordSchema, Schema listOpsSchema, int baseOffset) {
    GenericRecord listOps = new GenericData.Record(listOpsSchema);
    List<Float> newEntries = new ArrayList<>(OPERAND_SIZE);
    // Generate values that are NOT already present in the base. This mirrors the integration test workload
    // where updateCount * singleUpdateEntryCount + j produces non-overlapping floats per UPDATE.
    for (int j = 0; j < OPERAND_SIZE; j++) {
      newEntries.add((float) (baseOffset + j));
    }
    listOps.put(SET_UNION, newEntries);
    listOps.put(SET_DIFF, Collections.emptyList());
    GenericRecord wcRecord = new GenericData.Record(writeComputeRecordSchema);
    wcRecord.put("floatArray", listOps);
    return wcRecord;
  }

  private GenericRecord freshRmdRecord(Schema rmdSchema) {
    GenericRecord rmd = new GenericData.Record(rmdSchema);
    // RMD shape: { timestamp: <per-field-ts record OR long>, replication_checkpoint_vector: array<long> }
    // The per-field-ts record has one field per value-record field. For our single "floatArray" collection field,
    // the timestamp value is a CollectionRmdTimestamp record.
    Schema perFieldTsSchema = rmdSchema.getFields().get(TIMESTAMP_FIELD_POS).schema().getTypes().get(1);
    GenericRecord perFieldTs = new GenericData.Record(perFieldTsSchema);
    Schema collectionTsSchema = perFieldTsSchema.getField("floatArray").schema();
    CollectionTimestampBuilder builder = new CollectionTimestampBuilder(Schema.create(Schema.Type.FLOAT));
    builder.setCollectionTimestampSchema(collectionTsSchema);
    builder.setTopLevelTimestamps(1L);
    builder.setTopLevelColoID(-1);
    builder.setPutOnlyPartLength(0);
    perFieldTs.put("floatArray", builder.build());
    rmd.put(TIMESTAMP_FIELD_POS, perFieldTs);
    rmd.put(1, Collections.emptyList());
    return rmd;
  }
}
