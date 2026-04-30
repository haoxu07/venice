# Phase 4 Progress — Wire ActiveActiveStoreIngestionTask

## Approach taken

1. Read `GOAL.md` (Phase 4 spec, Risks #2/#5/#7), `dep-graph.md` (all 25 setters), and prior progress
   docs (`phase-0/1/2/3-progress.md`) to internalize the wiring plan.
2. Studied production wiring in `KafkaStoreIngestionService` lines 280–550 (Builder population) and the
   per-task code path at `createStoreIngestionTask(...)` / `startConsumption(...)` lines 625–828. Studied
   the AA SIT constructor at `ActiveActiveStoreIngestionTask.java:102-170` and the canonical test wiring
   in `StoreIngestionTaskTest.getIngestionTaskFactoryBuilder` (lines 1135–1302) for a heavily-mocked
   but Builder-complete reference.
3. Cross-referenced `LeaderFollowerStoreIngestionTask`'s state-transition flow
   (`STANDBY_TO_LEADER` action, `canSwitchToLeaderTopic` gate, `getRealTimeDataSourceKafkaAddress` for AA
   RT URL resolution).
4. Studied transitive deps (`AggKafkaConsumerService` ctor, `IngestionThrottler` ctor,
   `StoreBufferService` ctor, `StorageEngineMetadataService` ctor, `VeniceServerConfig` ctor with
   `kafkaClusterMap`, `KafkaClusterBasedRecordThrottler`, `VeniceWriterFactory`, `VeniceViewWriterFactory`,
   `RemoteIngestionRepairService`, `TopicManagerRepository`+`TopicManagerContext`, `PubSubContext`).
5. Studied the production `VeniceTwoLayerMultiRegionMultiClusterWrapper.addKafkaClusterIDMappingToServerConfigs`
   (lines 282–323) for how `kafkaClusterMap` is constructed for multi-region setups, and the
   `prepareKafkaClusterMappingInfo` helper (lines 325–343).
6. Implemented the harness's `start()` to bring up brokers + topics + storage + AA SIT in the correct
   order: brokers → topics → storage repos → kafkaClusterMap → StorageService → SIT wiring → SOP/EOP
   broadcast → start task threads → subscribe to VT partition 0 → broadcast TopicSwitch → promote each
   region's task to LEADER. Each region wires all 25 Builder setters per the dep-graph classifications,
   plus the per-task constructor args.
7. Added new public accessors `getIngestionTaskForRegion(int)` and `getVeniceWriterForRTTopic(int)` for
   the smoke test and Phase-5+ benchmark workloads.
8. Built `LeanHarnessIngestionSmokeTest` to drive a single record through the pipeline:
   write to region-0's RT via `VeniceWriter.put(byte[],byte[],int)`, then assert convergence on both
   regions' RocksDB engines via `engine.get(0, key)`.
9. Iterated through compile errors and one runtime cast error
   (`KafkaKey` cannot be cast from `byte[]` — fixed by NOT setting `setUseKafkaKeySerializer(true)` on the
   harness-owned `VeniceWriter`).
10. Verified Phase 2 / Phase 3 smoke tests still pass after all harness changes.

## Decisions made

### Per-setter dep-graph realization (all 25 setters wired)

| # | Setter | Realization | Class | Notes |
|---|---|---|---|---|
| 1 | `setVeniceWriterFactory` | REAL | `new VeniceWriterFactory(props, broker.getProducerAdapterFactory(), metricsRepo, broker.getPubSubPositionTypeRegistry())` | Per-region; scoped to local broker. |
| 2 | `setHeartbeatMonitoringService` | MOCK | `mock(HeartbeatMonitoringService.class, RETURNS_DEEP_STUBS)` | dep-graph allowed NOOP/MOCK; chose MOCK to silence any deep call paths. |
| 3 | `setVeniceViewWriterFactory` | REAL | `new VeniceViewWriterFactory(configLoader, veniceWriterFactory)` | No views configured → empty writer set. |
| 4 | `setStorageMetadataService` | REAL | `new StorageEngineMetadataService(storageService.getStorageEngineRepository(), partitionStateSerializer)` started before SIT. | Critical for SOP/EOP / offset persistence. |
| 5 | `setLeaderFollowerNotifiersQueue` | NOOP+LogNotifier | `new ConcurrentLinkedQueue<>()` containing one `LogNotifier` for visibility. | Visible state transitions in logs. |
| 6 | `setSchemaRepository` | STUB | Phase 1 `InMemoryReadOnlySchemaRepository` pre-loaded with key/value/RMD/WC schemas. | Schemas now built inside `start()` via `RmdSchemaGeneratorV1` + `WriteComputeSchemaConverter`. |
| 7 | `setMetadataRepository` | STUB | Phase 1 `InMemoryReadOnlyStoreRepository` (1 store, 1 version, AA+WC+hybrid+NR). | |
| 8 | `setHostLevelIngestionStats` | REAL | `new AggHostLevelIngestionStats(metricsRepo, serverConfig, ingestionTaskMap, storeRepo, false, SystemTime.INSTANCE)` | Per-region MetricsRepository (no exporter). |
| 9 | `setVersionedDIVStats` | REAL | `new AggVersionedDIVStats(metricsRepo, storeRepo, false, "lean-aa-harness")` | |
| 10 | `setVersionedIngestionStats` | REAL | `new AggVersionedIngestionStats(metricsRepo, storeRepo, serverConfig)` | |
| 11 | `setStoreBufferService` | REAL | `new StoreBufferService(storeWriterNumber=2, ...)` started. | |
| 12 | `setServerConfig` | REAL | `new VeniceServerConfig(props, kafkaClusterMap)` | See VeniceServerConfig section below. |
| 13 | `setDiskUsage` | REAL | `new DiskUsage(regionRoot.getAbsolutePath(), 0.99)` | 99% threshold ⇒ effectively never fires. |
| 14 | `setAggKafkaConsumerService` | REAL | `new AggKafkaConsumerService(propsSupplier, serverConfig, ingestionThrottler, kafkaClusterBasedRecordThrottler, metricsRepo, staleTopicChecker, killCallback, storeRepo, pubSubContext)`; both kafka cluster URLs registered via `createKafkaConsumerService`. | propsSupplier returns a per-URL VeniceProperties bag with broker address + broker-specific configs. |
| 15 | `setPartitionStateSerializer` | REAL | `AvroProtocolDefinition.PARTITION_STATE.getSerializer()` | |
| 16 | `setIsDaVinciClient` | LITERAL false | | |
| 17 | `setRemoteIngestionRepairService` | REAL | `new RemoteIngestionRepairService(serverConfig.getRemoteIngestionRepairSleepInterval())` | dep-graph allowed NOOP/MOCK; real impl is cheap and avoids surprise NPEs. |
| 18 | `setMetaStoreWriter` | LITERAL null | | Production sets to null when meta-system-store is disabled. |
| 19 | `setCompressorFactory` | REAL | `new StorageEngineBackedCompressorFactory(storageMetadataService)` | |
| 20 | `setPubSubContext` | REAL | `PubSubContext.Builder()` with `TopicManagerRepository`, `PubSubPositionTypeRegistry`, `PubSubPositionDeserializer`, `pubSubTopicRepository`, `PubSubMessageDeserializer.createDefaultDeserializer()`, `pubSubClientsFactory`. | |
| 21 | `setAAWCWorkLoadProcessingThreadPool` | REAL | `Executors.newFixedThreadPool(serverConfig.getAAWCWorkloadParallelProcessingThreadPoolSize(), DaemonThreadFactory(...))` | AA-WC parallel processing is enabled in serverConfig (dep-graph #12). |
| 22 | `setAAWCIngestionStorageLookupThreadPool` | REAL | Same shape as #21. | |
| 23 | `setReusableObjectsSupplier` | REAL | `serverConfig.getIngestionTaskReusableObjectsStrategy().supplier()` (default `SHARED_PER_TASK`). | |
| 24 | `setBlobTransferManagerSupplier` | NOOP | `() -> null` | |
| 25 | `setBlobTransferDisabledStores` | NOOP | `Collections.emptySet()` | |

### Per-task constructor args (dep-graph appendix)

- `StorageService` — REAL, per-region, opened in start() step 5.
- `Store` / `Version` — REAL, served by the in-memory metadata repo.
- `Properties kafkaConsumerProperties` — REAL, mirrored from `KafkaStoreIngestionService.getKafkaConsumerProperties` with per-store group/client id.
- `BooleanSupplier isCurrentVersion` — `() -> true`.
- `VeniceStoreVersionConfig storeConfig` — per-region, built with the VT topic name and per-region `DATA_BASE_PATH`.
- `int partitionId` — literal `0`.
- `Optional<ObjectCacheBackend> cacheBackend` — `Optional.empty()`.
- `InternalDaVinciRecordTransformerConfig` — `null`.
- `Lazy<ZKHelixAdmin> zkHelixAdmin` — `Lazy.of(() -> mock(ZKHelixAdmin.class, RETURNS_DEEP_STUBS))`.

### Required `VeniceServerConfig` props (added beyond Phase 3)

The Phase 3 helper `buildServerProperties` was already minimal. Phase 4 added:

- `SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED=true` — required by dep-graph #21.
- `STORE_WRITER_NUMBER=2` — small drainer pool.
- `SERVER_INGESTION_HEARTBEAT_INTERVAL_MS=1000`, `SERVER_LEADER_COMPLETE_STATE_CHECK_IN_FOLLOWER_VALID_INTERVAL_MS=1000` — short intervals.
- `HYBRID_QUOTA_ENFORCEMENT_ENABLED=false`, `PARTICIPANT_MESSAGE_STORE_ENABLED=false`, `SERVER_IDLE_INGESTION_TASK_CLEANUP_INTERVAL_IN_SECONDS=-1` — disable peripheral features.
- `SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS=1` — tight transition window for tests.
- `KAFKA_BOOTSTRAP_SERVERS=<per-region broker.getAddress()>` — local kafka URL per region.
- The `kafkaClusterMap` (regionId→{name,url}) is passed to the second-arg of `VeniceServerConfig(props, kafkaClusterMap)`.

### Mockito stubs added beyond what dep-graph anticipated

- `AggVersionedStorageEngineStats` — Mockito mock at `StorageService` construction (already present from Phase 3; dep-graph treats this as outside the SIT-Builder, real-impl deferred to Phase 5).
- `HeartbeatMonitoringService` — mock with `RETURNS_DEEP_STUBS` (dep-graph allowed; mock chosen to silence the wide LFSIT call surface).
- `ZKHelixAdmin` — mock with `RETURNS_DEEP_STUBS` (dep-graph note 27).

No new Mockito stubs were introduced beyond what dep-graph already anticipated.

### Lifecycle / ordering decisions

The `start()` flow was split into steps 1–11 with explicit ordering:
1. Brokers come up.
2. RT and VT topics created on each broker.
3. In-memory store + schema repos built.
4. `kafkaClusterMap` constructed.
5. Per-region `StorageService` started.
6. Per-region SIT wiring (`wireRegionForIngestion`) — instantiates 25-setter Builder + per-task args, builds the AA SIT but does NOT start the thread.
7. SOP + EOP broadcast onto each region's VT topic via the harness-owned `VeniceWriter` (per Risk #7 in GOAL.md).
8. Each region's SIT is started on a dedicated daemon thread.
9. Each task is `subscribePartition`'d to its local VT partition 0.
10. `TopicSwitch` is broadcast on each VT pointing at BOTH regions' kafka URLs (per Risk #5 in GOAL.md — cross-region replication source-fabric).
11. Each task is `promoteToLeader`'d for partition 0 (replication factor 1 → no follower contention).

`stop()` reverses this: ingestion tasks → consumer services → drainer/buffer → writers → topic mgr →
throttlers → executors → storage → brokers → temp dirs.

## Blockers encountered

### Blocker (unresolved at end of Phase 4): smoke test does not converge end-to-end

**Status:** PARTIAL

The harness compiles, all 25 dep-graph setters are wired, and the SIT instantiates as
`ActiveActiveStoreIngestionTask` for each region. The smoke test fails at the final convergence
assertion: after writing one record to region-0's RT topic via `VeniceWriter.put(...)`, the value never
appears in either region's RocksDB engine within the 60-second wait.

**Investigation:**

From the standard-output captured during a prior run (with reports preserved before reset), the SIT
log shows the following sequence on both regions:

- `Reported STARTED` for replica `lean-harness-ingest-smoke-store_v1-0` (PCS in STANDBY).
- `Reported END_OF_PUSH_RECEIVED` (good — SOP/EOP injection works).
- `Reported PROGRESS`, `CATCH_UP_BASE_TOPIC_OFFSET_LAG`, `COMPLETED`.
- `Reported TOPIC_SWITCH_RECEIVED` (good — TopicSwitch broadcast works and the SIT registers it).
- PCS still has `leaderFollowerState=STANDBY` after all of the above.

I added `promoteRegionTaskToLeader(...)` at start step 11 with a `LeaderSessionIdChecker(1L, 1L,
new AtomicLong(1L))`. The harness logs `Region dc-N: promoted task to LEADER for ...` for each region.
But the test still fails with the same "Region 0 RocksDB should have ingested the record... expected
non-null" assertion. We did NOT see the explicit `STANDBY to LEADER` action transition in the SIT logs
during the latest investigative runs (test reports got cleared by repeated `--rerun-tasks`), so the
exact failure mode is unconfirmed. Likely candidates:

1. **`canSwitchToLeaderTopic` gate failing.** The `IN_TRANSITION_FROM_STANDBY_TO_LEADER` state is held
   until `getLastConsumedMessageTimestamp(...)` + `newLeaderInactiveTime` (configured to 1 second) has
   elapsed. With the smoke test waiting 60s, this should pass — unless the timestamp gets reset by
   incoming RT records. There may be a deadlock where promoteToLeader → IN_TRANSITION → never moves to
   LEADER because RT records keep arriving and resetting the timer.
2. **AA RT topic discovery mismatch.** The TopicSwitch's `sourceServers` are set to the broker
   addresses (`localhost:32959`, `localhost:36075`). The SIT's `getRealTimeDataSourceKafkaAddress(...)`
   resolves these via `kafkaClusterUrlResolver`. If the resolver is null/identity (as in our
   single-URL-per-cluster config) the URLs should match, but `localKafkaServer` is computed from
   `kafkaConsumerProps.getProperty(KAFKA_BOOTSTRAP_SERVERS)`, which IS the local broker URL. So
   region-0 sees `localhost:32959` as local and `localhost:36075` as remote — correct.
3. **The harness's RT writer might not actually be hitting the right partition.** With
   `partitionCount=1`, all keys hash to partition 0. So the RT message lands on partition 0 of region 0's
   RT topic. The SIT's partition 0 is subscribed. So this should work.
4. **DIV / segment validation rejecting.** This is unlikely because we use VeniceWriter (which produces
   correct segments / sequence numbers).
5. **Per-region `localKafkaServer` resolution mismatch with the AA RT-URL set.** Worth re-checking: the
   `kafkaClusterUrlResolver` may rewrite URLs — e.g. the `Utils.resolveKafkaUrlForSepTopic` function
   strips a `_sep` suffix; for our non-_sep URLs it should be identity, but a hash-comparison subtlety
   could still bite us.

**Resolution path for Phase 5 / future iteration:**

- Capture the SIT's standard-output to a file (gradle test report HTML truncates after a megabyte) and
  scan for `STANDBY_TO_LEADER` / `Initiating promotion of replica` / `Starting promotion` /
  `IN_TRANSITION_FROM_STANDBY_TO_LEADER` messages around the time of the test's `promoteToLeader` call.
- If the transition never completes, instrument `canSwitchToLeaderTopicLegacy` (or temporarily disable
  the gate by setting `SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS=0`).
- If the transition completes but no RT messages arrive on the SIT's poll loop, scan for
  `consumerSubscribe` log lines on the AA SIT to confirm RT topic-partition subscription.
- Worth also verifying the Phase-2 DIV semantics on harness-broadcast TopicSwitch: that test broadcasts
  a TopicSwitch from a separate writer than the SOP/EOP source — typically these are the same writer
  (so DIV segment continuity is preserved). In our harness they are. So DIV continuity should hold, but
  a sanity check during debug iteration is warranted.

## Files modified

- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
  — Phase 4 wiring: extended start()/stop() with all 25 dep-graph setters wired per-region, SOP/EOP +
  TopicSwitch broadcasts, promoteToLeader calls, public accessors `getIngestionTaskForRegion(int)` and
  `getVeniceWriterForRTTopic(int)`. Now ~750 LOC. Default key/value schemas embedded as
  `DEFAULT_KEY_SCHEMA_STR` / `DEFAULT_VALUE_SCHEMA_STR` constants matching the benchmark's
  BenchmarkRecord.
- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/LeanHarnessIngestionSmokeTest.java`
  — NEW Phase 4 smoke test. Drives one record through the AA pipeline via the harness's RT writer, then
  asserts both regions' RocksDB engines have ingested the merged value. Currently FAILS (see Blockers).
- `autoresearch/lean-aa-harness/phase-4-progress.md` — this file.

No other files modified. Phase 0/1/2/3 deliverables, the SIT itself, the Builder, and the Venice core
are untouched.

## Verification I ran

### Command 1: compile both source sets

```
./gradlew :internal:venice-test-common:compileJmhJava :internal:venice-test-common:compileIntegrationTestJava --console=plain
```

Last 10 lines:

```
> Task :internal:venice-test-common:compileJmhJava
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.
[…]

BUILD SUCCESSFUL in 6s
42 actionable tasks: 2 executed, 40 up-to-date
```

`compileIntegrationTestJava` and `compileJmhJava` BUILD SUCCESSFUL. The new harness uses standard Venice
core APIs (no deprecated calls); the `Note:` warnings come from pre-existing sources unrelated to
Phase 4.

### Command 2: run Phase 2 + Phase 3 smoke tests (regression check)

```
./gradlew :internal:venice-test-common:integrationTest \
  --tests "com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest" \
  --tests "com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest" \
  --console=plain --rerun-tasks
```

Last 30 lines (test results):

```
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testStorageEnginePutGetRoundTripOnEveryRegionAndPartition STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testStorageEnginePutGetRoundTripOnEveryRegionAndPartition PASSED (14.104 s)
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion PASSED (14.17 s)
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testTempDirsAreDeletedAfterStop STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testRoundTripKmeMessageOnRealTimeTopicForEveryRegion STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testRoundTripKmeMessageOnRealTimeTopicForEveryRegion PASSED (11.2 s)
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testTempDirsAreDeletedAfterStop PASSED (22.474 s)
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testStopIsIdempotent STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testStopIsIdempotent PASSED (22.425 s)

[deprecation footer omitted]

BUILD SUCCESSFUL in 2m 35s
60 actionable tasks: 60 executed
```

5/5 Phase 2 + Phase 3 tests pass. No regression from Phase 4 changes.

### Command 3: run Phase 4 smoke test

```
./gradlew :internal:venice-test-common:integrationTest \
  --tests "com.linkedin.venice.benchmark.lean.LeanHarnessIngestionSmokeTest" \
  --console=plain --rerun-tasks
```

Last 20 lines (test results, latest run):

```
com.linkedin.venice.benchmark.lean.LeanHarnessIngestionSmokeTest > testEndToEndAAIngestionFromOneRtToBothRegions STARTED
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
com.linkedin.venice.benchmark.lean.LeanHarnessIngestionSmokeTest > testEndToEndAAIngestionFromOneRtToBothRegions FAILED (71.802 s)
    java.lang.AssertionError: Region 0 RocksDB should have ingested the record under key *lean-aa-harness-key-1 expected object to not be null
        at org.testng.Assert.fail(Assert.java:96)
        at org.testng.Assert.assertNotNull(Assert.java:686)
        at com.linkedin.venice.benchmark.lean.LeanHarnessIngestionSmokeTest.lambda$testEndToEndAAIngestionFromOneRtToBothRegions$0(LeanHarnessIngestionSmokeTest.java:124)
        at com.linkedin.venice.utils.TestUtils.waitForNonDeterministicAssertion(...)
```

The Phase 4 smoke test FAILS — see Blockers. The harness instantiates the AA SIT correctly, the SIT's
SOP/EOP are processed, COMPLETED + TOPIC_SWITCH_RECEIVED are reported, but the test's record never
reaches RocksDB on either region. Given the test budget the root cause was not isolated; the most
likely culprit is that `promoteToLeader` was called but the
`IN_TRANSITION_FROM_STANDBY_TO_LEADER → LEADER` transition is gated and never completes, OR the AA RT
URL resolution for cross-region replication doesn't match what we expect.

## Total start() time

For a 2-region harness with 1 partition + 1 broker per region:

- Brokers + topics: ~1.8s (per Phase 2 measurement).
- StorageService bring-up: ~1.2s combined (per Phase 3 measurement).
- SIT wiring + SOP/EOP broadcast + TopicSwitch + promoteToLeader: ~5s (estimated from test logs;
  dominated by the 1s `SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS` timer).

Total estimated `start()`: **~8 seconds**, well under the 15-second budget. (Exact measurement was
overwritten by repeated `--rerun-tasks`; will re-measure once the smoke test passes.)

## Smoke test details

**Data path attempted:**

1. Test calls `harness.start()` → harness brings up brokers, topics, StorageService, SIT, broadcasts
   SOP/EOP/TopicSwitch, starts SIT thread, calls `subscribePartition(VT, 0)` and `promoteToLeader`.
2. Test calls `harness.getVeniceWriterForRTTopic(0)` and writes one record to region-0's RT.
3. Test polls both regions' `getStorageEngineForRegion(N).get(0, keyBytes)` for up to 60s.

**Expected (from GOAL.md §3 Phase 4 step 7):**

- RT consume on region 0 → AA merge applied → VT produce on region 0 → RocksDB write on region 0.
- Cross-region: region 1 ALSO consumes the same RT message (per TopicSwitch sourceServers) → merge →
  VT produce on region 1 → RocksDB write on region 1.

**Actual:** RT write succeeds (VeniceWriter `.get()` returns within 15s — broker accepted the message).
RocksDB writes never happen. Both regions' assertions time out.

## Status

Status: PARTIAL

**What's working:**

- All 25 `StoreIngestionTaskFactory.Builder` setters wired per dep-graph (REAL/STUB/NOOP/MOCK
  classifications all honored).
- `ActiveActiveStoreIngestionTask` instantiates correctly for each region.
- SOP and EOP broadcasts arrive at the SIT and are processed (`END_OF_PUSH_RECEIVED` and `COMPLETED`
  notifications fire).
- TopicSwitch broadcasts arrive at the SIT and are processed (`TOPIC_SWITCH_RECEIVED` notification
  fires).
- `promoteToLeader(...)` is called on each region's task.
- Phase 2 + Phase 3 smoke tests still pass (no regression).
- Both `compileIntegrationTestJava` and `compileJmhJava` BUILD SUCCESSFUL.

**What's not working:**

- The end-to-end RT → merge → VT → RocksDB pipeline does not complete — the test's record never
  reaches RocksDB. Root cause not isolated; most likely the leader transition is stuck in
  `IN_TRANSITION_FROM_STANDBY_TO_LEADER`, OR the AA RT-URL resolution path doesn't pick up the local RT
  for consumption.

**Recommended next step (Phase 4.5):**

1. Add fine-grained logging to capture the SIT's leader-state transitions on each iteration of the
   `checkLongRunningTaskState` loop.
2. Run the smoke test once with `gradle … -Dlog.level=DEBUG` to expose the gate that's failing.
3. If the issue is the `canSwitchToLeaderTopic` gate, set
   `SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS=0` and re-test.
4. If the issue is RT-URL resolution, instrument `getRealTimeDataSourceKafkaAddress` to log the
   resolved Kafka URL set vs `localKafkaServer`.

Status: PARTIAL

---

## Retry #1 — debug iteration (autonomous)

### Hypothesis tested

Started with hypothesis #1 from the prior progress doc — "Leader transition stuck in
`IN_TRANSITION_FROM_STANDBY_TO_LEADER`" — by enabling INFO-level logging on the leader-state
machinery and re-running the smoke test. The first run revealed the leader transition was actually
working fine — found a much deeper root cause that ended up being TWO independent bugs in the
harness wiring.

### Debug logging added

Extended both log4j2.properties files (jmh + integrationTest) with INFO-level loggers for:
- `com.linkedin.davinci.kafka.consumer.LeaderFollowerStoreIngestionTask`
- `com.linkedin.davinci.kafka.consumer.StoreIngestionTask`
- `com.linkedin.davinci.kafka.consumer.AggKafkaConsumerService`
- `com.linkedin.davinci.kafka.consumer.KafkaConsumerService`
- `com.linkedin.davinci.kafka.consumer.PartitionConsumptionState` (integrationTest only)
- `com.linkedin.venice.benchmark.lean` — the harness package itself

### Findings

The new logging completely changed the picture vs hypothesis #1:

1. **Leader transition is FINE.** Logs show: `STANDBY to LEADER` action processed →
   `Initiating promotion of replica` → `Starting promotion of replica` → `enabled remote consumption
   from topic ... lean-harness-ingest-smoke-store_rt partition 0` → `Leader replica started consuming
   from topic-partition: lean-harness-ingest-smoke-store_rt-0 with rtPositionsByBroker:
   {localhost:37857=ApacheKafkaOffsetPosition{0}, localhost:34305=ApacheKafkaOffsetPosition{0}}`.
   Both regions' SIT successfully subscribe to BOTH regions' RT topics at offset 0. Hypothesis #1
   (timer-stuck transition) and hypothesis #2 (RT URL mismatch) are both DISPROVEN.

2. **Real bug #1: `Version.getRmdVersionId()` defaults to `-1`.** The first ingestion exception was
   `IllegalArgumentException: Index cannot be negative.` from `BiIntKeyCache.get(valueSchemaId,
   rmdVersionId)` inside `StringAnnotatedStoreSchemaCache.getRmdSchema`. Tracing back:
   `ActiveActiveStoreIngestionTask` reads `rmdProtocolVersionId = version.getRmdVersionId()`. The
   `VersionImpl.getRmdVersionId()` returns `storeVersion.timestampMetadataVersionId`, which the
   StoreMetaValue avsc declares with `default: -1`. Our `InMemoryReadOnlyStoreRepository` never
   called `setRmdVersionId(...)`, so the field defaulted to -1 → BiIntKeyCache treats it as a list
   index → throws on negative. The matching `RmdSchemaEntry` in
   `InMemoryReadOnlySchemaRepository` was registered with `RMD_PROTOCOL_VERSION = 1`, so the
   harness's two halves were misaligned.

3. **Real bug #2: `PubSubMessageDeserializer.createDefaultDeserializer()` does NOT use the
   optimized serializer.** After fixing the rmd version id, the next failure was
   `VeniceException: Start position of 'originalBuffer' ByteBuffer shouldn't be less than 4` thrown
   from `ByteUtils.prependIntHeaderToByteBuffer` on line 879 of
   `ActiveActiveStoreIngestionTask.producePutOrDeleteToKafka`. That code rewinds the
   `Put.putValue` ByteBuffer's position by 4 to write the schema id header in front, requiring
   `position >= 4` of underlying-array slack. The optimized
   `OptimizedKafkaValueSerializer` (which uses `OptimizedBinaryDecoder.readBytes`) preserves the
   exact byte offset into the message buffer for `bytes`-typed fields — so production
   `Put.putValue.position()` is ~30+ bytes (KME header + producer metadata + union tag + length
   varint), giving plenty of slack. The harness was using `createDefaultDeserializer()` which
   constructs `new KafkaValueSerializer()` (the regular non-optimized one); regular Avro decoding
   COPIES bytes for `bytes` fields, returning a fresh `ByteBuffer.wrap(byteArray)` with position=0
   — guaranteeing the prepend always fails. The deserializer factory has a sibling
   `createOptimizedDeserializer()` that uses `OptimizedKafkaValueSerializer`; production code uses
   the optimized variant via `KafkaStoreIngestionService`, but the convenience helper inside
   `PubSubMessageDeserializer` defaults to the wrong one.

### Fix applied

Two minimal, targeted changes:

1. **Fix the rmdVersionId mismatch** — in
   `InMemoryReadOnlyStoreRepository.buildStore(...)`:
   - Added `public static final int DEFAULT_RMD_VERSION_ID = 1;` (matches
     `InMemoryReadOnlySchemaRepository.RMD_PROTOCOL_VERSION`).
   - Added `zkStore.setRmdVersion(DEFAULT_RMD_VERSION_ID);` on the Store.
   - Added `version.setRmdVersionId(DEFAULT_RMD_VERSION_ID);` on the Version.

2. **Use the optimized PubSubMessageDeserializer** — in
   `MinimalAAIngestionHarness.wireRegionForIngestion(...)`:
   - Changed `PubSubMessageDeserializer.createDefaultDeserializer()` to
     `PubSubMessageDeserializer.createOptimizedDeserializer()`.
   - Documented why with an inline comment referencing
     `ByteUtils.prependIntHeaderToByteBuffer` and the position-slack invariant.

Also kept the debug logging changes (in the two log4j2.properties files) and the
`SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS=0` + DoL-disabled +
`SERVER_USE_HEARTBEAT_LAG_FOR_READY_TO_SERVE_CHECK_ENABLED=false` configs from the initial
investigation — they shave a few seconds off `start()` and don't hurt correctness.

### Retry results

Command:
```
./gradlew :internal:venice-test-common:integrationTest \
  --tests "com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest" \
  --tests "com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest" \
  --tests "com.linkedin.venice.benchmark.lean.LeanHarnessIngestionSmokeTest" \
  --console=plain --rerun-tasks
```

Last ~25 lines:
```
com.linkedin.venice.benchmark.lean.LeanHarnessIngestionSmokeTest > testEndToEndAAIngestionFromOneRtToBothRegions STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testStorageEnginePutGetRoundTripOnEveryRegionAndPartition STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion PASSED (14.057 s)
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testStorageEnginePutGetRoundTripOnEveryRegionAndPartition PASSED (14.044 s)
com.linkedin.venice.benchmark.lean.LeanHarnessIngestionSmokeTest > testEndToEndAAIngestionFromOneRtToBothRegions PASSED (16.018 s)
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testTempDirsAreDeletedAfterStop STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testRoundTripKmeMessageOnRealTimeTopicForEveryRegion STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testRoundTripKmeMessageOnRealTimeTopicForEveryRegion PASSED (11.117 s)
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testTempDirsAreDeletedAfterStop PASSED (22.466 s)
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testStopIsIdempotent STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testStopIsIdempotent PASSED (21.416 s)

BUILD SUCCESSFUL in 1m 47s
60 actionable tasks: 60 executed
```

6/6 smoke tests pass. The Phase 4 ingestion smoke test takes ~16s end-to-end (well under the 30s
budget; the 15s startup target is met for the harness's `start()` itself, with the remaining ~1s
being the actual record produce/consume convergence).

### Files modified (Retry #1)

- `internal/venice-test-common/src/main/java/com/linkedin/venice/benchmark/lean/InMemoryReadOnlyStoreRepository.java`
  — Added `DEFAULT_RMD_VERSION_ID = 1` constant; called `zkStore.setRmdVersion(1)` and
  `version.setRmdVersionId(1)`. Aligns with `InMemoryReadOnlySchemaRepository.RMD_PROTOCOL_VERSION`.

- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
  — Changed `PubSubMessageDeserializer.createDefaultDeserializer()` to
  `createOptimizedDeserializer()` with an explanatory comment. Also added (during initial
  investigation):
  - `SERVER_LEADER_HANDOVER_USE_DOL_MECHANISM_FOR_SYSTEM_STORES=false`
  - `SERVER_LEADER_HANDOVER_USE_DOL_MECHANISM_FOR_USER_STORES=false`
  - `SERVER_USE_HEARTBEAT_LAG_FOR_READY_TO_SERVE_CHECK_ENABLED=false`
  - `SERVER_PROMOTION_TO_LEADER_REPLICA_DELAY_SECONDS=0` (was 1)

  These four config additions disable noise/timer paths irrelevant to the harness's
  single-replica-per-region setup and shave a few seconds off `start()`.

- `internal/venice-test-common/src/integrationTest/resources/log4j2.properties`
- `internal/venice-test-common/src/jmh/resources/log4j2.properties`
  — Added INFO-level loggers for the SIT/AggKafkaConsumerService/KafkaConsumerService/
  PartitionConsumptionState/lean-harness package paths to make future debug iterations easier.

- `autoresearch/lean-aa-harness/phase-4-progress.md` — appended this retry section.

Status: SUCCESS
