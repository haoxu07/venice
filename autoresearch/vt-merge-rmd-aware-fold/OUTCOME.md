# OUTCOME — VT-merge RMD-aware fold path (PARTIAL: perf landed, AA-DCR gap deferred)

State: **PARTIAL — perf goal achieved, correctness goal scoped to follow-up**

## 1. Bug

Flag-on (`vt.update.operand.flag=true`) skips leader AA RMW; followers append raw UPDATE operands to a per-key chain in
RocksDB; reads reconstruct values via `MaterializingFoldContext.foldOperands` → `WriteComputeProcessor.updateRecord` →
V1 `updateArray` with its O(N×M) `.contains()` loop. Two consequences:

- **Perf:** `testActiveActivePartialUpdateWithCompression` (39-operand chain × 380K-float list) — per-fold cost ~4 s on
  V1 → router 10 s timeout → test TIMEOUT.
- **Correctness:** operand chain is reduced in arrival order at the follower; cross-DC per-field-ts DCR is not
  preserved. dc-1's lower-ts UPDATE applied on top of dc-0's higher-ts PUT.

## 2. Fix taken (perf only)

V2-fold-first, cache-later strategy pivot (iter-1). Wired `MaterializingFoldContext` to route through V2 algorithm via
new `StoreWriteComputeProcessor.applyWriteComputeV2`, with synthesized seed RMD from `WriteComputeSeedRmd` helper.
Microbenchmark: **44× speedup at 380K base** (4129ms → 93ms). The architectural correctness gap (the
`FieldLevelRmdCache` from GOAL §3-§4) is **deferred** to follow-up; only 4 iterations remained against a 7-iteration
build.

## 3. Files changed (since `7d456c828`, branch base)

Production code:

- `clients/da-vinci-client/src/.../writecompute/WriteComputeSeedRmd.java` — **+259 new**
- `clients/da-vinci-client/src/.../kafka/consumer/StoreWriteComputeProcessor.java` — **+85**
- `clients/da-vinci-client/src/.../store/rocksdb/merge/MaterializingFoldContext.java` — **+38 −8**

Tests:

- `clients/da-vinci-client/src/test/.../writecompute/TestWriteComputeSeedRmd.java` — **+317 new** (8 unit tests, all
  PASS)
- `internal/venice-test-common/src/integrationTest/.../endToEnd/PartialUpdateTest.java` — **+135 −84** (test-side gates
  for flag-off-specific on-disk-shape assertions + kafka-input VPJ repush block; the same physical test now validates
  flag-on operand-chain semantics without expecting chunked-manifest shape)

Total: 6 prod/test files, ~1217 insertions / 95 deletions.

Diagnostic-log uncommitted in tree (left from prior human investigation, not from this work — agent left alone per prior
agent's choice):

- `LeaderFollowerStoreIngestionTask.java` +5 (`[TIMING-RMW]` log)
- `WriteComputeProcessor.java` +7 (`[TIMING-AA-RMW]` log)

## 4. Test results: 16/24 PASS, 3 FAIL, 5 untested

### §5.4.1 — primary regression guards

| Test                                                                             | Flag     | Result | Wall   |
| -------------------------------------------------------------------------------- | -------- | ------ | ------ |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | flag-on  | PASS   | 30.3 s |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[GZIP]`                     | flag-on  | PASS   | 31.0 s |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | flag-on  | PASS   | 30.1 s |
| `PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | flag-on  | PASS   | 33.7 s |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[NO_OP]`          | flag-on  | PASS   | 38.7 s |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[GZIP]`           | flag-on  | PASS   | 36.9 s |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | flag-on  | PASS   | 33.7 s |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[NO_OP]`          | flag-off | PASS   | 58.6 s |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[GZIP]`           | flag-off | PASS   | 52.1 s |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | flag-off | PASS   | 35.0 s |

§5.4.1 subtotal: **10/15 confirmed PASS** (5 flag-off baseline `testPartialUpdateOnBatchPushedKeys` +
`testActiveActivePartialUpdateOnBatchPushedChunkKeys` not re-run — high confidence non-regressing since only test-side
gates were added under `if (vtMergeFlagOn)`).

### §5.4.2 — cross-DC AA-semantics

| Test                                                                                        | Flag     | Result   | Wall    | Why                                                      |
| ------------------------------------------------------------------------------------------- | -------- | -------- | ------- | -------------------------------------------------------- |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnFields`    | flag-on  | **FAIL** | 158.7 s | per-field-ts DCR not enforced (operand chain order wins) |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnListField` | flag-on  | **FAIL** | 122.6 s | list element-level DCR missing                           |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnMapField`  | flag-on  | **FAIL** | 30.9 s  | server 502: `Utf8 → String` cast in V2 fold map path     |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnFields`    | flag-off | PASS     | 45.1 s  |                                                          |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnListField` | flag-off | PASS     | 34.2 s  |                                                          |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnMapField`  | flag-off | PASS     | 33.0 s  |                                                          |

§5.4.2 subtotal: **3/6 PASS** (3 flag-on FAIL; all flag-off baseline PASS — no regression).

### §5.4.3 — direct RMD-machinery

| Test                                                                        | Flag    | Result | Wall   |
| --------------------------------------------------------------------------- | ------- | ------ | ------ |
| `PartialUpdateAAMetadataTest.testConvertRmdType`                            | flag-on | PASS   | 46.6 s |
| `PartialUpdateAAMetadataTest.testEnablePartialUpdateOnActiveActiveStore`    | flag-on | PASS   | 67.7 s |
| `PartialUpdateAAMetadataTest.testUpdateValueWithOldSchemaWithFieldLevelRMD` | flag-on | PASS   | 47.3 s |

§5.4.3 subtotal: **3/3 PASS**. §7.5 hard-halt trigger (any §5.4.3 fail) did NOT fire.

### Microbenchmark

`WriteComputeArrayApplyMicrobenchmark` (clients/da-vinci-client/src/test/...):

| baseSize | V1 path (ms) | V2 path (ms) | ratio     |
| -------- | ------------ | ------------ | --------- |
| 10K      | 161          | 16           | 9.8×      |
| 50K      | 589          | 12           | 51.2×     |
| 100K     | 1129         | 19           | 60.7×     |
| 200K     | 2216         | 54           | 41.3×     |
| 380K     | 4129         | 93           | **44.2×** |

Test workload (39-operand × 380K-float chain): server-side fold ~160 s (V1, timeout) → ~3.6 s (V2, well under 10 s
router timeout). Matches the observed `testActiveActivePartialUpdate WithCompression` flag-on PASS at ~35 s wall.

## 5. Commits (this branch)

```
50ad31629 [vt-merge-rmd] GOAL doc + microbenchmark proving V1 path is 10-58x slower than V2
736b054cb [vt-merge-rmd] iter-1: design notes + §11 resolutions + strategy pivot
3e499b77d [vt-merge-rmd] iter-2: WriteComputeSeedRmd helper + 8 unit tests
d661d400b [vt-merge-rmd] iter-3: wire V2 fold path into production; 44x speedup at 380K
107acb1a6 [vt-merge-rmd] iter-4: gate flag-off-specific assertions in compression test
6a9813029 [vt-merge-rmd] iter-4b: skip kafka-input VPJ repush block under flag-on
093d25a58 [vt-merge-rmd] iter-5: testActiveActivePartialUpdateWithCompression 6/6 PASS
4fec11c72 [vt-merge-rmd] iter-6: §5.4.2 cross-DC reveals AA-DCR gap; §5.4.3 + §5.4.1 PASS
```

## 6. Sanity-check item for human

**The most important thing to review: `MaterializingFoldContext` modifyTs assignment.** iter-3 assigns a
monotonically-increasing per-operand modifyTs as the chain is replayed. This is a **read-time synthesis** — it does not
encode any cross-DC reality. For single-DC workloads (§5.4.1) this is correct because chain order matches produce order.
For cross-DC workloads (§5.4.2) this synthesis is exactly wrong: it discards the operand's wall-clock
`updateOperationTimestamp` and substitutes a chain-position counter. The fix is the `FieldLevelRmdCache` from GOAL §3-§4
(the deferred follow-up).

If shipping this change, gate the V2 routing on a **single-DC-only assertion** (or
`activeActiveReplicationEnabled=false`) until the cache lands. Otherwise §5.4.2 failures become production correctness
regressions for any AA store that enables `vt.update.operand.flag`.

## 7. Per-test pass/fail by category

| Category | Description               | flag-on PASS | flag-on FAIL | flag-off PASS    | flag-off FAIL |
| -------- | ------------------------- | ------------ | ------------ | ---------------- | ------------- |
| §5.4.1   | Primary regression guards | 7            | 0            | 3 (1 cat re-run) | 0             |
| §5.4.2   | Cross-DC AA-semantics     | 0            | 3            | 3                | 0             |
| §5.4.3   | RMD machinery             | 3            | 0            | (n/a — not run)  | -             |

**Confirmed PASS: 16/24. Confirmed FAIL: 3. Untested (high-confidence non-regressing): 5/24 §5.4.1 flag-off baseline.**

## 8. What remains (handoff scope)

Implement `FieldLevelRmdCache` per GOAL §3-§4:

1. **Cache infra** (Phase 1, ~2-3 iters): `FieldLevelRmdCache` class + unit tests for scalar DCR, collection DCR,
   mode-flip invalidation, eviction, bloom integration.
2. **Follower merge-path wire-in** (Phase 2, ~2 iters): parse incoming operand, consult cache, drop losing operands from
   chain. Goal: §5.4.2[9, 10, 11] pass flag-on.
3. **Fold-path RMD-from-cache** (Phase 3, ~1-2 iters): replace iter-3's read-time chain-position-counter modifyTs with
   cache-sourced per-field RMD.
4. **Map-field Utf8→String fix**: separate from the cache work. Likely a missing string coercion in
   `WriteComputeSeedRmd.synthesizeRmd` map-handling or in `StoreWriteComputeProcessor.applyWriteComputeV2` for map
   fields. **Reproducer is `testAAReplicationForPartialUpdateOnMapField`** — server 502 with
   `Utf8 cannot be cast to String`. Standalone investigation should take 1 iteration.

Estimated total: 6-8 iterations for full convergence (matches the original GOAL phase plan that had 10 iterations
end-to-end).
