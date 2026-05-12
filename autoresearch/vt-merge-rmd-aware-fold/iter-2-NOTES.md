# iter-2 NOTES — Seed RMD helper + unit tests

## Hypothesis

A `WriteComputeSeedRmd` helper that:

1. Builds a per-field-ts RMD record on demand from any value schema
2. Initializes every per-field RMD entry to topLevelTs=0 (put-only state for collections)
3. Caches the RMD schema per (storeName, valueSchemaId)

...will allow the V2 algorithm (`WriteComputeHandlerV2.updateRecordWithRmd`) to be invoked on arbitrary fold operands
while producing **identical output** to the current V1 algorithm (`WriteComputeHandlerV1.updateValueRecord`) on the
SET_UNION-only workloads used by `testActiveActivePartialUpdateWithCompression`.

## Change

- New: `clients/da-vinci-client/src/main/java/com/linkedin/davinci/schema/writecompute/WriteComputeSeedRmd.java` (210
  lines)
- New: `clients/da-vinci-client/src/test/java/com/linkedin/davinci/schema/writecompute/TestWriteComputeSeedRmd.java`
  (260 lines)

The helper does NOT yet wire into production paths — pure infrastructure + tests. Production wire-in is iter-3.

## Result

8/8 unit tests pass on first re-run after one fixture-only fix (V2's `handleModifyPutOnlyList` does NOT raise
`topLevelTs`; instead it populates the active-element-ts list with the modify-ts. Fixture corrected to assert against
active-ts list, not topLevelTs).

Tests cover:

- Structural shape: per-field-ts branch, scalars as Long(0), collections as put-only CollectionRmdTimestamp
- V2-with-seed RMD parity with V1 on single SET_UNION
- V2-with-seed RMD parity with V1 on 8-operand chain (the fold use case)
- Reset-in-place (in-place reuse across fold-loop iterations)
- SET_UNION + SET_DIFF interaction
- Schema cache hit on repeated calls

## Halt-trigger check

- Iter-2 unit test for V2 fold parity: **PASS**. Seed-RMD shortcut produces same output as V1 on operand-chain workload.
  Proceed to iter-3.

## Next step (iter-3)

Wire `WriteComputeSeedRmd` into `MaterializingFoldContext.foldOperands` and `foldOperandOnly`. Change the per-operand
call from `wcProcessor.applyWriteCompute(...)` (V1 path) to a new `applyWriteComputeV2(...)` method on
`StoreWriteComputeProcessor` that takes the V2 path with a fresh seed RMD per call.

Validation: re-run microbenchmark to verify expected speedup; then integration test §5.4.1 (the primary target —
`testActiveActivePartialUpdateWithCompression`).
