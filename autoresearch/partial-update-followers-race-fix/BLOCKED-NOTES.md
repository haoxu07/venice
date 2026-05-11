# BLOCKED — VT-merge follower-race fix (Bug #6)

**Date:** 2026-05-11 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Iterations used:** 9 of 12. **Outcome:** Phase 1
RCA confirmed in iter-1, but two distinct Phase 2 fixes (close-time memtable flush; PUT-preserves-operand-suffix)
neither individually nor in combination caused the test to pass. Halt per GOAL §4.

## Verdict

**FAILED**: confirmed RCA refinement (per-key inconsistency on the SAME follower partition object — both base+operand
readbacks and operand-only readbacks observed on the same `partition.identityHash` within the same second) but **no
working fix** within the budget.

## Phase 1 evidence (verified, iter-1)

Added `[VT-MERGE-READBACK]` and `[ADJUST-PARTITION]` diagnostics. Re-ran `testPartialUpdateOnBatchPushedKeys[NO_OP]`
flag-on.

For the user store data partitions across 4 servers, 368 follower merge events broke down:

| operandFramedLen | readbackRawLen | count | interpretation                                         |
| ---------------- | -------------- | ----- | ------------------------------------------------------ |
| 23               | 55             | 26    | base + delim + operand = good                          |
| 23               | 23             | 8     | operand only, no base → **MERGE LANDED, BASE MISSING** |
| 24               | 58             | 208   | base + delim + operand = good                          |
| 24               | 24             | 126   | operand only → **MERGE LANDED, BASE MISSING**          |

134 out of 368 (~36%) merges land on a key whose base is missing. The merge succeeded (operand is present in the
readback), but the BASE PUT never made it to this partition, or was wiped out somewhere.

## Phase 1 RCA — partition lifecycle close+reopen window

From iter-1's `[ADJUST-PARTITION]` log: each user-data partition undergoes 3 close+reopen cycles on each replica before
partial updates land:

1. BEGIN_BATCH_PUSH (consumer thread) — partition reopened in batch-write mode
2. END_BATCH_PUSH (drainer thread) — partition reopened in non-batch mode
3. PREPARE_FOR_READ (drainer thread) — partition reopened in read-allowed mode

RocksDB has WAL DISABLED in Venice (`RocksDBStoragePartition` ctor:
`new WriteOptions().setDisableWAL(this.partitionId != METADATA_PARTITION_ID)`). The comment says "flush will be
triggered for every 'sync' to avoid data loss during crash recovery", but `AbstractStorageEngine#closePartition` does
NOT call `sync()` before `partition.close()`. So any unflushed memtable writes are dropped on close.

## Phase 2 fix attempts (all failed)

### Attempt 1 (iter-2): `partition.sync()` before `closePartition` in `adjustStoragePartition`

- Added explicit `partition.sync()` inside `adjustStoragePartition`'s write-lock region.
- Confirmed sync ran via `[ADJUST-PARTITION] SYNC OK` log.
- **Distribution unchanged**: still 134 operand-only events.
- Cause for ineffectiveness: at END_BATCH_PUSH adjust, the partition still has `deferredWrite=true` (BEGIN_BATCH_PUSH
  set it). `RocksDBStoragePartition.sync()` for `deferredWrite=true` only flushes the SST file writer state, NOT the
  memtable. So the memtable contents (operand merges that arrived concurrent to batch push) remained unflushed.

### Attempt 2 (iter-3, iter-6, iter-7, iter-8): `put`-preserves-existing-operand-suffix

- Added a helper in both `MaterializingRocksDBStoragePartition` and the RMD-aware variant that reads `super.get(key)`
  before writing, and if existing bytes include an operand suffix, appends that suffix onto the new framed base before
  `super.put`.
- Theory: cross-region replication or VT-replay can deliver a base PUT to a key that already has operand merges applied.
  A plain `rocksDB.put` would wipe the operand chain; the preserve path concatenates `[BASE][delim][OP*]` so the
  read-fold sees both.
- **Distribution unchanged**.
- Diagnostic `[PUT-PRESERVE]`: **all 400 PUTs report `existingLen=-1`** (no existing value at PUT time). So the preserve
  helper had nothing to preserve in any invocation. This proves the PUT-then-MERGE ordering is correct on each partition
  object; the bug is that the MERGE-only readbacks happen on a DIFFERENT partition object than the PUTs (the
  close+reopen between them).

### Attempt 3 (iter-4, iter-5, iter-8): close-time `rocksDB.flush()` before `rocksDB.close()`

- Added `rocksDB.flush(WAIT_FOR_FLUSH_OPTIONS, columnFamilyHandleList)` in `RocksDBStoragePartition.close()` for
  `!readOnly`. With WAL off, this forces memtable→L0 before close.
- Confirmed flush ran for every user-data partition close via `[CLOSE-FLUSH]` log.
- **Distribution unchanged**: still 134 operand-only events.
- Why it didn't help (hypothesis): the operand-only merges happen on the post-PREPARE_FOR_READ partition object, and
  PREPARE_FOR_READ flushed the prior partition's memtable successfully. So the prior partition's L0 should have the
  bases. But the new partition reads L0 and doesn't find bases. Either (a) the flushed memtable didn't include the bases
  (bases never landed on the prior partition), or (b) the L0 files written by flush aren't visible to the new
  partition's manifest read.

### Combined fix (iter-8): close-flush + put-preserve

- Both fixes applied together. Same result.

## Per-partition pattern (most likely smoking gun)

On dc-0:localhost_37157 user-store partition=2, partition.identityHash=2086272344 (post-PREPARE_FOR_READ), within a
single thread `Store-writer-sorted-t5` over the span of ~1 second:

- 14 readbacks show `readbackRawLen=58` (base+operand: GOOD)
- 14 readbacks show `readbackRawLen=24` (operand only: BUG)

**Exactly half** the keys on this partition have bases. The other half don't.

`dc-0:37157` is a late-joining replica (no BEGIN_BATCH_PUSH adjust on its user-store data partitions, only
PREPARE_FOR_READ at 23:42:54). It opens the partition in non-deferred-write mode, processes some VT messages, then
PREPARE_FOR_READ triggers a close+reopen, then more VT messages are processed.

The per-key inconsistency on the same partition object means:

- For 14 keys: PUT landed, MERGE landed, both visible → readback=58
- For 14 keys: only MERGE landed, no PUT visible → readback=24

This is structurally consistent with **PUT and MERGE arriving on separate ingestion paths or in different timing
windows**, with about half the keys' PUTs not making it to the partition object that's serving the merges.

## What we did NOT try

- **Enabling WAL on data partitions** (`setDisableWAL(false)` for non-metadata): would make memtable contents durable
  across close+reopen without needing explicit flush. Avoided because it's a broader change that could affect
  performance and other partitions. May be the right fix.
- **Removing the chunking-suffix wrap on the UPDATE producer side** to test whether key-mismatch between PUT and UPDATE
  is the actual cause. The wrap is intentional per the iter-11 fix for the sister test; reverting it would break the
  sister test.
- **Examining late-replica VT consumption ordering** to see whether some PUTs are being dropped or routed to a different
  partition than the corresponding UPDATEs. Would require more detailed VT-consumer-side instrumentation.
- **Reading L0 contents directly via RocksDB tools** after a forced PREPARE_FOR_READ to confirm whether the bases that
  ARE flushed end up in L0.

## Recommended next steps (out of budget for this work-stream)

1. **Try enabling WAL** for data partitions when `vt.update.operand.enabled=true`. If the test passes with WAL on, the
   bug is definitively in the close+reopen-without-flush surface and the fix is "enable WAL". Performance cost is the
   trade.
2. **Add per-key partition-state diagnostic** to track which keys' PUTs land on which partition.identityHash, and
   correlate with the operand-only merges. If a key's PUT landed on partition object X1 but its MERGE landed on X2
   (different identityHashes), that's the close+reopen-loses-memtable bug — even if the flush ran, the base was never in
   the memtable at flush time (it was on an earlier partition's memtable that was dropped).
3. **Investigate the late-replica bootstrap path** (the replica that has no BEGIN_BATCH_PUSH adjust). The mechanism by
   which this replica catches up may bypass the partition.put path entirely (e.g., blob transfer / snapshot restore), in
   which case the framing isn't applied — meaning the base on disk isn't in the `[schemaId][KIND_BASE][len][avro]`
   format the read-fold expects, and the merge readback would see "no base" because the base wasn't framed correctly.
4. **Inspect the actual on-disk bytes** for one of the failing keys after the test completes (via RocksDB's `sst_dump`
   or by writing a small test helper). If the on-disk bytes have an unframed base, the merge operator can't produce a
   parseable concat blob and the read-fold rejects it.
5. **Run with `vtMergeMaxChainLength=1`** to force a fold+rewrite on every merge. This would test whether the
   rewrite-PUT inside the chain backstop hits the framing path correctly, indirectly exercising the put-preserve
   scenario.

## Non-regression confirmation

After reverting all production code changes (Phase 2 fix attempts), the repo is back to the baseline state (`c2560b859`
HEAD). Confirmed:

- Flag-OFF `testPartialUpdateOnBatchPushedKeys` (NO_OP / GZIP / ZSTD_WITH_DICT): **3/3 PASS**
- Flag-ON sister test `TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField`:
  **PASS** at 35.844s

So the branch is NOT regressed flag-off, and the sister test still passes flag-on (no regression in the iter-11 fix).

## Final state

Production code: reverted to baseline (no changes).

```
$ git diff --stat
(no changes)
```

`autoresearch/partial-update-followers-race-fix/` contains the iteration notes and this document. Iter-1 RCA is solid;
iter-2 through iter-8 fix attempts are documented as failed-but-informative.

## Iteration log

| #   | Phase | Action                                                               | Result                                                                                         |
| --- | ----- | -------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------- |
| 1   | 1     | Add VT-MERGE-READBACK + ADJUST-PARTITION diagnostics; re-run         | CONFIRMED race; 134/368 operand-only readbacks                                                 |
| 2   | 2     | partition.sync() before close in adjustStoragePartition              | FAILED — sync was no-op for deferredWrite=true path                                            |
| 3   | 2     | put-preserves-existing-operand-suffix in materializing RMD partition | FAILED — diagnostic shows all 400 PUTs had existingLen=-1                                      |
| 4   | 2     | rocksDB.flush() in close() for non-readOnly partitions               | FAILED — flush ran, distribution unchanged                                                     |
| 5   | 2     | iter-4 with CLOSE-FLUSH diagnostic log to confirm flush ran          | Confirmed flush ran; same distribution                                                         |
| 6   | 2     | put-preserve + close-flush combined                                  | FAILED — distribution unchanged                                                                |
| 7   | 2     | put-preserve with PUT-PRESERVE diagnostic                            | All 400 PUTs see existingLen=-1                                                                |
| 8   | 2     | All three fixes combined; per-partition pattern analysis             | Same 134 operand-only events; pattern points to late-replica or cross-consumer-thread ordering |
| 9   | 2/3   | Revert all production code; verify flag-OFF + sister test            | Flag-OFF 3/3 PASS; sister-test flag-ON PASS                                                    |

## Decision

Per GOAL §10 "FAILED / BLOCKED: budget exhausted with at least one failure":

- Confirmed RCA from Phase 1: partition-lifecycle close-without-flush race exists.
- Three distinct Phase 2 fixes were attempted; none made the test pass.
- The actual bug surface is in a layer below where the fixes apply — likely cross-consumer-thread interleaving on the
  partition object, or late-replica bootstrap path. Both are out of scope for what 12 iterations can resolve without
  deeper Venice-internals expertise.

Halt and escalate.
