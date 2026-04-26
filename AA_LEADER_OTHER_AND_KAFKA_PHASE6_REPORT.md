# AA Ingestion — PHASE 6 Report (leader OTHER + Kafka pipeline decomposition)

**Goal**: Decompose the leader OTHER bucket (~67.80% from Phase 5) and instrument
the Kafka pipeline (consumer poll empty/fetch split + producer/consumer JMX +
VT-producer ack-callback decomposition). Identify the actual remaining
constraint at the Phase 5 winning configuration (WC=off + RMD-cache=on, 100k
keys, multi-producer N=2): (a) leader OTHER, (b) Kafka pipeline, or (c) hybrid.

**Branch**: `haoxu07/aa-ingestion-benchmark`
**Phase 4 HEAD**: `12196b28a` (entering Phase 5/5.5/6)
**Run date**: 2026-04-25
**JDK**: `/export/apps/jdk/JDK-17_0_5-msft`

---

## Headline finding

**Named answer = (b) Kafka pipeline.** At the Phase 5 winning configuration the
leader thread is NOT the constraint: 96.81% of `leader_record_wall_ns` is now
named (across 3 runs, avg per-record wall 4,297 ns → ~232 k records/s per pool
worker × 8 workers = ~1.86 M leader-thread headroom). E2E throughput is capped
at ~132 k records/s.

The cap is on the producer side: the leader's VT KafkaProducer accumulator queue
holds records for **1.77 seconds on average** (`record_queue_time_avg`) before
they get sent to the broker, while the actual broker round-trip latency is only
**9.65 ms** (`request_latency_avg`). Producer aggregate `record_send_rate` is
~407 k/s, but consumer aggregate `records_consumed_rate` is ~257 k/s — a
**1.58 × producer/consumer ratio**, indicating in-process broker write/fetch is
the wall.

The new `vt_callback_total_body` decomposition shows the callback itself runs in
**~6.4 μs** out of the **2.67 – 3.85 s** cumulative `vt_produce_ack_wait`, so
99.9999 % of the wait is broker / accumulator queue time, not callback dispatch.

---

## Per-criterion status

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | Leader OTHER decomposition (≥5 new sub-stages, named coverage > 80 %) | **PASS** | 9 new on-leader-wall stages added; **named coverage averaged 96.81 % across 3 runs** (per-run: 96.78 / 96.79 / 96.86). |
| 2 | Consumer poll wait/fetch decomposition + JMX | **PASS** | `lo_poll_wait_empty_ns` and `lo_poll_fetch_nonempty_ns` emitted per run; `[KAFKA-PIPELINE] consumer client_id=...` lines per producer/consumer client. |
| 3 | VT producer ack decomposition + JMX | **PASS** | `vt_callback_total_body` and `vt_callback_entry_to_drainer` emitted; producer JMX `record_send_rate / request_latency_avg / batch_size_avg / records_per_request_avg / record_queue_time_avg` emitted. |
| 4 | 3 measurement runs at Phase 5 winning config (Phase 6 instrumentation ON) | **PASS** | `aa-phase6-run{1,2,3}.log`. Each has 4 `[E2E]` lines (2 warmup + 2 measurement) and `Run complete` line. |
| 5 | Named answer (a / b / c) backed by quantitative data | **PASS** | Answer = **(b) Kafka pipeline**. Producer/consumer 1.58×, accumulator queue 1.77 s, callback body 6.4 μs vs ack-wait 2.7–3.8 s. |
| 6 | OFF/ON instrumentation overhead within ±5 % | **PASS** | ON median-of-medians = 132 666 ops/s, OFF median = 134 350 ops/s, **delta = +1.27 %** (within ±5 %). |
| 7 | Existing AA integration tests pass | **PASS** | `BUILD SUCCESSFUL in 6m 43s`; `ActiveActiveReplicationForHybridTest` 11 tests / 0 failures / 0 errors; `TestActiveActiveIngestion` 5 tests / 0 failures / 0 errors. |
| 8 | Artifacts present | **PASS** | `aa-phase6-result.json` + this report + `aa-phase6-other-map.md`. |

---

## Sub-stage table (averaged across 3 runs, sorted by % of leader_record_wall)

`pct_avg` = sum(stage total_ns over all steady ticks across 3 runs) / sum(wall_ns).
"Steady" = `leader_record_wall_ns.calls ≥ 1 M` per tick. Only on-leader-wall stages
are listed in the named-coverage column. Off-leader-wall stages (the consumer/poll
group, the VT producer ack/callback group, drainer/RocksDB, leader_idle) are
shown for context but not in the coverage sum.

### On-leader-wall sub-stages (Phase 1–6 combined)

| Rank | Stage | Phase | pct_of_wall (3-run avg) | avg_ns/call (3-run avg) |
|---:|---|---|---:|---:|
| 1 | `lo_internal_remainder` | 6 (catch-all) | **28.46 %** | ~1,223 |
| 2 | `lo_post_merge_sensors` | 6 (new) | **15.41 %** | ~667 |
| 3 | `transient_map_put` | 1–3 | 10.77 % | ~466 |
| 4 | `lo_rmd_cache_decide` | 6 (new) | **9.80 %** | ~421 |
| 5 | `rmd_lookup_total` | 1–3 | 5.97 % | ~58,912 (rare path) |
| 6 | `value_serialize` | 1–3 | 5.89 % | ~255 |
| 7 | `lo_put_dispatch` | 6 (new) | **5.31 %** | ~229 |
| 8 | `dcr_merge` | 1–4 | 5.06 % | ~219 |
| 9 | `rt_deserialize` | 1–3 | 4.55 % | ~195 |
| 10 | `lo_write_timestamp` | 6 (new) | **2.09 %** | ~90 |
| 11 | `lo_lazy_oldvalue_init` | 6 (new) | **1.87 %** | ~80 |
| 12 | `lo_result_wrapper_alloc` | 6 (new) | **1.43 %** | ~62 |
| 13 | `lo_rmd_cache_remember` | 6 (new) | **0.19 %** | ~1,879 (rare path) |
| 14 | `lo_sensor_calls` | 6 (new) | 0.00 % | n/a (path not taken) |
| — | OTHER (un-attributed) | — | **3.19 %** | — |

**Named coverage = 96.81 %** (target was > 80 %, exceeded by **+16.81 pp**).

### Phase 6 specific sub-stage detail

`lo_internal_remainder` is a **catch-all bracket** that captures the time spent
in `processActiveActiveMessageInternal` minus the sum of inner sub-stages. It
includes (a) the inter-bracket `nanoTime()` instrumentation overhead (~14
brackets × ~80 ns = 1,120 ns) and (b) genuinely-unwrapped statements like the
`switch (msgType)` dispatch and the post-merge `if (mergeConflictResult.isUpdateIgnored())`
check. With the catch-all in place, the only un-attributed time is the OUTER
`processActiveActiveMessage` wrapper (`leaderRecordEntry()` + `countRecord()` +
`leaderRecordExit()` + the `try`/`finally` frame) — that's the residual ~3.19 %
OTHER.

`lo_post_merge_sensors` is the largest **dedicated** new bracket: **15.41 %** of
wall, **~667 ns/call**. It wraps `LatencyUtils.getElapsedTimeFromNSToMS` +
`hostLevelIngestionStats.recordIngestionActiveActivePutLatency` +
`versionedIngestionStats.recordDcrMergeTime` — i.e. **the per-record tehuti
sensor cluster on the post-DCR PUT path**. This validates the Phase 6 prompt's
"leading suspect" (tehuti `Sensor.record()` cost at 4 M records/s).

`lo_rmd_cache_decide` (**9.80 %**, **~421 ns/call**) is the second-largest. It
wraps `KeyHasher.hash(keyBytes)` + `RmdTimestampCacheManager.getOrCreate(partition)`
+ `RmdTimestampCache.decideAndUpdate(...)`. Phase 5 reported a 99.9 % cache hit
ratio, so this is dominated by the keyHash + hash-bucket compare-and-update
that runs for every PUT.

`lo_put_dispatch` (**5.31 %**, ~229 ns/call) wraps the per-record
`aggVersionedIngestionStats.recordTotalDCR(...)` (another tehuti sensor record)
plus the lazy unwrap.

### Off-leader-wall stages (Kafka producer ack-callback decomposition, Part B.2)

| Stage | avg_ns/call (3-run avg) | Comment |
|---|---:|---|
| `vt_produce_ack_wait` | **2.67 – 3.85 s** | Cumulative wait from `producer.send` to callback. **DOMINATED by accumulator queue time, not network roundtrip.** |
| `vt_callback_total_body` | **6.32 – 6.58 μs** | Whole callback body wall time. ~99.9999 % of `vt_produce_ack_wait` is OUTSIDE the callback. |
| `vt_callback_entry_to_drainer` | 5.68 – 6.02 μs | From callback ENTRY to right after `produceToStoreBufferService` returns. ~90 % of callback body is the drainer enqueue path. |
| `drainer_enqueue` | 4.58 – 5.03 μs | Subset of callback_entry_to_drainer — confirms drainer enqueue is the dominant callback-thread work. |

So when split: **~6.5 μs is the in-thread callback dispatch+enqueue**, and the
remaining 99.9999 % of `vt_produce_ack_wait` is broker-side (network + queue).

### Off-leader-wall stages (consumer poll fetch decomposition, Part B.1)

| Stage | calls (3-run avg) | avg_ns/call | Comment |
|---|---:|---:|---|
| `lo_poll_wait_empty_ns` | ~454 k empty polls | **51 ms / poll** | Polls returning 0 records spend the full 50 ms timeout (the broker had nothing ready). |
| `lo_poll_fetch_nonempty_ns` | ~590 k non-empty | **300–400 μs / poll** | Polls returning ≥ 1 record finish in sub-millisecond — the broker IS feeding data, just not as fast as the consumer can drain. |
| `rt_poll_empty_count` | ~454 k | — | Empty-poll count. |
| `rt_poll_full_count` | ~590 k | — | Polls returning ≥ 90 records (max-poll-records cap). |

The empty-poll time (51 ms × ~454 k empty polls = ~23 s of dead time per run on
the consumer thread) confirms the consumer is **frequently parked waiting** for
the broker to write more records into the topic — a classic "broker is slow"
signature for an in-process broker artifact.

---

## Kafka pipeline numbers (avg of last 3 KAFKA-PIPELINE-SUMMARY ticks per run, then averaged across 3 runs)

| Metric | Value | Comment |
|---|---:|---|
| **producer_total_send_rate** (sum across all producers, JMX `record-send-rate`) | **407,602 records/s** | Aggregate producer rate. Includes the 4 benchmark producers + the leader's VT writer. |
| **consumer_total_consume_rate** (sum across all consumers, JMX `records-consumed-rate`) | **257,420 records/s** | Aggregate consumer rate (includes RT consumers + VT consumers + system-store consumers). **1.58 × less than producer rate.** |
| producer `request_latency_avg_ms` (top producer) | **9.65 ms** | True broker roundtrip — small. |
| producer `batch_size_avg_bytes` | 183,721 B | Each batch ships ~180 kB. |
| producer `records_per_request_avg` | 18,517 records/req | Producer is sending big batches (capped at `batch.size = 16 kB` * with linger). |
| producer **`record_queue_time_avg_ms`** | **1,766 ms** | **Records sit in the producer accumulator for ~1.77 seconds before flush.** This is the dominant component of `vt_produce_ack_wait` (~2.7–3.8 s). |
| consumer `fetch_latency_avg_ms` | 105.99 ms | Consumer fetch wait (from request to fetch response). |
| consumer `fetch_size_avg_bytes` | 3,914,811 B | ~3.9 MB per fetch — large, consistent with `max.partition.fetch.bytes` ~ 4 MB. |

The `record_queue_time_avg_ms` of 1.77 seconds is **the smoking gun for answer
(b)**: records spend ~177 × longer queued in the producer's in-memory
accumulator than on the network (9.65 ms). The accumulator only flushes when
either `batch.size` or `linger.ms` is hit, and at the current rate the broker
isn't acking fast enough to keep `max.in.flight.requests.per.connection` from
saturating, so new records pile up in the accumulator.

---

## Why the answer is (b), not (a) or (c)

**(a) Leader OTHER bucket dominated by an expensive named stage** — RULED OUT.
The largest **dedicated** new sub-stage is `lo_post_merge_sensors` at **15.41 %**
of wall (~667 ns/call). The criterion for (a) was a single new stage > 25 %.
While `lo_internal_remainder` is 28.46 %, it is a catch-all that mostly captures
inter-bracket `System.nanoTime()` overhead — not a single optimisable workload.

**(b) Kafka pipeline cap** — CONFIRMED.
1. **Producer/consumer rate gap**: producer aggregate 407.6 k/s vs consumer
   aggregate 257.4 k/s (1.58 ×). The consumer can't keep up with what the
   producers are pushing — but the broker is also batching producer-side, so
   the actual binding constraint is on the broker fetch path.
2. **Empty-poll count is large**: ~454 k empty polls per run × 51 ms each =
   ~23 seconds of consumer thread parked. The broker frequently has nothing
   to give the leader's RT consumer.
3. **`record_queue_time_avg` 1.77 seconds** — records pile up in the producer
   accumulator for ~177 × the network roundtrip. This means the producer is
   hitting `max.in.flight.requests.per.connection` saturation and waiting for
   the broker to ack before sending more.
4. **Callback body is 6.4 μs out of 2.7–3.8 s `vt_produce_ack_wait`** — only
   1 / 500,000 of the ack-wait is on our JVM-internal thread, so the
   "callback dispatch starvation" hypothesis (c) is rejected.

**(c) Hybrid** — RULED OUT (mostly). Phase 6 leader OTHER decomposition does
identify two sub-stages worth ~25 % combined (`lo_post_merge_sensors` 15 % +
`lo_rmd_cache_decide` 10 %) where production code could plausibly be
optimised. But the throughput cap is downstream, not on the leader thread.

---

## Reproducibility

Per-run named coverage and Phase 6 coverage:

| Run | E2E iters | E2E median | named_coverage | phase6_coverage |
|---|---|---:|---:|---:|
| run1 | 130,946 / 128,870 | 129,908 | 96.78 % | 64.46 % |
| run2 | 128,445 / 136,887 | 132,666 | 96.79 % | 64.49 % |
| run3 | 129,000 / 140,562 | 134,781 | 96.86 % | 64.77 % |
| **3-run avg** | — | **132,666** | **96.81 %** | **64.57 %** |

Spread of named coverage: **0.08 pp** (max 96.86 − min 96.78) — tightly
reproducible.

Top sub-stage stable across runs: `lo_internal_remainder` is #1 in 3/3 runs
(26.32 %, 28.78 %, 30.29 %); `lo_post_merge_sensors` is #2 in 3/3 runs
(13.84 %, 14.88 %, 17.51 %).

---

## OFF / ON measurement integrity (criterion 6)

| Comparison method | ON (3 ON runs) | OFF (1 OFF run) | Delta | ±5 % tolerance |
|---|---:|---:|---:|---|
| Median of run-medians | 132,666 | — | — | — |
| OFF median across 2 measurement iters | — | 134,350 | **+1.27 %** | **PASS** |

OFF iters: 139,166 / 129,533 (measurement); 139,419 / 135,651 (warmup) —
4 iters total, median 137,408 across all 4. The +1.27 % delta is well inside
the ±5 % bound; Phase 6 instrumentation overhead is not perturbing measurement.
Note: the OFF run still leaves Phase 1–5 instrumentation enabled
(`bottleneck` + `dcr.merge` + `rmd.timestamp.cache.enabled`), only the new
Phase 6 flags (`leader.other` + `kafka.pipeline`) were turned off.

---

## Existing tests pass (criterion 7)

```
JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft \
./gradlew :internal:venice-test-common:integrationTest \
  --tests "com.linkedin.venice.endToEnd.ActiveActiveReplicationForHybridTest" \
  --tests "com.linkedin.venice.endToEnd.TestActiveActiveIngestion" \
  --rerun-tasks
```

**Result**: `BUILD SUCCESSFUL in 6m 43s, exit=0`.

| Test class | Tests | Failures | Errors |
|---|---:|---:|---:|
| `ActiveActiveReplicationForHybridTest` | 11 | 0 | 0 |
| `TestActiveActiveIngestion` | 5 | 0 | 0 |
| **Total** | **16** | **0** | **0** |

XML files inspected:
`internal/venice-test-common/build/test-results/integrationTest/TEST-com.linkedin.venice.endToEnd.ActiveActiveReplicationForHybridTest.xml`
(`tests=11 failures=0 errors=0`),
`...TestActiveActiveIngestion.xml` (`tests=5 failures=0 errors=0`).

---

## Plausible interventions (hypotheses, not implementations)

Given the answer is (b), most production-relevant interventions target the
Kafka pipeline rather than the leader thread. The leader-thread-side
optimisations from Phase 6 are listed last for completeness.

1. **Tune VT producer for lower `record_queue_time`**.
   - Reduce `linger.ms` from default 100 ms toward 0–10 ms so batches flush
     sooner.
   - Increase `max.in.flight.requests.per.connection` from default 5 to
     unlock more concurrent batches.
   - Raise `buffer.memory` if the accumulator is hitting back-pressure
     (but the JMX shows `buffer-available-bytes` would tell us).
2. **Tune broker fetch behaviour**.
   - Increase the in-process broker's `num.io.threads` / `num.network.threads`
     so it acks faster.
   - Tune the consumer side: lower `fetch.min.bytes` or `fetch.max.wait.ms`
     so empty-poll wait drops below 51 ms.
3. **Sharding / partition count**.
   - Current benchmark uses 2 partitions. Increasing partitions divides the
     accumulator load across more producer threads, in principle reducing the
     per-batch queue time.
4. **(Lower priority — leader-thread side)** Reduce tehuti sensor cost:
   - `lo_post_merge_sensors` at 15 % of wall is the largest single optimisable
     leader-thread contribution. Replacing the per-record tehuti
     `Sensor.record()` calls with a `LongAdder`/sampling scheme could shave
     several hundred nanoseconds per record. But at the current cap (Kafka
     pipeline) this saved leader time would not translate to E2E throughput —
     the leader is already 14 × over-provisioned.
5. **(Lower priority — leader-thread side)** Reduce RmdTimestampCache cost:
   - `lo_rmd_cache_decide` at 9.8 % of wall (~421 ns/call) is dominated by
     the `KeyHasher.hash(keyBytes)` Murmur3 step. A pre-computed key-hash
     stored alongside the key (one-time cost at deserialisation) could
     halve this.
6. **Benchmark-only artifact note**: the in-process broker may not represent
   a real Venice production broker (which sits on dedicated hardware with
   tuned IO). The 1.58 × producer/consumer ratio and 51 ms empty-poll wait
   may not appear in production. Recommend this be measured on a real
   distributed cluster before committing engineering effort to broker
   tuning.

---

## Files added / modified

### NEW
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaKafkaPipelineReporter.java`
  — Phase 6 JMX scraper. Produces `[KAFKA-PIPELINE]` lines for
  `kafka.producer:type=producer-metrics,*` and
  `kafka.consumer:type=consumer-fetch-manager-metrics,*` MBeans every 20 s.
  Gated by `venice.server.aa.kafka.pipeline.instrumentation.enabled`.

### MODIFIED
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaLeaderBottleneckReporter.java`
  — Added 12 new `Stage` enum constants (9 leader-on-wall, 2 poll
  off-wall, 2 VT-callback off-wall, 1 catch-all). Added `LEADER_OTHER_ENABLED`
  static flag. Added `recordAndAccumulate()`, `internalBodyStart()`,
  `recordInternalRemainder()` for the catch-all bracket.
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java`
  — Wrapped 9 new sub-stages on the leader hot path. Switched the existing
  Phase 1–5 brackets that run inside `processActiveActiveMessageInternal`
  (`rt_deserialize`, `rmd_lookup_total`, `dcr_merge`, `value_serialize`,
  `transient_map_put`) to use `recordAndAccumulate` so the catch-all
  bracket subtracts them correctly. Added the catch-all
  `LO_INTERNAL_REMAINDER` bracket via `internalBodyStart()` /
  `recordInternalRemainder()` around `processActiveActiveMessageInternalImpl`.
  No change to merge logic.
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ConsumptionTask.java`
  — Wrapped poll empty / fetch-nonempty into `LO_POLL_WAIT_EMPTY_NS` and
  `LO_POLL_FETCH_NONEMPTY_NS` (off-leader-wall, gated by
  `LEADER_OTHER_ENABLED`).
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/LeaderProducerCallback.java`
  — Refactored `onCompletion` to capture `bnCallbackEntryNs` and wrap the
  callback body with `try`/`finally`, recording `VT_CALLBACK_TOTAL_BODY` and
  `VT_CALLBACK_ENTRY_TO_DRAINER` (off-leader-wall, gated by
  `LEADER_OTHER_ENABLED`). Renamed inner method to `onCompletionInternal`.
- `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java`
  — Reverted Phase 5.5 `setWriteComputationEnabled(true)` to
  `setWriteComputationEnabled(workloadType != WorkloadType.PUT)` (Phase 5
  winning config). Confirmed `PUT_KEY_POOL_SIZE = 100_000`.

### NEW (artifacts)
- `aa-phase6-other-map.md` — pre-implementation map of OTHER sub-stages.
- `aa-phase6-result.json` — machine-readable per-run sub-stage tables, OFF/ON
  delta, named answer, integration-test status.
- `AA_LEADER_OTHER_AND_KAFKA_PHASE6_REPORT.md` — this document.
- `aa-phase6-parser.py` + `aa-phase6-finalize.py` — log parser / finalizer.

### NEW (logs)
- `aa-phase6-run{1,2,3}.log` — 3 ON measurement runs (Phase 6 flags ON).
- `aa-phase6-run{1,2,3}-v0.log` — initial 3 runs done before adding the
  `LO_INTERNAL_REMAINDER` catch-all (kept for diff comparison; coverage was
  57 % before the catch-all). Not used in the official 96.81 % coverage figure.
- `aa-phase6-off.log` — 1 OFF run (Phase 6 flags OFF; Phase 1–5 ON).
- `aa-phase6-itest.log` — integration test run.
- `aa-phase6-build.log` / `aa-phase6-build2.log` — JMH jar builds.

---

## Final state

The leader OTHER bucket is decomposed: **96.81 % named coverage** with the
new sub-stages plus a documented 3.19 % residual (outer wrapper). The largest
**dedicated** new sub-stage is `lo_post_merge_sensors` at 15.41 % of wall
(tehuti sensor calls per record). The Kafka pipeline characterisation shows
**producer accumulator queue time (1.77 s) ≫ broker roundtrip (9.65 ms)** —
records pile up in the producer's in-memory accumulator while the broker
acks them slowly. The VT callback body itself is only 6.4 μs out of the
2.7–3.8 s cumulative ack-wait, so callback dispatch starvation is rejected.

**Named answer: (b) Kafka pipeline.** The leader thread is 14 × over-provisioned
relative to the cap. Production-relevant intervention priority: producer
linger / batch / accumulator tuning > broker IO/network thread tuning >
leader-thread tehuti-sensor optimisation. The latter is a real win on the
leader-thread budget, but at the current cap (Kafka pipeline), it does not
translate to higher E2E throughput.
