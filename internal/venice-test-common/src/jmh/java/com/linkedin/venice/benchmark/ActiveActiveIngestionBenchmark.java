package com.linkedin.venice.benchmark;

import static com.linkedin.venice.ConfigKeys.DEFAULT_MAX_NUMBER_OF_PARTITIONS;
import static com.linkedin.venice.ConfigKeys.NATIVE_REPLICATION_SOURCE_FABRIC;
import static com.linkedin.venice.ConfigKeys.PARENT_KAFKA_CLUSTER_FABRIC_LIST;
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
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
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
  private static final int NUM_RECORDS_PER_INVOCATION = 1000;
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
  private VeniceSystemProducer producerDC0;
  private VeniceSystemProducer producerDC1;
  private AvroGenericStoreClient<String, GenericRecord> readClient;
  private String storeName;
  private Schema valueSchema;
  private Schema writeComputeSchema;
  private final AtomicLong keyCounter = new AtomicLong(0);

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    Utils.thisIsLocalhost();

    // Build multi-region cluster: 2 regions, 1 cluster each, 1 server per region
    Properties parentControllerProps = new Properties();
    parentControllerProps.put(DEFAULT_MAX_NUMBER_OF_PARTITIONS, "3");
    parentControllerProps.put(NATIVE_REPLICATION_SOURCE_FABRIC, "dc-0");
    parentControllerProps.put(PARENT_KAFKA_CLUSTER_FABRIC_LIST, DEFAULT_PARENT_DATA_CENTER_REGION_NAME);

    Properties childControllerProps = new Properties();
    childControllerProps.put(DEFAULT_MAX_NUMBER_OF_PARTITIONS, "3");

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
        .setWriteComputationEnabled(true);
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

    // Start Samza system producers for both regions
    producerDC0 = IntegrationTestPushUtils.getSamzaProducerForStream(multiRegionCluster, 0, storeName);
    producerDC1 = IntegrationTestPushUtils.getSamzaProducerForStream(multiRegionCluster, 1, storeName);

    // Create a read client to verify data landed (used in warmup verification)
    String dc0RouterUrl = childDatacenters.get(0).getClusters().get(CLUSTER_NAME).getRandomRouterURL();
    readClient = ClientFactory
        .getAndStartGenericAvroClient(ClientConfig.defaultGenericClientConfig(storeName).setVeniceURL(dc0RouterUrl));

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

    // JMH benchmark relies on System.exit to finish one round of benchmark run, otherwise it will hang there.
    TestUtils.restoreSystemExit();
  }

  @TearDown(Level.Trial)
  public void cleanUp() {
    Utils.closeQuietlyWithErrorLogged(readClient);
    Utils.closeQuietlyWithErrorLogged(producerDC0);
    Utils.closeQuietlyWithErrorLogged(producerDC1);
    Utils.closeQuietlyWithErrorLogged(parentControllerClient);
    Utils.closeQuietlyWithErrorLogged(multiRegionCluster);
  }

  /**
   * Benchmark: full record PUTs from alternating regions.
   * Exercises value-level timestamp conflict resolution.
   */
  @Benchmark
  @OperationsPerInvocation(NUM_RECORDS_PER_INVOCATION)
  public void benchmarkAAIngestion() {
    switch (workloadType) {
      case PUT:
        runPutWorkload();
        break;
      case PARTIAL_UPDATE:
        runPartialUpdateWorkload();
        break;
      case MIXED:
        runMixedWorkload();
        break;
    }
  }

  private void runPutWorkload() {
    long baseKey = keyCounter.getAndAdd(NUM_RECORDS_PER_INVOCATION);
    for (int i = 0; i < NUM_RECORDS_PER_INVOCATION; i++) {
      String key = "put-" + (baseKey + i);
      GenericRecord record = new GenericData.Record(valueSchema);
      record.put("name", "user-" + i);
      record.put("age", i % 100);
      record.put("score", i * 1.1);
      Map<String, String> tags = new HashMap<>();
      tags.put("region", i % 2 == 0 ? "dc-0" : "dc-1");
      record.put("tags", tags);

      // Alternate between producers to simulate cross-region writes
      VeniceSystemProducer producer = (i % 2 == 0) ? producerDC0 : producerDC1;
      sendStreamingRecordWithoutFlush(producer, storeName, key, record);
    }
    // Flush both producers
    producerDC0.flush(storeName);
    producerDC1.flush(storeName);
  }

  private void runPartialUpdateWorkload() {
    long baseKey = keyCounter.getAndAdd(NUM_RECORDS_PER_INVOCATION);
    for (int i = 0; i < NUM_RECORDS_PER_INVOCATION; i++) {
      String key = "pu-" + (baseKey + i);
      UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);

      if (i % 3 == 0) {
        // Update scalar field
        ub.setNewFieldValue("name", "updated-" + i);
      } else if (i % 3 == 1) {
        // Update scalar field
        ub.setNewFieldValue("age", i % 100);
      } else {
        // AddToMap — exercises collection merge
        Map<String, String> mapUpdate = new HashMap<>();
        mapUpdate.put("key-" + i, "val-" + i);
        ub.setEntriesToAddToMapField("tags", mapUpdate);
      }

      VeniceSystemProducer producer = (i % 2 == 0) ? producerDC0 : producerDC1;
      sendStreamingRecordWithoutFlush(producer, storeName, key, ub.build());
    }
    producerDC0.flush(storeName);
    producerDC1.flush(storeName);
  }

  private void runMixedWorkload() {
    long baseKey = keyCounter.getAndAdd(NUM_RECORDS_PER_INVOCATION);
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
      } else if (op < 7) {
        // 30% partial updates (field-level)
        UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
        ub.setNewFieldValue("name", "partial-" + i);
        ub.setNewFieldValue("score", i * 2.2);
        sendStreamingRecordWithoutFlush(producer, storeName, key, ub.build());
      } else if (op < 9) {
        // 20% collection merges (AddToMap)
        UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
        Map<String, String> delta = new HashMap<>();
        delta.put("k-" + i, "v-" + i);
        delta.put("k2-" + i, "v2-" + i);
        ub.setEntriesToAddToMapField("tags", delta);
        sendStreamingRecordWithoutFlush(producer, storeName, key, ub.build());
      } else {
        // 10% deletes
        sendStreamingRecordWithoutFlush(producer, storeName, key, null, null);
      }
    }
    producerDC0.flush(storeName);
    producerDC1.flush(storeName);
  }

  public static void main(String[] args) throws RunnerException {
    org.openjdk.jmh.runner.options.Options opt =
        new OptionsBuilder().include(ActiveActiveIngestionBenchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .build();
    new Runner(opt).run();
  }
}
