package com.linkedin.venice.integration.utils;

import com.linkedin.venice.pubsub.PubSubProducerAdapterContext;
import com.linkedin.venice.pubsub.PubSubProducerAdapterFactory;
import com.linkedin.venice.pubsub.api.PubSubProducerAdapter;
import com.linkedin.venice.pubsub.mock.BoundedInMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.adapter.producer.BoundedMockInMemoryProducerAdapter;
import java.io.IOException;


/**
 * Phase 8 in-memory analogue of
 * {@link com.linkedin.venice.pubsub.adapter.kafka.producer.ApacheKafkaProducerAdapterFactory}.
 *
 * <p>Phase 9 update: wires a {@link BoundedMockInMemoryProducerAdapter} that targets the
 * bounded broker, so producers experience Kafka-style back-pressure when a partition
 * queue fills (capacity 10000 by default). The in-memory unit-test path is unaffected --
 * it instantiates {@code MockInMemoryProducerAdapter} directly.
 *
 * <p>Has a public no-arg constructor (required for reflective instantiation by Venice's
 * {@code PubSubClientsFactory.createInstance}) and looks the broker up by address from
 * {@link PubSubProducerAdapterContext#getBrokerAddress()} at create() time.
 */
public class InMemoryProducerAdapterFactory extends PubSubProducerAdapterFactory<PubSubProducerAdapter> {
  private static final String NAME = "InMemoryProducerAdapter";

  /** Public no-arg constructor required for reflective instantiation. */
  public InMemoryProducerAdapterFactory() {
  }

  @Override
  public PubSubProducerAdapter create(PubSubProducerAdapterContext context) {
    String brokerAddress = context.getBrokerAddress();
    BoundedInMemoryPubSubBroker broker = InMemoryPubSubBrokerRegistry.lookup(brokerAddress);
    return new BoundedMockInMemoryProducerAdapter(broker);
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
