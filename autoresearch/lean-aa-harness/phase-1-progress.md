# Phase 1 Progress — Schema + store stubs

## Approach taken

1. Read `GOAL.md` (Phase 1 spec), `dep-graph.md` entries #6 and #7, and `phase-0-progress.md` to internalize the
   prior decisions and the exact deliverables for this phase.
2. Read the existing skeleton at
   `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/lean/MinimalAAIngestionHarness.java`
   to confirm package layout and to scope the new files alongside it.
3. Read the `BenchmarkRecord` value schema from `ActiveActiveIngestionBenchmark.java` (4 fields:
   `name:string`, `age:int`, `score:double`, `tags:map<string,string>`).
4. Read the relevant Venice interfaces and concrete classes:
   - `ReadOnlySchemaRepository` (14 abstract methods + 1 default)
   - `ReadOnlyStoreRepository` (12 abstract methods + 2 defaults inherited from interface)
   - `ZKStore` constructors (full 12-arg variant accepts hybrid + partitioner config)
   - `RmdSchemaGeneratorV1` and `WriteComputeSchemaConverter` entry points
   - `SchemaEntry`, `RmdSchemaEntry`, `DerivedSchemaEntry`, `GeneratedSchemaID`
   - `HybridStoreConfigImpl` (4-arg variant: rewindSec, offsetLag, timeLagSec, BufferReplayPolicy)
   - `VersionImpl` setters for AA / WC / hybrid / status
5. Implemented `InMemoryReadOnlySchemaRepository` (in `src/main/java/...`) — pre-loads exactly one
   `(key, value, RMD, write-compute)` schema set keyed by store name; fails loudly with `VeniceException`
   on lookups for unknown stores.
6. Implemented `InMemoryReadOnlyStoreRepository` (in `src/main/java/...`) — wraps a single `ZKStore` with
   AA + WC + hybrid (rewind=25s, threshold=1) + partitionCount=2 + a single ready-to-serve `Version 1`.
7. Wrote `LeanHarnessSchemaTest` (TestNG, in `src/test/java/...`) with 8 test methods covering schema
   lookups, value-record / RMD-record / write-compute round-trips through fastAvro, store metadata
   correctness, and unknown-store loud-failure.
8. Iterated through compile errors:
   - `Store.DEFAULT_READ_QUOTA` does not exist; the constant lives on `AbstractStore` — fixed by qualifying.
   - `HybridStoreConfigImpl` has no `(long, long, long, DataReplicationPolicy, BufferReplayPolicy)` ctor;
     used the 4-arg `(long, long, long, BufferReplayPolicy)` ctor which routes data-replication-policy to
     the documented default `NON_AGGREGATE`.
9. Ran `compileJava`, `compileTestJava`, and `compileJmhJava` for `:internal:venice-test-common` — all
   `BUILD SUCCESSFUL`.
10. Ran `:internal:venice-test-common:test --tests LeanHarnessSchemaTest` — 8/8 passed.
11. Verified branch `haoxu07/aa-bench-jmh-improvements` and that only the expected new files are present
    (no unrelated edits).

## Decisions made

- **Placement: `src/main/java/com/linkedin/venice/benchmark/lean/` for the impls.** GOAL.md gave three
  candidate locations and asked for the minimum-surface choice. Putting the impls in `main` rather than
  `jmh` is the minimum-surface choice that satisfies all consumers simultaneously: the harness in
  `src/jmh/...` automatically sees `main` classes, AND the unit test in `src/test/...` (required to run via
  `gradle test`) also sees `main` classes. Putting them in `jmh` would force the test into the jmh source
  set, which `gradle test` does not run; putting them in `integrationTest` would be unnecessarily heavy
  (no integration-test deps are required). All needed classes (RmdSchemaGeneratorV1,
  WriteComputeSchemaConverter, ZKStore, etc.) are already on the `main` source set's compile classpath via
  existing `implementation project(':internal:venice-common')` and `:internal:venice-client-common`
  dependencies.
- **Test source set under `src/test/java/com/linkedin/venice/benchmark/lean/`.** This matches the existing
  one-test-class precedent (`src/test/java/com/linkedin/venice/utils/TestUtilsTest.java`), runs under the
  default `test` task (TestNG-based per the rootProject `build.gradle`).
- **Unknown-store lookups throw, not return null.** dep-graph.md entry #6 explicitly flags silent null
  returns as the medium-risk failure mode for the schema repository. I biased toward loud failures — the
  schema repo throws `VeniceException` on unknown store names, the store repo throws `VeniceNoStoreException`
  on `getStoreOrThrow` (and returns null on `getStore`, matching the documented contract). The unit test
  asserts these throws.
- **`getValueSchema(unknownId)` returns `null`** rather than throwing, because that is the documented
  contract used by the production `HelixReadOnlySchemaRepository` — the SIT inspects the result via
  `hasValueSchema()` first. Throwing here would break the existing call pattern.
- **One value schema, one RMD schema, one write-compute schema, all at id=1.** The benchmark only registers
  one value schema; matching that. RMD protocolVersion=1 reflects use of `RmdSchemaGeneratorV1`.
  Write-compute protocolVersion=1 because there is only ever one write-compute derivation per value schema.
  These are surfaced as `public static final int` constants on `InMemoryReadOnlySchemaRepository` so callers
  (the harness in Phase 4) don't have to magic-number them.
- **`ZKStore` chosen as the concrete `Store` class** per dep-graph.md entry #7 ("Backed by `ZKStore`
  constructor for object equivalence"). The 12-arg ctor lets us inject hybrid + partitioner config in
  one shot; the boolean setters fill in AA / WC / native-replication / chunking flags.
- **Single `Version 1` at `VersionStatus.ONLINE`** so `getCurrentVersion()` returns 1 and
  `version.getStatus() == ONLINE` (the SIT's "ready-to-serve" predicate). Hybrid + AA + native-rep flags
  also propagated to the version itself (via `setUseVersionLevelHybridConfig(true)`) so the SIT sees
  consistent values whether it reads from store or version.
- **Repo-level constants exposed (`DEFAULT_PARTITION_COUNT=2`, `DEFAULT_HYBRID_REWIND_SECONDS=25L`,
  `DEFAULT_HYBRID_OFFSET_LAG_THRESHOLD=1L`, `DEFAULT_VERSION_NUMBER=1`)** so the test asserts against the
  same constants, and Phase 4 / 5 callers don't have to magic-number them either.
- **Tests use TestNG** (not JUnit), matching the root-project `build.gradle` `useTestNG{}` config and the
  one existing file at `src/test/java/com/linkedin/venice/utils/TestUtilsTest.java`.
- **No modifications outside Phase 1 scope.** The skeleton harness and `dep-graph.md` are unchanged.

## Blockers encountered

1. **Compile error: `Store.DEFAULT_READ_QUOTA` not found.** `Store` is an interface; the constant is
   declared on `AbstractStore`. Resolved by fully qualifying as
   `com.linkedin.venice.meta.AbstractStore.DEFAULT_READ_QUOTA` at the call site.
2. **Compile error: `HybridStoreConfigImpl(long,long,long,DataReplicationPolicy,BufferReplayPolicy)` ctor
   does not exist.** That constructor takes 6 args including a final `String realTimeTopicName`; the 5-arg
   variant I tried first does not exist. Resolved by switching to the 4-arg
   `(long, long, long, BufferReplayPolicy)` ctor, which routes `dataReplicationPolicy` to the documented
   default `NON_AGGREGATE` internally — matching what production controllers default to for AA stores.
3. No other blockers. All other interface methods, ZKStore setters, and VersionImpl setters worked as
   documented on the first try.

## Files modified

- `internal/venice-test-common/src/main/java/com/linkedin/venice/benchmark/lean/InMemoryReadOnlySchemaRepository.java`
  — new in-memory implementation of `ReadOnlySchemaRepository` pre-loaded with key/value/RMD/write-compute
  schemas for one store. Fails loudly on unknown stores.
- `internal/venice-test-common/src/main/java/com/linkedin/venice/benchmark/lean/InMemoryReadOnlyStoreRepository.java`
  — new in-memory implementation of `ReadOnlyStoreRepository` wrapping a `ZKStore` with AA+WC+hybrid+2
  partitions and a single ready-to-serve `Version 1`.
- `internal/venice-test-common/src/test/java/com/linkedin/venice/benchmark/lean/LeanHarnessSchemaTest.java`
  — new TestNG unit test with 8 test methods exercising both repos and round-tripping value / RMD /
  write-compute records through fastAvro.
- `autoresearch/lean-aa-harness/phase-1-progress.md` — this file.

## Verification I ran

### Command 1: compile main source set

```
./gradlew :internal:venice-test-common:compileJava --console=plain
```

Last 20 lines of output:

```
> Task :services:venice-controller:compileAvro UP-TO-DATE
> Task :services:venice-controller:compileJava UP-TO-DATE
> Task :services:venice-server:compileAvro NO-SOURCE
> Task :services:venice-server:compileJava UP-TO-DATE

> Task :internal:venice-test-common:compileJava
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

See https://docs.gradle.org/7.3/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 4s
27 actionable tasks: 1 executed, 26 up-to-date
```

The deprecation/unchecked warnings come from pre-existing sources unrelated to the Phase 1 files (the new
files use no deprecated APIs and no unchecked operations).

### Command 2: compile test source set

```
./gradlew :internal:venice-test-common:compileTestJava --console=plain
```

Last 20 lines of output:

```
> Task :internal:venice-common:processTestResources UP-TO-DATE
> Task :internal:venice-common:testClasses
> Task :internal:venice-test-common:classes UP-TO-DATE

> Task :internal:venice-test-common:compileTestJava
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF8

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

See https://docs.gradle.org/7.3/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 5s
39 actionable tasks: 2 executed, 37 up-to-date
```

### Command 3: compile jmh source set (skeleton harness still compiles, no regression)

```
./gradlew :internal:venice-test-common:compileJmhJava --console=plain
```

Last 20 lines of output:

```
> Task :internal:venice-test-common:compileJmhJava
Note: Some input files use or override a deprecated API.
Note: Recompile with -Xlint:deprecation for details.
Note: Some input files use unchecked or unsafe operations.
Note: Recompile with -Xlint:unchecked for details.

Deprecated Gradle features were used in this build, making it incompatible with Gradle 8.0.

You can use '--warning-mode all' to show the individual deprecation warnings and determine if they come from your own scripts or plugins.

See https://docs.gradle.org/7.3/userguide/command_line_interface.html#sec:command_line_warnings

BUILD SUCCESSFUL in 10s
42 actionable tasks: 2 executed, 40 up-to-date
```

### Command 4: run the unit test

```
./gradlew :internal:venice-test-common:test --tests "com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest" --console=plain
```

Last 20 lines of output:

```
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testKeyAndValueSchemaLookup STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testKeyAndValueSchemaLookup PASSED (11 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testRmdAndWriteComputeSchemaLookup STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testRmdAndWriteComputeSchemaLookup PASSED (0 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testRmdRecordRoundTripsViaFastAvro STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testRmdRecordRoundTripsViaFastAvro PASSED (41 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testStoreRepositoryExposesPhase1Configuration STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testStoreRepositoryExposesPhase1Configuration PASSED (2 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testUnknownStoreLookupsFailLoudly STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testUnknownStoreLookupsFailLoudly PASSED (2 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueRecordRoundTripsViaFastAvro STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueRecordRoundTripsViaFastAvro PASSED (3 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueSchemaIdLookupByCanonicalString STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testValueSchemaIdLookupByCanonicalString PASSED (1 ms)
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testWriteComputeUpdateRecordRoundTripsViaFastAvro STARTED
com.linkedin.venice.benchmark.lean.LeanHarnessSchemaTest > testWriteComputeUpdateRecordRoundTripsViaFastAvro PASSED (6 ms)

BUILD SUCCESSFUL in 4s
59 actionable tasks: 12 executed, 47 up-to-date
```

8 of 8 tests passed.

### Command 5: verify branch + git state

```
git branch --show-current
git status
```

- Branch: `haoxu07/aa-bench-jmh-improvements` (correct).
- Untracked files (all expected for Phase 1):
  - `autoresearch/` (the existing GOAL.md / dep-graph.md / phase-0-progress.md / new phase-1-progress.md)
  - `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/lean/` (Phase 0 skeleton)
  - `internal/venice-test-common/src/main/java/com/linkedin/venice/benchmark/` (Phase 1 impls)
  - `internal/venice-test-common/src/test/java/com/linkedin/venice/benchmark/` (Phase 1 unit test)
- No tracked-file modifications. No unrelated edits.

## Schema-byte-equivalence note

The Phase-1 schemas come from the same generators that production uses
(`RmdSchemaGeneratorV1.generateMetadataSchema(valueSchema)` and
`WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(valueSchema)`), seeded by the same
`BenchmarkRecord` value-schema string used by `ActiveActiveIngestionBenchmark`. Therefore the in-memory
schemas should be byte-identical to what the full cluster wrapper would produce for an AA + WC + hybrid
store created with the same value schema.

I did NOT run a head-to-head byte comparison against the wrapper's output as part of Phase 1 because the
wrapper requires a full multi-region cluster bootstrap (~2 minutes startup), and Phase 1 explicitly scopes
to in-memory deliverables. Flagging for **Phase 5 verification**: when the lean harness is run side-by-side
with the wrapper-based benchmark, dump both registered RMD and WC schemas, compare via
`Schema.equals()` and `parsingFingerprint64`, assert byte-equality. If the wrapper applies any extra schema
manipulation (e.g., superset evolution for write-compute), this is the place it would surface.

## Status

Status: SUCCESS
