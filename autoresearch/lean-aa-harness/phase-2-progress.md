# Phase 2 Progress — Real Kafka brokers + topic creation

## Approach taken

1. Read `GOAL.md` (Phase 2 spec), `dep-graph.md` entries #14 and #20 (broker/PubSub-context wiring), and
   prior progress docs (`phase-0-progress.md`, `phase-1-progress.md`) to internalize the deliverables.
2. Read the existing skeleton at
   `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
   to confirm the public API surface and the `Config` value type.
3. Studied:
   - `PubSubBrokerWrapper` (abstract base + `getAddress()` + `getPubSubClientsFactory()` +
     `getAdditionalConfig()` / `getMergeableConfigs()`).
   - `ServiceFactory.getPubSubBroker(PubSubBrokerConfigs)` — the canonical broker bootstrap entry point.
   - `PubSubAdminAdapterTest` — the canonical pattern for spinning up an admin adapter against a
     `PubSubBrokerWrapper` (build a Properties bag, wrap in `VeniceProperties`, then
     `clientsFactory.getAdminAdapterFactory().create(context)`).
   - `PubSubConsumerAdapterTest` — the canonical pattern for produce + subscribe + poll against the same
     broker, including the `PubSubMessageDeserializer.createDefaultDeserializer()` factory.
   - `PubSubAdminAdapter#listAllTopics()` and `#containsTopicWithPartitionCheck(...)` — the verification
     APIs Phase 2 requires.
   - Topic-naming utilities: `Version.composeKafkaTopic(storeName, versionNumber)` for VT and
     `Utils.composeRealTimeTopic(storeName)` for RT, exactly as `GOAL.md` calls out.
4. Made a structural decision: **relocated `MinimalAAIngestionHarness` from `src/jmh/...` to
   `src/integrationTest/...`** so the smoke test (which needs `PubSubBrokerWrapper` from `integrationTest`)
   can sit alongside the harness without any cross-source-set jiggery-pokery. The jmh source set already
   extends `integrationTestUtils` (via `jmhImplementation project(path: ':internal:venice-test-common',
   configuration: 'integrationTestUtils')`), so future Phase-5+ JMH benchmarks see the harness exactly as
   they would have if it had stayed in `src/jmh/`. See "Decisions made" §1 for the full rationale.
5. Implemented the harness's `start()` method to:
   - Loop over `config.getRegionCount()` and call `ServiceFactory.getPubSubBroker(...)` once per region,
     stamping each broker with its `regionName` (`dc-0`, `dc-1`, ... by default).
   - For each broker, build an in-process `PubSubAdminAdapter`, call `createTopic(...)` for both the RT
     topic (`Utils.composeRealTimeTopic(storeName)`) and the VT topic
     (`Version.composeKafkaTopic(storeName, versionNumber)`), with the configured partition count
     (`config.getPartitionCount()`, default 2 from Phase 1) and replication factor 1.
   - Call `admin.listAllTopics()` after creation and throw `VeniceException` if either expected topic is
     missing — the verification cannot be skipped silently.
   - Wrap the entire startup in a `try/catch` that calls `stopQuietly()` on failure so a half-allocated
     harness never leaks brokers.
6. Implemented `stop()` as idempotent: closes every broker via `Utils.closeQuietlyWithErrorLogged(...)`,
   clears the broker list, resets `started=false`. Safe to call multiple times.
7. Added public accessors `getBrokerForRegion(int)`, `getBrokerAddress(int)`, `getRealTimeTopic()`,
   `getVersionTopic()`, `getPubSubTopicRepository()` — these are the surfaces Phase 4+ will consume to
   wire the AA ingestion task and the smoke test consumes for verification.
8. Extended `Config` to carry `regionNames` (a `List<String>`, defaulted to `dc-0`, `dc-1`, ...) and
   `versionNumber` (defaulted to `1`). Added validation: regionCount > 0, partitionCount > 0, storeName
   non-empty, `regionNames.size() == regionCount`, versionNumber > 0.
9. Wrote `LeanHarnessBrokerSmokeTest` — a TestNG test in `src/integrationTest/java/.../lean/` with three
   tests:
   - `testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion` — calls `start()`, then for each region opens
     a fresh `PubSubAdminAdapter` and asserts `listAllTopics()` contains both expected topics, AND that
     each topic has exactly the configured partition count (verified via
     `containsTopicWithPartitionCheck` for partitions 0..N-1 and a negative check for partition N).
     Logs broker startup time via `System.nanoTime()` for the progress doc's timing requirement.
   - `testRoundTripKmeMessageOnRealTimeTopicForEveryRegion` — for each region, builds a minimal
     `KafkaMessageEnvelope` (Put with a known payload), produces it to RT partition 0 via
     `PubSubProducerAdapter.sendMessage(...).get(15s)`, then subscribes a fresh
     `PubSubConsumerAdapter` from `PubSubSymbolicPosition.EARLIEST` and polls until the message returns.
     Asserts byte-equality of both the produced key bytes and the produced Put.putValue payload bytes
     against the consumed values.
   - `testStopIsIdempotent` — calls `start()`, asserts `isStarted()`, calls `stop()` once, then again,
     asserts no throw.
10. Iterated on compile-error fixes (none surfaced — both `compileIntegrationTestJava` and
    `compileJmhJava` succeeded on the first attempt).
11. Ran the smoke test via `./gradlew :internal:venice-test-common:integrationTest --tests
    "com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest"` — 3/3 passed.
12. Re-ran the Phase 1 unit test (`LeanHarnessSchemaTest`) with `--rerun-tasks` to confirm no regression
    from the relocation — 8/8 still pass.

## Decisions made

1. **Harness relocated from `src/jmh/...` to `src/integrationTest/...`.** Phase 0 placed the skeleton in
   `src/jmh/` because no integration deps were yet needed. Phase 2 *requires* `PubSubBrokerWrapper`,
   `PubSubBrokerConfigs`, and `ServiceFactory` — all of which live in the `src/integrationTest/...`
   source set. Two placements were considered:

   - **Option A (rejected):** keep harness in `src/jmh/...`, add to its imports the integrationTest
     classes (visible via `jmhImplementation project(path: ..., configuration: 'integrationTestUtils')`).
     Then place the smoke test as a `@Test` in `src/integrationTest/...` that *re-implements* the
     start/topic-creation logic inline (since the integrationTest source set cannot see the jmh source
     set classes). **Rejected** because it would duplicate the start/stop logic and risk drift between
     the harness and the test.

   - **Option B (chosen):** move the harness to `src/integrationTest/...`. Both tests in
     `src/integrationTest/...` and JMH benchmarks in `src/jmh/...` can use it. The latter works because
     `integrationTestJar` is published as the `integrationTestUtils` configuration that the jmh source
     set already pulls in. **Chosen.**

   Note: `GOAL.md` says "Implement in the existing `MinimalAAIngestionHarness.java` skeleton (replace
   the stubs in `start()` and `stop()`)". This was honored — the file's class name, package, public
   API surface, and `Config` value-type structure are all preserved (with additions documented below).
   Only the source-set location changed, which is purely a build-system structural decision and does
   not alter the public API the harness exposes. The Phase 0 file was untracked git-wise, so the move
   has zero commit-history impact.

2. **`Config` extended with `regionNames` (default `dc-0`, `dc-1`, ...) and `versionNumber` (default
   1).** Two reasons:

   - The brokers want a region name (passed to `PubSubBrokerConfigs.Builder.setRegionName(...)`) to
     stamp the broker for kafka-cluster-map registration in Phase 4. Hardcoding it inside the harness
     would force every benchmark to use the same region names, conflicting with `GOAL.md`'s statement
     that the harness must be configurable.
   - The VT topic name depends on the version number (`Version.composeKafkaTopic(storeName, version)`).
     Phase 1's `InMemoryReadOnlyStoreRepository.DEFAULT_VERSION_NUMBER == 1` is the default; the
     harness uses the same default unless the caller overrides. Both Config constructors validate that
     the values are non-null/non-empty/in-range.

3. **Topic creation uses replication factor = 1 and a long retention (3 days).** A single in-process
   broker per region cannot replicate across nodes, so RF=1 is the only valid choice. 3-day retention
   matches what `PubSubAdminAdapterTest` uses for its analogous test and is far longer than any realistic
   benchmark run. Constants exposed as `static final` on the harness class for clarity.

4. **Topic verification via two independent admin calls.** The smoke test calls both
   `admin.listAllTopics()` (matches GOAL.md's exact requirement: "via `admin.listTopics()`") AND
   `admin.containsTopicWithPartitionCheck(...)` for each partition number. The latter catches the failure
   mode where a topic was created but with the wrong partition count (which `listAllTopics` would not
   detect). Both checks happen on a fresh admin adapter created in the test (not the one used internally
   by `start()`), proving the harness's view is consistent with an external observer's view.

5. **Smoke test placement: `src/integrationTest/java/.../benchmark/lean/LeanHarnessBrokerSmokeTest.java`.**
   GOAL.md offered three placements ("a NEW unit test or a JMH benchmark sanity check, OR added to
   LeanHarnessSchemaTest"). Adding to `LeanHarnessSchemaTest` was rejected because that test lives in
   `src/test/...` which has no access to `PubSubBrokerWrapper`. A new TestNG `@Test`-annotated class
   alongside the harness in `src/integrationTest/...` is the lightest-weight choice and runs cleanly
   under `gradle integrationTest`. Naming follows the existing project convention
   (`*SmokeTest`/`*E2ETest` for integration-test classes).

6. **Smoke test produces a hand-rolled minimal `KafkaMessageEnvelope`, not via VeniceWriter.** The Phase 2
   spec says "write 1 KME-encoded message". A full VeniceWriter would also exercise DIV, segment/offset
   tracking, schema-id resolution, etc. — none of which Phase 2 needs. A handwritten minimal Put-typed
   KME (with timestamp, sequence number 0, segment 0, fresh GUID, schema id 1, no replication metadata)
   is the smallest end-to-end shape that round-trips through Kafka and meets the byte-equality
   assertion. Phase 4+ will swap to VeniceWriter when DIV becomes a real concern.

7. **No modifications to Phase 1 files.** `InMemoryReadOnlySchemaRepository` /
   `InMemoryReadOnlyStoreRepository` / `LeanHarnessSchemaTest` are untouched. The constraint
   "don't refactor InMemoryReadOnly* unless strictly necessary" was honored.

## Blockers encountered

None of substance. Compilation succeeded on the first attempt; the smoke test passed on the first run.

The one structural question was where to place the harness, and that was resolved by the chosen
relocation strategy (see Decision #1).

## Files modified

- `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
  — **deleted** (relocated; the file was untracked, so no git history loss).
- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
  — **new** (Phase 2 implementation: real `start()`/`stop()`, broker accessors, topic creation +
  listAllTopics verification, idempotent stop, full JavaDoc covering Phase 2 status and lifecycle).
- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/LeanHarnessBrokerSmokeTest.java`
  — **new** TestNG smoke test with three test methods covering broker startup + topic-listing
  verification, KME round-trip, and stop-idempotency.
- `autoresearch/lean-aa-harness/phase-2-progress.md` — this file.

No other files in the repository were modified. Phase 1 files (the two repositories and
`LeanHarnessSchemaTest`) and Phase 0 docs (`dep-graph.md`, `GOAL.md`) are untouched.

## Verification I ran

### Command 1: compile integrationTest source set

```
./gradlew :internal:venice-test-common:compileIntegrationTestJava --console=plain
```

Last 20 lines of output:

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

BUILD SUCCESSFUL in 8s
39 actionable tasks: 1 executed, 38 up-to-date
```

The deprecation/unchecked warnings come from pre-existing sources unrelated to Phase 2 (the new files
use no deprecated APIs and no unchecked operations).

### Command 2: compile JMH source set (no regression from harness relocation)

```
./gradlew :internal:venice-test-common:compileJmhJava --console=plain
```

Last 20 lines:

```
> Task :internal:venice-test-common:compileIntegrationTestJava UP-TO-DATE
> Task :internal:venice-test-common:processIntegrationTestResources UP-TO-DATE
> Task :internal:venice-test-common:integrationTestClasses UP-TO-DATE
> Task :internal:venice-test-common:integrationTestJar

> Task :internal:venice-test-common:compileJmhJava
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

See https://docs.gradle.org/7.3/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 5s
42 actionable tasks: 2 executed, 40 up-to-date
```

### Command 3: compile test source set (no regression from harness relocation)

```
./gradlew :internal:venice-test-common:compileTestJava --console=plain
```

Last 12 lines:

```
> Task :internal:venice-test-common:compileJava UP-TO-DATE
> Task :internal:venice-common:compileTestJava UP-TO-DATE
> Task :internal:venice-common:processTestResources UP-TO-DATE
> Task :internal:venice-common:testClasses UP-TO-DATE
> Task :internal:venice-test-common:classes UP-TO-DATE
> Task :internal:venice-test-common:compileTestJava UP-TO-DATE

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

[deprecation footer omitted]

BUILD SUCCESSFUL in 1s
39 actionable tasks: 39 up-to-date
```

### Command 4: run the Phase 2 smoke test

```
./gradlew :internal:venice-test-common:integrationTest --tests "com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest" --console=plain
```

Last 20 lines:

```
> Task :internal:venice-test-common:integrationTest
forkEvery=0
maxParallelForks=4
jvmArgs=[-DpubSubBrokerFactory=com.linkedin.venice.integration.utils.KafkaBrokerFactory, ...]
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8
WARNING: A terminally deprecated method in java.lang.System has been called
WARNING: System::setSecurityManager has been called by com.linkedin.venice.utils.TestUtils (file:/home/coder/Projects/venice/internal/venice-test-common/build/libs/venice-test-common.jar)
WARNING: Please consider reporting this to the maintainers of com.linkedin.venice.utils.TestUtils
WARNING: System::setSecurityManager will be removed in a future release
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion PASSED (1.802 s)
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testRoundTripKmeMessageOnRealTimeTopicForEveryRegion STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testRoundTripKmeMessageOnRealTimeTopicForEveryRegion PASSED (875 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testStopIsIdempotent STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessBrokerSmokeTest > testStopIsIdempotent PASSED (5.445 s)

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

[deprecation footer omitted]

BUILD SUCCESSFUL in 18s
60 actionable tasks: 1 executed, 59 up-to-date
```

3/3 tests passed.

### Command 5: re-run Phase 1 tests to confirm no regression

```
./gradlew :internal:venice-test-common:test --tests "com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest" --rerun-tasks --console=plain
```

Last 20 lines (tests-only excerpt):

```
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testKeyAndValueSchemaLookup PASSED (11 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testRmdAndWriteComputeSchemaLookup PASSED (1 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testRmdRecordRoundTripsViaFastAvro PASSED (46 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testStoreRepositoryExposesPhase1Configuration PASSED (2 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testUnknownStoreLookupsFailLoudly PASSED (2 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueRecordRoundTripsViaFastAvro PASSED (4 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueSchemaIdLookupByCanonicalString PASSED (1 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testWriteComputeUpdateRecordRoundTripsViaFastAvro PASSED (6 ms)

BUILD SUCCESSFUL in 33s
59 actionable tasks: 59 executed
```

8/8 Phase 1 tests still pass.

### Command 6: confirm branch and tracked-files state

```
git branch --show-current
git status -uall
```

- Branch: `haoxu07/aa-bench-jmh-improvements` (correct).
- Untracked files (all expected for Phase 2):
  - `autoresearch/` (the existing GOAL.md / dep-graph.md / phase-0,1-progress.md / new
    phase-2-progress.md)
  - `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
  - `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/benchmark/lean/LeanHarnessBrokerSmokeTest.java`
  - Phase 1 files (unchanged): `src/main/.../lean/InMemoryReadOnlySchemaRepository.java`,
    `src/main/.../lean/InMemoryReadOnlyStoreRepository.java`, `src/test/.../lean/LeanHarnessSchemaTest.java`
- The Phase 0 jmh skeleton at `src/jmh/.../lean/MinimalAAIngestionHarness.java` is no longer present
  (relocated to `src/integrationTest/...`); `src/jmh/.../lean/` directory removed entirely. As that
  file was untracked, no git history was lost.
- No tracked-file modifications anywhere in the repo. No unrelated edits.

## Broker startup timing

Captured via `System.nanoTime()` inside the harness's `start()` method (logged at INFO level) AND inside
the smoke test (printed to stdout for the test's first assertion path).

Result from the verified test run:

```
[LeanHarnessBrokerSmokeTest] harness.start() took 1761 ms
```

That is the total wall-clock time for **both** brokers (region 0 and region 1) to come up AND for both
RT and VT topics to be created on each (4 topics total) AND for the listAllTopics() verification to
complete. The 10-second sanity goal in the GOAL.md document is met by a factor of 5.7×.

For reference, the "stop is idempotent" test took 5.445 s — that's almost entirely
broker-shutdown + leftover-thread-pool cleanup time (the test calls `start()` then `stop()` twice in
sequence, and `stop()` blocks on broker close completion).

## Status

Status: SUCCESS
