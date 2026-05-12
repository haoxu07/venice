# iter-2b-1 NOTES — Track 2 Phase B: plumb base RMD into fold path

## Hypothesis

Phase 2A plumbed operand's real ts but the §5.4.2 cross-DC tests still fail because:

- `testAAReplicationForPartialUpdateOnFields`: A PUT writes base with per-field-ts RMD = {name: 2, age: 2}. A later
  UPDATE arrives with ts=1. Fold path's seed RMD is put-only ts=0 → V2 says "1>0, apply" → result is wrong. Need to seed
  V2 with the base's RMD so V2 says "1<2, skip" → correct.
- `testAAReplicationForPartialUpdateOnListField`: same kind of issue for list element-level DCR.
- `testAAReplicationForPartialUpdateOnMapField`: PUT-vs-chain semantics are complicated (PUT after chain overwrites
  everything; the chained UPDATEs are lost). The Map test failure shows the PUT-vs-chain issue rather than just RMD-DCR.
  This may not be fixable in Phase B alone.

## Phase B scope

1. **Modify `applyWriteComputeV2` signature** to optionally accept an external seed RMD (instead of building fresh each
   call).
2. **In `foldOperands`:** read the base's RMD bytes via a new `rmdFetchFn` callback parameter. Deserialize via
   `RmdSchemaGenerator` + Avro deserializer. Pass as the seed RMD to V2.
3. **Maintain RMD across operands:** the seed RMD is built once before the loop; each operand mutates it in place (V2
   does this naturally).
4. **Storage partition:** in `MaterializingReplicationMetadataRocksDBStoragePartition.get`, read RMD bytes via
   `super.getReplicationMetadata(key)` and pass as the `rmdFetchFn` callback to materialize.

## Step-by-step plan

1. Add `applyWriteComputeV2` overload that takes a `GenericRecord seedRmd` parameter (or null to build fresh).
2. Add `Function<byte[], byte[]> rmdFetchFn` parameter to `MaterializingFraming.materialize`.
3. Thread that through `foldFramedBaseAndOperands` → `foldOperands(int, byte[], List<byte[]>, byte[] rmdBytes)`.
4. In `foldOperands`, if `rmdBytes != null`, decode using
   `RmdSchemaGenerator.generateMetadataSchema(valueSchema, getLatestVersion())` and a fresh Avro deserializer. Use as
   seed.
5. `MaterializingReplicationMetadataRocksDBStoragePartition.get` reads the RMD from the inherited
   `getReplicationMetadata` API.

## Unit test plan

- New test in TestWriteComputeSeedRmd (or new file): provide a base RMD with per-field-ts {name:2}. Apply a UPDATE
  operand with ts=1. Assert the operand is IGNORED (V2 returns NOT_UPDATED_AT_ALL).

## Halt condition

- Unit test demonstrates per-field DCR with base RMD seed
- Integration test `testAAReplicationForPartialUpdateOnFields` flag-on shows some PUT/UPDATE-by-ts scenarios start to
  work
- No regression on other tests
