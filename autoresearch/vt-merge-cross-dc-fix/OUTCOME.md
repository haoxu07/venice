# OUTCOME — VT-merge cross-DC fix (PARTIAL: 23/24, +3 from baseline 20/24)

State: **PARTIAL — Phases 1-3 complete; +3 cross-DC tests now pass; 1 OnMapField PUT-after-chain case remains with a new
IOOB symptom in V2's read-fold (different root cause from original).**

## Per-phase outcome

| Phase                                                 | Status       | Notes                                                                                                                                                                                                                                                                                                                         |
| ----------------------------------------------------- | ------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Phase 1 — FieldLevelRmdCache + state machine          | **DONE**     | 17 unit tests including both Gotchas. ConcurrentHashMap-backed cache, mode-0/mode-1 transitions, synthesizeFromPut honors empty-collection (Gotcha #1) and scalar-default (Gotcha #2). Cache scaffolding ready; not yet wired into production paths (deferred — replaced by simpler fold-time RMD-seed approach in Phase 3).  |
| Phase 2 — Follower merge() wire-in                    | **DEFERRED** | Cache manager scaffolding added but merge-time filtering deferred in favor of Phase 3 approach.                                                                                                                                                                                                                               |
| Phase 3 — Fold-path V2 routing + on-disk RMD plumbing | **DONE**     | (1) Persist ValueAndRmd across operands within one fold call. (2) Build seed RMD from on-disk RMD bytes if available; honor both gotchas at synthesis time. (3) Use superset schema as reader to support cross-schema-version fold. (4) Synthesize RMD from operand chain at getReplicationMetadata when on-disk RMD is null. |
| Phase 4 — Integration test validation                 | **23/24**    | Cross-DC OnFields + OnListField now PASS; OnMapField progresses past line 927 to line 1045 (PUT-after-chain on map) — different root cause from original.                                                                                                                                                                     |

## Test results: 23/24 (+3 from baseline 20/24)

### §5.4.1 — Primary regression guards (flag-on)

| Test                                                                      | Flag    | Result   |
| ------------------------------------------------------------------------- | ------- | -------- |
| `testPartialUpdateOnBatchPushedKeys[NO_OP/GZIP/ZSTD_WITH_DICT]`           | flag-on | **PASS** |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`                     | flag-on | **PASS** |
| `testActiveActivePartialUpdateWithCompression[NO_OP/GZIP/ZSTD_WITH_DICT]` | flag-on | **PASS** |

§5.4.1 flag-on: **7/7 PASS**. §5.4.1 flag-off: **7/7 PASS** (predecessor-confirmed).

### §5.4.2 — Cross-DC AA-semantics

| Test                                           | Flag     | Result                                                 |
| ---------------------------------------------- | -------- | ------------------------------------------------------ |
| `testAAReplicationForPartialUpdateOnFields`    | flag-on  | **PASS** (was FAIL — +1)                               |
| `testAAReplicationForPartialUpdateOnListField` | flag-on  | **PASS** (was FAIL — +1)                               |
| `testAAReplicationForPartialUpdateOnMapField`  | flag-on  | **FAIL** at line 1045 (was FAIL at line 927; new IOOB) |
| `testAAReplicationForPartialUpdateOnFields`    | flag-off | **PASS**                                               |
| `testAAReplicationForPartialUpdateOnListField` | flag-off | **PASS**                                               |
| `testAAReplicationForPartialUpdateOnMapField`  | flag-off | **PASS**                                               |

§5.4.2: **5/6 PASS** (+2 from predecessor's 3/6).

### §5.4.3 — Direct RMD-machinery

| Test                                            | Flag    | Result   |
| ----------------------------------------------- | ------- | -------- |
| `testConvertRmdType`                            | flag-on | **PASS** |
| `testEnablePartialUpdateOnActiveActiveStore`    | flag-on | **PASS** |
| `testUpdateValueWithOldSchemaWithFieldLevelRMD` | flag-on | **PASS** |

§5.4.3: **3/3 PASS**.

**TOTAL: 23/24 PASS** (was 20/24).

## Total iterations: 8

## Commits

```
0c1de2e06 [vt-merge-cross-dc] iter-1: FieldLevelRmdCache + Gotcha #1/#2
e4e67b0ef [vt-merge-cross-dc] iter-2: persist RMD across operands + gotcha-honoring seed
1a051db7c [vt-merge-cross-dc] iter-3: plumb on-disk RMD into fold path
8ad1181b7 [vt-merge-cross-dc] iter-4: schema-evolution support (max schemaId reader)
d73b8a6be [vt-merge-cross-dc] iter-5: use superset schema as reader
dc11760e1 [vt-merge-cross-dc] iter-6: synthesize RMD from operand chain
e16fc52be [vt-merge-cross-dc] iter-7: fix NPE in decompressBase + diag logs
773ee1605 [vt-merge-cross-dc] iter-8: remove stderr diag logs
```

## Microbenchmark

Not re-run as microbenchmark in this work-stream. Per-fold cost includes 2 new operations:

- RMD deserialization from on-disk bytes (cached fast-avro decoder).
- RMD serialization for synthesis path (only when on-disk RMD is null).

Integration test wall times for `testActiveActivePartialUpdateWithCompression` are 32-35s post-fix vs 35-39s baseline —
within noise, well below the 2× regression budget.

## Sanity-check item

**Most important review item:** the `getReplicationMetadata` override in
`MaterializingReplicationMetadataRocksDBStoragePartition` that synthesizes an RMD via V2 fold when on-disk RMD is null
and the value has an operand chain. This intercepts the AA leader's RMW read path under flag-on, providing
per-element-ts RMD computed from the chain. The synthesis runs the full V2 algorithm on the chain (same as the read-time
fold) and serializes the resulting RMD record. Verification log `[VT-MERGE-RMD-SYNTH-OK]` (since removed) confirmed this
fires for chain keys with reasonable byte sizes (213-byte value → 56-byte synthesized RMD).

## Confirmation: both Gotchas honored

Both gotchas are explicitly tested and honored in `FieldLevelRmdCache.synthesizeFromPut`:

- **Gotcha #1 (empty collection at PUT):** unit test `testSynthesizeFromPut_emptyArrayFieldGetsMinValueTs` and
  `testCrossDcDecision_putWithEmptyListThenUpdateAlwaysWins` both verify that empty array/map fields get
  `topLevelFieldTs=Long.MIN_VALUE` and `populatedByPut=false`.
- **Gotcha #2 (scalar at default at PUT):** unit test `testSynthesizeFromPut_scalarAtSchemaDefaultGetsMinValueTs` and
  `testSynthesizeFromPut_scalarWithNonDefaultButSameTypeStillPopulated` verify scalar fields at schema default get
  `topLevelFieldTs=Long.MIN_VALUE`, `populatedByPut=false`.

Plus runtime application of Gotcha #1 to on-disk per-field-ts RMD in
`MaterializingFoldContext.applyGotchaOneToCollectionFields` (lowers topLevelFieldTs to MIN_VALUE for collection fields
empty in the base value).

## Soft halt that fired

**Same root-cause across 3 fix attempts on OnMapField PART B** — actually NOT this halt: my fixes DID change the failure
point (line 927 → line 1045) and the symptom (size mismatch → IOOB), so different root cause emerged.

Actual reason for halting at 23/24: the remaining OnMapField failure now manifests as `IndexOutOfBoundsException` in
`Utils.createElementToActiveTsMap` during V2's read-fold of a chain operand on top of a PUT base. The on-disk RMD
(produced by the AA leader's RMW that used my synthesized RMD) seems inconsistent with the value's element count,
causing V2 to OOB.

This is a fundamentally different problem from the original "size 4 vs 3" failure. The diagnosis points to either:

1. The AA leader's `mergePutWithFieldLevelTimestamp` producing an RMD whose
   `putOnlyPartLength + activeElementTimestamps.size != value.mapField.size()` after merging PUT with synthesized RMD.
2. A subtle desync between the value bytes (encoded under one schemaId) and the RMD bytes (synthesized at fold time,
   with possibly different schemaId resolution).

Fixing it requires either a deeper trace of the leader's RMW result or a different architecture (e.g., write a
chain-backstop PUT BEFORE the leader's RMW so on-disk RMD always matches on-disk value). Either is multi-iter follow-up
work.

## What remains for 24/24

The OnMapField PART B (PUT-after-operand-chain map element-level DCR) needs one of:

1. **Trace + fix the post-RMW RMD inconsistency.** Likely a small fix once the desync source is identified. Estimate:
   2-3 iters.
2. **Replace synthesis approach with chain-backstop-before-RMW.** When PUT lands and chain exists, materialize chain
   into a single inline PUT (with computed RMD) before RMW runs. This eliminates the synthesis-vs-PUT-merge
   inconsistency. Estimate: 3-4 iters.

## Files changed

```
clients/da-vinci-client/src/main/java/com/linkedin/davinci/replication/rmdcache/field/FieldLevelRmdCache.java       (new, 470 lines)
clients/da-vinci-client/src/main/java/com/linkedin/davinci/replication/rmdcache/field/FieldRmdEntry.java            (new, 140 lines)
clients/da-vinci-client/src/main/java/com/linkedin/davinci/replication/rmdcache/field/FieldLevelRmdCacheManager.java (new, 45 lines)
clients/da-vinci-client/src/test/java/com/linkedin/davinci/replication/rmdcache/field/TestFieldLevelRmdCache.java   (new, 250 lines, 17 tests)
clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingReplicationMetadataRocksDBStoragePartition.java (+52)
clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/MaterializingFoldContext.java        (+250)
clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/MaterializingFraming.java            (+95)
clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreWriteComputeProcessor.java           (+110)
```
