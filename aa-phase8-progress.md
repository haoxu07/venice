# Phase 8 — In-Memory Kafka Substitution — Progress

Goal prompt: /home/coder/AA_INMEMORY_KAFKA_PHASE8_PROMPT.md
Branch: haoxu07/aa-ingestion-benchmark
HEAD at start: 9abec747a (Phase 7 progress checkpoint)
Java: JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft

## 2026-04-26T<start> — Phase 8 kicked off
Master spawned subagent with full prompt. Awaiting first checkpoint.

## 2026-04-26T<code> — Code written
Wrote 5 new files in `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/integration/utils/`:
- `InMemoryPubSubBrokerRegistry` — process-wide ConcurrentHashMap<String, InMemoryPubSubBroker>
- `InMemoryPubSubBrokerFactory` (with inner `InMemoryPubSubBrokerWrapper`)
- `InMemoryProducerAdapterFactory`
- `InMemoryConsumerAdapterFactory`
- `InMemoryAdminAdapterFactory`
- `NoOpPubSubConsumerAdapter` (delegate for MockInMemoryConsumerAdapter)

Updated:
- `AaKafkaBrokerReporter` — first-tick gate skips emission when no Kafka broker MBeans (in-memory mode)
- `AaKafkaPipelineReporter` — same first-tick gate, emits one inactivity line then quiet
- `ActiveActiveIngestionBenchmark.setUp()` — reads `-Dvenice.benchmark.use.inmemory.pubsub`, flips `pubSubBrokerFactory` system prop before any ServiceFactory call

Next: build jmhJar to verify compilation.

## 2026-04-26T<build> — Build OK
`./gradlew :internal:venice-test-common:jmhJar` BUILD SUCCESSFUL in 51s. JMH jar regenerated.

## 2026-04-26T<runA-start> — Run A (apache_kafka) launched detached
PID file: aa-phase8-runA.pid; log: aa-phase8-runA.log.
JVM args: -Xms32G -Xmx32G + Phase 0-7 instrumentation flags + phase3.producers.per.region=2 + venice.benchmark.use.inmemory.pubsub=false.
JMH params: -p workloadType=PUT -wi 2 -w 20s -i 2 -r 20s -f 1.

## 2026-04-26T<sit-sanity-start> — SIT sanity launched
First attempt with `--tests "...StoreIngestionTaskTest"` produced 0 tests; re-running with `.*` glob.

## 2026-04-26T<sit-actually> — SIT note
`StoreIngestionTaskTest` is `abstract`; the concrete subclasses are `SITWith{P|S}Wise{And|Without}BufferAfterLeaderTest` (and SAware variants), 4-8 total. Running `SITWithPWiseWithoutBufferAfterLeaderTest` as a representative concrete subclass.

## 2026-04-26T<runA-done> — Run A (apache_kafka) complete in 5m32s
4 [E2E] lines: 128754, 130951, 131292, 130779 ops/s.
Within phase 7 baseline window (132-142k, range allows ~5% off). Median = 130865 ops/s.

## 2026-04-26T<runB-start> — Run B (in_memory) launched detached
Same canonical config; flag flipped to `venice.benchmark.use.inmemory.pubsub=true`.

## 2026-04-26T<runB-debug> — Run B v1 failed: missing admin adapter wiring
`MockInMemoryConsumerAdapter.partitionsFor()` requires its delegate admin adapter to be wired. Fixed: added `lookupOrCreateAdmin()` registry helper and wired in consumer factory.

## 2026-04-26T<runB-v2-debug> — Run B v2 failed: position type ID -42 unknown
Default `RESERVED_POSITION_TYPE_REGISTRY` only has Kafka offset (0), EARLIEST (-2), LATEST (-1). The in-memory factory produces `InMemoryPubSubPosition` (-42), and downstream code (controllers, etc.) couldn't deserialize. Fixed: built a dedicated `IN_MEMORY_PUBSUB_POSITION_TYPE_REGISTRY` containing reserved + -42, and `getAdditionalConfig()` now publishes a merged map containing -42 too.

## 2026-04-26T<runB-v3-debug> — Run B v3 failed: SSL/plaintext admin state split
Each broker registers under TWO addresses (plaintext and SSL); the admin adapters were keyed by address, so dc-1 server (subscribing via SSL port `localhost:36789`) saw an empty topic map while dc-1 controller (using plaintext `localhost:39075`) had created the topic in a different admin adapter instance. Fixed: registry now keys admin adapters by `InMemoryPubSubBroker` (object identity), not address. SSL+plaintext lookups for the same broker share the same admin instance.

## 2026-04-26T<runB-v4-start> — Run B v4 launched
Built jmh jar with all 3 fixes (admin wiring, position type registry, broker-keyed admin sharing). Detached.

## 2026-04-26T<runB-v4-debug> — Run B v4 failed: Samza producer fell back to Apache Kafka
`IntegrationTestPushUtils.getSamzaProducerForStream` (multi-region path) doesn't auto-inject the producer/consumer/admin adapter factory class. Without that, `VeniceWriterFactory` defaults to `ApacheKafkaProducerAdapterFactory`, which then opens a TCP connection to the in-memory broker URL and times out at `getNumberOfPartitions(topicName)`. Fixed: benchmark setUp now passes `pubsub.{producer,consumer,admin}.adapter.factory.class=com.linkedin.venice.integration.utils.InMemory*Factory` as Samza optionalConfigs when `useInMemoryPubSub=true`.

## 2026-04-26T<runB-v5-debug> — Run B v5 hangs at sentinel ingest
With Samza properly wired, Run B v5 produces 25.8M records in warmup1 (4x Run A), but the consumer never catches up: stuck at RT position 1. Cause: `AbstractPollStrategy.maxMessagePerPoll=3` (the unit-test default). At 3 records per poll, the consumer cannot drain millions of records produced under the synchronized in-memory broker. Fixed: factory now creates `RandomPollStrategy(500)` (matching Apache Kafka's default `max.poll.records`).

## 2026-04-26T<runB-v6-start> — Run B v6 launched with poll 500
Detached. If E2E lines emerge within 5 minutes, we're golden.

## 2026-04-26T<plan> — Plan
1. Read prompt + relevant existing source: ServiceFactory, KafkaBrokerFactory, PubSubBrokerWrapper, MockInMemoryProducerAdapter, MockInMemoryAdminAdapter, MockInMemoryConsumerAdapter, PubSubProducerAdapterContext, PubSubClientsFactory. (DONE)
2. Implement in-memory: registry, broker wrapper, broker factory, three adapter factories, no-op consumer delegate. (NEXT)
3. Make AaKafkaBrokerReporter quiet under no-MBeans (broker_count==0).
4. Patch ActiveActiveIngestionBenchmark.setUp() to gate on -Dvenice.benchmark.use.inmemory.pubsub before any ServiceFactory call.
5. Build jmhJar to compile-check.
6. Run StoreIngestionTaskTest sanity (default unit-test path stays green).
7. Run A: apache_kafka detached. Run B: in_memory detached.
8. Run async-profiler alloc on Run B config to aa-profile-alloc-inmemory/.
9. Parse + write json + report.
10. Run integration tests under default Apache Kafka.
11. Commit (no-verify), write DONE.
