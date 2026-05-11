# walk-iter-1 — Phase 1 (H1): Enable WAL on data partitions when flag is on

## Hypothesis tested

**H1**: the race is the close+reopen-without-flush surface; enabling WAL on data partitions when
`server.vt.update.operand.enabled=true` forces durability before close, eliminating the loss.

## Unit-test sub-step (sanity-only per GOAL §3 Phase 1)

Added
`clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/RocksDBStoragePartitionWalConfigTest.java`
with 4 cases:

| Test                                 | partitionId           | flag  | expected disableWAL |
| ------------------------------------ | --------------------- | ----- | ------------------- |
| dataPartition_flagOff_disablesWal    | 0                     | false | true                |
| dataPartition_flagOn_enablesWal      | 0                     | true  | false               |
| metadataPartition_flagOff_enablesWal | METADATA_PARTITION_ID | false | false               |
| metadataPartition_flagOn_enablesWal  | METADATA_PARTITION_ID | true  | false               |

Reads `RocksDBStoragePartition.writeOptions.disableWAL()` via reflection (the field is protected). **Result**: 4/4 PASS
in <0.5s total. Sanity wiring is correct.

## Code change (production)

`clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStoragePartition.java` line ~199:

```java
this.writeOptions = new WriteOptions()
    .setDisableWAL(this.partitionId != METADATA_PARTITION_ID && !factory.isVtUpdateOperandEnabled());
```

(Was: `setDisableWAL(this.partitionId != METADATA_PARTITION_ID)`.)

Comment block added at line ~194 documenting the H1 experiment rationale.

## Integration test result

```
./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast
```

**FAILED at 47.373s**:

```
testPartialUpdateOnBatchPushedKeys[0](NO_OP) FAILED (47.373 s)
java.lang.AssertionError: expected [new_name_11] but found [first_name_11]
    at PartialUpdateTest.lambda$testPartialUpdateOnBatchPushedKeys$1(PartialUpdateTest.java:179)
```

Exact same symptom as the original race (the merge operand isn't visible in readback — only the base value remains). The
first occurrence of the failure was an environment-flake (ChunkedKeySuffix resource lookup failure at 7s). Re-ran
cleanly and reproduced the real race symptom.

## Decision

**H1 DISPROVEN.** Enabling WAL on data partitions does NOT fix the race. This corroborates the prior BLOCKED-NOTES
observation that flush-on-close didn't help either (iter-4 of the prior fix work-stream) — the bug is NOT a simple
memtable-loss-on-close surface.

Note: this also implies the bug is not even on the leader-side write path's durability; the WAL would have caught any
in-memtable loss. The bug must be in something WAL doesn't cover — most likely a logical splitter (H4) or a state-reset
(H3), or a path-divergence side-effect (H2).

Note: the unit-test sanity case still serves as a regression guard for the conditional. Leaving it in place is harmless.
Production code H1 change will be reverted before declaring final success (unless a later hypothesis confirms WAL-on is
also part of the eventual fix surface).

## Next step

Advance to Phase 2 (H2). Revert the iter-11 early-return for UPDATE messages in
`ActiveActiveStoreIngestionTask.processActiveActiveMessage` and re-run the same single test to see if a different
symptom appears.
