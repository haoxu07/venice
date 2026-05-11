# BLOCKED — Follower-Race Hypothesis Walkdown (H1 → H2 → H3 → H4)

**Date:** 2026-05-11 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Iterations used:** 4 of 11. **Outcome:** **All 4
hypotheses DISPROVEN or AMBIGUOUS at the unit-test sub-step.** Halt per GOAL §3 Phase 4 decision tree and §7 FAILED
criterion.

## Verdict

**FAILED**: each of the 4 hypotheses was investigated at the unit-test sub-step (where viable) and the integration
sub-step (for H1 and H2). None of the 4 produced either (a) a unit-level reproducer that maps to the production race
symptom, or (b) an integration-level fix that makes the 7 in-scope flag-on PartialUpdateTest invocations pass.

## Per-hypothesis verification result

| Hypothesis                                                        | Unit test result                                                                                                           | Integration test result                                                                                                                                        | Verdict                                                                                                                         |
| ----------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| **H1** (Enable WAL on data partitions flag-on)                    | Sanity 4/4 PASS (config wiring) — see iter-1. Test removed since H1 disproven at integration.                              | `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-ON FAILED at 47.4s with `expected [new_name_11] but found [first_name_11]` — same race symptom                | **DISPROVEN** — WAL-on does not eliminate the race                                                                              |
| **H2** (Revert iter-11 early-return for UPDATE)                   | N/A (not unit-testable per GOAL §3)                                                                                        | `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-ON FAILED at 44.5s with same symptom (`expected [new_name_2] but found [first_name_2]`) — no new failure mode | **DISPROVEN** — iter-11 skip is orthogonal to the race                                                                          |
| **H3** (FoldContextRegistry / partition close+reopen state reset) | 4 unit tests PASS — close+reopen with deferred-write, multi-key, RMD & plain variants — all preserve the base after reopen | not run (unit test did not reproduce; would have advanced to integration diagnostic)                                                                           | **DISPROVEN at unit level**; the simple close+reopen mechanism does not lose the base. Tests left in place as regression guards |
| **H4** (Local-DC vs remote-DC structural divider)                 | Partition-layer framing parity PASS — same operand bytes through `partition.merge` produce byte-equal on-disk shape        | not run (would require adding source-topic instrumentation in `case UPDATE` handler then a 3-5 min integration run)                                            | **DISPROVEN at partition layer; upstream divider AMBIGUOUS**                                                                    |

## Unit-level evidence captured (lasting value of this walkdown)

The walkdown left a permanent unit-test surface that catches future regressions on the 4 disproven mechanisms:

`clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/MaterializingPartitionCloseReopenRaceTest.java`
(279 LOC, 5 tests):

| Test                                                                | Guards against                                                             |
| ------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| `rmdPartition_putThenCloseReopenThenMerge_baseSurvives`             | RMD partition losing base on simple close+reopen                           |
| `plainPartition_putThenCloseReopenThenMerge_baseSurvives`           | Plain partition losing base on simple close+reopen                         |
| `rmdPartition_multipleKeys_putCloseReopenMerge_allBasesSurvive`     | Partial-loss split-half pattern across many keys on a single partition     |
| `rmdPartition_deferredWritePutThenSwitchModeThenMerge_baseSurvives` | BEGIN_BATCH_PUSH → END_BATCH_PUSH lifecycle transition losing the SST base |
| `mergeFramingIsPureFunctionOfOperandBytes`                          | Future change introducing per-source-DC branching in `partition.merge`     |

All 5 tests run in <2 seconds total.

## What this walkdown rules in/out

**Ruled out:**

- The bug is **not** a WAL-vs-no-WAL issue. Enabling WAL on flag-on data partitions did not fix it.
- The bug is **not** caused by the iter-11 early-return for UPDATE messages.
- The bug is **not** in the simple close+reopen → memtable-loss surface (verified by unit test).
- The bug is **not** in the deferred-write SST writer → close → reopen cycle when `endBatchWrite` is properly called.
- The bug is **not** in `partition.merge`'s framing logic (it's a pure function of the operand bytes; no DC-dependent
  branching).

**Ruled in (surviving / still ambiguous):**

- **Late-replica bootstrap path** (out of scope per GOAL §9; surfaced in BLOCKED-NOTES of the prior fix work-stream):
  the late-joining replica `dc-0:37157` had no BEGIN_BATCH_PUSH adjust, only PREPARE_FOR_READ. The mechanism by which
  this replica catches up (blob transfer / VT replay / snapshot restore) may bypass `partition.put`'s materializing
  framing override entirely. In that case, the on-disk base bytes would not have the `[schemaId][KIND_BASE][len][avro]`
  framing the read-fold expects, and the merge readback would see "no base."
- **Upstream consumer-thread / DC routing splitter** (H4 fallback, not exercised): if local-DC and remote-DC UPDATEs
  land on different code paths before reaching `partition.merge`, one of those paths could skip the materializing
  framing. The unit test rules out the partition layer as the splitter but does not rule out the upstream routing.
- **Cross-thread interleaving on the partition object** (BLOCKED-NOTES §"Per-partition pattern"): the production trace
  shows exactly half the keys on the same partition object affected within the same second on the same drainer thread.
  The split is too deterministic for naive timing jitter.

## Recommended next steps for a future work-stream

Listed in increasing instrumentation/complexity cost:

1. **Add a `[BLOB-TRANSFER-PUT]` / `[BOOTSTRAP-PUT]` log line** to any code path where a follower receives base bytes
   outside of the standard `partition.put` override (blob transfer, snapshot restore, etc.). Re-run the failing test; if
   any operand-only readback key correlates with a bootstrap PUT (vs. a standard ingestion PUT), the late-replica path
   is the culprit. This is the single most-leveraged next experiment.

2. **Add a per-key `[UPDATE-SOURCE]` log line** to the follower's `case UPDATE` handler logging
   `(storeVersion, partition, key.first8, sourceTopic, kafkaUrl)`. Cross-reference with the `[VT-MERGE-READBACK]`
   operand-only events from iter-1. If the affected keys cluster by source topic/DC, H4's upstream divider is confirmed.

3. **Read the on-disk SST bytes directly** for one operand-only key via `sst_dump` after the test ends. If the bytes
   there have no kind-byte prefix at all (raw Avro), the materializing framing was bypassed for the base PUT — strongly
   suggesting late-replica bootstrap (which uses blob transfer or SST ingestion that doesn't go through the
   partition.put override).

4. **Investigate `RocksDBStoragePartition#beginBatchWrite` checksum supplier interactions**: the
   `MaterializingFraming.setFramingActiveForChecksum` flag is process-global. If a deferred-write partition's SST writer
   computes a checksum with framing-active=true but the actual SST bytes were written without going through the framing
   override (because of a path-divergence we haven't found), the SST might contain unframed bytes — matching the
   symptom.

## What the prior agent (BLOCKED-NOTES.md) and this agent agree on

- The race is real (134/368 operand-only readbacks, ~36%).
- The race is NOT in the memtable-loss-on-close surface (both `partition.sync()` and `flush()` before close were tried;
  neither helped — and this walkdown confirms even with WAL enabled the race persists).
- The split-half pattern (exactly 14/28 keys affected on the same partition within the same second) points to a
  **structural** divider, not random timing.
- The most-leveraged unexplored hypothesis is the **late-replica bootstrap path** (blob transfer bypassing the
  materializing-put framing).

## Test gate status (no production code changes)

After all 4 walkdown iterations, production code is at branch baseline. `git diff --stat` of production code:

```
(empty — no production code modifications survived)
```

Unit-test surface added (regression guards):

```
clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/MaterializingPartitionCloseReopenRaceTest.java | 279 +++++
```

Iter notes added:

```
autoresearch/partial-update-followers-race-fix/walk-iter-1-NOTES.md | 73 +++
autoresearch/partial-update-followers-race-fix/walk-iter-2-NOTES.md | 47 +++
autoresearch/partial-update-followers-race-fix/walk-iter-3-NOTES.md | 92 +++++
autoresearch/partial-update-followers-race-fix/walk-iter-4-NOTES.md | 95 +++++
autoresearch/partial-update-followers-race-fix/BLOCKED-NOTES-WALKDOWN.md | (this file)
```

## Final result table

Single-test results from this walkdown (all integration tests run with `-Dvt.update.operand.flag=true`):

| Test                                                                                         | Iter | Result   | Wall  | Mode        |
| -------------------------------------------------------------------------------------------- | ---- | -------- | ----- | ----------- |
| `RocksDBStoragePartitionWalConfigTest` (H1 sanity, removed)                                  | 1    | 4/4 PASS | ~0.5s | unit        |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-ON (H1: WAL-on)           | 1    | FAIL     | 47.4s | integration |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-ON (H2: iter-11 reverted) | 2    | FAIL     | 44.5s | integration |
| `MaterializingPartitionCloseReopenRaceTest` (H3 simple close+reopen, 3 cases)                | 3    | 3/3 PASS | ~1.9s | unit        |
| `MaterializingPartitionCloseReopenRaceTest` (H3 deferred-write lifecycle)                    | 3    | 1/1 PASS | ~0.2s | unit        |
| `MaterializingPartitionCloseReopenRaceTest.mergeFramingIsPureFunctionOfOperandBytes` (H4)    | 4    | PASS     | 1.5s  | unit        |

The full 15-row test gate was NOT run because no fix candidate passed the single-test gate; per GOAL §3 Phase 5 the gate
only fires after a CONFIRMED + fix exit.

## Decision per GOAL §7

**FAILED**: all 4 hypotheses disproven (or, in H4's case, ruled out at the unit level with the upstream-routing
diagnostic remaining as future work). Per GOAL §11 ("Why this matters"), this walkdown leaves the bug surface precisely
characterized:

- The race is upstream of `partition.merge`'s framing logic
- The race is upstream of (or independent of) the partition close+reopen lifecycle for the value column family
- The race is independent of the WAL-disabled config and the iter-11 path-skip

The remaining unexplored mechanism is **the late-replica bootstrap path** (BLOCKED-NOTES of the prior fix work-stream
§"Recommended next steps" #3). That is the recommended focus for a future work-stream with LinkedIn Venice-internals
expertise.

Halt and escalate.
