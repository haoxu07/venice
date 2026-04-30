# Lean AA Ingestion Harness

A programmable test harness that exercises Venice's Active-Active (AA) write-compute ingestion
path with **real Kafka** and **real RocksDB**, but with everything else (Helix, ZooKeeper,
controllers, routers, D2, schema registry) replaced by **minimal in-memory stubs**.

## What this harness IS

- A faster, lower-noise alternative to `VeniceTwoLayerMultiRegionMultiClusterWrapper` for AA
  ingestion micro-benchmarks and component-level perf experiments.
- A reverse-engineered, dependency-graph-documented surface for the ~25 things
  `ActiveActiveStoreIngestionTask` actually needs at runtime — useful as a reference for any future
  Venice perf work that wants to surgically replace one of those dependencies.
- A way to spin up `2 brokers + 2 RocksDB + 2 AA SITs` in **~14 seconds** total (vs ~60–120
  seconds for the full multi-region multi-cluster wrapper) and tear them down cleanly in <5s.

## What this harness IS NOT

- It is **NOT** a full Venice cluster. There are no controllers, routers, ZK, Helix, D2, parent
  controllers, system stores, push jobs, or admin protocols.
- It is **NOT** suitable for end-to-end correctness testing of features that touch the control
  plane (admin commands, version pushes, store updates, leader-handover via Helix, etc.).
- It is **NOT** a replacement for integration tests that need real cluster topology — use
  `VeniceTwoLayerMultiRegionMultiClusterWrapper` for those.

## Architecture in one diagram

```
                 ┌──────────────────────────────────────────────────┐
                 │              MinimalAAIngestionHarness            │
                 │                                                   │
   region 0 ────►│ ┌────────────────┐    ┌────────────────────────┐ │
                 │ │ PubSubBroker   │◄──►│ ActiveActiveSITTask    │ │
                 │ │ (real Kafka)   │    │  + StorageService      │ │
                 │ │  RT + VT topic │    │  + RocksDB (real)      │ │
                 │ └────────────────┘    └────────────────────────┘ │
                 │           ▲                       │               │
                 │           │ TopicSwitch ──┐       ▼ VT writes     │
                 │           ▼               │  (RocksDB on disk)    │
   region 1 ────►│ ┌────────────────┐    ┌───┴────────────────────┐ │
                 │ │ PubSubBroker   │◄──►│ ActiveActiveSITTask    │ │
                 │ │ (real Kafka)   │    │  + StorageService      │ │
                 │ │  RT + VT topic │    │  + RocksDB (real)      │ │
                 │ └────────────────┘    └────────────────────────┘ │
                 │                                                   │
                 │  In-memory stubs (shared):                        │
                 │   • InMemoryReadOnlyStoreRepository (1 store)     │
                 │   • InMemoryReadOnlySchemaRepository (1 value+rmd+wc)│
                 │  Mocks: HeartbeatMonitoringService, ZKHelixAdmin   │
                 └──────────────────────────────────────────────────┘
```

Cross-region replication is enabled by broadcasting a `TopicSwitch` control message on each VT
that lists *both* regions' kafka URLs as RT sources. Each region's AA SIT then subscribes to its
own RT *and* the remote RT, producing the canonical AA dual-RT consumption pattern.

## Quick start — instantiate and use

```java
import com.linkedin.venice.benchmark.lean.MinimalAAIngestionHarness;
import com.linkedin.davinci.store.StorageEngine;
import com.linkedin.venice.partitioner.DefaultVenicePartitioner;
import com.linkedin.venice.serializer.AvroSerializer;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.VeniceWriter;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;

// 1. Configure: 2 regions, 2 partitions, unique store name.
Utils.thisIsLocalhost();
String storeName = Utils.getUniqueString("my-aa-test");
MinimalAAIngestionHarness.Config config =
    new MinimalAAIngestionHarness.Config(/*regionCount*/ 2, /*partitionCount*/ 2, storeName);

// 2. Construct + start. start() does the heavy lifting (~14 seconds).
MinimalAAIngestionHarness harness = new MinimalAAIngestionHarness(config);
try {
  harness.start();

  // 3. Get per-region writers and engines.
  VeniceWriter<byte[], byte[], byte[]> writerDC0 = harness.getVeniceWriterForRTTopic(0);
  VeniceWriter<byte[], byte[], byte[]> writerDC1 = harness.getVeniceWriterForRTTopic(1);
  StorageEngine engineDC0 = harness.getStorageEngineForRegion(0);
  StorageEngine engineDC1 = harness.getStorageEngineForRegion(1);

  // 4. Build serializers from the harness's schemas.
  Schema valueSchema = harness.getSchemaRepository()
      .getValueSchema(storeName, /*valueSchemaId*/ 1).getSchema();
  AvroSerializer<String> keySer = new AvroSerializer<>(Schema.create(Schema.Type.STRING));
  AvroSerializer<GenericRecord> valSer = new AvroSerializer<>(valueSchema);
  DefaultVenicePartitioner partitioner = new DefaultVenicePartitioner();

  // 5. Send a PUT and wait for AA replication.
  GenericRecord rec = new GenericData.Record(valueSchema);
  rec.put("name", "alice");
  rec.put("age", 42);
  rec.put("score", 3.14);
  rec.put("tags", new java.util.HashMap<String, String>());

  byte[] keyBytes = keySer.serialize("key-1");
  int partition = partitioner.getPartitionId(keyBytes, /*partitionCount*/ 2);
  writerDC0.put(keyBytes, valSer.serialize(rec), /*valueSchemaId*/ 1)
      .get(15, java.util.concurrent.TimeUnit.SECONDS);

  // 6. Verify the put landed on BOTH regions' RocksDB (cross-region AA replication).
  com.linkedin.venice.utils.TestUtils.waitForNonDeterministicAssertion(
      30, java.util.concurrent.TimeUnit.SECONDS, () -> {
        org.testng.Assert.assertNotNull(engineDC0.get(partition, keyBytes), "DC0 missing");
        org.testng.Assert.assertNotNull(engineDC1.get(partition, keyBytes), "DC1 missing");
      });
} finally {
  harness.stop();
}
```

The schemas pre-loaded by the harness's `InMemoryReadOnlySchemaRepository` match the
`BenchmarkRecord` shape from `ActiveActiveIngestionBenchmark`. If you need a different value
schema, see the **Extending the harness** section below.

## Public API

The full programmable surface is on `MinimalAAIngestionHarness`:

| Method                            | Purpose                                                              |
|-----------------------------------|----------------------------------------------------------------------|
| `MinimalAAIngestionHarness(Config)` | Construct (lightweight; no kafka / RocksDB yet).                  |
| `start()`                         | Bring up brokers, topics, storage, AA SITs, leader promotion.        |
| `stop()`                          | Tear down all resources; idempotent.                                 |
| `isStarted()`                     | Check whether `start()` has completed and `stop()` has not yet run.  |
| `getBrokerForRegion(int)`         | Get the `PubSubBrokerWrapper` for a region.                          |
| `getBrokerAddress(int)`           | Convenience for getting the bootstrap URL for a region.              |
| `getStorageServiceForRegion(int)` | Get the `StorageService` for a region.                               |
| `getStorageEngineForRegion(int)`  | Get the version-topic `StorageEngine` for a region.                  |
| `getRegionTempDir(int)`           | Get the region-specific temp dir (root of RocksDB data).             |
| `getStoreRepository()`            | Get the shared in-memory store repo.                                 |
| `getSchemaRepository()`           | Get the shared in-memory schema repo.                                |
| `getRealTimeTopic()`              | Get the harness's RT `PubSubTopic` handle.                           |
| `getVersionTopic()`               | Get the harness's VT `PubSubTopic` handle.                           |
| `getPubSubTopicRepository()`      | Get the shared `PubSubTopicRepository`.                              |
| `getIngestionTaskForRegion(int)`  | Get the running AA SIT for a region (already started + leader).      |
| `getVeniceWriterForRTTopic(int)`  | Get the per-region `VeniceWriter<byte[],byte[],byte[]>` for RT.      |
| `getConfig()`                     | Get the `Config` this harness was built with.                        |

## Extending the harness for new workloads

### Add new producers (writing through the harness)

The harness exposes `VeniceWriter` instances per region via `getVeniceWriterForRTTopic(int)`.
Use the standard `VeniceWriter` API:

```java
VeniceWriter<byte[], byte[], byte[]> writer = harness.getVeniceWriterForRTTopic(0);
writer.put(keyBytes, valueBytes, valueSchemaId);
writer.update(keyBytes, wcBytes, valueSchemaId, derivedSchemaId, callback);
writer.delete(keyBytes, callback);
writer.flush();   // optional but recommended for measurement boundaries
```

The wire format on RT is identical to what production Samza producers emit (KME envelope, DIV
sequence numbers, etc.), so ingestion-side behavior matches a real cluster.

### Custom store config

Today the harness builds its `Store` via `InMemoryReadOnlyStoreRepository` with a fixed config
(AA enabled, write-compute enabled, hybrid, configurable partition count, single version 1
marked `ONLINE`). If you need a different setting:

1. **Partition count** — pass via `Config(regionCount, partitionCount, storeName)`.
2. **Hybrid rewind** / **offset lag** / **version number** — use the 5-arg `Config` ctor and the
   5-arg `InMemoryReadOnlyStoreRepository` ctor.
3. **Anything else** (read computation, chunking, batch-get limit, etc.) — extend
   `InMemoryReadOnlyStoreRepository.buildStore(...)` to set the desired flags on the `ZKStore`.

### Custom value schema

The default value schema is `BenchmarkRecord` (matches `ActiveActiveIngestionBenchmark`). To use
a different one, replace the `DEFAULT_VALUE_SCHEMA_STR` reference inside
`MinimalAAIngestionHarness.start()`:

```java
// In start():
Schema valueSchema = new Schema.Parser().parse(MY_VALUE_SCHEMA_STR);
Schema rmdSchema = new RmdSchemaGeneratorV1().generateMetadataSchema(valueSchema);
Schema writeComputeSchema = WriteComputeSchemaConverter.getInstance()
    .convertFromValueRecordSchema(valueSchema);
```

For multi-value-schema stores, `InMemoryReadOnlySchemaRepository` would need to be extended to
hold a list of value schemas keyed by id; today it only stores one. The store repo's
`setLatestSuperSetValueSchemaId` would need to point at the schema the AA merge path treats as
the superset.

### Custom workloads

Look at `LeanActiveActiveIngestionBenchmark` for a JMH-driven workload example, and at
`LeanBenchmarkSetupSmokeTest` for a non-JMH single-record example covering PUT, UPDATE, DELETE.

## Known-mock dependencies and caveats

These dependencies of `ActiveActiveStoreIngestionTask` are mocked rather than wired against real
implementations. If your perf experiment exercises code paths that depend on their *behavior*
(not just their *presence*), check carefully. See `autoresearch/lean-aa-harness/dep-graph.md`
for the full classification of all 25 setters.

| Dependency                       | Strategy                                | Caveat |
|----------------------------------|-----------------------------------------|--------|
| `HeartbeatMonitoringService`     | Mockito mock (RETURNS_DEEP_STUBS)       | No actual heartbeat tracking. Don't use this harness to test heartbeat-lag-based ready-to-serve gating (the harness disables that gate). |
| `ZKHelixAdmin` (`Lazy<>`)        | Mockito mock                            | No leader-handover via Helix; the harness directly calls `LeaderFollowerStoreIngestionTask.promoteToLeader`. |
| `MetaStoreWriter`                | `null`                                  | System-store push paths cannot be exercised. |
| `BlobTransferManager`            | `null` (via supplier)                   | Blob transfer / bootstrap paths cannot be exercised. |
| `KafkaClusterBasedRecordThrottler` | Empty quota map (no throttling)        | Per-cluster ingestion throttling is effectively disabled. |
| Heartbeat-lag ready-to-serve     | Disabled via `SERVER_USE_HEARTBEAT_LAG_FOR_READY_TO_SERVE_CHECK_ENABLED=false` | The harness can't reproduce that gating logic (no heartbeat producers exist). |
| DoL (Declaration-of-Leadership)  | Disabled via `SERVER_LEADER_HANDOVER_USE_DOL_MECHANISM_FOR_USER_STORES=false` | Falls back to legacy time-based gating with 0s delay. |
| Replication factor               | Hardcoded 1                             | Only one replica per partition per region; no follower-replica testing in this harness. |

The harness always promotes its single SIT-per-region to LEADER for every partition. There are
no follower replicas. If you need to test follower-side behavior, this harness is not the right
tool.

## How to run the lean benchmark

### 1. Build the JMH jar (once)

```bash
./gradlew :internal:venice-test-common:jmhJar
```

This produces `internal/venice-test-common/build/libs/venice-test-common-jmh.jar` (~300 MB).

### 2. Set up JVM open flags

The benchmark JVM needs reflective access to several `java.base` packages (kafka client / netty
internals + `--add-opens` for Avro). Use this `JVM_OPENS`:

```bash
JVM_OPENS="-XX:+IgnoreUnrecognizedVMOptions \
  --add-opens=java.base/java.nio=ALL-UNNAMED \
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens=java.base/java.lang=ALL-UNNAMED \
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
  -DpubSubBrokerFactory=com.linkedin.venice.integration.utils.KafkaBrokerFactory"
```

### 3. Run the benchmark directly

```bash
java -Xms4G -Xmx4G $JVM_OPENS \
  -jar internal/venice-test-common/build/libs/venice-test-common-jmh.jar \
  'LeanActiveActiveIngestionBenchmark' \
  -p workloadType=PUT \
  -f 1 -wi 1 -w 5s -i 2 -r 30s -foe true \
  -jvmArgs "-Xms4G -Xmx4G $JVM_OPENS"
```

`workloadType` accepts `PUT`, `PARTIAL_UPDATE`, or `MIXED`. For the side-by-side full-wrapper
baseline, swap the include pattern for `'^com\.linkedin\.venice\.benchmark\.ActiveActiveIngestionBenchmark\.'`.

### 4. Run the smoke tests (CI-friendly)

The CI smoke gate is:

```bash
./gradlew :internal:venice-test-common:integrationTest \
  --tests 'com.linkedin.venice.benchmark.lean.*'
```

This runs all four smoke tests (broker, storage, ingestion, benchmark setup) and the Phase-1
unit test:

```bash
./gradlew :internal:venice-test-common:test \
  --tests 'com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest'
```

Smoke-test wall time: ~2 minutes total; each test starts a fresh harness (~14s) and runs a
small functional check.

## File map

| File | Role |
|---|---|
| `MinimalAAIngestionHarness.java` | The harness itself. Lives in `integrationTest` source set because it pulls in test infra (`PubSubBrokerWrapper`, `ServiceFactory`). |
| `InMemoryReadOnlyStoreRepository.java` | Single-store in-memory store repo. Lives in `main` source set so it's reachable from JMH. |
| `InMemoryReadOnlySchemaRepository.java` | Single-store in-memory schema repo. Lives in `main` source set. |
| `LeanHarness*SmokeTest.java` (4 files) | Per-phase smoke tests — broker, storage, ingestion, benchmark setup. |
| `../../jmh/.../LeanActiveActiveIngestionBenchmark.java` | JMH benchmark using the harness. |
| `../../jmh/.../AAIngestionWorkloadHelper.java` | Shared workload constants + record builders for both lean and full benchmarks. |
| `../../test/.../LeanHarnessSchemaTest.java` | Phase-1 unit test for the in-memory repos. |
| `autoresearch/lean-aa-harness/dep-graph.md` | Authoritative classification of all ~25 SIT factory setters. |
| `autoresearch/lean-aa-harness/SUCCESS.md` | Final wrap-up doc with measurements and follow-ups. |
| `autoresearch/lean-aa-harness/phase-N-progress.md` | Per-phase implementation history (Phases 0–6). |

## Performance characteristics

Measured on a developer host (16 vCPU, JDK 17), 2026-04-30, branch
`haoxu07/aa-bench-jmh-improvements`:

| Metric                       | Full Wrapper | Lean Harness | Delta             |
|------------------------------|--------------|--------------|-------------------|
| `start()` time               | ~50 s        | **~14 s**    | -72%              |
| Total `@Setup` time (PARTIAL_UPDATE w/ pre-pop) | ~66 s | **~24 s** | -64% (~2.75×)  |
| Steady-state RSS (PUT, 4G heap) | 5.93 GB    | **5.27 GB**  | -11.1%            |
| Virtual memory (`VmSize`)    | 60.0 GB      | **46.8 GB**  | -22.0%            |
| JMH Score (PARTIAL_UPDATE)   | 124,198 ops/s | 140,420 ops/s | +13.1%          |
| JMH Score (PUT)              | 76,303 ops/s | 154,636 ops/s | +102.6%          |
| Measurement E2E (PARTIAL_UPDATE) | 56,214 ops/s | 68,326 ops/s | +21.5%        |
| VT-CHECK (mismatches/missing/errors) | 0/0/0 | 0/0/0      | identical         |

The +13–103% throughput delta is in the *favorable* direction (less wrapper overhead → more
headroom). VT consistency is byte-identical, confirming the AA merge path produces the same
results regardless of which cluster harness it runs under.

The RSS reduction (11%) is bounded by the 4 GB committed heap (`-Xms4G -Xmx4G`); the non-heap
reduction is more dramatic (`VmSize` -22%), reflecting fewer thread stacks and fewer mapped
files in the stripped-down JVM.

## Troubleshooting

- **`Sentinel not yet ingested on region 0/1: …`** during the canary check after `start()`:
  Indicates the AA SITs aren't draining. Check the kafka brokers came up (logs print
  `Started broker for region X: localhost:NNNNN`) and that the SITs are subscribed
  (`subscribed task to <topic>/<partition>`).

- **`IllegalStateException: Expect to get superset value schema for store: <name>`**: The
  `InMemoryReadOnlySchemaRepository.getSupersetSchema()` must return the value schema (not
  `null`). The current impl handles this; if you fork the schema repo, preserve that behavior.

- **`UnsatisfiedLinkError` on RocksDB**: typically a JNI mismatch on aarch64 / unusual JDKs.
  Confirm `pubSubBrokerFactory` is set to `com.linkedin.venice.integration.utils.KafkaBrokerFactory`.

- **JMH lock**: `/tmp/jmh.lock` lingers after a crashed JMH run. Delete it manually or pass
  `-Djmh.ignoreLock=true`.

## See also

- `autoresearch/lean-aa-harness/GOAL.md` — the original goal document and phased plan
- `autoresearch/lean-aa-harness/dep-graph.md` — classification of all ~25 SIT factory setters
- `autoresearch/lean-aa-harness/SUCCESS.md` — final wrap-up with measurements and follow-ups
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java` — the production AA ingestion task this harness drives
