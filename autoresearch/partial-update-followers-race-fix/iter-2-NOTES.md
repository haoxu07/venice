# Iter-2 — Phase 2 fix attempt #1: sync-before-close in adjustStoragePartition (FAILED)

## Hypothesis (from iter-1)

The partition-lifecycle close+reopen (BEGIN_BATCH_PUSH / END_BATCH_PUSH / PREPARE_FOR_READ adjustments) drops
in-memtable writes because RocksDB WAL is disabled and `close()` is called without a prior `flush()`. Adding
`partition.sync()` before `closePartition(partitionId)` in `adjustStoragePartition` should force the memtable to L0
before close.

## Code change

In `AbstractStorageEngine.adjustStoragePartition`, between acquiring the write lock and calling
`closePartition(partitionId)`, added:

```java
try {
  partition.sync();
} catch (Exception syncFailure) {
  LOGGER.warn(...);
}
```

with `[ADJUST-PARTITION] SYNCING before close` and `[ADJUST-PARTITION] SYNC OK` diagnostic logs to confirm the sync ran.

## Result

FAILED with `expected [new_name_2] but found [first_name_2]` at 48.601s.

Server logs confirm sync IS being called on all 3 user-store data partitions (BEGIN_BATCH_PUSH, END_BATCH_PUSH,
PREPARE_FOR_READ), all 4 servers. **No sync failures.** Yet the readback distribution is unchanged from iter-1:

| operandFramedLen | readbackRawLen | iter-1 count | iter-2 count |
| ---------------- | -------------- | ------------ | ------------ |
| 23               | 55             | 26           | 26           |
| 23               | 23             | 8            | 8            |
| 24               | 58             | 208          | 201          |
| 24               | 24             | 126          | 126          |

Identical 134 operand-only readbacks. Sync did not help.

## Re-analysis — the bug is NOT partition-lifecycle

Closer inspection of the timeline for partition.identityHash=432975296 (a single partition object's lifetime,
post-PREPARE_FOR_READ on dc-0:42233):

```
22:26:04 ...readbackRawLen=24 (operand only)   <-- first batch of merges
22:26:04 ...readbackRawLen=24 (operand only)   <-- still operand-only
... (many more readbackRawLen=24)
22:26:04 ...readbackRawLen=23 (operand only, op variant)
22:26:04 ...readbackRawLen=55 (base + operand) <-- BASE NOW PRESENT
22:26:04 ...readbackRawLen=58 (base + operand)
22:26:04 ...readbackRawLen=58 (base + operand) <-- subsequent merges see base
```

**Critical observation:** on the SAME partition object (no close+reopen between events), EARLY merges see operand-only
(no base), but LATER merges see base+operand. This means the base PUTs are arriving AFTER the first batch of operand
merges on this partition. There is NO partition lifecycle event in between — the partition object is alive throughout.

This rules out the partition-lifecycle race hypothesis. The bug is **PUT-after-MERGE ordering across cross-region
consumer threads**.

## Revised root cause

For an AA store, a follower replica subscribes to BOTH local-DC VT AND remote-DC VT. The base PUTs arrive on both VTs
(each region's leader independently batch-pushes). The partial- update UPDATEs (after VT-merge experiment flag-on) also
arrive on both VTs. The follower's two consumer threads write to the SAME partition object — `synchronized` on the
partition serializes individual calls, but does NOT order PUTs vs UPDATEs across consumers.

Scenario:

1. Local-VT consumer: PUT_local(base) → UPDATE_local(operand)
2. Remote-VT consumer (slow on PUT, fast on UPDATE relay): UPDATE_remote(operand) → PUT_remote(base)

If the interleaving is:

- PUT_local (disk: [BASE_local])
- UPDATE_local (disk: [BASE_local][OP_local])
- UPDATE_remote (disk: [BASE_local][OP_local][OP_remote])
- PUT_remote (disk: [BASE_remote]) ← `rocksDB.put` REPLACES, wiping the operands!

Or even earlier (worse):

- UPDATE_remote (disk: [OP_remote])
- PUT_local (disk: [BASE_local]) ← wipes OP_remote
- UPDATE_local (disk: [BASE_local][OP_local])
- PUT_remote (disk: [BASE_remote]) ← wipes OP_local

The merge readback at write time shows operand-only because at that moment, the PUT hasn't landed yet (or vice-versa).
Once the PUT lands, **`rocksDB.put` wipes all preceding merge operands** because PUT is replace, not concatenate.

The merge operator (StringAppendOperator) only handles append/concat on `merge`. A `put` followed by `get` returns ONLY
the put value, ignoring prior operand chains.

## Fix direction (iter-3)

The fix is in the materializing partition's `put` / `putWithReplicationMetadata` override: **read the existing value
first**, and if it already contains operand bytes (concat-blob with KIND_OPERAND segments), preserve them by appending
after the new base. The wire format already supports `[base][delim][op1][delim][op2]`, so this is a same-format change.

Specifically:

```java
public synchronized void putWithReplicationMetadata(byte[] key, byte[] value, byte[] metadata) {
  byte[] valueToWrite = MaterializingFraming.shouldBypassFraming(value)
      ? value : MaterializingFraming.frameForPut(value);
  byte[] existing = super.get(key);
  if (existing != null && hasUnframedOperandBytes(existing)) {
    valueToWrite = appendOperandsToFramedBase(valueToWrite, existing);
  }
  super.putWithReplicationMetadata(key, valueToWrite, metadata);
}
```

This makes PUT preserve prior operands, eliminating the cross-region UPDATE-then-PUT loss.

## Code change reverted

The `partition.sync()` call in `adjustStoragePartition` and its diagnostic logs were reverted at end of iter-2 because
they did not help (and would only add noise/perf cost without the actual fix being in place). The `[ADJUST-PARTITION]` /
`[VT-MERGE-READBACK]` diagnostics from iter-1 remain in place for iter-3.

## Files modified (reverted at end of iter-2)

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/AbstractStorageEngine.java` (only the
  sync-before-close addition was reverted; the `[ADJUST-PARTITION]` diagnostic remains).

## Decision

Pivot strategy. Iter-3 will implement the put-preserves-operands fix in the materializing partitions'
put/putWithReplicationMetadata overrides.
