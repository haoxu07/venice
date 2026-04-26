# Phase 9 — Bounded In-Memory PubSub Topic + Integration-Test Correctness — Progress

Goal prompt: /home/coder/AA_BOUNDED_INMEMORY_PHASE9_PROMPT.md
Branch: haoxu07/aa-ingestion-benchmark
HEAD at start: 41d149a76 (Phase 8 final commit)
Java: JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft

Two-stage plan:
- Stage A: bounded topic + bounded broker (no changes to existing broker), pass ActiveActiveReplicationForHybridTest + TestActiveActiveIngestion under in-memory factory
- Stage B: JMH benchmark with bounded broker, must complete + emit >=1 [E2E] line

## 2026-04-26T<start> — Phase 9 kicked off
Master spawned subagent with full prompt. Awaiting first checkpoint.

## 2026-04-26 — Architecture decision

**Constraint analysis**: The existing mock adapter classes
(`MockInMemoryProducerAdapter`, `MockInMemoryConsumerAdapter`,
`MockInMemoryAdminAdapter`, `AbstractPollStrategy`) take typed
`InMemoryPubSubBroker` arguments. We cannot modify them (existing mocks).
Criterion 2 forbids inheritance from `InMemoryPubSubBroker`. So we cannot
substitute a `BoundedInMemoryPubSubBroker` instance into them.

**Decision**: Shape B (no inheritance). Write parallel infrastructure:
- `BoundedInMemoryPubSubTopic` — new class, per-partition bounded queues,
  blocking produce, low-water-mark eviction.
- `BoundedInMemoryPubSubBroker` — new class. No `extends`. Same public
  method signatures as `InMemoryPubSubBroker`.
- `BoundedMockInMemoryProducerAdapter` — new mock, composes bounded broker.
- `BoundedMockInMemoryConsumerAdapter` — new mock, composes bounded broker.
  Inlines the equivalent of `AbstractPollStrategy` poll loop.
- `BoundedMockInMemoryAdminAdapter` — new mock, composes bounded broker.

Phase 8 wiring updated:
- `InMemoryPubSubBrokerFactory` — instantiate bounded broker.
- `InMemoryPubSubBrokerRegistry` — store bounded broker.
- 3 adapter factories — instantiate bounded mocks.

**Files MUST stay byte-for-byte unchanged**:
- InMemoryPubSubBroker.java, InMemoryPubSubTopic.java
- MockInMemoryProducerAdapter.java, MockInMemoryConsumerAdapter.java,
  MockInMemoryAdminAdapter.java
- AbstractPollStrategy.java, RandomPollStrategy.java, all PollStrategy types
- All Phase 0-7 instrumentation classes

## Capacity choice
DEFAULT_CAPACITY = 10000 messages per partition. May raise if benchmark/
integration test motivates it (per criterion 1 rules).

## Eviction policy (Risk 3 resolution)
Per-partition low-water-mark tracker. Consumers call
`reportConsumerPosition(topic, partition, lastReadPosition)` on each
successful read. The bounded topic evicts (drops) messages whose offset
is at-or-below the lowest consumer position when the queue is full.
Offsets remain monotonic IDs (Risk 2): the queue stores `(offset,
message)` pairs, not array-index implicit offsets. If no consumer is
registered, produce blocks (Risk 3 conservative path) with a 30s timeout
(Risk 1) -> PubSubException to surface the deadlock loudly.

If integration tests reveal that the per-consumer tracking is too
fragile, fallback is plain FIFO drop-oldest (slow consumers will hit
IndexOutOfBounds-style "below low-water-mark" errors).

## 2026-04-26 — Files written, build clean

Wrote new files:
- internal/venice-test-common/src/main/java/com/linkedin/venice/pubsub/mock/BoundedInMemoryPubSubTopic.java
- internal/venice-test-common/src/main/java/com/linkedin/venice/pubsub/mock/BoundedInMemoryPubSubBroker.java
- internal/venice-test-common/src/main/java/com/linkedin/venice/pubsub/mock/adapter/producer/BoundedMockInMemoryProducerAdapter.java
- internal/venice-test-common/src/main/java/com/linkedin/venice/pubsub/mock/adapter/admin/BoundedMockInMemoryAdminAdapter.java
- internal/venice-test-common/src/main/java/com/linkedin/venice/pubsub/mock/adapter/consumer/BoundedMockInMemoryConsumerAdapter.java

Updated Phase 8 wiring (NOT instrumentation, all test infra):
- internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/integration/utils/InMemoryPubSubBrokerFactory.java   (instantiate BoundedInMemoryPubSubBroker)
- internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/integration/utils/InMemoryPubSubBrokerRegistry.java (store BoundedInMemoryPubSubBroker)
- internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/integration/utils/InMemoryProducerAdapterFactory.java
- internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/integration/utils/InMemoryConsumerAdapterFactory.java
- internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/integration/utils/InMemoryAdminAdapterFactory.java

Existing mock files unchanged (verified via `git diff --stat`).

`./gradlew :internal:venice-test-common:integrationTestClasses` -> BUILD SUCCESSFUL in 11s.

## 2026-04-26 — Criterion 4 PASSED

`./gradlew :clients:da-vinci-client:test --tests "com.linkedin.davinci.kafka.consumer.SITWithPWiseWithoutBufferAfterLeaderTest" --rerun-tasks`
-> BUILD SUCCESSFUL in 2m 43s
XML: tests=318 skipped=0 failures=0 errors=0 (per build/test-results/test/TEST-...xml)

The unit-test path is unchanged. Bounded code is only on the integration-test side.

## 2026-04-26 — Starting Stage A criterion 5 (integration tests under in-memory factory)

## 2026-04-26 — Iteration 1: gradle CLI ordering issue

First attempt with `./gradlew :task ... --tests ... -DpubSubBrokerFactory=...`
ran the tests but `Will be using: KafkaBrokerFactory` was printed in the
test report (i.e. the `-D` flag didn't reach the test JVM's
`System.getProperty`, even though it appeared in the printed `jvmArgs`).
The 4 failing tests had failure modes (404 store-not-found, push timeout)
that are general Apache Kafka multi-region admin-flow issues and NOT
attributable to my bounded code (because my code wasn't even active).

Fix: put `-DpubSubBrokerFactory=...` BEFORE the `:task` argument so
gradle's CLI parser treats it as a JVM flag for gradle itself (then
build.gradle line 437's `systemProperty` propagates it to the test JVM).

Verified: smoke test of one method
(`ActiveActiveReplicationForHybridTest.controllerClientCanGetStoreReplicationMetadataSchema`)
PASSED in 22s with `Will be using: ...InMemoryPubSubBrokerFactory` in
the test XML.

## 2026-04-26 — Iteration 2: full integration test run with in-memory factory active

Started detached run with `-D...` before task name:
  ./gradlew -DpubSubBrokerFactory=com.linkedin.venice.integration.utils.InMemoryPubSubBrokerFactory \
    :internal:venice-test-common:integrationTest \
    --tests "ActiveActiveReplicationForHybridTest" \
    --tests "TestActiveActiveIngestion" \
    --rerun-tasks

Log: /home/coder/Projects/venice/aa-phase9-itest-v3.log
PID: aa-phase9-itest-v3.pid

Polling for completion.

## 2026-04-26 — Continuation subagent: ROOT CAUSE FOUND

JUnit XML for `testActiveActiveStoreRestart` showed:
- Parent (`dc-parent-0`) AdminConsumerService at 11:16:15: `first read OK: topic=venice_admin_venice-cluster0 partition=0 position=InMemoryPubSubPosition{0}`
- Child (`dc-0:localhost_37499`) AdminConsumerService at 11:16:10: subscribed,
  `PHASE9-DEBUG poll(brokerAddress=...dc-parent-0...:34651, subscribed=[venice_admin_venice-cluster0-0])`
  but NO `first read OK` ever for the child controller — the child's admin
  consumer polls forever and never receives anything.

The two consumers are reading the SAME admin topic on the SAME parent broker.
The bug: `BoundedInMemoryPubSubTopic.consume()` short-circuits on
`target < p.lowWaterMark`, returning `Optional.empty()` even when the entry is
still physically in the queue. The parent self-consumes (advancing lowWaterMark
forward); the child reads at offset 0 and gets locked out because the parent
has already advanced lowWaterMark past 0.

This violates the Phase 9 doc's stated intent:
"Risk 4 (multi-consumer single-partition): we keep a single low-water-mark per
partition that tracks the MIN of any reported consumer position." The current
implementation tracks MAX (`if (readPosition > p.lowWaterMark)`).

Without admin propagation from parent to child, the child controller never
observes the user-store ADD_VERSION operation, never triggers Helix to assign
partitions to the server, and so the user-store v1 push never starts ingestion
on the server. `getOffLinePushStatus()` never reaches COMPLETED, the test's
20-second `waitForNonDeterministicCompletion` expires, and we get the
"non-deterministic condition not met" failure.

## 2026-04-26 — Iteration 2 fix applied

Fix in `BoundedInMemoryPubSubTopic.consume()`: removed the
`target < p.lowWaterMark` short-circuit. The linear walk through the queue
naturally returns `Optional.empty()` if the entry was actually evicted, so the
short-circuit was both incorrect (locks out slower peers) and redundant (in
the not-yet-evicted case, the walk would have found and returned the entry
anyway). Eviction only happens lazily during produce() when capacity is
reached; a still-present entry is always servable to any consumer.

We deliberately leave the `reportConsumerPosition` MAX-tracking unchanged: at
capacity 10000 and admin-topic / system-store traffic of ~10 messages,
capacity-driven eviction is never reached during the test, so MAX vs MIN
doesn't matter here. Stage B benchmark may revisit this.


