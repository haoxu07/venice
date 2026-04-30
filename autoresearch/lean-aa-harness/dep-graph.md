# StoreIngestionTaskFactory.Builder Dependency Graph

**Source:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTaskFactory.java` (lines 100-374)
**Cross-reference:** `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/KafkaStoreIngestionService.java` (lines 522-548)

This document enumerates every `set*()` method on `StoreIngestionTaskFactory.Builder` and classifies how
the lean harness will provision each dependency.

**Classification legend:**
- `REAL` — instantiate the production class with reduced inputs (e.g., empty configs, no metrics exporters).
- `STUB` — minimal hand-written in-memory implementation of the interface; surface-area limited to what
  `ActiveActiveStoreIngestionTask` actually calls.
- `NOOP` — the builder accepts `null` (verified by reading the SIT code) OR a do-nothing object whose
  methods return defaults; the SIT never observes side-effects.
- `MOCK` — a Mockito mock with sensible default returns (`RETURNS_DEEPS_STUBS` or selective stubbing).

**Risk legend:**
- `LOW` — clearly stubbable, used in well-isolated code paths.
- `MEDIUM` — used in many call sites; requires verification that the chosen substitute returns sane defaults.
- `HIGH` — uncertain whether stubbing/no-op will cleanly work; concrete fallback included.

Total entries: **27** (matching exactly the 27 builder setters; counted via `grep -c "public Builder set" StoreIngestionTaskFactory.java` ⇒ 27).

---

## 1. `setVeniceWriterFactory(VeniceWriterFactory)`

- **Type:** `com.linkedin.venice.writer.VeniceWriterFactory`
- **Classification:** `REAL`
- **Justification:** The SIT must produce records to the version topic via `VeniceWriter`, which the factory
  manufactures. DIV (Data Integrity Validation) is enforced inside `VeniceWriter`'s segment/sequence-number
  logic — bypassing it (or stubbing the factory) would cause downstream consumers to reject messages. The real
  factory is a thin wrapper over a producer config map; it is cheap to construct.
- **Source-of-truth:** `IntegrationTestPushUtils.getVeniceWriterFactory(brokerAddress)` — the same helper used by
  the existing benchmark. Pointed at the harness's per-region `PubSubBrokerWrapper` bootstrap URL.
- **Risk:** LOW

## 2. `setHeartbeatMonitoringService(HeartbeatMonitoringService)`

- **Type:** `com.linkedin.davinci.stats.ingestion.heartbeat.HeartbeatMonitoringService`
- **Classification:** `NOOP` (or `MOCK` fallback)
- **Justification:** Heartbeat monitoring observes leader-side timestamps for staleness alerting. In a single-host
  benchmark with hardcoded leader-follower state, there is no consumer of these signals. SIT defensively guards
  against `null` in many call sites; for those that don't, a Mockito mock with default returns will silence them.
- **Source-of-truth:** Mockito `mock(HeartbeatMonitoringService.class)` with no stubbed behaviors; or `null` if SIT
  null-tolerates (verified during Phase 4 smoke test).
- **Risk:** LOW

## 3. `setVeniceViewWriterFactory(VeniceViewWriterFactory)`

- **Type:** `com.linkedin.davinci.store.view.VeniceViewWriterFactory`
- **Classification:** `REAL`
- **Justification:** Constructed from `VeniceConfigLoader` + `VeniceWriterFactory`. With no views configured on
  the test store (default), the factory produces an empty writer set and AA processing skips the view-writing
  loop entirely. Constructing the real one is trivial and avoids an unwanted abstraction leak.
- **Source-of-truth:** `new VeniceViewWriterFactory(veniceConfigLoader, veniceWriterFactory)` mirroring
  `KafkaStoreIngestionService:488`. The `veniceConfigLoader` is fed minimal cluster props.
- **Risk:** LOW

## 4. `setStorageMetadataService(StorageMetadataService)`

- **Type:** `com.linkedin.davinci.storage.StorageMetadataService`
- **Classification:** `REAL`
- **Justification:** Critical: SIT persists per-partition offset records and `StoreVersionState` (SOP/EOP) here.
  Stubbing risks dropping checkpoints and silently corrupting the run. The real implementation
  (`StorageEngineMetadataService`) is RocksDB-backed and is part of the same `StorageService` we already need
  to spin up for Phase 3, so we get this for free.
- **Source-of-truth:** Real `StorageEngineMetadataService` constructed from `StorageService` via
  `storageService.getStorageEngineMetadataService()` (or its public API).
- **Risk:** LOW (used heavily but the real impl is lightweight in-process)

## 5. `setLeaderFollowerNotifiersQueue(Queue<VeniceNotifier>)`

- **Type:** `java.util.Queue<com.linkedin.davinci.notifier.VeniceNotifier>`
- **Classification:** `NOOP`
- **Justification:** `VeniceNotifier` callbacks fire on partition state transitions (started, restarted, completed,
  error). In the benchmark we do not observe these externally; at most we want to log. A queue containing one
  do-nothing `VeniceNotifier` (or an empty `ConcurrentLinkedQueue`) is sufficient — SIT iterates and calls each;
  empty iteration is a no-op.
- **Source-of-truth:** `new ConcurrentLinkedQueue<>()` empty, or with a single logging-only `VeniceNotifier`
  implementation if observability of phase-4 progress proves useful.
- **Risk:** LOW

## 6. `setSchemaRepository(ReadOnlySchemaRepository)`

- **Type:** `com.linkedin.venice.meta.ReadOnlySchemaRepository`
- **Classification:** `STUB`
- **Justification:** SIT looks up value schemas, RMD schemas, and write-compute schemas by store name + schema id.
  The interface is wide but the SIT call sites are a small subset. We will provide an in-memory implementation
  pre-loaded with a fixed schema set generated by `RmdSchemaGeneratorV1` and `WriteComputeSchemaConverter`. This
  is the Phase 1 deliverable.
- **Source-of-truth:** `InMemoryReadOnlySchemaRepository` (Phase 1) backed by a `HashMap<storeName, SchemaTable>`
  pre-loaded with `BenchmarkRecord` value schema, RMD v1, and WC schema.
- **Risk:** MEDIUM — the surface area is wide; we must intercept any unexpected lookup and either populate or
  throw with a clear "missing schema" message rather than returning `null` silently.

## 7. `setMetadataRepository(ReadOnlyStoreRepository)`

- **Type:** `com.linkedin.venice.meta.ReadOnlyStoreRepository`
- **Classification:** `STUB`
- **Justification:** SIT reads `Store` and `Version` metadata (AA enabled, WC enabled, hybrid config, partition
  count, partitioner config). One in-memory store with the required flags satisfies all reads. Phase 1 deliverable.
- **Source-of-truth:** `InMemoryReadOnlyStoreRepository` containing one pre-built `Store` + one `Version`
  (AA=true, WC=true, hybrid=true, partitionCount=2). Backed by `ZKStore` constructor for object equivalence.
- **Risk:** MEDIUM — `Store` is a wide value object and SIT inspects many fields; missing one returns a default
  that may be silently wrong (e.g., `getPartitionerConfig() == null` causes NPE deep in partitioner setup).

## 8. `setHostLevelIngestionStats(AggHostLevelIngestionStats)`

- **Type:** `com.linkedin.davinci.stats.AggHostLevelIngestionStats`
- **Classification:** `REAL`
- **Justification:** Stats objects are constructed against a `MetricsRepository` and a metadata repo. Constructing
  the real one with a no-reporter `MetricsRepository` is essentially free (HashMap of sensors) and avoids
  null-pointer surprises in code paths that record metrics unconditionally.
- **Source-of-truth:** `new AggHostLevelIngestionStats(metricsRepo, serverConfig, emptyMap, metadataRepo, false, SystemTime.INSTANCE)`
  mirroring `KafkaStoreIngestionService:418-424`.
- **Risk:** LOW

## 9. `setVersionedDIVStats(AggVersionedDIVStats)`

- **Type:** `com.linkedin.davinci.stats.AggVersionedDIVStats`
- **Classification:** `REAL`
- **Justification:** Same rationale as #8 — recorded by the SIT on every poll; cheap with a no-reporter
  `MetricsRepository`. Stubbing risks NPE.
- **Source-of-truth:** `new AggVersionedDIVStats(metricsRepo, metadataRepo, false, "lean-cluster")` per
  `KafkaStoreIngestionService:425-429`.
- **Risk:** LOW

## 10. `setVersionedIngestionStats(AggVersionedIngestionStats)`

- **Type:** `com.linkedin.davinci.stats.AggVersionedIngestionStats`
- **Classification:** `REAL`
- **Justification:** Same as #8/#9. Real construction is one line; avoids stub coverage gaps.
- **Source-of-truth:** `new AggVersionedIngestionStats(metricsRepo, metadataRepo, serverConfig)` per
  `KafkaStoreIngestionService:430`.
- **Risk:** LOW

## 11. `setStoreBufferService(AbstractStoreBufferService)`

- **Type:** `com.linkedin.davinci.kafka.consumer.AbstractStoreBufferService`
- **Classification:** `REAL`
- **Justification:** This is the drainer. Records consumed from RT/VT are drained through this service into
  `StorageEngine` writes. It is on the critical path of every put, so a stub is impossible. The real impl
  (`StoreBufferService`) is a multi-threaded executor; use a small thread count for the lean harness.
- **Source-of-truth:** `new StoreBufferService(numThreads, bufferCapacity, notifyDelta, afterLeaderLogic, logContext, metricsRepo, true, "lean-cluster")`
  per `KafkaStoreIngestionService:435-443`. With `numThreads=2` to keep the footprint small.
- **Risk:** LOW

## 12. `setServerConfig(VeniceServerConfig)`

- **Type:** `com.linkedin.davinci.config.VeniceServerConfig`
- **Classification:** `REAL`
- **Justification:** SIT reads dozens of tunables from this — RT throttling, AA-WC parallelism, drainer
  batching, idle-task timeouts. A stub would be a reimplementation of the entire config surface. The real
  class is a property-bag built from a `VeniceProperties`; we only need to provide the props the SIT actually
  reads.
- **Source-of-truth:** `new VeniceServerConfig(veniceProperties, kafkaClusterMap)` populated with the same
  defaults `ActiveActiveIngestionBenchmark` uses + lean-harness-specific overrides
  (`SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED=true`, the kafka cluster map of region 0/1).
- **Risk:** MEDIUM — must remember to set every tunable the SIT reads; missing one defaults to whatever
  `VeniceProperties.getXxx()` returns. We will iteratively add props as the smoke test surfaces NPEs / wrong defaults.

## 13. `setDiskUsage(DiskUsage)`

- **Type:** `com.linkedin.venice.utils.DiskUsage`
- **Classification:** `REAL`
- **Justification:** Cheap object that polls the filesystem every N writes to short-circuit ingestion when disk
  is full. Real construction is `new DiskUsage(path, threshold)`. With a high threshold and a tmp dir, this is
  trivially a no-op in steady state.
- **Source-of-truth:** `new DiskUsage(harnessTempDir.toString(), 0.99)` — 99% threshold means the disk-full
  check effectively never fires during the bench run.
- **Risk:** LOW

## 14. `setAggKafkaConsumerService(AggKafkaConsumerService)`

- **Type:** `com.linkedin.davinci.kafka.consumer.AggKafkaConsumerService`
- **Classification:** `REAL`
- **Justification:** This is the multi-region consumer aggregator that gives the SIT topic-partition
  subscription primitives. It must be the real one; stubbing would mean re-writing the consumer
  multiplex/throttle/heartbeat layer. We construct it with the harness's two `PubSubBrokerWrapper` bootstrap
  URLs as known kafka clusters.
- **Source-of-truth:** Real `new AggKafkaConsumerService(propsSupplier, serverConfig, throttler, recordThrottler,
  metricsRepo, staleTopicChecker, killCallback, metadataRepo, pubSubContext)` mirroring `KafkaStoreIngestionService:454-463`.
  Local kafka cluster registered via `aggKafkaConsumerService.createKafkaConsumerService(commonProps)`.
- **Risk:** MEDIUM — many transitive dependencies (throttlers, kafka cluster map). Each is constructable from
  config but must be assembled in the right order.

## 15. `setPartitionStateSerializer(InternalAvroSpecificSerializer<PartitionState>)`

- **Type:** `com.linkedin.venice.serialization.avro.InternalAvroSpecificSerializer<com.linkedin.venice.kafka.protocol.state.PartitionState>`
- **Classification:** `REAL`
- **Justification:** Singleton-style avro serializer for the on-disk partition state record. There is exactly one
  correct instance. Use `AvroProtocolDefinition.PARTITION_STATE.getSerializer()`.
- **Source-of-truth:** `AvroProtocolDefinition.PARTITION_STATE.getSerializer()` cast to the typed param. This is
  the same instance every other Venice service uses.
- **Risk:** LOW

## 16. `setIsDaVinciClient(boolean)`

- **Type:** `boolean`
- **Classification:** `REAL` (literal value)
- **Justification:** Boolean flag that toggles a few code branches (e.g., daemon-thread heartbeat reporting).
  We are emulating a Venice server, not DaVinci, so this is `false`.
- **Source-of-truth:** Literal `false`.
- **Risk:** LOW

## 17. `setRemoteIngestionRepairService(RemoteIngestionRepairService)`

- **Type:** `com.linkedin.davinci.kafka.consumer.RemoteIngestionRepairService`
- **Classification:** `NOOP`
- **Justification:** This service repairs partitions that fail on remote regions by re-subscribing. In our two-
  region harness, the repair path is exercised only on rare failure modes that the bench will not trigger. A
  Mockito mock that no-ops `submitRepairTask()` is sufficient.
- **Source-of-truth:** `Mockito.mock(RemoteIngestionRepairService.class)` with default returns. Fallback: if the
  SIT calls a non-mocked method that throws, instantiate the real class; it has a single executor and is cheap.
- **Risk:** LOW

## 18. `setMetaStoreWriter(MetaStoreWriter)`

- **Type:** `com.linkedin.venice.system.store.MetaStoreWriter`
- **Classification:** `NOOP`
- **Justification:** Meta system store is for cross-cluster metadata propagation. The harness has no controller,
  so meta-store does not exist. `KafkaStoreIngestionService:415` shows the production code itself sets this to
  `null` when meta-system-store is disabled, so the SIT is null-tolerant on this path. We pass `null`.
- **Source-of-truth:** Literal `null`.
- **Risk:** LOW

## 19. `setCompressorFactory(StorageEngineBackedCompressorFactory)`

- **Type:** `com.linkedin.davinci.compression.StorageEngineBackedCompressorFactory`
- **Classification:** `REAL`
- **Justification:** SIT decompresses incoming records (if compression is enabled) and compresses on produce. Even
  with the `NO_OP` compression strategy, the factory is consulted on every record. Real construction is a single
  no-arg `new StorageEngineBackedCompressorFactory(storageMetadataService)`. Stubbing would risk mis-classifying
  the compression strategy.
- **Source-of-truth:** `new StorageEngineBackedCompressorFactory(storageMetadataService)`.
- **Risk:** LOW

## 20. `setPubSubContext(PubSubContext)`

- **Type:** `com.linkedin.venice.pubsub.PubSubContext`
- **Classification:** `REAL`
- **Justification:** Carries the topic-manager-repository, deserializer, position registry, and clients factory.
  These are all real production primitives required for the consumer / topic admin path. The wrapper itself is a
  builder-constructed bag; trivial to construct.
- **Source-of-truth:** `new PubSubContext.Builder().setTopicManagerRepository(...).setPubSubPositionTypeRegistry(...).
  setPubSubPositionDeserializer(...).setPubSubTopicRepository(...).setPubSubMessageDeserializer(...).
  setPubSubClientsFactory(pubSubClientsFactory).build()` mirroring `KafkaStoreIngestionService:379-386`.
- **Risk:** LOW

## 21. `setAAWCWorkLoadProcessingThreadPool(ExecutorService)`

- **Type:** `java.util.concurrent.ExecutorService`
- **Classification:** `REAL`
- **Justification:** Active-Active write-compute parallel processing pool. Required when AA-WC parallel
  processing is enabled (the benchmark enables this for throughput). Plain JDK `Executors.newFixedThreadPool(n)`
  is the production primitive.
- **Source-of-truth:** `Executors.newFixedThreadPool(serverConfig.getAAWCWorkloadParallelProcessingThreadPoolSize(),
  new DaemonThreadFactory("AA_WC_PARALLEL_PROCESSING", logContext))` mirroring `KafkaStoreIngestionService:491-493`.
- **Risk:** LOW

## 22. `setAAWCIngestionStorageLookupThreadPool(ExecutorService)`

- **Type:** `java.util.concurrent.ExecutorService`
- **Classification:** `REAL`
- **Justification:** Same as #21; companion pool for storage lookups during AA-WC ingestion. Always required
  (no enabled flag — production code unconditionally creates it).
- **Source-of-truth:** `Executors.newFixedThreadPool(serverConfig.getAaWCIngestionStorageLookupThreadPoolSize(),
  new DaemonThreadFactory("AA_WC_INGESTION_STORAGE_LOOKUP", logContext))` mirroring `KafkaStoreIngestionService:508-510`.
- **Risk:** LOW

## 23. `setReusableObjectsSupplier(Supplier<IngestionTaskReusableObjects>)`

- **Type:** `java.util.function.Supplier<com.linkedin.davinci.ingestion.utils.IngestionTaskReusableObjects>`
- **Classification:** `REAL`
- **Justification:** Per-thread allocation-reuse buffer; required for SIT's thread-local optimizations. Strategy
  is selected by an enum on `VeniceServerConfig`. We use the production `SHARED_PER_TASK` (default) supplier.
- **Source-of-truth:** `serverConfig.getIngestionTaskReusableObjectsStrategy().supplier()` mirroring
  `KafkaStoreIngestionService:519-520`.
- **Risk:** LOW

## 24. `setBlobTransferManagerSupplier(Supplier<BlobTransferManager>)`

- **Type:** `java.util.function.Supplier<com.linkedin.davinci.blobtransfer.BlobTransferManager>`
- **Classification:** `NOOP`
- **Justification:** Blob transfer is for hydrating new replicas from peer hosts (a Helix-coordinated optimization).
  In the lean harness with no peer hosts, blob transfer is unused. Production code at `StoreIngestionTaskFactory.java:343`
  null-tolerates: `blobTransferManagerSupplier != null ? blobTransferManagerSupplier.get() : null`. We pass a
  supplier returning `null` (or simply don't call this setter).
- **Source-of-truth:** `() -> null` supplier, or skip the setter entirely.
- **Risk:** LOW

## 25. `setBlobTransferDisabledStores(Set<String>)`

- **Type:** `java.util.Set<String>`
- **Classification:** `NOOP`
- **Justification:** A live-checked allowlist of stores for which blob transfer is disabled. With blob transfer
  itself absent (#24), this set is consulted by `BlobTransferIngestionHelper` which is only constructed when the
  manager is non-null. An empty `Collections.emptySet()` is safe.
- **Source-of-truth:** `Collections.emptySet()`.
- **Risk:** LOW

## 26. (Helper) `getBlobTransferHelper(StorageService)`

- **Type:** `BlobTransferIngestionHelper` (returns null-or-real lazily)
- **Classification:** Not a setter — derived. Returns `null` when blob transfer manager is null (#24).
- **Justification:** Listed for completeness; no setter to call.
- **Source-of-truth:** N/A.
- **Risk:** N/A

---

## Setters NOT called by `KafkaStoreIngestionService` (production wiring) — important caveat

Cross-referencing `KafkaStoreIngestionService:522-548` shows the production code calls **27 setters** in
total. Those are:

```
1.  setPubSubContext                            (entry #20)
2.  setVeniceWriterFactory                      (entry #1)
3.  setStorageMetadataService                   (entry #4)
4.  setLeaderFollowerNotifiersQueue             (entry #5)
5.  setSchemaRepository                         (entry #6)
6.  setMetadataRepository                       (entry #7)
7.  setHostLevelIngestionStats                  (entry #8)
8.  setVersionedDIVStats                        (entry #9)
9.  setVersionedIngestionStats                  (entry #10)
10. setStoreBufferService                       (entry #11)
11. setServerConfig                             (entry #12)
12. setDiskUsage                                (entry #13)
13. setAggKafkaConsumerService                  (entry #14)
14. setPartitionStateSerializer                 (entry #15)
15. setIsDaVinciClient                          (entry #16)
16. setRemoteIngestionRepairService             (entry #17)
17. setMetaStoreWriter                          (entry #18)
18. setCompressorFactory                        (entry #19)
19. setVeniceViewWriterFactory                  (entry #3)
20. setHeartbeatMonitoringService               (entry #2)
21. setAAWCWorkLoadProcessingThreadPool         (entry #21)
22. setAAWCIngestionStorageLookupThreadPool     (entry #22)
23. setReusableObjectsSupplier                  (entry #23)
24. setBlobTransferManagerSupplier              (entry #24)
25. setBlobTransferDisabledStores               (entry #25)
```

That is **25 setters** called by production. Two additional setters exist on the Builder but are NOT called
by `KafkaStoreIngestionService`:

## 27. (Bonus) Private fields with no setter (must construct via reflection or accept default)

The Builder has a private field `blobTransferHelper` (lazy) and `built` (state). Neither has a setter; both
are managed internally. No action needed.

---

## Summary table

| # | Setter | Class | Risk |
|---|---|---|---|
| 1 | setVeniceWriterFactory | REAL | LOW |
| 2 | setHeartbeatMonitoringService | NOOP | LOW |
| 3 | setVeniceViewWriterFactory | REAL | LOW |
| 4 | setStorageMetadataService | REAL | LOW |
| 5 | setLeaderFollowerNotifiersQueue | NOOP | LOW |
| 6 | setSchemaRepository | STUB | MEDIUM |
| 7 | setMetadataRepository | STUB | MEDIUM |
| 8 | setHostLevelIngestionStats | REAL | LOW |
| 9 | setVersionedDIVStats | REAL | LOW |
| 10 | setVersionedIngestionStats | REAL | LOW |
| 11 | setStoreBufferService | REAL | LOW |
| 12 | setServerConfig | REAL | MEDIUM |
| 13 | setDiskUsage | REAL | LOW |
| 14 | setAggKafkaConsumerService | REAL | MEDIUM |
| 15 | setPartitionStateSerializer | REAL | LOW |
| 16 | setIsDaVinciClient | REAL (false) | LOW |
| 17 | setRemoteIngestionRepairService | NOOP | LOW |
| 18 | setMetaStoreWriter | NOOP (null) | LOW |
| 19 | setCompressorFactory | REAL | LOW |
| 20 | setPubSubContext | REAL | LOW |
| 21 | setAAWCWorkLoadProcessingThreadPool | REAL | LOW |
| 22 | setAAWCIngestionStorageLookupThreadPool | REAL | LOW |
| 23 | setReusableObjectsSupplier | REAL | LOW |
| 24 | setBlobTransferManagerSupplier | NOOP | LOW |
| 25 | setBlobTransferDisabledStores | NOOP | LOW |

## Risk roll-up

- **0 HIGH-risk** dependencies. (None of the 25 setters appear at this stage to need genuine controller / Helix
  coordination at the SIT layer.)
- **4 MEDIUM-risk** dependencies: `setSchemaRepository`, `setMetadataRepository`, `setServerConfig`,
  `setAggKafkaConsumerService`. These warrant focused attention in Phases 1, 2, 4 because they have wide
  surface area and missing-field bugs would manifest as silent NPEs or wrong defaults rather than loud failures.
- **21 LOW-risk** dependencies: cleanly classifiable as `REAL`, `NOOP`, or `MOCK`.

## Notes on `ActiveActiveStoreIngestionTask` constructor argument (`zkHelixAdmin`)

Note that `getNewIngestionTask(...)` takes one extra argument that is *not* on the Builder:
`Lazy<ZKHelixAdmin> zkHelixAdmin`. Per Risk #2 of GOAL.md, this is the most likely Helix-coupling escape hatch.
- **Plan:** pass `Lazy.of(() -> Mockito.mock(ZKHelixAdmin.class, RETURNS_DEEP_STUBS))`. The lazy supplier is
  only resolved when SIT actually calls `helixAdmin.xxx()`. If during Phase 4 smoke testing the mock is invoked
  on a method that returns a non-trivial value, we will switch to selective stubbing per call site.
- **Risk:** MEDIUM, but only surfaces in Phase 4 — not part of this dep-graph audit.

## Notes on `getNewIngestionTask` other args (resolved at task instantiation, not on Builder)

For completeness, the per-task arguments are:
- `StorageService` — REAL, opened by harness.
- `Store` — REAL, served by the in-memory metadata repo (#7); we hold a reference for direct passing.
- `Version` — REAL, ditto.
- `Properties kafkaConsumerProperties` — REAL, built via `getCommonKafkaConsumerProperties(serverConfig)` mirror.
- `BooleanSupplier isCurrentVersion` — `() -> true` (the only version is current).
- `VeniceStoreVersionConfig storeConfig` — REAL, constructed via `VeniceStoreVersionConfig(topicName, veniceProperties)`.
- `int partitionId` — literal partition number (0 or 1).
- `Optional<ObjectCacheBackend> cacheBackend` — `Optional.empty()` (no DaVinci object cache).
- `InternalDaVinciRecordTransformerConfig internalRecordTransformerConfig` — `null` (no transformer).
- `Lazy<ZKHelixAdmin> zkHelixAdmin` — `Lazy.of(() -> Mockito.mock(...))` per above.

These are not Builder setters but are required for instantiating each `ActiveActiveStoreIngestionTask` and
will be addressed in Phase 4.
