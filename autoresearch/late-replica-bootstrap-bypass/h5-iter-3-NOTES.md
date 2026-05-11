# h5-iter-3 — Phase 2: fix applied + integration test results

## Fix applied

1. **New method** `VeniceWriter.updateForVtMergeOperand(byte[], byte[], int, int, PubSubProducerCallback)` in
   `internal/venice-common/src/main/java/com/linkedin/venice/writer/VeniceWriter.java`:

   - Routes the message to the partition computed from the **UNWRAPPED** key (matches `put`).
   - Wraps the key with the chunking suffix INTERNALLY when `isChunkingEnabled` (matches `put`).
   - This eliminates the divergence between `put`'s hash-by-unwrapped-key and the legacy `update`'s hash-by-wrapped-key.

2. **Caller** `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT` (line ~1047) now calls
   `updateForVtMergeOperand(rawKeyBytes, operand, valueSchemaId, derivedSchemaId, callback)` instead of
   `update(keyBytes=wrapped, ...)`.

   - The explicit wrap of `rawKeyBytes` -> `keyBytes` at the top of `produceUpdateOperandToVT` is RETAINED because
     `keyBytes` is still used by `LeaderProducedRecordContext.newUpdateRecord` (which is consumed by the local-leader
     follower-style processing path that expects the wrapped key).

3. **Unit-test surface** added:
   `internal/venice-common/src/test/java/com/linkedin/venice/writer/VeniceWriterVtMergeOperandRoutingTest.java`
   - 3 tests, all PASS in <1s total:
     - `updateForVtMergeOperand_routesSamePartitionAsPut_whenChunkingEnabled` — verifies route parity vs put for keys
       1..99 (and sanity-asserts at least 1 key diverges in the legacy path, proving the test actually exercises the bug
       surface).
     - `updateForVtMergeOperand_wrapsKeyWithChunkingSuffix_whenChunkingEnabled` — verifies the on-disk KafkaKey is the
       wrapped key.
     - `updateForVtMergeOperand_doesNotWrapKey_whenChunkingDisabled` — verifies the chunking-disabled passthrough path.

## Integration test results (flag-on, with fix)

| Test                                                  | Result | Wall   | Notes                                                     |
| ----------------------------------------------------- | ------ | ------ | --------------------------------------------------------- |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`           | PASS   | 39.7s  | H5 fix works                                              |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`            | PASS   | 29.5s  | H5 fix works                                              |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`  | PASS   | 29.0s  | H5 fix works                                              |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys` | FAIL   | 46.7s  | Pre-existing failure (chunking-related, separate from H5) |
| `testActiveActivePartialUpdateWithCompression[NO_OP]` | FAIL   | 392.5s | Pre-existing failure (empty-push + chunking)              |

## Conclusion

**H5 partition-routing bug CONFIRMED and FIXED.** The 3 `testPartialUpdateOnBatchPushedKeys` invocations (which use a
small non-chunked value record schema) all PASS under flag-on with the fix.

**Pre-existing chunked-value failures** in the other 4 invocations persist. These have a different symptom
(`nullRecord=true` from the read path for empty-push tests, `expected [new_name_1] but found [1]` for chunkKeys) and a
different root cause (likely related to how the materializing fold interacts with the on-disk ChunkedValueManifest blob
— see `MaterializingFraming.materialize` line 186-188 which returns raw bytes for negative-schema-id, but the on-disk
bytes include appended operand bytes that break downstream chunking-reassembly).

Per `partial-update-batch-pushed-keys-validation/iter-1-NOTES.md` Phase B baseline, all 7 invocations were failing
BEFORE any H5 investigation. My H5 fix moves the count from 7/7 FAIL to 4/7 FAIL — a verified improvement on 3
invocations without regressing the other 4.
