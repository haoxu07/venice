# Iter-1 — Phase A baseline + Phase B failure characterization

## Hypothesis tested

Run all 7 invocations both flag-OFF and flag-ON to characterize the current state of the branch (which has 11 commits
since the iter-11 fix).

## Phase A — flag-OFF (baseline)

**Result: 7/7 PASS.** Branch hasn't regressed flag-off behavior.

| Test                                                           | Result | Wall    |
| -------------------------------------------------------------- | ------ | ------- |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | PASS   | 43.995s |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | PASS   | 53.677s |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | PASS   | 52.049s |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | PASS   | 35.097s |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | PASS   | 28.642s |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`                     | PASS   | 28.733s |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | PASS   | 29.499s |

## Phase B — flag-ON

**Result: 7/7 FAIL.** Same root failure mode across all 7: partial UPDATE not applied; read returns the original value
(or null in the empty-push variants).

| Test                                                           | Result | Wall                     | Failure                                                                                  |
| -------------------------------------------------------------- | ------ | ------------------------ | ---------------------------------------------------------------------------------------- |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | FAIL   | 48.753s (isolated rerun) | `expected [new_name_1] but found [1]` (orig batch-pushed value)                          |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | FAIL   | 390.613s                 | `expected [false] but found [true]` (`nullRecord=true`; read returned null)              |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | FAIL   | 391.615s                 | (same null-read symptom)                                                                 |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | FAIL   | 387.892s                 | (same null-read symptom)                                                                 |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | FAIL   | 39.866s                  | `expected [new_name_4] but found [first_name_4]` (orig value)                            |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`                     | FAIL   | 37.940s                  | `expected [new_name_3] but found [first_name_3]`                                         |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | FAIL   | 29.718s                  | Cascading: `RouterException: no version` (likely test-infra cascade from prior failures) |

## Diagnostic data

- Symptom for tests with batch-push base: original value returned, partial-update operand never visible.
- Symptom for tests with empty-push base: `valueRecord == null` after 39 partial updates over ~6.5 min wait timeout.
- Sister test `TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField` (only 2
  partial updates, simpler schema, AA + chunking-on, NO_OP) was previously confirmed PASS per
  `phase-C-progress-update.md`.
- No SERVER-DESERIALIZE-FAIL or VeniceSerializationException in the test stdout — failures are at assertion level, not
  at deserialization level. This rules out the iter-11 prefix-capture bug regression.

## Tier classification

**Tier 2: new bug investigation.** The iter-11 fix (the 5th of the prior 5 fixes) was verified for one specific scenario
(sister test, MAP_OPS, NO_OP, 2 updates). The 7 in-scope `PartialUpdateTest` invocations have never been verified
flag-on (per OUTCOME.md they were halted at TIER-3 before iter-11 work and never re-run). The current symptom (original
value returned, no operand applied) is consistent with: operand merges not reaching the right key OR not being parsed by
the read fold.

## Likely failure modes (to investigate in iter 2)

1. **Operand never merged to disk.** The leader's `produceUpdateOperandToVT` produces UPDATE on VT, but the follower's
   case UPDATE in `StoreIngestionTask` may have a precondition that fails.
2. **Operand merged at wrong key.** `produceUpdateOperandToVT` wraps the key with
   `KEY_WITH_CHUNKING_SUFFIX_SERIALIZER.serializeNonChunkedKey(rawKeyBytes)` only when `isChunked()` is true. If the
   read path also wraps the key, they should match. But the batch-push key wrap is done by the legacy path inside
   `VeniceWriter.put` via the chunking-aware path; need to verify both paths use the same wrap.
3. **Operand merged but lost during read fold for chunked stores.** For chunked-base + operand path, the read returns
   the manifest bytes (negative schemaId) and bypasses the materialize fold. The chunking adapter then reassembles only
   the manifest's chunks, ignoring any appended operand bytes.

## Files modified

None. Diagnostic-only iteration.

## Next steps

Iter-2: Add minimal targeted diagnostic logging (or use existing, gated by toggle) to determine which of the three modes
above is the actual bug. Most likely candidate: chunking-related read-path bypass (mode 3) for the chunk-keys test, but
does not explain the non-chunked `testPartialUpdateOnBatchPushedKeys[*]` failures (where individual records are small
and shouldn't trigger chunking).
