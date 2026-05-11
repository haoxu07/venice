# Iter-3 — Phase 2 fix attempt #2: put-preserves-existing-operand-suffix (FAILED, no measurable effect)

## Hypothesis

After iter-2's failure, re-analysis pointed at PUT-after-MERGE ordering. If a base PUT arrives on a key that already has
an operand suffix in the on-disk concat blob, `rocksDB.put` REPLACES the value and wipes the operands. The fix is to
read the existing on-disk bytes inside the materializing partition's `put`/`putWithReplicationMetadata` override, find
any operand suffix (post-base structural delimiter onwards), and append it to the new framed base before calling
`super.put`.

## Code change

Modified `MaterializingReplicationMetadataRocksDBStoragePartition.put(byte[], ByteBuffer)` and
`MaterializingReplicationMetadataRocksDBStoragePartition.putWithReplicationMetadata` to call a new helper
`preserveExistingOperandSuffix(key, newFramedBase)` that:

1. Reads `super.get(key)`.
2. If existing is null/empty → returns `newFramedBase` unchanged.
3. If existing is operand-only chain ([0x01][len][op]...) → prepends a synthetic StringAppendOperator delimiter byte and
   returns `[newFramedBase][delim][existing-chain]`.
4. If existing is framed-base + operand chain ([schemaId][0x00][len][base][delim][0x01]...) → finds the structural
   delimiter byte after the base and copies from there: returns `[newFramedBase][delim][operand-chain-from-existing]`.
5. If existing is plain framed-base (no operand suffix) → returns `newFramedBase` (no preservation needed; new base
   replaces old base).

Also added a `[PUT-PRESERVE]` diagnostic logging existing length and first byte.

## Result

FAILED with `expected [new_name_3] but found [first_name_3]`. The merge readback distribution was unchanged (134
operand-only events out of 368). The PUT-PRESERVE diagnostic showed ALL 400 PUTs have `existingLen=-1` (no existing
value at PUT time). So the put-preserve helper had nothing to preserve in any invocation — the fix was a no-op for this
test.

## Analysis

If all 400 PUTs see `existingLen=-1`, the PUT happens BEFORE any merge for that key on that partition object. So the
order on the partition is:

- PUT (base into memtable, no existing)
- MERGE (operand appended in memtable)

But then the merge readback should see `[BASE][delim][OP]` = 55 or 58 bytes — not 24 (operand-only). Yet we see 134
operand-only readbacks. So the PUTs that ran with `existingLen=-1` are not the PUTs corresponding to the operand-only
merges. The operand-only merges must be running on a DIFFERENT partition object than the PUTs.

Hypothesis refined: PREPARE_FOR_READ adjust closes the partition (memtable LOST per iter-1), opens a NEW partition
object. The merges run on the NEW partition. The PUT-PRESERVE diagnostic shows `existingLen=-1` because the NEW
partition's get returns null (memtable wiped, L0 has no base because the previous close didn't flush memtable first).

## Decision

Pivot strategy. Iter-4 will add an explicit `rocksDB.flush()` inside `RocksDBStoragePartition.close()` to drain the
memtable to L0 SST files before close. This should ensure the base is in L0 after the close+reopen cycle.
