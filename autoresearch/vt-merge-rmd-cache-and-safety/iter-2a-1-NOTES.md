# iter-2a-1 NOTES — Track 2 Phase A: plumb operand timestamp through wire format

## Decision

Full FieldLevelRmdCache design (GOAL §3-§4) would need 7+ iterations on its own. With 9 iterations remaining after Track
1, that doesn't fit alongside Track 3 and buffer. Pivot to a SMALLER scope that still addresses the core cross-DC
correctness gap:

**Minimum viable architecture:** plumb (operand's real updateOperationTimestamp, base record's RMD) through the fold
path so V2 algorithm can do proper per-field DCR. No cache — re-read base RMD on each fold call. Acceptable perf cost
because the §5.4.2 tests have small chains, and the perf-target single-DC chunkKeys workload already has correctness
preserved by chain-order semantics.

## Phase A scope (this iteration)

Plumb operand's real timestamp through three boundaries:

1. **Leader side:** `VeniceWriter.updateForVtMergeOperand` currently passes `APP_DEFAULT_LOGICAL_TS = -2` to
   `sendMessage`, discarding the original RT message's logical timestamp. Modify the signature to accept a logicalTs
   param and pass it through to `sendMessage`. Caller in `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT`
   extracts the RT KME's writeTimestamp via `getWriteTimestampFromKME` and passes it.

2. **Follower side at consume:** `StoreIngestionTask.processKafkaDataMessage` UPDATE case (under flag-on) extracts the
   VT KME's writeTimestamp and frames it as part of OperandContent.

3. **Wire format extension:** `OperandContent.frame/parse` extended to include 8B BE long timestamp between
   updateSchemaId and payload: `[valueSchemaId : 4B BE][updateSchemaId : 4B BE][updateOpTs : 8B BE][payload]`

4. **Fold path:** `MaterializingFoldContext.foldOperands/foldOperandOnly` reads
   `OperandContent.updateOperationTimestamp` and passes it to `applyWriteComputeV2` as the `modifyTimestamp` parameter
   (replacing the chain-position counter).

## Unit test plan

Add to `TestOperandContent` (new file under
`clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/merge/`) to verify frame/parse round-trip on
the new 8-byte field.

Also a `TestMaterializingFoldContextTimestamp` (or update existing) that runs the fold over a chain with non-monotonic
ts and verifies the resulting value reflects ts-based ordering (a lower-ts later operand should lose to a higher-ts
earlier operand).

## Halt at end of Phase A

- Wire-format unit test passes
- All flag-off integration tests still pass (no regression — flag-on code paths only).
- Operand's real ts is now visible in fold logs (verifiable via timing logs we already have in
  MaterializingFoldContext).

Phase B will use the real ts AND read the base RMD to gate operands by per-field DCR.

## Halt trigger

If wire-format-side fixes break the chunkKeys test, halt and reassess.
