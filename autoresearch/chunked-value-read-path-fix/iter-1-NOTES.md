# iter-1 — Phase 1: Bug characterization (via code-reading)

## Hypothesis

The chunked-value bug is in the read path. When operands are appended to a chunked-manifest top-level key,
`MaterializingFraming.materialize()` returns raw bytes (manifest + operand suffix) because of the `schemaId < 0` early
return at line 186. The chunking layer (`ChunkingUtils.getFromStorage`) then:

1. Reads the schemaId from the first 4 bytes — sees negative → enters chunked path
2. Deserializes the manifest via Avro starting at offset 4. Avro is greedy but stops reading when its schema is
   exhausted — so it consumes the manifest correctly and IGNORES the trailing operand bytes
3. Reassembles chunks per the manifest → returns the ORIGINAL value
4. Operands are silently dropped — the read returns pre-update state

## Bug shape (confirmed via code inspection)

On-disk bytes for a chunked-manifest top-level key after operand merges:

```
[chunkManifestSchemaId : 4B BE, negative]
[serialized ChunkedValueManifest Avro bytes]
[delim 0x01] [0x01 KIND_OPERAND] [len:varint] [operand1]
[delim 0x01] [0x01 KIND_OPERAND] [len:varint] [operand2]
...
```

(No `[KIND_BASE 0x00][len:varint]` prefix on the manifest itself — the materializing partition's `shouldBypassFraming`
short-circuits framing for `schemaId < 0`.)

`materialize()` line 186-188 returns this raw blob; downstream chunking-reassembly ignores the operands.

## Why this matches the observed test failure symptoms

- `testActiveActivePartialUpdateOnBatchPushedChunkKeys`: reads original value (key="1" not "new_name_1") — matches:
  original value reassembled, operands ignored.
- `testActiveActivePartialUpdateWithCompression[*]` empty-push variant: there's no batch-pushed base, so first writes
  are UPDATEs. With chunking enabled, these are followed by more UPDATEs that eventually trigger chunking via a fold...
  or stay as operand-only on a non-chunked key. Will revisit if Option A doesn't cover this case.

## Decision: pursue Option A (read-path fold-and-rewrite)

Per GOAL doc preference order. Approach:

1. In `materialize()`, when `schemaId < 0`, detect appended operand bytes
2. The bytes shape after Avro manifest is ambiguous (manifest Avro length is not in the on-disk header) — but we CAN
   attempt Avro deserialization to know the manifest's consumed length, OR we can scan from the END for the operand
   pattern, OR we can add a framing prefix for chunked-manifest writes too
3. Cleanest: in `materialize()`, deserialize the manifest (using ChunkedValueManifestSerializer) to discover its length,
   then scan trailing bytes as the operand chain

**But there's a structural problem:** `materialize()` is called at the storage partition layer. The chunking-reassembly
path is at the chunking-adapter layer ABOVE the partition. Within `materialize()`, we don't have a way to fetch the
chunk keys (they're separate keys, possibly on other partitions even — no wait, chunks for one manifest live on the same
partition).

Looking more carefully: chunks for a given manifest are stored with chunk-suffixed keys on the SAME partition as the
manifest. So in principle the partition has access to all chunks via its own `super.get(chunkKey)`.

**Refined Option A plan:**

In `materialize()`, when `schemaId < 0`:

1. Detect manifest vs chunk (CHUNK_MANIFEST_SCHEMA_ID vs CHUNK_SCHEMA_ID — manifest needs special handling; individual
   chunks have no operands and stay verbatim)
2. For manifest: deserialize manifest, find appended operand bytes (if any)
3. If no operands appended → return raw (current behavior, fast path)
4. If operands appended: a. Reassemble the chunked value using the manifest's chunk keys (via storage partition's own
   super.get) b. Apply operands via MaterializingFoldContext.foldOperands c. Return [schemaId][materializedValue] —
   note: schemaId from the manifest's `chunkedValueManifest.schemaId` field, NOT the manifest's negative schemaId

This requires `materialize()` to have access to the partition's `super.get(chunkKey)`. That means we cannot keep
`materialize()` as a pure static utility — we'd need to pass a function reference or move the logic into the partition
class itself.

**Implementation: move the chunked-manifest+operand fold into the partition class** (or pass a chunk-fetch function into
`materialize()`). The partition has the right context.

## Next step

iter-2: Write a unit-test reproducer at the partition level that:

- Constructs a chunked-manifest blob (with negative schemaId)
- Appends an operand
- Calls `materialize()` (or a partition `get`)
- Asserts a usable materialized value comes back (NOT raw bytes including operand suffix)

Then implement the fix.
