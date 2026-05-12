# iter-3-1 NOTES — Track 3 flag-off baseline regression rerun

## Hypothesis

Track 1 (iter-1-1, iter-1-2) modified `WriteComputeHandlerV2.modifyCollectionField` and
`WriteComputeHandlerV2.updateRecordWithRmd`. Track 2 Phase A (iter-2a-1) modified `OperandContent` wire format,
`StoreIngestionTask` UPDATE-consume, `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT`, and
`MaterializingFoldContext.foldOperands/foldOperandOnly`.

All these touch ONLY flag-on code paths or strictly-additive utility methods. Flag-off behavior should be unchanged.
This iteration empirically confirms.

## Tests to run (all flag-off)

| #   | Test                                                                    |
| --- | ----------------------------------------------------------------------- |
| 1   | `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[NO_OP]`           |
| 2   | `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[GZIP]`            |
| 3   | `PartialUpdateTest.testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`  |
| 4   | `PartialUpdateTest.testActiveActivePartialUpdateOnEmptyPush`            |
| 5   | `PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys` |

These are the 5 untested §5.4.1[1-5] flag-off baselines from the predecessor work-stream.

Plus §5.4.2 flag-off baseline (3 tests) — were PASSing before, must still pass.

## Halt condition

- 5/5 §5.4.1 flag-off pass
- 3/3 §5.4.2 flag-off pass (already verified PASS at iter-6 of predecessor; this is a final-state re-confirmation)
