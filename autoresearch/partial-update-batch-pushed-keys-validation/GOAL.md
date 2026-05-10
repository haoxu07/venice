# PartialUpdateTest Validation — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-09 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation;
flag-on integration validation) **Execution model:** Autonomous agent. Single-phase iterative debug loop with up to 8
fix attempts per failing invocation (informed by prior history — see §0). **Estimated effort:** 1–2 sessions. Best case
(all pass first try): ~30 min. Worst case (3+ distinct new bugs across compression / chunking paths): ~4 hours.

---

## 0. Prior history (the agent must read this first)

The integration-test family targeted by this work — `PartialUpdateTest` — was the original gating test family for
`vt-rocksdb-merge-correctness` Phase C. That work was halted at iter 7 for "Tier 3 design issue" but later resumed and
hit root cause at iter 11. **Total: 11 iterations across two sessions, 5 production bugs found and fixed.**

The 5 bugs (all already fixed on this branch — DO NOT re-introduce or re-fix):

1. **RMD partition wrapper missing** — `MaterializingReplicationMetadataRocksDBStoragePartition` now wraps RMD
   partitions for AA stores
2. **Double-framing re-entry** — `MaterializingFraming.FRAMING_IN_PROGRESS` thread-local guards against virtual-dispatch
   loops
3. **SST checksum framing-aware** — `PartitionConsumptionState.maybeUpdateExpectedChecksum` includes kind byte + varint
   length when framing is active
4. **`VeniceWriter.update()` chunking-mode rejection** — relaxed; size check now governs (operand ≤ max user payload)
5. **Operand-snapshot prefix-capture (iter-11 root cause)** — `produceUpdateOperandToVT` now uses
   `duplicate().get(byte[])` instead of `arrayOffset()`/`limit()` arithmetic; AND `processActiveActiveMessage`
   early-returns on UPDATE under flag-on so the buffer position isn't advanced before the snapshot

The agent's job is to verify these fixes are sufficient for the originally-in-scope **7 invocations** across 3
`PartialUpdateTest` methods. **One sister test (`testActiveActivePartialUpdateWithRecordMapField` in
`TestPartialUpdateWithActiveActiveReplication`) was confirmed passing in a prior verification run today (37.6s wall,
flag-on).** That sister test exercises the same fold path but a different value schema and only the no-op compression
strategy.

### Required reading before the agent starts

- `autoresearch/vt-rocksdb-merge-correctness/OUTCOME.md` — the formal outcome of the predecessor experiment
- `autoresearch/vt-rocksdb-merge-correctness/iter-11-NOTES.md` — root-cause analysis + fix
- `autoresearch/vt-rocksdb-merge-correctness/phase-C-progress-update.md` — final iter-11 resolution narrative
- `autoresearch/vt-rocksdb-merge-correctness/phase-C-progress.md` — full iter 1-7 log (Tier 1/2/3 hypotheses tried)

If a test fails, the agent MUST first hypothesize whether the failure is one of the 5 known bugs (regression) or a new
failure mode (e.g., chunking-specific or GZIP/ZSTD-specific code path). Do not blindly re-attempt iter 1-7 fixes.

## 1. Goal

Get all 7 originally-in-scope invocations across 3 `PartialUpdateTest` methods passing **with flag ON**
(`-Dvt.update.operand.flag=true`):

1. `testPartialUpdateOnBatchPushedKeys[NO_OP]`
2. `testPartialUpdateOnBatchPushedKeys[GZIP]`
3. `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`
4. `testActiveActivePartialUpdateOnBatchPushedChunkKeys` (single invocation; exercises chunking)
5. `testActiveActivePartialUpdateWithCompression[NO_OP]`
6. `testActiveActivePartialUpdateWithCompression[GZIP]`
7. `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]`

Plus the **flag-OFF regression confirmation** (already known-good for the 3 `testPartialUpdateOnBatchPushedKeys`
invocations per the prior run; the other 4 were never run flag-off either, so this fully establishes the baseline):

- All 7 invocations, with `vt.update.operand.flag` unset (default false)

**Success criterion:** all 14 invocations PASS (7 flag-on + 7 flag-off) with no test-code modifications. Production code
modifications are allowed only if a NEW bug not covered by the prior 5 is discovered.

## 2. Scope

### In scope

- Run all 7 flag-on invocations and 7 flag-off invocations across these 3 test methods:
  - `testPartialUpdateOnBatchPushedKeys` (3-way parametrized over compression strategy)
  - `testActiveActivePartialUpdateOnBatchPushedChunkKeys` (single invocation, chunking-on)
  - `testActiveActivePartialUpdateWithCompression` (3-way parametrized over compression strategy, AA-replicated)
- Diagnose any failures using existing diagnostic infrastructure (server-side `[SERVER-RECV-GET]`, `[SERVER-AFTER-GET]`,
  `[SERVER-DESERIALIZE-FAIL]` log lines, on-disk hex dumps via
  `MaterializingReplicationMetadataRocksDBStoragePartition.get(ByteBuffer)`)
- Fix any newly-discovered production bug that prevents these tests from passing
- Document the result and any new bugs found

### Out of scope

- Changing the flag-on default (production stays flag-off)
- Re-running unrelated integration tests (only the 3 `PartialUpdateTest` methods listed)
- Performance regressions — this is a correctness-only validation
- Modifying the `[SERVER-RECV-GET]` etc. diagnostic infrastructure (use as-is)
- Adding new test methods or test parameters

## 3. Run command

**IMPORTANT:** Venice's gradle config sets `maxRetries = 4` (5 attempts total) on every test task by default — see
`build.gradle:475`. Without overriding this, every failing invocation eats ~3 min of wall time on automatic retries
before the failure is reported. For the debug loop this is wasted time (every retry hits the same deterministic bug).

**Disable retries for the agent's runs** via a gradle init script + `--fail-fast`:

```bash
# One-time init script setup (writes to /tmp; persists for the session)
cat > /tmp/disable-test-retry.gradle <<'EOF'
allprojects {
  tasks.withType(Test).configureEach {
    retry {
      maxRetries = 0
    }
  }
}
EOF
```

**Flag-on run** (the primary validation, all 3 test methods together):

```bash
cd /home/coder/Projects/venice && \
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateWithCompression" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast
```

**Flag-off baseline** (regression check, all 3 test methods together):

```bash
cd /home/coder/Projects/venice && \
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateWithCompression" \
    --rerun-tasks --fail-fast
```

(Omit `-Dvt.update.operand.flag=true` — defaults to false → flag-off path.)

The test class `PartialUpdateTest` already implements `getExtraServerProperties()` to read the `vt.update.operand.flag`
JVM system property (per the prior phase-C-progress.md test-target wiring). The bench's
`ConfigKeys.SERVER_VT_UPDATE_OPERAND_ENABLED` flag flips when the system property is set.

### When debugging a single failure

After narrowing to a specific failing invocation, isolate it for faster iteration:

```bash
# Example: debug just the chunked-keys variant
./gradlew --init-script /tmp/disable-test-retry.gradle \
  :internal:venice-test-common:integrationTest \
  --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys" \
  -Dvt.update.operand.flag=true \
  --rerun-tasks --fail-fast
```

Wall time per run:

| Scenario            |                     Without overrides |                         With overrides above |
| ------------------- | ------------------------------------: | -------------------------------------------: |
| All 7 PASS          | ~10-15 min (parallel, fork pool of 4) |                       ~10-15 min (no change) |
| 1 FAIL, others PASS |     ~25 min (5× retry on failing one) |          ~5-10 min (aborts on first failure) |
| All 7 FAIL          |                               ~75 min | ~30-40s (single attempt, fail-fast on first) |

Each isolated invocation takes ~30-40s under `--fail-fast` with retries off. The 5-30× speedup applies only when bugs
are present — pass-only runs see no change.

## 4. Iteration policy

Per the prior experiment's history (5 production bugs across 11 iterations), the original budget of 5 attempts per test
was insufficient. **This work-stream uses an 8-attempt budget per failing test method**, with the following structure:

| Iter range | Tier                               | Action                                                                                                                                                                 |
| ---------- | ---------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 1-3        | Tier 1: known-bug regression check | Hypothesize one of the 5 prior bugs has regressed; verify via diagnostic logs; if confirmed, restore the fix                                                           |
| 4-6        | Tier 2: new bug investigation      | Hypothesize a new bug (likely in chunking or GZIP/ZSTD compression pathway since iter-11 fix only verified the no-op + map-merge case); use diagnostics to narrow down |
| 7-8        | Tier 3: escalation prep            | If still failing, write `BLOCKED-NOTES.md` describing the symptom precisely (matching the format of `iter-11-NOTES.md`); halt and escalate                             |

**Bug-clustering principle:** if a single fix unlocks multiple invocations (likely, given they share code paths), it
counts as one attempt. The budget is per failing test METHOD, not per parametrized invocation. Example: if the GZIP fix
also resolves ZSTD_WITH_DICT, both count under the same iteration.

Each iteration must produce an `iter-N-NOTES.md` in `autoresearch/partial-update-batch-pushed-keys-validation/` with:

- Hypothesis tested
- Diagnostic data captured (log excerpts, hex dumps if relevant)
- Result (PASS / FAIL with new symptom)
- Tier classification

If the very first run passes, write a single `OUTCOME.md` confirming the result and skip the iteration log.

## 5. Test gates

| Gate                                                                       | Threshold                                                    | What it tells us                                                                                      |
| -------------------------------------------------------------------------- | ------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| **Flag-OFF baseline (all 7)**                                              | All PASS in same wall-time band as historical (~30-42s each) | Branch hasn't regressed flag-off behavior                                                             |
| **Flag-ON `testPartialUpdateOnBatchPushedKeys[NO_OP]`**                    | PASS                                                         | Iter-11 fix generalizes beyond MapField schema                                                        |
| **Flag-ON `testPartialUpdateOnBatchPushedKeys[GZIP]`**                     | PASS                                                         | `MaterializingFoldContext.compressFolded` works on GZIP                                               |
| **Flag-ON `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`**           | PASS                                                         | `compressFolded` works on ZSTD-with-dict (most complex variant)                                       |
| **Flag-ON `testActiveActivePartialUpdateOnBatchPushedChunkKeys`**          | PASS                                                         | Chunking key-wrap fix (one of the 5 prior bugs) is correct under AA + post-batch-push partial updates |
| **Flag-ON `testActiveActivePartialUpdateWithCompression[NO_OP]`**          | PASS                                                         | AA + partial update + compression strategy = NO_OP works                                              |
| **Flag-ON `testActiveActivePartialUpdateWithCompression[GZIP]`**           | PASS                                                         | AA + GZIP compression on the fold-and-recompress path works                                           |
| **Flag-ON `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]`** | PASS                                                         | AA + ZSTD on the fold-and-recompress path works                                                       |

The 3 compression strategies exercise distinct code paths in `MaterializingFoldContext.compressFolded`. The chunking
variant exercises the `KEY_WITH_CHUNKING_SUFFIX_SERIALIZER` wrapping that was added as part of bug #4.

`[NO_OP]` is the easy case (already known to work for MapField test); `[GZIP]`, `[ZSTD_WITH_DICT]`, and chunked-keys may
surface new bugs the iter-11 fix didn't reach.

## 6. Reference files (must-read before debugging)

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/MaterializingFoldContext.java` — fold
  logic; iter-10 confirmed compressor IS plumbed through correctly for NO_OP, but compressor's behavior for GZIP/ZSTD
  has not been verified end-to-end
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingReplicationMetadataRocksDBStoragePartition.java`
  — the AA-store wrapper; has `get(ByteBuffer)` diagnostic that hex-dumps on-disk bytes
- `services/venice-server/src/main/java/com/linkedin/venice/listener/StorageReadRequestHandler.java` —
  `[SERVER-RECV-GET]`, `[SERVER-AFTER-GET]`, `[SERVER-DESERIALIZE-FAIL]` diagnostic emit sites
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java` —
  `produceUpdateOperandToVT` (iter-5/iter-11 fix site, including the chunking key-wrap from bug #4) and
  `processActiveActiveMessage` (iter-11 early-return site)
- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/endToEnd/PartialUpdateTest.java` — the test
  class under validation

## 7. Decision criteria

**YES (success):** all 7 flag-on invocations PASS + 7 flag-off invocations PASS = **14 PASS total**. Write `OUTCOME.md`
with the wall times and any observations.

**PARTIAL:** some pass, some fail. Document which fail in `OUTCOME.md`; classify each failure (known regression vs. new
bug); do NOT escalate if there's a clear next step within the 8-iter budget.

**FAILED / BLOCKED:** budget exhausted with at least one invocation still failing. Write `BLOCKED-NOTES.md` with:

- Exact assertion error
- Diagnostic log excerpts
- Hex dump of any corrupted on-disk bytes (use the
  `[MaterializingReplicationMetadataRocksDBStoragePartition.get(ByteBuffer)]` diagnostic)
- Hypotheses tried
- Specific code paths to investigate next

The agent should NOT attempt design-level changes (e.g., changing the on-disk wire format, replacing
`StringAppendOperator`). Those are out of scope for this work-stream.

## 8. Risks

| Risk                                                                                                                                                       | How we'd see it                                                                                                           | Response                                                                                                                                                                                              |
| ---------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Compression-strategy-specific bug in `compressFolded`                                                                                                      | `[GZIP]` or `[ZSTD_WITH_DICT]` fail with deserialization errors but `[NO_OP]` passes (across either or both test methods) | Inspect `MaterializingFoldContext.compressFolded` for compressor null-handling + recompression invariants                                                                                             |
| Chunking-specific bug in the read path                                                                                                                     | `testActiveActivePartialUpdateOnBatchPushedChunkKeys` fails but `testPartialUpdateOnBatchPushedKeys[*]` passes            | Verify the `KEY_WITH_CHUNKING_SUFFIX_SERIALIZER` wrap is consistent on the produce path AND read path; check the chunked-value reassembly in `RawBytesChunkingAdapter` against the framed-base format |
| AA-replication-specific bug (`testActiveActivePartialUpdateWithCompression` fails but `testPartialUpdateOnBatchPushedKeys` of the same compression passes) | Per-compression invocation behaves differently between AA and non-AA test methods                                         | The AA path uses `MaterializingReplicationMetadataRocksDBStoragePartition`; non-AA uses `MaterializingRocksDBStoragePartition`. Cross-check the framing path between them.                            |
| Test infrastructure flake (Kafka container start, etc.)                                                                                                    | Sporadic non-correctness errors, multiple invocations behave differently                                                  | Re-run; if persistent, isolate single invocation with `--tests` filter                                                                                                                                |
| Pre-commit hook fails on `npm install` for prettier                                                                                                        | Spotless gradle task fails                                                                                                | Workaround: `npm install --registry=https://registry.npmjs.org/` in `build/spotless-node-modules-prettier-format/` (same workaround prior agents used)                                                |
| Branch state regression — one of the 5 prior bugs reverted                                                                                                 | Test fails with one of the documented historical symptoms                                                                 | Cross-reference `phase-C-progress.md` iter log; re-apply fix                                                                                                                                          |
| New bug specific to the chunked-keys schema (different fixture than MapField + non-chunked)                                                                | Failure mode doesn't match any of the 5 prior bugs                                                                        | Tier 2: write hex dump + hypothesis to `iter-N-NOTES.md`; if 3 attempts don't resolve, write `BLOCKED-NOTES.md` with diagnostic data and halt                                                         |

## 9. Non-goals

- This is **not** about adding new test coverage. The 14 invocations (7 flag-on + 7 flag-off) are the entire scope.
- This is **not** about refactoring `MaterializingFoldContext` or any of the production fold path. Bug fixes only if a
  NEW bug surfaces.
- This is **not** about benchmarking or performance — correctness only.
- This is **not** about validating other integration test classes (e.g., `TestPartialUpdateWithActiveActiveReplication`
  beyond what's already passed, or any other `Test*PartialUpdate*` class).

## 10. Final report shape

When done (success OR halt), write a single message back with:

- One-paragraph verdict (PASS / PARTIAL / FAILED with headline)
- 7-row table: each flag-on invocation (test method × compression-or-chunking variant) result + wall time
- 7-row table: same for flag-off
- Any new bugs found, with iter-N-NOTES.md references
- Final commit hashes (if any production code changes were made)
- `OUTCOME.md` location

If `BLOCKED-NOTES.md` was written, include its contents verbatim in the final report.

---

## 11. Why this matters

The original Phase C work-stream was halted at "Tier 3 design issue" but the iter-11 root-cause analysis showed it was a
Tier-1 bug all along (operand-snapshot prefix-capture). This validation closes the loop on the original Phase C goal
across **the full 7-invocation surface area**, not just the 3 invocations the prior session re-tested:

- **`testPartialUpdateOnBatchPushedKeys[*]`** (3 invocations) — single-region partial updates over batch-pushed keys;
  covers the basic flag-on path under each compression strategy
- **`testActiveActivePartialUpdateOnBatchPushedChunkKeys`** (1 invocation) — AA-replicated partial updates with chunking
  enabled; covers the chunking key-wrap fix (one of the 5 prior bugs)
- **`testActiveActivePartialUpdateWithCompression[*]`** (3 invocations) — AA-replicated partial updates under each
  compression strategy; covers the fold-on-read + recompress path through `MaterializingFoldContext.compressFolded`

Together these cover: scalar SET, map merge, list merge, chunked values, all 3 compression strategies, AA replication.
**If all 7 pass with the flag ON, the design is read-correct for the full advertised feature surface.**

If even one fails, we learn something specific about a residual code-path bug. Either result tightens the
production-readiness story for the VT-merge design.
