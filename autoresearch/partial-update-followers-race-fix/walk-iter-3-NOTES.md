# walk-iter-3 — Phase 4 (H3): Unit-test reproducer for close+reopen merge readback

## Hypothesis tested

**H3**: `MaterializingFoldContextRegistry` state OR partition framing-state is reset/missing at merge time after a
close+reopen, causing operand-only readbacks.

Per GOAL §3 Phase 4 sketch.

## Unit-test reproducer (the experiment)

Added
`clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/MaterializingPartitionCloseReopenRaceTest.java`
with 3 tests:

| Test                                                            | Partition class           | Shape                                |
| --------------------------------------------------------------- | ------------------------- | ------------------------------------ |
| `rmdPartition_putThenCloseReopenThenMerge_baseSurvives`         | AA RMD-aware (production) | 1 key: put → close → reopen → merge  |
| `plainPartition_putThenCloseReopenThenMerge_baseSurvives`       | Plain materializing       | 1 key: put → close → reopen → merge  |
| `rmdPartition_multipleKeys_putCloseReopenMerge_allBasesSurvive` | AA RMD-aware              | 28 keys (matches split-half pattern) |

Each test:

1. Registers a `MaterializingFoldContext` for the store-version
2. Opens partition #1; calls `partition.put(key, framedBaseBytes)`
3. Calls `partition.close()` — same shape as `AbstractStorageEngine#closePartition` (no flush, no sync)
4. Re-opens at the same path with a fresh `MaterializingReplicationMetadataRocksDBStoragePartition`
5. Calls `partition2.merge(key, framedOperand)`
6. Calls `partition2.getRaw(key)` and asserts the readback has `parsed.getBase() != null` (i.e., the operand-only
   readback symptom did NOT manifest)

## Result

**All 3 tests PASS** in <2s total:

```
plainPartition_putThenCloseReopenThenMerge_baseSurvives          PASSED (1.487 s)
rmdPartition_multipleKeys_putCloseReopenMerge_allBasesSurvive    PASSED (188 ms)
rmdPartition_putThenCloseReopenThenMerge_baseSurvives            PASSED (197 ms)
```

The close+reopen by itself, with the production WAL-disabled config and no explicit flush, **does NOT lose the base
bytes** for the partition's value column family in the unit-level scenario. The 28-key test specifically reproduces the
production's "exactly half affected" split shape and finds **0 of 28 keys** lose their base.

## Why the unit test does NOT reproduce the bug (despite stripping the lifecycle to its essence)

The unit test exercises:

- A single thread doing PUT → close → reopen → MERGE
- `deferredWrite=false` partition (not the BEGIN_BATCH_PUSH-mode SST-writer path)
- No concurrent ingestion task or drainer thread
- No `adjustStoragePartition` with its 3 lifecycle modes (BEGIN_BATCH_PUSH → END_BATCH_PUSH → PREPARE_FOR_READ)
- No remote-DC consumer ordering

The bug **must** live in machinery the unit test does NOT exercise. Candidates:

1. **Deferred-write SST writer path**: BEGIN_BATCH_PUSH has `deferredWrite=true`. PUTs go through the SST file writer,
   not the memtable. END_BATCH_PUSH transitions to `deferredWrite=false`. The SST writer's flush behavior on
   close+reopen may interact with the materializing framing in a way the unit test (`deferredWrite=false` throughout)
   doesn't catch.

2. **Cross-thread interleaving**: the production trace from iter-1 shows the same partition object serving GOOD merges
   (base+operand) and BAD merges (operand only) within the same second, on the same Store-writer-sorted-tN thread. The
   split is exactly half, suggesting a structural divider that the unit test can't capture with a single thread.

3. **Late-replica bootstrap path**: per BLOCKED-NOTES, `dc-0:37157` had no BEGIN_BATCH_PUSH adjust, only
   PREPARE_FOR_READ. The mechanism by which this replica catches up (blob transfer / VT replay / snapshot restore) may
   bypass `partition.put`'s framing entirely. The unit test does not simulate this path at all.

## Decision

Per GOAL §3 Phase 4 decision tree:

> **NOT reproduce the bug** (readback is correctly framed) → H3 DISPROVEN at the partition level. The bug is in whatever
> the integration test does that the unit test does not (e.g., concurrent threads, partition map manipulation in
> AbstractStorageEngine, etc.). Advance to integration diagnostic.

**H3 DISPROVEN at unit level.** The simple close+reopen mechanism is not the cause. The 3 unit tests remain in the
codebase as regression guards: if a future change DID introduce a close+reopen base-loss, they would catch it.

## Walk-down state

| Hypothesis           | Result            | Notes                                                        |
| -------------------- | ----------------- | ------------------------------------------------------------ |
| H1 (WAL)             | DISPROVEN         | iter-1: same race symptom with WAL enabled                   |
| H2 (revert iter-11)  | DISPROVEN         | iter-2: same symptom; iter-11 skip is orthogonal             |
| H3 (registry/reopen) | DISPROVEN at unit | iter-3: close+reopen alone doesn't lose base                 |
| H4 (DC splitter)     | not yet tested    | iter-4+: unit-test parity first, then integration diagnostic |

## Next step

Advance to H4. Write a unit test verifying that the partition's `merge` produces identical framing regardless of arrival
source (since at the partition layer, there is no "DC" — just bytes). If the unit test passes (framing is identical),
the splitter must be upstream of `partition.merge`, and the fall-back is integration diagnostic instrumenting the
`case UPDATE` handler with source-topic logging.

Given iters 1-3 have disproven all the cheap hypotheses and only H4 remains (with the integration diagnostic still
requiring a 3-5 min run for confirmation), the walkdown is approaching the budget-exhaustion exit. If H4 also doesn't
yield, will write `BLOCKED-NOTES-WALKDOWN.md`.
