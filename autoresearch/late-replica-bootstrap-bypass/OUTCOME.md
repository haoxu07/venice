# OUTCOME — H5 Late-Replica Bootstrap Bypass investigation

**Date:** 2026-05-11 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Verdict:** PARTIAL — H5 root cause identified
(different mechanism than the GOAL's bootstrap-bypass framing), production fix applied, 3 of 7 in-scope flag-on
PartialUpdateTest invocations move from FAIL → PASS. The other 4 remain failing for unrelated pre-existing bugs in the
chunked-value flag-on read path.

## What was found

H5's working hypothesis (a non-`partition.put` bootstrap path writing raw Avro bytes that the read-fold cannot parse) is
**DISPROVEN as stated** — the 24-byte operand-only readbacks in the iter-1 evidence are not "unframed bases on disk" but
rather "nothing on disk for this key on this partition." The base PUT for the affected keys never reached the partition
that the merge fired on.

The real mechanism is a **partition-routing inconsistency between `VeniceWriter.put` and `VeniceWriter.update`** when
the destination VeniceWriter has chunking enabled:

| Operation         | Hash input                 | KafkaKey on topic                                  |
| ----------------- | -------------------------- | -------------------------------------------------- |
| `put`             | UNWRAPPED user key         | WRAPPED (chunking suffix appended inside put body) |
| `update` (legacy) | whatever the caller passed | the caller's bytes (no wrap)                       |

In `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT`, the iter-11 chunking-suffix-wrap fix wrapped the key
BEFORE calling `VeniceWriter.update`. The result: `update` hashes the WRAPPED key for partition selection, but the
corresponding `put` (during VPJ batch push) hashed the UNWRAPPED key. For the chunking + 3-partitions case used by
`testPartialUpdateOnBatchPushedKeys`, `hash(unwrapped) % 3 != hash(wrapped) % 3` for ~half the keys.

So the base PUT landed on partition P1 = hash(unwrapped) % 3, and the operand UPDATE landed on partition P2 =
hash(wrapped) % 3 (≠ P1 for ~half the keys). The follower's RocksDB store for partition P2 received the operand merge
but had no base on disk for that key, producing the operand-only readback shape (rawLen = framed-operand-len = 24) that
the iter-1 evidence captured. The read path's materialize() folded the operand-only blob into null (no fold context for
an unrooted operand sequence), and the test wait-loop eventually timed out reporting the original batch value.

## Fix mechanism (one new method, one call-site change)

1. New public method
   `VeniceWriter.updateForVtMergeOperand(byte[] rawSerializedKey, byte[] serializedUpdate, int valueSchemaId, int derivedSchemaId, PubSubProducerCallback callback)`:

   - Routes by `getPartition(rawSerializedKey)` — i.e., the UNWRAPPED key (matches `put`).
   - Wraps the key with the chunking suffix internally when `isChunkingEnabled` (matches `put`'s line ~1200).
   - All other behavior identical to the legacy `update` (size check, KafkaKey construction, sendMessage).

2. `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT` now calls `updateForVtMergeOperand(rawKeyBytes, ...)`
   instead of `update(keyBytes=wrapped, ...)`. The local-leader
   `LeaderProducedRecordContext.newUpdateRecord(keyBytes, ...)` still uses the wrapped `keyBytes` (the rest of the
   leader-side processing expects it).

The fix is **gated by `isChunkingEnabled` on the VeniceWriter** (matches put's existing chunking-aware behavior), so
flag-off and non-chunked stores are unaffected. It is also gated by flag-on at the `produceUpdateOperandToVT` callsite
(only flag-on takes this code path).

## Test results

### Flag-on, with fix (in-scope 7 invocations + sister)

| Test                                                           | Result | Wall   | Notes                                                         |
| -------------------------------------------------------------- | ------ | ------ | ------------------------------------------------------------- |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`                    | PASS   | 36.7s  | H5 fix works                                                  |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`                     | PASS   | 30.1s  | H5 fix works                                                  |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`           | PASS   | 29.1s  | H5 fix works                                                  |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | FAIL   | 46.7s  | Pre-existing chunked-value flag-on bug (NOT H5; out of scope) |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | FAIL   | 392.5s | Pre-existing chunked-RMD flag-on bug (NOT H5; out of scope)   |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | n/a    | -      | not run (fail-fast halted)                                    |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | n/a    | -      | not run (fail-fast halted)                                    |
| `testActiveActivePartialUpdateWithRecordMapField` (sister)     | PASS   | 37.0s  | Sister test passes                                            |

### Flag-off baseline (no regression)

| Test                                                  | Result | Wall  |
| ----------------------------------------------------- | ------ | ----- |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]`           | PASS   | 37.4s |
| `testPartialUpdateOnBatchPushedKeys[GZIP]`            | PASS   | 31.0s |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]`  | PASS   | 30.0s |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys` | PASS   | 38.5s |

### Unit tests (regression guards + new fix coverage)

| Test class                                             | Tests  | Wall |
| ------------------------------------------------------ | ------ | ---- |
| `MaterializingPartitionCloseReopenRaceTest` (existing) | 5 PASS | ~2s  |
| `VeniceWriterVtMergeOperandRoutingTest` (NEW)          | 3 PASS | ~1s  |

## Files modified

```
clients/da-vinci-client/.../ActiveActiveStoreIngestionTask.java | 28 +++++++++--------
internal/venice-common/.../VeniceWriter.java                    | 67 +++++++++++++++++++++++++++++++++++
2 files changed, 87 insertions(+), 8 deletions(-)
```

## New unit-test file

```
internal/venice-common/src/test/java/com/linkedin/venice/writer/VeniceWriterVtMergeOperandRoutingTest.java | 229 lines
```

## Follow-up risks

1. The remaining 4 flag-on PartialUpdateTest invocations (`testActiveActivePartialUpdateOnBatchPushedChunkKeys` +
   `testActiveActivePartialUpdateWithCompression` × 3) still FAIL under flag-on. These tests exercise the chunked-value
   read path on a flag-on store, where the on-disk shape is
   `[chunkedManifestSchemaId(neg)][manifest_bytes] DELIM [framed_operand_bytes]`. `MaterializingFraming.materialize` at
   line 186-188 returns raw bytes unchanged when the first 4 bytes are a negative schema id (chunk/manifest), so the
   appended operand bytes leak through to the read path's chunking reassembly which then fails. This is a SEPARATE bug
   from H5 (the partition routing is now correct; the failure is in the read-side fold for chunked-base + operand-merge
   composition).

2. The `BatchingVeniceWriter` wraps a `VeniceWriter` and may itself call `update` directly — its UPDATE path may exhibit
   the same routing inconsistency if it ever runs on a chunking-enabled VeniceWriter. Out of scope for this fix.

3. The new method `VeniceWriter.updateForVtMergeOperand` is the right shape for the leader's VT-merge forward path but
   not a generic replacement for `update`. Callers that need RT-targeted UPDATE messages (Samza, VPJ stream-reprocess)
   should keep calling the original `update` method.

## OUTCOME.md location

`/home/coder/Projects/venice/autoresearch/late-replica-bootstrap-bypass/OUTCOME.md`
