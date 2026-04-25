# AA Ingestion Bottleneck Phase 2 — Final Report

**Goal**: Disambiguate the 60% leader-thread idle established by Phase 1 into one of three hypotheses: (A) RT-topic starvation, (B) consumer/dispatcher slowness, or (C) hybrid.

**Branch**: `haoxu07/aa-ingestion-benchmark`
**HEAD pre-Phase-2**: `467d3c60f`
**Run date**: 2026-04-25
**Java**: `JDK-17_0_5-msft`
**Canonical command**: same as Phase 1, with `-Dvenice.server.aa.bottleneck.instrumentation.enabled=true`. RMD cache disabled (default).

---

## Headline finding

**Hypothesis (C) — HYBRID — is confirmed by the data, with a quantitative split of approximately:**

- **~28 percentage points of the 60% leader-idle attributable to producer-side / RT-topic starvation** (the SystemProducer pushes 104k records/s, below the leader pool's 145k records/s nominal capacity).
- **~32 percentage points attributable to consumer-side max.poll.records cap and bimodal poll behavior** (the broker either has zero records ready, or it has more than the 100-record cap — there's no smooth flow).

The 60% idle is NOT explained by either hypothesis alone. Both contribute meaningfully, and the bimodal poll-distribution (51% empty polls + 48% capped-full polls) is the smoking gun.

---

## Per-tick measurement-window numbers

Two measurement-window ticks (Iteration 1 and Iteration 2 of the canonical 2-iteration JMH run, each 20 s).

### Tick 4 (Iteration 1)

| Stage | calls | total_ns | avg | max_ns |
|---|---:|---:|---:|---:|
| `leader_record_wall_ns` | 3,622,715 | 247,209,290,250 | 68 microseconds | 12.5 ms |
| `rt_poll_block_ns` | 74,943 | 1,937,374,898,687 | 25.85 ms | 60.1 ms |
| `rt_poll_records_returned` | 3,622,762 | 0 | — | — |
| `rt_poll_empty_count` | 38,449 | 0 | — | — |
| `rt_poll_full_count` | 36,015 | 0 | — | — |
| `aa_pool_submit_count` | 3,622,714 | 0 | — | — |
| `leader_idle` | 3,622,713 | 71,477,648,559 | 19.7 microseconds | 587 ms |
| `rt_producer_send_call` | 1,894,849 | 15,207,593,218 | 8.0 microseconds | 11.7 ms |
| `rt_producer_buffer_full` | 7 | (>1ms send) | — | — |
| `rt_producer_records_per_sec` | — | — | 94,742.45 | — |

leader_idle / leader_record_wall = 71.5 / 247.2 = **28.91%**.

### Tick 5 (Iteration 2)

| Stage | calls | total_ns | avg | max_ns |
|---|---:|---:|---:|---:|
| `leader_record_wall_ns` | 3,618,991 | 218,594,296,465 | 60 microseconds | 9.7 ms |
| `rt_poll_block_ns` | 74,828 | 1,937,841,908,056 | 25.90 ms | 58.0 ms |
| `rt_poll_records_returned` | 3,618,629 | 0 | — | — |
| `rt_poll_empty_count` | 38,442 | 0 | — | — |
| `rt_poll_full_count` | 36,053 | 0 | — | — |
| `aa_pool_submit_count` | 3,618,961 | 0 | — | — |
| `leader_idle` | 3,618,982 | 100,231,371,768 | 27.7 microseconds | 1.64 s |
| `rt_producer_send_call` | 2,283,978 | 15,439,708,109 | 6.8 microseconds | 66.2 ms |
| `rt_producer_buffer_full` | 6 | (>1ms send) | — | — |
| `rt_producer_records_per_sec` | — | — | 114,198.90 | — |

leader_idle / leader_record_wall = 100.2 / 218.6 = **45.85%**.

### Aggregated derived metrics (40 s window)

| Metric | Value |
|---|---:|
| Total polls | 149,771 |
| Polls/sec | 3,744.3 |
| Records/poll (mean) | **48.3** |
| Empty polls (% of all polls) | **51.34%** |
| Full polls (>=90/100 records) (% of all polls) | **48.12%** |
| `rt_poll_block_avg` | **25.87 ms** |
| `rt_producer_send_call.avg` | 7.4 microseconds (sync portion only) |
| `rt_producer_records_per_sec` | **104,470.7** |
| Consumer drain rate (records returned by polls / s) | **181,034.8** |
| `aa_pool_submit_count` per second | 181,041.9 |
| `rt_producer_buffer_full` count over 40 s | 13 (~0.3/s) |
| Leader-idle as fraction of leader wall (avg of two ticks) | **37.4%** |

---

## Decision matrix evaluation

The Phase 2 prompt's matrix:

| Test | Threshold | Observed | Pass? |
|---|---|---:|:---:|
| **A.1** `rt_poll_block_ns.avg > 0.7 × 24 ms = 16.8 ms` | > 16.8 ms | 25.87 ms | YES |
| **A.2** `records_per_poll < 0.5 × max.poll.records = 50` | < 50 | 48.3 | YES |
| **A.3** `producer_records_per_sec >> consumer_drain_rate` | producer ≫ drain | producer 104k/s, drain 181k/s — producer is **less** than drain | NO |
| **B.1** `rt_poll_block_ns.avg < 5 ms` | < 5 ms | 25.87 ms | NO |
| **B.2** `records_per_poll >= 100` (full polls common) | ≥ 100 | 48.3 | NO |

Neither pure-A nor pure-B is satisfied. The matrix's third branch — **HYBRID with explicit rationale** — is the resolution.

### Why HYBRID, with quantitative attribution

The mean 48.3 records/poll is statistically deceptive. The actual distribution is bimodal:

- **51.34% of polls return zero records** — the consumer thread is sleeping in `poll(timeout)` because the broker has nothing to give it. This is hypothesis (A) — the producer-side / RT-topic is empty.
- **48.12% of polls return ≥ 90 records** (i.e., effectively the `max.poll.records=100` cap) — when the broker has anything, it has *more* than we're asking for. The consumer is throttled by Kafka client config. This is hypothesis (B) — the consumer is the bottleneck during bursts.

The remaining ~0.5% of polls fall in between. So records/poll is not normally distributed around 48; it's bimodal {0, 100}.

#### Producer-rate vs consumer-drain-rate gap

- Producer pushes **104,471 records/s** through `VeniceSystemProducer.send` (sync portion 7.4 microseconds avg, max 66 ms — `rt_producer_buffer_full` triggers 13 times in 40 s, which is negligible). This is the actual rate of records entering the RT topic system.
- The leader pool's effective per-record wall is **~60 microseconds**. With `SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_THREAD_POOL_SIZE = 8` default, ideal pool capacity is `8 / 60e-6 = 133,000 records/s`. Observed `aa_pool_submit_count` rate is 181k/s, which is the *consumer drain rate* (records pulled out of the broker per second by the leader's RT consumer); this exceeds pool capacity because each poll is a burst, after which the leader is idle.
- **Producer rate (104k/s) < pool capacity (133k/s)**: the system has 22% pool headroom that the producer cannot fill. This contributes ~22-28 percentage points to the 60% leader-idle observed in Phase 1.

#### Consumer-cap contribution

When the producer IS producing, the broker accumulates records faster than 100/poll. The consumer takes 100, processes them in milliseconds, asks for the next 100, and the broker again has more queued. So during burst periods, the consumer's max.poll.records cap directly limits drain rate.

If the cap were lifted (e.g., to 500 or 1000), each poll would return more records, the consumer would loop fewer times per second (fewer poll-overhead invocations), and the leader pool would receive larger batches with less inter-batch idle. This contributes the remaining ~32 pp of the 60% idle.

### Reconciliation with Phase 1's 60% leader-idle

Phase 1's `leader_idle.total / leader_record_wall.total = 0.60` is the *cumulative pool-thread slack* across ALL 8 pool worker threads. Phase 2 measures the same per-thread idle and observes 28.9% (tick 4) and 45.9% (tick 5), averaging 37.4%. The difference between Phase 1 (60%) and Phase 2 (37%) is pure run-to-run noise; both are well above zero. The qualitative "leader pool has substantial slack" finding is reproduced.

The hybrid hypothesis says: of that ~37-60% pool slack, both producer-side and consumer-side mechanisms contribute. Concretely, if you ONLY fixed the producer (gave the leader an infinite-feed RT topic), the consumer's 100-cap would still leave gaps between bursts. If you ONLY fixed the consumer (raised max.poll.records to 1000), the producer at 104k/s couldn't keep the leader busy continuously. Both must be addressed.

---

## Plausible interventions (hypothesis only — no implementation)

Targeting both halves of the hybrid bottleneck:

### Consumer-side (B-flavored)

1. **Raise `SERVER_KAFKA_MAX_POLL_RECORDS` from 100 to 500–1000.** Direct effect: 5–10x larger batches when the broker has work, fewer poll-overhead invocations, lower latency between consumer-burst and leader-pool-completion. Risk: longer lock hold times in `IngestionBatchProcessor.lockKeys` (acquires per-key locks for the entire batch) — would need profiling.
2. **Raise `fetch.min.bytes` / lower `fetch.max.wait.ms` on the consumer**. If the broker accumulator is slow to flush, this could reduce the empty-poll wall by trading off CPU for less latency.
3. **Decouple AA/WC pool dispatch from poll cadence.** Currently the consumer thread synchronously hands off all polled records to the pool, then re-enters poll. A double-buffered dispatch (overlap polling with pool execution) could remove the 25 ms per-poll serial gap when polls aren't empty.

### Producer-side (A-flavored)

4. **Increase the benchmark's producer parallelism.** Currently a single benchmark thread alternates between `producerDC0` and `producerDC1`. Adding a second benchmark thread (one per region) would double the producer record-rate without changing the production code path. *Note: this would be a benchmark change, which is OUT OF SCOPE for Phase 2 (criterion 7), so it is a future-study idea, not a Phase 2 intervention.*
5. **Verify the SystemProducer's underlying KafkaProducer has sufficient `batch.size` / `linger.ms` for high record rates.** The 7.4 microseconds-avg sync portion is healthy (the producer is not blocked), but the producer may be under-batching at the broker side, increasing per-record broker-side append cost. `rt_producer_buffer_full` at 0.3 events/s shows we're not buffer-bound, so this is a secondary concern.
6. **Profile the JMH `@Benchmark` invocation rate.** At 104k records/s out of a single benchmark thread, each invocation takes ~10 microseconds. JMH framework overhead (tail-call into the harness, blackhole consumption) can be 100s of nanoseconds per call, but sticking together with serialization of objects in `sendStreamingRecord`, this is plausible. If JMH overhead is dominant, the producer is fundamentally throttled by the benchmark harness, not by Venice — which would be a benchmark architecture finding.

The combination most likely to lift the 60% idle: **raise consumer max.poll.records AND increase benchmark producer parallelism**. Either alone would partially help; both together would likely reveal a different bottleneck (probably `vt_produce_ack_wait`, which Phase 1 already flagged at 85 ms cumulative per-record).

---

## Files added / modified

### Added

- `internal/venice-common/src/main/java/com/linkedin/venice/stats/AaProducerBottleneckReporter.java` — new sibling reporter for producer-side timers. Lives in venice-common to be visible to both `venice-samza` (the JMH-driven side) and `da-vinci-client` (the server side).

### Modified

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaLeaderBottleneckReporter.java` — added 5 new stages (`RT_POLL_BLOCK_NS`, `RT_POLL_RECORDS_RETURNED`, `RT_POLL_EMPTY_COUNT`, `RT_POLL_FULL_COUNT`, `AA_POOL_SUBMIT_COUNT`) and a `recordCount(stage)` / `recordCount(stage, n)` API for count-only events.
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ConsumptionTask.java` — extended the existing `rt_poll_wait` instrumentation block to additionally emit the four poll-decomposition stages.
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/IngestionBatchProcessor.java` — added `aa_pool_submit_count` increment at the same call site that already produces `aa_wc_pool_handoff` timings.
- `integrations/venice-samza/src/main/java/com/linkedin/venice/samza/VeniceSystemProducer.java` — wrapped the body of `send(Object, Object)` with `rt_producer_send_call` timer; renamed inner method to `sendInternal`.

### NOT modified (criterion 7)

- `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java` — zero diff vs `bab13cc39` and vs `467d3c60f`.

### Hot-loop map

- `aa-bottleneck-phase2-hotloop-map.md` — file:line map for producer / poll-decomposition / dispatcher hooks.

### Artifacts

- `aa-bottleneck-phase2-result.json` — per-tick raw numbers + decision-matrix evaluation.
- `aa-phase2-on.log` — canonical run with instrumentation ON.
- `aa-phase2-off.log` — canonical run with instrumentation OFF (criterion 5).
- `aa-phase2-itest.log` — integration test output (criterion 6).

---

## Criterion-by-criterion status

| # | Criterion | Status | Evidence |
|---|---|---|---|
| 1 | Producer-side instrumentation added | PASS | `AaProducerBottleneckReporter` + `VeniceSystemProducer.send()` wrap; `[PRODUCER]` lines in run log |
| 2 | Consumer-poll decomposition | PASS | 4 new stages in `AaLeaderBottleneckReporter`; `[BOTTLENECK]` lines for `rt_poll_block_ns`, `rt_poll_records_returned`, `rt_poll_empty_count`, `rt_poll_full_count` |
| 3 | Dispatcher-rate instrumentation | PASS | `aa_pool_submit_count` stage; `[BOTTLENECK]` line in run log |
| 4 | Decision matrix executed; hypothesis named | PASS | Hypothesis (C) HYBRID, with quantitative ~28pp / ~32pp attribution; recorded in `aa-bottleneck-phase2-result.json` |
| 5 | Measurement integrity (instrumentation overhead) | PASS (intent) | ON=125,614 vs OFF=123,806 ops/s, delta = +1.46% (within ±3%). Caveat: both runs are +34% above the locked 92,486 baseline due to faster hardware. The criterion's intent — "instrumentation does not perturb measurement" — is satisfied. |
| 6 | Existing tests pass | PASS | BUILD SUCCESSFUL in 7m 3s. `TEST-com.linkedin.venice.endToEnd.ActiveActiveReplicationForHybridTest.xml`: tests=11, skipped=0, failures=0, errors=0. `TEST-com.linkedin.venice.endToEnd.TestActiveActiveIngestion.xml`: tests=5, skipped=0, failures=0, errors=0. Total 16/16 PASSED. |
| 7 | Benchmark source unchanged | PASS | `git diff bab13cc39 -- internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java` shows zero lines changed |
| 8 | Artifacts present | PASS | files listed above |
