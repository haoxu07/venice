package com.linkedin.venice.benchmark.lean;

import static com.linkedin.venice.ConfigKeys.CLUSTER_NAME;
import static com.linkedin.venice.ConfigKeys.DATA_BASE_PATH;
import static com.linkedin.venice.ConfigKeys.KAFKA_CLUSTER_MAP_KEY_NAME;
import static com.linkedin.venice.ConfigKeys.KAFKA_CLUSTER_MAP_KEY_URL;
import static com.linkedin.venice.ConfigKeys.LISTENER_PORT;
import static com.linkedin.venice.ConfigKeys.PERSISTENCE_TYPE;
import static com.linkedin.venice.ConfigKeys.SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED;
import static com.linkedin.venice.ConfigKeys.STORE_WRITER_NUMBER;
import static com.linkedin.venice.ConfigKeys.ZOOKEEPER_ADDRESS;
import static org.mockito.Answers.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

import com.linkedin.davinci.compression.StorageEngineBackedCompressorFactory;
import com.linkedin.davinci.config.VeniceConfigLoader;
import com.linkedin.davinci.config.VeniceServerConfig;
import com.linkedin.davinci.config.VeniceStoreVersionConfig;
import com.linkedin.davinci.helix.LeaderFollowerPartitionStateModel;
import com.linkedin.davinci.kafka.consumer.ActiveActiveStoreIngestionTask;
import com.linkedin.davinci.kafka.consumer.AggKafkaConsumerService;
import com.linkedin.davinci.kafka.consumer.IngestionThrottler;
import com.linkedin.davinci.kafka.consumer.KafkaClusterBasedRecordThrottler;
import com.linkedin.davinci.kafka.consumer.LeaderFollowerStoreIngestionTask;
import com.linkedin.davinci.kafka.consumer.RemoteIngestionRepairService;
import com.linkedin.davinci.kafka.consumer.StoreBufferService;
import com.linkedin.davinci.kafka.consumer.StoreIngestionTask;
import com.linkedin.davinci.kafka.consumer.StoreIngestionTaskFactory;
import com.linkedin.davinci.notifier.LogNotifier;
import com.linkedin.davinci.notifier.VeniceNotifier;
import com.linkedin.davinci.stats.AggHostLevelIngestionStats;
import com.linkedin.davinci.stats.AggVersionedDIVStats;
import com.linkedin.davinci.stats.AggVersionedIngestionStats;
import com.linkedin.davinci.stats.AggVersionedStorageEngineStats;
import com.linkedin.davinci.stats.ingestion.heartbeat.HeartbeatMonitoringService;
import com.linkedin.davinci.storage.StorageEngineMetadataService;
import com.linkedin.davinci.storage.StorageService;
import com.linkedin.davinci.store.StorageEngine;
import com.linkedin.davinci.store.view.VeniceViewWriterFactory;
import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.integration.utils.PubSubBrokerConfigs;
import com.linkedin.venice.integration.utils.PubSubBrokerWrapper;
import com.linkedin.venice.integration.utils.ServiceFactory;
import com.linkedin.venice.kafka.protocol.state.PartitionState;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.pubsub.PubSubAdminAdapterContext;
import com.linkedin.venice.pubsub.PubSubClientsFactory;
import com.linkedin.venice.pubsub.PubSubConstants;
import com.linkedin.venice.pubsub.PubSubContext;
import com.linkedin.venice.pubsub.PubSubPositionDeserializer;
import com.linkedin.venice.pubsub.PubSubProducerAdapterFactory;
import com.linkedin.venice.pubsub.PubSubTopicConfiguration;
import com.linkedin.venice.pubsub.PubSubTopicRepository;
import com.linkedin.venice.pubsub.api.PubSubAdminAdapter;
import com.linkedin.venice.pubsub.api.PubSubMessageDeserializer;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.manager.TopicManagerContext;
import com.linkedin.venice.pubsub.manager.TopicManagerRepository;
import com.linkedin.venice.schema.rmd.v1.RmdSchemaGeneratorV1;
import com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serialization.avro.InternalAvroSpecificSerializer;
import com.linkedin.venice.utils.DaemonThreadFactory;
import com.linkedin.venice.utils.DiskUsage;
import com.linkedin.venice.utils.PropertyBuilder;
import com.linkedin.venice.utils.SystemTime;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.utils.lazy.Lazy;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.VeniceWriterFactory;
import com.linkedin.venice.writer.VeniceWriterOptions;
import io.tehuti.metrics.MetricsRepository;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.avro.Schema;
import org.apache.commons.io.FileUtils;
import org.apache.helix.manager.zk.ZKHelixAdmin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * MinimalAAIngestionHarness — programmable Venice Active-Active ingestion test harness.
 *
 * <p>
 * This harness exercises Venice's Active-Active write-compute ingestion path with <b>real Kafka</b>
 * and <b>real RocksDB</b>, but with everything else (Helix, ZooKeeper, controllers, routers, D2,
 * schema registry) replaced by minimal in-memory stubs. It is intended as a faster, lower-noise
 * alternative to {@code VeniceTwoLayerMultiRegionMultiClusterWrapper} for AA ingestion micro-benchmarks
 * and component-level perf experiments.
 * </p>
 *
 * <p>
 * <b>Phase 4 status:</b> Brokers + topics + per-region RocksDB + per-region
 * {@link ActiveActiveStoreIngestionTask} are now operational. Each region runs a real AA SIT against
 * its own kafka cluster, with cross-region RT consumption enabled via a {@code TopicSwitch} broadcast
 * after SOP/EOP. See {@code dep-graph.md} for the wiring decisions for all 25 builder setters.
 * </p>
 */
public class MinimalAAIngestionHarness {
  private static final Logger LOGGER = LogManager.getLogger(MinimalAAIngestionHarness.class);

  /** Replication factor for in-process test brokers — only one broker per region exists. */
  static final int DEFAULT_TOPIC_REPLICATION_FACTOR = 1;
  /** Default topic retention. Long enough to cover any realistic test run. */
  static final long DEFAULT_TOPIC_RETENTION_MS = Duration.ofDays(3).toMillis();

  /** Default key schema (string). */
  public static final String DEFAULT_KEY_SCHEMA_STR = "\"string\"";
  /** Default value schema — matches {@code ActiveActiveIngestionBenchmark.BenchmarkRecord}. */
  public static final String DEFAULT_VALUE_SCHEMA_STR = "{\n" + "  \"type\": \"record\",\n"
      + "  \"name\": \"BenchmarkRecord\",\n" + "  \"namespace\": \"com.linkedin.venice.benchmark\",\n"
      + "  \"fields\": [\n" + "    { \"name\": \"name\", \"type\": \"string\", \"default\": \"default_name\" },\n"
      + "    { \"name\": \"age\", \"type\": \"int\", \"default\": -1 },\n"
      + "    { \"name\": \"score\", \"type\": \"double\", \"default\": 0.0 },\n"
      + "    { \"name\": \"tags\", \"type\": " + "{ \"type\": \"map\", \"values\": \"string\" }, \"default\": {} }\n"
      + "  ]\n" + "}";

  /**
   * Immutable configuration container for harness construction.
   *
   * <p>Captures the cluster shape (number of regions, partitions, region names), the single store
   * the harness will host, and the version number to bring up. All values are validated eagerly
   * in the constructor so misconfigurations fail-fast at {@code Config} construction rather than
   * partway through {@link MinimalAAIngestionHarness#start()}.
   */
  public static final class Config {
    private final int regionCount;
    private final int partitionCount;
    private final String storeName;
    private final List<String> regionNames;
    private final int versionNumber;

    /**
     * Convenience constructor that uses default region names ({@code "dc-0"}, {@code "dc-1"}, ...)
     * and version number {@code 1}.
     *
     * @param regionCount number of simulated regions; each gets its own kafka broker, RocksDB, and
     *                    {@link ActiveActiveStoreIngestionTask}. Must be {@code > 0}.
     * @param partitionCount number of partitions per topic; same value applied to RT and VT. Must
     *                       be {@code > 0}.
     * @param storeName the single store name the harness will host. Must be non-empty.
     */
    public Config(int regionCount, int partitionCount, String storeName) {
      this(regionCount, partitionCount, storeName, defaultRegionNames(regionCount), 1);
    }

    /**
     * Full constructor for callers who need to override region names or version number.
     *
     * @param regionCount number of simulated regions. Must be {@code > 0}.
     * @param partitionCount partitions per topic. Must be {@code > 0}.
     * @param storeName the single store name the harness will host. Must be non-empty.
     * @param regionNames symbolic region names; size must match {@code regionCount}. Names appear
     *                    in log lines and are used to seed the kafka cluster map (which the AA SIT
     *                    consults when computing source-fabric routing).
     * @param versionNumber the single version the harness will bring up. Must be {@code > 0}.
     * @throws IllegalArgumentException if any argument fails validation.
     */
    public Config(int regionCount, int partitionCount, String storeName, List<String> regionNames, int versionNumber) {
      if (regionCount <= 0) {
        throw new IllegalArgumentException("regionCount must be > 0, was " + regionCount);
      }
      if (partitionCount <= 0) {
        throw new IllegalArgumentException("partitionCount must be > 0, was " + partitionCount);
      }
      if (storeName == null || storeName.isEmpty()) {
        throw new IllegalArgumentException("storeName must be non-empty");
      }
      if (regionNames == null || regionNames.size() != regionCount) {
        throw new IllegalArgumentException(
            "regionNames must have exactly regionCount=" + regionCount + " entries, got "
                + (regionNames == null ? "null" : regionNames.size()));
      }
      if (versionNumber <= 0) {
        throw new IllegalArgumentException("versionNumber must be > 0, was " + versionNumber);
      }
      this.regionCount = regionCount;
      this.partitionCount = partitionCount;
      this.storeName = storeName;
      this.regionNames = Collections.unmodifiableList(new ArrayList<>(regionNames));
      this.versionNumber = versionNumber;
    }

    /** @return the number of simulated regions configured for this harness. */
    public int getRegionCount() {
      return regionCount;
    }

    /** @return the partition count applied to both RT and VT topics on every region. */
    public int getPartitionCount() {
      return partitionCount;
    }

    /** @return the single store name this harness will host. */
    public String getStoreName() {
      return storeName;
    }

    /** @return an unmodifiable list of region names; index aligns with broker / SIT indices. */
    public List<String> getRegionNames() {
      return regionNames;
    }

    /** @return the single store-version number this harness will bring up. */
    public int getVersionNumber() {
      return versionNumber;
    }

    /**
     * Look up a region name by index.
     *
     * @param regionIdx zero-based index in the region list.
     * @return the region name at that index.
     * @throws IndexOutOfBoundsException if {@code regionIdx} is out of range.
     */
    public String getRegionName(int regionIdx) {
      return regionNames.get(regionIdx);
    }

    private static List<String> defaultRegionNames(int regionCount) {
      List<String> names = new ArrayList<>(regionCount);
      for (int i = 0; i < regionCount; i++) {
        names.add("dc-" + i);
      }
      return Collections.unmodifiableList(names);
    }
  }

  private final Config config;
  private final List<PubSubBrokerWrapper> brokers = new ArrayList<>();
  private final PubSubTopicRepository pubSubTopicRepository = new PubSubTopicRepository();
  private final PubSubTopic realTimeTopic;
  private final PubSubTopic versionTopic;
  /**
   * Phase 1 store repo, lazily initialized on {@link #start()}. Required by {@link StorageService}'s
   * constructor for AA-replication-metadata gating; held as a field so it can be reused across regions
   * and so Phase 4 wiring can grab the same instance.
   */
  private InMemoryReadOnlyStoreRepository storeRepository;
  /** Phase 1 schema repo. Required by SIT for value/RMD/WC schema lookups. */
  private InMemoryReadOnlySchemaRepository schemaRepository;
  /** Per-region root tmp dir, e.g. {@code <jvmTempDir>/lean-harness-<random>/region-0}. */
  private final List<File> regionTempDirs = new ArrayList<>();
  /** Per-region {@link StorageService}; index aligns with {@link #brokers}. */
  private final List<StorageService> storageServices = new ArrayList<>();
  /** Per-region {@link StorageEngine} for the version topic. Index aligns with {@link #brokers}. */
  private final List<StorageEngine> versionTopicStorageEngines = new ArrayList<>();
  /** Per-region {@link VeniceStoreVersionConfig} used to open the version topic engine. */
  private final List<VeniceStoreVersionConfig> versionTopicStoreConfigs = new ArrayList<>();

  // ============= Phase 4 fields =============
  /** Per-region {@link VeniceServerConfig}; each carries the kafkaClusterMap entry for both regions. */
  private final List<VeniceServerConfig> serverConfigs = new ArrayList<>();
  /** Per-region {@link VeniceWriterFactory}; index aligns with brokers. */
  private final List<VeniceWriterFactory> veniceWriterFactories = new ArrayList<>();
  /**
   * Per-region VT writer (used for SOP/EOP/TopicSwitch broadcasts on the local VT). Closed on stop().
   */
  private final List<VeniceWriter<byte[], byte[], byte[]>> vtWriters = new ArrayList<>();
  /**
   * Per-region RT writer (used by tests/benchmarks to push RT records into the local RT). Closed on stop().
   */
  private final List<VeniceWriter<byte[], byte[], byte[]>> rtWriters = new ArrayList<>();
  /** Per-region {@link AggKafkaConsumerService}; multiplex consumer service per region. */
  private final List<AggKafkaConsumerService> aggKafkaConsumerServices = new ArrayList<>();
  /** Per-region {@link StoreBufferService} (drainer). */
  private final List<StoreBufferService> storeBufferServices = new ArrayList<>();
  /** Per-region {@link StorageEngineMetadataService}. */
  private final List<StorageEngineMetadataService> storageMetadataServices = new ArrayList<>();
  /** Per-region {@link IngestionThrottler}. */
  private final List<IngestionThrottler> ingestionThrottlers = new ArrayList<>();
  /** Per-region {@link TopicManagerRepository}. */
  private final List<TopicManagerRepository> topicManagerRepositories = new ArrayList<>();
  /** Per-region executor pools (AA/WC parallel processing + storage lookup). */
  private final List<ExecutorService> aaWcParallelProcessingPools = new ArrayList<>();
  private final List<ExecutorService> aaWcStorageLookupPools = new ArrayList<>();
  /** Per-region {@link RemoteIngestionRepairService}. */
  private final List<RemoteIngestionRepairService> remoteIngestionRepairServices = new ArrayList<>();
  /** Per-region {@link ActiveActiveStoreIngestionTask}. */
  private final List<StoreIngestionTask> ingestionTasks = new ArrayList<>();
  /** Per-region thread that runs the ingestion task. */
  private final List<Thread> ingestionThreads = new ArrayList<>();
  /** Per-region MetricsRepository (no exporter). */
  private final List<MetricsRepository> metricsRepositories = new ArrayList<>();
  /** Per-region {@link HeartbeatMonitoringService} mock. */
  private final List<HeartbeatMonitoringService> heartbeatMonitoringServices = new ArrayList<>();

  /** Shared kafka cluster map: regionId → {name, url}. */
  private Map<String, Map<String, String>> kafkaClusterMap;

  private volatile boolean started = false;

  /**
   * Construct a harness instance. Allocates only lightweight in-memory state — no kafka brokers,
   * no RocksDB, no thread pools. Call {@link #start()} to bring up the actual infrastructure.
   *
   * @param config the harness configuration; must not be null.
   * @throws IllegalArgumentException if {@code config} is null.
   */
  public MinimalAAIngestionHarness(Config config) {
    if (config == null) {
      throw new IllegalArgumentException("config must not be null");
    }
    this.config = config;
    this.realTimeTopic = pubSubTopicRepository.getTopic(Utils.composeRealTimeTopic(config.getStoreName()));
    this.versionTopic =
        pubSubTopicRepository.getTopic(Version.composeKafkaTopic(config.getStoreName(), config.getVersionNumber()));
  }

  /**
   * Bring up the harness end-to-end. Performs the following steps in order:
   * <ol>
   *   <li>Start one {@link PubSubBrokerWrapper} per region (real Kafka).</li>
   *   <li>Create RT and VT topics on each broker via {@code PubSubAdminAdapter}.</li>
   *   <li>Build the in-memory {@link InMemoryReadOnlyStoreRepository} and
   *       {@link InMemoryReadOnlySchemaRepository} (shared across regions).</li>
   *   <li>Stand up one {@link StorageService} per region with a real RocksDB data root under a
   *       region-specific temp dir, and open a {@link StorageEngine} for the version topic with
   *       the configured partition count.</li>
   *   <li>Wire all ~25 dependencies of {@link ActiveActiveStoreIngestionTask} per region (see
   *       {@code dep-graph.md}) and instantiate one task per region.</li>
   *   <li>Inject SOP and EOP control messages onto each region's VT to make the partitions
   *       "ready-to-serve" without requiring a full push.</li>
   *   <li>Start each task on a daemon worker thread.</li>
   *   <li>Subscribe each task to ALL partitions of its local VT.</li>
   *   <li>Broadcast {@code TopicSwitch} on each VT pointing at all regions' RT URLs (enables
   *       cross-region replication).</li>
   *   <li>Promote each task to LEADER for ALL partitions (single-replica per region, no follower).</li>
   * </ol>
   *
   * <p>Total time: ~13–15 seconds (vs ~60–120 seconds for a comparable
   * {@code VeniceTwoLayerMultiRegionMultiClusterWrapper}). On any failure, all
   * already-allocated resources are torn down before the exception propagates.
   *
   * @throws IllegalStateException if {@link #start()} has already been called.
   * @throws RuntimeException wrapping any underlying failure (kafka start, topic create, RocksDB
   *         open, SIT wiring, etc.).
   */
  public synchronized void start() {
    if (started) {
      throw new IllegalStateException("Harness already started; call stop() first.");
    }
    LOGGER.info(
        "Starting MinimalAAIngestionHarness: storeName={}, regionCount={}, partitionCount={}, regions={}",
        config.getStoreName(),
        config.getRegionCount(),
        config.getPartitionCount(),
        config.getRegionNames());
    long startNanos = System.nanoTime();
    try {
      // 1) Start one PubSubBrokerWrapper per region.
      for (int i = 0; i < config.getRegionCount(); i++) {
        String regionName = config.getRegionName(i);
        PubSubBrokerWrapper broker =
            ServiceFactory.getPubSubBroker(new PubSubBrokerConfigs.Builder().setRegionName(regionName).build());
        brokers.add(broker);
        LOGGER.info("Started broker for region {}: {}", regionName, broker.getAddress());
      }

      // 2) Create RT and VT topics on each broker.
      for (int i = 0; i < brokers.size(); i++) {
        createTopicsOnBroker(brokers.get(i), config.getRegionName(i));
      }

      // 3) Build the in-memory store + schema repos (shared across regions).
      this.storeRepository = new InMemoryReadOnlyStoreRepository(
          config.getStoreName(),
          config.getPartitionCount(),
          InMemoryReadOnlyStoreRepository.DEFAULT_HYBRID_REWIND_SECONDS,
          InMemoryReadOnlyStoreRepository.DEFAULT_HYBRID_OFFSET_LAG_THRESHOLD,
          config.getVersionNumber());
      Schema keySchema = new Schema.Parser().parse(DEFAULT_KEY_SCHEMA_STR);
      Schema valueSchema = new Schema.Parser().parse(DEFAULT_VALUE_SCHEMA_STR);
      Schema rmdSchema = new RmdSchemaGeneratorV1().generateMetadataSchema(valueSchema);
      Schema writeComputeSchema = WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(valueSchema);
      this.schemaRepository = new InMemoryReadOnlySchemaRepository(
          config.getStoreName(),
          keySchema,
          valueSchema,
          rmdSchema,
          writeComputeSchema);

      // 4) Build the kafka cluster map shared across regions: clusterId → {name, url}.
      this.kafkaClusterMap = buildKafkaClusterMap();

      // 5) Stand up StorageService per region.
      long storageStartNanos = System.nanoTime();
      for (int i = 0; i < config.getRegionCount(); i++) {
        String regionName = config.getRegionName(i);
        File regionRoot = createRegionTempDir(regionName);
        regionTempDirs.add(regionRoot);

        StorageService storageService = createStorageService(regionRoot, regionName);
        storageServices.add(storageService);

        VeniceStoreVersionConfig vtStoreConfig = new VeniceStoreVersionConfig(
            versionTopic.getName(),
            buildServerProperties(regionRoot, brokers.get(i).getAddress()),
            PersistenceType.ROCKS_DB);
        versionTopicStoreConfigs.add(vtStoreConfig);

        // Open the storage engine for the version topic with all configured partitions.
        StorageEngine engine = storageService.openStoreForNewPartition(vtStoreConfig, /*partitionId*/ 0, () -> null);
        for (int p = 1; p < config.getPartitionCount(); p++) {
          engine.addStoragePartitionIfAbsent(p);
        }
        versionTopicStorageEngines.add(engine);
        LOGGER.info(
            "Region {}: opened StorageEngine for {} on {} (partitions=[0,{}))",
            regionName,
            versionTopic.getName(),
            regionRoot.getAbsolutePath(),
            config.getPartitionCount());
      }
      long storageElapsedMs = (System.nanoTime() - storageStartNanos) / 1_000_000L;
      LOGGER.info(
          "MinimalAAIngestionHarness brought up {} StorageService instance(s) in {} ms",
          config.getRegionCount(),
          storageElapsedMs);

      // 6) Wire and start ActiveActiveStoreIngestionTask per region.
      long sitStartNanos = System.nanoTime();
      for (int i = 0; i < config.getRegionCount(); i++) {
        wireRegionForIngestion(i);
      }

      // 7) Inject SOP/EOP onto each region's VT topic so each task considers its partition "ready".
      injectSopEopOnAllRegions();

      // 8) Now actually start each task on a worker thread.
      for (int i = 0; i < config.getRegionCount(); i++) {
        startIngestionTaskForRegion(i);
      }

      // 9) Subscribe each task to ALL partitions of its local VT (one task per region handles every
      // partition, mirroring production where each Venice server runs one SIT per VT and that SIT
      // is subscribed to all replicas it owns).
      for (int i = 0; i < config.getRegionCount(); i++) {
        subscribeRegionToAllVTPartitions(i);
      }
      // 10) Broadcast TopicSwitch on each VT pointing at both RT URLs (cross-region replication).
      // Done before promoteToLeader so the SIT already has the topic switch in PCS before it transitions.
      broadcastTopicSwitchOnAllRegions();
      // 11) Promote each region's task to LEADER for ALL partitions — there are no follower replicas in
      // this 2-region setup (replicationFactor=1), so each region's local task is the sole leader.
      // This triggers the LFSIT STANDBY_TO_LEADER state machine, which will start consuming RT topics
      // on both regions per the broadcast TopicSwitch.
      for (int i = 0; i < config.getRegionCount(); i++) {
        promoteRegionTaskToLeaderForAllPartitions(i);
      }

      long sitElapsedMs = (System.nanoTime() - sitStartNanos) / 1_000_000L;
      LOGGER
          .info("MinimalAAIngestionHarness wired {} ingestion task(s) in {} ms", config.getRegionCount(), sitElapsedMs);

      started = true;
      long elapsedMs = (System.nanoTime() - startNanos) / 1_000_000L;
      LOGGER.info("MinimalAAIngestionHarness start() completed in {} ms", elapsedMs);
    } catch (RuntimeException e) {
      LOGGER.error("Harness start() failed; shutting down already-allocated resources.", e);
      stopQuietly();
      throw e;
    }
  }

  /**
   * Tear down the harness in roughly the reverse order of {@link #start()}: ingestion tasks →
   * consumer services → drainers/buffers → writers → topic managers → throttlers → executors →
   * storage services → brokers → temp dirs.
   *
   * <p>Idempotent: calling {@code stop()} a second time is a no-op. All errors during teardown
   * are logged but not propagated, so a partial-startup failure cleanup will still complete.
   * After {@code stop()} returns, the harness can be re-{@link #start()}ed (though typically
   * tests construct a fresh instance to avoid carrying any latent state).
   */
  public synchronized void stop() {
    LOGGER.info("Stopping MinimalAAIngestionHarness…");
    stopQuietly();
    started = false;
  }

  private void stopQuietly() {
    // Teardown order: ingestion tasks → consumer services → drainer/buffer → writers → topic mgr →
    // throttlers → executors → storage → brokers → temp dirs.
    for (StoreIngestionTask task: ingestionTasks) {
      try {
        task.close();
      } catch (Throwable t) {
        LOGGER.error("Error closing ingestion task: {}", t.toString(), t);
      }
    }
    for (Thread t: ingestionThreads) {
      try {
        t.interrupt();
        t.join(TimeUnit.SECONDS.toMillis(10));
      } catch (Throwable e) {
        LOGGER.error("Error joining ingestion thread {}: {}", t.getName(), e.toString(), e);
      }
    }
    ingestionTasks.clear();
    ingestionThreads.clear();

    for (AggKafkaConsumerService svc: aggKafkaConsumerServices) {
      try {
        svc.stop();
      } catch (Throwable t) {
        LOGGER.error("Error stopping AggKafkaConsumerService: {}", t.toString(), t);
      }
    }
    aggKafkaConsumerServices.clear();

    for (StoreBufferService svc: storeBufferServices) {
      try {
        svc.stop();
      } catch (Throwable t) {
        LOGGER.error("Error stopping StoreBufferService: {}", t.toString(), t);
      }
    }
    storeBufferServices.clear();

    for (VeniceWriter<byte[], byte[], byte[]> w: vtWriters) {
      try {
        w.close();
      } catch (Throwable t) {
        LOGGER.error("Error closing VT writer: {}", t.toString(), t);
      }
    }
    vtWriters.clear();
    for (VeniceWriter<byte[], byte[], byte[]> w: rtWriters) {
      try {
        w.close();
      } catch (Throwable t) {
        LOGGER.error("Error closing RT writer: {}", t.toString(), t);
      }
    }
    rtWriters.clear();
    veniceWriterFactories.clear();

    for (TopicManagerRepository tmr: topicManagerRepositories) {
      try {
        tmr.close();
      } catch (Throwable t) {
        LOGGER.error("Error closing TopicManagerRepository: {}", t.toString(), t);
      }
    }
    topicManagerRepositories.clear();

    for (IngestionThrottler thr: ingestionThrottlers) {
      try {
        thr.close();
      } catch (Throwable t) {
        LOGGER.error("Error closing IngestionThrottler: {}", t.toString(), t);
      }
    }
    ingestionThrottlers.clear();

    for (ExecutorService exec: aaWcParallelProcessingPools) {
      exec.shutdownNow();
    }
    aaWcParallelProcessingPools.clear();
    for (ExecutorService exec: aaWcStorageLookupPools) {
      exec.shutdownNow();
    }
    aaWcStorageLookupPools.clear();

    for (StorageEngineMetadataService svc: storageMetadataServices) {
      try {
        svc.stop();
      } catch (Throwable t) {
        LOGGER.error("Error stopping StorageEngineMetadataService: {}", t.toString(), t);
      }
    }
    storageMetadataServices.clear();
    remoteIngestionRepairServices.clear();
    heartbeatMonitoringServices.clear();
    metricsRepositories.clear();
    serverConfigs.clear();

    versionTopicStorageEngines.clear();
    versionTopicStoreConfigs.clear();
    for (StorageService storageService: storageServices) {
      try {
        storageService.stop();
      } catch (Throwable t) {
        LOGGER.error("Error while stopping StorageService: {}", t.toString(), t);
      }
    }
    storageServices.clear();

    for (PubSubBrokerWrapper broker: brokers) {
      try {
        Utils.closeQuietlyWithErrorLogged(broker);
      } catch (Throwable t) {
        LOGGER.error("Error while closing broker {}: {}", broker.getAddressForLogging(), t.toString(), t);
      }
    }
    brokers.clear();

    for (File regionDir: regionTempDirs) {
      deleteRecursivelyQuietly(regionDir);
    }
    regionTempDirs.clear();
    storeRepository = null;
    schemaRepository = null;
    kafkaClusterMap = null;
  }

  private static void deleteRecursivelyQuietly(File path) {
    if (path == null || !path.exists()) {
      return;
    }
    try {
      FileUtils.deleteDirectory(path);
    } catch (IOException e) {
      LOGGER.error("Failed to delete temp dir {}: {}", path.getAbsolutePath(), e.toString(), e);
    }
  }

  // ===========================================================================================
  // Region accessors
  // ===========================================================================================

  /**
   * @param region zero-based region index.
   * @return the running {@link PubSubBrokerWrapper} (real Kafka) for that region.
   * @throws IllegalStateException if {@link #start()} has not been called.
   * @throws IllegalArgumentException if {@code region} is out of range.
   */
  public PubSubBrokerWrapper getBrokerForRegion(int region) {
    if (!started) {
      throw new IllegalStateException("Harness not started; call start() first.");
    }
    if (region < 0 || region >= brokers.size()) {
      throw new IllegalArgumentException("Region " + region + " is out of bounds [0, " + brokers.size() + ")");
    }
    return brokers.get(region);
  }

  /**
   * @param region zero-based region index.
   * @return the bootstrap address ({@code host:port}) of that region's kafka broker. Convenience
   *         wrapper around {@link #getBrokerForRegion(int)}.
   */
  public String getBrokerAddress(int region) {
    return getBrokerForRegion(region).getAddress();
  }

  /**
   * @param regionId zero-based region index.
   * @return the running {@link StorageService} for that region. Each region has its own RocksDB
   *         data root under a region-specific temp directory.
   * @throws IllegalStateException if {@link #start()} has not been called.
   * @throws IllegalArgumentException if {@code regionId} is out of range.
   */
  public StorageService getStorageServiceForRegion(int regionId) {
    if (!started) {
      throw new IllegalStateException("Harness not started; call start() first.");
    }
    if (regionId < 0 || regionId >= storageServices.size()) {
      throw new IllegalArgumentException(
          "Region " + regionId + " is out of bounds [0, " + storageServices.size() + ")");
    }
    return storageServices.get(regionId);
  }

  /**
   * @param regionId zero-based region index.
   * @return the {@link StorageEngine} for the version topic on that region. All partitions are
   *         opened on this engine; callers should compute partition via
   *         {@link com.linkedin.venice.partitioner.DefaultVenicePartitioner} or equivalent.
   * @throws IllegalStateException if {@link #start()} has not been called.
   * @throws IllegalArgumentException if {@code regionId} is out of range.
   */
  public StorageEngine getStorageEngineForRegion(int regionId) {
    if (!started) {
      throw new IllegalStateException("Harness not started; call start() first.");
    }
    if (regionId < 0 || regionId >= versionTopicStorageEngines.size()) {
      throw new IllegalArgumentException(
          "Region " + regionId + " is out of bounds [0, " + versionTopicStorageEngines.size() + ")");
    }
    return versionTopicStorageEngines.get(regionId);
  }

  /**
   * @param regionId zero-based region index.
   * @return the per-region root tmp dir (the parent of the RocksDB sub-tree). Useful for tests
   *         that want to inspect or assert on disk state. Deleted by {@link #stop()}.
   * @throws IllegalStateException if {@link #start()} has not been called.
   * @throws IllegalArgumentException if {@code regionId} is out of range.
   */
  public File getRegionTempDir(int regionId) {
    if (!started) {
      throw new IllegalStateException("Harness not started; call start() first.");
    }
    if (regionId < 0 || regionId >= regionTempDirs.size()) {
      throw new IllegalArgumentException("Region " + regionId + " is out of bounds [0, " + regionTempDirs.size() + ")");
    }
    return regionTempDirs.get(regionId);
  }

  /**
   * @return the in-memory store repository shared across all regions. Hosts a single store
   *         configured for AA + write-compute + hybrid + the configured partition count.
   *         Available before {@link #start()} is called only as null; callable after start.
   */
  public InMemoryReadOnlyStoreRepository getStoreRepository() {
    return storeRepository;
  }

  /**
   * @return the in-memory schema repository shared across all regions. Pre-loaded with the value
   *         schema, RMD schema (v1), and write-compute schema (v1) for the harness's single store.
   *         Available before {@link #start()} is called only as null; callable after start.
   */
  public InMemoryReadOnlySchemaRepository getSchemaRepository() {
    return schemaRepository;
  }

  /** @return the {@link PubSubTopic} handle for the harness's real-time topic. */
  public PubSubTopic getRealTimeTopic() {
    return realTimeTopic;
  }

  /** @return the {@link PubSubTopic} handle for the harness's version topic. */
  public PubSubTopic getVersionTopic() {
    return versionTopic;
  }

  /** @return the shared {@link PubSubTopicRepository} used by the harness's brokers and SITs. */
  public PubSubTopicRepository getPubSubTopicRepository() {
    return pubSubTopicRepository;
  }

  /**
   * @param regionId zero-based region index.
   * @return the running {@link ActiveActiveStoreIngestionTask} (typed loosely as {@link StoreIngestionTask})
   *         for the requested region. The returned task is already running and subscribed/promoted-to-leader
   *         on every partition; callers should not call {@code start()} or {@code subscribePartition()} on it.
   * @throws IllegalStateException if {@link #start()} has not been called.
   * @throws IllegalArgumentException if {@code regionId} is out of range.
   */
  public StoreIngestionTask getIngestionTaskForRegion(int regionId) {
    if (!started) {
      throw new IllegalStateException("Harness not started; call start() first.");
    }
    if (regionId < 0 || regionId >= ingestionTasks.size()) {
      throw new IllegalArgumentException("Region " + regionId + " is out of bounds [0, " + ingestionTasks.size() + ")");
    }
    return ingestionTasks.get(regionId);
  }

  /**
   * @param regionId zero-based region index.
   * @return a {@link VeniceWriter} configured to write to the RT topic on the given region's broker.
   *         The harness owns this writer; do not call {@link VeniceWriter#close()} on it. Use
   *         {@link VeniceWriter#put(Object, Object, int)},
   *         {@link VeniceWriter#update(Object, Object, int, int, com.linkedin.venice.pubsub.api.PubSubProducerCallback)},
   *         or {@link VeniceWriter#delete(Object, com.linkedin.venice.pubsub.api.PubSubProducerCallback)}
   *         with raw {@code byte[]} key/value/update arguments — the writer wraps them in the
   *         standard KME envelope identical to what production producers would emit.
   * @throws IllegalStateException if {@link #start()} has not been called.
   * @throws IllegalArgumentException if {@code regionId} is out of range.
   */
  public VeniceWriter<byte[], byte[], byte[]> getVeniceWriterForRTTopic(int regionId) {
    if (!started) {
      throw new IllegalStateException("Harness not started; call start() first.");
    }
    if (regionId < 0 || regionId >= rtWriters.size()) {
      throw new IllegalArgumentException("Region " + regionId + " is out of bounds [0, " + rtWriters.size() + ")");
    }
    return rtWriters.get(regionId);
  }

  /** @return the {@link Config} this harness was constructed with. */
  public Config getConfig() {
    return config;
  }

  /** @return {@code true} if {@link #start()} has been called and {@link #stop()} has not yet completed. */
  public boolean isStarted() {
    return started;
  }

  // ===========================================================================================
  // Topic creation helpers (Phase 2)
  // ===========================================================================================

  private void createTopicsOnBroker(PubSubBrokerWrapper broker, String regionName) {
    String clientId = Utils.getUniqueString("lean-harness-admin-" + regionName);
    PubSubClientsFactory clientsFactory = broker.getPubSubClientsFactory();
    Properties properties = buildPubSubProperties(broker);
    VeniceProperties veniceProperties = new VeniceProperties(properties);

    PubSubAdminAdapter admin = clientsFactory.getAdminAdapterFactory()
        .create(
            new PubSubAdminAdapterContext.Builder().setAdminClientName(clientId)
                .setVeniceProperties(veniceProperties)
                .setPubSubTopicRepository(pubSubTopicRepository)
                .setPubSubPositionTypeRegistry(broker.getPubSubPositionTypeRegistry())
                .build());
    try {
      PubSubTopicConfiguration topicConfig = new PubSubTopicConfiguration(
          Optional.of(DEFAULT_TOPIC_RETENTION_MS),
          /*isLogCompacted*/ false,
          Optional.of(/*minInSyncReplicas*/ 1),
          /*minLogCompactionLagMs*/ 0L,
          /*maxLogCompactionLagMs*/ Optional.empty());

      for (PubSubTopic topic: Arrays.asList(realTimeTopic, versionTopic)) {
        admin.createTopic(topic, config.getPartitionCount(), DEFAULT_TOPIC_REPLICATION_FACTOR, topicConfig);
        LOGGER.info(
            "Created topic {} (partitions={}, replication={}) on region {}",
            topic.getName(),
            config.getPartitionCount(),
            DEFAULT_TOPIC_REPLICATION_FACTOR,
            regionName);
      }

      Set<PubSubTopic> listed = admin.listAllTopics();
      for (PubSubTopic expected: Arrays.asList(realTimeTopic, versionTopic)) {
        if (!listed.contains(expected)) {
          throw new VeniceException(
              "Expected topic " + expected.getName() + " not present after creation on region " + regionName
                  + "; listed topics: " + listed);
        }
      }
    } finally {
      try {
        admin.close();
      } catch (IOException e) {
        LOGGER.warn("Error closing admin adapter for region {}: {}", regionName, e.toString(), e);
      }
    }
  }

  static Properties buildPubSubProperties(PubSubBrokerWrapper broker) {
    Properties properties = new Properties();
    properties.setProperty(ConfigKeys.KAFKA_BOOTSTRAP_SERVERS, broker.getAddress());
    properties.setProperty(PubSubConstants.PUBSUB_ADMIN_API_DEFAULT_TIMEOUT_MS, String.valueOf(15_000));
    properties.putAll(broker.getAdditionalConfig());
    properties.putAll(broker.getMergeableConfigs());
    return properties;
  }

  // ===========================================================================================
  // Storage helpers (Phase 3)
  // ===========================================================================================

  private File createRegionTempDir(String regionName) {
    File regionRoot = Utils.getTempDataDirectory("lean-harness-region-" + regionName + "-");
    File rocksdbRoot = new File(regionRoot, "rocksdb");
    if (!rocksdbRoot.mkdirs() && !rocksdbRoot.isDirectory()) {
      throw new VeniceException("Failed to create RocksDB root: " + rocksdbRoot.getAbsolutePath());
    }
    LOGGER.info("Region {}: prepared temp dir {}", regionName, regionRoot.getAbsolutePath());
    return regionRoot;
  }

  private StorageService createStorageService(File regionRoot, String regionName) {
    VeniceProperties serverProps =
        buildServerProperties(regionRoot, brokers.get(brokerIndexForRegion(regionName)).getAddress());
    VeniceConfigLoader configLoader = new VeniceConfigLoader(VeniceProperties.empty(), serverProps);
    StorageService storageService = new StorageService(
        configLoader,
        mock(AggVersionedStorageEngineStats.class),
        /*rocksDBMemoryStats*/ null,
        AvroProtocolDefinition.STORE_VERSION_STATE.getSerializer(),
        AvroProtocolDefinition.PARTITION_STATE.getSerializer(),
        storeRepository,
        /*restoreDataPartitions*/ false,
        /*restoreMetadataPartitions*/ false);
    storageService.start();
    LOGGER.info(
        "Region {}: started StorageService rooted at {}",
        regionName,
        new File(regionRoot, "rocksdb").getAbsolutePath());
    return storageService;
  }

  private int brokerIndexForRegion(String regionName) {
    for (int i = 0; i < brokers.size(); i++) {
      if (config.getRegionName(i).equals(regionName)) {
        return i;
      }
    }
    throw new VeniceException("Unknown region: " + regionName);
  }

  /**
   * Build the {@link VeniceProperties} bag for a given region, parameterized by the per-region RocksDB
   * data root and broker bootstrap address. This is reused as both the StorageService config and the
   * VeniceServerConfig source.
   */
  private VeniceProperties buildServerProperties(File regionRoot, String localBrokerAddress) {
    File rocksdbRoot = new File(regionRoot, "rocksdb");
    return new PropertyBuilder().put(CLUSTER_NAME, "lean-aa-harness")
        .put(ZOOKEEPER_ADDRESS, "localhost:2181")
        .put(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.toString())
        .put(ConfigKeys.KAFKA_BOOTSTRAP_SERVERS, localBrokerAddress)
        .put(LISTENER_PORT, 7072)
        .put(ConfigKeys.ADMIN_PORT, 7073)
        .put(DATA_BASE_PATH, rocksdbRoot.getAbsolutePath())
        .put(SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED, true)
        // Match VeniceServerConfig's default (8) so the lean harness's drainer parallelism matches
        // what the full multi-region wrapper exposes — important for fair side-by-side benchmarks.
        .put(STORE_WRITER_NUMBER, 8)
        .put(ConfigKeys.SERVER_INGESTION_HEARTBEAT_INTERVAL_MS, 1000)
        .put(ConfigKeys.SERVER_LEADER_COMPLETE_STATE_CHECK_IN_FOLLOWER_VALID_INTERVAL_MS, 1000)
        .put(ConfigKeys.HYBRID_QUOTA_ENFORCEMENT_ENABLED, false)
        .put(ConfigKeys.PARTICIPANT_MESSAGE_STORE_ENABLED, false)
        .put(ConfigKeys.SERVER_IDLE_INGESTION_TASK_CLEANUP_INTERVAL_IN_SECONDS, -1)
        .put(ConfigKeys.SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS, 0L)
        // Disable DoL (Declaration of Leadership) loopback for harness — there is no handover scenario
        // to coordinate, and the DoL produce/consume loopback through VT introduces an extra wait that
        // can hold the IN_TRANSITION_FROM_STANDBY_TO_LEADER state indefinitely if the harness's
        // SOP/EOP/TopicSwitch writes interleave with the SIT-internal VeniceWriter's segment numbers.
        // Falling back to legacy time-based gating with a 0s delay gives an immediate transition.
        .put(ConfigKeys.SERVER_LEADER_HANDOVER_USE_DOL_MECHANISM_FOR_SYSTEM_STORES, false)
        .put(ConfigKeys.SERVER_LEADER_HANDOVER_USE_DOL_MECHANISM_FOR_USER_STORES, false)
        // Disable heartbeat-lag-based ready-to-serve gating — the harness has no heartbeat producers,
        // so the lag would never go to zero and the partition would never be considered "ready".
        .put(ConfigKeys.SERVER_USE_HEARTBEAT_LAG_FOR_READY_TO_SERVE_CHECK_ENABLED, false)
        .build();
  }

  // ===========================================================================================
  // Phase 4: AA SIT wiring
  // ===========================================================================================

  /** Build the kafka cluster map: regionId → {name, url}. */
  private Map<String, Map<String, String>> buildKafkaClusterMap() {
    Map<String, Map<String, String>> map = new HashMap<>();
    for (int i = 0; i < config.getRegionCount(); i++) {
      Map<String, String> mapping = new HashMap<>();
      mapping.put(KAFKA_CLUSTER_MAP_KEY_NAME, config.getRegionName(i));
      mapping.put(KAFKA_CLUSTER_MAP_KEY_URL, brokers.get(i).getAddress());
      map.put(String.valueOf(i), mapping);
    }
    return Collections.unmodifiableMap(map);
  }

  /**
   * Wire all dependencies for the given region's ActiveActiveStoreIngestionTask, but do NOT start it
   * yet (that happens after SOP/EOP injection).
   */
  private void wireRegionForIngestion(int regionId) {
    String regionName = config.getRegionName(regionId);
    PubSubBrokerWrapper broker = brokers.get(regionId);
    File regionRoot = regionTempDirs.get(regionId);
    StorageService storageService = storageServices.get(regionId);

    // 1. Per-region MetricsRepository (no exporter). dep-graph #8/9/10.
    MetricsRepository metricsRepo = new MetricsRepository();
    metricsRepositories.add(metricsRepo);

    // 2. Build VeniceServerConfig with the kafka cluster map. dep-graph #12.
    VeniceProperties props = buildServerProperties(regionRoot, broker.getAddress());
    VeniceServerConfig serverConfig = new VeniceServerConfig(props, kafkaClusterMap);
    serverConfigs.add(serverConfig);

    // 3. PubSubProducerAdapterFactory + PubSubClientsFactory + VeniceWriterFactory (dep-graph #1).
    PubSubProducerAdapterFactory producerAdapterFactory = broker.getPubSubClientsFactory().getProducerAdapterFactory();
    Properties veniceWriterProps = new Properties();
    veniceWriterProps.put(ConfigKeys.KAFKA_BOOTSTRAP_SERVERS, broker.getAddress());
    veniceWriterProps.putAll(broker.getAdditionalConfig());
    veniceWriterProps.putAll(broker.getMergeableConfigs());
    veniceWriterProps.put(PubSubConstants.PUBSUB_PRODUCER_USE_HIGH_THROUGHPUT_DEFAULTS, "true");
    VeniceWriterFactory veniceWriterFactory = new VeniceWriterFactory(
        veniceWriterProps,
        producerAdapterFactory,
        metricsRepo,
        broker.getPubSubPositionTypeRegistry());
    veniceWriterFactories.add(veniceWriterFactory);

    // 4. PubSubContext (dep-graph #20). Build a TopicManagerRepository for the local broker.
    Properties topicMgrProps = new Properties();
    topicMgrProps.putAll(veniceWriterProps);
    final VeniceProperties topicMgrVeniceProps = new VeniceProperties(topicMgrProps);
    TopicManagerContext topicManagerContext =
        new TopicManagerContext.Builder().setPubSubPropertiesSupplier(k -> topicMgrVeniceProps)
            .setPubSubTopicRepository(pubSubTopicRepository)
            .setPubSubPositionTypeRegistry(broker.getPubSubPositionTypeRegistry())
            .setPubSubAdminAdapterFactory(broker.getPubSubClientsFactory().getAdminAdapterFactory())
            .setPubSubConsumerAdapterFactory(broker.getPubSubClientsFactory().getConsumerAdapterFactory())
            .setMetricsRepository(metricsRepo)
            .build();
    TopicManagerRepository topicManagerRepository =
        new TopicManagerRepository(topicManagerContext, broker.getAddress());
    topicManagerRepositories.add(topicManagerRepository);

    // Must use the OPTIMIZED deserializer (which uses {@link OptimizedKafkaValueSerializer}) — the
    // default deserializer copies the value bytes during avro decode, leaving Put.putValue with
    // position=0 in the resulting ByteBuffer. The AA SIT later calls
    // {@code ByteUtils.prependIntHeaderToByteBuffer(buf, schemaId, true /*reuseInput*/)} which
    // requires position >= 4 (so it can rewind 4 bytes and write the schema id header in front).
    // The optimized serializer preserves the position offset into the underlying message bytes,
    // which gives plenty of slack at the front for the prepend operation.
    PubSubMessageDeserializer pubSubMessageDeserializer = PubSubMessageDeserializer.createOptimizedDeserializer();
    PubSubContext pubSubContext = new PubSubContext.Builder().setTopicManagerRepository(topicManagerRepository)
        .setPubSubPositionTypeRegistry(serverConfig.getPubSubPositionTypeRegistry())
        .setPubSubPositionDeserializer(new PubSubPositionDeserializer(serverConfig.getPubSubPositionTypeRegistry()))
        .setPubSubTopicRepository(pubSubTopicRepository)
        .setPubSubMessageDeserializer(pubSubMessageDeserializer)
        .setPubSubClientsFactory(broker.getPubSubClientsFactory())
        .build();

    // 5. StorageMetadataService (dep-graph #4). Real impl backed by the same StorageService.
    InternalAvroSpecificSerializer<PartitionState> partitionStateSerializer =
        AvroProtocolDefinition.PARTITION_STATE.getSerializer();
    StorageEngineMetadataService storageMetadataService =
        new StorageEngineMetadataService(storageService.getStorageEngineRepository(), partitionStateSerializer);
    try {
      storageMetadataService.start();
    } catch (Exception e) {
      throw new VeniceException("Failed to start StorageEngineMetadataService for region " + regionName, e);
    }
    storageMetadataServices.add(storageMetadataService);

    // 6. Stats objects (dep-graph #8/9/10).
    Map<String, StoreIngestionTask> ingestionTaskMapForStats = new HashMap<>();
    AggHostLevelIngestionStats hostLevelIngestionStats = new AggHostLevelIngestionStats(
        metricsRepo,
        serverConfig,
        ingestionTaskMapForStats,
        storeRepository,
        false,
        SystemTime.INSTANCE);
    AggVersionedDIVStats versionedDIVStats =
        new AggVersionedDIVStats(metricsRepo, storeRepository, false, serverConfig.getClusterName());
    AggVersionedIngestionStats versionedIngestionStats =
        new AggVersionedIngestionStats(metricsRepo, storeRepository, serverConfig);

    // 7. StoreBufferService (dep-graph #11).
    StoreBufferService storeBufferService = new StoreBufferService(
        serverConfig.getStoreWriterNumber(),
        serverConfig.getStoreWriterBufferMemoryCapacity(),
        serverConfig.getStoreWriterBufferNotifyDelta(),
        serverConfig.isStoreWriterBufferAfterLeaderLogicEnabled(),
        serverConfig.getLogContext(),
        metricsRepo,
        true,
        serverConfig.getClusterName());
    try {
      storeBufferService.start();
    } catch (Exception e) {
      throw new VeniceException("Failed to start StoreBufferService for region " + regionName, e);
    }
    storeBufferServices.add(storeBufferService);

    // 8. IngestionThrottler (real, transitive dep of AggKafkaConsumerService).
    IngestionThrottler ingestionThrottler =
        new IngestionThrottler(false, serverConfig, () -> Collections.unmodifiableMap(ingestionTaskMapForStats), null);
    ingestionThrottlers.add(ingestionThrottler);

    // 9. KafkaClusterBasedRecordThrottler (no per-region quota; empty map → no throttling).
    KafkaClusterBasedRecordThrottler kafkaClusterBasedRecordThrottler =
        new KafkaClusterBasedRecordThrottler(Collections.emptyMap());

    // 10. AggKafkaConsumerService (dep-graph #14). Stale topic checker is a no-op (always false).
    AggKafkaConsumerService aggKafkaConsumerService = new AggKafkaConsumerService(
        url -> getVenicePropertiesForKafkaUrl(url),
        serverConfig,
        ingestionThrottler,
        kafkaClusterBasedRecordThrottler,
        metricsRepo,
        topicName -> false, // stale topic checker
        topicName -> {
          /* killIngestionTaskRunnable: no-op */ },
        storeRepository,
        pubSubContext);
    try {
      aggKafkaConsumerService.start();
    } catch (Exception e) {
      throw new VeniceException("Failed to start AggKafkaConsumerService for region " + regionName, e);
    }
    // Register a KafkaConsumerService for each kafka cluster URL (local + remote regions).
    for (Map.Entry<String, Map<String, String>> entry: kafkaClusterMap.entrySet()) {
      String kafkaUrl = entry.getValue().get(KAFKA_CLUSTER_MAP_KEY_URL);
      Properties kafkaConsumerProps = getCommonKafkaConsumerProperties(serverConfig, kafkaUrl);
      aggKafkaConsumerService.createKafkaConsumerService(kafkaConsumerProps);
    }
    aggKafkaConsumerServices.add(aggKafkaConsumerService);

    // 11. DiskUsage (dep-graph #13).
    DiskUsage diskUsage = new DiskUsage(regionRoot.getAbsolutePath(), 0.99);

    // 12. CompressorFactory (dep-graph #19).
    StorageEngineBackedCompressorFactory compressorFactory =
        new StorageEngineBackedCompressorFactory(storageMetadataService);

    // 13. Thread pools for AA/WC parallel processing (dep-graph #21/22).
    ExecutorService aaWcParallelPool = Executors.newFixedThreadPool(
        serverConfig.getAAWCWorkloadParallelProcessingThreadPoolSize(),
        new DaemonThreadFactory("AA_WC_PARALLEL_PROCESSING_" + regionName, serverConfig.getLogContext()));
    aaWcParallelProcessingPools.add(aaWcParallelPool);
    ExecutorService aaWcStorageLookupPool = Executors.newFixedThreadPool(
        serverConfig.getAaWCIngestionStorageLookupThreadPoolSize(),
        new DaemonThreadFactory("AA_WC_INGESTION_STORAGE_LOOKUP_" + regionName, serverConfig.getLogContext()));
    aaWcStorageLookupPools.add(aaWcStorageLookupPool);

    // 14. RemoteIngestionRepairService (dep-graph #17). Real impl is cheap.
    RemoteIngestionRepairService remoteIngestionRepairService =
        new RemoteIngestionRepairService(serverConfig.getRemoteIngestionRepairSleepInterval());
    remoteIngestionRepairServices.add(remoteIngestionRepairService);

    // 15. VeniceViewWriterFactory (dep-graph #3). Real impl, no views configured.
    VeniceConfigLoader configLoader = new VeniceConfigLoader(VeniceProperties.empty(), props);
    VeniceViewWriterFactory veniceViewWriterFactory = new VeniceViewWriterFactory(configLoader, veniceWriterFactory);

    // 16. HeartbeatMonitoringService (dep-graph #2). Mock with deep stubs.
    HeartbeatMonitoringService heartbeatMonitoringService = mock(HeartbeatMonitoringService.class, RETURNS_DEEP_STUBS);
    heartbeatMonitoringServices.add(heartbeatMonitoringService);

    // 17. Leader-follower notifiers queue (dep-graph #5). LogNotifier is OK for visibility.
    java.util.Queue<VeniceNotifier> leaderFollowerNotifiers = new ConcurrentLinkedQueue<>();
    leaderFollowerNotifiers.add(new LogNotifier());

    // 18. Build the StoreIngestionTaskFactory.
    StoreIngestionTaskFactory.Builder builder = StoreIngestionTaskFactory.builder()
        .setVeniceWriterFactory(veniceWriterFactory)
        .setHeartbeatMonitoringService(heartbeatMonitoringService)
        .setVeniceViewWriterFactory(veniceViewWriterFactory)
        .setStorageMetadataService(storageMetadataService)
        .setLeaderFollowerNotifiersQueue(leaderFollowerNotifiers)
        .setSchemaRepository(schemaRepository)
        .setMetadataRepository(storeRepository)
        .setHostLevelIngestionStats(hostLevelIngestionStats)
        .setVersionedDIVStats(versionedDIVStats)
        .setVersionedIngestionStats(versionedIngestionStats)
        .setStoreBufferService(storeBufferService)
        .setServerConfig(serverConfig)
        .setDiskUsage(diskUsage)
        .setAggKafkaConsumerService(aggKafkaConsumerService)
        .setPartitionStateSerializer(partitionStateSerializer)
        .setIsDaVinciClient(false)
        .setRemoteIngestionRepairService(remoteIngestionRepairService)
        .setMetaStoreWriter(null)
        .setCompressorFactory(compressorFactory)
        .setPubSubContext(pubSubContext)
        .setAAWCWorkLoadProcessingThreadPool(aaWcParallelPool)
        .setAAWCIngestionStorageLookupThreadPool(aaWcStorageLookupPool)
        .setReusableObjectsSupplier(serverConfig.getIngestionTaskReusableObjectsStrategy().supplier())
        .setBlobTransferManagerSupplier(() -> null)
        .setBlobTransferDisabledStores(Collections.emptySet());

    StoreIngestionTaskFactory factory = builder.build();

    // 19. Build per-task args.
    Store store = storeRepository.getStoreOrThrow(config.getStoreName());
    Version version = store.getVersionOrThrow(config.getVersionNumber());
    Properties kafkaConsumerProps = getKafkaConsumerProperties(serverConfig, broker.getAddress());

    // 20. Lazy<ZKHelixAdmin> (dep-graph note). Mock with deep stubs.
    Lazy<ZKHelixAdmin> zkHelixAdmin = Lazy.of(() -> mock(ZKHelixAdmin.class, RETURNS_DEEP_STUBS));

    // 21. Instantiate the task for partition 0.
    VeniceStoreVersionConfig vtStoreConfig = versionTopicStoreConfigs.get(regionId);
    StoreIngestionTask task = factory.getNewIngestionTask(
        storageService,
        store,
        version,
        kafkaConsumerProps,
        () -> true,
        vtStoreConfig,
        /*partitionId*/ 0,
        Optional.empty(),
        /*internalRecordTransformerConfig*/ null,
        zkHelixAdmin);
    if (!(task instanceof ActiveActiveStoreIngestionTask)) {
      throw new VeniceException(
          "Expected ActiveActiveStoreIngestionTask but got " + task.getClass().getName() + " for region " + regionName);
    }
    ingestionTaskMapForStats.put(versionTopic.getName(), task);
    ingestionTasks.add(task);
    LOGGER.info("Region {}: instantiated ActiveActiveStoreIngestionTask for partition 0", regionName);

    // 22. Build per-region RT and VT writers (used by tests, and for SOP/EOP/TopicSwitch broadcast).
    // Default writer uses byte[] key/value/update serializers; the SIT-side decoding handles the
    // KafkaKey + KME envelope construction. We don't enable setUseKafkaKeySerializer so that callers
    // can pass raw byte[] keys via #put(byte[], byte[], int).
    VeniceWriter<byte[], byte[], byte[]> vtWriter = veniceWriterFactory.createVeniceWriter(
        new VeniceWriterOptions.Builder(versionTopic.getName()).setPartitionCount(config.getPartitionCount()).build());
    vtWriters.add(vtWriter);
    VeniceWriter<byte[], byte[], byte[]> rtWriter = veniceWriterFactory.createVeniceWriter(
        new VeniceWriterOptions.Builder(realTimeTopic.getName()).setPartitionCount(config.getPartitionCount()).build());
    rtWriters.add(rtWriter);
  }

  /** Inject SOP and EOP control messages onto each region's VT topic so the partition is "ready". */
  private void injectSopEopOnAllRegions() {
    for (int i = 0; i < config.getRegionCount(); i++) {
      String regionName = config.getRegionName(i);
      VeniceWriter<byte[], byte[], byte[]> vtWriter = vtWriters.get(i);
      LOGGER.info("Region {}: broadcasting SOP/EOP onto VT {}", regionName, versionTopic.getName());
      vtWriter.broadcastStartOfPush(false, false, CompressionStrategy.NO_OP, Collections.emptyMap());
      vtWriter.broadcastEndOfPush(Collections.emptyMap());
    }
  }

  /** Start each region's ingestion task on a dedicated worker thread. */
  private void startIngestionTaskForRegion(int regionId) {
    String regionName = config.getRegionName(regionId);
    StoreIngestionTask task = ingestionTasks.get(regionId);
    Thread thread = new Thread(task, "lean-aa-sit-" + regionName);
    thread.setDaemon(true);
    thread.start();
    ingestionThreads.add(thread);
    LOGGER.info("Region {}: started ingestion task thread {}", regionName, thread.getName());
  }

  /** Subscribe each region's task to ALL partitions of the local VT. */
  private void subscribeRegionToAllVTPartitions(int regionId) {
    String regionName = config.getRegionName(regionId);
    StoreIngestionTask task = ingestionTasks.get(regionId);
    for (int partition = 0; partition < config.getPartitionCount(); partition++) {
      com.linkedin.venice.pubsub.api.PubSubTopicPartition tp =
          new com.linkedin.venice.pubsub.PubSubTopicPartitionImpl(versionTopic, partition);
      task.subscribePartition(tp, Optional.empty());
      LOGGER.info("Region {}: subscribed task to {}/{}", regionName, versionTopic.getName(), partition);
    }
  }

  /**
   * Promote the region's ingestion task to LEADER for ALL partitions. Replication factor is 1
   * (single region per cluster, single in-process broker), so each region's task is the sole leader
   * for every one of its own partitions; there are no follower replicas competing.
   */
  private void promoteRegionTaskToLeaderForAllPartitions(int regionId) {
    String regionName = config.getRegionName(regionId);
    StoreIngestionTask task = ingestionTasks.get(regionId);
    if (!(task instanceof LeaderFollowerStoreIngestionTask)) {
      throw new VeniceException(
          "Region " + regionName + " task is not a LeaderFollowerStoreIngestionTask, got " + task.getClass().getName());
    }
    for (int partition = 0; partition < config.getPartitionCount(); partition++) {
      com.linkedin.venice.pubsub.api.PubSubTopicPartition tp =
          new com.linkedin.venice.pubsub.PubSubTopicPartitionImpl(versionTopic, partition);
      // Construct a LeaderSessionIdChecker that always reports valid (single-leader test scenario,
      // no Helix-driven session-id contention).
      AtomicLong sessionIdHandle = new AtomicLong(1L);
      LeaderFollowerPartitionStateModel.LeaderSessionIdChecker checker =
          new LeaderFollowerPartitionStateModel.LeaderSessionIdChecker(
              /*leadershipTerm*/ 1L,
              /*assignedSessionId*/ 1L,
              sessionIdHandle);
      ((LeaderFollowerStoreIngestionTask) task).promoteToLeader(tp, checker);
      LOGGER.info("Region {}: promoted task to LEADER for {}/{}", regionName, versionTopic.getName(), partition);
    }
  }

  /**
   * Broadcast a {@code TopicSwitch} CM on each region's VT, with sourceServers = both regions' kafka URLs.
   * This makes the AA leader subscribe to both local AND remote RT topics, enabling cross-region replication.
   */
  private void broadcastTopicSwitchOnAllRegions() {
    List<CharSequence> allRtSourceServers = new ArrayList<>();
    for (int i = 0; i < config.getRegionCount(); i++) {
      allRtSourceServers.add(brokers.get(i).getAddress());
    }
    long rewindStartTimestamp = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(60);
    for (int i = 0; i < config.getRegionCount(); i++) {
      VeniceWriter<byte[], byte[], byte[]> vtWriter = vtWriters.get(i);
      LOGGER.info(
          "Region {}: broadcasting TopicSwitch on VT {} with sourceServers={}",
          config.getRegionName(i),
          versionTopic.getName(),
          allRtSourceServers);
      vtWriter.broadcastTopicSwitch(
          allRtSourceServers,
          realTimeTopic.getName(),
          rewindStartTimestamp,
          Collections.emptyMap());
    }
  }

  // ---------- Kafka consumer property helpers ----------

  /**
   * Build the per-region consumer properties bag for the given kafka URL. Mirrors
   * {@code KafkaStoreIngestionService#getCommonKafkaConsumerProperties}.
   */
  private Properties getCommonKafkaConsumerProperties(VeniceServerConfig serverConfig, String kafkaBootstrapUrl) {
    Properties p = serverConfig.getClusterProperties().getPropertiesCopy();
    p.setProperty(ConfigKeys.KAFKA_BOOTSTRAP_SERVERS, kafkaBootstrapUrl);
    p.setProperty(
        ConfigKeys.KAFKA_FETCH_MIN_BYTES_CONFIG,
        String.valueOf(serverConfig.getKafkaFetchMinSizePerSecond()));
    p.setProperty(
        ConfigKeys.KAFKA_FETCH_MAX_BYTES_CONFIG,
        String.valueOf(serverConfig.getKafkaFetchMaxSizePerSecond()));
    p.setProperty(ConfigKeys.KAFKA_MAX_POLL_RECORDS_CONFIG, Integer.toString(serverConfig.getKafkaMaxPollRecords()));
    p.setProperty(ConfigKeys.KAFKA_FETCH_MAX_WAIT_MS_CONFIG, String.valueOf(serverConfig.getKafkaFetchMaxTimeMS()));
    p.setProperty(
        ConfigKeys.KAFKA_MAX_PARTITION_FETCH_BYTES_CONFIG,
        String.valueOf(serverConfig.getKafkaFetchPartitionMaxSizePerSecond()));
    return p;
  }

  /** Build per-store consumer properties (for SIT.start). */
  private Properties getKafkaConsumerProperties(VeniceServerConfig serverConfig, String kafkaBootstrapUrl) {
    Properties p = getCommonKafkaConsumerProperties(serverConfig, kafkaBootstrapUrl);
    String groupId = "lean-aa-harness_" + config.getStoreName() + "_v" + config.getVersionNumber();
    p.setProperty(ConfigKeys.KAFKA_GROUP_ID_CONFIG, groupId);
    p.setProperty(ConfigKeys.KAFKA_CLIENT_ID_CONFIG, groupId);
    return p;
  }

  /** Look up VeniceProperties for a given kafka URL, used by AggKafkaConsumerService. */
  private VeniceProperties getVenicePropertiesForKafkaUrl(String kafkaUrl) {
    Properties p = new Properties();
    p.setProperty(ConfigKeys.KAFKA_BOOTSTRAP_SERVERS, kafkaUrl);
    // Pass through the broker-specific additional/mergeable configs for whichever broker matches.
    for (PubSubBrokerWrapper broker: brokers) {
      if (broker.getAddress().equals(kafkaUrl)) {
        p.putAll(broker.getAdditionalConfig());
        p.putAll(broker.getMergeableConfigs());
        break;
      }
    }
    return new VeniceProperties(p);
  }

  /** Lightweight Future placeholder — unused, kept for symmetric signature. */
  @SuppressWarnings("unused")
  private static <T> Future<T> noopFuture() {
    return null;
  }
}
