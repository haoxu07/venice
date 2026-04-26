package com.linkedin.venice.integration.utils;

import com.linkedin.venice.pubsub.PubSubConsumerAdapterContext;
import com.linkedin.venice.pubsub.PubSubConsumerAdapterFactory;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.mock.BoundedInMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.adapter.consumer.BoundedMockInMemoryConsumerAdapter;
import java.io.IOException;


/**
 * Phase 8 in-memory analogue of
 * {@link com.linkedin.venice.pubsub.adapter.kafka.consumer.ApacheKafkaConsumerAdapterFactory}.
 *
 * <p>Phase 9 update: wires a {@link BoundedMockInMemoryConsumerAdapter} that polls the
 * bounded broker directly. After every successful read, the consumer adapter calls
 * {@code broker.reportConsumerPosition(...)} so the bounded topic can advance the
 * per-partition low-water-mark and free producer slots. This is the missing piece of
 * back-pressure that the Phase 8 unbounded path lacked.
 *
 * <p>Has a public no-arg constructor (required for reflective instantiation by Venice's
 * {@code PubSubClientsFactory.createInstance}) and looks the broker up by address from
 * {@link PubSubConsumerAdapterContext#getPubSubBrokerAddress()} at create() time.
 *
 * <p>Default {@code maxMessagesPerPoll} is 500 -- matches Apache Kafka's default
 * {@code max.poll.records}. Phase 8 raised this from the unit-test default of 3 to keep
 * up with benchmark throughput; the bounded path keeps the same value because the
 * back-pressure mechanism caps in-flight backlog, not per-poll batch size.
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
    BoundedInMemoryPubSubBroker broker = InMemoryPubSubBrokerRegistry.lookup(brokerAddress);
    BoundedMockInMemoryConsumerAdapter consumer =
        new BoundedMockInMemoryConsumerAdapter(broker, BENCHMARK_MAX_MESSAGES_PER_POLL);
    // Wire the same admin adapter the InMemoryAdminAdapterFactory uses, so partitionsFor()
    // resolves topics created via the controller's admin path.
    consumer.setBoundedMockInMemoryAdminAdapter(InMemoryPubSubBrokerRegistry.lookupOrCreateAdmin(brokerAddress));
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
