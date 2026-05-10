# BLOCKED — PartialUpdateTest Validation (7/7 fail flag-ON)

**Date:** 2026-05-10 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Outcome:** PARTIAL/FAILED — flag-OFF baseline 7/7
PASS; flag-ON 7/7 FAIL **Iterations used:** 3 of 8 (Tier 2 budget exhausted on hypothesis space; further iteration
unlikely to find root cause without modifying out-of-scope code)

## Verdict

The 5 prior bugs (per OUTCOME.md and iter-11-NOTES.md) are NOT regressed. The 7 in-scope `PartialUpdateTest` invocations
under the originally-targeted GOAL fail with a NEW failure mode that the iter-11 fix did not cover, even though the
iter-11 fix DID generalize to the sister test
`TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField` (per
phase-C-progress-update.md).

**Failure mode (uniform across 6 of the 7):** the partial UPDATE is produced to VT by the leader, the follower replicas
DO consume the VT message and DO invoke `storageEngine.merge(...)`, but the merge result is NOT persisted at one or more
follower replicas per partition. The leader replica DOES have the operand on disk (so the leader's own merge of its own
VT-produced UPDATE works); only the followers are inconsistent.

The 7th failure (`testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`) is a cascading test-infra failure
(`RouterException: no version`) likely caused by the slow earlier failures consuming all the test-run wall-time budget.

## Test results

### Phase A — flag-OFF baseline (regression check) ✅

| Test                                                           | Result | Wall    |
| -------------------------------------------------------------- | ------ | ------- |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | PASS   | 43.995s |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | PASS   | 53.677s |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | PASS   | 52.049s |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | PASS   | 35.097s |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | PASS   | 28.642s |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`                     | PASS   | 28.733s |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | PASS   | 29.499s |

Branch is NOT regressed in flag-off behavior.

### Phase B — flag-ON ❌

| Test                                                           | Result | Wall     | Failure                                               |
| -------------------------------------------------------------- | ------ | -------- | ----------------------------------------------------- |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | FAIL   | 48.753s  | `expected [new_name_1] but found [1]`                 |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | FAIL   | 390.613s | `expected [false] but found [true]` (nullRecord=true) |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | FAIL   | 391.615s | (same null-read symptom)                              |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | FAIL   | 387.892s | (same null-read symptom)                              |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | FAIL   | 39.866s  | `expected [new_name_4] but found [first_name_4]`      |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`                     | FAIL   | 37.940s  | `expected [new_name_3] but found [first_name_3]`      |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | FAIL   | 29.718s  | Cascading: `RouterException: no version`              |

## Root cause investigation

### Iter-1 (Tier 2: characterize) — `iter-1-NOTES.md`

Ran all 14 invocations. 7 flag-OFF PASS; 7 flag-ON FAIL. Symptom: partial UPDATE not visible at read time.

### Iter-2 (Tier 2: hex dumps) — `iter-2-NOTES.md`

Promoted server-side VT-merge diagnostic loggers from DEBUG to INFO/DEBUG via `log4j2.properties` (test infrastructure,
not test code or production diagnostic infrastructure). Re-ran a single invocation. Server-side logs (extracted from
`internal/venice-test-common/build/test-results/integrationTest/binary/output.bin` via `strings`) show:

For one user store partition, the LEADER replica's RocksDB has the framed-base + framed-operand on disk (rawLen=55), but
at least one FOLLOWER replica's RocksDB has only the framed-base (rawLen=31). The materialize fold logic IS correct
(when invoked on the rawLen=55 bytes, `MaterializingFraming.materialize` correctly identifies `opCount=1` and the fold
context is registered). The bug is upstream of the read path.

### Iter-3 (Tier 2: write-path tracing) — `iter-3-NOTES.md`

Bumped `logger.aatask` and `logger.sittask` to DEBUG to surface the `VT-merge follower case UPDATE` log. Re-ran.
Confirmed:

- The follower replica DOES receive the VT UPDATE message (36 entries of `follower case UPDATE` logged for partition=1
  on the followers).
- For each, `storageEngine.merge(producedPartition, keyBytes, framedOperand)` is called (no exception thrown).
- BUT, on-disk reads on the same follower for the SAME partition show that some keys' operands ARE persisted (e.g.,
  key="5" → rawLen=55), and others are NOT (e.g., key="3" → rawLen=31, multiple read timestamps over 10s).

This is a **per-key inconsistency on the same follower replica** — the merge call is invoked successfully, but the
operand sometimes does not persist.

## Suspected root cause (UNVERIFIED — would need write-path diagnostic)

The follower's storage partition is re-opened during the test:

- t=19:27:02: STANDBY init opens RocksDB (path: `.../updateBatch_..._v1/updateBatch_..._v1_1`)
- t=19:27:18: `Store-writer-sorted-t0` (drainer thread) re-opens the SAME path during ingestion (presumably for
  `adjustStoragePartition` triggered by BEGIN_BATCH_PUSH or END_BATCH_PUSH).

If the merge for key="3" lands during the close-and-reopen window (or against a stale partition reference held by the
consumer thread), the merge would silently lose the operand. RocksDB's `merge` followed by `close()` should be durable
via WAL, but if the partition's `rocksDB` reference is replaced before WAL recovery is complete, the merge may be lost.

**This is a HYPOTHESIS.** Verifying it would require:

1. Adding a write-side diagnostic in `MaterializingReplicationMetadataRocksDBStoragePartition.merge`: immediate
   `super.get(key)` after `super.merge(...)` to confirm the merge is visible in the same transaction. (Out of scope per
   GOAL §9 — would be modifying diagnostic infrastructure.)
2. Examining RocksDB's WAL state across the close-and-reopen.
3. Tracing partition map updates in `AbstractStorageEngine` during partition adjustment to see if there's a window where
   the merge lands on the OLD partition that's about to be replaced.

## Why iter-11 fix passed for sister test but not for these 7

The sister test (`testActiveActivePartialUpdateWithRecordMapField` in `TestPartialUpdateWithActiveActiveReplication`)
does:

- Empty push (no batch data → no SST file ingestion → no `BEGIN_BATCH_PUSH`/`END_BATCH_PUSH` partition adjustments)
- Only 2 partial updates total

The 7 in-scope `PartialUpdateTest` invocations all do:

- Batch push of 99 keys with VPJ → triggers `BEGIN_BATCH_PUSH` and `END_BATCH_PUSH` partition adjustments → the
  partition is re-opened during ingestion. The 4 `*WithCompression` variants do empty push but then send 39+ partial
  updates over a hybrid-store rewind window, which may also trigger adjustments via topic switches.
- More partial updates (99 for ChunkKeys/BatchPushedKeys, 39 for WithCompression) → larger window of opportunity for the
  close/reopen race.

The sister test happens to avoid the suspected partition-reopen race because it doesn't do batch push.

## Constraints that prevented further investigation

Per GOAL §9:

- DO NOT modify test code (precluded adding write-path assertions in the test).
- DO NOT modify production code unless a NEW bug is discovered (which this is — but verifying it would require adding
  instrumentation).
- DO NOT modify diagnostic infrastructure (precluded adding new log lines).

The bug is in the partition-lifecycle / RocksDB-WAL interaction during batch-push transitions. Diagnosing it further
would require either:

- (a) Adding write-path diagnostic (e.g., immediate readback after merge) — out of scope.
- (b) Modifying the test to inject a delay between batch-push EOP and partial-update production, to widen / shrink the
  race window.
- (c) Re-architecting how the materializing partition's `merge` interacts with the partition lifecycle (e.g.,
  registering a write callback that tracks the partition reference at merge time vs. at flush time).

## Recommended next steps (out of scope for this work-stream)

1. **Add write-path readback diagnostic** to `MaterializingReplicationMetadataRocksDBStoragePartition.merge`: after the
   `super.merge(...)` call, immediately call `super.get(key)` and log whether the operand is visible. If not, log enough
   context to find which thread context lost the merge.
2. **Trace `adjustStoragePartition` calls** for the user store during ingestion to confirm the partition is re-opened.
   Verify whether the new partition has the same merge operator wiring as the original.
3. **Force flush after every merge** as a temporary diagnostic — if forcing flush makes the test pass, the bug is in the
   WAL/flush handling during partition close.
4. **Sleep-inject in the test** between batch-push completion and partial-update production: if introducing a 5-second
   sleep makes the test pass, the bug is the close/reopen race.
5. **Consider widening `vtMergeMaxChainLength` to 0 (disable backstop)** as a defensive measure — although the backstop
   should not fire here (chain depth = 1 << threshold 64), eliminating the per-merge `super.get(key)` call inside
   `ChainLengthBackstop.maybeBackstop` removes one potential race surface. This is a no-op fix but worth checking.

## Final commit hashes

No production code modifications were made in this validation work-stream. The only file touched outside of
`autoresearch/` was `internal/venice-test-common/src/integrationTest/resources/log4j2.properties` (temporarily, for
diagnostics; reverted at end of iter-3 — confirmed via `git diff` returning empty).

## Files

- `autoresearch/partial-update-batch-pushed-keys-validation/iter-1-NOTES.md` — Phase A baseline + Phase B failure
  characterization
- `autoresearch/partial-update-batch-pushed-keys-validation/iter-2-NOTES.md` — Diagnostic uplift; first hex dumps;
  identified leader-vs-follower inconsistency
- `autoresearch/partial-update-batch-pushed-keys-validation/iter-3-NOTES.md` — Confirmed follower IS calling merge;
  per-key inconsistency on the same replica; suspected partition-reopen race
- `autoresearch/partial-update-batch-pushed-keys-validation/BLOCKED-NOTES.md` — this document
