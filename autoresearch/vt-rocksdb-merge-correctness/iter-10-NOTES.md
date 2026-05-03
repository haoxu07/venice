# Iter-10 — Investigate compression-mismatch + integration-shape unit reproduction

## Hypothesis tested

Top hypothesis from prior session: `MaterializingFoldContext.foldOperandOnly`'s `compressFolded(lastBytes)`
is a no-op when the fold context wasn't constructed with a compressor → folded bytes are
uncompressed when the chunking adapter on read expects compressed bytes → deserialization fails.

## Investigation

1. Read `LeaderFollowerStoreIngestionTask` line ~289: the fold context IS constructed with
   `this.compressor` (the per-store-version compressor) when the flag is on. So the registered
   context has the right compressor.
2. Read `StoreInfo.java`: default compression strategy is `NO_OP`. The integration test
   `testActiveActivePartialUpdateWithRecordMapField` does NOT set a compression strategy
   explicitly; it inherits the default. So compression is NO_OP.
3. The chunking adapter for NO_OP stores uses the `byteArrayDecoder` path (no decompression),
   which deserializes `bytes[SCHEMA_HEADER_LEN..]` as Avro. So if the materializing fold returns
   `[schemaId][uncompressed-avro]`, the read should succeed for NO_OP.

→ Compression is NOT the bug for this specific test.

## Test added

`MaterializingPartitionSmokeTest.mergeOnlyMapOpsOnNullableMapFieldFoldsToCorrectRecord` —
mirrors the integration test scenario at the storage layer:
- Schema: same as `PartialUpdateWithRecordMapField.avsc` (single field `nullableMapField`,
  union of `[null, map<TestMapRecord>]`, default null).
- Empty store (no PUT). Two MAP_OPS UPDATEs that each add 2 entries via
  `setEntriesToAddToMapField`.
- Reads via `RawBytesChunkingAdapter.getWithSchemaId(...)` with `isChunked=true` (key wrapped
  on both sides).
- Asserts decoded record has 2 entries after first merge and 4 entries after second.

WC bytes are serialized via `FastSerializerDeserializerFactory.getFastAvroGenericSerializer` to
mirror what `VeniceSystemProducer.serializeObject` actually does in the integration path
(plain HashMap is allowed). Initially used `MapOrderPreservingSerDeFactory.getSerializer` which
required IndexedHashMap and threw.

## Result

**Test PASSES (~120 ms).** All 11 tests in the smoke suite pass.

## Implication

The storage-layer fold path is correct for the integration scenario. The bug is at a higher
layer not captured by the unit test. Candidates:

1. **Race between merge landing on disk and read happening** — but the integration test waits
   up to 90s with `waitForNonDeterministicAssertion`. Not race-related.
2. **Pre-processing in `IngestionBatchProcessor.process` runs `processMessage` →
   `processActiveActiveMessage` → `mergeConflictResolver.update` BEFORE the leader fast-path
   fires.** This deserializes WC bytes (advancing the buffer position; iter-5 fix already handles
   this via arraySnapshot) AND reads the existing value via `getValueBytesForKey` AND writes
   a transient record via `setTransientRecord`. The transient record carries the merged full
   value. Side effects unclear.
3. **Multi-replica race** — RF=2 in the test means two replicas per partition. Reads might hit
   a replica that doesn't have the fold context registered yet (partition just opened, ingestion
   task not started).
4. **AA cross-region behavior** — leader in dc-0 forwards to local VT; leader in dc-1 ALSO
   consumes the RT and forwards to its local VT independently. The two regions' replicas
   independently call `merge` on their stores. Shouldn't be a correctness issue but worth
   verifying.

Next iteration: actually run the integration test (~14 min) and capture the deserialization
exception stack trace + the raw bytes returned by `materialize()`. The READ-DIAG and
SERVER-AFTER-GET logs will show what's actually happening.

## Hypothesis → result

| Hypothesis | Result |
|---|---|
| Compression mismatch in `compressFolded` | DISPROVEN — store is NO_OP, fold compressor is the right one |
| Storage-layer operand-only MAP_OPS fold is incorrect for nullable union map field | DISPROVEN — unit test passes |

## Status

Need integration-test run with diagnostics to make progress. Iter-11 will run the integration
test, capture logs, examine the actual failure mode.
