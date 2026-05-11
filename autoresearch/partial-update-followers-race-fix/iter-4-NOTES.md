# Iter-4/5/6/7/8 — Phase 2 fix attempts: close-time flush + put-preserve (FAILED)

These iterations consolidate four fix attempts that each address part of the race surface. Same outcome: test fails with
same operand-only-readback distribution.

## Iter-4: close-time `rocksDB.flush()` (failed)

**Change:** Inside `RocksDBStoragePartition.close()`, before `rocksDB.close()`, call
`rocksDB.flush(WAIT_FOR_FLUSH_OPTIONS, columnFamilyHandleList)` when `!readOnly`. With WAL disabled, the memtable
contents must be force-flushed to L0 before close so the re-opened RocksDB sees them.

**Result:** FAILED with `expected [new_name_4] but found [first_name_4]` at 46.5s. Readback distribution unchanged: 134
operand-only events (out of 368).

**Diagnostic check:** `[CLOSE-FLUSH] flushed memtable before close` logged for every user data partition close on every
replica — flush IS executing. Either: (a) the memtable was already empty at close time (no race), OR (b) flush isn't
actually persisting the data visible to a subsequent reopen.

## Iter-5: same close-time flush + diagnostic confirmation (failed)

Added `[CLOSE-FLUSH]` log to confirm the flush ran for user-store partitions. Confirmed flushes on all 4 servers'
partition 0/1/2. Distribution still 134 operand-only.

## Iter-6: put-preserve added back alongside close-flush (failed)

Reapplied iter-3's put-preserve to both `put(byte[], ByteBuffer)` and `putWithReplicationMetadata` overrides. Idea:
belt-and-suspenders. If close-flush doesn't catch every memtable state, at least put-preserve protects against
PUT-after-MERGE.

**Result:** FAILED. Distribution unchanged (134 operand-only).

## Iter-7: PUT-PRESERVE diagnostic added (failed)

Added `[PUT-PRESERVE] existingLen=...` diagnostic to inspect what the existing on-disk value looks like at PUT time.

**Result:** ALL 400 PUTs see `existingLen=-1`. The put-preserve helper has nothing to preserve — the PUTs never observe
an existing operand chain. So either the PUTs and operand-only merges happen on different partition objects
(close+reopen between them), or the PUTs run on partitions where the operand was already flushed (memtable empty).

## Iter-8: combined fix re-tested (failed)

Combined close-flush + put-preserve + diagnostics. Same result. **Distribution:**

| operandFramedLen | readbackRawLen | count | %   |
| ---------------- | -------------- | ----- | --- |
| 23               | 23             | 8     | 2   |
| 23               | 55             | 27    | 7   |
| 24               | 24             | 126   | 35  |
| 24               | 58             | 229   | 56  |

134 operand-only events persist across all fix attempts.

## Cross-server analysis

Among the 4 servers in iter-8 (dc-0:33211, dc-0:37157, dc-1:32825, dc-1:40193):

- One server (dc-0:37157) is a late-joining replica — its user-store data partitions ONLY see PREPARE_FOR_READ adjusts,
  no BEGIN_BATCH_PUSH/END_BATCH_PUSH. It opened in non-deferred-write mode, processed VT PUTs into memtable, then
  PREPARE_FOR_READ closed the partition. CLOSE-FLUSH ran but the operand-only readbacks persist on this server's
  post-PREPARE-FOR-READ partition.
- The other servers go through full BEGIN_BATCH_PUSH→END_BATCH_PUSH→PREPARE_FOR_READ lifecycle.

**On dc-0:37157 partition=2 specifically** (post-PREPARE-FOR-READ identityHash=2086272344):

- 14 readbackRawLen=58 (base+operand, good)
- 14 readbackRawLen=24 (operand-only, bad)

**Exactly half** the keys on this partition show operand-only — strongly suggests the partition's L0 has bases for ~half
the keys (the ones that went through batch push or were flushed cleanly) but not the other half (lost in some adjust
transition or via PUT-replace AFTER merge from cross-region).

## Why none of the fix attempts worked

The close-time flush should have preserved memtable contents across PREPARE_FOR_READ. The fact that it didn't suggests
either:

1. **The keys whose merges show operand-only were never PUT on this partition object's memtable** — they were PUT on a
   previous partition object whose memtable WASN'T flushed before close (e.g., before my fix in production code, or on a
   CONCURRENT close path I missed).
2. **The flush is invoked but doesn't actually persist** — possible if there's a columnFamilyHandleList issue, or if
   RocksDB's flush returns successfully but the L0 files aren't committed to the manifest. Unlikely given
   `setWaitForFlush(true)`.
3. **The merges happen on the WRONG partition object** — e.g., a stale reference. But the readback `super.get(key)` on
   the same `this` partition wouldn't return operand-only if the base was on disk for that partition.

## Decision

Phase 2 budget exhausted at iter 8 with confirmed RCA from iter-1 but no working fix. Both attempted fixes (close-time
flush, put-preserves-operand) are individually correct mitigations for the partition-lifecycle race and PUT-after-MERGE
bug respectively, but neither (nor their combination) eliminates the 134 operand-only events on this test.

The bug is therefore in a third surface that neither fix touches. The most likely candidate based on per-partition
readback patterns is the **interleaving of multiple consumer threads writing to the same partition object**: AA store
followers consume VT messages from local-DC AND remote-DC simultaneously, each on its own consumer thread. While
`partition.merge` and `partition.put` are individually `synchronized`, the PUT-vs-MERGE ordering across the two consumer
threads is non-deterministic. The merge readback diagnostic catches the moment AFTER a merge — if a PUT from the OTHER
consumer thread hasn't landed yet, the readback returns operand-only.

Halt and write BLOCKED-NOTES.md.

## Files modified across iter 4-8 (all currently in place)

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/AbstractStorageEngine.java` — `[ADJUST-PARTITION]`
  diagnostic (iter-1 retained)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStoragePartition.java` —
  `[CLOSE-FLUSH]` flush-before-close (~16 LOC of production fix + 1 log line)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingReplicationMetadataRocksDBStoragePartition.java`
  — `[VT-MERGE-READBACK]` diagnostic + put-preserve helper + `[PUT-PRESERVE]` diagnostic (~60 LOC of production fix +
  diagnostics)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingRocksDBStoragePartition.java` —
  `[VT-MERGE-READBACK]` diagnostic + put-preserve helper (~50 LOC of production fix + diagnostics)
