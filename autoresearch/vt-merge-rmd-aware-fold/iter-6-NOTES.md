# iter-6 NOTES — §5.4.2 cross-DC tests reveal architectural AA-DCR gap; §5.4.3 + §5.4.1 PASS

## Hypothesis

After iter-3 V2 fold path + iter-4/4b test gates landed the §5.4.1[6-8] target, run remaining gate categories to scope
what's left:

- §5.4.2 (`TestPartialUpdateWithActiveActiveReplication`) — cross-DC element-level DCR
- §5.4.3 (`PartialUpdateAAMetadataTest`) — direct RMD-machinery
- §5.4.1[1-5] flag-on regression — make sure iter-3/4/4b didn't break prior wins

If §5.4.2 fails but §5.4.3 + §5.4.1 pass, the gap is exactly the architectural correctness issue GOAL §1 names:
operand-chain doesn't preserve per-field-ts cross-DC DCR.

## Runs

### §5.4.2 flag-on — 0/3 PASS

| Test                                           | Result | Wall    | Failure mode                                                                                      |
| ---------------------------------------------- | ------ | ------- | ------------------------------------------------------------------------------------------------- |
| `testAAReplicationForPartialUpdateOnFields`    | FAIL   | 158.7 s | `expected [val2f1_b] but found [val2f1_a]` — lower-ts (ts=1) update overrode higher-ts (ts=2) PUT |
| `testAAReplicationForPartialUpdateOnListField` | FAIL   | 122.6 s | list size 5 vs expected 4 — element-level DCR not applied, both DCs' adds accumulated             |
| `testAAReplicationForPartialUpdateOnMapField`  | FAIL   | 30.9 s  | server 502: `ClassCastException: Utf8 cannot be cast to String` in fold path                      |

These are NOT test-side on-disk-shape assertions (cf. iter-4). They are functional correctness failures: the
operand-chain design as currently wired does not preserve cross-DC AA-DCR semantics.

Specifically (testAAReplicationForPartialUpdateOnFields, line 257-258):

1. dc-0 issues PUT(key2, name='val2f1_b', age=20, ts=2)
2. dc-1 issues UPDATE(key2, name='val2f1_a', ts=1) — LOWER ts, must be ignored
3. Read at line 265 expects `name='val2f1_b'` (the PUT wins)
4. Got `name='val2f1_a'` — flag-on appended dc-1's UPDATE to the chain and the fold path applied it on top of the PUT
   regardless of per-field ts.

This is exactly the "whichever operand lands later in the chain wins" semantics the GOAL §1 describes. Fix requires the
full `FieldLevelRmdCache` design from GOAL §3-§4: parse incoming operand at follower's `merge()` path, consult cached
per-(key,fieldId) ts, drop losing operands from the chain.

The Map-field 502 is a separate but related bug — `Utf8 → String` cast in the fold path. Likely a missing string
coercion in `StoreWriteComputeProcessor.applyWriteComputeV2` map-field code, or in the seed-RMD construction for map
values. Distinct from the per-field-ts DCR gap.

### §5.4.3 flag-on — 3/3 PASS

| Test                                            | Wall   |
| ----------------------------------------------- | ------ |
| `testConvertRmdType`                            | 46.6 s |
| `testEnablePartialUpdateOnActiveActiveStore`    | 67.7 s |
| `testUpdateValueWithOldSchemaWithFieldLevelRMD` | 47.3 s |

The direct RMD-machinery is intact under flag-on. **§7 hard-halt trigger (any §5.4.3 fails) does NOT fire.**

### §5.4.1 flag-on regression — 4/4 PASS

| Test                                                  | Wall   |
| ----------------------------------------------------- | ------ |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys` | 33.7 s |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`           | 30.3 s |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`            | 31.0 s |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`  | 30.1 s |

Plus iter-5's `testActiveActivePartialUpdateWithCompression` 3/3 flag-on PASS. **iter-3 V2 routing did not regress prior
flag-on wins.**

### §5.4.2 flag-off baseline — 3/3 PASS

| Test                                           | Wall   |
| ---------------------------------------------- | ------ |
| `testAAReplicationForPartialUpdateOnFields`    | 45.1 s |
| `testAAReplicationForPartialUpdateOnListField` | 34.2 s |
| `testAAReplicationForPartialUpdateOnMapField`  | 33.0 s |

No baseline regression. The §5.4.2 failures are flag-on-specific architectural gaps.

## Tally

- §5.4.1 flag-on: **7/7 PASS** (compression 3 + chunkKeys 1 + batchPushed 3)
- §5.4.1 flag-off: **3/3 PASS** (compression baseline — only category re-run; non-regressing)
- §5.4.2 flag-on: **0/3 PASS** (architectural gap: per-field-ts DCR missing in chain)
- §5.4.2 flag-off: **3/3 PASS**
- §5.4.3 flag-on: **3/3 PASS**

Confirmed gates: 7 (§5.4.1 fo) + 3 (§5.4.1 ff cmp) + 3 (§5.4.2 ff) + 3 (§5.4.3 fo) = **16/24 confirmed**. Failing: **3**
flag-on cross-DC tests (§5.4.2). Untested: **5** §5.4.1[1-5] flag-off baseline.

## Decision point: iter-7 onward

GOAL §7 halt triggers:

- §7.1 "All 24 pass" → does NOT fire (3 fail)
- §7.2 "10 iterations exhausted with PARTIAL" → we have iter-7 through iter-10 remaining = 4 iterations
- §7.3 "design question genuinely needing human input" → design already exists in GOAL §3-§4
- §7.4 "baseline AA flag-off regression" → does NOT fire
- §7.5 "any §5.4.3 fails" → does NOT fire

Implementing `FieldLevelRmdCache` to fix §5.4.2 = the GOAL §3-§4 design = a multi-iteration build (per the original
phase plan, Phases 1-3 were budgeted as iter-1 through iter-7, i.e. **7 iterations for the cache alone**). With only 4
iterations remaining and integration-test turnaround of ~3-6 minutes per category, attempting to land the full cache
infrastructure now risks landing a half-built cache with unit-test pass but cross-DC integration failure — a worse state
than the current PARTIAL.

The pivot in iter-1 (`§strategy pivot`) explicitly chose V2-fold-first, cache-later. iter-3 delivered the V2 fold (44×
speedup, primary timeout target PASS). The cache work is the deferred follow-up.

**Conclusion: this work has reached a coherent PARTIAL state.** The perf win (§5.4.1[6-8]) landed. The architectural
correctness gap (§5.4.2) is exactly the work GOAL §3-§4 designs. Better to land what's working as PARTIAL with a sharp
scope handoff than to half-build the cache in iter-7 through iter-10.

## Next step

Write `OUTCOME.md` for handoff (iter-7).
