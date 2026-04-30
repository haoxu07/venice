package com.linkedin.venice.benchmark.lean;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.davinci.store.StorageEngine;
import com.linkedin.venice.partitioner.DefaultVenicePartitioner;
import com.linkedin.venice.partitioner.VenicePartitioner;
import com.linkedin.venice.serializer.AvroSerializer;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.update.UpdateBuilder;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


/**
 * Smoke test for the {@code LeanActiveActiveIngestionBenchmark}'s setup logic.
 *
 * <p>This test does NOT run JMH. It exercises the equivalent of the benchmark's {@code @Setup} +
 * a single PARTIAL_UPDATE invocation + drain verification, all on a fresh harness, to confirm the
 * lean-benchmark plumbing (partition computation, write-compute serialization, RT writer use,
 * RocksDB direct reads) is correct end-to-end. Runs in ~30s.
 *
 * <p>If this passes, the lean benchmark's @Setup is functionally equivalent to the full
 * benchmark's @Setup; the only difference between them is the cluster (harness vs full wrapper),
 * which is the whole point of the side-by-side comparison.
 */
public class LeanBenchmarkSetupSmokeTest {
  private static final String STORE_NAME_PREFIX = "lean-aa-bench-setup-smoke";
  private static final int REGION_COUNT = 2;
  private static final int PARTITION_COUNT = 2;
  private static final int VERSION_NUMBER = 1;
  private static final int VALUE_SCHEMA_ID = 1;
  private static final int WRITE_COMPUTE_DERIVED_SCHEMA_ID = 1;

  private MinimalAAIngestionHarness harness;
  private String storeName;

  @BeforeMethod(alwaysRun = true)
  public void setUp() {
    Utils.thisIsLocalhost();
    storeName = Utils.getUniqueString(STORE_NAME_PREFIX);
    MinimalAAIngestionHarness.Config config =
        new MinimalAAIngestionHarness.Config(REGION_COUNT, PARTITION_COUNT, storeName);
    harness = new MinimalAAIngestionHarness(config);
  }

  @AfterMethod(alwaysRun = true)
  public void tearDown() {
    if (harness != null) {
      try {
        harness.stop();
      } catch (Throwable t) {
        System.err.println("[LeanBenchmarkSetupSmokeTest] stop() threw: " + t);
      }
    }
  }

  /**
   * Stand up the harness, send one canary PUT, send one PARTIAL_UPDATE, send one DELETE, and
   * verify each operation drains to BOTH regions' RocksDB engines.
   *
   * <p>This covers all three operations the lean benchmark's three workload types
   * (PUT / PARTIAL_UPDATE / MIXED) need to exercise.
   */
  @Test(timeOut = 180_000)
  public void testLeanBenchmarkSetupAndAllThreeOperations() throws Exception {
    long startNanos = System.nanoTime();
    harness.start();
    long startMs = (System.nanoTime() - startNanos) / 1_000_000L;
    System.out.println("[LeanBenchmarkSetupSmokeTest] harness.start() took " + startMs + " ms");
    assertTrue(harness.isStarted(), "Harness must report started after start()");
    assertTrue(startMs < 60_000L, "Harness start should be well under 1 min, took " + startMs + " ms");

    Schema valueSchema = harness.getSchemaRepository().getValueSchema(storeName, VALUE_SCHEMA_ID).getSchema();
    Schema writeComputeSchema = harness.getSchemaRepository()
        .getDerivedSchema(storeName, VALUE_SCHEMA_ID, WRITE_COMPUTE_DERIVED_SCHEMA_ID)
        .getSchema();

    AvroSerializer<String> keySerializer = new AvroSerializer<>(Schema.create(Schema.Type.STRING));
    AvroSerializer<GenericRecord> valueSerializer = new AvroSerializer<>(valueSchema);
    AvroSerializer<GenericRecord> wcSerializer = new AvroSerializer<>(writeComputeSchema);
    VenicePartitioner partitioner = new DefaultVenicePartitioner();

    VeniceWriter<byte[], byte[], byte[]> writerDC0 = harness.getVeniceWriterForRTTopic(0);
    VeniceWriter<byte[], byte[], byte[]> writerDC1 = harness.getVeniceWriterForRTTopic(1);
    StorageEngine engineDC0 = harness.getStorageEngineForRegion(0);
    StorageEngine engineDC1 = harness.getStorageEngineForRegion(1);

    // 1. PUT — same path as runPutWorkload's per-record send.
    String putKey = "lean-bench-setup-put-key";
    GenericRecord putRecord = new GenericData.Record(valueSchema);
    putRecord.put("name", "user-put");
    putRecord.put("age", 42);
    putRecord.put("score", 3.14);
    putRecord.put("tags", new HashMap<String, String>());
    byte[] putKeyBytes = keySerializer.serialize(putKey);
    int putPartition = partitioner.getPartitionId(putKeyBytes, PARTITION_COUNT);
    writerDC0.put(putKeyBytes, valueSerializer.serialize(putRecord), VALUE_SCHEMA_ID).get(15, TimeUnit.SECONDS);

    TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
      assertNotNull(engineDC0.get(putPartition, putKeyBytes), "PUT must land on DC0 RocksDB");
      assertNotNull(engineDC1.get(putPartition, putKeyBytes), "PUT must land on DC1 RocksDB (cross-region)");
    });
    System.out.println("[LeanBenchmarkSetupSmokeTest] PUT drained on both regions.");

    // 2. UPDATE (write-compute partial update) — same path as runPartialUpdateWorkload's per-record send.
    String updateKey = "lean-bench-setup-update-key";
    UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
    ub.setNewFieldValue("name", "user-upd-1");
    GenericRecord updateRec = ub.build();
    byte[] updateKeyBytes = keySerializer.serialize(updateKey);
    int updatePartition = partitioner.getPartitionId(updateKeyBytes, PARTITION_COUNT);
    writerDC1.update(
        updateKeyBytes,
        wcSerializer.serialize(updateRec),
        VALUE_SCHEMA_ID,
        WRITE_COMPUTE_DERIVED_SCHEMA_ID,
        null).get(15, TimeUnit.SECONDS);

    TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
      assertNotNull(engineDC0.get(updatePartition, updateKeyBytes), "UPDATE must land on DC0 RocksDB");
      assertNotNull(engineDC1.get(updatePartition, updateKeyBytes), "UPDATE must land on DC1 RocksDB");
    });
    System.out.println("[LeanBenchmarkSetupSmokeTest] PARTIAL_UPDATE drained on both regions.");

    // 3. DELETE — same path as runMixedWorkload's delete branch.
    String deleteKey = "lean-bench-setup-delete-key";
    GenericRecord toBeDeletedRecord = new GenericData.Record(valueSchema);
    toBeDeletedRecord.put("name", "tombstone-target");
    toBeDeletedRecord.put("age", 0);
    toBeDeletedRecord.put("score", 0.0);
    toBeDeletedRecord.put("tags", new HashMap<String, String>());
    byte[] deleteKeyBytes = keySerializer.serialize(deleteKey);
    int deletePartition = partitioner.getPartitionId(deleteKeyBytes, PARTITION_COUNT);
    writerDC0.put(deleteKeyBytes, valueSerializer.serialize(toBeDeletedRecord), VALUE_SCHEMA_ID)
        .get(15, TimeUnit.SECONDS);

    TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
      assertNotNull(engineDC0.get(deletePartition, deleteKeyBytes), "Pre-delete value must be visible on DC0");
      assertNotNull(engineDC1.get(deletePartition, deleteKeyBytes), "Pre-delete value must be visible on DC1");
    });
    writerDC0.delete(deleteKeyBytes, null).get(15, TimeUnit.SECONDS);
    TestUtils.waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
      assertTrue(
          engineDC0.get(deletePartition, deleteKeyBytes) == null,
          "DELETE must remove value on DC0 RocksDB");
      assertTrue(
          engineDC1.get(deletePartition, deleteKeyBytes) == null,
          "DELETE must remove value on DC1 RocksDB");
    });
    System.out.println("[LeanBenchmarkSetupSmokeTest] DELETE drained on both regions.");
  }
}
