# iter-2 — Phase 2: Implement Option A (read-path fold-and-rewrite)

## Hypothesis (refined from iter-1)

The fix is at `MaterializingFraming.materialize()`. When the raw on-disk bytes start with a chunked-manifest schemaId
(CHUNK_MANIFEST_SCHEMA_ID, negative) AND have appended operand bytes, we must:

1. Deserialize the manifest from the leading bytes
2. Detect/parse the trailing operand chain
3. Reassemble the chunked value (via the partition's chunk-fetch capability)
4. Apply operands via `MaterializingFoldContext.foldOperands`
5. Return as a non-chunked materialized value: `[manifest.schemaId][materializedValue]`

The chunking-aware reader at `ChunkingUtils.getFromStorage` will then see a POSITIVE schemaId and treat the bytes as a
non-chunked value (correct).

## Implementation plan

1. Add a `Function<byte[], byte[]> chunkFetchFn` parameter to `materialize()` (overload-style: keep the existing
   zero-arg signature returning raw for chunked, add a new signature taking the fetch fn).
2. Within materialize, if schemaId is the CHUNK_MANIFEST_SCHEMA_ID and the raw blob is longer than the encoded manifest:
   a. Use `DirectBinaryDecoder` over a `ByteArrayInputStream` so we can measure consumption. b. Deserialize manifest →
   know consumed bytes. c. Slice trailing operand chain → parse via `parseOperandChainFromTrailing()` (new helper —
   treats trailing as ops, skipping leading structural delimiter byte if present). d. Reassemble: for each chunk key in
   manifest, call `chunkFetchFn`, strip the 4-byte chunk- schemaId header, concat all chunk content →
   `compressedFullValue`. e. Call `MaterializingFoldContext.foldOperands(manifest.schemaId, compressedFullValue, ops)`.
   (foldOperands handles decompress/recompress.) f. Return `[manifest.schemaId][materializedValue]`.
3. For schemaId == CHUNK_SCHEMA_ID (individual chunks): always return raw (no operands ever merge onto chunk keys).
4. Update both partition classes (`MaterializingRocksDBStoragePartition`,
   `MaterializingReplicationMetadataRocksDBStoragePartition`) to pass `this::superGetByteBuffer` (or a lambda) for the
   chunk-fetch fn.

## Detection of trailing operands

After Avro deserialization of the manifest, the BinaryDecoder backed by `ByteArrayInputStream` will have consumed
exactly N bytes. If `bis.available() > 0`, then trailing bytes exist. They have shape:

```
[delim 0x01][KIND_OPERAND 0x01][len:varint][op1][delim 0x01][KIND_OPERAND 0x01][...]...
```

The first byte is the structural delimiter inserted by RocksDB's StringAppendOperator. Parse the trailing region using
`ConcatBlobParser`'s operand-chain logic.

## Unit-test reproducer plan

Add a new test class
`clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/MaterializingChunkedManifestOperandTest.java`
that:

1. Sets up a partition with a fold context.
2. Manually writes a chunked-manifest blob to a key (bypassing the high-level chunking-writer).
3. Stores the chunks at chunk-suffixed keys.
4. Merges an operand at the manifest key.
5. Reads via `partition.get` → asserts materialized record reflects manifest base + operand.
