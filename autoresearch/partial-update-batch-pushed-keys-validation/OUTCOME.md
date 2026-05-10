# OUTCOME — PartialUpdateTest Validation

**Date:** 2026-05-10 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Outcome:** **PARTIAL/FAILED — flag-OFF baseline 7/7
PASS; flag-ON 7/7 FAIL.** See `BLOCKED-NOTES.md` for full triage.

## Summary

The 7 in-scope `PartialUpdateTest` invocations were validated under both flag-OFF and flag-ON. Phase A (flag-OFF
baseline) passed cleanly: all 7 invocations PASS, confirming the branch has not regressed flag-off behavior. Phase B
(flag-ON validation) failed: all 7 invocations FAIL with a uniform symptom (partial UPDATE not visible at read time).
Tier 2 investigation across iterations 1-3 narrowed the root cause to a partition-lifecycle / RocksDB-WAL interaction at
FOLLOWER replicas during batch-push ingestion transitions. Verifying and fixing the bug is out-of-scope for this
validation work-stream per GOAL §9 (cannot modify diagnostic infrastructure or production code).

## Test pass/fail tables

### Flag-OFF (baseline regression check) ✅ 7/7 PASS

| Test                                                           | Result | Wall    |
| -------------------------------------------------------------- | ------ | ------- |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | PASS   | 43.995s |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | PASS   | 53.677s |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | PASS   | 52.049s |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | PASS   | 35.097s |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | PASS   | 28.642s |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`                     | PASS   | 28.733s |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | PASS   | 29.499s |

### Flag-ON ❌ 7/7 FAIL

| Test                                                           | Result | Wall               | Failure                                                                                  |
| -------------------------------------------------------------- | ------ | ------------------ | ---------------------------------------------------------------------------------------- |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | FAIL   | 48.753s (isolated) | `expected [new_name_1] but found [1]`                                                    |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | FAIL   | 390.613s           | `expected [false] but found [true]` (nullRecord=true)                                    |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | FAIL   | 391.615s           | (same null-read symptom)                                                                 |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | FAIL   | 387.892s           | (same null-read symptom)                                                                 |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | FAIL   | 39.866s            | `expected [new_name_4] but found [first_name_4]`                                         |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`                     | FAIL   | 37.940s            | `expected [new_name_3] but found [first_name_3]`                                         |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | FAIL   | 29.718s            | Cascading: `RouterException: no version` (likely test-infra cascade from prior failures) |

## New bugs found

**Bug #6 (suspected, unverified):** FOLLOWER replicas of AA stores under flag-ON intermittently lose merge-operand
writes during batch-push partition adjustments. Per-key inconsistency on the same follower: e.g., for partition=1 on
`dc-0:localhost_45497`, key="5"'s operand is persisted (rawLen=55), but key="3"'s operand is NOT (rawLen=31), even
though both `case UPDATE` handlers ran without exception. The partition-state log shows the follower's RocksDB is
RE-OPENED during ingestion (between the STANDBY transition open and a later `Store-writer-sorted` thread open), creating
a window where merges may land on a closed/orphaned partition reference.

This bug is NOT one of the 5 prior bugs documented in the predecessor experiment's OUTCOME / iter-11-NOTES. See
`BLOCKED-NOTES.md` for full diagnostic data, hypotheses, and recommended next steps.

## Iteration log

| #   | Tier | Action                                                                                                                                                    | Result                                                                                                         |
| --- | ---- | --------------------------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| 1   | 2    | Characterize: ran flag-OFF baseline (7/7 PASS) and flag-ON (7/7 FAIL); confirmed no regression of the 5 prior bugs                                        | All 7 in-scope flag-on invocations FAIL with similar symptoms                                                  |
| 2   | 2    | Promote VT-merge diagnostic logger levels (test infra config only) and re-run isolated invocation; extract server logs from `output.bin` via `strings`    | Identified per-replica inconsistency: leader has operand on disk, at least one follower does not               |
| 3   | 2→3  | Bump aatask/sittask loggers to DEBUG; confirm follower IS calling `case UPDATE` and `storageEngine.merge`; observe per-key inconsistency on same follower | Suspected root cause: partition re-open during ingestion drops some merges. Halt and write `BLOCKED-NOTES.md`. |

## Final commit hashes

No production code modifications were made. The only file touched outside `autoresearch/` was
`internal/venice-test-common/src/integrationTest/resources/log4j2.properties` (temporarily; reverted at end of iter-3 —
verified via clean `git diff`).

## Documents

- `iter-1-NOTES.md` — Phase A baseline + Phase B failure characterization
- `iter-2-NOTES.md` — Diagnostic uplift; first hex dumps; identified leader-vs-follower inconsistency
- `iter-3-NOTES.md` — Confirmed follower IS calling merge; per-key inconsistency on the same replica
- `BLOCKED-NOTES.md` — Full root-cause hypothesis, suspected RocksDB-WAL / partition-lifecycle bug, recommended next
  steps

## Decision per GOAL §7

**FAILED / BLOCKED:** budget approached (3 of 8 iterations used; further iteration constrained by GOAL §9 prohibitions
on modifying diagnostic infrastructure / test code, both of which are necessary to verify the suspected bug). All
findings are documented in `BLOCKED-NOTES.md`.

The flag-OFF baseline is clean (no regression). The 7-test flag-ON validation requires either: (a) New write-path
instrumentation to verify the partition-reopen-loses-merge hypothesis, OR (b) A targeted production-code change to make
the materializing partition's merge robust against partition lifecycle transitions.

Both are out-of-scope per the GOAL constraints; recommend opening a follow-up work-stream specifically to verify and fix
the suspected bug.
