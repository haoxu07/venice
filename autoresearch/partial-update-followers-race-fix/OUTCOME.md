# OUTCOME — VT-merge follower-race fix

**Date:** 2026-05-11 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Outcome:** **FAILED / BLOCKED.** RCA confirmed in
Phase 1; three Phase 2 fix attempts (memtable-flush before close, put-preserves-operand-suffix, both combined) did not
make the test pass. Halt per GOAL §4 budget exhaustion. Full details in `BLOCKED-NOTES.md`.

## Summary

Phase 1 (iter-1) confirmed the suspected partition-lifecycle close+reopen race using new diagnostics:

- `[VT-MERGE-READBACK]` in materializing partitions' `merge` overrides
- `[ADJUST-PARTITION]` in `AbstractStorageEngine#adjustStoragePartition`

134 out of 368 follower merge readbacks (~36%) returned operand-only bytes (`readbackRawLen == operandFramedLen`),
indicating the base PUT was missing from RocksDB at merge time. RocksDB's WAL is disabled in Venice; `closePartition`
does NOT call `sync()` before `partition.close()`, so unflushed memtable contents are dropped. The 3 close+reopen cycles
per user-data partition per replica (BEGIN_BATCH_PUSH → END_BATCH_PUSH → PREPARE_FOR_READ) create three opportunities
for in-memtable PUTs and MERGEs to be lost.

Phase 2 attempted three independent fixes, all of which left the test still failing with the same readback distribution:

| Iter | Fix attempted                                           | Effect on 134-event count                               |
| ---- | ------------------------------------------------------- | ------------------------------------------------------- |
| 2    | partition.sync() before close in adjustStoragePartition | None                                                    |
| 3    | put-preserves-existing-operand-suffix on RMD partition  | None                                                    |
| 4    | rocksDB.flush() in RocksDBStoragePartition.close()      | None                                                    |
| 6    | iter-3 + iter-4 combined                                | None                                                    |
| 7    | iter-6 + PUT-PRESERVE diagnostic                        | Showed all 400 PUTs had existingLen=-1                  |
| 8    | All combined; per-partition pattern analysis            | Same; exactly half keys per partition show operand-only |

The per-partition pattern (exactly half the keys on the same partition object showing operand-only readbacks within the
same second of activity) suggests the bug surface is either:

1. **Late-joining replicas bypass the materializing-put framing** (e.g., via blob transfer or snapshot restore), so the
   base on disk doesn't have the `[schemaId][KIND_BASE][len]` structure the merge readback expects.
2. **Cross-consumer-thread interleaving** on the partition object — PUTs and MERGEs arrive on different consumer threads
   (local-DC VT vs remote-DC VT) for the same key, and the close+reopen between them drops some writes.

Neither was diagnosable within the 12-iteration budget without significant additional instrumentation.

## Final test results

After reverting all production code attempts back to the iter-1 baseline state:

| Test                                                                                                   | Result | Wall    |
| ------------------------------------------------------------------------------------------------------ | ------ | ------- |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-ON                                  | FAIL   | 46.5s   |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-OFF                                 | PASS   | 38.66s  |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[GZIP]` flag-OFF                                  | PASS   | 30.165s |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]` flag-OFF                        | PASS   | 30.857s |
| `TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField` flag-ON | PASS   | 35.844s |

Branch is NOT regressed on flag-OFF or on the sister test. The original Phase B problem (7/7 flag-on FAIL) remains.

## Production code changes

**None.** All Phase 2 fix attempts were reverted at iter-9 to return the production code to baseline. `git diff` is
empty (only the autoresearch/ documentation files are new).

## Iteration documents

- `iter-1-NOTES.md` — Phase 1 diagnostic instrumentation + RCA CONFIRMED
- `iter-2-NOTES.md` — Phase 2 attempt 1: partition.sync() before close — FAILED
- `iter-3-NOTES.md` — Phase 2 attempt 2: put-preserves-operand-suffix — FAILED
- `iter-4-NOTES.md` — Phase 2 attempts 3 (close-flush) + 4 (combined) + diagnostics — FAILED
- `BLOCKED-NOTES.md` — Full triage with recommended next steps for human follow-up
- `OUTCOME.md` — this document

## Recommended next steps for follow-up work-stream

See `BLOCKED-NOTES.md` §"Recommended next steps". Top 3:

1. **Try `setDisableWAL(false)`** for data partitions when `vt.update.operand.enabled=true`. Tests whether enabling WAL
   eliminates the race entirely (with a perf cost).
2. **Add per-key partition-tracking diagnostic** to verify which partition object received each key's PUT vs which
   received its MERGE. Correlates the close+reopen timing precisely.
3. **Inspect the late-replica bootstrap path** (blob transfer / VT replay) to determine whether it goes through the
   materializing-put framing override.

## Decision per GOAL §7

**FAILED / BLOCKED**: budget exhausted (9 of 12 iterations used; iter-9 used for cleanup

- regression verification). Confirmed RCA from Phase 1 documented. Three Phase 2 fixes attempted, all failed. Production
  code reverted to baseline; only autoresearch documentation files are added.
