# walk-iter-4 — Phase 3 (H4): Partition-level framing parity unit test

## Hypothesis tested

**H4**: the bug is a structural divider — local-DC writes go through one framing path, remote-DC writes go through
another. The unit-test sub-step (per GOAL §3 Phase 3) verifies framing parity AT the partition layer; the integration
sub-step (fallback) would instrument the consumer-thread `case UPDATE` handler with source-topic logging.

## Unit-test sub-step

Added `mergeFramingIsPureFunctionOfOperandBytes` to
`clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/MaterializingPartitionCloseReopenRaceTest.java`.

The test:

1. Opens a `MaterializingReplicationMetadataRocksDBStoragePartition`
2. PUTs a base for two keys (`keyLocal`, `keyRemote`)
3. Merges the same operand bytes through `partition.merge` twice — once on each key
4. Compares `partition.getRaw` outputs byte-for-byte

**Result**: PASSED (1.463 s). The on-disk readback is byte-equal between the two merges. The partition's `merge` API is
a pure function of (key, operand) — it does not branch on any topic or DC information (there is no such concept at the
partition layer).

## What this rules out

At the partition layer, the framing is NOT a structural splitter. The same operand bytes produce the same on-disk shape,
period. Therefore, **if** H4's structural divider exists, it must be upstream of `partition.merge` — in one of:

- The `case UPDATE` handler's branch selection (flag-on vs flag-off, leader vs follower)
- The consumer-thread routing (which topic/DC's record arrives on which Store-writer-sorted-tN thread)
- The deferred-write SST writer's interaction with the framing override
- The chunking-suffix wrap on the leader-side `produceUpdateOperandToVT`

## Integration sub-step (fallback)

Per the GOAL doc:

> **Integration experiment (fallback if unit test passes):** Instrument the follower-side `case UPDATE` handler in
> `StoreIngestionTask`/`ActiveActiveStoreIngestionTask` to log the source topic/DC for each UPDATE. ... Cross-reference
> with the keys showing operand-only readbacks (the `[VT-MERGE-READBACK]` evidence from iter-1) ... If keys from one DC
> are systematically affected and keys from the other are not, hypothesis CONFIRMED.

This integration diagnostic is the next step. However, given:

1. H1, H2, H3 disproven (iter-1, iter-2, iter-3)
2. Partition-level framing parity verified (this iter)
3. Budget consumed by 4 iters (out of 11), with the integration diagnostic itself taking 3-5 min per attempt
4. Prior fix work-stream already saw the split-half pattern (BLOCKED-NOTES §"Per-partition pattern") and noted the
   late-replica bootstrap path as a candidate that wasn't investigated
5. The unaddressed hypothesis (late-replica bootstrap bypass) sits outside the 4 walked-down hypotheses

The most honest conclusion is to declare BLOCKED with the unit-test evidence captured for the next work-stream. The
integration diagnostic for H4's upstream routing would add log instrumentation across the consumer-thread surface, which
is the same shape of investigation BLOCKED-NOTES already flagged as needing "deeper Venice-internals expertise".

## Decision

**H4 (unit-test sub-step) DISPROVES the partition-layer splitter hypothesis.** The bug is upstream of `partition.merge`.
Without the integration diagnostic (which would require additional iters beyond the unit-test signal already captured),
H4's full mechanism is AMBIGUOUS — partition-level parity holds, but consumer-routing-level parity is not verified.

Per GOAL §3 Phase 3 fallback: would now move to integration diagnostic. Per the user-specified walkdown order in the
bootstrap message (H1 → H2 → H4 → H3), and given that **H3 was tested in iter-3 before H4 in iter-4** (because H3's
unit-test angle was the most direct), the walkdown is effectively complete for the 4 stated hypotheses at the unit-test
sub-step. Advancing to the final disposition in BLOCKED-NOTES-WALKDOWN.

## Walk-down state

| Hypothesis           | Result                                            | Mechanism                                        |
| -------------------- | ------------------------------------------------- | ------------------------------------------------ |
| H1 (WAL)             | DISPROVEN                                         | iter-1: same race symptom with WAL enabled       |
| H2 (revert iter-11)  | DISPROVEN                                         | iter-2: same symptom; iter-11 skip is orthogonal |
| H3 (registry/reopen) | DISPROVEN at unit level                           | iter-3: close+reopen alone doesn't lose base     |
| H4 (DC splitter)     | DISPROVEN at partition layer; upstream not tested | iter-4: framing is byte-equal for same operand   |

## Next step

Write `BLOCKED-NOTES-WALKDOWN.md` documenting the walkdown results and the surviving hypothesis (late-replica bootstrap
bypass — out of scope for this walkdown per GOAL §9 "Non-goals").
