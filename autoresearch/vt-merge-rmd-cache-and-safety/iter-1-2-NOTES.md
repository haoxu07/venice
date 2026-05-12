# iter-1-2 NOTES — Track 1 extension: value-record-side Utf8 + PUT_NEW_FIELD HashMap coercion

## Hypothesis

iter-1-1's fix coerced WC-payload-side Utf8 keys to String. Integration test re-run showed the 502 ClassCastException
was gone but the test still failed at a DIFFERENT 502:

- `IllegalStateException: Expect the value to put on the field to be an IndexedHashMap. Got: class java.util.HashMap`

And the second wave of MAP_DIFF tests showed that key removal was silently no-op because the value record's current map
field still had Utf8 keys (from base deserialization), while the operand's MAP_DIFF list now had String keys (post
iter-1-1).

Two additional issues to fix:

1. **Value-record-side Utf8:** `WriteComputeHandlerV2.modifyCollectionField` MAP branch — if the current map field is an
   IndexedHashMap with Utf8 keys (from slow-avro base-bytes deserialization), V2's `handleModifyPutOnlyMap` does
   `putOnlyPartMap = new IndexedHashMap<>(currMap)` which preserves Utf8 keys. Subsequent
   `putOnlyPartMap.remove(stringKey)` silently fails because `Utf8.equals(String)` returns false. Fix: coerce in-place
   at V2 entry point so both put-only and collection-merge paths see uniformly String-keyed maps.

2. **HashMap from fast-avro on PUT_NEW_FIELD:** `WriteComputeHandlerV2.updateRecordWithRmd` PUT_NEW_FIELD case —
   fast-avro deserializes a map field as `java.util.HashMap` (not `IndexedHashMap`).
   `CollectionTimestampMergeRecordHelper.putOnField` asserts `newFieldValue instanceof IndexedHashMap` for MAP fields
   and throws otherwise. Fix: coerce non-IndexedHashMap Maps to IndexedHashMap at the PUT_NEW_FIELD dispatch site for
   map-typed fields.

## Fix shape

Both fixes are in `WriteComputeHandlerV2`:

- New `hasNonStringKeys` helper to detect value-record's Utf8-keyed maps cheaply.
- New coercion at the `modifyCollectionField` MAP branch to fix the value record's map field in place if it has
  non-String keys.
- New coercion at the PUT_NEW_FIELD case (only for MAP-typed fields) to wrap the payload Map into an IndexedHashMap with
  String keys.

Unit tests added to `TestWriteComputeSeedRmd`:

- `testV2WithSeedRmdHandlesValueRecordWithUtf8KeyedMap` — base record's map has Utf8 keys; MAP_DIFF removes 2 of 3;
  expect 1 remaining.
- `testV2WithSeedRmdHandlesPutNewFieldWithHashMap` — PUT_NEW_FIELD with java.util.HashMap payload; expect IndexedHashMap
  with 2 entries in result.

## Integration test progress

After iter-1-1: `testAAReplicationForPartialUpdateOnMapField` 502 cleared, but new 502 from IndexedHashMap assert. Test
fails at the MAP_DIFF step.

After iter-1-2: 502 fully cleared. The map test now progresses through several DCR steps correctly. Final failure mode
is cross-DC ts-DCR (an UPDATE with explicit ts=3 from one DC isn't taking effect because the per-field-ts isn't
preserved across the chain). This is Track 2's territory.

## Halt condition

- All 12 unit tests pass (10 pre-existing + 2 new this iteration).
- Track 1's 502 elimination goal is met. Map-field test now fails at functional DCR assertion, which is the §5.4.2
  territory.
- Track 1 is complete; move to Track 2 strategy decision.
