# iter-2 NOTES — Phase 3 fold-path RMD persistence across operands

## Hypothesis

The actual root cause of the 3 §5.4.2 failures is NOT just per-field DCR — it's that the fold path's V2 invocation
**discards the RMD between operands within one fold call**. `StoreWriteComputeProcessor.applyWriteComputeV2` builds a
FRESH `seedRmd` on every call. So if operand 1 mutates the RMD (e.g. for list element-level DCR), operand 2 starts with
a fresh put-only seed and doesn't see operand 1's RMD state.

This explains all three failures:

- `OnFields`: a PUT@ts=2 sets `{name:val2f1_b, age:20}`. Operand UPDATE@ts=1 on name should LOSE. But fold path's seed
  RMD has every field's topLevelTs=0, so V2 says ts=1 > 0, apply — wrong result.
- `OnListField`: cross-DC list operations need element-level DCR which requires the RMD to persist mutations.
- `OnMapField`: same as list.

## Fix shape

1. **Persist RMD across operands** within one `MaterializingFoldContext.foldOperands` call:

   - Build the seedRmd ONCE before the loop, populated from the base record (honoring Gotchas #1/#2).
   - Pass `ValueAndRmd<GenericRecord>` (an existing reusable container) through each V2 call.
   - V2 mutates the RMD in place each call, so each subsequent operand sees the updated state.

2. **Seed the RMD from the base value record + Gotchas**:

   - For each value-schema field, inspect the base record's value:
     - Scalar at schema default → topLevelFieldTs=Long.MIN_VALUE, populatedByPut=false
     - Scalar with non-default → topLevelFieldTs=0 (operands can win for these fields; no DCR floor yet because we don't
       have on-disk RMD)
     - Collection empty at base → topLevelFieldTs=Long.MIN_VALUE
     - Collection non-empty → topLevelFieldTs=0 (operands can establish element-level DCR)
   - This makes the seed conservative: it doesn't gate operands using PUT's ts (we don't have that info readily at fold
     time). It just provides V2 a starting RMD for element-level DCR within the operand chain.

3. **Cross-DC ts ordering** comes from `OperandContent.updateOperationTimestamp` (Phase A already plumbed). When two
   DCs' operands arrive in non-ts order in the chain, V2 uses the operand's real ts to do per-element DCR correctly.

This is a smaller, more surgical fix than the full FieldLevelRmdCache + merge-time filtering. It doesn't require
plumbing on-disk RMD into the fold path; it just preserves RMD state across operand applications within one fold.

For per-key state across folds (cross-DC PUT-vs-UPDATE-chain DCR), we still need either:

- Per-fold per-operand RMD persistence (this fix)
- AND cross-fold cache or on-disk RMD seed

But for the existing failures, the cross-fold component might not be needed because the fold path always replays the
whole operand chain. As long as V2 sees the operands' real ts and persists RMD across them, DCR works.

## Plan

1. Add `applyWriteComputeV2` overload to `StoreWriteComputeProcessor` accepting an external `ValueAndRmd<GenericRecord>`
   seed.
2. Update `MaterializingFoldContext.foldOperands` to build `ValueAndRmd` once + pass through loop.
3. Update `MaterializingFoldContext.foldOperandOnly` similarly.
4. Use `FieldLevelRmdCache.buildPerFieldTsRecord` to seed the RMD from the base value record (honoring gotchas).
5. Run §5.4.2 integration tests.

## Halt

- Unit tests demonstrate RMD persistence and gotcha-honoring seed.
- §5.4.2 OnFields starts passing.
- §5.4.2 OnListField/OnMapField improve or pass.

## Result

(filled in after iter-2)
