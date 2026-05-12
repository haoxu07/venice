# iter-3 NOTES — Plumb on-disk RMD into fold path

## Hypothesis

iter-2's fold-path RMD persistence was a NECESSARY but INSUFFICIENT fix. OnFields integration test still fails: expected
`[val2f1_b]` but found `[val2f1_a]`. Trace:

- PUT@ts=2 `{name:val2f1_b, age:20}` lands → AA leader RMW writes RMD `{name:2, age:2}` (per-field-ts mode).
- UPDATE@ts=1 name=val2f1_a → goes through flag-on fast path → appended to chain as operand.
- UPDATE@ts=3 age=40 → same.

At fold time, my iter-2 seed built RMD from base value with PUT ts=0 (we passed 0 because we didn't have the PUT ts). So
`name` got `topLevelFieldTs=0, populatedByPut=true`. Then UPDATE@ts=1 vs ts=0 → 1>0 → applied. WRONG.

The fix needs the actual PUT ts. That's on-disk in the RMD column family. So we need to plumb the RMD-fetch callback
into the fold path.

## Plan

1. Add `byte[] baseRmdBytes` parameter to `MaterializingFoldContext.foldOperands` (with overload for backward compat).
2. Plumb a `rmdFetchFn: Function<byte[], byte[]>` and `baseKey: byte[]` through `MaterializingFraming.materialize`.
3. In `MaterializingReplicationMetadataRocksDBStoragePartition.get`, supply both `chunkFetch` and `rmdFetch` callbacks.
4. In `foldOperands`, deserialize the on-disk RMD (skipping 4-byte valueSchemaId prefix). If it has per-field-ts mode
   (mode 1), use it directly as the seed (then apply Gotcha #1 to empty collection fields). If it has whole- record-ts
   (mode 0), convert to per-field with synthesizeFromPut (which honors both gotchas).
5. The seed RMD then has the right per-field-ts floors, and V2 will correctly drop low-ts operands.

## Result

(filled in after test runs)
