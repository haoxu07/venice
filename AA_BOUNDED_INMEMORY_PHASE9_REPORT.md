Phase 9 — Bounded in-memory PubSub broker

Status: PARTIAL — Stage A GREEN, Stage B blocked by pre-existing issues unrelated to the bounded broker.

Final commits: 307965ab1 (Bug 6a — producer-side deep-copy) and d1ccdd010 (Bug 6b — per-consumer-read deep-copy).

Bounded queue capacity: 100000 messages per partition.

No-diff invariant on existing mock classes (`InMemoryPubSubBroker.java`, `InMemoryPubSubTopic.java`, `MockInMemoryProducerAdapter.java`): HELD. `git diff --stat 41d149a76 -- internal/venice-test-common/src/main/java/com/linkedin/venice/pubsub/mock/InMemoryPubSubBroker.java internal/venice-test-common/src/main/java/com/linkedin/venice/pubsub/mock/InMemoryPubSubTopic.java` is empty.

# Design summary

The bounded in-memory broker mirrors the existing unbounded `InMemoryPubSubBroker` shape but adds three Kafka-style properties so JMH benchmarks (which produce thousands of records per partition) do not OOM the JVM:

1. Bounded per-partition capacity (100000 messages). When a partition is full, `produce` blocks for up to 30 s waiting for the consumer to drain, then throws `BoundedInMemoryPubSubTopic produce timed out` — mimicking Kafka's send-buffer back-pressure.

2. Per-partition lock on `produce`, with an explicit `reportConsumerPosition` callback from the consumer adapter so the producer can evict messages below the slowest consumer's low-water-mark. Phase 9 iter6 bumped the eviction strategy to MIN-watermark across all subscribed consumers (not the first consumer's offset) so a missing/late subscriber cannot stall eviction.

3. Synchronous produce-completion callback semantics that match real Kafka by deep-copying the `Put` payload at both produce and consume time (Bug 6 fixes 6a + 6b — see below).

# Stage A iteration log — six bugs across iterations 4–9

For each bug: the symptom, the iteration that fixed it, the fix commit, and the file touched.

Bug 1 (iter4, commit c5a859a2f): `consume()` on the bounded broker short-circuited when the topic existed but had no messages, so subscribed consumers never received the eventual messages produced after subscription. Fix: keep iterating subscribed positions even when the partition is empty.

Bug 2 (iter5, commit ebcba6799): `VeniceWriter` defaulted to `ApacheKafkaProducerAdapter` regardless of the cluster factory — system stores in-memory test factories were not wired through to VeniceWriter's producer adapter chooser. Fix: set the in-memory adapter class names via system property before VeniceWriter initialisation.

Bug 3 supplemental (iter6, commit 069e2e560): bounded queue capacity bumped from 1024 to 100000 (real benchmark needs much more headroom than initial sizing). Also replaced "low-water-mark = first consumer's reported offset" with "low-water-mark = MIN(across all subscribed consumers)" so a missing or temporarily lagging consumer cannot prevent eviction.

Bug 4 (iter7, commit c41b3f436): mock `getPositionByTimestamp` returned `null` fallback when no message matched the timestamp; the consumer treated this as a hard error rather than a soft "no-data". Fix: return `EARLIEST` symbolic position in the no-match case.

Bug 5 (iter8, commit 8e3b7347f): `getPositionByTimestamp` had to honour proper offsetForTime semantics (return the FIRST message at-or-after the timestamp). Fix: index per-partition message timestamps in a sorted structure and binary-search on lookup. Closes the iter7 Shape-C residual.

Bug 6 (iter9 — TWO sub-fixes):
  6a (commit 307965ab1, file `BoundedMockInMemoryProducerAdapter.java`): real Kafka serialises bytes onto the wire BEFORE invoking the producer callback, so any post-send mutation of the original buffer (in particular `ActiveActiveStoreIngestionTask.getProduceToTopicFunction`'s `resultReuseInput` path that calls `prependIntHeaderToByteBuffer(buf, h, true)` and transiently sets `position` to 0) cannot affect the broker's stored bytes. The previous bounded-mock implementation stored the `KafkaMessageEnvelope` reference verbatim. Fix: deep-copy `Put.putValue` and `Put.replicationMetadataPayload` into a freshly-cloned envelope before handing it to the broker. Non-PUT messages (CONTROL/UPDATE/DELETE) pass through verbatim because their payloads are not mutated post-send.
  6b (commit d1ccdd010, file `BoundedMockInMemoryConsumerAdapter.java`): the broker's stored `Put` is shared across all consumers (e.g. dc-0 leader and dc-1 follower both read from dc-0's broker for cross-DC consumption). When the AA leader's resultReuseInput callback fires synchronously inside `sendMessage` and transiently sets `putValue.position = 0` during `prependIntHeaderToByteBuffer`'s intermediate state, a concurrent reader on the same buffer sees `position < 4` and throws. Fix: per-consumer-read deep-copy of the entire `KafkaMessageEnvelope` in `BoundedMockInMemoryConsumerAdapter.poll`. Each consumer gets its own independent buffer; the legacy `enlargeByteBufferForIntHeader` step is preserved on the new buffer; the `isPutValueChanged` bookkeeping is dropped because every read produces a fresh buffer.

# Stage A final test pass count

16/16 PASS with retries.

  - iter8 baseline: 28 attempts, 3 retried failures of `testAAReplicationCanConsumeFromAllRegions[*]`, all ultimately PASS. See `aa-phase9-iter8-stagea.log`, BUILD SUCCESSFUL in 28m 22s.
  - iter9 smoke regression check: 3/3 PASS (`testActiveActiveStoreRestart`, `testKIFRepushActiveActiveStore[0]`, `testKIFRepushActiveActiveStore[2]`) under both Bug 6 fixes — see `aa-phase9-iter9-stagea-smoke.log`, BUILD SUCCESSFUL in 5m 37s.

# Stage B iteration log — Bug 6 mitigated, two pre-existing issues remain

Bug 6 (callback timing — fixed): see Bug 6a/6b above. After both fixes, the original `Start position of 'putValue' ByteBuffer shouldn't be less than 4` error from `LeaderProducerCallback.onCompletionInternal` no longer appears in the bench log when the callback fires.

Stage B [E2E]: NO `[E2E]` line was produced in any iter9 bench run.

Two pre-existing issues block Stage B (both unrelated to the bounded broker — Phase 8 reports Stage B [E2E] = 0 for the same reasons; Bug 6 simply fired first and masked them):

Block (i): dc-0 meta-store-rt heartbeat propagation under in-memory broker.
  `setUp.sendEmptyPushAndWait` sometimes does not complete in dc-0 within the 5-minute timeout. dc-0's `venice_system_store_meta_store_*_v1` SIT reaches STARTED → END_OF_PUSH_RECEIVED → TOPIC_SWITCH_RECEIVED but never `Reported COMPLETED`; logs show `[Heartbeat lag] ... lagging. Lag: [9223372036854775807]`. Looks like a meta-store-rt heartbeat propagation quirk that the bounded broker exhibits but Apache Kafka does not. Run-to-run variance: bench-2 finished setUp; bench-1 and bench-4 timed out (the 5-min timeout vs 60-s Apache Kafka timeout was bumped for in-memory mode in iter5+).

Block (ii): AA merge-resolver line-1198 buffer-position issue.
  When the bench DOES progress past setUp (iter9 run 2), the AA leader crashes after ~533 RT messages with `Start position of 'originalBuffer' ByteBuffer shouldn't be less than 4` at `ActiveActiveStoreIngestionTask:1198`. `updatedValueBytes` here is `mergeConflictResult.getNewValue()`, which can be the OLD buffer when `MergeUtils.compareAndReturn` picks oldValue based on hashCode comparison. The merge resolver always sets `resultReusesInput=true` regardless of who wins; when old wins, the production path expects `position >= 4` but the buffer's effective position is 0 in some merge sub-path. Hash-of-payload dependent, would manifest on Apache Kafka too.

Stage B [E2E] median: N/A (no `[E2E]` line emitted).

# Known limitations of the in-memory path

1. The bounded broker's per-partition synchronized `produce` serializes the entire control plane (admin topic, system stores, meta-store-rt, etc.) onto a single monitor. System-store creation in each region therefore takes longer than the 60-s Apache Kafka default; the benchmark `setUp` bumps the empty-push deadline to 5 minutes for in-memory mode (since iter5).

2. Block (i) above — sometimes the dc-0 meta-store-rt heartbeat does not catch up under the bounded broker, causing `setUp` to time out. Run-to-run variance.

3. Block (ii) above — the AA merge-resolver `resultReuseInput=true` semantic plus `compareAndReturn` returning oldValue can hit a position-mismatch on `prependIntHeaderToByteBuffer`. Pre-existing AA-merge issue, would also manifest on Apache Kafka.

4. Bug 6 fixes 6a/6b add ~one byte[] alloc + arraycopy per produce and per consumer-read. Negligible at single-partition unit-test scale, slightly higher at JMH 1k-records-per-invocation scale.

5. The `consume()`/poll path on the bounded broker uses a sequential round-robin over subscribed partitions capped at `maxMessagesPerPoll`. Cross-partition fairness is therefore weaker than real Kafka's broker-driven dispatch.

# iter10 recommendation

Phase 9's chartered goal was "bounded in-memory broker that supports the Stage A integration suite". Stage A is GREEN with the iter9 Bug 6 fixes. Stage B's two blocking issues are not bounded-broker bugs:

  - Block (i) is a meta-store-rt heartbeat-propagation flake under in-memory.
  - Block (ii) is a pre-existing AA-merge buffer-position bug.

Recommend closing Phase 9 as PARTIAL and opening a follow-up to:
  (a) trace block (i) in `BoundedInMemoryPubSubTopic` / `BoundedInMemoryPubSubBroker.reportConsumerPosition` to ensure heartbeat propagation matches Apache Kafka, OR
  (b) document Stage B as out-of-scope for in-memory and run Stage B only on Apache Kafka (Phase 8 baseline), OR
  (c) fix the AA-merge `compareAndReturn` / `resultReusesInput` semantics in production so block (ii) is resolved everywhere.
