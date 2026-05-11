# walk-iter-2 — Phase 2 (H2): Revert iter-11's processActiveActiveMessage early-return

## Hypothesis tested

**H2**: the iter-11 skip removed implicit protection (lock, partition stabilization, etc.) that flag-OFF still benefits
from. If iter-11's early-return is responsible for the race, reverting it should either (a) make the test pass or (b)
produce a different failure symptom.

## Unit-test sub-step

Per GOAL §3 Phase 2: not practical. The iter-11 early-return is trivially unit-testable but what the skipped code was
doing that incidentally protected against the race can only be observed when the test cluster is actually running.
Skipped directly to integration experiment.

## Code change (production, temporary)

`clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java`:
commented out the iter-11 early-return block (lines 499-518). Block restored after H2 verdict.

```java
// if (getServerConfig().isVtUpdateOperandEnabled() && msgType == MessageType.UPDATE) {
//   return null;
// }

```

## Integration test result

**FAILED at 44.488s** — SAME symptom as iter-1 and as the baseline blocked outcome:

```
testPartialUpdateOnBatchPushedKeys[0](NO_OP) FAILED (44.488 s)
java.lang.AssertionError: expected [new_name_2] but found [first_name_2]
    at PartialUpdateTest.lambda$testPartialUpdateOnBatchPushedKeys$1(PartialUpdateTest.java:179)
```

No new symptom (not the iter-5 operand-buffer-advancement bug; not a buffer-position corruption). The race still
manifests identically with iter-11 reverted.

## Decision

Per GOAL §3 Phase 2 decision tree:

> If test fails with the SAME symptom (operand-only readbacks): **H2 DISPROVEN**. Restore iter-11. Advance to Phase 3.

**H2 DISPROVEN.** The iter-11 early-return is orthogonal to the race. Whatever protection `processActiveActiveMessage`
was incidentally providing, it doesn't reach the late-replica / post-batch-push merge-readback path that produces the
operand-only bytes. Restored iter-11 verbatim. Production code now at baseline.

## Next step

Advance to Phase 3 (H4 — Per-key source-DC tracking). The unit-test angle is to verify that both local-DC and remote-DC
UPDATE paths produce identically-framed on-disk bytes at the
`MaterializingReplicationMetadataRocksDBStoragePartition.merge` level. If they match at the partition level, the
splitter must be upstream of `partition.merge` and falls back to integration diagnostic.
