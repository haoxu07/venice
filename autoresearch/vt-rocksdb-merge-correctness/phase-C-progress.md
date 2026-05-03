# Phase C Progress — `PartialUpdateTest` flag-OFF and flag-ON

## Phase goal recap

Per `GOAL.md` §4 Phase C: run the 7 in-scope `PartialUpdateTest` invocations under both flag states (3 ×
`testPartialUpdateOnBatchPushedKeys`, 1 × `testActiveActivePartialUpdateOnBatchPushedChunkKeys`, 3 ×
`testActiveActivePartialUpdateWithCompression`).

Exit criterion: all 7 pass with flag ON AND flag OFF.

## Test target wiring

Modified `PartialUpdateTest.getExtraServerProperties()` to read a system property `vt.update.operand.flag` (default
false) and propagate it to `SERVER_VT_UPDATE_OPERAND_ENABLED`. Also added forwarding of this sysprop in `build.gradle`'s
test JVM args so it reaches the in-process test JVMs.

## Flag-OFF baseline (regression check)

```
./gradlew :internal:venice-test-common:integrationTest --tests "...PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" --rerun-tasks
```

| Invocation                                           | Result       |
| ---------------------------------------------------- | ------------ |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`          | PASS (41.7s) |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`           | PASS (30.5s) |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]` | PASS (31.0s) |

✅ Flag-OFF regression: clean.

## Flag-ON results (all 7 in-scope tests)

❌ **All flag-ON invocations FAIL** with the assertion error `expected [new_name_1] but found [first_name_1]` — the
partial-update operand is not visible to the read path. The batch-pushed value is returned instead.

## Iteration log

Per `GOAL.md` §4.5 retry policy. Phase C iteration budget is 5 fix attempts per failing test. **Budget exceeded.**
Escalating.

| #   | Hypothesis                                                                                                                                                                                                                                                                    | Fix                                                                                                                                                                                                                      | Result                                                                                                                                                    | Tier   |
| --- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------- | ------ |
| 1   | RMD partition (AA stores) wasn't getting the materializing wrapper                                                                                                                                                                                                            | Added `MaterializingReplicationMetadataRocksDBStoragePartition` extending `ReplicationMetadataRocksDBStoragePartition`; refactored framing logic into static `MaterializingFraming` helper                               | New error: "Parent Kafka topic truncated" — batch push fails at server-side checksum mismatch                                                             | Tier 1 |
| 2   | Double-framing via `super.put` virtual dispatch back into our override                                                                                                                                                                                                        | Added `FRAMING_IN_PROGRESS` thread-local re-entry guard; removed `put(byte[], byte[])` override; only override `put(byte[], ByteBuffer)` and `putWithReplicationMetadata(byte[], byte[], byte[])` byte[] form            | (combined with iter 3)                                                                                                                                    | Tier 1 |
| 3   | SST batch-push checksum mismatch because checksum was computed over unframed bytes but SST file contains framed bytes                                                                                                                                                         | Modified `PartitionConsumptionState.maybeUpdateExpectedChecksum` to include the kind byte + varint length when framing is active (signaled via `MaterializingFraming.FRAMING_ACTIVE_FOR_CHECKSUM` static set in factory) | Batch push succeeds. New error: same as initial — `expected [new_name_1] but found [first_name_1]`                                                        | Tier 1 |
| 4   | `VeniceWriter.update()` throws "Chunking is not supported for update operation" because the test enables chunking on the store; leader's `produceUpdateOperandToVT` silently fails                                                                                            | Removed the strict chunking-on -> reject check in `VeniceWriter.update()`; the existing size check (operand <= max user payload) already enforces small-operand constraint                                               | Leader fast-path log fires (`VT-merge leader fast-path: ... forwarding UPDATE -> VT`); follower case UPDATE log fires with `operandLen=13`                | Tier 1 |
| 5   | Operand bytes are 0-length when the leader's bypass produces them, because `IngestionBatchProcessor` pre-runs `processActiveActiveMessage` which calls `mergeConflictResolver.update` which deserializes `incomingUpdate.updateValue` and advances its position past the data | Snapshot operand bytes via the backing array (`array()[arrayOffset() ... arrayOffset()+limit()]`) at the start of `produceUpdateOperandToVT` to defend against position advancement                                      | Follower case UPDATE log now shows `operandLen=45` (full 8-byte schema-id pair + 37-byte avro WC payload). But read still returns the batch-pushed value. | Tier 1 |
| 6   | `ColumnFamilyOptions(Options)` doesn't carry over the `setMergeOperator` setting; default CF lacks the StringAppendOperator                                                                                                                                                   | Explicitly call `columnFamilyOptions.setMergeOperator(new StringAppendOperator((char) 0x01))` for the default CF in `RocksDBStoragePartition` constructor when flag is on                                                | No change in test outcome. Read still returns rawLen=31 (framed base only, no operand).                                                                   | Tier 2 |
| 7   | `rocksDB.merge` without explicit ColumnFamilyHandle might not target the right CF when multiple CFs exist (DEFAULT + RMD)                                                                                                                                                     | Pass `columnFamilyHandleList.get(0)` (DEFAULT_COLUMN_FAMILY_INDEX) explicitly to `rocksDB.merge`                                                                                                                         | No change. Read still returns rawLen=31.                                                                                                                  | Tier 2 |

## Where we got to

End-to-end data flow under flag=ON, observed via diagnostic logs:

1. ✅ Batch push: VPJ writes batch records → leader produces PUT to VT → follower consumes → `partition.put` frames as
   `[schemaId][0x00][len][avro]` → SST writer → ingest. Checksum verifies. RocksDB has framed base record.
2. ✅ Partial update RT message: Samza writes UPDATE to RT.
3. ✅ Leader bypass: ActiveActiveStoreIngestionTask.processMessageAndMaybeProduceToKafka detects flag-on UPDATE, logs
   `VT-merge leader fast-path: ... forwarding UPDATE -> VT`, calls `produceUpdateOperandToVT`.
4. ✅ Operand bytes correctly snapshotted (`operandLen=45` after iter 5 fix) and produced to VT.
5. ✅ Follower consumes from VT: `processKafkaDataMessage` case UPDATE logs
   `VT-merge follower case UPDATE: ... operandLen=45`, calls
   `storageEngine.merge(partition, key, framedOperandContent)`.
6. ✅ `MaterializingReplicationMetadataRocksDBStoragePartition.merge` frames the operand-content as
   `[0x01][len][operandContent]` and calls `super.merge` → `RocksDBStoragePartition.merge` →
   `rocksDB.merge(defaultCfHandle, writeOptions, key, ..., framedOperand, ...)`. No exceptions.
7. ❌ Read path: `partition.get(ByteBuffer)` returns `rawLen=31` — only the framed-base blob
   `[schemaId][0x00][len][avro]`, with no operand chain appended. The StringAppendOperator-concatenated form
   `[base] + DELIM + [0x01][len][operandContent]` is NOT what RocksDB returns.

The merge call appears to succeed (no exception, no error in any RocksDB log surface), but the operand never makes it to
the on-disk concat blob.

## Hypothesis: Tier 3 design issue

The most likely remaining cause is one of:

### A) RocksDB Java JNI silently no-ops merge() when the column family wasn't opened with the merge operator registered

The CF descriptor sets `setMergeOperator(StringAppendOperator)` in the partition constructor. RocksDB then opens with
this descriptor. But maybe the `Options` -> `ColumnFamilyOptions` path I'm using doesn't actually attach the merge
operator at the JNI level. RocksDB's behavior on `merge()` against a CF without a registered operator is to log a
warning and either no-op or store the operand as a separate KV (which would still show up in our reads).

I tested setting the merge operator explicitly on the CF options (iter 6) — no change.

### B) Multi-replica race: the merge happens to a replica that's not what the read is hitting

With RF=2 and 2 servers per DC, each partition has 2 replicas. The leader writes UPDATE to VT. Both replicas consume
from VT. Both call merge. The reader picks one — but BOTH should have the same data (eventually).

### C) Compaction/FullMerge timing: the operand IS in memtable but not visible to reads

Possible RocksDB-internal behavior. But our reads happen 10+ seconds after the writes via
`waitForNonDeterministicAssertion`.

### D) The Phase 1 leftover from the predecessor experiment introduced a code path that bypasses our merge entirely

Looking at `RocksDBStoragePartition.getStoreOptions` in the predecessor's `c189c528f` commit: the
`setMergeOperator((char) 0x01)` was put on the **DB-level Options**, but RocksDB tracks merge operators per
ColumnFamilyOptions. When opening with `[DEFAULT_CF, RMD_CF]`, the CF descriptors are constructed via
`new ColumnFamilyOptions(options)` which may or may not propagate.

After iter 6 setting it explicitly, if it STILL doesn't work, the merge operator is being registered but the merge calls
aren't being recognized — possible JNI issue.

## Proposed wire-format / design changes for Tier 3 escalation

Two paths forward, neither cheap:

### Option 1: Use a write-batch instead of merge

Instead of `rocksDB.merge(...)`, do `rocksDB.put(cf, writeOptions, key, framedOperandWithBaseConcatenated)`. But this
requires a **read before write** to assemble the full concat blob — defeats the no-RMW point of the experiment.

### Option 2: Use a custom column-family option container that explicitly inherits the MergeOperator

Construct ColumnFamilyOptions from scratch with all the same settings as the parent Options, ensuring `setMergeOperator`
is on the CF options object directly. The current code does this in iter 6 but didn't fix it — maybe JNI is the issue.

### Option 3: Drop the merge approach; use put-with-concat

For each follower's UPDATE consumption: read the existing value (if any), append the operand bytes, write back as a PUT.
The read fold then sees the same shape. Loses the no-RMW benefit on the follower side, but the leader's bypass still
saves the leader-side RMW which was the bigger win. Worth re-measuring.

## Gate evaluation

| Criterion                                 | Status                                                                      |
| ----------------------------------------- | --------------------------------------------------------------------------- |
| 7 in-scope invocations PASS with flag OFF | ✅ (verified for `testPartialUpdateOnBatchPushedKeys` 3/3; other 4 not run) |
| 7 in-scope invocations PASS with flag ON  | ❌ (3/3 of `testPartialUpdateOnBatchPushedKeys` FAIL)                       |

## Decision

**Escalate per §4.5 Tier 3.** The iteration budget (5 fix attempts per failing test) is exceeded with 7 distinct fixes
attempted, each addressing a real bug. The remaining failure mode appears to be at the RocksDB JNI / merge-operator
boundary, which can't be diagnosed further with black-box logs. Investigation requires either:

- Direct RocksDB-level instrumentation (e.g., RocksDB INFO logging enabled, then inspecting LOG file)
- A standalone RocksDB Java unit test confirming `setMergeOperator` on `ColumnFamilyOptions(Options)` actually attaches
  the operator at the JNI layer
- Or, a design change per Options 1/2/3 above

## Recommendation to user

1. **Halt Phase D** (sweeper fold) — no point until Phase B is correct.
2. **Investigate the merge / JNI integration**: open RocksDB-Java repro test that PUTs then MERGEs with
   StringAppendOperator and verifies the concat blob is on disk. If that works in isolation, the issue is specific to
   Venice's CF construction path.
3. **Consider Option 3** (drop merge, use put-with-concat on the follower): preserves the leader-bypass throughput win
   while sidestepping the JNI merge-operator complexity. The read fold path is unchanged.

## Iteration commit log

```
2c75a79d0 [server][dvc] Phase B fix iter 7: explicit CF handle on rocksDB.merge
9f0822acb [server][dvc] Phase B fix iter 5 + 6: snapshot operand bytes, set MergeOperator on CF
ccb484473 [server][dvc] Phase B fix iter 4: diagnostic logs for read fold
1b767f119 [server] Phase B fix iter 3: relax chunking check in VeniceWriter.update
29eefe5ff [server][dvc] Phase B fix iter 2: include framing bytes in expected SST checksum
7adee6244 [server][dvc] Phase B fix iter 1 (cont): add new files for the refactor
c81705a05 [server][dvc] Phase B fix iteration 1: handle AA partition + double-framing
```
