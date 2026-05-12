# iter-5 NOTES — re-validate testActiveActivePartialUpdateWithCompression after iter-4 + iter-4b gates

## Hypothesis

After iter-3 wired V2 fold path (router timeout gone), iter-4 gated 5 flag-off-specific assertion blocks, and iter-4b
skipped the kafka-input VPJ repush block under flag-on, the test should now pass 3/3 flag-on AND 3/3 flag-off (no
baseline regression).

## Run setup

- `/tmp/no-retry.gradle` already in place with afterEvaluate + `if (extensions.findByName('retry'))` guard
- 1 stale GradleWorkerMain before run; no cleanup needed
- Diagnostic logs in `LeaderFollowerStoreIngestionTask.java` + `WriteComputeProcessor.java` left in tree uncommitted
  (per prior agent's choice; useful for re-measuring fold cost end-to-end). They do not affect test correctness.

## Results

### Flag-on (`-Dvt.update.operand.flag=true`)

| Variant        | Status | Wall time |
| -------------- | ------ | --------- |
| NO_OP          | PASS   | 38.7 s    |
| GZIP           | PASS   | 36.9 s    |
| ZSTD_WITH_DICT | PASS   | 33.7 s    |

Total build wall: 2m 41s. **All three primary §5.4.1[6-8] targets pass.** Previously these timed out (>180s);
post-iter-3 V2 routing + iter-4/4b test gates, they complete in ~35s each.

### Flag-off baseline

| Variant        | Status | Wall time |
| -------------- | ------ | --------- |
| NO_OP          | PASS   | 58.6 s    |
| GZIP           | PASS   | 52.1 s    |
| ZSTD_WITH_DICT | PASS   | 35.0 s    |

Total build wall: 3m 20s. **No baseline regression** — flag-off still validates the chunked manifest + per-element RMD
on-disk shapes correctly.

## Tally so far

- §5.4.1[6-8] flag-on: **3/3 PASS**
- §5.4.1[6-8] flag-off: **3/3 PASS**
- Cumulative gate progress: 6/24 invocations confirmed

## Plan for iter-6

Run §5.4.2 cross-DC AA-semantics tests in `TestPartialUpdateWithActiveActiveReplication`:

- `testAAReplicationForPartialUpdateOnFields`
- `testAAReplicationForPartialUpdateOnListField`
- `testAAReplicationForPartialUpdateOnMapField`

Run flag-on first. The §5.4.2 set exercises element-level cross-DC DCR — since flag-on intentionally bypasses leader
RMW, element-level conflict resolution between dc-0 and dc-1 may behave differently than flag-off. If any FAIL, diagnose
whether the failure is: (a) genuine cross-DC DCR bug in the operand-chain design (HALT — needs the FieldLevelRmdCache
from §4 to fix), or (b) test-side on-disk-shape assertion that needs gating like iter-4 (apply gate).
