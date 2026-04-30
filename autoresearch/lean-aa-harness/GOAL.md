# Lean AA Ingestion Harness — Goal Document

**Owner:** xhao@linkedin.com
**Drafted:** 2026-04-30
**Branch:** `haoxu07/aa-bench-jmh-improvements`
**Execution model:** **AUTONOMOUS** — subagent implements; main agent algorithmically verifies and dispatches next phase without human approval. Human is only re-engaged on genuine blockers (escalation criteria below) or final goal completion.
**Estimated effort:** 1–2 weeks of agent compute time

---

## 1. Goal

Build `MinimalAAIngestionHarness` — a programmable test harness that exercises Venice's Active-Active write-compute ingestion path with **real Kafka** and **real RocksDB**, but with everything else (Helix, ZooKeeper, controllers, routers, D2, schema registry) replaced by minimal in-memory stubs.

**Success criterion:** the harness can run the same workload as `ActiveActiveIngestionBenchmark` and produce E2E throughput numbers within ±10% of the same workload running on `VeniceTwoLayerMultiRegionMultiClusterWrapper`, while:

- Cluster startup ≤ 15 seconds (vs ~2 minutes today)
- Per-iteration overhead reduced (less background admin chatter)
- Memory footprint reduced (no controller / router / Helix / ZK threads)
- Code path is auditable — every dependency of `ActiveActiveStoreIngestionTask` is either real-and-traceable or a clearly-named stub

Once built, this harness becomes the foundation for further AA-ingestion perf work (drainer batching, in-memory record cache, partial-field serde experiments, etc.).

---

## 2. Scope

### In scope

- Two `PubSubBrokerWrapper` instances (real Kafka, one per simulated region) — REUSE existing
- Manual creation of RT and VT topics via `PubSubAdminAdapter`
- Two `StorageService` / `StorageEngine` instances backed by real RocksDB on temp disk
- Two `ActiveActiveStoreIngestionTask` instances (one per region) — REAL Venice ingestion code
- Real `AggKafkaConsumerService` for RT consumption
- Real `VeniceWriter` for VT produce
- In-memory schema repository pre-loaded with the benchmark's value, write-compute, and RMD schemas
- In-memory store repository pre-loaded with the benchmark's `Store` / `Version` objects
- Hardcoded leader-follower state (each task is leader for its region's partition)
- Direct `PubSubConsumerAdapter` reading VT for verification (replacing router-based reads)

### Explicitly out of scope (NOT built)

- ZooKeeper
- Helix coordination / state model
- Parent + child controllers
- Routers
- D2
- Cross-region admin protocol
- Daemon services (ParticipantStoreConsumer, BackupVersionCleanup, etc.)
- Ingestion isolation / forked-process ingestion mode

### Existing test infrastructure to reuse

| Class | Path | Use as |
|---|---|---|
| `PubSubBrokerWrapper` | `internal/venice-test-common/.../integration/utils/PubSubBrokerWrapper.java` | Real Kafka per region |
| `PubSubBrokerFactory` | same dir | Spin up brokers |
| `IntegrationTestPushUtils.getVeniceWriterFactory` | `internal/venice-test-common/.../utils/` | VeniceWriter setup |
| `RmdSchemaGeneratorV1` | `internal/venice-common/.../schema/rmd/v1/` | Generate RMD schema from value schema |
| `WriteComputeSchemaConverter` | `internal/venice-client-common/.../schema/writecompute/` | Generate write-compute schema |
| `ServiceFactory.getPubSubBroker` | `internal/venice-test-common/.../integration/utils/ServiceFactory.java` | Broker bootstrap |

---

## 3. Phased plan with verification checkpoints

Each phase is a session-sized chunk. The subagent implements one phase at a time and signals the main agent for verification.

### Phase 0 — Dependency graph & skeleton

**Subagent deliverables:**
- `MinimalAAIngestionHarness.java` skeleton class with constructor, `start()`, `stop()`, `getProducerForRegion(int)`, `readFromVT(...)` methods (all stubs)
- `dep-graph.md` — exhaustive list of every `StoreIngestionTaskFactory.Builder.set*()` call with: param name, real-vs-stub-vs-noop classification, source-of-truth for the real or stub value
- Compile-only build of the skeleton (no runtime functionality yet)

**Main agent verification:**
- ✅ Skeleton compiles
- ✅ `dep-graph.md` covers every setter on the Builder (cross-check against the source file)
- ✅ Each "real" classification has a defensible source (e.g. "Real `MetricsRepository` — uses `new MetricsRepository()` with no exporter, low overhead")
- ✅ Each "stub" classification has a clear stub strategy (e.g. "Stub `IngestionNotifier` — no-op for all methods")

**Exit criterion:** main agent reads `dep-graph.md` end-to-end and says "OK, the wiring plan looks complete."

---

### Phase 1 — Schema + store stubs

**Subagent deliverables:**
- `InMemoryReadOnlySchemaRepository` implementing the interface, pre-loaded with:
  - Value schema (`BenchmarkRecord` from the benchmark)
  - RMD schema generated via `RmdSchemaGeneratorV1`
  - Write-compute schema generated via `WriteComputeSchemaConverter`
- `InMemoryReadOnlyStoreRepository` with one `Store` object (AA enabled, write-compute enabled, hybrid, partition count = 2 for now)
- Unit test `LeanHarnessSchemaTest` that verifies all three schemas can be looked up via the repos and round-trip correctly through fastAvro serde

**Main agent verification:**
- ✅ Unit test passes
- ✅ Schemas are byte-equivalent to those produced by the full cluster wrapper for the same store config
- ✅ Repository implementations are minimum-surface (no half-implemented methods that would silently break the ingestion task)

**Exit criterion:** unit test green; schemas match wrapper-generated ones byte-for-byte.

---

### Phase 2 — Real Kafka brokers + topic creation

**Subagent deliverables:**
- `MinimalAAIngestionHarness.start()` brings up 2 `PubSubBrokerWrapper` instances
- Topics `rt` and `_v1` created on each broker via `PubSubAdminAdapter` with the configured partition count
- Topics verified present via `admin.listTopics()`
- A simple direct-write test: write 1 KME-encoded message to one RT topic, read it back via `PubSubConsumerAdapter` — verify roundtrip

**Main agent verification:**
- ✅ Brokers start in <10s combined
- ✅ Topics are created with correct partition counts
- ✅ Produce-and-consume roundtrip succeeds for both regions
- ✅ Brokers shut down cleanly in `stop()`

**Exit criterion:** the harness can act as a programmable Kafka cluster and round-trip messages.

---

### Phase 3 — Storage service + RocksDB

**Subagent deliverables:**
- `MinimalAAIngestionHarness` instantiates 2 `StorageService` instances, each backed by a real RocksDB directory under `tempDir/region-N`
- For each, opens a storage engine for the version topic
- Smoke test: `StorageEngine.put(partition, key, value)` + `.get(...)` roundtrip

**Main agent verification:**
- ✅ RocksDB directories created and writable
- ✅ Put + get roundtrip works on both regions
- ✅ Cleanup deletes the directories on `stop()`

**Exit criterion:** RocksDB is operational under the harness; cleanup is correct.

---

### Phase 4 — Wire `ActiveActiveStoreIngestionTask`

**Subagent deliverables:**
- For each region, build a `StoreIngestionTaskFactory.Builder` with all 30 dependencies populated per `dep-graph.md`
- Instantiate `ActiveActiveStoreIngestionTask` for partition 0 of the version topic
- Hardcode the partition's leader state (call `partitionConsumptionState.setLeaderFollowerState(LEADER)` or equivalent)
- Subscribe the task to both regions' RT topics
- Start the task on a worker thread

**Main agent verification:**
- ✅ Constructor doesn't throw
- ✅ Task starts a consumer thread
- ✅ Consumer is subscribed to expected topics (verify via `consumer.getAssignment()`)
- ✅ Task processes a single test record end-to-end (RT → merge → VT → RocksDB)

**Exit criterion:** a single record flows through the entire pipeline. Logs show:
1. RT consume on region A
2. AA merge applied
3. VT produce on region A
4. RocksDB write on region A
5. (Cross-region) RT consume on region B
6. Same merge applied independently on region B
7. VT produce on region B
8. RocksDB write on region B

---

### Phase 5 — Match the existing benchmark workload

**Subagent deliverables:**
- Modify `ActiveActiveIngestionBenchmark` (or add a new `LeanActiveActiveIngestionBenchmark`) that uses `MinimalAAIngestionHarness` instead of `VeniceTwoLayerMultiRegionMultiClusterWrapper`
- All workload methods (`runPutWorkload`, `runPartialUpdateWorkload`, etc.) work identically
- VT consistency check (existing `runVTConsistencyCheck` method) works against the lean harness — uses the harness's broker URLs

**Main agent verification:**
- ✅ Benchmark compiles and runs end-to-end
- ✅ E2E throughput within ±10% of full-wrapper version (run both, compare)
- ✅ VT consistency check returns 0 mismatches / 0 missing / 0 errors
- ✅ Cluster startup ≤ 15s
- ✅ Memory footprint reduced (RSS at steady state vs full wrapper)

**Exit criterion:** an apples-to-apples benchmark comparison shows the lean harness matches the wrapper's measurements.

---

### Phase 6 — Polish & document

**Subagent deliverables:**
- Update session-notes / pickup doc in autoresearch folder
- Document how to use the harness for new perf experiments
- Add JavaDoc to the harness's public API
- Add a smoke test that runs in CI (or at least runnable via gradle task)

**Main agent verification:**
- ✅ Documentation is comprehensive enough that a fresh engineer could use the harness
- ✅ Smoke test runs in <30 seconds and validates basic correctness

**Exit criterion:** the harness is usable beyond just our benchmark — it's a reusable Venice-internal perf testing tool.

---

## 4. Validation criteria

The harness is "done" when **all of these hold simultaneously**:

| Criterion | Measurement |
|---|---|
| **Functional** | Same workload produces same E2E ops/s within ±10% |
| **Correct** | VT consistency check returns 0 mismatches / 0 missing / 0 errors over 3M+ records |
| **Fast startup** | Total `start()` time ≤ 15 seconds (5× improvement) |
| **Smaller footprint** | Steady-state RSS reduced by ≥30% vs full wrapper |
| **Maintainable** | Every stub clearly documented with what real component it replaces and why a stub is sufficient |
| **Reusable** | Documentation lets another engineer run a different workload through the harness without modifying the harness |

---

## 5. Main agent's autonomous execution loop

```
phase = 0
while phase <= 6:
  spawn subagent with Phase-N prompt
  wait for subagent to report back
  run main-agent verification checks (below)
  if verification passes:
    log success, increment phase
  elif verification fails AND retries < 3:
    spawn subagent again with feedback on what failed
    increment retry counter
  elif retries >= 3:
    ESCALATE to human (see escalation criteria)
  else:
    proceed to next phase
log final success once phase 6 complete; report to human
```

**The main agent does NOT pause for human approval between phases.** Verification is automated (run tests, inspect files, check git state). The main agent has authority to dispatch the next phase as soon as the current one passes its checks.

## 5b. Main agent's verification checklist

For each phase, before dispatching the next phase:

### Code review checks
- ✅ All TODOs from the previous phase are resolved
- ✅ No new TODOs introduced without justification
- ✅ Test coverage for the phase's deliverables exists
- ✅ No silent failures (every failure path either logs or throws)

### Behavior verification (always before signing off)
- ✅ Run any unit test the subagent claims is green — verify it actually compiles AND passes
- ✅ Run any integration smoke test the subagent claims is green — verify
- ✅ Spot-check 2–3 of the subagent's claims by directly inspecting source files (not trusting summaries)
- ✅ For the "stub vs real" classifications in `dep-graph.md`, sample-verify 5 random entries against the actual code

### Drift checks
- ✅ Confirm working-tree state matches subagent's reported state
- ✅ Confirm no unrelated files modified
- ✅ Confirm subagent stayed on the `haoxu07/aa-bench-jmh-improvements` branch (or a sub-branch off it)

### When NOT to approve (auto-retry, up to 3 attempts)
- ❌ Subagent says "test passes" but I can't reproduce — auto-retry with the failure log as feedback
- ❌ Stub classification is unjustified or hand-wavy — auto-retry asking for justification
- ❌ Phase deliverables are partial ("works for region A but region B has TODO") — auto-retry to complete
- ❌ Cluster startup time blows past target without explanation — auto-retry asking for analysis

### Escalation criteria (engage human)

The main agent escalates ONLY when ALL of these apply:

1. **3 consecutive subagent attempts failed** on the same problem with substantively different approaches each time
2. **The blocker is a design decision**, not an implementation bug (e.g., "we can't avoid Helix dependency without changing Venice core code — should we?")
3. **Or:** subagent hits an environmental issue the main agent cannot resolve (e.g., a real bug in Venice that needs upstream fix, missing system dependencies, network restrictions)

The main agent must NOT escalate for:
- ❌ Test failures that have a clear fix
- ❌ Compilation errors
- ❌ Missing dependencies that can be added to gradle
- ❌ Subagent confusion about scope (re-prompt with clearer instructions)
- ❌ Slow progress (autonomous = patient)

When escalating, the main agent writes a detailed `BLOCKED.md` with:
- What was attempted (all 3 retries summarized)
- Why each attempt failed
- A concrete proposed pivot for human review
- Files / line numbers that would need to change

### Success criteria (declare done)

Main agent declares success and writes `SUCCESS.md` summarizing the run when:
- Phase 6 verification passes
- ALL six validation criteria from §4 are met simultaneously
- A demo run of the lean harness benchmark produces measurable, defensible numbers

---

## 6. Open risks / known unknowns

These need to be resolved (often during Phase 0–1) before the subagent can confidently estimate later phases:

| # | Risk | Resolution path |
|---|---|---|
| 1 | `StoreIngestionTaskFactory.Builder` may have evolved beyond what's documented; some "must-set" deps may be hidden in private fields | Phase 0 dep-graph audit; cross-reference with `KafkaStoreIngestionService.startConsumption` source |
| 2 | Helix state model coupling: `ActiveActiveStoreIngestionTask` may call `helixAdmin` methods not just for state-change but for routine operations (e.g., partition status reports) | Stub `helixAdmin` with a Mockito mock that returns sane defaults; trace any unexpected calls during Phase 4 smoke test |
| 3 | DIV (Data Integrity Validation) may reject messages we produce directly because of segment/sequence-number mismatch | Use `VeniceWriter` to produce, not raw KafkaProducer — DIV is enforced by the writer |
| 4 | Schema-system-store dependencies — Venice has system stores like `meta_store` that may be expected to exist | Stub `ReadOnlySchemaRepository` to claim system stores exist; if ingestion task throws on lookup, mock the lookup |
| 5 | Native replication source-fabric routing requires controller awareness | Hardcode source-fabric to a specific region; bypass any controller-driven negotiation |
| 6 | OTel + metrics dependencies might require an active TelemetryReporter | Use `MetricsRepository` with no reporters; verify no NPEs |
| 7 | `StoreVersionState` (StartOfPush, EndOfPush control messages) — needs proper SOP/EOP control-message sequence on VT for the ingestion to consider partition "ready" | Manually inject SOP and EOP control messages into RT before workload starts |

---

## 7. Reference materials

- Existing benchmark: `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java`
- Existing wrapper: `internal/venice-test-common/.../integration/utils/VeniceTwoLayerMultiRegionMultiClusterWrapper.java`
- Existing unit test (mocked): `clients/da-vinci-client/src/test/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTaskTest.java`
- AA ingestion task: `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java`
- Builder: `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTaskFactory.java`
- Server config (defaults for thread pools etc.): `clients/da-vinci-client/src/main/java/com/linkedin/davinci/config/VeniceServerConfig.java`
- Session notes from the previous benchmarking session: `autoresearch/260419-1758-aa-partial-update-collection-merge/session-notes.md`

---

## 8. How the main agent invokes the subagent (per phase)

For each phase, the main agent invokes a fresh `general-purpose` subagent with a self-contained prompt. The subagent's prompt must include:

1. Phase number and full phase requirements
2. Pointer to this `GOAL.md` for full context
3. Reference materials specific to that phase
4. Instruction to write progress to `autoresearch/lean-aa-harness/phase-N-progress.md`
5. Instruction to stay on branch `haoxu07/aa-bench-jmh-improvements`
6. Instruction NOT to push to remote
7. Specific exit signal: "When done, write SUCCESS marker to phase-N-progress.md and return"

The main agent does not wait for human approval between phases. It runs verification immediately upon subagent return, then either:
- Dispatches the next phase automatically, OR
- Re-dispatches the same phase with feedback (retry, max 3), OR
- Escalates to human (per §5b escalation criteria), OR
- Declares done and writes `SUCCESS.md`

## 8b. Subagent prompt template

```
You are the implementation subagent for the Lean AA Ingestion Harness project.

REQUIRED CONTEXT:
1. Read /home/coder/Projects/venice/autoresearch/lean-aa-harness/GOAL.md in full
2. Read all phase-*-progress.md files in autoresearch/lean-aa-harness/ for prior context
3. Read referenced files for THIS phase

YOUR PHASE: <Phase-N — name>

DELIVERABLES:
<copied from §3 of GOAL.md>

CONSTRAINTS:
- Stay on branch haoxu07/aa-bench-jmh-improvements (do not switch, do not push)
- Do not modify files outside the phase's scope without justifying it in progress doc
- Every code change must compile (run `./gradlew :internal:venice-test-common:jmhJar` or equivalent before declaring done)
- For Java tests, run them and confirm they pass

REPORTING:
- Write progress, decisions, blockers to autoresearch/lean-aa-harness/phase-<N>-progress.md
- The progress doc must include:
  - "Approach taken" section
  - "Decisions made" with brief rationale
  - "Blockers encountered" with how you resolved them (or didn't)
  - "Files modified" list with one-line description per file
  - "Verification I ran" section showing exact commands and outputs
  - "Status" line: SUCCESS | PARTIAL | BLOCKED at the end

EXIT SIGNAL:
- Write Status: SUCCESS to phase-<N>-progress.md when done
- Return a brief summary (under 300 words) of what was completed
```

---

## 9. Why this is worth doing

- **Cluster startup 12× faster** (2 min → 10 s) — dramatically improves dev iteration
- **Reduced noise** — fewer background services running during measurement → cleaner numbers
- **Surgical optimization** — easier to swap in/out individual components (e.g., test alternative RocksDB configs without re-bootstrapping Helix)
- **Reusable** — any future Venice perf work can use this harness, not just our benchmark
- **Documents the AA ingestion path** — building this is itself a reverse-engineering exercise that produces a `dep-graph.md` documenting the full ingestion machinery, which is useful regardless of whether the harness is ever shipped
