# Phase B Progress — `MaterializingRocksDBStoragePartition` + read-side fold

## Phase goal recap

Per `GOAL.md` §4 Phase B: build a `MaterializingRocksDBStoragePartition` that wraps `put`, `merge`, and `get` with
kind-byte framing on disk and folds concat blobs into materialized base records on read. The fold uses
`WriteComputeProcessor.applyWriteCompute`. Wiring change in the factory to pick the materializing subclass when the
feature flag is on. One smoke test demonstrates put → get and put → merge → merge → get round-trips.

Exit criterion: smoke test green AND existing storage tests still green (regression check for flag=OFF byte-equivalent
behavior).

## Code changes

| Commit      | File                                                                      | Description                                                                                                                                                                                                                         |
| ----------- | ------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `6d7da60f2` | `clients/da-vinci-client/.../MaterializingRocksDBStoragePartition.java`   | NEW — storage-partition subclass: wraps put/merge with kind-byte framing, overrides get to parse + fold + return today's `[schemaId][avro]` shape; bypasses framing for chunks/manifests                                            |
| `6d7da60f2` | `clients/da-vinci-client/.../merge/MaterializingFoldContext.java`         | NEW — per-store fold helper: holds `ReadOnlySchemaRepository`, `StoreWriteComputeProcessor`, value-deserializer cache; `OperandContent` inner type for the `[valueSchemaId][updateSchemaId][avro-WC-payload]` operand-content shape |
| `6d7da60f2` | `clients/da-vinci-client/.../merge/MaterializingFoldContextRegistry.java` | NEW — process-global registry keyed by `storeNameAndVersion`                                                                                                                                                                        |
| `6d7da60f2` | `clients/da-vinci-client/.../RocksDBStorageEngine.java`                   | wire factory to pick materializing subclass when flag is on (and partition is not metadata)                                                                                                                                         |
| `6d7da60f2` | `clients/da-vinci-client/.../LeaderFollowerStoreIngestionTask.java`       | register fold context on init, unregister on close                                                                                                                                                                                  |
| `6d7da60f2` | `clients/da-vinci-client/.../StoreIngestionTask.java`                     | `case UPDATE` prepends schema-id pair to operand before calling `storageEngine.merge`                                                                                                                                               |
| `6d7da60f2` | `clients/da-vinci-client/.../MaterializingPartitionSmokeTest.java`        | NEW — 4 smoke-test invocations                                                                                                                                                                                                      |

## Wire-format extensions to GOAL.md §3 — Tier 2 deviations

Two additions beyond what §3 specs:

### 1. Varint length on the materialized form (introduced in Phase A)

Already documented in `phase-A-progress.md`. The materialized form on disk is
`[schemaId : 4B BE][0x00][len:varint][avro-base]`. The `[len:varint]` is needed because the parser cannot otherwise
determine the base→operand boundary safely (avro bytes can contain any byte value, including the StringAppendOperator
delimiter).

### 2. Schema-id pair inside operand content

The on-disk operand framing is now:

```
[0x01][len:varint][valueSchemaId : 4B BE][updateSchemaId : 4B BE][avro-WC-payload]
```

The two schema-id ints are inside the operand-content (covered by the varint length). Without them, the read-fold path
cannot know which WC schema to deserialize the operand against — RocksDB's merge call only carries the operand bytes,
not the schema ids that the `Update` message carried on the wire.

The follower's `case UPDATE` in `StoreIngestionTask` prepends them via
`MaterializingFoldContext.OperandContent.frame(valueSchemaId, updateSchemaId, payload)`. The read-fold path strips them
via `OperandContent.parse()` and uses them to look up the right WC schema in `MaterializingFoldContext.foldOperands`.

This is consistent with the §3 contract that operands are tagged-against-the-value-schema (§3 says "operands are tagged
against the value schema currently active at the leader, which the reader can fetch from the base record"). The
schema-id-pair is just the on-disk realization of that contract — the leader puts them in, the reader reads them out.

## Test results

```
./gradlew :clients:da-vinci-client:test --tests "com.linkedin.davinci.store.rocksdb.MaterializingPartitionSmokeTest"
BUILD SUCCESSFUL in 8s
```

| Test                                     | Result         | Notes                                                                                                                                                                      |
| ---------------------------------------- | -------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `chunkWriteIsBypassed`                   | PASS (332 ms)  | chunk schemaId=-10 written and read as-is, no framing                                                                                                                      |
| `getReturnsNullForMissingKey`            | PASS (60 ms)   | null forwarded correctly                                                                                                                                                   |
| `putThenGetRoundTripsWithFraming`        | PASS (70 ms)   | base put → get returns `[schemaId][avro]` byte-identical to input; raw bytes on disk are framed `[schemaId][0x00][len][avro]`                                              |
| `putThenMergeThenGetFoldsViaFoldContext` | PASS (1.178 s) | full put + merge + merge + get round-trip with real Avro schema (User: firstName, lastName), real `StoreWriteComputeProcessor`, two operands folded into materialized form |

Regression check (flag-OFF path):

```
./gradlew :clients:da-vinci-client:test --tests "com.linkedin.davinci.store.rocksdb.RocksDBStoragePartitionTest" --tests "com.linkedin.davinci.store.rocksdb.RocksDBStorageEngineTest"
BUILD SUCCESSFUL in 1m 8s
```

All 30+ parameterized invocations passed — flag-OFF path byte-equivalent to today.

## Failures encountered & fixes

| #   | Failure                                                                                           | Tier   | Fix                                                    |
| --- | ------------------------------------------------------------------------------------------------- | ------ | ------------------------------------------------------ |
| 1   | Smoke test ClassInitError: `Schema.parse` static call removed in newer Avro                       | Tier 1 | switched to `new Schema.Parser().parse(...)`           |
| 2   | `WriteComputeSchemaConverter.convertFromValueRecordSchema` rejected schema without field defaults | Tier 1 | added `"default":""` to both fields in the test schema |

Both were Tier 1 trivia in the test fixture; no production-code changes.

## Gate evaluation

| Criterion                                                             | Status                                         |
| --------------------------------------------------------------------- | ---------------------------------------------- |
| Smoke test green (4 invocations)                                      | ✅                                             |
| Existing `RocksDBStoragePartitionTest` still green (regression check) | ✅                                             |
| Wire format documented and tested                                     | ✅                                             |
| Bypass for chunked-value paths                                        | ✅ (chunk schemaId=-10 path returns raw bytes) |

## Decision

**Continue to Phase C.** Storage-layer write/read fold is plumbed end-to-end. The integration tests in Phase C will
exercise it under realistic conditions (cluster, compression, A/A, chunks).

## Iteration log

| #   | Hypothesis                                                                            | Fix                                                                                                        | Result                             |
| --- | ------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------- | ---------------------------------- |
| 1   | Put/get with framing: a real fold context isn't needed for the materialized-only case | implemented `materialize()` to short-circuit when no operands                                              | put/get round-trip works (test 3)  |
| 2   | Need real Avro + WC + schema repo for the merge fold test                             | wired up `StoreWriteComputeProcessor` with mock schema repo, used `UpdateBuilderImpl` to build WC payloads | 2-operand fold test green (test 4) |
| 3   | Chunks must not be framed                                                             | added schemaId-based bypass in `shouldBypassFraming` (negative schemaId = chunk/manifest)                  | chunk test green (test 1)          |
