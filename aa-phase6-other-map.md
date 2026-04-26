# Phase 6 — Mapping the AA leader OTHER bucket

Phase 5 outcome: at WC=off + RMD-cache-on + 100k keys + multi-producer, the
leader_record_wall is ~3,167 ns/record and 67.80 % of that wall is in an
"OTHER" bucket. We need to add 5+ named sub-stages on the leader thread
to bring named coverage above 80 %.

## Hot-loop call sites surveyed

Source: `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java`
function `processActiveActiveMessageInternal`, lines 546-811.

Existing leader-on-wall named brackets: `RT_DESERIALIZE`, `RMD_LOOKUP_TOTAL`,
`DCR_MERGE`, `VALUE_SERIALIZE`, `VALUE_CHUNK`, `TRANSIENT_MAP_PUT`. The gap is
the OTHER bucket.

## New sub-stages chosen (≥5)

Each is wired with a `start = System.nanoTime() / record(stage, delta)` pair
gated by `AaLeaderBottleneckReporter.LEADER_OTHER_ENABLED && AaLeaderBottleneckReporter.ENABLED`.

| New Stage | File:Lines (old) | Rationale |
|---|---|---|
| `LO_LAZY_OLDVALUE_INIT` | ActiveActiveStoreIngestionTask.java:590-602 | Allocates `ChunkedValueManifestContainer`, builds the `Lazy<ByteBufferValueRecord<ByteBuffer>>` and (on the field-level merge path) materialises it via `oldValueProvider.get()`. Lazy supplier setup at 4 M records/sec is suspected to be a non-trivial slice (allocation + lambda capture). |
| `LO_RMD_CACHE_DECIDE` | ActiveActiveStoreIngestionTask.java:612-637 | Hashes the key (`KeyHasher.hash`), enters `RmdTimestampCacheManager.getOrCreate(partition)` then `RmdTimestampCache.decideAndUpdate(...)`. Phase 5 reported cache hit ~99.9 %, but the entry overhead applies to every PUT, so its share of the wall in steady state is the dominant suspect. |
| `LO_WRITE_TIMESTAMP` | ActiveActiveStoreIngestionTask.java:604 | `getWriteTimestampFromKME(kafkaValue)` — dispatch on KME `producerMetadata.logicalTimestamp >= 0`. Tiny per-call but at 4 M/s adds up. |
| `LO_PUT_DISPATCH` | ActiveActiveStoreIngestionTask.java:661-668 | The body between `aggVersionedIngestionStats.recordTotalDCR(...)` (sensor call) and `beforeDCRTimestampInNs = System.nanoTime()` — sensor record overhead + LongAdder-style record count emission for `total_dcr`. Captures the tehuti sensor cost from `recordTotalDCR`. |
| `LO_RMD_CACHE_REMEMBER` | ActiveActiveStoreIngestionTask.java:738-740 | `rmdTimestampCacheManager.getOrCreate(partition).rememberAfterFallback(...)` — fallback path remember after lookup. Only fires on non-skipped path. |
| `LO_RESULT_WRAPPER_ALLOC` | ActiveActiveStoreIngestionTask.java:800-810 | `MergeConflictResultWrapper`/`PubSubMessageProcessedResult` allocation + setting fields + lambda capture for `storeDeserializerCache`. |
| `LO_SENSOR_CALLS` | ActiveActiveStoreIngestionTask.java:743-746 | `aggVersionedIngestionStats.recordTotalDuplicateKeyUpdate(...)` — second tehuti sensor call on each PUT. (Tehuti's `Sensor.record()` is the leading suspect per the prompt's Acknowledged Design Notes.) |

That gives 7 new on-leader-wall sub-stages with `offLeaderWall=false`.

## Off-leader sub-stages for VT producer ack (Part B.2)

Add a sibling enum entry on the same reporter, marked `offLeaderWall=true`:

| New Stage | File:Lines | Rationale |
|---|---|---|
| `VT_CALLBACK_ENTRY_TO_DRAINER` | LeaderProducerCallback.java:81-181 | Time from callback ENTRY (`onCompletion` first line) to right after `produceToStoreBufferService(...)` returns. This is callback body up to drainer enqueue — the dispatch+queue work that runs on the producer IO thread. |
| `VT_CALLBACK_TOTAL_BODY` | LeaderProducerCallback.java:81-256 | Whole callback body wall time. Difference (`VT_PRODUCE_ACK_WAIT - VT_CALLBACK_TOTAL_BODY`) is the broker-roundtrip portion. |

`VT_CALLBACK_ENTRY_TO_DRAINER` plus the whole-body bracket lets the report
distinguish "broker took time" vs "callback dispatch / drainer enqueue took
time". Both off-leader-wall.

## Consumer poll decomposition (Part B.1)

In `ConsumptionTask.run()` around line 138-167, extend the existing
`RT_POLL_BLOCK_NS` measurement with two on-poll-thread sub-stages:

| New Stage | Rationale |
|---|---|
| `LO_POLL_WAIT_EMPTY_NS` | Total nanos accumulated from polls that returned 0 records. |
| `LO_POLL_FETCH_NONEMPTY_NS` | Total nanos accumulated from polls that returned at least 1 record. |

Both are gated by the new `LEADER_OTHER_ENABLED` flag (poll loop only).

## JMX scrape (`AaKafkaPipelineReporter`)

A new daemon-scheduled class will scrape `kafka.producer:type=producer-metrics,*`
and `kafka.consumer:type=consumer-fetch-manager-metrics,*` MBeans every 20 s
and print:

```
[KAFKA-PIPELINE] producer client_id=<id> record_send_rate=... request_latency_avg=... batch_size_avg=... records_per_request_avg=... record_queue_time_avg=...
[KAFKA-PIPELINE] consumer client_id=<id> records_consumed_rate=... fetch_latency_avg=... fetch_size_avg=... records_per_request_avg=...
```

Gated by `venice.server.aa.kafka.pipeline.instrumentation.enabled`.

## Flag separation

| Flag | Default | Effect |
|---|---|---|
| `venice.server.aa.bottleneck.instrumentation.enabled` | off | Enables Phase 1/2/3/5 counters (already present). |
| `venice.server.aa.dcr.merge.instrumentation.enabled` | off | Phase 4 `dcr_merge` decomposition (present). |
| `venice.server.aa.rmd.timestamp.cache.enabled` | off | Phase 5 RMD cache (present). |
| **`venice.server.aa.leader.other.instrumentation.enabled`** | **off** | **NEW** — Phase 6 leader OTHER decomposition + VT callback split + poll empty/fetch split. |
| **`venice.server.aa.kafka.pipeline.instrumentation.enabled`** | **off** | **NEW** — Phase 6 JMX scraping reporter. |

Cost: each new bracket is one `nanoTime()` pair + one `LongAdder.add()`.
At 7 brackets × 4 M records/s ≈ 28 M nanoTime pairs/s. Mirrors Phase 4's
overhead profile (~5–12 % delta), so we keep the entire Phase 6 set behind
the dedicated `leader.other` flag so the OFF-run can disable it cleanly.
