# Phase 4 — `MergeConflictResolver.put` PUT-path sub-stage map

Goal: instrument `MergeConflictResolver.put` (PUT path) into >=5 named sub-stages
that together account for >=80% of dcr_merge wall (~2,877 ns/call at the Phase 3
winner config Exp.4 A+C+B-2). The benchmark sets `useFieldLevelTimestamp` (via
`setWriteComputationEnabled(true)`), so the PUT path goes through
`mergePutWithFieldLevelTimestamp`. Most invocations have `rmdWithValueSchemaID != null`
(transient-cache-hit regime at this drive rate), so `putWithoutRmd` is rare.

## Call graph for the PUT happy path (per record)

`ActiveActiveStoreIngestionTask` calls `mergeConflictResolver.put(...)` with:
  - `oldValueBytesProvider` (lazy, will be invoked inside the merge)
  - `rmdWithValueSchemaID != null` (Phase 3 winner: transient cache hit regime)
  - `newValueBytes`, `putOperationTimestamp`, `newValueSchemaID`, `newValueColoID`

In `MergeConflictResolver.put` (file:lines):
  - L127–128: read `oldRmdRecord` and `oldTimestampObject`
  - L136: branch — `useFieldLevelTimestamp == true` -> `mergePutWithFieldLevelTimestamp`

In `mergePutWithFieldLevelTimestamp` (file:lines 280–327):
  - L290–295: `ignoreNewPut` early-exit check (only if `oldTimestampObject` is a
    `GenericRecord`). For value-level timestamp inputs this is skipped at this
    site — but at the steady state (Phase 3 winner) RMD has already been
    converted to per-field-timestamp; first incoming PUT may go through the
    value-level branch. Either way, this early-out can be a sub-stage.
  - L297–298: `mergeResultValueSchemaResolver.getMergeResultValueSchema(...)` —
    schema-id resolution / cache lookup for the merge result schema.
  - L305–307: `deserializerCacheForFullValue.get(...).deserialize(newValueBytes)`
    — Avro decode of the incoming new PUT value bytes into a `GenericRecord`.
  - L308–313: `createOldValueAndRmd(...)` — this is the expensive helper:
      * L394–398: `createValueRecordFromByteBuffer` — Avro decode of the OLD
        value bytes (calls `oldValueBytesProvider.get()`); for
        Phase 3 winner, the old bytes come from the transient cache hit
        path so this is mostly an Avro deserialize cost.
      * L402: `convertToPerFieldTimestampRmd(oldRmdRecord, oldValueRecord)` —
        if RMD timestamp is value-level, build a per-field-timestamp record
        (allocates a new `GenericRecord`, fills per-field defaults). At
        steady state most records are already per-field, so this is an
        early-return after a `RmdUtils.getRmdTimestampType` check.
      * L403–405: `convertRmdToUseReaderValueSchema` — only when reader/writer
        value schema ids differ, otherwise no-op.
      * L406–408: allocates `ValueAndRmd<>` wrapper.
  - L315–316: `mergeGenericRecord.put(...)` — the **actual merge**:
      * `MergeGenericRecord.put` (L56–80): `validatePutInputParams` (cheap
        instanceof / superset check) + `RmdUtils.getRmdTimestampType` + branch
        to `handlePutWithPerFieldLevelTimestamp` (the steady-state branch).
      * `handlePutWithPerFieldLevelTimestamp` (L98–127):
          - `updateReplicationCheckpointVector(rmd)` (L106) — sets
            `oldRmd[REPLICATION_CHECKPOINT_VECTOR_FIELD_POS] = emptyList`
            (allocation cheap; just a field put).
          - For-each field in newValue's schema (L112–122): `putOnField` per
            field. The benchmark has 4 fields (name string, age int, score
            double, tags map<string,string>). Map field goes to
            `CollectionTimestampMergeRecordHelper.handlePutMap` which does
            sort-based collection merge — this is O(map_size) per record
            and is likely a chunky cost for the 100-entry map workload.
            Primitive fields go to `PerFieldTimestampMergeRecordHelper.putOnField`
            (cheap: timestamp compare + maybe a `oldRecord.put(pos, newValue)`).
  - L317–319: `isUpdateIgnored` check.
  - L320–321: `serializeMergedValueRecord(mergedSchemaId, mergedValue)` — Avro
    encode of the merged value to bytes (returns a `ByteBuffer` for VT produce).
  - L322–326: allocate `MergeConflictResult` return value.

## Chosen sub-stage cut points (>=5 named, target >=80% coverage)

Each cut point bracketed by `System.nanoTime()` only when the new gate
`AaDcrMergeReporter.ENABLED` is true.

| # | Sub-stage label                 | Cut point inside `MergeConflictResolver.java` (or helper) | Hypothesis on cost |
|---|---------------------------------|-----------------------------------------------------------|--------------------|
| 1 | `new_value_deserialize`         | Line 305–307 (the `deserializerCacheForFullValue.get(...).deserialize(newValueBytes)` call). | Avro decode of the incoming PUT value bytes. |
| 2 | `old_value_deserialize`         | Inside `createValueRecordFromByteBuffer` (L411–420), bracket the entire call from `mergePutWithFieldLevelTimestamp` (L308–313 `createOldValueAndRmd` is broader; we only want the Avro decode of old value). | Old value Avro decode (transient cache hit returns ByteBuffer; we still pay decode cost). |
| 3 | `rmd_prepare`                   | Lines 402–407 inside `createOldValueAndRmd` (`convertToPerFieldTimestampRmd` + `convertRmdToUseReaderValueSchema` + `ValueAndRmd<>` allocation). | RMD format conversion + wrapper alloc. |
| 4 | `merge_put_apply`               | Line 315–316: `mergeGenericRecord.put(oldValueAndRmd, newValueRecord, ...)` — the actual per-field timestamp compare + map merge. | Field iteration (4 fields incl. 100-entry map). Likely the heavyweight bucket. |
| 5 | `merged_value_serialize`        | Line 320–321: `serializeMergedValueRecord(...)`. | Avro encode of merged GenericRecord to bytes for VT produce. |
| 6 | `merge_result_alloc`            | Line 322–326: `new MergeConflictResult(...)` return-value allocation. | Cheap allocation but counted to keep coverage tight. |
| 7 | `schema_resolve`                | Line 297–298: `mergeResultValueSchemaResolver.getMergeResultValueSchema(...)`. | Schema-id pair resolution / cache. |
| 8 | `ignore_new_put_check`          | Line 290–295 `ignoreNewPut` invocation when applicable. | Field-iteration short-circuit ts compare. |

Outer wrapper: an `OUTER` (or `_total`) bracket placed at the `put(...)` entry
that records the same wall as the existing `Stage.DCR_MERGE` from
`ActiveActiveStoreIngestionTask`. We will reuse this to compute
`pct_of_dcr_merge` per-sub-stage = sub_total_ns / outer_total_ns.

## Coverage expectation

Sum of (1)+(2)+(3)+(4)+(5)+(6)+(7)+(8) ~= the body of
`mergePutWithFieldLevelTimestamp` minus a few boolean checks and the call-stack
overhead (early returns from the L290–295 ignoreNewPut check are observable
because we record 0-ns / count-only when not entered).

Practical coverage estimate:
  - new_value_deserialize:  ~15-25%
  - old_value_deserialize:  ~15-25%
  - rmd_prepare:            ~5-10%
  - merge_put_apply:        ~25-45% (likely top, due to 100-entry map)
  - merged_value_serialize: ~10-20%
  - merge_result_alloc:     <2%
  - schema_resolve:         <2%
  - ignore_new_put_check:   <5%

Sum target: 80-95% of dcr_merge_total_ns.

## Implementation notes

- `AaDcrMergeReporter` mirrors `AaLeaderBottleneckReporter`: per-stage
  `LongAdder` count + `LongAdder` total_ns + `AtomicLong` max_ns, ScheduledExecutorService
  20s tick, daemon thread, gated by `-Dvenice.server.aa.dcr.merge.instrumentation.enabled`.
- An additional `OUTER` stage records the put(...) wall so that
  `pct_of_dcr_merge` can be computed per sub-stage.
- All sub-stage timer brackets are gated by the static-final ENABLED flag so
  the production hot path is byte-for-byte unchanged when the flag is off.
- We do NOT change merge logic. Only add `if (ENABLED)` brackets around
  existing call sites.
