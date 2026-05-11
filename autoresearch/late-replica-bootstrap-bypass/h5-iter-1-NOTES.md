# h5-iter-1 — Phase 1 Sub-step 1a: Code-path enumeration of base-write entry points

## Files inspected

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/AbstractStorageEngine.java`
  - `put(int, byte[], byte[])`, `put(int, byte[], ByteBuffer)` → all dispatch to `partition.put` (materializing override
    applies)
  - `putWithReplicationMetadata(int, byte[], ByteBuffer, byte[])` → dispatches to `partition.putWithReplicationMetadata`
    (materializing override applies)
  - `merge(int, byte[], ByteBuffer)` → dispatches to `partition.merge` (materializing override applies)
  - `adjustStoragePartition` → close+reopen, NOT a write
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStoragePartition.java`
  - `put(byte[], ByteBuffer)` line 542 → either `rocksDBSstFileWriter.put` (deferredWrite) or `rocksDB.put`
    (non-deferred)
  - `merge(byte[], ByteBuffer)` line 586 → `rocksDB.merge`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBSstFileWriter.java`
  - `put(byte[], ByteBuffer)` line 121 → `currentSSTFileWriter.put` (raw SST file)
  - `ingestSSTFiles` line 469 → `rocksDB.ingestExternalFile` (raw SST file ingestion)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/ReplicationMetadataRocksDBStoragePartition.java`
  - `putWithReplicationMetadata(byte[], byte[], byte[])` line 66 → deferredWrite branch uses `super.put` + RMD-SST;
    non-deferred branch uses `rocksDB.write(writeBatch)`
  - `putReplicationMetadata` line 90 → writes only RMD CF (not VALUE CF; no concern for materializing framing)
  - `deleteWithReplicationMetadata` line 151 → deletes VALUE CF, puts RMD; no concern for adding bases
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingRocksDBStoragePartition.java`
  - `put(byte[], ByteBuffer)` override → applies framing then `super.put`
  - `merge(byte[], ByteBuffer)` override → applies framing then `super.merge`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingReplicationMetadataRocksDBStoragePartition.java`
  - `put(byte[], ByteBuffer)` override → applies framing
  - `putWithReplicationMetadata(byte[], byte[], byte[])` override → applies framing to value before super
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/BlobTransferIngestionHelper.java`
  - Inspected — blob transfer is NOT enabled in this test (no `setBlobTransferEnabled(true)`, no `BlobTransferManager`
    instance in test fixture)
  - So blob-transfer-based bootstrap is NOT the H5 vector here.
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java`
  - `putInStorageEngine` line 262 → dispatches to `storageEngine.putWithReplicationMetadata` / `put` /
    `putReplicationMetadata`, all of which apply materializing framing through the partition override.

## Critical observation — the iter-1 readback shape

iter-1 evidence shows 24-byte operand-only readbacks (134/368 events). 24 bytes is the EXACT size of
`[KIND_OPERAND][len:varint][operand-content]` for the test's UPDATE payloads. This means **RocksDB has NOTHING** before
the merge fires for these keys. NOT a "raw avro" base; literally NO base.

This rules out the literal H5 statement (raw avro base on disk → unparseable). Instead, the bug must be that the base
PUT NEVER reached the materializing partition's disk for those keys/replicas. Possible mechanisms:

1. The base PUT was routed to a different partition / different RocksDB instance and never landed here.
2. The base PUT was written into deferred-write SST, but the SST was discarded before ingestion (because the partition
   was reopened in non-deferred mode mid-batch, dropping the SST writer state).
3. The base PUT was lost via a concurrent close+reopen race (already disproven by Walkdown unit tests).
4. The base PUT was deleted before the merge (no test code does this).

## Phase 1 Sub-step 1b — diagnostic plan

Add `[BASE-WRITE-PATH]` log lines (INFO level) capturing:

- entry-point name
- partition.identityHash
- key.first8 (hex)
- value.first8 (hex) — to verify framing is applied
- thread name
- framing-in-progress flag

At these entry points:

1. `MaterializingRocksDBStoragePartition.put(byte[], ByteBuffer)` — after framing
2. `MaterializingReplicationMetadataRocksDBStoragePartition.put(byte[], ByteBuffer)` — after framing
3. `MaterializingReplicationMetadataRocksDBStoragePartition.putWithReplicationMetadata` — after framing
4. `RocksDBStoragePartition.put(byte[], ByteBuffer)` — at the rocksDB.put / SST writer call (catches ALL writes, framed
   or not)
5. `RocksDBSstFileWriter.put` — at SST file writer (catches the deferred-write path)

Also re-add `[VT-MERGE-READBACK]` from iter-1 to correlate.

If we see entries 4 or 5 fire WITHOUT a corresponding entry 1-3 (i.e., a base write that bypassed the materializing
override), we've found the bypass.

If all base writes go through entries 1-3 (framed), then H5 is disproven. The bug is elsewhere (e.g., consumer routing).
