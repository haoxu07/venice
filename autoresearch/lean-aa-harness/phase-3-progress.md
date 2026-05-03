# Phase 3 Progress — Storage service + RocksDB

## Approach taken

1. Read `GOAL.md` (Phase 3 spec), `dep-graph.md` entry #4 (`StorageMetadataService` — RocksDB-backed, piggybacks on the
   same `StorageService`), and `phase-{0,1,2}-progress.md` to internalize the prior decisions and open-API surface.
2. Read the Phase 2 harness (`MinimalAAIngestionHarness.java`) and the Phase 2 smoke test
   (`LeanHarnessBrokerSmokeTest.java`) to confirm the wiring style I need to mirror.
3. Surveyed every `new StorageService(` construction site in the repo:
   - `services/venice-server/src/main/java/.../VeniceServer.java:342` — production wiring (6-arg ctor).
   - `clients/da-vinci-client/src/main/java/.../DaVinciBackend.java:199` — DaVinci wiring.
   - `clients/da-vinci-client/src/test/java/.../{InMemoryStorageEngineTest, RocksDBStorageEngineTest, ReplicationMetadata*Test, StorageServiceTest}`
     — test-only patterns using `AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB)` +
     `getVeniceConfigLoader`, `mock(AggVersionedStorageEngineStats.class)`, and
     `openStoreForNewPartition(storeConfig, partitionId, () -> null)`. The test patterns were the closest fit since they
     need fresh RocksDB roots and do not depend on Helix / metrics exporters.
4. Read `StorageService.java`'s three public ctors; chose the 8-arg variant
   `(VeniceConfigLoader, AggVersionedStorageEngineStats, RocksDBMemoryStats, store-version-state-serializer, partition-state-serializer, ReadOnlyStoreRepository, restoreDataPartitions, restoreMetadataPartitions)`
   so I can pass `restoreDataPartitions=false` and `restoreMetadataPartitions=false`. The harness has no pre-existing
   RocksDB data on a fresh tmp dir, so the restore path is wasted work and risks interacting with the (empty) factory
   persisted-store-names enumeration.
5. Read `AbstractVeniceService.java` to confirm the lifecycle: `start()` → `startInner()`, `stop()` → `stopInner()`,
   both synchronized and idempotent-by-construction (state machine guards double-start). Confirmed `stopInner()` closes
   the storage-engine repository and every storage-engine factory — so a clean `storageService.stop()` is sufficient
   teardown for RocksDB.
6. Confirmed `StorageEngine` has the put/get API I need: `void put(int partitionId, byte[] key, byte[] value)` and
   `byte[] get(int partitionId, byte[] key)`.
7. Confirmed `Utils.getTempDataDirectory(prefix)` returns a uniqueified `File` under the JVM's `java.io.tmpdir` and
   registers it for delete-on-exit. I still call `FileUtils.deleteDirectory` in `stop()` because Phase 3 requires
   explicit cleanup, not deferred-on-JVM-exit.
8. Implemented harness changes:
   - Added `import` for `StorageService`, `StorageEngine`, `VeniceConfigLoader`, `VeniceStoreVersionConfig`,
     `AggVersionedStorageEngineStats`, `PersistenceType`, `AvroProtocolDefinition`, `PropertyBuilder`, `FileUtils`,
     `Mockito.mock`, and the relevant `ConfigKeys` static imports.
   - Added per-region tracking lists: `regionTempDirs`, `storageServices`, `versionTopicStorageEngines`,
     `versionTopicStoreConfigs`, plus a single `InMemoryReadOnlyStoreRepository` instance shared across regions.
   - Extended `start()` with two new steps after broker/topic creation: build the in-memory store repo, and for each
     region create a temp dir → instantiate `StorageService` → `start()` it →
     `openStoreForNewPartition(vtStoreConfig, /*partitionId*/ 0, () -> null)` to bring up the engine for partition 0,
     then `engine.addStoragePartitionIfAbsent(p)` for partitions `1..N-1`.
   - Reworked `stopQuietly()` to teardown in the order: storage engines/services → brokers → temp dirs. This ordering
     matters: RocksDB must close before brokers (so RocksDB doesn't depend on broker liveness which currently it
     doesn't, but ordering is documented), and tmp dirs come last so that storage-shutdown errors are logged against
     still-extant paths. Added `deleteRecursivelyQuietly` helper using `FileUtils.deleteDirectory`.
   - Added public accessors: `getStorageServiceForRegion(int)`, `getStorageEngineForRegion(int)`,
     `getRegionTempDir(int)`, `getStoreRepository()`. The first three are guarded by `started` and bounds-checked; the
     last returns `null` until `start()`.
   - Added two private helper methods: `createRegionTempDir(regionName)` (creates
     `<tmp>/lean-harness-region-<name>-<random>/rocksdb`) and `createStorageService(regionRoot, regionName)`
     (instantiates and starts the `StorageService`). And `buildServerProperties(regionRoot)` to centralize the
     `VeniceProperties` bag (mirrors `AbstractStorageEngineTest.getServerProperties` minimally).
9. Wrote `LeanHarnessStorageSmokeTest` (TestNG) with two test methods:
   - `testStorageEnginePutGetRoundTripOnEveryRegionAndPartition` — for each region, asserts the `StorageService` is
     running, the `StorageEngine` is opened on the version topic with all configured partitions added, and a `put`/`get`
     byte-equality round-trip succeeds for every (region, partition) combination. Also asserts region isolation: a key
     written to region 0 partition 0 returns `null` when read from region 1 partition 0 (separate RocksDB roots).
   - `testTempDirsAreDeletedAfterStop` — captures the per-region temp dirs and the `rocksdb` subdir paths while running,
     asserts they exist on disk, calls `stop()`, then asserts both the per-region temp dirs and (transitively) the
     `rocksdb` subdirs are deleted.
10. Iterated through compile-only validation (compile passed first try; no further iteration needed).
11. Ran the smoke test, then re-ran the Phase 2 smoke test and the Phase 1 unit test to verify no regression. Re-ran all
    three source sets' `compile*Java` tasks.

## Decisions made

1. **Eight-arg `StorageService` ctor with `restoreDataPartitions=false, restoreMetadataPartitions=false`.** Three ctor
   variants exist (6-arg, 8-arg, 9-arg). The 6-arg variant defaults both restore flags to `true`, which would trigger
   `restoreAllStores(...)` — a costly enumerate-then-reopen pass against the empty factory persisted-store-names set.
   The 8-arg variant lets us skip that. Rationale: in Phase 3 we always start with an empty per-region temp dir, so
   there's nothing to restore. Keeping restore disabled also makes `start()` deterministically faster (and stays under
   the 5-second budget for combined StorageService startup).

2. **Per-region temp-dir layout: `<jvmTmp>/lean-harness-region-<name>-<random>/rocksdb`.** `GOAL.md` example:
   `<harness-tmp-dir>/region-0/rocksdb`. The `<random>` suffix comes from `Utils.getUniqueTempPath()` (nanoTime +
   ThreadLocalRandom), which guarantees no collision between concurrent test runs (TestNG can fork multiple JVMs). The
   `rocksdb` subdir under each region is what we feed `DATA_BASE_PATH` to `VeniceConfigLoader`. RocksDB materializes its
   files there on first open. The region-root parent (above `rocksdb`) is what gets deleted in `stop()`, so any future
   per-region siblings (offset-store, ingestion logs) get wiped together.

3. **`AggVersionedStorageEngineStats` is a Mockito mock in Phase 3.** Phase 3 does not exercise the stats path — direct
   `put`/`get` against `StorageEngine` does not invoke `aggVersionedStorageEngineStats` beyond the one-time
   `setStorageEngine` call (which the mock accepts as a no-op). Using a real `AggVersionedStorageEngineStats` would
   require constructing a `MetricsRepository` and a `VeniceServerConfig` here, both of which are Phase 4 (dep-graph
   entries #8, #12) deliverables. By mocking now and replacing with the real impl in Phase 4, we avoid duplicating
   wiring. Note: dep-graph entry #8 is "REAL" for the SIT-facing `AggHostLevelIngestionStats`, but
   `AggVersionedStorageEngineStats` is a different class consumed only by `StorageService` — its dep-graph treatment
   will be added in Phase 4. Two of the existing canonical tests (`RocksDBStorageEngineTest`,
   `ReplicationMetadataRocksDBStoragePartitionTest`) also use `mock(AggVersionedStorageEngineStats.class)`, confirming
   this is a known-safe pattern.

4. **`InMemoryReadOnlyStoreRepository` is shared across regions, not per-region.** The store-repo's only role at the
   `StorageService` level is to answer `isReplicationMetadataEnabled(topicName, ...)` for the version topic. That answer
   depends solely on the store/version's AA flag, which is the same on every region (same store, same version, same
   Phase-1 config). Sharing one instance saves construction time and (more importantly) ensures all regions see the same
   store metadata in Phase 4 when the SIT consults it.

5. **Per-region `VeniceStoreVersionConfig` (not shared).** Each region has its own `VeniceStoreVersionConfig` because
   `DATA_BASE_PATH` differs per region. The store version name (i.e. version topic name) is the same across all regions
   (single store, single version), so this is the only knob that varies. Tracked in `versionTopicStoreConfigs` so Phase
   4 can pass the same instance into the SIT factory.

6. **Cleanup uses `FileUtils.deleteDirectory(File)` (Apache Commons IO), not `Files.walk` + `Files.delete`.** The former
   is well-tested for RocksDB-style trees with deep nesting and is already on the harness's classpath via
   `internal/venice-common`'s transitive deps. The latter would require manual symlink handling and a custom
   `FileVisitor`. I do not propagate `IOException` — failure to delete a tmp dir is logged at ERROR level but does not
   throw, since `stop()` is the last line of defense and must always complete. The smoke test's
   `testTempDirsAreDeletedAfterStop` asserts post-condition explicitly, so silent failures are caught.

7. **Smoke test placed in a NEW file `LeanHarnessStorageSmokeTest`, not appended to `LeanHarnessBrokerSmokeTest`.**
   GOAL.md offered both options. A separate file:

   - keeps each test class focused on one concern (broker behavior vs storage behavior),
   - lets the storage smoke test add `BeforeMethod`/`AfterMethod` blocks specific to storage scenarios without polluting
     the broker tests,
   - mirrors the existing project convention (e.g. `*EndToEndTest`, `*SmokeTest`, `*ConsumerTest` are all separate
     classes, not amalgamated). Both classes coexist under the same package and run together via the
     `tests "com.linkedin.venice.benchmark.lean.*"` glob.

8. **No modifications to Phase 0/1/2 files.** The Phase 1 in-memory repos are untouched; the Phase 2 smoke test is
   untouched; the harness's Phase 2 surface (broker accessors, topic accessors, Config class) is preserved bit-for-bit.
   The harness's `start()` and `stopQuietly()` got new steps appended/inserted, the JavaDoc on the class header and on
   `start()` was updated to reflect Phase 3 status, and the imports list was extended. No public method was renamed or
   removed.

## Blockers encountered

None of substance. One minor course-correction:

- **Issue:** First draft attempted `new InMemoryReadOnlyStoreRepository(storeName, partitionCount, versionNumber)` — a
  3-arg ctor that does not exist on `InMemoryReadOnlyStoreRepository` (only 1-arg and 5-arg ctors are defined per Phase
  1).
- **Resolution:** Switched to the 5-arg ctor with `DEFAULT_HYBRID_REWIND_SECONDS` and
  `DEFAULT_HYBRID_OFFSET_LAG_THRESHOLD` from `InMemoryReadOnlyStoreRepository`'s public constants. Caught at the design
  phase before compile, no code thrown away.

## Files modified

- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
  — added imports for `StorageService`, `StorageEngine`, `VeniceConfigLoader`, `VeniceStoreVersionConfig`,
  `AggVersionedStorageEngineStats`, `PersistenceType`, `AvroProtocolDefinition`, `PropertyBuilder`, `FileUtils`,
  `Mockito.mock`; added 5 new private fields (`storeRepository`, `regionTempDirs`, `storageServices`,
  `versionTopicStorageEngines`, `versionTopicStoreConfigs`); extended `start()` with storage-service bring-up steps +
  per-step timing log; reworked `stopQuietly()` to tear down storage before brokers and delete temp dirs; added 4 public
  accessors (`getStorageServiceForRegion`, `getStorageEngineForRegion`, `getRegionTempDir`, `getStoreRepository`); added
  4 private helpers (`createRegionTempDir`, `createStorageService`, `buildServerProperties`,
  `deleteRecursivelyQuietly`); refreshed JavaDoc on the class and on `start()` to describe Phase 3 status.
- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/LeanHarnessStorageSmokeTest.java`
  — **new** TestNG smoke test with two test methods: `testStorageEnginePutGetRoundTripOnEveryRegionAndPartition`
  (asserts engine bring-up + put/get byte-equality on every region and partition + region isolation) and
  `testTempDirsAreDeletedAfterStop` (asserts per-region temp dirs are deleted after `stop()`).
- `autoresearch/lean-aa-harness/phase-3-progress.md` — this file.

No other files in the repository were modified. Phase 0/1/2 deliverables are untouched.

## Verification I ran

### Command 1: compile integrationTest source set

```
./gradlew :internal:venice-test-common:compileIntegrationTestJava --console=plain
```

Last 20 lines:

```
> Task :services:venice-server:compileJava UP-TO-DATE
> Task :internal:venice-test-common:compileJava UP-TO-DATE
> Task :internal:venice-common:compileTestJava UP-TO-DATE
> Task :internal:venice-common:processTestResources UP-TO-DATE
> Task :internal:venice-common:testClasses UP-TO-DATE

> Task :internal:venice-test-common:compileIntegrationTestJava
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

See https://docs.gradle.org/7.3/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 17s
39 actionable tasks: 1 executed, 38 up-to-date
```

### Command 2: cross-source-set compile (test + jmh + integrationTest)

```
./gradlew :internal:venice-test-common:compileTestJava :internal:venice-test-common:compileJmhJava :internal:venice-test-common:compileIntegrationTestJava --console=plain
```

Last 12 lines:

```
> Task :internal:venice-test-common:compileTestJava UP-TO-DATE
> Task :internal:venice-test-common:compileIntegrationTestJava UP-TO-DATE
> Task :internal:venice-test-common:processIntegrationTestResources UP-TO-DATE
> Task :internal:venice-test-common:integrationTestClasses UP-TO-DATE
> Task :internal:venice-test-common:integrationTestJar UP-TO-DATE
> Task :internal:venice-test-common:compileJmhJava UP-TO-DATE

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

See https://docs.gradle.org/7.3/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 1s
43 actionable tasks: 43 up-to-date
```

All three source sets BUILD SUCCESSFUL.

### Command 3: run the Phase 3 smoke test

```
./gradlew :internal:venice-test-common:integrationTest --tests "com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest" --console=plain
```

Last 20 lines:

```
> Task :internal:venice-test-common:integrationTest
forkEvery=0
maxParallelForks=4
jvmArgs=[-DpubSubBrokerFactory=com.linkedin.venice.integration.utils.KafkaBrokerFactory, -XX:+IgnoreUnrecognizedVMOptions, --add-opens=java.base/java.nio=ALL-UNNAMED, --add-opens=java.base/sun.nio.ch=ALL-UNNAMED, --add-opens=java.base/java.lang=ALL-UNNAMED, --add-opens=java.base/java.lang.invoke=ALL-UNNAMED, --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED, -Xms4g, -Xmx8g, -Dfile.encoding=UTF-8, -Duser.country=US, -Duser.language=en, -Duser.variant, -ea]
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8
WARNING: A terminally deprecated method in java.lang.System has been called
WARNING: System::setSecurityManager has been called by com.linkedin.venice.utils.TestUtils (file:/home/coder/Projects/venice/internal/venice-test-common/build/libs/venice-test-common.jar)
WARNING: Please consider reporting this to the maintainers of com.linkedin.venice.utils.TestUtils
WARNING: System::setSecurityManager will be removed in a future release
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testStorageEnginePutGetRoundTripOnEveryRegionAndPartition STARTED
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testStorageEnginePutGetRoundTripOnEveryRegionAndPartition PASSED (3.092 s)
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testTempDirsAreDeletedAfterStop STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testTempDirsAreDeletedAfterStop PASSED (3.53 s)

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

[deprecation footer omitted]

BUILD SUCCESSFUL in 15s
60 actionable tasks: 1 executed, 59 up-to-date
```

2/2 Phase 3 tests passed.

### Command 4: run all lean integration tests (Phase 2 + Phase 3 together — regression check)

```
./gradlew :internal:venice-test-common:integrationTest --tests "com.linkedin.venice.benchmark.lean.*" --console=plain
```

Last 20 lines:

```
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion STARTED
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
OpenJDK 64-Bit Server VM warning: Sharing is only supported for boot loader classes because bootstrap classpath has been appended
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testStorageEnginePutGetRoundTripOnEveryRegionAndPartition PASSED (3.371 s)
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion PASSED (3.445 s)
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testTempDirsAreDeletedAfterStop STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testRoundTripKmeMessageOnRealTimeTopicForEveryRegion STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testRoundTripKmeMessageOnRealTimeTopicForEveryRegion PASSED (1.211 s)
com.linkedin.venice.benchmark.lean.LeanHarnessStorageSmokeTest > testTempDirsAreDeletedAfterStop PASSED (4.506 s)
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testStopIsIdempotent STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testStopIsIdempotent PASSED (5.41 s)

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

[deprecation footer omitted]

BUILD SUCCESSFUL in 23s
60 actionable tasks: 1 executed, 59 up-to-date
```

5/5 tests passed (3 Phase 2 + 2 Phase 3). No regressions.

### Command 5: re-run Phase 1 unit tests (regression check)

```
./gradlew :internal:venice-test-common:test --tests "com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest" --console=plain
```

Last 20 lines:

```
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testRmdRecordRoundTripsViaFastAvro PASSED (40 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testStoreRepositoryExposesPhase1Configuration STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testStoreRepositoryExposesPhase1Configuration PASSED (1 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testUnknownStoreLookupsFailLoudly STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testUnknownStoreLookupsFailLoudly PASSED (2 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueRecordRoundTripsViaFastAvro STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueRecordRoundTripsViaFastAvro PASSED (3 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueSchemaIdLookupByCanonicalString STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueSchemaIdLookupByCanonicalString PASSED (1 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testWriteComputeUpdateRecordRoundTripsViaFastAvro PASSED (6 ms)

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

[deprecation footer omitted]

BUILD SUCCESSFUL in 3s
59 actionable tasks: 1 executed, 58 up-to-date
```

8/8 Phase 1 tests still pass.

### Command 6: confirm branch and tracked-files state

```
git branch --show-current
git status
```

- Branch: `haoxu07/aa-bench-jmh-improvements` (correct).
- Untracked files (all expected for Phase 0/1/2/3):
  - `autoresearch/` (the existing GOAL.md / dep-graph.md / phase-{0,1,2,3}-progress.md)
  - `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/` — Phase 2
    `MinimalAAIngestionHarness.java` (modified) + `LeanHarnessBrokerSmokeTest.java` (Phase 2) +
    `LeanHarnessStorageSmokeTest.java` (Phase 3, new).
  - `internal/venice-test-common/src/main/java/com/linkedin/venice/benchmark/` — Phase 1
    `InMemoryReadOnlySchemaRepository.java`, `InMemoryReadOnlyStoreRepository.java` (unchanged).
  - `internal/venice-test-common/src/test/java/com/linkedin/venice/benchmark/` — Phase 1 `LeanHarnessSchemaTest.java`
    (unchanged).
- No tracked-file modifications anywhere. No unrelated edits.

## Storage init timing

Captured from the harness's INFO log inside the smoke test run:

```
2026-04-30 09:42:15 - [] INFO [MinimalAAIngestionHarness] MinimalAAIngestionHarness brought up 2 StorageService instance(s) in 1215 ms
2026-04-30 09:42:15 - [] INFO [MinimalAAIngestionHarness] MinimalAAIngestionHarness start() completed in 3055 ms
[LeanHarnessStorageSmokeTest] harness.start() took 3056 ms
```

Storage-service-only startup (both regions combined): **1215 ms**, well under the 5-second budget. Total `start()`
(brokers + topics + storage): **3055 ms**, well under the 15-second goal.

Per-engine breakdown (also from the harness logs):

```
Region dc-0: time spent on creating new storage Engine for store ..._v1: 99.39 ms
Region dc-1: time spent on creating new storage Engine for store ..._v1: 35.08 ms
```

The first region pays a one-time RocksDB JNI/native-library load cost (~99 ms); the second region benefits from the
already-loaded JNI and is much cheaper (~35 ms). For Phase 4+ workloads that hit both regions, this is a one-shot cost.

## Cleanup verification

Two layers of verification:

1. **Test-level assertion** (`testTempDirsAreDeletedAfterStop`):

   ```java
   List<File> tempDirsBeforeStop = new ArrayList<>(REGION_COUNT);
   for (int region = 0; region < REGION_COUNT; region++) {
     File regionDir = harness.getRegionTempDir(region);
     assertTrue(regionDir.exists() && regionDir.isDirectory(), ...);   // exists while running
     File rocksdbRoot = new File(regionDir, "rocksdb");
     assertTrue(rocksdbRoot.exists() && rocksdbRoot.isDirectory(), ...);
     tempDirsBeforeStop.add(regionDir);
   }
   harness.stop();
   for (int region = 0; region < REGION_COUNT; region++) {
     assertFalse(tempDirsBeforeStop.get(region).exists(), ...);        // deleted after stop()
   }
   ```

   Test PASSED in 3.53 s (initial run) / 4.51 s (parallel-with-broker-test re-run).

2. **External `/tmp` survey** after the test JVM exited:

   ```
   $ ls /tmp/ | grep -E "lean-harness"
   (no output)
   ```

   Zero leaked directories — the explicit `FileUtils.deleteDirectory` in `stop()` removes everything, and
   `Utils.getTempDataDirectory`'s `forceDeleteOnExit` would have caught any survivors but didn't need to.

The cleanup is robust against test-method failures because both `LeanHarnessStorageSmokeTest` (Phase 3) and
`LeanHarnessBrokerSmokeTest` (Phase 2) use a TestNG `@AfterMethod` block that calls `harness.stop()` even on test
failure (the `if (harness != null)` guard makes it safe even if `start()` itself threw and left `harness` half-allocated
— `stopQuietly` handles partial state).

## Status

Status: SUCCESS
