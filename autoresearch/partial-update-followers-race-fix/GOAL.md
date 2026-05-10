# Follower-Partition-Lifecycle Race Fix — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-10 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation;
production-code fix) **Execution model:** Autonomous agent. Three phases (verify → fix → validate). 12-attempt total
budget split across phases. Production code changes ARE authorized (this is the distinguishing feature of this
work-stream vs the prior validation). **Estimated effort:** 1–2 sessions. Best case (RCA confirmed first try, fix
small): ~2 hours. Worst case (RCA disproven OR fix requires architectural change): hit budget at iter 12, write
`BLOCKED-NOTES.md`, halt.

---

## 0. Background

The prior validation work-stream `autoresearch/partial-update-batch-pushed-keys-validation/` (commit `481b5a6cd`)
discovered **Bug #6** — a follower-partition-lifecycle race that loses merge writes during batch-push partition
adjustments. All 7 in-scope `PartialUpdateTest` invocations FAIL flag-ON because of it; all 7 PASS flag-OFF. The 5 prior
known bugs are NOT regressed.

**Required reading before the agent starts** (all in `autoresearch/partial-update-batch-pushed-keys-validation/`):

- `BLOCKED-NOTES.md` — full triage with hex dumps, server-log timeline, suspected RCA, 5 recommended next steps
- `OUTCOME.md` — top-level result
- `iter-3-NOTES.md` — the per-key-inconsistency observation that pinned the bug to follower-side persistence
- `iter-2-NOTES.md` — hex dump evidence
- `iter-1-NOTES.md` — initial characterization

### The hypothesis to verify (verbatim from BLOCKED-NOTES.md §"Suspected RCA")

> The follower's storage partition is re-opened during the test:
>
> - t=19:27:02: STANDBY init opens RocksDB
> - t=19:27:18: `Store-writer-sorted-t0` (drainer thread) re-opens the SAME path during ingestion (presumably for
>   `adjustStoragePartition` triggered by BEGIN_BATCH_PUSH or END_BATCH_PUSH).
>
> If the merge for key="3" lands during the close-and-reopen window (or against a stale partition reference held by the
> consumer thread), the merge would silently lose the operand. RocksDB's `merge` followed by `close()` should be durable
> via WAL, but if the partition's `rocksDB` reference is replaced before WAL recovery is complete, the merge may be
> lost.

### Why the bug manifests only on the 7 in-scope tests (and not on the sister test we re-verified earlier)

`TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField` uses an **empty push**
start — no `BEGIN_BATCH_PUSH` / `END_BATCH_PUSH` partition adjustments. The 7 `PartialUpdateTest` invocations all do
batch push of 99 keys (or a hybrid-store rewind), which triggers the close-and-reopen window where the race manifests.

## 1. Goal

Verify the partition-lifecycle race hypothesis and apply a minimal fix so that **all 7 in-scope `PartialUpdateTest`
invocations pass flag-ON**, with flag-OFF behavior preserved.

1. **Verify**: confirm the close-and-reopen race is the actual cause (or refute the hypothesis with evidence)
2. **Fix**: apply minimal production code change to eliminate the race
3. **Validate**: all 7 flag-on + all 7 flag-off PASS (14/14)

**Non-goal:** "make tests pass at any cost." A workaround that masks the race (e.g., synchronous flush after every
merge) is acceptable as a Phase 2 diagnostic to confirm the hypothesis, but must be replaced with a proper fix before
Phase 3 declares success. Document the trade.

## 2. Scope

### In scope

- **Production code modifications** in `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/...`
  and `kafka/consumer/...` are AUTHORIZED for both verification (diagnostic) and fix
- Adding new diagnostic log lines (and removing them later if desired)
- The 4 suggested verification approaches from `BLOCKED-NOTES.md` §"Recommended next steps":
  1. Add immediate read-after-merge diagnostic in `MaterializingReplicationMetadataRocksDBStoragePartition.merge`
  2. Trace `adjustStoragePartition` calls
  3. Force flush after every merge (diagnostic-only confirmation)
  4. Sleep-inject in the test temporarily (diagnostic-only; revert before Phase 3)
- Fixing the race via whichever of these proves most appropriate:
  - Partition-reference revalidation in the merge path (e.g., re-acquire from `engine.getPartitionOrThrow` immediately
    before `partition.merge`)
  - Synchronization between `adjustStoragePartition` and concurrent ingest threads
  - Holding a partition-stable-reference guard during the case-UPDATE handler
  - Other mechanism the agent discovers

### Out of scope

- **Changing the on-disk wire format** (`ConcatBlobParser` framing stays)
- **Changing the leader-side write contract** (leader continues to produce UPDATE operands)
- **Redesigning `adjustStoragePartition`** broadly — only fix the case-UPDATE-during-adjust race; do not rewrite the
  partition lifecycle
- **Modifying test code** to make tests pass (no fixture mutations)
- **Changing flag-OFF behavior** in any way
- **Disabling the chain backstop** as a "fix" (the backstop is orthogonal; it could even hide the bug if removed but
  won't fix it)
- **Performance optimization** — correctness only

## 3. Phased plan

### Phase 1 — Verify the race (iter 1-3)

**Goal:** confirm or refute the hypothesis that `adjustStoragePartition` causes a stale-partition-reference race that
loses merges.

**Build:**

- Add a write-path readback diagnostic in
  `MaterializingReplicationMetadataRocksDBStoragePartition.merge(byte[], byte[])` (and
  `MaterializingRocksDBStoragePartition.merge` if applicable):
  ```
  super.merge(key, operand);
  byte[] readback = super.get(key);
  LOGGER.info("[VT-MERGE-READBACK] partition.id={} partition.identityHash={} key.firstBytes={} mergeReturnedRawLen={} readbackRawLen={}",
      this.partitionId, System.identityHashCode(this), Bytes.toString(key), operand.length, readback==null ? -1 : readback.length);
  ```
  Goal: if `mergeReturnedRawLen > 0` but `readbackRawLen` doesn't include the operand, we've confirmed the merge LANDED
  on the partition object but didn't persist. If `readbackRawLen` always matches expected, the race is elsewhere (e.g.,
  the consumer holds the wrong partition reference).
- Add an `[ADJUST-PARTITION]` log line at every site that calls `adjustStoragePartition` (search the codebase). Include
  thread name, partition id, calling code site, partition.identityHash before+after.

**Verify:**

- Re-run `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on (single isolated invocation)
- Grep server logs for `[VT-MERGE-READBACK]` + `[ADJUST-PARTITION]`
- Identify the failing key (e.g., key="3" from prior iter-3 evidence)
- Cross-reference: does the key="3" merge happen on a partition that's about to be (or has just been) re-opened?

**Exit criterion for Phase 1:**

- **CONFIRMED**: a specific log pattern shows merges landing on a stale partition reference OR persisting to a partition
  that gets superseded by adjustStoragePartition → advance to Phase 2
- **DISPROVEN**: readback always returns the merged value at the time of merge, but later reads see the un-merged value
  → the race is somewhere else; write `BLOCKED-NOTES.md` and halt (this would suggest a RocksDB WAL bug or a
  snapshot-isolation issue, which is genuinely out of our control)
- **AMBIGUOUS** after 3 iters: write `BLOCKED-NOTES.md`, halt

### Phase 2 — Apply minimal fix (iter 4-9)

**Goal:** eliminate the race with the smallest possible production code change.

**Build (depends on Phase 1's confirmation):**

- If the race is "stale partition reference held by the consumer thread": fix by re-acquiring the partition via
  `engine.getPartitionOrThrow(partitionId)` inside the case-UPDATE handler, immediately before calling
  `partition.merge`. This is a 5-10 LOC change in `StoreIngestionTask` or `ActiveActiveStoreIngestionTask`.
- If the race is "partition map updated between consumer's get-partition and partition.merge": fix by holding the
  partition map's lock (or a finer-grained partition-lifecycle lock) during the case-UPDATE handler's merge call.
- If the race is "RocksDB WAL not flushed before close" during `adjustStoragePartition`: fix by ensuring
  `partition.sync()` (or equivalent) is called before any partition replacement.
- **Document the chosen fix's mechanism explicitly** in an `iter-N-NOTES.md`.

**Verify per fix attempt:**

- Run `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on (isolated). If PASS, move to next test.
- If 3 isolated invocations PASS, run the full 7 flag-on invocations.
- For each failure during Phase 2, iterate on the fix (within budget).

**Exit criterion for Phase 2:**

- All 7 flag-on PartialUpdateTest invocations PASS (with the fix applied, retries=0, fail-fast)
- The flag-OFF baseline is re-confirmed PASS (regression check)

### Phase 3 — Validate + cleanup (iter 10-12)

**Build:**

- Remove the diagnostic `[VT-MERGE-READBACK]` and `[ADJUST-PARTITION]` log lines added in Phase 1 (or downgrade to
  DEBUG)
- Restore `log4j2.properties` to its branch-HEAD state (the prior validation agent already reverted; verify)
- Verify the fix is minimal (`git diff` should be focused on 1-2 files, ideally < 30 LOC of production code change)

**Verify:**

- Run all 14 invocations (7 flag-on + 7 flag-off) under the same gradle init script + `--fail-fast` configuration as the
  validation work-stream:
  ```
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateWithCompression" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast
  ```
- Plus run `TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField` flag-on (the
  sister test that was passing pre-fix; confirm we haven't broken it):
  ```
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast
  ```

**Exit criterion for Phase 3:**

- All 7 flag-on PartialUpdateTest invocations PASS
- All 7 flag-off PartialUpdateTest invocations PASS
- TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField flag-on PASS
- `git diff` is minimal (preferably < 30 LOC of production code change)
- All diagnostic log lines removed or downgraded to DEBUG
- Write `OUTCOME.md` documenting the fix's mechanism, the test results, and any follow-up risks

## 4. Iteration policy

**12-attempt total budget across all 3 phases.** Bug-clustering principle applies: a single fix that unlocks multiple
invocations counts as ONE iteration.

| Phase                        | Iter range | Action if budget exhausted                                                                                      |
| ---------------------------- | ---------- | --------------------------------------------------------------------------------------------------------------- |
| Phase 1 (verify)             | 1-3        | If RCA not confirmed/disproven by iter 3 → `BLOCKED-NOTES.md`, halt                                             |
| Phase 2 (fix)                | 4-9        | If race confirmed but fix elusive by iter 9 → `BLOCKED-NOTES.md` with the confirmed RCA + attempted fixes, halt |
| Phase 3 (validate + cleanup) | 10-12      | If regression on the sister test by iter 12 → `BLOCKED-NOTES.md`, halt                                          |

Each iteration produces an `iter-N-NOTES.md` with:

- Hypothesis / fix attempt
- Code change made (file paths + line counts)
- Test result (PASS / FAIL with key log excerpts)
- Decision: continue to next iter / pivot strategy / escalate

## 5. Test gates

| Gate         | Threshold                                                                                                  |
| ------------ | ---------------------------------------------------------------------------------------------------------- |
| Phase 1 exit | Log evidence either confirming or refuting the partition-reference race hypothesis                         |
| Phase 2 exit | All 7 flag-on PartialUpdateTest invocations PASS in isolation + flag-OFF regression PASS                   |
| Phase 3 exit | All 14 PartialUpdateTest invocations + sister-test (1 more) flag-on PASS = 15/15. Diagnostic logs removed. |

## 6. Reference files (must-read before debugging)

### Suspected race surface

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/AbstractStorageEngine.java` — where the partition
  map lives; `adjustStoragePartition` likely modifies it
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStorageEngine.java` —
  RocksDB-specific partition lifecycle (open/close/reopen logic)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingReplicationMetadataRocksDBStoragePartition.java`
  — the AA wrapper's `merge` override
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingRocksDBStoragePartition.java` —
  non-AA materializing wrapper
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTask.java` —
  `processKafkaDataMessage` case UPDATE; routes to `storageEngine.merge`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java` —
  AA-specific overrides
- Search for `adjustStoragePartition` callers — likely in StoreIngestionTask around BEGIN_BATCH_PUSH / END_BATCH_PUSH

### Prior diagnostic infrastructure (use as-is unless extending)

- `services/venice-server/src/main/java/com/linkedin/venice/listener/StorageReadRequestHandler.java` —
  `[SERVER-RECV-GET]`, `[SERVER-AFTER-GET]`, `[SERVER-DESERIALIZE-FAIL]`
- `MaterializingReplicationMetadataRocksDBStoragePartition.get(ByteBuffer)` — hex-dump diagnostic for raw on-disk bytes

### Test

- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/endToEnd/PartialUpdateTest.java`

## 7. Decision criteria

**YES (success):** All 15 invocations PASS (14 in-scope + 1 sister-test regression check). Diagnostic logs cleaned up.
`OUTCOME.md` written with fix mechanism. Commit hash captured.

**PARTIAL:** 7 flag-on PASS but sister test fails / flag-off regresses. Document and halt; this means the fix broke
something else. Write `BLOCKED-NOTES.md` with the regression.

**FAILED / BLOCKED:** budget exhausted with at least one failure. Write `BLOCKED-NOTES.md` with:

- Confirmed RCA (if Phase 1 reached confirmation)
- Each fix attempt's mechanism + why it didn't work
- Remaining diagnostic data (hex dumps, log excerpts)
- Recommended next steps for human follow-up

## 8. Risks

| Risk                                                                           | How we'd see it                                                                      | Response                                                                                                                   |
| ------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------ | -------------------------------------------------------------------------------------------------------------------------- |
| Hypothesis disproven (race isn't the cause)                                    | Phase 1 readback diagnostic shows merge persists at write time, but later reads fail | Investigate the actual mechanism — could be RocksDB compaction-time merge issue, snapshot isolation, etc. Likely escalate. |
| Fix is more invasive than expected (touches > 50 LOC of production code)       | Iter 5+ involves redesigning partition lifecycle                                     | Halt; document the design tension; this becomes a larger work-stream than this GOAL covers                                 |
| Fix breaks the sister test (`testActiveActivePartialUpdateWithRecordMapField`) | Phase 3 validation finds regression                                                  | Iterate within Phase 3 budget; if can't reconcile, halt                                                                    |
| Fix breaks flag-OFF behavior                                                   | Phase 3 flag-OFF regression                                                          | Iterate within Phase 3 budget; if can't reconcile, halt                                                                    |
| The race is RocksDB-internal (WAL not flushed before close)                    | Phase 1 confirms merge persists temporarily but is lost on close                     | Add a `partition.sync()` call before close; if effective, ship that fix                                                    |
| Pre-commit hook fails on prettier `npm install`                                | Spotless gradle task fails                                                           | Workaround: `npm install --registry=https://registry.npmjs.org/` in `build/spotless-node-modules-prettier-format/`         |
| `--fail-fast` masks compounding failures                                       | First test passes after fix; second test fails due to a different race               | Run all 7 without `--fail-fast` in Phase 3 to surface all remaining issues                                                 |

## 9. Non-goals

- This is **not** about general partition-lifecycle refactoring. Only fix the case-UPDATE-during-adjust race.
- This is **not** about performance — correctness only. If a synchronous flush after every merge is the only way to fix
  the race, that's an acceptable Phase 2 outcome (just document the performance trade in OUTCOME.md).
- This is **not** about retrofitting the iter-11 fix. That fix is good for its specific bug; this work-stream is a
  different bug.
- This is **not** about validating new tests beyond the 7 in-scope + the 1 sister regression check.
- This is **not** about productionizing the VT-merge design beyond fixing this specific race. Flag-on default stays OFF.

## 10. Final report shape

When done (success OR halt), write a single message back with:

- One-paragraph verdict (PASS / PARTIAL / FAILED with headline mechanism)
- For PASS: the fix's mechanism in 1-2 sentences, plus the commit hash
- 15-row table: each invocation (7 flag-on + 7 flag-off + 1 sister regression check) with result + wall time
- Files modified, line counts (`git diff --stat` for the final commit)
- Any follow-up risks or technical debt the fix introduces
- `OUTCOME.md` location

If `BLOCKED-NOTES.md` was written, include its contents verbatim in the final report.

## 11. Why this matters

The original Phase C work-stream halted at "Tier 3 design issue" with the right suspicion (RocksDB JNI / merge operator
interaction). The prior validation work-stream pinned it more precisely to **follower-side persistence during batch-push
partition adjustments**. This work-stream tries to close the loop entirely:

- **If fixed**: the VT-merge design is fully read-correct for the originally-advertised feature surface (scalar SET, map
  merge, list merge, chunked, all compression strategies, AA replication, post-batch-push partial-update workflow).
  That's production-readiness in correctness terms.
- **If RCA confirmed but fix elusive**: we have a precisely-characterized bug to hand to a future
  LinkedIn-Venice-internals expert. Significantly better than the prior session's "Tier 3 design issue" formulation.
- **If RCA disproven**: we learn the bug is elsewhere (possibly RocksDB-internal). Either way, the diagnostic
  infrastructure built here makes the next investigation easier.

Either result tightens the production-readiness story for VT-merge. Failure to fix is acceptable; failure to learn is
not.
