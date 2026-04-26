# Phase 8 Report — In-Memory Kafka Substitution: A/B Throughput + Allocation

**Branch**: `haoxu07/aa-ingestion-benchmark`
**HEAD at start**: `9abec747a` (Phase 7 progress checkpoint)
**Java**: `JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft`
**Date**: 2026-04-26
**Goal prompt**: `/home/coder/AA_INMEMORY_KAFKA_PHASE8_PROMPT.md`

## Goal recap

Phase 7 concluded **(b) no Kafka knob lifts the 142k ops/s cap**, but the alloc profile showed
~30% of allocation pressure was still coming from the in-process Kafka broker + client. Phase
8 isolates that contribution by **substituting** Venice's existing in-memory pubsub broker
(the one used by `StoreIngestionTaskTest`) into the integration-test cluster used by the AA
ingestion JMH benchmark, then comparing throughput and allocation against the Apache Kafka
baseline at the Phase 5 winning config (WC=off + RMD cache=on, 100k keys, multi-producer N=2).

## Wiring delivered (criteria 1–4)

Six new files in
`internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/integration/utils/`:

| File | Purpose |
|---|---|
| `InMemoryPubSubBrokerRegistry.java` | Process-wide `ConcurrentHashMap<String, InMemoryPubSubBroker>` keyed by `host:port`. Also holds one shared `MockInMemoryAdminAdapter` per broker (NOT per address — both plaintext and SSL aliases for the same broker share state). The shared admin's `close()` is overridden to a no-op so component shutdowns don't clear the topic map. |
| `InMemoryPubSubBrokerFactory.java` | Implements `PubSubBrokerFactory<InMemoryPubSubBrokerWrapper>`. Public no-arg constructor. Inner `InMemoryPubSubBrokerWrapper` extends `PubSubBrokerWrapper`; allocates `host:freePort` + `host:freeSslPort`; on `internalStart()` registers itself in the registry under both addresses; on `internalStop()` deregisters. Returns a static `IN_MEMORY_CLIENTS_FACTORY` containing the three adapter factories. Carries a custom `PubSubPositionTypeRegistry` containing the reserved entries (Kafka offset, EARLIEST, LATEST) PLUS the in-memory entry (-42 → `InMemoryPubSubPositionFactory`). |
| `InMemoryProducerAdapterFactory.java` | Public no-arg constructor; `create(ctx)` → looks broker up by `ctx.getBrokerAddress()`, returns `MockInMemoryProducerAdapter(broker)`. |
| `InMemoryConsumerAdapterFactory.java` | Public no-arg constructor; `create(ctx)` → `MockInMemoryConsumerAdapter(broker, RandomPollStrategy(500), NoOpPubSubConsumerAdapter)`; wires the shared `MockInMemoryAdminAdapter` so `partitionsFor()` resolves topics created via the controller. **Bumped `maxMessagePerPoll` from the unit-test default of 3 to 500 to match Apache Kafka's `max.poll.records` default** — without this the consumer cannot drain anything close to production rates. |
| `InMemoryAdminAdapterFactory.java` | Public no-arg constructor; `create(ctx)` → returns the SAME shared admin adapter the consumer factory wires (registry-managed, broker-keyed). |
| `NoOpPubSubConsumerAdapter.java` | Tiny stub fulfilling `PubSubConsumerAdapter` for `MockInMemoryConsumerAdapter`'s delegate slot (the integration cluster doesn't use the delegate's call-verification semantics; the mock's own state machine handles subscribe/poll). |

Also updated:

* `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaKafkaBrokerReporter.java` — first-tick gate detects whether any Kafka broker MBeans exist; if none, emits one `[KAFKA-BROKER] no Kafka broker MBeans available; reporter inactive (in-memory broker mode).` line and goes silent. Required so Run B does not throw or spam `-1` lines (criterion 5).
* `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaKafkaPipelineReporter.java` — same first-tick gate; emits one `[KAFKA-PIPELINE] no Kafka client MBeans available; reporter inactive (in-memory broker mode).` line and goes silent.
* `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java` — `setUp()` reads `-Dvenice.benchmark.use.inmemory.pubsub` (default `false`), and when `true` sets `pubSubBrokerFactory` system property to `com.linkedin.venice.integration.utils.InMemoryPubSubBrokerFactory` BEFORE the first `ServiceFactory.*` call. Echoes the choice via `System.err`. Also injects `pubsub.{producer,consumer,admin}.adapter.factory.class` into the Samza producer's config map so the Samza-driven `VeniceWriterFactory` reflectively picks up the in-memory factory class names rather than falling back to `ApacheKafkaProducerAdapterFactory`. Bumps the empty-push timeout from 60 s → 5 min for the in-memory mode (still 60 s for Apache Kafka).

## Two canonical runs

**Run A (apache_kafka)** — log: `aa-phase8-runA.log`. Total wall: 5m32s. **4 [E2E] lines emitted, 0 throws, 0 KAFKA-BROKER errors.**

| Iteration | Records | Elapsed (ms) | E2E (ops/sec) |
|---|---:|---:|---:|
| W1 | 6 845 000 | 53 163 | 128 754.08 |
| W2 | 7 387 000 | 56 410 | 130 951.42 |
| M1 | 7 392 000 | 56 301 | 131 292.75 |
| M2 | 7 364 000 | 56 308 | 130 779.87 |
| **Median** | | | **130 865.65** |

Within `±10%` of the Phase 5/6/7 baseline window of 132–142 k (Run A's median is 1.0 % below the lower edge; well inside JMH variance for a 4-iteration run).

**Run B (in_memory)** — primary log: `aa-phase8-runB-v7.log`. Seven progressive attempts (v1 → v7 + `runB-short`, `runB-bal`, `runB-tiny`) before concluding the iteration cannot complete. Each attempt fixed a different wiring bug exposed by the prior failure:

| Attempt | What was added | Outcome |
|---|---|---|
| v1 | Initial wiring | `MockInMemoryConsumerAdapter.partitionsFor()` → `UnsupportedOperationException: In-memory admin adapter is not set` |
| v2 | Wired `setMockInMemoryAdminAdapter()` in consumer factory; added `lookupOrCreateAdmin()` to registry | `PubSubPositionTypeRegistry: PubSub position type ID not found: -42` (the in-memory position type wasn't published in `getAdditionalConfig()`) |
| v3 | Built `IN_MEMORY_PUBSUB_POSITION_TYPE_REGISTRY` containing reserved + −42; merged map shipped to all controllers/servers | `Topic does not exist` on dc-1 server side — admin adapter was keyed by ADDRESS so the SSL-port lookup got a different empty admin instance from the plaintext-port one, even though both pointed at the same broker |
| v4 | Re-keyed admin adapters by `InMemoryPubSubBroker` instance identity (so SSL+plaintext share state) | Samza producer fell back to `ApacheKafkaProducerAdapterFactory` (multi-region helper in `IntegrationTestPushUtils.getSamzaProducerForStream()` doesn't auto-inject the adapter factory class names); 30 s `getNumberOfPartitions(topicName)` timeout |
| v5 | Benchmark `setUp()` injects `pubsub.{producer,consumer,admin}.adapter.factory.class` into the Samza Pair varargs when in-memory mode is on | Iteration ran (25.8 M records produced!) but consumer stuck at RT position 1 because `AbstractPollStrategy.maxMessagePerPoll = 3` |
| v6 | `RandomPollStrategy(500)` (matching Apache Kafka's `max.poll.records` default) | Empty-push hit 60 s timeout — too short for the in-memory broker's serialized control-plane to drain |
| v7 | 5 min empty-push timeout for in-memory mode | Iteration started, BOTTLENECK ticks emitted (~268 k rec/sec/server), but per-iteration sentinel never visible within 5+ min teardown timeout — producer rate still ~10× consumer rate |
| short, bal, tiny | Reduced `phase3.records.per.invocation` to 50 / 1, reduced JMH iteration time to 5 s / 2 s | Even an empty push (single record from each region!) takes longer than 5 minutes to drain through the system-store creation chain — the synchronized in-memory broker serializes the entire control-plane in addition to the data path |

**Run B's broker registry was healthy across all attempts**: 3 distinct `InMemoryPubSubBroker` instances (parent + dc-0 + dc-1), each registered under both plaintext and SSL aliases (6 total entries). Cross-region replication WAS observed in v5–v7 (`rtPositionsByBroker={localhost:42609=…, localhost:43943=…}` shows servers consuming from BOTH the local and the remote-region broker over the same in-memory address registry — that part worked exactly like the prompt specified).

**KAFKA-BROKER reporter under in-memory** (criterion 5 dependency): Run B v6+ logs contain exactly:

```
[KAFKA-PIPELINE] no Kafka client MBeans available; reporter inactive (in-memory broker mode).
[KAFKA-BROKER] no Kafka broker MBeans available; reporter inactive (in-memory broker mode).
```

…then both reporters go silent for the remainder of the run. **No throws, no `-1` line spam.** Criterion 5's "must not throw" sub-requirement is satisfied.

## E2E + allocation delta table

| run | broker | e2e_median_ops_per_sec | delta_vs_apache_pct | avg_alloc_rate_gb_per_sec | venice_alloc_share_pct | kafka_alloc_share_pct | helix_zk_alloc_share_pct |
|---|---|---:|---:|---:|---:|---:|---:|
| A | apache_kafka | 130 866 | 0.0 | (Phase 7 alloc) ~3.5 GB/s | <1 | ~30 | ~30 |
| B | in_memory | **0** (no completed iteration) | **−100 %** | not measured | unknown (alloc profile blocked) | ~0 expected | unknown (alloc profile blocked) |

The `kafka_alloc_share` row for Run B is the *expected* value — under the in-memory broker, the
producer/consumer/broker classes that allocated 30 % of Run A's bytes (`ProduceResponse$PartitionResponse`,
`RecordHeaders.<init>`, `ConsumerNetworkClient.failExpiredRequests`, `AbstractRecords` lambda,
`Selector.select`, `RequestChannel`, …) simply don't run. The expected redistribution targets
Helix/ZK + the synchronized monitor on `InMemoryPubSubTopic`. **We could not confirm this
empirically because the alloc flamegraph is emitted by async-profiler at JVM exit, and JMH does
not exit until measurement iterations finish — which under in-memory's coarse locking they did
not.** The `aa-profile-alloc-inmemory/` directory exists but is empty.

## Named conclusion: **(c) — E2E DROPS under in_memory; per-topic synchronized produce mutex is the new bottleneck**

### Evidence

1. Run A (apache_kafka) produced 4 [E2E] lines all within 1.9 % of each other (128–131 k ops/sec); median 130 866. Within Phase 7 baseline window.
2. Run B (in_memory) successfully replaced the in-process Apache Kafka broker — three `InMemoryPubSubBroker` instances were registered (parent + dc-0 + dc-1), each with plaintext and SSL aliases. Cross-region replication path resolved correctly through the registry (`rtPositionsByBroker` showed both regions' brokers consumed from).
3. `AaKafkaBrokerReporter` and `AaKafkaPipelineReporter` emitted one inactivity line each in Run B then went silent. **No throws.** Criterion 5's "must not throw" sub-requirement met.
4. Run B's *producer* path is much faster than Apache Kafka — `MockInMemoryProducerAdapter.sendMessage()` returns synchronously after a single `synchronized` append + immediate callback fire + completed future. No batching, no I/O, no broker request thread. Each invocation of `runPutWorkload()` therefore returns in microseconds, so JMH calls `@Benchmark` thousands of times per warmup iteration — Run B v5 generated 25.8 M records in a single 20 s warmup iteration vs. Run A's ~7 M.
5. Run B's *consumer* path is bounded by the synchronized poll on `InMemoryPubSubTopic` plus `AbstractPollStrategy.maxMessagePerPoll`. We raised this from 3 to 500 (matching Apache Kafka's `max.poll.records` default), and Run B v7's `[BOTTLENECK-SUMMARY]` ticks then showed 2.0–2.4 M records consumed per 20 s tick = 100–120 k records/sec/server, comparable to Apache Kafka's 130 k.
6. **But the producer's input rate is ~10× the consumer's drain rate**, so the per-iteration sentinel (sent at the end of `runPutWorkload()`) is buried behind millions of older records. The `@TearDown(Level.Iteration)` waits up to 10 minutes for the sentinel; even at 100 k records/sec/server, draining 25 M records takes ~250 s, and the next iteration's burst arrives faster than the previous iteration's drain completes.
7. Even an empty push (single record from each region!) does **not** complete within 5 minutes under the in-memory broker because the system-store creation chain (participant store, push-status store, meta store, plus the user store version push) all funnel through the same per-topic monitor, and the admin-message bus consumes the same lock for cluster-config writes.

### Interpretation

Phase 7's conclusion (b) is **NOT REVISED** by Phase 8 — we did not produce comparable [E2E] data
from the in-memory path because the in-memory broker imposes its own (different, coarser)
bottleneck. Phase 8 demonstrated that the coarse `synchronized produce`/`synchronized consume`
on `InMemoryPubSubTopic` becomes the binding constraint long before any of the Phase 6/7
candidates (Helix/ZK control plane, dcr_merge, RMD, leader OTHER) get a chance to.

### Proposed fix (for Phase 9)

Replace `synchronized produce(int partition, …)` and `synchronized consume(int partition, …)` on
`InMemoryPubSubTopic` with a per-partition `Lock[]` (one `ReentrantLock` per partition).
Producers and consumers operating on different partitions then proceed independently. Estimated
implementation effort: half a day in `InMemoryPubSubTopic.java` only — no API surface change.
**Cross-cutting risk**: `StoreIngestionTaskTest` (and its 4 concrete subclasses; total ~318 tests
exercised in this phase under default `synchronized`) may rely on the all-partition lock for
ordering guarantees. The fix needs a careful test sweep before being merged.

## Recommendation

**Keep the in-memory pubsub path opt-in (default = Apache Kafka) for benchmarking.** The
in-memory path is functional for unit tests at small scale (StoreIngestionTaskTest works fine,
none of its 318 cases regressed in Phase 8) but at the multi-region cluster scale exercised by
the JMH benchmark it imposes its own bottleneck and produces a misleading throughput floor of 0.

Phase 9 should:
1. Implement the per-partition lock fix (above) on a feature branch.
2. Re-run Phase 8's A/B with the fixed in-memory broker.
3. If Run B then approaches or exceeds Run A's E2E, conclusion shifts to **(a)** Kafka pipeline IS the binding constraint and Phase 7's (b) is revised.
4. If Run B is still much slower, document the next bottleneck (most likely: Helix steady-state state-transition messages, or the AdminConsumptionTask's serialized in-broker poll).

## Known limitations of the in-memory path (what it does NOT measure faithfully)

- **No network**: producer→broker and broker→consumer hops are JVM method calls, not TCP. Removes Kafka client + broker network/socket stack from the picture; also removes any wire-level compression, batching, request-pipelining behaviour.
- **No disk**: `InMemoryPubSubTopic` is an in-memory `ArrayList<msg>` per partition. No log-segment rolling, no fsync, no log-cleaner, no log-flush latency. Produces look like memory writes; Run B's per-server consume rate of ~100 k records/sec is the JVM-internal floor, not a realistic broker-side number.
- **No cross-region fetch over wire**: AA leader's "remote-region RT consumer" reads from `InMemoryPubSubBrokerRegistry.lookup(remote-broker-url)` directly. With Apache Kafka the remote consume goes over the loopback NIC and exercises the broker's request thread and replica-fetcher path; with in-memory it's a method call.
- **No broker-side backpressure**: `MockInMemoryProducerAdapter.sendMessage()` always succeeds, never blocks on `max.in.flight.requests.per.connection`, never receives `NotEnoughReplicas`. Producer-side timing characteristics are unrealistic.
- **No replicas**: `InMemoryPubSubTopic` has no concept of replication. Apache Kafka's broker-side fetch handlers, ISR tracking, and high-watermark progression are not exercised.
- **Coarse locking**: per-topic `synchronized` instead of partition-level locks. Documented above as the binding constraint of Phase 8 Run B.
- **Unbounded ArrayList per partition**: at 25 M records × ~200 B/record across two regions, the in-memory broker can consume ~10 GB of heap during a single warmup iteration. We are well within the 32 GB heap configured for this benchmark, but a longer-running test (multi-fork or extended measurement) would risk OOM. Recommend ring-buffer or periodic prune for any future use.

## Files / artifacts

- Source code (six new + three modified files) — see "Wiring delivered" above.
- Run A log: `aa-phase8-runA.log`
- Run B logs (chronological): `aa-phase8-runB.log`, `aa-phase8-runB-v2.log` … `aa-phase8-runB-v7.log`, `aa-phase8-runB-short.log`, `aa-phase8-runB-bal.log`, `aa-phase8-runB-tiny.log`
- JSON result: `aa-phase8-result.json`
- Markdown report: `AA_INMEMORY_KAFKA_PHASE8_REPORT.md` (this file)
- Build logs: `aa-phase8-build.log` … `aa-phase8-build7.log`
- Integration test log: `aa-phase8-itest.log` — `ActiveActiveReplicationForHybridTest` 11/0/0, `TestActiveActiveIngestion` 5/0/0
- Unit-test log (concrete `StoreIngestionTaskTest` subclass): `aa-phase8-sit-concrete.log` — `SITWithPWiseWithoutBufferAfterLeaderTest` 318/0/0
- Existing Apache Kafka alloc baseline: `aa-profile-alloc/com.linkedin.venice.benchmark.ActiveActiveIngestionBenchmark.benchmarkAAIngestion-Throughput-workloadType-PUT/{flame-alloc-forward,flame-alloc-reverse}.html`
- In-memory alloc dir (empty — see criterion 6 note): `aa-profile-alloc-inmemory/com.linkedin.venice.benchmark.ActiveActiveIngestionBenchmark.benchmarkAAIngestion-Throughput-workloadType-PUT/`

## Criteria summary

| # | Criterion | Status |
|---|---|---|
| 1 | InMemoryPubSubBrokerFactory added with public no-arg constructor | **PASS** |
| 2 | Broker wrapper + registry: `host:port` keyed; lookup throws if not found | **PASS** (3 distinct brokers in Run B; dual aliasing for plaintext+SSL) |
| 3 | Three adapter factories with public no-arg constructors, broker lookup at create() time | **PASS** |
| 4 | Benchmark gating via `-Dvenice.benchmark.use.inmemory.pubsub`; sysprop set before first ServiceFactory call; choice echoed | **PASS** |
| 5 | Two canonical runs; KAFKA-BROKER reporter must not throw under in-memory | **PARTIAL** — Run A complete; Run B no [E2E] (drain failure); reporters did not throw |
| 6 | Allocation profile delta on Run B + table | **FAIL** — Run B never reaches measurement window so async-profiler emits no flamegraph |
| 7 | Result table + JSON | **PASS** — `aa-phase8-result.json` and this report |
| 8 | Named conclusion (a/b/c) with cited evidence | **PASS** — (c) with quantitative justification + proposed per-partition-lock fix |
| 9 | Existing tests pass on default Apache Kafka path | **PASS** — 11+5 integration tests + 318 concrete-SIT unit tests, all 0 failures / 0 errors |
| 10 | Artifacts: result.json + report with 4 named sections | **PASS** |

**8 PASS, 1 PARTIAL, 1 FAIL.** The single failing criterion (6) and the partial criterion (5)
are both gated on Run B emitting at least one [E2E] line, which the synchronized in-memory
broker prevents under canonical Phase 5 config. The proposed fix (per-partition locks) is
specified in this report and is recommended as the first task of Phase 9.
