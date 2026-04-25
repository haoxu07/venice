# AA Leader PUT Hot Loop — End-to-End Map

Commit under investigation: `bab13cc39` (HEAD, branch `haoxu07/aa-ingestion-benchmark`).
Scope: one PUT record traveling through the Active-Active leader on the
`ActiveActiveIngestionBenchmark` (PUT workload, 10k-key pool, 2 regions,
2 partitions, SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED=true).
All file paths are absolute.

---

## 1. Consumer poll loop (RT topic)

| Stage | File | Lines |
|---|---|---|
| Outer poll loop | `/home/coder/Projects/venice/clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ConsumptionTask.java` | 110-160 |
| `pollFunction.get()` blocking poll | `/home/coder/Projects/venice/clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ConsumptionTask.java` | 129 |
| Polled messages -> receiver | `ConsumptionTask.java`, via `processPollResults` handing off into `StorePartitionDataReceiver.write` | `StorePartitionDataReceiver.java:41-79` |

`rt_poll_wait` stage timer bracket: a per-thread `nanoTime()` diff around
line 129 of `ConsumptionTask.java`. This is per *poll*, so we will divide
by polled record count when computing per-record averages.

---

## 2. Record delivery to ingestion task

| Stage | File | Lines |
|---|---|---|
| `StorePartitionDataReceiver.write(List<...>)` | `.../StorePartitionDataReceiver.java` | 41-79 |
| `storeIngestionTask.produceToStoreBufferServiceOrKafka(consumedData, ...)` | `.../StorePartitionDataReceiver.java` | 75 |
| dup-filter + batch path decision | `StoreIngestionTask.java` | 1433-1461 |

---

## 3. Per-record processing on the leader (AA/WC parallel-batch path)

The benchmark sets `SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED=true`,
so the batch path in `produceToStoreBufferServiceOrKafkaInBatch` is taken.

| Stage | File | Lines |
|---|---|---|
| Split RT records into batches of size `AAWCWorkloadParallelProcessingThreadPoolSize` | `StoreIngestionTask.java` | 1508-1542 |
| `keyLockMap = ingestionBatchProcessor.lockKeys(batch)` — KeyLevelLocksManager acquire | `StoreIngestionTask.java` | 1554; `IngestionBatchProcessor.java:84-102` |
| `ingestionBatchProcessor.process(batch, ...)` — pool hand-off | `StoreIngestionTask.java` | 1557-1564; `IngestionBatchProcessor.java:122-197` |
| `CompletableFuture.runAsync(..., batchProcessingThreadPool)` | `IngestionBatchProcessor.java` | 166-180 |
| `processingFunction.apply(...)` -> `ActiveActiveStoreIngestionTask::processActiveActiveMessage` | `ActiveActiveStoreIngestionTask.java` | 486-715 |
| `CompletableFuture.allOf(...).get()` — barrier wait | `IngestionBatchProcessor.java` | 182 |
| Post-batch `handleSingleMessage` calls (produces to VT via `produceToLocalKafka`) | `StoreIngestionTask.java` | 1574-1603; `StoreIngestionTask.java:1349-1422` |

Stage timer brackets (per record):

| Stage | Bracket |
|---|---|
| `key_lock_wait` | around `lockKeys(batch)` — total divided by batch size to attribute per-record |
| `aa_wc_pool_handoff` | wall time from `CompletableFuture.runAsync` submission to "first nanosecond of exec" inside `processingFunction.apply`. Implement by passing submission-timestamp into a ThreadLocal captured at method entry. |

---

## 4. processActiveActiveMessage — the PUT DCR core

All in `.../ActiveActiveStoreIngestionTask.java` lines 486-715.

| Stage | File:Lines | Notes |
|---|---|---|
| Key/value fetched from record; KME deserialization already done by consumer | 494-520 | `rt_deserialize` actually happens inside the consumer adapter BEFORE we reach here; timing site for `rt_deserialize` is inside `ApacheKafkaConsumerAdapter` (key+value deserializers). We'll approximate by timing the `poll -> batch dispatch` gap minus the message-count-proportional lock+process overhead, OR by a dedicated timer around the first use of `kafkaKey.getKey()` / `((Put)kafkaValue.payloadUnion).schemaId` (both hit Avro-decoded fields). Choosing the latter for precision. |
| `getValueBytesForKey` — old-value lookup (NOT RMD) | 522-528, 843-888 | Transient-map check OR RocksDB value-bytes read (`RawBytesChunkingAdapter.getWithSchemaId`). Per study scope the "value" side is mostly transient-cache hit; we attribute any miss time under this branch. |
| Key hash + RmdTimestampCache decide (disabled in this study) | 543-568 | `rmdTimestampCacheManager` is null when cache flag is off, so this entire branch is skipped and the code falls through to `getReplicationMetadataAndSchemaId`. |
| `rmd_lookup_total` — wrapper | 574-583 | `getReplicationMetadataAndSchemaId(partitionConsumptionState, keyBytes, partition, ts)` at line 578. |
| `rmd_lookup_transient` — cache check only | 420-430 | `partitionConsumptionState.getTransientRecord(key)` — fast map lookup. |
| `rmd_lookup_rocksdb` — RocksDB RMD read | 432-433, 453-479 | `getRmdWithValueSchemaByteBufferFromStorage` -> `databaseLookupWithConcurrencyLimit` -> `SingleGetChunkingAdapter.getReplicationMetadata`. |
| `rmd_deserialize` — bytes -> Avro RMD record | 440-442 | `rmdSerDe.deserializeValueSchemaIdPrependedRmdBytes(...)`. |
| `dcr_merge` — MergeConflictResolver.put | 592-607 | `mergeConflictResolver.put(oldValueByteBufferProvider, rmdWithValueSchemaID, putValue, writeTimestamp, schemaId, clusterId)`. For PUT path the existing Tehuti sensor at lines 604-606 already measures this (`putMergeLatency`) — we add a hot-path LongAdder counter to avoid double-counting. |
| `value_serialize` — merged value -> bytes | 665-668, 684-685 | `maybeCompressData(...)` and `rmdSerDe.serializeRmdRecord(...)`. |
| `transient_map_put` — `setTransientRecord` | 691-702 | `partitionConsumptionState.setTransientRecord(...)`. |

Chunking (`value_chunk`) is not exercised by this benchmark — the value
size is below the chunk threshold. We instrument the counter anyway so
non-zero values confirm that; we expect 0 calls/sec.

---

## 5. producePutOrDeleteToKafka — VT produce

All in `.../ActiveActiveStoreIngestionTask.java` lines 917-992, and
`LeaderFollowerStoreIngestionTask.java:produceToLocalKafka` lines
2038-2077.

| Stage | File:Lines | Notes |
|---|---|---|
| Build `Put updatedPut`, `BiConsumer produceToTopicFunction` | `ActiveActiveStoreIngestionTask.java` | 966-981 |
| `produceToLocalKafka(...)` | `ActiveActiveStoreIngestionTask.java:982-990` -> `LeaderFollowerStoreIngestionTask.java:2038-2077` | |
| `createProducerCallback` | `LeaderFollowerStoreIngestionTask.java` | 2047-2053 |
| **`vt_produce_send`** — `produceFunction.accept(callback, leaderMetadataWrapper)` which calls `veniceWriter.put(...)` (sync portion of KafkaProducer.send) | `LeaderFollowerStoreIngestionTask.java:2058-2060` (`beforeProduceTimestampNS`, `recordLeaderProduceLatency`) matches this bracket. The actual producer send is in `ActiveActiveStoreIngestionTask.getProduceToTopicFunction` body at line 1475-1486. | |

Notes:
- `vt_produce_ack_wait` is NOT on the leader's hot consume thread — the
  callback `LeaderProducerCallback.onCompletion` runs on the producer's
  callback thread AFTER the broker ACKs. The leader thread moves on to
  the next record as soon as `produceFunction.accept` returns. So
  `vt_produce_ack_wait` is accumulated *per-record but on a different
  thread*; its `%-of-wall` must be interpreted against "callback thread
  wall", not leader wall. We measure it but flag it explicitly.

---

## 6. Drainer-side work (triggered via producer callback)

In `LeaderProducerCallback.onCompletion` at `.../LeaderProducerCallback.java:80-249`,
the callback runs on the Kafka producer's IO thread once the broker ACKs the VT
message. It then calls `ingestionTask.produceToStoreBufferService(...)` which
enqueues into `StoreBufferService`, where the drainer thread picks up the record
and runs `internalProcessConsumerRecord` -> `processKafkaDataMessage` to actually
write to RocksDB and remove the transient record.

| Stage | File:Lines | Notes |
|---|---|---|
| **`drainer_enqueue`** — `ingestionTask.produceToStoreBufferService` | `LeaderProducerCallback.java:166-172`; `StoreIngestionTask.java:1318-1342` | Timer bracket around `storeBufferService.putConsumerRecord(...)` |
| Drainer polls & dispatches | `StoreBufferService.java` (see `drainBufferedRecordsFromTopicPartition`) | Not per-record instrumented (it's a pull) |
| `internalProcessConsumerRecord` -> `processKafkaDataMessage` | `StoreIngestionTask.java:4035, 4620` | |
| **`rocksdb_value_write`** — `prependHeaderAndWriteToStorageEngine` / `storageEngine.put(...)` | `StoreIngestionTask.java:4387, 4447, 4769-4776` | |
| **`transient_map_remove`** — `partitionConsumptionState.mayRemoveTransientRecord` | `StoreIngestionTask.java:4881-4888` | |

---

## 7. Reference wall counter

`leader_record_wall_ns` is defined as the full time from "leader starts
processing record R" to "leader finishes its last per-record synchronous
work for R (either returns from `produceFunction.accept`, or returns
from `handleSingleMessage` if the processed result was ignored)".

Implementation: a `long start = System.nanoTime()` at the top of
`handleSingleMessage` in `StoreIngestionTask.java:1349`, and a
`LEADER_RECORD_WALL.add(System.nanoTime() - start)` at every return
path (success / skipped / ignored). One `LongAdder`.

Drainer-side and producer-callback-side stages (`drainer_enqueue`,
`rocksdb_value_write`, `transient_map_remove`, `vt_produce_ack_wait`)
are NOT part of `leader_record_wall_ns`. They run on different threads.
We account for them separately and report them as "off-thread" stages,
because for a steady-state throughput study the relevant quantity is
"how much leader-thread time does each record consume" — anything on
other threads is already parallelized and only bottlenecks if its
total rate exceeds the leader's output rate.

For criterion 3's "≥85% of wall" check, we sum only stages whose
timers run on the leader consumer thread. The "OTHER" bucket captures
the un-attributed leader wall time.

---

## 8. Instrumentation hook points (summary)

| Stage | Hook class::method(line) | Bracket |
|---|---|---|
| `leader_record_wall_ns` | `StoreIngestionTask.handleSingleMessage` (1349-1422) | top / every return |
| `rt_poll_wait` | `ConsumptionTask.run` around `pollFunction.get()` (129) | bracket that single call, divide by batch size |
| `rt_deserialize` | `ActiveActiveStoreIngestionTask.processActiveActiveMessage` (494-520) | around `kafkaValue.messageType` / first `payloadUnion` access — primarily measures Avro-decoded field access which is a proxy |
| `rmd_lookup_total` | `ActiveActiveStoreIngestionTask.processActiveActiveMessage` (574-583) | around call to `getReplicationMetadataAndSchemaId` |
| `rmd_lookup_transient` | `ActiveActiveStoreIngestionTask.getReplicationMetadataAndSchemaId` (420-430) | around `getTransientRecord(key)` call |
| `rmd_lookup_rocksdb` | `ActiveActiveStoreIngestionTask.getRmdWithValueSchemaByteBufferFromStorage` (458-460) | around `databaseLookupWithConcurrencyLimit(...)` |
| `rmd_deserialize` | `ActiveActiveStoreIngestionTask.getReplicationMetadataAndSchemaId` (440-442) | around `rmdSerDe.deserializeValueSchemaIdPrependedRmdBytes` |
| `dcr_merge` | `ActiveActiveStoreIngestionTask.processActiveActiveMessage` (591-607) | between `beforeDCRTimestampInNs` and `putMergeLatency` — PUT branch only |
| `aa_wc_pool_handoff` | `IngestionBatchProcessor.process` submission (167) -> record-start inside lambda (169) | submit ts captured in local var, read at first ns of lambda execution |
| `key_lock_wait` | `IngestionBatchProcessor.lockKeys` (84-102) | around method body; attribute total / batch size to each record |
| `value_serialize` | `ActiveActiveStoreIngestionTask.processActiveActiveMessage` (684-685) | around `rmdSerDe.serializeRmdRecord` + `maybeCompressData` |
| `value_chunk` | VeniceWriter chunking path (invoked through `get().put`) | expected 0 for this benchmark |
| `vt_produce_send` | `LeaderFollowerStoreIngestionTask.produceToLocalKafka` (2058-2060) | around `produceFunction.accept(...)` (already has a Tehuti sensor but we add a cheap adder) |
| `vt_produce_ack_wait` | `LeaderProducerCallback.onCompletion` (80-123) | around entry to the callback — measured on producer IO thread, off-wall |
| `transient_map_put` | `ActiveActiveStoreIngestionTask.processActiveActiveMessage` (691-702) | around `setTransientRecord` |
| `drainer_enqueue` | `StoreIngestionTask.produceToStoreBufferService` (1318-1342) | around `storeBufferService.putConsumerRecord(...)` |
| `rocksdb_value_write` | `StoreIngestionTask.processKafkaDataMessage` (4768-4788) | around `prependHeaderAndWriteToStorageEngine(...)` — drainer thread |
| `transient_map_remove` | `StoreIngestionTask.processKafkaDataMessage` (4881-4888) | around `mayRemoveTransientRecord(...)` — drainer thread |

---

## 9. Gating

A single static final boolean read from
`Boolean.getBoolean("venice.server.aa.bottleneck.instrumentation.enabled")`
captured once at class-init in a new
`clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaLeaderBottleneckReporter.java`
class. All instrumentation sites do an early `if (!AaLeaderBottleneckReporter.ENABLED) return;`
before touching any counter, so the cost of the flag-off path is a
single sentinel-field read.

## 10. Per-record sampling

Given the 170k records/s rate and 16 stages, we will sample 1-in-N
records where N starts at 1 (i.e. no sampling). If criterion 6 fails
we move to N=8 or N=16 at the `leader_record_wall_ns` level and
proportionally scale totals. The sampling decision lives inside the
reporter; sites just call `reporter.record(stage, nanos)` which may
early-return on non-sampled records.
