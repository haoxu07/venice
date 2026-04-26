package com.linkedin.venice.integration.utils;

import com.linkedin.venice.pubsub.PubSubAdminAdapterContext;
import com.linkedin.venice.pubsub.PubSubAdminAdapterFactory;
import com.linkedin.venice.pubsub.api.PubSubAdminAdapter;
import java.io.IOException;


/**
 * Phase 8 in-memory analogue of
 * {@link com.linkedin.venice.pubsub.adapter.kafka.admin.ApacheKafkaAdminAdapterFactory}.
 *
 * <p>Phase 9 update: returns a {@code BoundedMockInMemoryAdminAdapter} bound to the
 * bounded broker, so controller-issued topic CRUD operations modify the same broker that
 * the bounded producer/consumer adapters of this region use.
 *
 * <p>Has a public no-arg constructor (required for reflective instantiation by Venice's
 * {@code PubSubClientsFactory.createInstance}) and looks the broker up by address from
 * {@link PubSubAdminAdapterContext#getPubSubBrokerAddress()} at create() time.
 */
public class InMemoryAdminAdapterFactory extends PubSubAdminAdapterFactory<PubSubAdminAdapter> {
  private static final String NAME = "InMemoryAdminAdapter";

  /** Public no-arg constructor required for reflective instantiation. */
  public InMemoryAdminAdapterFactory() {
  }

  @Override
  public PubSubAdminAdapter create(PubSubAdminAdapterContext adminAdapterContext) {
    String brokerAddress = adminAdapterContext.getPubSubBrokerAddress();
    // Reuse the same admin adapter that InMemoryConsumerAdapterFactory wires into the
    // consumer adapter. The mock's topic CRUD state is held inside the admin adapter
    // (not the broker), so all admin/consumer pairs for the same broker must share the
    // SAME admin instance.
    return InMemoryPubSubBrokerRegistry.lookupOrCreateAdmin(brokerAddress);
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
