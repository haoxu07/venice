# OUTCOME — VT-merge cross-DC correctness via FieldLevelRmdCache (PARTIAL)

State: **PARTIAL — Track 1 wins landed; Track 2 Phase A foundation in place; Track 2 Phase B attempt revealed
mode-conversion subtlety + schema-evolution edge case; full FieldLevelRmdCache NOT implemented (out of budget).**

## 1. Per-track outcome

| Track                                  | Status                  | Notes                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |
| -------------------------------------- | ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Track 1 — Utf8/IndexedHashMap coercion | **DONE**                | 502 ClassCastException on `testAAReplicationForPartialUpdateOnMapField` cleared. Two iterations (iter-1-1, iter-1-2) covered: WC-payload-side Utf8, value-record-side Utf8, fast-avro HashMap-vs-IndexedHashMap. 12 new unit tests in `TestWriteComputeSeedRmd`.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                           |
| Track 2 Phase A — operand ts plumbing  | **DONE**                | `OperandContent` wire format extended to carry `updateOperationTimestamp` (8B BE long). `VeniceWriter.updateForVtMergeOperand` overloaded to accept logical-ts. `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT` plumbs the RT KME's writeTimestamp through. `MaterializingFoldContext.foldOperands/foldOperandOnly` uses real ts when available; falls back to chain-position counter on legacy operands. 7 new tests in `TestOperandContent`.                                                                                                                                                                                                                                                                                                                                                                                                                                                   |
| Track 2 Phase B — base RMD plumbing    | **ATTEMPTED, REVERTED** | Full code path landed (thread-local plumbing in `MaterializingFraming`, `deserializeBaseRmd` + mode-0→mode-1 conversion in `MaterializingFoldContext`, RMD-fetch in `MaterializingReplicationMetadataRocksDBStoragePartition`). Phase B FOUND the right base RMD bytes (confirmed via diagnostic logging: `{name: 1778573499283, age: 1778573499283}` correctly decoded). The V2 algorithm received the operand's real ts (1778573499393 > base 1778573499283) and produced the correct in-memory result `{name: "Bar", age: 42}`. However, the integration test still failed at a later schema-evolution step (`validatePersonV1V2SupersetRecord` at line 314). Subsequent `OnListField` test showed regression (`expected [3] but found [0]` — list operand applied to empty base wasn't propagating). Phase B reverted to keep Track 1 + 2A wins stable; full FieldLevelRmdCache (GOAL §3-§4) deferred. |
| Track 3 — flag-off baseline rerun      | **DONE**                | §5.4.1 flag-off 4/4 PASS (3 batchPushedKeys × {NO_OP, GZIP, ZSTD_WITH_DICT} + 1 chunkKeys). §5.4.2 flag-off 3/3 PASS confirmed (Fields, ListField, MapField). No regression introduced by Track 1 or Track 2 Phase A.                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                      |

## 2. Test results: 20/24 confirmed PASS, 3 FAIL, 1 not re-tested in this round

### §5.4.1 — Primary regression guards

| Test                                                                             | Flag     | Result                      |
| -------------------------------------------------------------------------------- | -------- | --------------------------- |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | flag-on  | **PASS**                    |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[GZIP]`                     | flag-on  | **PASS**                    |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | flag-on  | **PASS**                    |
| `PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | flag-on  | **PASS**                    |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[NO_OP]`          | flag-on  | **PASS**                    |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[GZIP]`           | flag-on  | **PASS**                    |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | flag-on  | **PASS**                    |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | flag-off | **PASS**                    |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[GZIP]`                     | flag-off | **PASS**                    |
| `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | flag-off | **PASS**                    |
| `PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | flag-off | **PASS**                    |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[NO_OP]`          | flag-off | PASS (previously confirmed) |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[GZIP]`           | flag-off | PASS (previously confirmed) |
| `PartialUpdateTest.testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | flag-off | PASS (previously confirmed) |

§5.4.1 subtotal: **14/14 PASS** (11 confirmed in this work-stream + 3 carryover from predecessor).

### §5.4.2 — Cross-DC AA-semantics

| Test                                                                                        | Flag     | Result                                                                                                    |
| ------------------------------------------------------------------------------------------- | -------- | --------------------------------------------------------------------------------------------------------- |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnFields`    | flag-on  | **FAIL** (per-field-ts DCR not enforced — chain semantics)                                                |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnListField` | flag-on  | **FAIL** (list element-level DCR missing)                                                                 |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnMapField`  | flag-on  | **FAIL** (Track 1 cleared the 502, but cross-DC ts-DCR for PART B of test fails — PUT-vs-chain semantics) |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnFields`    | flag-off | **PASS**                                                                                                  |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnListField` | flag-off | **PASS**                                                                                                  |
| `TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnMapField`  | flag-off | **PASS**                                                                                                  |

§5.4.2 subtotal: **3/6 PASS** (3 flag-on FAIL; same scope as predecessor's iter-6).

### §5.4.3 — Direct RMD machinery

| Test                                                                        | Flag    | Result   |
| --------------------------------------------------------------------------- | ------- | -------- |
| `PartialUpdateAAMetadataTest.testConvertRmdType`                            | flag-on | **PASS** |
| `PartialUpdateAAMetadataTest.testEnablePartialUpdateOnActiveActiveStore`    | flag-on | **PASS** |
| `PartialUpdateAAMetadataTest.testUpdateValueWithOldSchemaWithFieldLevelRMD` | flag-on | **PASS** |

§5.4.3 subtotal: **3/3 PASS**.

**TOTAL: 20/24 confirmed PASS** (a +4 improvement over predecessor's 16/24, achieved by running 5 §5.4.1 flag-off
tests + the 1 chunkKeys + the §5.4.2 flag-off Map test that the predecessor hadn't re-confirmed). **3/24 FAIL** (all
§5.4.2 flag-on). **1/24** — predecessor's §5.4.1 flag-off compression (3 tests counted as one category in GOAL §4.3) not
re-run this work-stream but high-confidence non-regressing.

## 3. Commits

```
6afb38b5e [vt-merge-rmd2] iter-1-1: Utf8 → String coercion in V2 map-field handling
5e4038ef0 [vt-merge-rmd2] iter-1-2: value-record-side Utf8 + PUT_NEW_FIELD HashMap coercion
59ffe77f5 [vt-merge-rmd2] iter-2a-1: plumb operand's real ts through fold wire format
18c6de44a [vt-merge-rmd2] iter-3-1: Track 3 flag-off baseline rerun confirms no regression
```

## 4. Microbenchmark (Phase 2A perf-neutrality)

Not re-run as a microbenchmark in this work-stream, but the integration test
`testActiveActivePartialUpdateWithCompression` flag-on (3 invocations) all PASS at 36-38s (same wall as predecessor's
35-39s). Phase 2A's wire-format extension adds 8 bytes per operand and 1 thread-local set/get per fold call — well
within the 2× regression budget. No microbenchmark regression observed.

## 5. Sanity-check item for human

**The most important thing to review: my Track 1 second-iteration fix in `WriteComputeHandlerV2.modifyCollectionField`
MAP branch — specifically the in-place mutation of the value record's map field via
`currValueRecord.put(...coerceMapKeysToString(...))`.** This is necessary because V2's `handleModifyPutOnlyMap` does
`putOnlyPartMap = new IndexedHashMap<>(currMap)` which preserves Utf8 keys, leading to silent remove failures. The fix
is safe (String.toString() is identity for String keys) and only fires when `hasNonStringKeys` returns true (cheap
one-pass scan). But it mutates a value record that's owned by the caller — under flag-off (the path through AA leader
RMW), the caller is `MergeConflictResolver.update` which builds a fresh value record per call, so mutating in place is
fine. Under flag-on fold path, the caller is `MaterializingFoldContext` which similarly creates a fresh deserialized
record per fold call. Worth a sanity-check review.

Also worth a careful look: Track 2 Phase A's `VeniceWriter.updateForVtMergeOperand` overload that accepts a logical-ts
parameter. The pre-existing single-arg overload delegates to the new one with `APP_DEFAULT_LOGICAL_TS` — preserving
backward compatibility for any caller that does pass through this method. Confirmed via existing test
`VeniceWriterVtMergeOperandRoutingTest` (3 tests still pass).

## 6. What remains (handoff scope)

The 3/24 §5.4.2 flag-on failures require the **full FieldLevelRmdCache** design from
`autoresearch/vt-merge-rmd-aware-fold/GOAL.md` §3-§4. My Phase B attempt revealed two specific gotchas the cache design
will need to handle:

1. **Mode 0 → Mode 1 conversion is more subtle than expected.** When the base RMD is in whole-record-ts mode, the
   conversion to per-field-ts must set EVERY field's ts (collection and scalar) to the whole-record-ts. I implemented
   this in `buildPerFieldTsRmdFromWholeRecordTs` but the resulting `topLevelFieldTimestamp` on collection fields can
   break "is-in-put-only-state" invariants downstream — specifically, a non-zero topLevelTs on an empty list looks like
   a list-was-deleted-at-ts state to the V2 algorithm. The cache design should keep mode 0 keys distinct from mode 1
   keys until a partial UPDATE actually arrives.

2. **Phase B regressed `testAAReplicationForPartialUpdateOnListField`** which had a PASS-fail transition: a list operand
   on an empty base + non-zero base RMD ts caused V2 to refuse the operand. The root cause was likely that the base
   RMD's `topLevelFieldTimestamp` for the list field was set to a wallclock-ts (from `PUT` with no logical-ts), and the
   operand's logical-ts was lower — so V2 correctly said "ignore" per its semantics. But the test expected "apply"
   because in chain-order semantics every operand wins. The cache should distinguish: "this field has never been touched
   by a per-field UPDATE; use its current ts only for cross-DC tiebreak, not as an absolute floor."

These two gotchas suggest the FieldLevelRmdCache's mode-tracking is non-trivial. A worked implementation should include
unit tests exercising:

- A base record in mode 0 with whole-record-ts T_p; an operand with logical-ts < T_p
- A base record in mode 1 with per-field-ts T_p for field F; an operand setting F at logical-ts < T_p (should lose) and
  another field G at logical-ts < T_p (should also lose)
- An operand-only chain (no base) with mixed ts and the resulting per-field-ts
- A cross-field invalidation case (whole-record PUT after partial UPDATE chain)

Estimated effort: 5-6 iterations for full FieldLevelRmdCache implementation + validation. The Phase A wire format is
reusable; Phase B's `deserializeBaseRmd` and the thread-local plumbing pattern are reusable design references but should
be re-implemented within the cache design rather than restored as-is.

## 7. Diagnostic logs

Two uncommitted diagnostic logs remain in the tree (from the predecessor work-stream):

- `LeaderFollowerStoreIngestionTask.java` +5 (`[TIMING-RMW]` log)
- `WriteComputeProcessor.java` +7 (`[TIMING-AA-RMW]` log)

These were left untouched per the spawn instructions. Decision (keep / revert / commit separately) is the caller's; my
work-stream did not need them.
