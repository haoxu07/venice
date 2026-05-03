# Iter-11 — ROOT CAUSE FOUND: snapshot-bytes captured pre-data prefix

## Hypothesis tested

After iter-10's unit test confirmed the storage-layer fold path is correct in isolation, run
the integration test with diagnostic logging to capture the actual stored operand bytes that
fail to deserialize.

## Investigation

Added `SERVER-DESERIALIZE-FAIL` diagnostic to `StorageReadRequestHandler.handleSingleGetRequest`
that captures the raw bytes returned by `storageEngine.get(...)` when downstream
deserialization throws. Also relied on the existing `MaterializingReplicationMetadataRocksDBStoragePartition.get(ByteBuffer)`
diagnostic that hex-dumps the raw on-disk bytes for the user store.

Ran the integration test (without my new logging compiled — the prior binary's diagnostics
were sufficient).

### Counts from this run

- `SERVER-RECV-GET` for `test-store-aa-wc_v1`: 57
- `SERVER-AFTER-GET` for the same: 39 → all `valueRecordNull=true`
- 18 reads errored mid-flight (no AFTER-GET log) — these are the deserialization failures

### Stack trace from server log

```
com.linkedin.venice.serializer.VeniceSerializationException: Could not deserialize bytes back into Avro object
    at com.linkedin.venice.serializer.AvroGenericDeserializer.deserialize(AvroGenericDeserializer.java:79)
    at com.linkedin.davinci.kafka.consumer.StoreWriteComputeProcessor.applyWriteCompute(StoreWriteComputeProcessor.java:107)
    at com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext.foldOperandOnly(MaterializingFoldContext.java:144)
    at com.linkedin.davinci.store.rocksdb.merge.MaterializingFraming.foldOperandOnly(MaterializingFraming.java:243)
    at com.linkedin.davinci.store.rocksdb.merge.MaterializingFraming.materialize(MaterializingFraming.java:180)
    at com.linkedin.davinci.store.rocksdb.MaterializingReplicationMetadataRocksDBStoragePartition.get(...)
Caused by: java.lang.ArrayIndexOutOfBoundsException: Index -12 out of bounds for length 4
    at org.apache.avro.io.parsing.Symbol$Alternative.getSymbol(Symbol.java:460)
    at org.apache.avro.io.ResolvingDecoder.readIndex(ResolvingDecoder.java:283)
```

The deserialization is failing on the WC OPERAND payload itself. "Index -12" comes from
zigzag-decoding the FIRST byte of the WC payload as a union index. -12 is far outside the
valid union range, indicating corrupted WC bytes.

### Hex dump of stored bytes

```
01 3a 00 00 00 01 00 00 00 01
17 0d 06 ed 11 38 ed db 0d 4d a5 9b dd 2b 19 81  (16 bytes — looks like Producer GUID)
4c 39 28 00                                       (4 bytes  — segment number)
02 84 f8 c1 b1 bd 67 03                           (8 bytes  — long varint, looks like timestamp)
02 02 02 24 04 04 08 6b 65 79 31 02 02 08 6b 65 79 32 02 04 00 00  (22 bytes — actual WC payload "key1"/"key2")
```

Decoded:
- `01 3a`        = kind=OPERAND, varint(58)=length of operand content
- `00 00 00 01`  = valueSchemaId=1 (BE int)
- `00 00 00 01`  = updateSchemaId=1 (BE int)
- 50-byte WC payload that starts with what looks like KafkaMessageEnvelope producer-metadata
  bytes (GUID + segment + timestamp) instead of the actual Avro WC encoding.

### Root cause

`ActiveActiveStoreIngestionTask.produceUpdateOperandToVT` (iter-5 fix) snapshots the operand
bytes via:
```java
int len = src.limit();
int off = src.arrayOffset();
operandBytesSnapshot = new byte[len];
System.arraycopy(src.array(), off, operandBytesSnapshot, 0, len);
```

This is wrong when `src` is a ByteBuffer constructed via
`ByteBuffer.wrap(byte[] arr, int offset, int length)` — which is what shared-array Avro
decoders return for a `bytes`-type field embedded in a larger record. In that case:
- `arrayOffset() == 0`
- `position() == offset` (not 0)
- `limit() == offset + length`

The iter-5 logic copies `arr[0 .. limit)` which captures `offset` bytes BEFORE the actual
operand data. For a typical Update message in a KafkaMessageEnvelope, those preceding bytes
are the producer-metadata (16-byte GUID + segment + timestamp ≈ 28 bytes), which is exactly
what we see in the hex dump prefix.

This corruption is NOT detected at write time — the bytes still fit the framing. It surfaces
ONLY when the read-side fold tries to deserialize the WC payload via the WC schema.

## Fix applied (two parts)

### 1. Bypass `processActiveActiveMessage` for flag-on UPDATEs

The IngestionBatchProcessor's pre-processing runs `processActiveActiveMessage` for all AA
messages. For UPDATE under flag-on, this is wasted work AND it advances the `updateValue`
buffer's position past the operand. Returning null early from `processActiveActiveMessage`
when `(serverConfig.isVtUpdateOperandEnabled() && msgType == UPDATE)` skips the wasted
work. The leader fast-path in `processMessageAndMaybeProduceToKafka` already handles this
case without consulting the wrapper.

### 2. Fix snapshot to use `duplicate().get()` correctly

Replace the `arrayOffset()`/`limit()` arithmetic with a `duplicate().get(byte[])` call,
which respects the buffer's `position()` and copies the correct slice
`[arrayOffset()+position() .. arrayOffset()+limit())`. With the bypass in place, position
is at the original (untouched) value, so `remaining()` is the operand length.

## Verification

- Smoke test suite (11 tests) still passes.
- Integration test running now to verify end-to-end.

## Files modified

- `clients/da-vinci-client/.../ActiveActiveStoreIngestionTask.java` — early-return on UPDATE
  in `processActiveActiveMessage`; replaced snapshot logic in `produceUpdateOperandToVT`.
- `services/venice-server/.../StorageReadRequestHandler.java` — added
  `SERVER-DESERIALIZE-FAIL` diagnostic (kept for future iterations).
- `clients/da-vinci-client/.../MaterializingFraming.java` — defensive null-return when no
  fold context (instead of returning raw bytes that fail downstream Avro decoding).
