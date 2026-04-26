package com.linkedin.venice.integration.utils;

import com.linkedin.venice.pubsub.PubSubConsumerAdapterContext;
import com.linkedin.venice.pubsub.PubSubConsumerAdapterFactory;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.adapter.consumer.MockInMemoryConsumerAdapter;
import com.linkedin.venice.pubsub.mock.adapter.consumer.poll.RandomPollStrategy;
import java.io.IOException;


/**
 * Phase 8: in-memory analogue of
 * {@link com.linkedin.venice.pubsub.adapter.kafka.consumer.ApacheKafkaConsumerAdapterFactory}.
 *
 * <p>Has a public no-arg constructor (required for reflective instantiation by Venice's
 * {@code PubSubClientsFactory.createInstance}) and looks the broker up by address from
 * {@link PubSubConsumerAdapterContext#getPubSubBrokerAddress()} at create() time. Wraps a
 * {@link MockInMemoryConsumerAdapter} that polls the in-memory broker directly using
 * the {@link RandomPollStrategy} (matching the unit-test path used by
 * {@code StoreIngestionTaskTest}). The internal delegate is a {@link NoOpPubSubConsumerAdapter}
 * since the integration cluster doesn't need Mockito-style call verification.
 *
 * <p>Note: {@link com.linkedin.venice.pubsub.mock.adapter.consumer.poll.AbstractPollStrategy}
 * defaults to a {@code maxMessagePerPoll} of 3, which is fine for unit-test paths with
 * 10-100 messages but is the binding throughput cap of the in-memory benchmark path
 * (Phase 8 produces millions of records; at 3 records per poll the consumer cannot
 * possibly drain). We override it to 500 here -- still within the synchronized poll
 * window per partition, but enough that the consumer keeps up with the producer's
 * synchronized produce path. Apache Kafka's default {@code max.poll.records} is 500,
 * so this matches the production-path behaviour as closely as the in-memory mock
 * supports.
 */
public class InMemoryConsumerAdapterFactory extends PubSubConsumerAdapterFactory<PubSubConsumerAdapter> {
  private static final String NAME = "InMemoryConsumerAdapter";
  private static final int BENCHMARK_MAX_MESSAGES_PER_POLL = 500;

  /** Public no-arg constructor required for reflective instantiation. */
  public InMemoryConsumerAdapterFactory() {
  }

  @Override
  public PubSubConsumerAdapter create(PubSubConsumerAdapterContext context) {
    String brokerAddress = context.getPubSubBrokerAddress();
    InMemoryPubSubBroker broker = InMemoryPubSubBrokerRegistry.lookup(brokerAddress);
    MockInMemoryConsumerAdapter consumer = new MockInMemoryConsumerAdapter(
        broker,
        new RandomPollStrategy(BENCHMARK_MAX_MESSAGES_PER_POLL),
        new NoOpPubSubConsumerAdapter());
    // Wire the same admin adapter the InMemoryAdminAdapterFactory uses, so partitionsFor()
    // resolves topics created via the controller's admin path.
    consumer.setMockInMemoryAdminAdapter(InMemoryPubSubBrokerRegistry.lookupOrCreateAdmin(brokerAddress));
    return consumer;
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public void close() throws IOException {
    // No-op; the broker is owned by the wrapper.
  }
}
