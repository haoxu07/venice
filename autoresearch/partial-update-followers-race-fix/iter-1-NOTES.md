# Iter-1 — Phase 1: VT-MERGE-READBACK + ADJUST-PARTITION diagnostics → CONFIRMED race

## Hypothesis

Per BLOCKED-NOTES.md §"Suspected RCA": followers lose operand merges because the partition is re-opened during ingestion
(BEGIN/END_BATCH_PUSH and/or PREPARE_FOR_READ adjust). With WAL disabled (RocksDBStoragePartition line 194:
`setDisableWAL(true)`), in-memtable writes are lost if `close()` happens before `sync()`/`flush()`.

## Approach

Added diagnostic log lines at INFO:

1. `[VT-MERGE-READBACK]` in both materializing partition `merge(byte[], ByteBuffer)`: after `super.merge(key, framed)`,
   immediately call `super.get(key)` and log `partition.identityHash`, `operandFramedLen`, `readbackRawLen`, and thread
   name.
2. `[ADJUST-PARTITION]` in `AbstractStorageEngine.adjustStoragePartition`: BEFORE close logs `oldIdentityHash`; AFTER
   reopen logs both `oldIdentityHash` and `newIdentityHash`.

Re-ran `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on (single isolated test) with init-script disabling retries and
`--fail-fast`.

## Result

FAILED with `expected [new_name_4] but found [first_name_4]` at 46.85s — same as iter-1 of the prior validation
work-stream.

Server logs extracted from `internal/venice-test-common/build/test-results/integrationTest/binary/output.bin` via
`strings`.

## Key evidence — readback histogram for user store data partitions

| operandFramedLen | readbackRawLen | count | interpretation                                               |
| ---------------- | -------------- | ----- | ------------------------------------------------------------ |
| 23               | 55             | 26    | base (31) + delim (1) + operand (23) = full **GOOD MERGE**   |
| 23               | 23             | 8     | operand alone, no base **MERGE LOST BASE**                   |
| 24               | 58             | 208   | base (34) + delim (1) + operand (23+1) = full **GOOD MERGE** |
| 24               | 24             | 126   | operand alone, no base **MERGE LOST BASE**                   |

Out of 368 follower merge events on the user store, **134 (~36%) returned operand-only readbacks**, meaning the base was
missing from RocksDB at merge time. The merge operator (StringAppendOperator) appends bytes — when there's no existing
value, the merge result is just the operand. So the merge call succeeded, but the base bytes that the batch push wrote
were absent.

## Timeline for one specific follower (dc-0:localhost_37895 partition=0)

```
22:00:20  [ADJUST-PARTITION] BEFORE close ... mode=BEGIN_BATCH_PUSH oldIdentityHash=77944219
          AFTER reopen ... newIdentityHash=1462831225   (consumer-side thread)
22:00:23  [ADJUST-PARTITION] BEFORE close ... mode=END_BATCH_PUSH oldIdentityHash=1462831225
          AFTER reopen ... newIdentityHash=262049763    (Store-writer-sorted-t3 / drainer)
22:00:35  [ADJUST-PARTITION] BEFORE close ... mode=PREPARE_FOR_READ oldIdentityHash=681341265
          AFTER reopen ... newIdentityHash=184512888    (Store-writer-sorted-t3 / drainer)
22:00:36  [VT-MERGE-READBACK] partition.identityHash=184512888 ... operandFramedLen=24 readbackRawLen=24
          (... many more on same identityHash, all readbackRawLen=24)
```

**Three close+reopen cycles** for partition=0 on this follower before the first partial-update merge arrives:

1. BEGIN_BATCH_PUSH adjust (consumer thread) — partition reopened with `deferred-write: false`
2. END_BATCH_PUSH adjust (drainer thread) — partition reopened
3. PREPARE_FOR_READ adjust (drainer thread) — partition reopened in read-allowed mode

The base values written by the batch push (deferredWrite=false on this partition, so writes go to the memtable, NOT to
SST files) sit in the memtable when each close fires. Since WAL is disabled, the memtable contents are LOST on close.
The 134 operand-only readbacks confirm: the base is gone by the time the follower's merge runs.

For comparison, the leader replica (dc-0:42167 partition=0) shows readbackRawLen=58 (base + operand) on the same
identityHash. The leader does NOT lose the base because the leader's partition has a different close-timing — or has had
a sync. Both follower replicas (dc-0:37895 and dc-1:46717 for partition=0) consistently lose ~half their bases.

## Root cause

`AbstractStorageEngine.adjustStoragePartition` line 187-188:

```java
closePartition(partitionId);     // calls partition.close() — does NOT sync first
addStoragePartition(partitionConfig);
```

`closePartition` (line 235-245) calls `partition.close()` without any flush:

```java
public synchronized void closePartition(int partitionId) {
  AbstractStoragePartition partition = this.partitionList.remove(partitionId);
  ...
  partition.close();
  ...
}
```

`RocksDBStoragePartition.close()` (line 916-954) does NOT flush before closing rocksDB. With WAL disabled, the memtable
contents are dropped.

## Decision

**CONFIRMED race.** Advance to Phase 2 — apply the fix `partition.sync()` before `closePartition(partitionId)` in
`adjustStoragePartition`, OR equivalently call `partition.sync()` inside `closePartition` before `partition.close()`.

Per GOAL §3 Phase 2: "If the race is 'RocksDB WAL not flushed before close' during `adjustStoragePartition`: fix by
ensuring `partition.sync()` (or equivalent) is called before any partition replacement."

## Files modified (diagnostic only — will be cleaned up in Phase 3)

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingReplicationMetadataRocksDBStoragePartition.java`
  (+13 LOC)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingRocksDBStoragePartition.java`
  (+13 LOC)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/AbstractStorageEngine.java` (+18 LOC)
