package com.linkedin.venice.benchmark;

import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.NUM_RECORDS_PER_INVOCATION;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.PARTIAL_UPDATE_KEY_POOL_SIZE;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.PARTIAL_UPDATE_SENTINEL_COUNT;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.PUT_KEY_POOL_SIZE;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.TAGS_MAP_SIZE;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.VT_CHECK_SENTINEL_COUNT;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.buildCanaryRecord;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.buildPartialUpdatePoolInitRecord;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.buildPutRecord;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.buildPutSentinelRecord;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.mixedKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.partialUpdateFinalSentinelKeys;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.partialUpdatePoolCheckIndices;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.partialUpdatePoolKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.partialUpdatePoolPrePopulateKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.partialUpdateSentinelKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.putPoolKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.putSentinelKey;
import static com.linkedin.venice.utils.TestUtils.waitForNonDeterministicAssertion;

import com.linkedin.davinci.store.StorageEngine;
import com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.WorkloadType;
import com.linkedin.venice.benchmark.lean.MinimalAAIngestionHarness;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.partitioner.DefaultVenicePartitioner;
import com.linkedin.venice.partitioner.VenicePartitioner;
import com.linkedin.venice.serializer.AvroSerializer;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.update.UpdateBuilder;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.GCProfiler;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;


/**
 * End-to-end JMH benchmark for Active-Active ingestion using the lean
 * {@link MinimalAAIngestionHarness} instead of the full
 * {@link com.linkedin.venice.integration.utils.VeniceTwoLayerMultiRegionMultiClusterWrapper}.
 *
 * <p>This is a side-by-side counterpart to {@link ActiveActiveIngestionBenchmark}: same workload
 * (record shape, key pool, alternating producers, sentinels), same E2E timing instrumentation,
 * same end-of-trial VT consistency check — but the cluster layer is replaced by the harness.
 *
 * <p>The harness brings up only what {@code ActiveActiveStoreIngestionTask} actually needs:
 * 2 real Kafka brokers, 2 real RocksDB-backed StorageService instances, and the AA ingestion task
 * per region. Helix, ZK, controllers, routers, D2 are all removed. Cluster startup is ~10s vs
 * ~2 min for the full wrapper.
 *
 * <p>Differences vs the full benchmark, all of which preserve workload semantics:
 * <ul>
 *   <li>Records are produced via per-region {@link VeniceWriter} (lean harness exposes one writer
 *       per region's RT topic) instead of {@code VeniceSystemProducer}. The wire format on RT is
 *       identical: KME-wrapped PUT/UPDATE/DELETE messages with the same key/value bytes.</li>
 *   <li>Drain visibility is verified by reading from the per-region RocksDB
 *       {@link StorageEngine} (with the partition computed from the partitioner) instead of via
 *       a router-based read client. This is a strictly faster path with the same semantics.</li>
 *   <li>The end-of-trial VT consistency check uses the harness's broker URLs directly.</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :internal:venice-test-common:jmh -Pjmh.includes='LeanActiveActiveIngestionBenchmark'
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = { "-Xms4G", "-Xmx4G" })
@Warmup(iterations = 2, time = 30)
@Measurement(iterations = 3, time = 60)
public class LeanActiveActiveIngestionBenchmark {
  private static final int REGION_COUNT = 2;
  // Use the same partition count as the full benchmark (2) so the partition-distribution / DCR
  // workload is identical between the two.
  private static final int PARTITION_COUNT = 2;
  private static final int VERSION_NUMBER = 1;
  // Schema IDs match {@code InMemoryReadOnlySchemaRepository} (value=1, write-compute=1, RMD=1).
  private static final int VALUE_SCHEMA_ID = 1;
  private static final int WRITE_COMPUTE_DERIVED_SCHEMA_ID = 1;

  @Param({ "PUT", "PARTIAL_UPDATE", "MIXED" })
  private WorkloadType workloadType;

  private MinimalAAIngestionHarness harness;
  private String storeName;
  private String versionTopicName;
  private Schema valueSchema;
  private Schema writeComputeSchema;

  // Per-region RT writers (used to send PUTs/UPDATEs/DELETEs into the AA pipeline).
  private VeniceWriter<byte[], byte[], byte[]> writerDC0;
  private VeniceWriter<byte[], byte[], byte[]> writerDC1;
  // Per-region RocksDB engines (used by the iteration teardown drain check and pre-population).
  private StorageEngine engineDC0;
  private StorageEngine engineDC1;

  private AvroSerializer<String> keySerializer;
  private AvroSerializer<GenericRecord> valueSerializer;
  private AvroSerializer<GenericRecord> writeComputeSerializer;
  private VenicePartitioner partitioner;

  private final AtomicLong keyCounter = new AtomicLong(0);
  // Updated by each invocation; read at iteration teardown to verify ingestion caught up.
  private volatile String lastProducedKey;
  // Wall-clock end-to-end measurement (mirrors ActiveActiveIngestionBenchmark).
  private final AtomicLong iterationRecordCount = new AtomicLong(0);
  private volatile long iterationStartNanos;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    Utils.thisIsLocalhost();
    long setupStartNanos = System.nanoTime();

    storeName = Utils.getUniqueString("lean-aa-benchmark-store");
    MinimalAAIngestionHarness.Config config =
        new MinimalAAIngestionHarness.Config(REGION_COUNT, PARTITION_COUNT, storeName);
    harness = new MinimalAAIngestionHarness(config);

    long harnessStartNanos = System.nanoTime();
    harness.start();
    long harnessStartElapsedMs = (System.nanoTime() - harnessStartNanos) / 1_000_000L;
    System.err.println(
        "[LeanActiveActiveIngestionBenchmark] harness.start() took " + harnessStartElapsedMs + " ms");

    versionTopicName = Version.composeKafkaTopic(storeName, VERSION_NUMBER);

    // Schemas come from the harness's in-memory schema repo (which is pre-loaded with the same
    // BenchmarkRecord value schema + RMD + WC schemas as the full benchmark).
    valueSchema = harness.getSchemaRepository().getValueSchema(storeName, VALUE_SCHEMA_ID).getSchema();
    writeComputeSchema =
        harness.getSchemaRepository().getDerivedSchema(storeName, VALUE_SCHEMA_ID, WRITE_COMPUTE_DERIVED_SCHEMA_ID)
            .getSchema();

    keySerializer = new AvroSerializer<>(Schema.create(Schema.Type.STRING));
    valueSerializer = new AvroSerializer<>(valueSchema);
    writeComputeSerializer = new AvroSerializer<>(writeComputeSchema);
    partitioner = new DefaultVenicePartitioner();

    writerDC0 = harness.getVeniceWriterForRTTopic(0);
    writerDC1 = harness.getVeniceWriterForRTTopic(1);
    engineDC0 = harness.getStorageEngineForRegion(0);
    engineDC1 = harness.getStorageEngineForRegion(1);

    // Verify the pipeline is working with a canary record. This is the lean-harness equivalent of
    // the full benchmark's "wait for canary visible on router" step, but uses RocksDB directly.
    GenericRecord canary = buildCanaryRecord(valueSchema);
    String canaryKey = "canary-key";
    sendPut(writerDC0, canaryKey, canary);
    waitForKeysVisibleOnBothRegions(new String[] { canaryKey }, 60, TimeUnit.SECONDS);

    // For PARTIAL_UPDATE, pre-populate each bounded-pool key with a TAGS_MAP_SIZE-entry tags map.
    if (workloadType == WorkloadType.PARTIAL_UPDATE) {
      prePopulatePartialUpdatePool();
    }

    // JMH benchmark relies on System.exit to finish one round of benchmark run, otherwise it will
    // hang there.
    TestUtils.restoreSystemExit();

    long setupElapsedMs = (System.nanoTime() - setupStartNanos) / 1_000_000L;
    System.err
        .println("[LeanActiveActiveIngestionBenchmark] setUp() complete in " + setupElapsedMs + " ms");
  }

  private void prePopulatePartialUpdatePool() throws Exception {
    System.err.println(
        "[LeanActiveActiveIngestionBenchmark] Pre-populating " + PARTIAL_UPDATE_KEY_POOL_SIZE + " pool keys with "
            + TAGS_MAP_SIZE + "-entry tags map...");
    for (int poolIdx = 0; poolIdx < PARTIAL_UPDATE_KEY_POOL_SIZE; poolIdx++) {
      String key = partialUpdatePoolPrePopulateKey(poolIdx);
      GenericRecord rec = buildPartialUpdatePoolInitRecord(valueSchema, poolIdx);
      sendPutWithoutFlush(writerDC0, key, rec);
    }
    writerDC0.flush();

    int[] checkIndices = partialUpdatePoolCheckIndices();
    String[] checkKeys = new String[checkIndices.length];
    for (int i = 0; i < checkIndices.length; i++) {
      checkKeys[i] = partialUpdatePoolPrePopulateKey(checkIndices[i]);
    }
    waitForKeysVisibleOnBothRegions(checkKeys, 5, TimeUnit.MINUTES);
    System.err.println("[LeanActiveActiveIngestionBenchmark] Pre-population complete.");
  }

  @TearDown(Level.Trial)
  public void cleanUp() {
    // For PARTIAL_UPDATE, run the Spark-based VT consistency check across both regions before
    // stopping the harness. Reports counts of VALUE_MISMATCH and MISSING rows to stderr.
    if (workloadType == WorkloadType.PARTIAL_UPDATE) {
      try {
        runVTConsistencyCheck();
      } catch (Throwable t) {
        System.err.println("[VT-CHECK] Failed: " + t);
        t.printStackTrace(System.err);
      }
    }
    if (harness != null) {
      try {
        harness.stop();
      } catch (Throwable t) {
        System.err.println("[LeanActiveActiveIngestionBenchmark] harness.stop() threw: " + t);
      }
    }
  }

  /**
   * Mirror of {@code ActiveActiveIngestionBenchmark#runVTConsistencyCheck} but using the harness's
   * broker URLs (no router / controller). Sends a final round of sentinels via both writers and
   * waits for them to be visible on BOTH regions' RocksDB before invoking
   * {@link com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob}.
   */
  private void runVTConsistencyCheck() throws Exception {
    final int finalSentinelCount = VT_CHECK_SENTINEL_COUNT;
    long startSeq = keyCounter.getAndAdd(finalSentinelCount * 2L);
    String[] dc0Keys = partialUpdateFinalSentinelKeys(startSeq, finalSentinelCount, 0);
    String[] dc1Keys = partialUpdateFinalSentinelKeys(startSeq + finalSentinelCount, finalSentinelCount, 1);
    for (int s = 0; s < finalSentinelCount; s++) {
      UpdateBuilder ubA = new UpdateBuilderImpl(writeComputeSchema);
      ubA.setNewFieldValue("name", "final-dc0-" + (startSeq + s));
      sendUpdateWithoutFlush(writerDC0, dc0Keys[s], ubA.build());
      UpdateBuilder ubB = new UpdateBuilderImpl(writeComputeSchema);
      ubB.setNewFieldValue("name", "final-dc1-" + (startSeq + finalSentinelCount + s));
      sendUpdateWithoutFlush(writerDC1, dc1Keys[s], ubB.build());
    }
    writerDC0.flush();
    writerDC1.flush();

    System.err.println("[VT-CHECK] Waiting for both-region drain via dual-engine sentinel verification...");
    waitForKeysVisibleOnBothRegions(concat(dc0Keys, dc1Keys), 10, TimeUnit.MINUTES);
    System.err.println("[VT-CHECK] Drain confirmed on both regions. Running consistency check...");

    String dc0Broker = harness.getBrokerAddress(0);
    String dc1Broker = harness.getBrokerAddress(1);
    java.io.File tempRoot = java.nio.file.Files.createTempDirectory("vt-consistency-bench-lean").toFile();
    java.io.File outputDir = new java.io.File(tempRoot, "output");
    try {
      Properties jobProps = new Properties();
      jobProps.setProperty(com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.DC0_BROKER_URL, dc0Broker);
      jobProps.setProperty(com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.DC1_BROKER_URL, dc1Broker);
      jobProps.setProperty(
          com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.VERSION_TOPIC,
          versionTopicName);
      jobProps.setProperty(
          com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.OUTPUT_PATH,
          outputDir.getAbsolutePath());
      jobProps.setProperty(com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.NUMBER_OF_REGIONS, "2");
      com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.run(jobProps);

      org.apache.spark.sql.SparkSession spark = org.apache.spark.sql.SparkSession.builder()
          .master("local[*]")
          .appName("LeanAAIngestionBenchmarkVTConsistencyReader")
          .getOrCreate();
      try {
        org.apache.spark.sql.Dataset<org.apache.spark.sql.Row> result =
            spark.read().parquet(outputDir.getAbsolutePath());
        long mismatches = result.filter("type = 'VALUE_MISMATCH'").count();
        long missing = result.filter("type = 'MISSING'").count();
        long errors = result.filter("type = 'ERROR'").count();
        System.err.println(
            String.format(
                "[VT-CHECK] versionTopic=%s mismatches=%d missing=%d errors=%d",
                versionTopicName,
                mismatches,
                missing,
                errors));
        if (mismatches > 0) {
          System.err.println("[VT-CHECK] First 5 mismatch rows (forensic context):");
          result.filter("type = 'VALUE_MISMATCH'")
              .limit(5)
              .collectAsList()
              .forEach(r -> System.err.println("[VT-CHECK]   " + r));
        }
      } finally {
        spark.stop();
      }
    } finally {
      org.apache.commons.io.FileUtils.deleteDirectory(tempRoot);
    }
  }

  private static String[] concat(String[] a, String[] b) {
    String[] combined = new String[a.length + b.length];
    System.arraycopy(a, 0, combined, 0, a.length);
    System.arraycopy(b, 0, combined, a.length, b.length);
    return combined;
  }

  /**
   * Resets per-iteration counters and captures the iteration start timestamp. Runs before each
   * warmup/measurement iteration, outside the measured window.
   */
  @Setup(Level.Iteration)
  public void startIterationClock() {
    iterationRecordCount.set(0);
    lastProducedKey = null;
    iterationStartNanos = System.nanoTime();
  }

  @Benchmark
  @OperationsPerInvocation(NUM_RECORDS_PER_INVOCATION)
  public void benchmarkAAIngestion() {
    String lastKey;
    switch (workloadType) {
      case PUT:
        lastKey = runPutWorkload();
        break;
      case PARTIAL_UPDATE:
        lastKey = runPartialUpdateWorkload();
        break;
      case MIXED:
        lastKey = runMixedWorkload();
        break;
      default:
        throw new IllegalStateException("Unknown workload: " + workloadType);
    }
    if (lastKey != null) {
      lastProducedKey = lastKey;
    }
    iterationRecordCount.addAndGet(NUM_RECORDS_PER_INVOCATION);
  }

  /**
   * Runs once per iteration, outside the measured window. Mirrors the full-wrapper benchmark's
   * E2E reporting line so the two are directly comparable.
   */
  @TearDown(Level.Iteration)
  public void finishIterationAndReportE2E() {
    String keysToVerify = lastProducedKey;
    long records = iterationRecordCount.get();
    if (keysToVerify == null || records == 0) {
      return;
    }
    String[] keys = keysToVerify.split(",");
    waitForKeysVisibleOnBothRegions(keys, 10, TimeUnit.MINUTES);
    long elapsedNanos = System.nanoTime() - iterationStartNanos;
    double opsPerSec = records * 1e9 / elapsedNanos;
    System.err.println(
        String.format(
            "[E2E] workload=%s records=%d elapsed_ms=%d e2e_throughput_ops_per_sec=%.2f",
            workloadType,
            records,
            elapsedNanos / 1_000_000L,
            opsPerSec));
  }

  // ---------------- Workload methods (mirror ActiveActiveIngestionBenchmark) ----------------

  private String runPutWorkload() {
    for (int i = 0; i < NUM_RECORDS_PER_INVOCATION; i++) {
      long seq = keyCounter.getAndIncrement();
      String key = putPoolKey(seq);
      int dcIdx = i % 2;
      GenericRecord record = buildPutRecord(valueSchema, seq, dcIdx);
      VeniceWriter<byte[], byte[], byte[]> writer = (dcIdx == 0) ? writerDC0 : writerDC1;
      sendPutWithoutFlush(writer, key, record);
    }

    long sentinelSeq = keyCounter.getAndIncrement();
    String sentinelKey = putSentinelKey(sentinelSeq);
    GenericRecord sentinelRecord = buildPutSentinelRecord(valueSchema, sentinelSeq);
    sendPutWithoutFlush(writerDC1, sentinelKey, sentinelRecord);

    writerDC0.flush();
    writerDC1.flush();
    return sentinelKey;
  }

  private String runPartialUpdateWorkload() {
    for (int i = 0; i < NUM_RECORDS_PER_INVOCATION; i++) {
      long seq = keyCounter.getAndIncrement();
      String key = partialUpdatePoolKey(seq);
      UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
      int fieldChoice = (int) (seq % 3);
      if (fieldChoice == 0) {
        ub.setNewFieldValue("name", "upd-" + seq);
      } else if (fieldChoice == 1) {
        ub.setNewFieldValue("age", (int) (seq % 100_000));
      } else {
        Map<String, String> mapUpdate = new HashMap<>(2);
        String mapKey = "k-" + (seq % TAGS_MAP_SIZE);
        mapUpdate.put(mapKey, "v-" + seq);
        ub.setEntriesToAddToMapField("tags", mapUpdate);
      }
      VeniceWriter<byte[], byte[], byte[]> writer = (i % 2 == 0) ? writerDC0 : writerDC1;
      sendUpdateWithoutFlush(writer, key, ub.build());
    }

    StringBuilder sentinelKeys = new StringBuilder();
    for (int s = 0; s < PARTIAL_UPDATE_SENTINEL_COUNT; s++) {
      long sentinelSeq = keyCounter.getAndIncrement();
      String sentinelKey = partialUpdateSentinelKey(sentinelSeq);
      UpdateBuilder sentinelUb = new UpdateBuilderImpl(writeComputeSchema);
      sentinelUb.setNewFieldValue("name", "sentinel-" + sentinelSeq);
      sendUpdateWithoutFlush(writerDC1, sentinelKey, sentinelUb.build());
      if (s > 0) {
        sentinelKeys.append(',');
      }
      sentinelKeys.append(sentinelKey);
    }

    writerDC0.flush();
    writerDC1.flush();
    return sentinelKeys.toString();
  }

  private String runMixedWorkload() {
    long baseKey = keyCounter.getAndAdd(NUM_RECORDS_PER_INVOCATION);
    String lastWrittenKey = null;
    for (int i = 0; i < NUM_RECORDS_PER_INVOCATION; i++) {
      String key = mixedKey(baseKey, i);
      VeniceWriter<byte[], byte[], byte[]> writer = (i % 2 == 0) ? writerDC0 : writerDC1;
      int op = i % 10;
      if (op < 4) {
        // 40% PUTs
        GenericRecord record = new org.apache.avro.generic.GenericData.Record(valueSchema);
        record.put("name", "user-" + i);
        record.put("age", i % 100);
        record.put("score", i * 1.1);
        Map<String, String> tags = new HashMap<>(2);
        tags.put("tag-" + i, "val-" + i);
        record.put("tags", tags);
        sendPutWithoutFlush(writer, key, record);
        lastWrittenKey = key;
      } else if (op < 7) {
        // 30% partial updates (field-level)
        UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
        ub.setNewFieldValue("name", "partial-" + i);
        ub.setNewFieldValue("score", i * 2.2);
        sendUpdateWithoutFlush(writer, key, ub.build());
        lastWrittenKey = key;
      } else if (op < 9) {
        // 20% collection merges (AddToMap)
        UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
        Map<String, String> delta = new HashMap<>(4);
        delta.put("k-" + i, "v-" + i);
        delta.put("k2-" + i, "v2-" + i);
        ub.setEntriesToAddToMapField("tags", delta);
        sendUpdateWithoutFlush(writer, key, ub.build());
        lastWrittenKey = key;
      } else {
        // 10% deletes
        sendDeleteWithoutFlush(writer, key);
      }
    }
    writerDC0.flush();
    writerDC1.flush();
    return lastWrittenKey;
  }

  // ---------------- Send helpers (translate {@code SystemProducer} idioms to VeniceWriter) -----

  private void sendPut(VeniceWriter<byte[], byte[], byte[]> writer, String key, GenericRecord record) {
    sendPutWithoutFlush(writer, key, record);
    writer.flush();
  }

  private void sendPutWithoutFlush(VeniceWriter<byte[], byte[], byte[]> writer, String key, GenericRecord record) {
    byte[] keyBytes = keySerializer.serialize(key);
    byte[] valueBytes = valueSerializer.serialize(record);
    writer.put(keyBytes, valueBytes, VALUE_SCHEMA_ID);
  }

  private void sendUpdateWithoutFlush(
      VeniceWriter<byte[], byte[], byte[]> writer,
      String key,
      GenericRecord update) {
    byte[] keyBytes = keySerializer.serialize(key);
    byte[] updateBytes = writeComputeSerializer.serialize(update);
    writer.update(keyBytes, updateBytes, VALUE_SCHEMA_ID, WRITE_COMPUTE_DERIVED_SCHEMA_ID, null);
  }

  private void sendDeleteWithoutFlush(VeniceWriter<byte[], byte[], byte[]> writer, String key) {
    byte[] keyBytes = keySerializer.serialize(key);
    writer.delete(keyBytes, null);
  }

  // ---------------- Drain verification (replaces router-based reads) ----------------

  /**
   * Wait for all of the given keys to be visible on BOTH regions' RocksDB engines (any partition).
   * Computes the partition for each key via the default Venice partitioner and reads from that
   * partition on each region.
   */
  private void waitForKeysVisibleOnBothRegions(String[] keys, long timeout, TimeUnit unit) {
    waitForNonDeterministicAssertion(timeout, unit, true, () -> {
      for (String key: keys) {
        byte[] keyBytes = keySerializer.serialize(key);
        int partition = partitioner.getPartitionId(keyBytes, PARTITION_COUNT);
        byte[] dc0 = engineDC0.get(partition, keyBytes);
        if (dc0 == null) {
          throw new AssertionError("Sentinel not yet ingested on region 0: " + key + " (partition " + partition + ")");
        }
        byte[] dc1 = engineDC1.get(partition, keyBytes);
        if (dc1 == null) {
          throw new AssertionError("Sentinel not yet ingested on region 1: " + key + " (partition " + partition + ")");
        }
      }
    });
  }

  public static void main(String[] args) throws RunnerException {
    org.openjdk.jmh.runner.options.Options opt =
        new OptionsBuilder().include(LeanActiveActiveIngestionBenchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .build();
    new Runner(opt).run();
  }
}
