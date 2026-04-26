package com.linkedin.venice.integration.utils;

import com.linkedin.venice.pubsub.PubSubProducerAdapterContext;
import com.linkedin.venice.pubsub.PubSubProducerAdapterFactory;
import com.linkedin.venice.pubsub.api.PubSubProducerAdapter;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.adapter.producer.MockInMemoryProducerAdapter;
import java.io.IOException;


/**
 * Phase 8: in-memory analogue of
 * {@link com.linkedin.venice.pubsub.adapter.kafka.producer.ApacheKafkaProducerAdapterFactory}.
 *
 * <p>Has a public no-arg constructor (required for reflective instantiation by Venice's
 * {@code PubSubClientsFactory.createInstance}) and looks the broker up by address from
 * {@link PubSubProducerAdapterContext#getBrokerAddress()} at create() time. Wraps a
 * {@link MockInMemoryProducerAdapter} that synchronously appends messages to an
 * in-memory topic and immediately fires the producer callback + a completed future --
 * eliminating the Apache Kafka client and broker network/disk path entirely.
 */
public class InMemoryProducerAdapterFactory extends PubSubProducerAdapterFactory<PubSubProducerAdapter> {
  private static final String NAME = "InMemoryProducerAdapter";

  /** Public no-arg constructor required for reflective instantiation. */
  public InMemoryProducerAdapterFactory() {
  }

  @Override
  public PubSubProducerAdapter create(PubSubProducerAdapterContext context) {
    String brokerAddress = context.getBrokerAddress();
    InMemoryPubSubBroker broker = InMemoryPubSubBrokerRegistry.lookup(brokerAddress);
    return new MockInMemoryProducerAdapter(broker);
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
