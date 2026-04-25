package com.linkedin.venice.benchmark;

import static com.linkedin.venice.ConfigKeys.DEFAULT_MAX_NUMBER_OF_PARTITIONS;
import static com.linkedin.venice.ConfigKeys.NATIVE_REPLICATION_SOURCE_FABRIC;
import static com.linkedin.venice.ConfigKeys.PARENT_KAFKA_CLUSTER_FABRIC_LIST;
import static com.linkedin.venice.ConfigKeys.SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED;
import static com.linkedin.venice.ConfigKeys.SERVER_KAFKA_MAX_POLL_RECORDS;
import static com.linkedin.venice.integration.utils.VeniceClusterWrapperConstants.DEFAULT_PARENT_DATA_CENTER_REGION_NAME;
import static com.linkedin.venice.utils.IntegrationTestPushUtils.sendStreamingRecord;
import static com.linkedin.venice.utils.IntegrationTestPushUtils.sendStreamingRecordWithoutFlush;
import static com.linkedin.venice.utils.TestUtils.assertCommand;
import static com.linkedin.venice.utils.TestUtils.waitForNonDeterministicAssertion;

import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.ClientConfig;
import com.linkedin.venice.client.store.ClientFactory;
import com.linkedin.venice.controllerapi.ControllerClient;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.integration.utils.VeniceControllerWrapper;
import com.linkedin.venice.integration.utils.VeniceMultiClusterWrapper;
import com.linkedin.venice.integration.utils.VeniceMultiRegionClusterCreateOptions;
import com.linkedin.venice.integration.utils.VeniceTwoLayerMultiRegionMultiClusterWrapper;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.samza.VeniceSystemProducer;
import com.linkedin.venice.utils.IntegrationTestPushUtils;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.update.UpdateBuilder;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
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
 * End-to-end JMH benchmark for Active-Active ingestion in a full multi-region Venice cluster.
 *
 * <p>Uses the integration test framework ({@link VeniceTwoLayerMultiRegionMultiClusterWrapper}) to spin up
 * a 2-region cluster with parent controller, child controllers, servers, and routers. Records are produced
 * via {@link VeniceSystemProducer} (Samza) from both regions and consumed through the full AA ingestion
 * pipeline including DCR (Distributed Conflict Resolution), RMD handling, and cross-region replication.
 *
 * <p>Workload types:
 * <ul>
 *   <li>{@code PUT} — full record puts, exercises value-level timestamp merge</li>
 *   <li>{@code PARTIAL_UPDATE} — field-level partial updates, exercises field-level timestamp merge</li>
 *   <li>{@code MIXED} — interleaved PUTs, partial updates, and deletes from both regions</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   ./gradlew :internal:venice-test-common:jmh -Pjmh.includes='ActiveActiveIngestionBenchmark'
 * </pre>
 */
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgs = { "-Xms4G", "-Xmx4G" })
@Warmup(iterations = 2, time = 30)
@Measurement(iterations = 3, time = 60)
public class ActiveActiveIngestionBenchmark {
  private static final String CLUSTER_NAME = "venice-cluster0";
  // JMH @OperationsPerInvocation must be a compile-time constant. Keep this at 1000 so the
  // baseline configuration is bit-for-bit identical to Phase 0/1/2. The Phase 3 dynamic
  // override is RECORDS_PER_INVOCATION, read at @Setup time from the system property
  // "phase3.records.per.invocation" (default = 1000). The [E2E] line is computed off the
  // actual records sent (iterationRecordCount), so the dynamic override is what drives the
  // E2E throughput number — JMH's Score column would scale linearly the other way and is
  // ignored for Phase 3 measurement.
  private static final int NUM_RECORDS_PER_INVOCATION = 1000;
  // Phase 3 dynamic override of NUM_RECORDS_PER_INVOCATION. Set via sysprop
  // "phase3.records.per.invocation". Defaults to NUM_RECORDS_PER_INVOCATION = 1000.
  private static final int RECORDS_PER_INVOCATION =
      Integer.getInteger("phase3.records.per.invocation", NUM_RECORDS_PER_INVOCATION);
  // Phase 3 producer parallelism: each region gets N producers. Records per invocation
  // are stripe-balanced across producers within each region; sends are issued from N
  // concurrent worker threads per region per invocation, joined before flush. Set via
  // sysprop "phase3.producers.per.region". Default 1 (matches Phase 0/1/2).
  private static final int PRODUCERS_PER_REGION = Math.max(1, Integer.getInteger("phase3.producers.per.region", 1));
  // Phase 3 server-side max.poll.records override. If set (>0), wired into
  // SERVER_KAFKA_MAX_POLL_RECORDS in the server-properties block of setUp(). Default 0
  // (do not override — server uses its built-in default of 100).
  private static final int SERVER_MAX_POLL_RECORDS_OVERRIDE = Integer.getInteger("phase3.server.max.poll.records", 0);
  // Bounded key pool size for PARTIAL_UPDATE so updates actually hit existing records
  // and exercise the server-side read-modify-write + field-level DCR path.
  private static final int PARTIAL_UPDATE_KEY_POOL_SIZE = 10_000;
  // Bounded key pool size for PUT so both producers concurrently hit the same keys
  // and exercise the value-level DCR path (timestamp-based conflict resolution).
  private static final int PUT_KEY_POOL_SIZE = 10_000;
  // Each PARTIAL_UPDATE pool key is pre-populated with a tags map of this size.
  // AddToMap updates during the benchmark only overwrite values for map keys in
  // [0, TAGS_MAP_SIZE), so the map size stays constant instead of growing.
  private static final int TAGS_MAP_SIZE = 100;
  private static final String KEY_SCHEMA_STR = "\"string\"";

  // Schema with regular fields + map field for collection merge benchmarking
  private static final String VALUE_SCHEMA_STR = "{\n" + "  \"type\": \"record\",\n"
      + "  \"name\": \"BenchmarkRecord\",\n" + "  \"namespace\": \"com.linkedin.venice.benchmark\",\n"
      + "  \"fields\": [\n" + "    { \"name\": \"name\", \"type\": \"string\", \"default\": \"default_name\" },\n"
      + "    { \"name\": \"age\", \"type\": \"int\", \"default\": -1 },\n"
      + "    { \"name\": \"score\", \"type\": \"double\", \"default\": 0.0 },\n"
      + "    { \"name\": \"tags\", \"type\": " + "{ \"type\": \"map\", \"values\": \"string\" }, \"default\": {} }\n"
      + "  ]\n" + "}";

  /**
   * Workload type to benchmark.
   */
  public enum WorkloadType {
    /** Full record PUTs from both regions — value-level timestamp DCR */
    PUT,
    /** Field-level partial updates from both regions — field-level timestamp DCR + write-compute */
    PARTIAL_UPDATE,
    /** Interleaved PUTs, partial updates, and deletes — realistic mixed workload */
    MIXED
  }

  @Param({ "PUT", "PARTIAL_UPDATE", "MIXED" })
  private WorkloadType workloadType;

  private VeniceTwoLayerMultiRegionMultiClusterWrapper multiRegionCluster;
  private ControllerClient parentControllerClient;
  private List<VeniceMultiClusterWrapper> childDatacenters;
  // Phase 3: each region holds N producers (N = PRODUCERS_PER_REGION). The first producer
  // in each list is the "primary" used for canary, sentinel, and pre-population paths so
  // those single-record visibility checks remain unambiguous.
  private List<VeniceSystemProducer> producersDC0;
  private List<VeniceSystemProducer> producersDC1;
  // Convenience aliases for the primary producer per region (producersDCx.get(0)).
  private VeniceSystemProducer producerDC0;
  private VeniceSystemProducer producerDC1;
  // Worker pool for parallel send: 2 * PRODUCERS_PER_REGION threads (N per region). Each
  // benchmark invocation submits N tasks per region to this pool, then awaits them via a
  // CountDownLatch before flushing all producers. Same pool is reused across invocations.
  private ExecutorService sendWorkerPool;
  private AvroGenericStoreClient<String, GenericRecord> readClient;
  private AvroGenericStoreClient<String, GenericRecord> readClientDC1;
  private String storeName;
  private Schema valueSchema;
  private Schema writeComputeSchema;
  private final AtomicLong keyCounter = new AtomicLong(0);
  // Updated by each invocation; read at iteration teardown to verify ingestion caught up.
  // Volatile because the benchmark worker thread and the teardown thread may differ.
  private volatile String lastProducedKey;
  // Wall-clock end-to-end measurement: time from iteration start until the last produced key
  // is visible via the read client. JMH's own throughput excludes the teardown drain, so we
  // compute and print the E2E number ourselves.
  private final AtomicLong iterationRecordCount = new AtomicLong(0);
  private volatile long iterationStartNanos;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    Utils.thisIsLocalhost();

    // Build multi-region cluster: 2 regions, 1 cluster each, 1 server per region
    Properties parentControllerProps = new Properties();
    parentControllerProps.put(DEFAULT_MAX_NUMBER_OF_PARTITIONS, "10");
    parentControllerProps.put(NATIVE_REPLICATION_SOURCE_FABRIC, "dc-0");
    parentControllerProps.put(PARENT_KAFKA_CLUSTER_FABRIC_LIST, DEFAULT_PARENT_DATA_CENTER_REGION_NAME);

    Properties childControllerProps = new Properties();
    childControllerProps.put(DEFAULT_MAX_NUMBER_OF_PARTITIONS, "10");

    // Enable AA/WC parallel processing on the server: incoming merge work for a single
    // partition's poll batch fans out across a thread pool, using per-key locks so
    // same-key updates still serialize. Targets exactly our workload (AA + write-compute).
    Properties serverProps = new Properties();
    serverProps.setProperty(SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED, "true");
    // Phase 3 (C-knob): if SERVER_MAX_POLL_RECORDS_OVERRIDE is set, raise the server's
    // Kafka consumer max.poll.records cap above its built-in default of 100. Compresses the
    // bimodal "full poll" half identified in Phase 2.
    if (SERVER_MAX_POLL_RECORDS_OVERRIDE > 0) {
      serverProps.setProperty(SERVER_KAFKA_MAX_POLL_RECORDS, Integer.toString(SERVER_MAX_POLL_RECORDS_OVERRIDE));
      System.err.println(
          "[ActiveActiveIngestionBenchmark] Phase3: server.kafka.max.poll.records=" + SERVER_MAX_POLL_RECORDS_OVERRIDE);
    }
    System.err.println(
        "[ActiveActiveIngestionBenchmark] Phase3: records_per_invocation=" + RECORDS_PER_INVOCATION
            + " producers_per_region=" + PRODUCERS_PER_REGION);

    VeniceMultiRegionClusterCreateOptions options =
        new VeniceMultiRegionClusterCreateOptions.Builder().numberOfRegions(2)
            .numberOfClusters(1)
            .numberOfParentControllers(1)
            .numberOfChildControllers(1)
            .numberOfServers(1)
            .numberOfRouters(1)
            .replicationFactor(1)
            .parentControllerProperties(parentControllerProps)
            .childControllerProperties(childControllerProps)
            .serverProperties(serverProps)
            .build();

    multiRegionCluster = ServiceFactory.getVeniceTwoLayerMultiRegionMultiClusterWrapper(options);
    childDatacenters = multiRegionCluster.getChildRegions();
    VeniceControllerWrapper parentController = multiRegionCluster.getParentControllers().get(0);
    parentControllerClient = new ControllerClient(CLUSTER_NAME, parentController.getControllerUrl());

    // Parse schemas
    valueSchema = new Schema.Parser().parse(VALUE_SCHEMA_STR);
    writeComputeSchema = com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter.getInstance()
        .convertFromValueRecordSchema(valueSchema);

    // Create AA-enabled hybrid store with write-compute
    storeName = Utils.getUniqueString("aa-benchmark-store");
    assertCommand(
        parentControllerClient.createNewStore(storeName, "benchmark-owner", KEY_SCHEMA_STR, VALUE_SCHEMA_STR));
    UpdateStoreQueryParams storeParams = new UpdateStoreQueryParams().setNativeReplicationEnabled(true)
        .setActiveActiveReplicationEnabled(true)
        .setStorageQuotaInByte(Store.UNLIMITED_STORAGE_QUOTA)
        .setChunkingEnabled(false)
        .setHybridRewindSeconds(25L)
        .setHybridOffsetLagThreshold(1L)
        .setWriteComputationEnabled(true)
        .setPartitionCount(2);
    assertCommand(parentControllerClient.updateStore(storeName, storeParams));

    // Empty push to create version 1
    assertCommand(
        parentControllerClient.sendEmptyPushAndWait(storeName, Utils.getUniqueString("empty-push"), 1L, 60_000L));

    // Wait for version to be ready in both DCs
    ControllerClient dc0Client =
        new ControllerClient(CLUSTER_NAME, childDatacenters.get(0).getControllerConnectString());
    ControllerClient dc1Client =
        new ControllerClient(CLUSTER_NAME, childDatacenters.get(1).getControllerConnectString());
    for (ControllerClient dcClient: Arrays.asList(dc0Client, dc1Client)) {
      waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
        int currentVersion = assertCommand(dcClient.getStore(storeName)).getStore().getCurrentVersion();
        if (currentVersion != 1) {
          throw new AssertionError("Expected version 1, got " + currentVersion);
        }
      });
    }
    dc0Client.close();
    dc1Client.close();

    // Start Samza system producers for both regions. Phase 3: N producers per region.
    producersDC0 = new ArrayList<>(PRODUCERS_PER_REGION);
    producersDC1 = new ArrayList<>(PRODUCERS_PER_REGION);
    for (int n = 0; n < PRODUCERS_PER_REGION; n++) {
      producersDC0.add(IntegrationTestPushUtils.getSamzaProducerForStream(multiRegionCluster, 0, storeName));
      producersDC1.add(IntegrationTestPushUtils.getSamzaProducerForStream(multiRegionCluster, 1, storeName));
    }
    producerDC0 = producersDC0.get(0);
    producerDC1 = producersDC1.get(0);
    // Build the parallel-send worker pool sized for both regions.
    if (PRODUCERS_PER_REGION > 1) {
      AtomicInteger workerSeq = new AtomicInteger();
      ThreadFactory tf = r -> {
        Thread t = new Thread(r, "phase3-aa-bench-sender-" + workerSeq.getAndIncrement());
        t.setDaemon(true);
        return t;
      };
      sendWorkerPool = Executors.newFixedThreadPool(2 * PRODUCERS_PER_REGION, tf);
    }

    // Create read clients for both DCs. DC0 client is used by the per-iteration drain check;
    // DC1 client is used at trial teardown to verify cross-region drain in the other direction
    // before running the VT consistency check.
    String dc0RouterUrl = childDatacenters.get(0).getClusters().get(CLUSTER_NAME).getRandomRouterURL();
    readClient = ClientFactory
        .getAndStartGenericAvroClient(ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(dc0RouterUrl));
    String dc1RouterUrl = childDatacenters.get(1).getClusters().get(CLUSTER_NAME).getRandomRouterURL();
    readClientDC1 = ClientFactory
        .getAndStartGenericAvroClient(ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(dc1RouterUrl));

    // Verify the pipeline is working with a canary record
    GenericRecord canary = new GenericData.Record(valueSchema);
    canary.put("name", "canary");
    canary.put("age", 0);
    canary.put("score", 0.0);
    canary.put("tags", Collections.emptyMap());
    sendStreamingRecord(producerDC0, storeName, "canary-key", canary);
    waitForNonDeterministicAssertion(60, TimeUnit.SECONDS, () -> {
      if (readClient.get("canary-key").get() == null) {
        throw new AssertionError("Canary record not yet visible");
      }
    });

    // For PARTIAL_UPDATE, pre-populate each bounded-pool key with a TAGS_MAP_SIZE-entry
    // tags map. Subsequent AddToMap operations only overwrite values for map keys
    // within [0, TAGS_MAP_SIZE), so the map size stays constant rather than growing.
    if (workloadType == WorkloadType.PARTIAL_UPDATE) {
      prePopulatePartialUpdatePool();
    }

    // JMH benchmark relies on System.exit to finish one round of benchmark run, otherwise it will hang there.
    TestUtils.restoreSystemExit();
  }

  private void prePopulatePartialUpdatePool() {
    System.err.println(
        "[ActiveActiveIngestionBenchmark] Pre-populating " + PARTIAL_UPDATE_KEY_POOL_SIZE + " pool keys with "
            + TAGS_MAP_SIZE + "-entry tags map...");
    for (int poolIdx = 0; poolIdx < PARTIAL_UPDATE_KEY_POOL_SIZE; poolIdx++) {
      String key = "pu-pool-" + poolIdx;
      GenericRecord rec = new GenericData.Record(valueSchema);
      rec.put("name", "init-" + poolIdx);
      rec.put("age", 0);
      rec.put("score", 0.0);
      Map<String, String> initTags = new HashMap<>();
      for (int m = 0; m < TAGS_MAP_SIZE; m++) {
        initTags.put("k-" + m, "init-v-" + m);
      }
      rec.put("tags", initTags);
      sendStreamingRecordWithoutFlush(producerDC0, storeName, key, rec);
    }
    producerDC0.flush(storeName);
    // Wait for a spread of pool keys to be visible so the benchmark starts from a fully
    // populated state across all partitions (pool keys hash to different partitions).
    int[] checkIndices = { 0, 1234, 2345, 3456, 4567, 5678, 6789, 7890, PARTIAL_UPDATE_KEY_POOL_SIZE - 1 };
    waitForNonDeterministicAssertion(5, TimeUnit.MINUTES, true, () -> {
      for (int idx: checkIndices) {
        if (readClient.get("pu-pool-" + idx).get() == null) {
          throw new AssertionError("Pre-population not yet drained: pu-pool-" + idx);
        }
      }
    });
    System.err.println("[ActiveActiveIngestionBenchmark] Pre-population complete.");
  }

  @TearDown(Level.Trial)
  public void cleanUp() {
    // For PARTIAL_UPDATE, run a Spark-based VT consistency check across both DCs before
    // shutting down the cluster. Reports counts of VALUE_MISMATCH and MISSING rows to stderr.
    if (workloadType == WorkloadType.PARTIAL_UPDATE) {
      try {
        runVTConsistencyCheck();
      } catch (Throwable t) {
        System.err.println("[VT-CHECK] Failed: " + t);
        t.printStackTrace(System.err);
      }
    }
    Utils.closeQuietlyWithErrorLogged(readClient);
    Utils.closeQuietlyWithErrorLogged(readClientDC1);
    if (producersDC0 != null) {
      for (VeniceSystemProducer p: producersDC0) {
        Utils.closeQuietlyWithErrorLogged(p);
      }
    }
    if (producersDC1 != null) {
      for (VeniceSystemProducer p: producersDC1) {
        Utils.closeQuietlyWithErrorLogged(p);
      }
    }
    if (sendWorkerPool != null) {
      sendWorkerPool.shutdownNow();
    }
    Utils.closeQuietlyWithErrorLogged(parentControllerClient);
    Utils.closeQuietlyWithErrorLogged(multiRegionCluster);
  }

  /**
   * Run the {@link com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob} over the
   * benchmark's two DCs' VTs. Before invoking the job, send a final round of sentinels via both
   * producers and wait for them to be visible on both DC0 and DC1 routers — this ensures both
   * DCs have drained both RTs and produced complete VTs.
   *
   * <p>The job runs Spark in local[*] mode in this JVM. Output is written to a temp directory
   * and read back to count VALUE_MISMATCH / MISSING rows, which are printed to stderr.
   */
  private void runVTConsistencyCheck() throws Exception {
    // Step 1: send N sentinels via each producer and wait for ALL of them on BOTH routers.
    final int finalSentinelCount = 20;
    java.util.List<String> dc0SentinelKeys = new java.util.ArrayList<>(finalSentinelCount);
    java.util.List<String> dc1SentinelKeys = new java.util.ArrayList<>(finalSentinelCount);
    for (int s = 0; s < finalSentinelCount; s++) {
      long seq = keyCounter.getAndIncrement();
      String dc0Key = "pu-final-dc0-" + seq;
      String dc1Key = "pu-final-dc1-" + seq;
      UpdateBuilder ubA = new UpdateBuilderImpl(writeComputeSchema);
      ubA.setNewFieldValue("name", "final-dc0-" + seq);
      sendStreamingRecordWithoutFlush(producerDC0, storeName, dc0Key, ubA.build());
      dc0SentinelKeys.add(dc0Key);
      UpdateBuilder ubB = new UpdateBuilderImpl(writeComputeSchema);
      ubB.setNewFieldValue("name", "final-dc1-" + seq);
      sendStreamingRecordWithoutFlush(producerDC1, storeName, dc1Key, ubB.build());
      dc1SentinelKeys.add(dc1Key);
    }
    producerDC0.flush(storeName);
    producerDC1.flush(storeName);

    System.err.println("[VT-CHECK] Waiting for both-DC drain via dual-router sentinel verification...");
    waitForNonDeterministicAssertion(10, TimeUnit.MINUTES, true, () -> {
      for (String k: dc0SentinelKeys) {
        if (readClient.get(k).get() == null) {
          throw new AssertionError("DC0-produced sentinel not visible on DC0 router: " + k);
        }
        if (readClientDC1.get(k).get() == null) {
          throw new AssertionError("DC0-produced sentinel not visible on DC1 router: " + k);
        }
      }
      for (String k: dc1SentinelKeys) {
        if (readClient.get(k).get() == null) {
          throw new AssertionError("DC1-produced sentinel not visible on DC0 router: " + k);
        }
        if (readClientDC1.get(k).get() == null) {
          throw new AssertionError("DC1-produced sentinel not visible on DC1 router: " + k);
        }
      }
    });
    System.err.println("[VT-CHECK] Drain confirmed on both DCs. Running consistency check...");

    // Step 2: invoke the Spark job.
    String versionTopic = com.linkedin.venice.meta.Version.composeKafkaTopic(storeName, 1);
    String dc0Broker = childDatacenters.get(0).getPubSubBrokerWrapper().getAddress();
    String dc1Broker = childDatacenters.get(1).getPubSubBrokerWrapper().getAddress();
    java.io.File tempRoot = java.nio.file.Files.createTempDirectory("vt-consistency-bench").toFile();
    java.io.File outputDir = new java.io.File(tempRoot, "output");
    try {
      Properties jobProps = new Properties();
      jobProps.setProperty(com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.DC0_BROKER_URL, dc0Broker);
      jobProps.setProperty(com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.DC1_BROKER_URL, dc1Broker);
      jobProps.setProperty(com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.VERSION_TOPIC, versionTopic);
      jobProps.setProperty(
          com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.OUTPUT_PATH,
          outputDir.getAbsolutePath());
      jobProps.setProperty(com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.NUMBER_OF_REGIONS, "2");
      com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.run(jobProps);

      // Step 3: read the output and report.
      org.apache.spark.sql.SparkSession spark = org.apache.spark.sql.SparkSession.builder()
          .master("local[*]")
          .appName("AAIngestionBenchmarkVTConsistencyReader")
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
                versionTopic,
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

  /**
   * Benchmark: full record PUTs from alternating regions.
   * Exercises value-level timestamp conflict resolution.
   */
  /**
   * Resets per-iteration counters and captures the iteration start timestamp. Runs before
   * each warmup/measurement iteration, outside the measured window.
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
    iterationRecordCount.addAndGet(RECORDS_PER_INVOCATION);
  }

  /**
   * Runs once per iteration, outside the measured window. Waits for the last produced key to
   * become visible (draining the AA pipeline), then prints the true end-to-end throughput:
   * total records produced during the iteration divided by (first-produce → last-visible).
   */
  @TearDown(Level.Iteration)
  public void finishIterationAndReportE2E() {
    String keysToVerify = lastProducedKey;
    long records = iterationRecordCount.get();
    if (keysToVerify == null || records == 0) {
      return;
    }
    // The workload may return a comma-separated list of sentinel keys (one per partition
    // coverage). Wait for ALL of them to be visible so the E2E timestamp reflects the
    // slowest partition's drain.
    String[] keys = keysToVerify.split(",");
    waitForNonDeterministicAssertion(10, TimeUnit.MINUTES, true, () -> {
      for (String key: keys) {
        if (readClient.get(key).get() == null) {
          throw new AssertionError("Sentinel not yet ingested: " + key);
        }
      }
    });
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

  private String runPutWorkload() {
    // Bounded key pool + alternating producers means both DC0 and DC1 write to the same
    // keys concurrently. DCR has to resolve real timestamp-based conflicts for every record.
    //
    // AA-symmetry invariant: every i-th record alternates region (i%2 == 0 -> DC0, else
    // DC1) so DC0 and DC1 each receive exactly RECORDS_PER_INVOCATION/2 records (record
    // index parity drives region, NOT producer-instance index). Within each region, when
    // PRODUCERS_PER_REGION > 1, the records destined for that region are stripe-balanced
    // across the region's N producers via a producer-stripe counter (incremented on every
    // record going to that region), and the N producers' send work is dispatched to N
    // worker threads that run concurrently. All threads finish before the flush phase.
    //
    // Sentinel still goes through producersDC1.get(0) (the primary DC1 producer), so
    // visibility on the DC0 router still proves cross-region replication has drained.
    final int totalRecords = RECORDS_PER_INVOCATION;
    final long invocationBaseSeq = keyCounter.getAndAdd(totalRecords);

    if (PRODUCERS_PER_REGION == 1) {
      // Single-producer-per-region path: identical to Phase 0/1/2 baseline behaviour
      // (preserving exact ordering and serial send semantics for the baseline measurement).
      for (int i = 0; i < totalRecords; i++) {
        long seq = invocationBaseSeq + i;
        sendOnePutRecord(producerForRegion(i % 2, 0), seq, i);
      }
    } else {
      // Multi-producer-per-region path: stripe records destined for each region across
      // its N producers, dispatch each (region, producer) work-slice to its own thread.
      // Each task processes a disjoint subset of [0, totalRecords) such that the union
      // equals the full range — same records, same keys, same DC assignment as the
      // single-producer path; only the producer instance and sender thread change.
      final int n = PRODUCERS_PER_REGION;
      // 2 * n total tasks: one per (region, producer-index) combination.
      CountDownLatch latch = new CountDownLatch(2 * n);
      for (int region = 0; region < 2; region++) {
        for (int p = 0; p < n; p++) {
          final int regionFinal = region;
          final int producerIdxFinal = p;
          sendWorkerPool.submit(() -> {
            try {
              VeniceSystemProducer producer = producerForRegion(regionFinal, producerIdxFinal);
              // Iterate i values in [0, totalRecords) where i%2 == regionFinal AND the
              // per-region-stripe counter ((i/2) % n) == producerIdxFinal. This gives
              // a disjoint partition of the full range across all 2*n tasks.
              for (int i = regionFinal; i < totalRecords; i += 2) {
                int stripeIdxWithinRegion = (i - regionFinal) / 2;
                if ((stripeIdxWithinRegion % n) != producerIdxFinal) {
                  continue;
                }
                long seq = invocationBaseSeq + i;
                sendOnePutRecord(producer, seq, i);
              }
            } finally {
              latch.countDown();
            }
          });
        }
      }
      try {
        latch.await();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new RuntimeException("Phase3 send-pool interrupted", e);
      }
    }

    // Sentinel: unique fresh key via the primary DC1 producer so visibility on DC0 router
    // proves cross-region replication has drained.
    long sentinelSeq = keyCounter.getAndIncrement();
    String sentinelKey = "put-sentinel-" + sentinelSeq;
    GenericRecord sentinelRecord = new GenericData.Record(valueSchema);
    sentinelRecord.put("name", "sentinel-" + sentinelSeq);
    sentinelRecord.put("age", 0);
    sentinelRecord.put("score", 0.0);
    sentinelRecord.put("tags", Collections.emptyMap());
    sendStreamingRecordWithoutFlush(producerDC1, storeName, sentinelKey, sentinelRecord);

    // Flush ALL producers (every region, every producer instance) so the burst of buffered
    // records drains to the brokers before the iteration teardown waits for the sentinel.
    for (VeniceSystemProducer p: producersDC0) {
      p.flush(storeName);
    }
    for (VeniceSystemProducer p: producersDC1) {
      p.flush(storeName);
    }
    return sentinelKey;
  }

  /**
   * Encapsulates the per-record value construction + send for the PUT workload. Used by both
   * the single-producer path and the multi-producer / multi-thread path.
   */
  private void sendOnePutRecord(VeniceSystemProducer producer, long seq, int recordIdxWithinInvocation) {
    String key = "put-pool-" + (seq % PUT_KEY_POOL_SIZE);
    GenericRecord record = new GenericData.Record(valueSchema);
    record.put("name", "user-" + seq);
    record.put("age", (int) (seq % 100));
    record.put("score", seq * 1.1);
    Map<String, String> tags = new HashMap<>();
    tags.put("region", recordIdxWithinInvocation % 2 == 0 ? "dc-0" : "dc-1");
    record.put("tags", tags);
    sendStreamingRecordWithoutFlush(producer, storeName, key, record);
  }

  private VeniceSystemProducer producerForRegion(int region, int producerIndex) {
    List<VeniceSystemProducer> list = (region == 0) ? producersDC0 : producersDC1;
    return list.get(producerIndex % list.size());
  }

  private String runPartialUpdateWorkload() {
    // Bounded key pool + globally-unique sequence ensures:
    // - same key is updated many times across invocations (real read-modify-write path)
    // - every update carries a unique value (never a duplicate)
    for (int i = 0; i < NUM_RECORDS_PER_INVOCATION; i++) {
      long seq = keyCounter.getAndIncrement();
      String key = "pu-pool-" + (seq % PARTIAL_UPDATE_KEY_POOL_SIZE);
      UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
      int fieldChoice = (int) (seq % 3);
      if (fieldChoice == 0) {
        ub.setNewFieldValue("name", "upd-" + seq);
      } else if (fieldChoice == 1) {
        ub.setNewFieldValue("age", (int) (seq % 100_000));
      } else {
        // AddToMap with a map key from the pre-populated 0..TAGS_MAP_SIZE range.
        // Since the key already exists, this updates the value and the map size
        // stays constant at TAGS_MAP_SIZE instead of growing.
        Map<String, String> mapUpdate = new HashMap<>();
        String mapKey = "k-" + (seq % TAGS_MAP_SIZE);
        mapUpdate.put(mapKey, "v-" + seq);
        ub.setEntriesToAddToMapField("tags", mapUpdate);
      }
      VeniceSystemProducer producer = (i % 2 == 0) ? producerDC0 : producerDC1;
      sendStreamingRecordWithoutFlush(producer, storeName, key, ub.build());
    }

    // Sentinels: N fresh unique keys distributed across partitions (by hash). Visibility
    // of ALL N on DC0 proves that every partition a sentinel landed on has drained. With
    // 10 partitions and ~20 sentinels, we cover every partition with very high probability.
    // Sent via DC1 so visibility on DC0 also confirms cross-region replication caught up.
    final int sentinelCount = 20;
    StringBuilder sentinelKeys = new StringBuilder();
    for (int s = 0; s < sentinelCount; s++) {
      long sentinelSeq = keyCounter.getAndIncrement();
      String sentinelKey = "pu-sentinel-" + sentinelSeq;
      UpdateBuilder sentinelUb = new UpdateBuilderImpl(writeComputeSchema);
      sentinelUb.setNewFieldValue("name", "sentinel-" + sentinelSeq);
      sendStreamingRecordWithoutFlush(producerDC1, storeName, sentinelKey, sentinelUb.build());
      if (s > 0) {
        sentinelKeys.append(',');
      }
      sentinelKeys.append(sentinelKey);
    }

    producerDC0.flush(storeName);
    producerDC1.flush(storeName);
    return sentinelKeys.toString();
  }

  private String runMixedWorkload() {
    long baseKey = keyCounter.getAndAdd(NUM_RECORDS_PER_INVOCATION);
    // Track only non-delete writes so the post-invocation poll targets a key that should be visible.
    String lastWrittenKey = null;
    for (int i = 0; i < NUM_RECORDS_PER_INVOCATION; i++) {
      String key = "mixed-" + (baseKey + i);
      VeniceSystemProducer producer = (i % 2 == 0) ? producerDC0 : producerDC1;
      int op = i % 10;

      if (op < 4) {
        // 40% PUTs
        GenericRecord record = new GenericData.Record(valueSchema);
        record.put("name", "user-" + i);
        record.put("age", i % 100);
        record.put("score", i * 1.1);
        Map<String, String> tags = new HashMap<>();
        tags.put("tag-" + i, "val-" + i);
        record.put("tags", tags);
        sendStreamingRecordWithoutFlush(producer, storeName, key, record);
        lastWrittenKey = key;
      } else if (op < 7) {
        // 30% partial updates (field-level)
        UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
        ub.setNewFieldValue("name", "partial-" + i);
        ub.setNewFieldValue("score", i * 2.2);
        sendStreamingRecordWithoutFlush(producer, storeName, key, ub.build());
        lastWrittenKey = key;
      } else if (op < 9) {
        // 20% collection merges (AddToMap)
        UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
        Map<String, String> delta = new HashMap<>();
        delta.put("k-" + i, "v-" + i);
        delta.put("k2-" + i, "v2-" + i);
        ub.setEntriesToAddToMapField("tags", delta);
        sendStreamingRecordWithoutFlush(producer, storeName, key, ub.build());
        lastWrittenKey = key;
      } else {
        // 10% deletes
        sendStreamingRecordWithoutFlush(producer, storeName, key, null, null);
      }
    }
    producerDC0.flush(storeName);
    producerDC1.flush(storeName);
    return lastWrittenKey;
  }

  public static void main(String[] args) throws RunnerException {
    org.openjdk.jmh.runner.options.Options opt =
        new OptionsBuilder().include(ActiveActiveIngestionBenchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .build();
    new Runner(opt).run();
  }
}
