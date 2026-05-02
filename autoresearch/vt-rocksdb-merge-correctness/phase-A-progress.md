# Phase A Progress — `ConcatBlobParser` + unit tests

## Phase goal recap

Per `GOAL.md` §4 Phase A: build a pure-Java parser that walks the on-disk concat blob produced by
RocksDB's `StringAppendOperator` and returns `(base: byte[], operands: List<byte[]>)`. Robust
against operand payload bytes containing the structural delimiter. Unit-tested.

Exit criterion: `./gradlew :clients:da-vinci-client:test --tests "*ConcatBlobParserTest*"` green,
and a randomized round-trip property test passes.

## Code changes

| Commit | File | Description |
|---|---|---|
| `c35b49492` | `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/ConcatBlobParser.java` | NEW — 200 LOC parser + framing helpers |
| `c35b49492` | `clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/merge/ConcatBlobParserTest.java` | NEW — 17 invocations (300 LOC) |

## Wire-format deviation from GOAL.md §3 — Tier 2 extension

§3 specs the materialized form as `[schemaId : int32 BE][kind=0x00 : 1B][avro-encoded value]` (no
length prefix on base) and the concat-blob form as
`[schemaId][0x00][avro-base][0x2C][0x01][len1][op1][0x2C]...`.

**Problem:** the spec says the parser uses kind bytes to find boundaries, but with no length on
the base, the only way to know where the base ends and the operand chain starts is to scan
forward for the delimiter byte. Avro-encoded payloads can contain arbitrary byte values, including
the delimiter byte (`0x2C` per the spec, or `0x01` per the value Phase 1 actually wired into
`RocksDBStoragePartition.getStoreOptions`). So scanning is unsafe.

**Fix (Tier 2 per §4.5):** add a varint payload-length to the materialized form too:
- Materialized: `[schemaId : 4B BE][0x00][len:varint][avro-base]`
- Operand: `[0x01][len:varint][avro-WC-payload]`
- StringAppendOperator delimiter (whatever its byte value): treated as opaque 1-byte structural
  padding the parser skips between successive chunks.

This is a minimal, local extension consistent with §3 intent ("varint length on operands gives a
deterministic skip past variable-content payloads" — same logic now applies to the base too) and
does not change any other Phase A/B contract. Phase B's framing path will produce this format
on `put` and the parser handles it uniformly.

## Test results

```
./gradlew :clients:da-vinci-client:test --tests "com.linkedin.davinci.store.rocksdb.merge.ConcatBlobParserTest"
BUILD SUCCESSFUL in 42s
```

17 invocations, all PASSED:

| Test | Result | Notes |
|---|---|---|
| `parseMaterializedOnlyReturnsBaseAndEmptyOperands` | PASS | shape 1 |
| `parseMaterializedOnlyHandlesZeroLengthBase` | PASS | empty avro base |
| `parseOperandOnlySingleReturnsNullBaseAndOneOperand` | PASS | shape 2 (single op) |
| `parseOperandOnlyChainReturnsAllOperandsInOrder[0](1)` | PASS | shape 2, delim=0x01 |
| `parseOperandOnlyChainReturnsAllOperandsInOrder[1](44)` | PASS | shape 2, delim=0x2C |
| `parseBaseAndOperandsReturnsAll[0](1)` | PASS | shape 3, delim=0x01 |
| `parseBaseAndOperandsReturnsAll[1](44)` | PASS | shape 3, delim=0x2C |
| `parseHandlesOperandPayloadsContainingDelimiterByte[0](1)` | PASS | adversarial, delim=0x01 |
| `parseHandlesOperandPayloadsContainingDelimiterByte[1](44)` | PASS | adversarial, delim=0x2C |
| `parseHandlesBasePayloadContainingKindBytes` | PASS | base contains 0x00 and 0x01 bytes |
| `roundTripPropertyTest` | PASS | 200 random trials |
| `roundTripPropertyTestOperandOnly` | PASS | 100 random trials |
| `parseRejectsNull` | PASS | defensive |
| `parseRejectsEmpty` | PASS | defensive |
| `parseRejectsTruncatedBaseLen` | PASS | malformed input |
| `parseRejectsUnknownKindByte` | PASS | unknown byte at offset 4 |
| `varintEncodeDecodeRoundTrip` | PASS | 0..MAX_VALUE |

## Failures encountered

None. First-pass implementation passed all tests.

## Gate evaluation

| Criterion | Status |
|---|---|
| `ConcatBlobParserTest` green | ✅ |
| Round-trip property test passes | ✅ |
| Adversarial input (operand contains delimiter) handled | ✅ |
| Base contains kind-byte values handled | ✅ |
| Malformed input rejected with clear errors | ✅ |

## Decision

**Continue to Phase B.** The parser is a well-tested pure-Java helper and its wire-format
contract is now nailed down. Phase B builds the storage-partition wrapper that uses it.

## Iteration log

| # | Hypothesis | Fix | Result |
|---|---|---|---|
| 1 | Initial implementation per the wire-format design | Wrote parser with kind-byte + varint-len structure; skip exactly one delim byte between chunks | All 17 tests PASS first try |
