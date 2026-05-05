package com.linkedin.venice.benchmark;

import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.NUM_RECORDS_PER_INVOCATION;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.PARTIAL_UPDATE_SENTINEL_COUNT;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.VT_CHECK_SENTINEL_COUNT;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.buildCanaryRecord;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.buildPutRecord;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.buildPutSentinelRecord;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.mixedKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.partialUpdateFinalSentinelKeys;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.partialUpdatePoolPrePopulateKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.partialUpdateSentinelKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.putPoolKey;
import static com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.putSentinelKey;
import static com.linkedin.venice.utils.TestUtils.waitForNonDeterministicAssertion;

import com.linkedin.davinci.kafka.consumer.PartitionConsumptionState;
import com.linkedin.davinci.kafka.consumer.StoreIngestionTask;
import com.linkedin.davinci.store.StorageEngine;
import com.linkedin.davinci.store.rocksdb.RocksDBStoragePartition;
import com.linkedin.davinci.store.rocksdb.merge.ConcatBlobParser;
import com.linkedin.venice.benchmark.AAIngestionWorkloadHelper.WorkloadType;
import com.linkedin.venice.benchmark.lean.MinimalAAIngestionHarness;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.partitioner.DefaultVenicePartitioner;
import com.linkedin.venice.partitioner.VenicePartitioner;
import com.linkedin.venice.pubsub.api.PubSubPosition;
import com.linkedin.venice.serializer.AvroSerializer;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.VeniceWriter;
import com.linkedin.venice.writer.update.UpdateBuilder;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

  /**
   * VT-merge experiment design mode. {@code BASELINE} runs the existing AA partial-update path
   * (RMW at the leader, full-record PUT to VT). {@code MERGE_OPERAND} and {@code MERGE_OPERAND_SWEPT}
   * both flip {@code server.vt.update.operand.enabled=true} so the leader skips RMW and produces
   * UPDATE operand bytes directly to VT, where the follower routes them to
   * {@code storageEngine.merge()}.
   *
   * <p>The two MERGE modes are kept distinct for benchmark continuity (the historical
   * {@code MERGE_OPERAND_SWEPT} mode previously enabled a separate Phase-2 sweeper, now retired
   * in favor of the Phase B chain-length backstop). They are functionally equivalent today;
   * future divergence is reserved for follow-up experiments.
   *
   * <p>The flag is propagated to every region's {@code VeniceServerConfig} via JVM system
   * property (see {@code VeniceServerConfig} which falls back to
   * {@code venice.server.vt.update.operand.enabled} when not in {@code VeniceProperties}). The
   * JMH harness sets the sysprop in {@code @Setup(Level.Trial)} before constructing the harness.
   *
   * <p>See {@code autoresearch/vt-merge-compaction-fold/GOAL.md} for the current design.
   */
  public enum DesignMode {
    BASELINE, MERGE_OPERAND, MERGE_OPERAND_SWEPT
  }

  @Param({ "BASELINE" })
  private DesignMode designMode;

  /**
   * Producer-side back-pressure threshold (records). The producer blocks when
   * {@code producedCount - lastSeenStorageCount > maxBacklog}. Tunable via JMH
   * {@code -p maxBacklog=N}. See {@code autoresearch/jmh-backpressure/GOAL.md} §2 Approach 1.
   */
  @Param({ "100000" })
  private long maxBacklog;

  /**
   * Workload-shape parameters (PARTIAL_UPDATE only). Defaults reproduce the historical workload
   * (100K keys × ~1.6 KB record × ~16 B per-update payload) so prior benchmark results stay
   * reproducible. Override via {@code -p partialUpdateKeyPoolSize=N -p recordSizeBytes=N
   * -p fieldUpdateSizeBytes=N} to scale the workload (e.g., {@code -p partialUpdateKeyPoolSize=50000
   * -p recordSizeBytes=102400 -p fieldUpdateSizeBytes=1024} for the 100 KB record / 1 KB update
   * regime).
   *
   * <p>{@code tagsMapSize} is derived as {@code max(1, recordSizeBytes / fieldUpdateSizeBytes)}.
   * The {@code tags} map size is what gives the record its bulk; per-update payload size is
   * applied to both the {@code AddToMap} branch and the {@code setName} branch so per-update
   * bytes track {@code fieldUpdateSizeBytes} on average. The {@code setAge} branch always writes
   * a small int regardless of the param.
   */
  @Param({ "100000" })
  private int partialUpdateKeyPoolSize;

  @Param({ "1600" })
  private int recordSizeBytes;

  @Param({ "16" })
  private int fieldUpdateSizeBytes;

  // Derived in @Setup(Level.Trial) from recordSizeBytes / fieldUpdateSizeBytes.
  private int derivedTagsMapSize;

  // Pre-computed in @Setup(Level.Trial); the per-update payload prefix is concatenated with
  // a sequence number so each write produces unique bytes.
  private String derivedTagValuePadding;

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
  // Per-region ingestion tasks (used by the back-pressure poller to read consumer-side VT progress).
  private StoreIngestionTask sitDC0;
  private StoreIngestionTask sitDC1;

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

  // ---------------- Back-pressure (Option A: lag-bounded gate) ----------------
  // GOAL: autoresearch/jmh-backpressure/GOAL.md §2 Approach 1
  /** Records produced this iteration (incremented by the workload methods). */
  private final AtomicLong producedCount = new AtomicLong(0);
  /**
   * Slowest-region VT-progress delta from this iteration's baseline, updated by the
   * background poller. This is min(DC0 VT offset, DC1 VT offset) - baseline. Using min
   * means the gate respects the slower follower; both regions must keep up before the
   * producer is allowed to advance further.
   */
  private volatile long lastSeenStorageCount = 0L;
  private volatile long iterationStorageBaseline = 0L;
  private ScheduledExecutorService backpressurePoller;
  /**
   * Diagnostic counter: total milliseconds spent in {@link #waitForBackpressure()} during the
   * current iteration. Reported in {@code [E2E]} log line for visibility into how much the
   * producer is being throttled.
   */
  private final AtomicLong backpressureWaitNanos = new AtomicLong(0);

  // ---------------- RocksDB internal stats forensics ----------------
  // GOAL: discriminate compaction churn (H1) vs operand-chain growth (H2). See
  // autoresearch/jmh-backpressure/phase-C-rocksdb-NOTES.md.
  /** Monotonic iteration counter (1-based, including warmup). Bumped at iteration start. */
  private final AtomicLong iterationOrdinal = new AtomicLong(0);
  /** Wall-clock start of the current iteration (used for relative timestamps in the timeline TSV). */
  private volatile long timelineIterStartNanos;
  /** Per-iteration max running-compactions seen so far (across both regions, summed). */
  private final AtomicLong maxRunningCompactionsThisIter = new AtomicLong(0);
  /** Last sampled estimate-num-keys (DC0+DC1, summed across partitions) at end of iteration. */
  private volatile long lastEstimateKeysDc0 = 0L;
  private volatile long lastEstimateKeysDc1 = 0L;
  /** Periodic compaction/key-count poller (separate from back-pressure poller). */
  private ScheduledExecutorService rocksdbStatsPoller;
  /** Per-iteration timeline writer; rotated at iteration start. */
  private volatile java.io.PrintWriter timelineWriter;
  private static final String ROCKSDB_TIMELINE_DIR = "/tmp/jmh-rocksdb-timeline";
  private static final String ROCKSDB_CFSTATS_DIR = "/tmp/jmh-rocksdb-cfstats";
  /** Number of keys to sample for operand-count distribution at end of each iteration. */
  private static final int OPERAND_SAMPLE_SIZE = 200;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    Utils.thisIsLocalhost();
    long setupStartNanos = System.nanoTime();

    // VT-merge experiment: propagate the design mode to the server-side flag via JVM system
    // property. VeniceServerConfig consults this property as the default when the property is not
    // present in VeniceProperties — see VeniceServerConfig#~line 1204.
    boolean vtUpdateOperand = (designMode == DesignMode.MERGE_OPERAND || designMode == DesignMode.MERGE_OPERAND_SWEPT);
    System.setProperty("venice.server.vt.update.operand.enabled", Boolean.toString(vtUpdateOperand));
    System.err.println(
        "[LeanActiveActiveIngestionBenchmark] designMode=" + designMode + " (vtUpdateOperand=" + vtUpdateOperand + ")");

    storeName = Utils.getUniqueString("lean-aa-benchmark-store");
    MinimalAAIngestionHarness.Config config =
        new MinimalAAIngestionHarness.Config(REGION_COUNT, PARTITION_COUNT, storeName);
    harness = new MinimalAAIngestionHarness(config);

    long harnessStartNanos = System.nanoTime();
    harness.start();
    long harnessStartElapsedMs = (System.nanoTime() - harnessStartNanos) / 1_000_000L;
    System.err.println("[LeanActiveActiveIngestionBenchmark] harness.start() took " + harnessStartElapsedMs + " ms");

    versionTopicName = Version.composeKafkaTopic(storeName, VERSION_NUMBER);

    // Schemas come from the harness's in-memory schema repo (which is pre-loaded with the same
    // BenchmarkRecord value schema + RMD + WC schemas as the full benchmark).
    valueSchema = harness.getSchemaRepository().getValueSchema(storeName, VALUE_SCHEMA_ID).getSchema();
    writeComputeSchema = harness.getSchemaRepository()
        .getDerivedSchema(storeName, VALUE_SCHEMA_ID, WRITE_COMPUTE_DERIVED_SCHEMA_ID)
        .getSchema();

    keySerializer = new AvroSerializer<>(Schema.create(Schema.Type.STRING));
    valueSerializer = new AvroSerializer<>(valueSchema);
    writeComputeSerializer = new AvroSerializer<>(writeComputeSchema);
    partitioner = new DefaultVenicePartitioner();

    // Workload-shape derivation. tagsMapSize × per-tag-value-size dominates the record bulk;
    // padding is precomputed so the per-write hot path only does a single string concat.
    derivedTagsMapSize = Math.max(1, recordSizeBytes / Math.max(1, fieldUpdateSizeBytes));
    derivedTagValuePadding = AAIngestionWorkloadHelper.makeTagValuePadding(fieldUpdateSizeBytes);

    writerDC0 = harness.getVeniceWriterForRTTopic(0);
    writerDC1 = harness.getVeniceWriterForRTTopic(1);
    engineDC0 = harness.getStorageEngineForRegion(0);
    engineDC1 = harness.getStorageEngineForRegion(1);
    sitDC0 = harness.getIngestionTaskForRegion(0);
    sitDC1 = harness.getIngestionTaskForRegion(1);

    // Back-pressure poller: every 100ms, sum each region's per-partition latest-processed VT
    // offset, take the slower-region min, store as lastSeenStorageCount. The producer hot loop
    // reads this volatile value to gate advancement. See GOAL.md §2 Approach 1.
    backpressurePoller = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "jmh-backpressure-poller");
      t.setDaemon(true);
      return t;
    });
    backpressurePoller.scheduleAtFixedRate(this::updateLastSeenStorageCount, 100, 100, TimeUnit.MILLISECONDS);

    // RocksDB stats poller: every 1000ms, sample num-running-compactions + estimate-num-keys for
    // both regions and stream to a per-iteration TSV. Separate from the back-pressure poller so
    // their tick rates and failure modes don't conflate. See phase-C-rocksdb-NOTES.md.
    new java.io.File(ROCKSDB_TIMELINE_DIR).mkdirs();
    new java.io.File(ROCKSDB_CFSTATS_DIR).mkdirs();
    rocksdbStatsPoller = Executors.newSingleThreadScheduledExecutor(r -> {
      Thread t = new Thread(r, "jmh-rocksdb-stats-poller");
      t.setDaemon(true);
      return t;
    });
    rocksdbStatsPoller.scheduleAtFixedRate(this::sampleRocksDBStats, 1000, 1000, TimeUnit.MILLISECONDS);

    // Verify the pipeline is working with a canary record. This is the lean-harness equivalent of
    // the full benchmark's "wait for canary visible on router" step, but uses RocksDB directly.
    GenericRecord canary = buildCanaryRecord(valueSchema);
    String canaryKey = "canary-key";
    sendPut(writerDC0, canaryKey, canary);
    waitForKeysVisibleOnBothRegions(new String[] { canaryKey }, 60, TimeUnit.SECONDS);

    // For PARTIAL_UPDATE, pre-populate each bounded-pool key with a derivedTagsMapSize-entry tags map.
    if (workloadType == WorkloadType.PARTIAL_UPDATE) {
      prePopulatePartialUpdatePool();
    }

    // JMH benchmark relies on System.exit to finish one round of benchmark run, otherwise it will
    // hang there.
    TestUtils.restoreSystemExit();

    long setupElapsedMs = (System.nanoTime() - setupStartNanos) / 1_000_000L;
    System.err.println("[LeanActiveActiveIngestionBenchmark] setUp() complete in " + setupElapsedMs + " ms");
  }

  private void prePopulatePartialUpdatePool() throws Exception {
    System.err.println(
        "[LeanActiveActiveIngestionBenchmark] Pre-populating " + partialUpdateKeyPoolSize + " pool keys with "
            + derivedTagsMapSize + "-entry tags map...");
    for (int poolIdx = 0; poolIdx < partialUpdateKeyPoolSize; poolIdx++) {
      String key = partialUpdatePoolPrePopulateKey(poolIdx);
      GenericRecord rec = AAIngestionWorkloadHelper
          .buildPartialUpdatePoolInitRecord(valueSchema, poolIdx, derivedTagsMapSize, derivedTagValuePadding);
      sendPutWithoutFlush(writerDC0, key, rec);
    }
    writerDC0.flush();

    int[] checkIndices = AAIngestionWorkloadHelper.partialUpdatePoolCheckIndices(partialUpdateKeyPoolSize);
    String[] checkKeys = new String[checkIndices.length];
    for (int i = 0; i < checkIndices.length; i++) {
      checkKeys[i] = partialUpdatePoolPrePopulateKey(checkIndices[i]);
    }
    waitForKeysVisibleOnBothRegions(checkKeys, 5, TimeUnit.MINUTES);
    System.err.println("[LeanActiveActiveIngestionBenchmark] Pre-population complete.");
  }

  @TearDown(Level.Trial)
  public void cleanUp() {
    // Stop the back-pressure poller first so it doesn't race with harness teardown.
    if (backpressurePoller != null) {
      try {
        backpressurePoller.shutdownNow();
        backpressurePoller.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    if (rocksdbStatsPoller != null) {
      try {
        rocksdbStatsPoller.shutdownNow();
        rocksdbStatsPoller.awaitTermination(2, TimeUnit.SECONDS);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
    }
    if (timelineWriter != null) {
      try {
        timelineWriter.close();
      } catch (Throwable ignored) {
      }
      timelineWriter = null;
    }
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
    // Capture storage size + a quick read-latency probe BEFORE harness.stop() deletes temp dirs.
    if (harness != null) {
      try {
        captureStorageMetrics();
      } catch (Throwable t) {
        System.err.println("[STORAGE] Capture failed: " + t);
        t.printStackTrace(System.err);
      }
      try {
        captureReadLatencyMetrics();
      } catch (Throwable t) {
        System.err.println("[READ-LAT] Capture failed: " + t);
        t.printStackTrace(System.err);
      }
      try {
        verifyReadCorrectness();
      } catch (Throwable t) {
        System.err.println("[READ-VERIFY] Capture failed: " + t);
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
   * Walk both region RocksDB dirs and emit total bytes-on-disk per region. Captured before
   * {@code harness.stop()} since stop() deletes the temp dirs.
   */
  private void captureStorageMetrics() {
    for (int regionId = 0; regionId < REGION_COUNT; regionId++) {
      java.io.File regionRoot = harness.getRegionTempDir(regionId);
      java.io.File rocksdbRoot = new java.io.File(regionRoot, "rocksdb");
      long bytes = directorySize(rocksdbRoot);
      System.err.println(
          String.format(
              "[STORAGE] region=dc-%d rocksdb_bytes=%d rocksdb_path=%s",
              regionId,
              bytes,
              rocksdbRoot.getAbsolutePath()));
    }
  }

  private static long directorySize(java.io.File path) {
    if (path == null || !path.exists()) {
      return 0L;
    }
    if (path.isFile()) {
      return path.length();
    }
    long total = 0L;
    java.io.File[] children = path.listFiles();
    if (children == null) {
      return 0L;
    }
    for (java.io.File child: children) {
      total += directorySize(child);
    }
    return total;
  }

  /**
   * Probe read latency on a sample of "hot" keys (the partial-update pool keys). Emits p50/p99
   * from a single-thread tight loop of {@code engine.get(partition, key)} calls. Captures both
   * regions for symmetry. Sample size is bounded so this doesn't stretch teardown by minutes.
   */
  private void captureReadLatencyMetrics() {
    captureReadLatencyMetrics("trial-end");
  }

  private void captureReadLatencyMetrics(String context) {
    if (workloadType != WorkloadType.PARTIAL_UPDATE) {
      return;
    }
    final int sampleCount = 5_000;
    final int strideOverPool = Math.max(1, partialUpdateKeyPoolSize / sampleCount);
    long[] dc0Nanos = new long[sampleCount];
    long[] dc1Nanos = new long[sampleCount];
    int captured = 0;
    for (int i = 0; i < sampleCount && i * strideOverPool < partialUpdateKeyPoolSize; i++) {
      String key = partialUpdatePoolPrePopulateKey(i * strideOverPool);
      byte[] keyBytes = keySerializer.serialize(key);
      int partition = partitioner.getPartitionId(keyBytes, PARTITION_COUNT);
      long t0 = System.nanoTime();
      byte[] dc0 = engineDC0.get(partition, keyBytes);
      long t1 = System.nanoTime();
      byte[] dc1 = engineDC1.get(partition, keyBytes);
      long t2 = System.nanoTime();
      if (dc0 != null && dc1 != null) {
        dc0Nanos[captured] = t1 - t0;
        dc1Nanos[captured] = t2 - t1;
        captured++;
      }
    }
    if (captured == 0) {
      System.err.println("[READ-LAT] No hot keys returned non-null; aborting probe.");
      return;
    }
    long[] dc0Trim = java.util.Arrays.copyOf(dc0Nanos, captured);
    long[] dc1Trim = java.util.Arrays.copyOf(dc1Nanos, captured);
    java.util.Arrays.sort(dc0Trim);
    java.util.Arrays.sort(dc1Trim);
    long dc0p50 = dc0Trim[(int) (captured * 0.50)];
    long dc0p99 = dc0Trim[(int) (captured * 0.99)];
    long dc1p50 = dc1Trim[(int) (captured * 0.50)];
    long dc1p99 = dc1Trim[(int) (captured * 0.99)];
    System.err.println(
        String.format(
            "[READ-LAT] context=%s samples=%d dc0_p50_ns=%d dc0_p99_ns=%d dc1_p50_ns=%d dc1_p99_ns=%d",
            context,
            captured,
            dc0p50,
            dc0p99,
            dc1p50,
            dc1p99));
  }

  /**
   * Read-correctness check: pick a sample of pool keys, fetch from both regions' storage engines,
   * Avro-decode the returned bytes against the value schema, and assert invariants that hold
   * regardless of which random workload operation hit a given key.
   *
   * <p>The workload {@link #runPartialUpdateWorkload} picks one of 3 ops per record (set name /
   * set age / addToMap on tags). The fields that change are workload-dependent and key-specific.
   * The only invariants that hold across ALL keys are:
   * <ul>
   *   <li><b>Decode succeeds</b> — bytes are valid Avro per the value schema.</li>
   *   <li><b>{@code score} == 0.0</b> — never modified by the partial-update workload, set to
   *       0.0 by {@code buildPartialUpdatePoolInitRecord} during pre-populate.</li>
   *   <li><b>{@code tags} is a non-null Map of size {@code derivedTagsMapSize}</b> — workload uses
   *       bounded map keys 0..derivedTagsMapSize-1, so AddToMap operations overwrite existing
   *       entries rather than growing the map.</li>
   * </ul>
   *
   * <p>Reports counts of decode failures and invariant violations per region. This catches bugs
   * the VT consistency check cannot — e.g., the materializing fold producing un-decodable bytes,
   * the read path returning wrong-shaped bytes, a missing/wrong schemaId prefix, or fields lost
   * during the operand fold. {@code VT-CHECK} verifies cross-region byte agreement; this
   * verifies application-level correctness.
   */
  private void verifyReadCorrectness() {
    if (workloadType != WorkloadType.PARTIAL_UPDATE) {
      return;
    }
    final int sampleCount = 1_000;
    final int strideOverPool = Math.max(1, partialUpdateKeyPoolSize / sampleCount);
    org.apache.avro.io.DatumReader<org.apache.avro.generic.GenericRecord> reader =
        new org.apache.avro.generic.GenericDatumReader<>(valueSchema);

    int dc0Decoded = 0, dc0DecodeFailures = 0, dc0InvariantViolations = 0, dc0Null = 0;
    int dc1Decoded = 0, dc1DecodeFailures = 0, dc1InvariantViolations = 0, dc1Null = 0;
    String firstViolationSample = null;

    for (int i = 0; i < sampleCount && i * strideOverPool < partialUpdateKeyPoolSize; i++) {
      int poolIdx = i * strideOverPool;
      String key = partialUpdatePoolPrePopulateKey(poolIdx);
      byte[] keyBytes = keySerializer.serialize(key);
      int partition = partitioner.getPartitionId(keyBytes, PARTITION_COUNT);

      String dc0Outcome = decodeAndCheck(reader, engineDC0.get(partition, keyBytes));
      String dc1Outcome = decodeAndCheck(reader, engineDC1.get(partition, keyBytes));

      switch (dc0Outcome) {
        case "OK":
          dc0Decoded++;
          break;
        case "NULL":
          dc0Null++;
          break;
        case "DECODE_FAIL":
          dc0DecodeFailures++;
          break;
        default:
          dc0InvariantViolations++;
          if (firstViolationSample == null)
            firstViolationSample = "dc0 key=" + key + ": " + dc0Outcome;
      }
      switch (dc1Outcome) {
        case "OK":
          dc1Decoded++;
          break;
        case "NULL":
          dc1Null++;
          break;
        case "DECODE_FAIL":
          dc1DecodeFailures++;
          break;
        default:
          dc1InvariantViolations++;
          if (firstViolationSample == null)
            firstViolationSample = "dc1 key=" + key + ": " + dc1Outcome;
      }
    }

    System.err.println(
        String.format(
            "[READ-VERIFY] sampled=%d  dc0: ok=%d null=%d decodeFail=%d invariantViolation=%d  "
                + "dc1: ok=%d null=%d decodeFail=%d invariantViolation=%d  firstViolation=%s",
            dc0Decoded + dc0Null + dc0DecodeFailures + dc0InvariantViolations,
            dc0Decoded,
            dc0Null,
            dc0DecodeFailures,
            dc0InvariantViolations,
            dc1Decoded,
            dc1Null,
            dc1DecodeFailures,
            dc1InvariantViolations,
            firstViolationSample == null ? "none" : firstViolationSample));
  }

  /**
   * Decode {@code raw} and check workload-independent invariants:
   * {@code score == 0.0} (never modified) and {@code tags.size() == derivedTagsMapSize} (bounded).
   * Returns "OK" on success, "NULL" if input is null, "DECODE_FAIL: ..." if Avro decode throws,
   * or a specific invariant-violation description.
   */
  private String decodeAndCheck(
      org.apache.avro.io.DatumReader<org.apache.avro.generic.GenericRecord> reader,
      byte[] raw) {
    if (raw == null) {
      return "NULL";
    }
    // raw bytes from engine.get are [schemaId : 4B BE][avro-bytes]. Strip the schemaId prefix.
    if (raw.length < 4) {
      return "DECODE_FAIL: raw too short " + raw.length;
    }
    org.apache.avro.generic.GenericRecord rec;
    try {
      org.apache.avro.io.BinaryDecoder decoder =
          org.apache.avro.io.DecoderFactory.get().binaryDecoder(raw, 4, raw.length - 4, null);
      rec = reader.read(null, decoder);
    } catch (Throwable t) {
      return "DECODE_FAIL: " + t.getClass().getSimpleName() + ":" + t.getMessage();
    }
    if (rec == null) {
      return "DECODE_FAIL: null record";
    }
    Object score = rec.get("score");
    if (!(score instanceof Double) || ((Double) score) != 0.0) {
      return "score invariant violated: expected=0.0 got=" + score;
    }
    Object tags = rec.get("tags");
    if (!(tags instanceof java.util.Map)) {
      return "tags wrong type: " + (tags == null ? "null" : tags.getClass().getName());
    }
    int tagsSize = ((java.util.Map<?, ?>) tags).size();
    if (tagsSize != derivedTagsMapSize) {
      return "tags size invariant violated: expected=" + derivedTagsMapSize + " got=" + tagsSize;
    }
    return "OK";
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
      jobProps
          .setProperty(com.linkedin.venice.spark.consistency.VTConsistencyCheckerJob.VERSION_TOPIC, versionTopicName);
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
    // Reset back-pressure counters for this iteration. Establish the storage baseline as the
    // current consumer-side VT progress so producedCount and lastSeenStorageCount measure deltas
    // within this iteration only.
    producedCount.set(0);
    backpressureWaitNanos.set(0);
    iterationStorageBaseline = readSlowerRegionVtOffsetSum();
    lastSeenStorageCount = 0L;

    // Rotate per-iteration RocksDB-stats timeline file.
    long ordinal = iterationOrdinal.incrementAndGet();
    timelineIterStartNanos = iterationStartNanos;
    maxRunningCompactionsThisIter.set(0);
    if (timelineWriter != null) {
      try {
        timelineWriter.close();
      } catch (Throwable ignored) {
      }
      timelineWriter = null;
    }
    try {
      java.io.File timelineFile =
          new java.io.File(ROCKSDB_TIMELINE_DIR, String.format("iter-%d-%s.tsv", ordinal, designMode));
      java.io.PrintWriter pw =
          new java.io.PrintWriter(new java.io.BufferedWriter(new java.io.FileWriter(timelineFile)));
      pw.println(
          "t_ms_since_iter_start\tdc0_running_compactions\tdc1_running_compactions\tdc0_estimate_keys\tdc1_estimate_keys");
      pw.flush();
      timelineWriter = pw;
    } catch (Throwable t) {
      System.err.println("[ROCKSDB-STATS] Failed to open timeline file for iter " + ordinal + ": " + t);
    }
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
    long bpWaitMs = backpressureWaitNanos.get() / 1_000_000L;
    long produced = producedCount.get();
    long consumed = lastSeenStorageCount;

    // ----- RocksDB forensics: dump cfstats + sample operand-count distribution. -----
    long ordinal = iterationOrdinal.get();
    CfStatsSummary dc0Cf = dumpCfStatsToFile(engineDC0, 0, ordinal);
    CfStatsSummary dc1Cf = dumpCfStatsToFile(engineDC1, 1, ordinal);
    long maxRunComp = maxRunningCompactionsThisIter.get();
    long estKeysDc0 = lastEstimateKeysDc0;
    long estKeysDc1 = lastEstimateKeysDc1;
    OperandStats opStats = sampleOperandCountDistribution();
    if (timelineWriter != null) {
      try {
        timelineWriter.flush();
      } catch (Throwable ignored) {
      }
    }

    System.err.println(
        String.format(
            "[E2E] workload=%s records=%d elapsed_ms=%d e2e_throughput_ops_per_sec=%.2f"
                + " bp_wait_ms=%d bp_produced=%d bp_consumed_delta=%d bp_lag_at_end=%d max_backlog=%d",
            workloadType,
            records,
            elapsedNanos / 1_000_000L,
            opsPerSec,
            bpWaitMs,
            produced,
            consumed,
            produced - consumed,
            maxBacklog));
    System.err.println(
        String.format(
            "[E2E-ROCKSDB] iter=%d designMode=%s dc0_writeAmp=%.2f dc1_writeAmp=%.2f"
                + " dc0_stallCount=%d dc1_stallCount=%d dc0_pendingComp=%d dc1_pendingComp=%d"
                + " maxRunningCompactionsThisIter=%d estimateKeys_dc0=%d estimateKeys_dc1=%d"
                + " operandCount_n=%d min=%d p50=%d p90=%d p99=%d max=%d mean=%.2f",
            ordinal,
            designMode,
            dc0Cf.writeAmp,
            dc1Cf.writeAmp,
            dc0Cf.stallCount,
            dc1Cf.stallCount,
            dc0Cf.pendingCompactions,
            dc1Cf.pendingCompactions,
            maxRunComp,
            estKeysDc0,
            estKeysDc1,
            opStats.n,
            opStats.min,
            opStats.p50,
            opStats.p90,
            opStats.p99,
            opStats.max,
            opStats.mean));

    // Per-iter read-latency probe — captures p50/p99 at end of measurement iter, when chain
    // depth distribution is at its working-state worst (before next iter's setup runs).
    captureReadLatencyMetrics("iter-end-" + ordinal);
  }

  /**
   * Iterate every partition on the given engine, fetch {@code rocksdb.cfstats} as a string, write
   * to a per-iteration file, and parse out three summary numbers.
   */
  private CfStatsSummary dumpCfStatsToFile(StorageEngine engine, int regionId, long iter) {
    CfStatsSummary summary = new CfStatsSummary();
    java.io.File outFile = new java.io.File(ROCKSDB_CFSTATS_DIR, String.format("iter-%d-dc%d.txt", iter, regionId));
    StringBuilder all = new StringBuilder();
    for (int p = 0; p < PARTITION_COUNT; p++) {
      try {
        Object part = engine.getPartitionOrThrow(p);
        if (part instanceof RocksDBStoragePartition) {
          RocksDBStoragePartition rp = (RocksDBStoragePartition) part;
          String cfstats = rp.getRocksDBStringProperty("rocksdb.cfstats");
          all.append("=== region=dc")
              .append(regionId)
              .append(" partition=")
              .append(p)
              .append(" iter=")
              .append(iter)
              .append(" ===\n");
          if (cfstats != null) {
            all.append(cfstats);
            parseCfStatsAccumulating(cfstats, summary);
          } else {
            all.append("(null)\n");
          }
          all.append("\n");
        } else {
          all.append("=== region=dc")
              .append(regionId)
              .append(" partition=")
              .append(p)
              .append(" not RocksDBStoragePartition: ")
              .append(part == null ? "null" : part.getClass().getName())
              .append(" ===\n\n");
        }
      } catch (Throwable t) {
        all.append("=== region=dc")
            .append(regionId)
            .append(" partition=")
            .append(p)
            .append(" cfstats threw: ")
            .append(t)
            .append(" ===\n\n");
      }
    }
    try {
      java.nio.file.Files.write(outFile.toPath(), all.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    } catch (Throwable t) {
      System.err.println("[ROCKSDB-STATS] Failed to write cfstats file " + outFile + ": " + t);
    }
    return summary;
  }

  /** Parsed-summary accumulator for cfstats text. Numbers are summed across partitions. */
  private static final class CfStatsSummary {
    /** Latest "Sum" row's W-Amp column (write amplification). Across-partition: max wins. */
    double writeAmp = 0.0;
    /**
     * Sum of all "Write Stall (count): ..." individual category counts (delays + stops
     * across cf-l0, l0, memtable, pending-compaction-bytes), excluding the redundant
     * total-delays/total-stops aggregates.
     */
    long stallCount = 0L;
    /** rocksdb.cfstats's "pending compactions" line, summed. */
    long pendingCompactions = 0L;
  }

  /**
   * Best-effort cfstats-text parser. RocksDB's cfstats text has a "Compaction Stats" table where
   * the last row begins with "Sum" and contains a W-Amp column. Below it, a "Stalls(count):" line
   * lists various stall categories with their counts. We pull:
   *   - Sum row's W-Amp value
   *   - Stalls(count): ... write-amp count (a count, NOT micros — the property name is the only
   *     stall metric easily available from cfstats. We treat the count as the "stall signal"; if a
   *     "stall-micros" line exists we prefer that.)
   *   - "Pending Compaction Bytes" / "pending-compactions" value if present.
   * The exact text format depends on RocksDB version; this parser is permissive and accumulates
   * what it can find.
   */
  private static void parseCfStatsAccumulating(String text, CfStatsSummary out) {
    if (text == null) {
      return;
    }
    java.util.regex.Matcher sumMatcher = java.util.regex.Pattern.compile("(?m)^\\s*Sum\\s+.*$").matcher(text);
    while (sumMatcher.find()) {
      String row = sumMatcher.group();
      String[] cols = row.trim().split("\\s+");
      // Try heuristic: W-Amp column is around index ~10 in default layout. Pick the first numeric
      // value > 0 and < 100 found scanning right-to-left from middle that *could* be a write-amp.
      // To be more robust: locate "W-Amp" header location by row above; but this iterates over the
      // text without context. We'll pick the value at index 11 if present and parseable, else any
      // double-looking column.
      if (cols.length > 11) {
        try {
          double w = Double.parseDouble(cols[11]);
          if (w > out.writeAmp) {
            out.writeAmp = w;
          }
        } catch (NumberFormatException ignored) {
          // fall through to scan
        }
      }
    }
    // Pending compactions: match "Estimated pending compaction bytes: N" (RocksDB cfstats format).
    java.util.regex.Matcher pcm =
        java.util.regex.Pattern.compile("(?i)Estimated pending compaction bytes:?\\s*([0-9]+)").matcher(text);
    while (pcm.find()) {
      try {
        out.pendingCompactions += Long.parseLong(pcm.group(1));
      } catch (NumberFormatException ignored) {
      }
    }
    // Stalls: RocksDB cfstats line is
    // "Write Stall (count): foo: 0, bar: 1, ..., total-delays: 0, total-stops: 0"
    // We sum every "X: N" pair in that line — but skip "total-delays" / "total-stops" since they
    // are aggregates over the other entries (would double-count). Cleanest: extract the entire
    // "Write Stall (count):" line then sum the components except the "total-*" ones.
    java.util.regex.Matcher stallLine =
        java.util.regex.Pattern.compile("(?m)^Write Stall \\(count\\):\\s*(.*)$").matcher(text);
    while (stallLine.find()) {
      String body = stallLine.group(1);
      // Pull "name: N" pairs.
      java.util.regex.Matcher kv = java.util.regex.Pattern.compile("([a-zA-Z0-9_-]+)\\s*:\\s*([0-9]+)").matcher(body);
      while (kv.find()) {
        String name = kv.group(1);
        if (name.equals("total-delays") || name.equals("total-stops")) {
          continue; // these are aggregates of the others
        }
        try {
          out.stallCount += Long.parseLong(kv.group(2));
        } catch (NumberFormatException ignored) {
        }
      }
    }
    // Try the "Cumulative stall:" line if present (older format), summing micros.
    java.util.regex.Matcher cum =
        java.util.regex.Pattern.compile("(?i)Cumulative stall:\\s*([0-9]+):([0-9]+):([0-9]+(?:\\.[0-9]+)?)")
            .matcher(text);
    if (cum.find()) {
      try {
        long h = Long.parseLong(cum.group(1));
        long m = Long.parseLong(cum.group(2));
        double s = Double.parseDouble(cum.group(3));
        long micros = (long) ((h * 3600 + m * 60 + s) * 1_000_000.0);
        out.stallCount += micros;
      } catch (NumberFormatException ignored) {
      }
    }
  }

  /** Result of {@link #sampleOperandCountDistribution()}. */
  private static final class OperandStats {
    int n = 0;
    int min = 0;
    int max = 0;
    int p50 = 0;
    int p90 = 0;
    int p99 = 0;
    double mean = 0.0;
  }

  /**
   * Sample {@link #OPERAND_SAMPLE_SIZE} keys from the partial-update key pool. For each, fetch the
   * raw on-disk blob from DC0 — bypassing the merge-operator fold via the materializing
   * partition's {@code getRaw(byte[])} method (called reflectively because there are two such
   * classes — one per parent: {@code MaterializingRocksDBStoragePartition} and
   * {@code MaterializingReplicationMetadataRocksDBStoragePartition} — with no shared interface).
   * Parse with {@link ConcatBlobParser#parse(byte[])} to count operands. Compute distribution.
   *
   * <p>This is the H2 (operand-chain growth) signal: if per-key operand counts shift right
   * monotonically across iterations, the merge-operator's fold-on-read is doing more work per
   * read each iteration. If the distribution is flat near 0 (Phase B chain-length backstop
   * keeping it pruned), H2 is ruled out and the degradation must come from H1 (compaction churn).
   */
  private OperandStats sampleOperandCountDistribution() {
    OperandStats stats = new OperandStats();
    if (workloadType != WorkloadType.PARTIAL_UPDATE) {
      return stats;
    }
    if (designMode == DesignMode.BASELINE) {
      // BASELINE has no operand chains — engine path is full-record PUT. Skip sampling.
      return stats;
    }
    int[] counts = new int[OPERAND_SAMPLE_SIZE];
    int captured = 0;
    int decodeFailures = 0;
    int nullBlobs = 0;
    int notMaterializing = 0;
    int stride = Math.max(1, partialUpdateKeyPoolSize / OPERAND_SAMPLE_SIZE);
    String firstError = null;
    for (int i = 0; i < OPERAND_SAMPLE_SIZE && i * stride < partialUpdateKeyPoolSize; i++) {
      int poolIdx = i * stride;
      String key = partialUpdatePoolPrePopulateKey(poolIdx);
      byte[] keyBytes = keySerializer.serialize(key);
      int partition = partitioner.getPartitionId(keyBytes, PARTITION_COUNT);
      try {
        Object part = engineDC0.getPartitionOrThrow(partition);
        // Both MaterializingRocksDBStoragePartition and
        // MaterializingReplicationMetadataRocksDBStoragePartition expose a public byte[] getRaw(
        // byte[]) method that bypasses the merge-operator fold, returning the on-disk concat blob.
        // They have no shared interface, so duck-type via reflection.
        byte[] blob;
        try {
          java.lang.reflect.Method m = part.getClass().getMethod("getRaw", byte[].class);
          blob = (byte[]) m.invoke(part, (Object) keyBytes);
        } catch (NoSuchMethodException nsme) {
          notMaterializing++;
          continue;
        }
        if (blob == null) {
          nullBlobs++;
          continue;
        }
        ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(blob);
        int n = parsed.getOperands() == null ? 0 : parsed.getOperands().size();
        counts[captured++] = n;
      } catch (Throwable t) {
        decodeFailures++;
        if (firstError == null) {
          firstError = t.getClass().getSimpleName() + ":" + t.getMessage();
        }
      }
    }
    if (decodeFailures > 0 || nullBlobs > 0 || notMaterializing > 0) {
      System.err.println(
          String.format(
              "[OPERAND-SAMPLE] captured=%d decodeFailures=%d nullBlobs=%d notMaterializing=%d firstError=%s",
              captured,
              decodeFailures,
              nullBlobs,
              notMaterializing,
              firstError == null ? "none" : firstError));
    }
    if (captured == 0) {
      return stats;
    }
    int[] trim = java.util.Arrays.copyOf(counts, captured);
    java.util.Arrays.sort(trim);
    long sum = 0;
    for (int v: trim) {
      sum += v;
    }
    stats.n = captured;
    stats.min = trim[0];
    stats.max = trim[captured - 1];
    stats.p50 = trim[Math.min(captured - 1, (int) (captured * 0.50))];
    stats.p90 = trim[Math.min(captured - 1, (int) (captured * 0.90))];
    stats.p99 = trim[Math.min(captured - 1, (int) (captured * 0.99))];
    stats.mean = (double) sum / captured;
    return stats;
  }

  /**
   * RocksDB-stats poller tick. Sums {@code rocksdb.num-running-compactions} and
   * {@code rocksdb.estimate-num-keys} across partitions for both regions. Updates max-counter and
   * appends a row to the per-iteration timeline TSV.
   */
  private void sampleRocksDBStats() {
    try {
      long t0 = timelineIterStartNanos;
      if (t0 == 0) {
        return;
      }
      long dc0Comp = sumNumericProperty(engineDC0, "rocksdb.num-running-compactions");
      long dc1Comp = sumNumericProperty(engineDC1, "rocksdb.num-running-compactions");
      long dc0Keys = sumNumericProperty(engineDC0, "rocksdb.estimate-num-keys");
      long dc1Keys = sumNumericProperty(engineDC1, "rocksdb.estimate-num-keys");
      lastEstimateKeysDc0 = dc0Keys;
      lastEstimateKeysDc1 = dc1Keys;
      long combined = dc0Comp + dc1Comp;
      long prev = maxRunningCompactionsThisIter.get();
      while (combined > prev && !maxRunningCompactionsThisIter.compareAndSet(prev, combined)) {
        prev = maxRunningCompactionsThisIter.get();
      }
      java.io.PrintWriter pw = timelineWriter;
      if (pw != null) {
        long tMs = (System.nanoTime() - t0) / 1_000_000L;
        pw.println(tMs + "\t" + dc0Comp + "\t" + dc1Comp + "\t" + dc0Keys + "\t" + dc1Keys);
      }
    } catch (Throwable ignored) {
      // Don't let poller exceptions kill the executor.
    }
  }

  private long sumNumericProperty(StorageEngine engine, String name) {
    if (engine == null) {
      return 0L;
    }
    long sum = 0L;
    for (int p = 0; p < PARTITION_COUNT; p++) {
      try {
        Object part = engine.getPartitionOrThrow(p);
        if (part instanceof RocksDBStoragePartition) {
          sum += ((RocksDBStoragePartition) part).getRocksDBStatValue(name);
        }
      } catch (Throwable ignored) {
      }
    }
    return sum;
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
    producedCount.addAndGet(NUM_RECORDS_PER_INVOCATION);
    waitForBackpressure();
    return sentinelKey;
  }

  private String runPartialUpdateWorkload() {
    for (int i = 0; i < NUM_RECORDS_PER_INVOCATION; i++) {
      long seq = keyCounter.getAndIncrement();
      String key = AAIngestionWorkloadHelper.partialUpdatePoolKey(seq, partialUpdateKeyPoolSize);
      UpdateBuilder ub = new UpdateBuilderImpl(writeComputeSchema);
      int fieldChoice = (int) (seq % 3);
      if (fieldChoice == 0) {
        // setName payload scales with fieldUpdateSizeBytes via the precomputed padding so per-update
        // bytes track the @Param across both setName and AddToMap branches (setAge stays small).
        ub.setNewFieldValue("name", "upd-" + seq + derivedTagValuePadding);
      } else if (fieldChoice == 1) {
        ub.setNewFieldValue("age", (int) (seq % 100_000));
      } else {
        Map<String, String> mapUpdate = new HashMap<>(2);
        String mapKey = "k-" + (seq % derivedTagsMapSize);
        mapUpdate.put(mapKey, "v-" + seq + derivedTagValuePadding);
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
    producedCount.addAndGet(NUM_RECORDS_PER_INVOCATION);
    waitForBackpressure();
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
    producedCount.addAndGet(NUM_RECORDS_PER_INVOCATION);
    waitForBackpressure();
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

  private void sendUpdateWithoutFlush(VeniceWriter<byte[], byte[], byte[]> writer, String key, GenericRecord update) {
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

  // ---------------- Back-pressure helpers (Option A: lag-bounded gate) ----------------
  // GOAL: autoresearch/jmh-backpressure/GOAL.md §2 Approach 1.

  /**
   * Read the current consumer-side VT progress as the slower-region sum across all partitions of
   * {@link PartitionConsumptionState#getLatestProcessedVtPosition()}'s numeric offset.
   *
   * <p>min(DC0, DC1) is used so the gate respects the slower follower — both regions must consume
   * before the producer is allowed to advance. Returns 0 if the SITs / PCSs aren't ready yet.
   */
  private long readSlowerRegionVtOffsetSum() {
    return Math.min(readRegionVtOffsetSum(sitDC0), readRegionVtOffsetSum(sitDC1));
  }

  private long readRegionVtOffsetSum(StoreIngestionTask sit) {
    if (sit == null) {
      return 0L;
    }
    long sum = 0L;
    for (int p = 0; p < PARTITION_COUNT; p++) {
      try {
        PartitionConsumptionState pcs = sit.getPartitionConsumptionState(p);
        if (pcs == null) {
          continue;
        }
        PubSubPosition pos = pcs.getLatestProcessedVtPosition();
        if (pos == null) {
          continue;
        }
        long off = pos.getNumericOffset();
        if (off > 0) {
          sum += off;
        }
      } catch (Throwable ignored) {
        // PCS may flicker during teardown; ignore and treat as no progress on this partition.
      }
    }
    return sum;
  }

  /**
   * Background-poller callback. Updates {@link #lastSeenStorageCount} to the slower-region delta
   * from {@link #iterationStorageBaseline}. Runs every 100ms via {@link #backpressurePoller}.
   */
  private void updateLastSeenStorageCount() {
    try {
      long currentSum = readSlowerRegionVtOffsetSum();
      long delta = currentSum - iterationStorageBaseline;
      if (delta < 0) {
        delta = 0;
      }
      lastSeenStorageCount = delta;
    } catch (Throwable ignored) {
      // Don't let poller exceptions kill the executor.
    }
  }

  /**
   * Block the producer until {@code producedCount - lastSeenStorageCount <= maxBacklog}. Sleeps
   * 2ms between checks (poller updates {@code lastSeenStorageCount} every 100ms, so granularity
   * is ~100ms anyway). Tracks total wait time in {@link #backpressureWaitNanos} for diagnostics.
   */
  private void waitForBackpressure() {
    long waitStartNanos = 0L;
    boolean recordingWait = false;
    while (true) {
      long lag = producedCount.get() - lastSeenStorageCount;
      if (lag <= maxBacklog) {
        break;
      }
      if (!recordingWait) {
        waitStartNanos = System.nanoTime();
        recordingWait = true;
      }
      try {
        Thread.sleep(2);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        break;
      }
    }
    if (recordingWait) {
      backpressureWaitNanos.addAndGet(System.nanoTime() - waitStartNanos);
    }
  }

  public static void main(String[] args) throws RunnerException {
    org.openjdk.jmh.runner.options.Options opt =
        new OptionsBuilder().include(LeanActiveActiveIngestionBenchmark.class.getSimpleName())
            .addProfiler(GCProfiler.class)
            .build();
    new Runner(opt).run();
  }
}
