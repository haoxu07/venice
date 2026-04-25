# AA Phase 2 — Producer / Poll-decomposition / Dispatcher Hot-loop Map

Branch: `haoxu07/aa-ingestion-benchmark`. HEAD: `467d3c60f`.
Scope: extend Phase 1 instrumentation to disambiguate the 60% leader-idle
finding (hypothesis A: RT-topic starvation, B: consumer/dispatcher slow,
C: hybrid). Producer-side timers go in production code, NOT the JMH file.

All file paths are absolute.

---

## 1. Producer-side hot loop (benchmark thread → KafkaProducer.send)

The benchmark calls (via `IntegrationTestPushUtils.sendStreamingRecord{,WithoutFlush}`):

```
producer.send(storeName, OutgoingMessageEnvelope) -> VeniceSystemProducer.send(String, OutgoingMessageEnvelope)
                                                      |
                                                      +-> VeniceSystemProducer.send(Object key, Object value)
                                                            |
                                                            +-> getInternalWriter().put(...)  // VeniceWriter.put -> KafkaProducer.send
```

| Stage | File | Lines |
|---|---|---|
| `producer.send(String, envelope)` | `integrations/venice-samza/src/main/java/com/linkedin/venice/samza/VeniceSystemProducer.java` | 583-632 |
| `send(Object, Object)` (sync portion of producer pipeline) | same file | 649-726 |
| `getInternalWriter().put(...)` | same file | 705-710 |

Instrumentation bracket for `rt_producer_send_call`: wrap the entry-to-exit
of `send(Object, Object)`. Captures sync portion only (not the
`CompletableFuture` ack). This is the boundary between the benchmark
thread and Venice's producer pipeline, exactly what the prompt asks for.

`rt_producer_buffer_full`: count of `send(Object, Object)` invocations whose
duration exceeds 1 ms. Justification: under a steady accumulator, the sync
portion of `KafkaProducer.send()` returns in ~10 microseconds; values >1 ms
indicate the producer is blocking on RecordAccumulator buffer-pool space or
metadata refresh.

`rt_producer_records_per_sec`: derived in the reporter as
`(call_count_delta / 20s)`.

These are wired in a new sibling reporter
`clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaProducerBottleneckReporter.java`
(separate from `AaLeaderBottleneckReporter` because the producer flag may
need to ship in the samza-integration package, but the test code already
runs in the same JVM as the server, so we can put both reporters in
`davinci-client.stats` and have `venice-samza` call into it via reflection
or direct dependency).

To avoid pulling a new dependency from `venice-samza` to `da-vinci-client`,
we use a `Class.forName(...)` reflective call from `VeniceSystemProducer`
to look up the reporter at static-init time — exactly what
`TransientRecordCacheDiagnosticReporter` does. Falls back to a no-op on
ClassNotFound.

Actually, simpler: the reporter lives in `venice-common` which is already
a dependency of both modules. Looking at the existing
`AaLeaderBottleneckReporter`, it lives in `da-vinci-client`. Cleanest fix:
create a NEW reporter in `internal/venice-common/src/main/java/com/linkedin/venice/stats/AaProducerBottleneckReporter.java`
because `venice-common` is already a dep of `venice-samza`. Verified by
inspection of `integrations/venice-samza/build.gradle`.

After audit: `venice-samza` already depends transitively on
`da-vinci-client` through `venice-test-common` in benchmark builds, but
NOT in the standalone samza package. To stay safe, the new reporter goes
in `internal/venice-common/src/main/java/com/linkedin/venice/stats/`.

---

## 2. Consumer-poll decomposition

Existing `rt_poll_wait` timer is at `ConsumptionTask.java:130-139`. Replace
it with the four required stages (keeping `rt_poll_wait` as an alias for
backward compatibility, mapped to `rt_poll_block_ns`).

| Stage | Capture point |
|---|---|
| `rt_poll_block_ns` | wall time of `pollFunction.get()` call (= old `rt_poll_wait`) |
| `rt_poll_records_returned` | sum of message counts across all topic partitions in the poll result |
| `rt_poll_empty_count` | increment if `polledMessages.isEmpty()` is true OR all partitions report 0 records |
| `rt_poll_full_count` | increment if total record count across all partitions ≥ 0.9 × max.poll.records (default 100, so threshold = 90) |

Implementation: post-poll, compute total record count by iterating the map
values' `.size()`. This is O(num topic partitions) which is small (~2 in
the canonical run), so cost is trivial.

These are added to `AaLeaderBottleneckReporter.Stage`:
- `RT_POLL_BLOCK_NS` (off-leader-wall)
- `RT_POLL_RECORDS_RETURNED` (off-leader-wall, no nanos — count only)
- `RT_POLL_EMPTY_COUNT` (off-leader-wall, count only)
- `RT_POLL_FULL_COUNT` (off-leader-wall, count only)

For the count-only stages, we abuse the existing nanos field: `record(stage, 1)`
on each event, and read `count` (which equals nanos in this case) at report time.
Actually cleaner: add a separate `recordCount(stage)` helper that increments
only the count adder; nanos stays 0. Reporter tolerates count-only stages.

---

## 3. Dispatcher submit-rate

`IngestionBatchProcessor.process` already has `aa_wc_pool_handoff` timing
at line 178-186. Right next to it, increment a count adder.

Stage: `AA_POOL_SUBMIT_COUNT` — count-only, off-leader-wall.

The reporter prints `aa_pool_submit_count` per tick; per-second rate is
derived from the 20s tick interval.

---

## 4. Decision matrix application

After the run completes, parse the median-tick numbers from
`/tmp/aa-phase2-on.log` and apply:

```
records_per_poll       = rt_poll_records_returned.calls / rt_poll_block_ns.calls
polls_per_sec          = rt_poll_block_ns.calls / 20
producer_records_per_sec = rt_producer_send_call.calls / 20
max_poll_records       = 100  (Venice server default SERVER_KAFKA_MAX_POLL_RECORDS)

A: avg(rt_poll_block_ns) > 0.7 * 24ms = 16.8ms
   AND records_per_poll < 50
   AND producer_records_per_sec >> records_per_poll * polls_per_sec  (i.e. producer faster than consumer drain)

B: avg(rt_poll_block_ns) < 5ms
   AND records_per_poll >= 100  (full polls common)
   AND aa_pool_submit_count > 0.9 * pool_capacity_per_sec

Else => C (hybrid, with quantitative rationale)
```

Pool capacity is `SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_THREAD_POOL_SIZE × (1s / per-task-time)`. From Phase 1, per-record leader wall = ~55 microseconds, pool size default 8, so capacity ≈ 8 / 55e-6 = 145k records/s. Not directly compared in matrix; we use `records_per_poll` and `block_ns` as primary signals.
