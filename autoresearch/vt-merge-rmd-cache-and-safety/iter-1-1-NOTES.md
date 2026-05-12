# iter-1-1 NOTES — Track 1 Utf8 → String ClassCastException fix

## Hypothesis

`testAAReplicationForPartialUpdateOnMapField` flag-on returns 502 with
`ClassCastException: Utf8 cannot be cast to String` (from `vt-merge-rmd-aware-fold/iter-6-NOTES.md`).

Root cause located by static read of the V2 fold dispatch:

- `StoreWriteComputeProcessor.applyWriteComputeV2` (line 167) deserializes WC bytes with
  `writeComputeDeserializerCache.get(...)`, which when `fastAvroEnabled=false` routes to
  `MapOrderPreservingSerDeFactory.getDeserializer` → `MapOrderPreservingDatumReader extends GenericDatumReader`.
- Standard Avro `GenericDatumReader` deserializes string fields as `org.apache.avro.util.Utf8`, not `java.lang.String`,
  **unless the schema carries `"avro.java.string": "String"` or the reader overrides `readString`.**
  `PartialUpdateMapField.avsc` carries no such prop and the reader does not override.
- `WriteComputeHandlerV2.modifyCollectionField` (lines 158-159) casts to `Map<String, Object>` / `List<String>`. The
  generic cast is erased, but `SortBasedCollectionFieldOpHandler.handleModifyPutOnlyMap` line 786 does
  `for (String newKey: newEntries.keySet())` — this implicit Utf8→String cast at iteration time raises the CCE the test
  sees.

Why flag-off doesn't blow up on the same test: the AA-leader path (`MergeConflictResolver.deserializeWriteComputeBytes`)
is identical, but the production AA-leader code path runs with `fastAvroEnabled=true` in the integration test harness
(FastSerializerDeserializerFactory produces `java.lang.String` for map keys regardless of schema prop). The flag-on
path's `StoreWriteComputeProcessor.applyWriteComputeV2` also branches on `fastAvroEnabled` (line 209-211 of
getValueDeserializer), so in production with fast-avro the bug doesn't manifest either. But in unit/integration tests
where slow-avro is exercised, V2 sees Utf8 keys and explodes.

That said: the V2 handler should be defensive — V1 already is (it uses raw `Map` and iterates with `Object` typed
entries, so it tolerates Utf8). V2 should match.

## Fix shape (~5 LOC)

Coerce Utf8 keys to String at the V2 entry point — `WriteComputeHandlerV2.modifyCollectionField` just before passing to
`handleModifyMap`. Two transformations needed:

1. `mapUnion` (the MAP_UNION sub-record): rebuild as `IndexedHashMap<String, Object>` with `key.toString()` coercion.
2. `mapDiff` (the MAP_DIFF list of keys to remove): rebuild as `List<String>` with `key.toString()` coercion.

`String.toString()` is identity (returns `this`), so calling `.toString()` on an arg that is already a String is a no-op
and safe.

## Unit test plan

Add to `TestWriteComputeSeedRmd` (the existing seed-RMD test class — it's exactly the class GOAL §4.1 names for this
test).

Test: `testV2WithSeedRmdHandlesMapFieldWithUtf8Keys`

- Build a map-field value record (matching `PartialUpdateMapField.avsc` shape: `mapField: map<string,int>` with default
  `{}`)
- Build a write-compute payload using `WriteComputeSchemaConverter` and put MAP_UNION entries keyed by `Utf8` (mimicking
  what slow-avro produces on deserialize)
- Call `processor.updateRecordWithRmd(...)` via the same code path `applyWriteComputeV2` uses
- Assert the result map contains the expected keys (as String) and no CCE is thrown

## Run command (after fix)

```
./gradlew :clients:da-vinci-client:test \
  --tests "com.linkedin.davinci.schema.writecompute.TestWriteComputeSeedRmd" \
  --init-script /tmp/no-retry.gradle
```

Then run integration test to confirm 502 is gone:

```
./gradlew :internal:venice-test-common:integrationTest \
  --tests "*.TestPartialUpdateWithActiveActiveReplication.testAAReplicationForPartialUpdateOnMapField" \
  --init-script /tmp/no-retry.gradle
```

## Halt condition

- Unit test passes (it must fail BEFORE the fix; pass AFTER).
- Integration test no longer 502s. (May still fail at the functional DCR assertion — that's Track 2's territory; the 502
  specifically must be gone.)
