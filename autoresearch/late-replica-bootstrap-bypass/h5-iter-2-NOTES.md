# h5-iter-2 — Phase 1 Sub-step 1c: BUG FOUND — partition-routing inconsistency

## Diagnostic evidence (from iter-1 run)

For key `0431320200` ("12" — Avro encoded):

| Path     | Server     | Partition | identityHash | deferredWrite | framingInProgress | Result                            |
| -------- | ---------- | --------- | ------------ | ------------- | ----------------- | --------------------------------- |
| Base PUT | dc-0:37723 | **1**     | 479659748    | true          | true              | OK                                |
| Base PUT | dc-0:35509 | **1**     | 1215693861   | false         | true              | OK                                |
| Base PUT | dc-1:43323 | **1**     | 914692499    | false         | true              | OK                                |
| Base PUT | dc-1:34073 | **1**     | 110371051    | true          | true              | OK                                |
| MERGE    | dc-0:35509 | **1**     | 1677229565   | -             | true              | readback=58 (good — base present) |
| MERGE    | dc-0:37723 | **0**     | 119182759    | -             | true              | readback=24 (BUG — no base!)      |
| MERGE    | dc-1:43323 | **1**     | 697348297    | -             | true              | readback=58 (good)                |
| MERGE    | dc-1:43323 | **0**     | 103582762    | -             | true              | readback=24 (BUG)                 |

**Pattern**: base PUT goes only to **VT partition 1** (hash(unwrapped key "12")). MERGE fires on BOTH partition 1 AND
partition 0. On partition 0 there is no base → operand-only readback.

Same pattern verified for key `0431370200` ("17"): base on partition=2, but merge ALSO fires on partition=0.

## Root cause

The chunking-enabled store wraps keys with a `ChunkedKeySuffix` before writing them to the topic. Both
`VeniceWriter.put` and `VeniceWriter.update` write the wrapped key to the topic. BUT they compute the destination
partition differently:

`VeniceWriter.put` (line 1001-1003): computes partition from **UNWRAPPED** `serializedKey`, then wraps it inside the put
body (line 1199-1200) before sending the KafkaKey to the topic.

`VeniceWriter.update` (line 1335-1337): computes partition from whatever `serializedKey` the caller passed, and does NOT
wrap.

In `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT` (line 987-990), the iter-11 fix wraps the key BEFORE
calling `veniceWriter.update`. This makes the on-disk key match `put`'s on-disk key (good — read path finds it). But it
ALSO causes `getPartition` inside `update` to hash the WRAPPED key, which lands on a different partition than `put`'s
UNWRAPPED-key hash.

So for chunking-enabled stores under flag-on:

- Base PUT lands on partition P1 = hash(unwrapped) % N
- Operand UPDATE lands on partition P2 = hash(wrapped) % N

For ~half the keys (probabilistically), P1 ≠ P2. The follower's RocksDB store for partition P2 receives the operand
merge but has no base for that key → operand-only readback → "operand only, no base" → read returns null → test sees old
(batch) value.

## Decision

**H5 is CONFIRMED but the mechanism is different from the GOAL's bootstrap-bypass framing.** The bypass is upstream of
the partition layer — specifically in `VeniceWriter.update`'s partition-routing path. The base PUT was never written to
the WRONG partition; it was just routed by a different key-hash.

Advance to Phase 2 — fix the partition routing inconsistency.

## Fix approach

Make `VeniceWriter.update` wrap the key INTERNALLY when `isChunkingEnabled`, mirroring `put`'s behavior:

1. Compute partition from UNWRAPPED key
2. Wrap key with chunking suffix before constructing the KafkaKey

Then revert the explicit wrapping in `produceUpdateOperandToVT` (since update will handle it).

Gated by flag-on: change only applies to flag-on stores via the existing `isChunkingEnabled` check on the VeniceWriter
(no change to flag-off behavior because that path doesn't use `produceUpdateOperandToVT`).
