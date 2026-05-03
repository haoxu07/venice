# OUTCOME — VT-Merge Read-Path Correctness Experiment

**Date:** 2026-05-02 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Outcome:** **TIER-3-HALTED** in Phase C. Phases A
and B succeeded; Phase C revealed a deeper RocksDB merge-operator integration issue that exhausts the iteration budget.

## Summary

The experiment built and tested the missing pieces from the predecessor write-side experiment (parser, materializing
partition, schema-aware fold context, framing helpers). Phase A's parser and Phase B's read fold both pass their unit /
smoke tests in isolation. The integration test (Phase C, `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys` with
flag=ON) reveals that **RocksDB's `merge()` calls succeed without exception, but the operand bytes do not accumulate
into the on-disk concat blob**. After 7 fix iterations addressing 5 distinct production bugs, the in-scope tests still
fail with the same symptom.

The remaining issue is most likely at the RocksDB Java JNI layer: the `StringAppendOperator` registered via
`ColumnFamilyOptions.setMergeOperator(...)` does not appear to attach to the default column family in the way our writes
use it. Diagnosing further requires RocksDB-INFO-level logs or a standalone JNI repro, which is outside the iteration
budget.

## Per-phase result summary

| Phase | Goal                                                               | Status        | Notes                                                                                                                               |
| ----- | ------------------------------------------------------------------ | ------------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| A     | `ConcatBlobParser` + unit tests                                    | ✅ PASS       | 17 unit tests green; round-trip property test (300 random trials) green                                                             |
| B     | `MaterializingRocksDBStoragePartition` + smoke test                | ✅ PASS       | 4 smoke tests green; full put → merge → merge → get round-trip with real Avro + WriteComputeProcessor verified in unit-test context |
| C     | 7 in-scope `PartialUpdateTest` invocations with flag ON + flag OFF | ❌ HALTED     | flag-OFF green (3/3 of `testPartialUpdateOnBatchPushedKeys`); flag-ON FAIL (3/3) — see phase-C-progress.md for full iteration log   |
| D     | Sweeper fold logic                                                 | ⏸ NOT STARTED | Halted per §4.5 Tier 3 escalation criteria                                                                                          |

## Test pass/fail tables

### Phase A — `ConcatBlobParserTest` (17 invocations)

All PASS.

| Test                                                             | Result |
| ---------------------------------------------------------------- | ------ |
| `parseMaterializedOnlyReturnsBaseAndEmptyOperands`               | PASS   |
| `parseMaterializedOnlyHandlesZeroLengthBase`                     | PASS   |
| `parseOperandOnlySingleReturnsNullBaseAndOneOperand`             | PASS   |
| `parseOperandOnlyChainReturnsAllOperandsInOrder[delim=0x01]`     | PASS   |
| `parseOperandOnlyChainReturnsAllOperandsInOrder[delim=0x2C]`     | PASS   |
| `parseBaseAndOperandsReturnsAll[delim=0x01]`                     | PASS   |
| `parseBaseAndOperandsReturnsAll[delim=0x2C]`                     | PASS   |
| `parseHandlesOperandPayloadsContainingDelimiterByte[delim=0x01]` | PASS   |
| `parseHandlesOperandPayloadsContainingDelimiterByte[delim=0x2C]` | PASS   |
| `parseHandlesBasePayloadContainingKindBytes`                     | PASS   |
| `roundTripPropertyTest` (200 trials)                             | PASS   |
| `roundTripPropertyTestOperandOnly` (100 trials)                  | PASS   |
| `parseRejectsNull`                                               | PASS   |
| `parseRejectsEmpty`                                              | PASS   |
| `parseRejectsTruncatedBaseLen`                                   | PASS   |
| `parseRejectsUnknownKindByte`                                    | PASS   |
| `varintEncodeDecodeRoundTrip`                                    | PASS   |

### Phase B — `MaterializingPartitionSmokeTest` (4 invocations)

All PASS.

| Test                                     | Result                                                                                                                                        |
| ---------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------- |
| `chunkWriteIsBypassed`                   | PASS — chunk schemaId=-10 path bypasses framing as expected                                                                                   |
| `getReturnsNullForMissingKey`            | PASS                                                                                                                                          |
| `putThenGetRoundTripsWithFraming`        | PASS — base put/get round-trip; raw on-disk bytes verified to be `[schemaId][0x00][len][avro]`                                                |
| `putThenMergeThenGetFoldsViaFoldContext` | PASS — full put + merge + merge + get round-trip with real Avro schema + StoreWriteComputeProcessor; 2 operands folded into materialized form |

Regression: existing `RocksDBStoragePartitionTest` (33 invocations) all PASS — flag-OFF byte-equivalent to today.

### Phase C — `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys`

**Flag-OFF (regression check):** all 3 PASS.

| Test                                                 | Result | Time  |
| ---------------------------------------------------- | ------ | ----- |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`          | PASS   | 41.7s |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`           | PASS   | 30.5s |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]` | PASS   | 31.0s |

**Flag-ON (correctness check):** all 3 FAIL with `AssertionError: expected [new_name_1] but found [first_name_1]`.

| Test                                                 | Result | Failure mode                                                          |
| ---------------------------------------------------- | ------ | --------------------------------------------------------------------- |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`          | FAIL   | Read returns batch-pushed value; partial-update operand never visible |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`           | FAIL   | (same)                                                                |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]` | FAIL   | (same)                                                                |

Other 4 in-scope invocations (`testActiveActivePartialUpdateOnBatchPushedChunkKeys` and 3 ×
`testActiveActivePartialUpdateWithCompression`) NOT RUN; they would fail for the same reason.

### Phase D — Sweeper unit test

NOT STARTED.

## Decision per §8

The decision criteria require:

- ✅ All 7 in-scope invocations from `PartialUpdateTest` pass with flag ON — ❌ FAIL
- ✅ Same 7 invocations still pass with flag OFF (no regression) — ✅ PASS for the 3 of
  `testPartialUpdateOnBatchPushedKeys`; the other 4 not run
- ✅ Phase D sweeper unit test passes — N/A (not started)

**Verdict: NO**, the design as implemented in this experiment is NOT read-correct for the in-scope tests.

## What works (for the next session)

1. **Wire format and parser** are nailed down; `ConcatBlobParser` is well-tested and ready for use.
2. **Read-fold context infrastructure** (`MaterializingFoldContext`, `MaterializingFoldContextRegistry`,
   `MaterializingFraming`) is built out and unit-tested.
3. **Storage-partition wrapper hierarchy** is in place — `MaterializingRocksDBStoragePartition` for non-AA stores,
   `MaterializingReplicationMetadataRocksDBStoragePartition` for AA stores. Both go through the same
   `MaterializingFraming` helpers.
4. **Leader fast-path** in `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT` is fixed to defensively snapshot
   operand bytes before the IngestionBatchProcessor's deserializer consumes them.
5. **Follower's case UPDATE** in `StoreIngestionTask` now prepends the schema-id pair to operand content before calling
   `storageEngine.merge`.
6. **Checksum compute** in `PartitionConsumptionState.maybeUpdateExpectedChecksum` is updated to include the framing
   bytes when flag is on.
7. **Chunking-update interaction**: relaxed the strict chunking-on-rejects-update check in `VeniceWriter.update()`;
   small-operand UPDATEs now work on chunking-enabled stores.

## What needs to be solved (next session pickup)

The merge operator integration. Specifically:

- **A standalone JNI repro test**: open a RocksDB instance with two CFs (DEFAULT, RMD), register StringAppendOperator on
  the DEFAULT CF's options, do `Put(key, base)`, `Merge(key, op1)`, `Merge(key, op2)`, then `Get(key)`. Verify the
  result is the concat blob.
- If that works, the issue is in Venice's CF construction path. Possibly the `Options` / `ColumnFamilyOptions`
  separation: try setting `setMergeOperator` on a fresh ColumnFamilyOptions object (not derived from
  `new ColumnFamilyOptions(options)`).
- If that doesn't work, the merge-operator approach itself may be broken on the version of RocksDB Java in use. The
  fallback is **Option 3** (see `phase-C-progress.md`): drop the merge approach; have the follower do put-with-concat
  instead.

## Files / commits relevant to next-session pickup

All commits on branch `haoxu07/vt-rocksdb-merge-design`:

```
2c75a79d0 [server][dvc] Phase B fix iter 7: explicit CF handle on rocksDB.merge
9f0822acb [server][dvc] Phase B fix iter 5 + 6: snapshot operand bytes, set MergeOperator on CF
ccb484473 [server][dvc] Phase B fix iter 4: diagnostic logs for read fold
1b767f119 [server] Phase B fix iter 3: relax chunking check in VeniceWriter.update
29eefe5ff [server][dvc] Phase B fix iter 2: include framing bytes in expected SST checksum
7adee6244 [server][dvc] Phase B fix iter 1 (cont): add new files for the refactor
c81705a05 [server][dvc] Phase B fix iteration 1: handle AA partition + double-framing
c632f0911 [autoresearch] Phase B progress: smoke test 4/4 green, regression check green
6d7da60f2 [server][dvc] Phase B: MaterializingRocksDBStoragePartition + read-side fold
fe5cfb4e3 [autoresearch] Phase A progress: ConcatBlobParser unit tests green
c35b49492 [server][dvc] Phase A: ConcatBlobParser + unit tests
```

Diagnostic logs are still in the code (e.g., `LOGGER.info("VT-merge ...")`); leaving them in for the next session.

Key files touched:

- `clients/da-vinci-client/.../store/rocksdb/MaterializingRocksDBStoragePartition.java` (NEW)
- `clients/da-vinci-client/.../store/rocksdb/MaterializingReplicationMetadataRocksDBStoragePartition.java` (NEW)
- `clients/da-vinci-client/.../store/rocksdb/merge/ConcatBlobParser.java` (NEW)
- `clients/da-vinci-client/.../store/rocksdb/merge/MaterializingFoldContext.java` (NEW)
- `clients/da-vinci-client/.../store/rocksdb/merge/MaterializingFoldContextRegistry.java` (NEW)
- `clients/da-vinci-client/.../store/rocksdb/merge/MaterializingFraming.java` (NEW)
- `clients/da-vinci-client/.../store/rocksdb/RocksDBStoragePartition.java` (modified — added explicit setMergeOperator
  on CF, explicit CF handle in merge)
- `clients/da-vinci-client/.../store/rocksdb/RocksDBStorageEngine.java` (modified — partition selection)
- `clients/da-vinci-client/.../store/rocksdb/RocksDBStorageEngineFactory.java` (modified — set
  FRAMING_ACTIVE_FOR_CHECKSUM)
- `clients/da-vinci-client/.../kafka/consumer/ActiveActiveStoreIngestionTask.java` (modified — operand bytes snapshot)
- `clients/da-vinci-client/.../kafka/consumer/LeaderFollowerStoreIngestionTask.java` (modified — register fold context,
  unregister on close)
- `clients/da-vinci-client/.../kafka/consumer/StoreIngestionTask.java` (modified — case UPDATE prepends schema ids)
- `clients/da-vinci-client/.../kafka/consumer/PartitionConsumptionState.java` (modified — checksum includes framing)
- `internal/venice-common/.../venice/writer/VeniceWriter.java` (modified — relaxed chunking-update check)
- `internal/venice-test-common/.../endToEnd/PartialUpdateTest.java` (modified — sysprop-driven flag setting)
- `build.gradle` (modified — propagate `vt.update.operand.flag` sysprop to test JVMs)
