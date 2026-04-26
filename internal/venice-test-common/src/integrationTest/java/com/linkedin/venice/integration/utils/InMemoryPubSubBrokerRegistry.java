package com.linkedin.venice.integration.utils;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.adapter.admin.MockInMemoryAdminAdapter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Phase 8: process-wide registry of {@link InMemoryPubSubBroker} instances keyed by their
 * "host:port" address. Required because every Venice component in the integration-test
 * cluster (controllers, servers, routers, Samza producers) instantiates its pubsub
 * adapters reflectively from a {@code VeniceProperties} bag and only knows the bootstrap
 * server address string -- they do NOT share a Java reference to the wrapped broker
 * object. This registry lets the in-memory adapter factories look the broker up by URL.
 *
 * <p>Lifecycle: brokers register on {@link InMemoryPubSubBrokerWrapper#internalStart()}
 * and deregister on {@link InMemoryPubSubBrokerWrapper#internalStop()}. The registry is
 * a static singleton because the JMH JVM hosts both ends of the pubsub conversation
 * inside the same process.
 *
 * <p>This class is benchmark/test-only -- it is never on the production code path.
 */
public final class InMemoryPubSubBrokerRegistry {
  private static final Logger LOGGER = LogManager.getLogger(InMemoryPubSubBrokerRegistry.class);

  private static final ConcurrentMap<String, InMemoryPubSubBroker> BROKERS_BY_ADDRESS = new ConcurrentHashMap<>();
  // Admin adapters are shared PER BROKER (not per address) so the consumer adapter factory
  // can wire its MockInMemoryConsumerAdapter to the same admin instance the controllers/
  // admin path uses to register topics, regardless of whether the lookup uses the
  // plaintext or SSL address (they map to the same broker). MockInMemoryConsumerAdapter
  // .partitionsFor() delegates to the admin adapter, and that wiring is required for
  // Venice's TopicMetadataFetcher.
  private static final ConcurrentMap<InMemoryPubSubBroker, MockInMemoryAdminAdapter> ADMIN_ADAPTERS_BY_BROKER =
      new ConcurrentHashMap<>();

  private InMemoryPubSubBrokerRegistry() {
  }

  /**
   * Register a broker under the given address. Called by
   * {@link InMemoryPubSubBrokerWrapper#internalStart()}.
   */
  public static void register(String address, InMemoryPubSubBroker broker) {
    InMemoryPubSubBroker existing = BROKERS_BY_ADDRESS.putIfAbsent(address, broker);
    if (existing != null && existing != broker) {
      throw new VeniceException(
          "InMemoryPubSubBrokerRegistry already has a different broker registered at address " + address);
    }
    LOGGER.info("Registered InMemoryPubSubBroker at address={} (total={})", address, BROKERS_BY_ADDRESS.size());
  }

  /**
   * Deregister a broker by address. Called by
   * {@link InMemoryPubSubBrokerWrapper#internalStop()}.
   * <p>The shared admin adapter for a broker is removed when the LAST address for that
   * broker is deregistered (i.e. when the broker is no longer reachable from any URL).
   */
  public static void deregister(String address) {
    InMemoryPubSubBroker removed = BROKERS_BY_ADDRESS.remove(address);
    if (removed != null) {
      // If no remaining address still points to this broker, drop its admin adapter too.
      if (!BROKERS_BY_ADDRESS.containsValue(removed)) {
        ADMIN_ADAPTERS_BY_BROKER.remove(removed);
      }
      LOGGER.info("Deregistered InMemoryPubSubBroker at address={} (total={})", address, BROKERS_BY_ADDRESS.size());
    }
  }

  /**
   * Look up (lazily creating) the {@link MockInMemoryAdminAdapter} bound to the broker at the
   * given address. Shared across all consumer + admin adapter factories so the
   * {@link com.linkedin.venice.pubsub.mock.adapter.consumer.MockInMemoryConsumerAdapter}
   * issued from {@link InMemoryConsumerAdapterFactory} sees the same topic
   * registration as the {@link com.linkedin.venice.pubsub.mock.adapter.admin.MockInMemoryAdminAdapter}
   * issued from {@link InMemoryAdminAdapterFactory}.
   *
   * <p>Note: the returned instance overrides {@link MockInMemoryAdminAdapter#close()} to a
   * no-op, because Venice components routinely close their admin adapter at shutdown and
   * the unmodified parent class clears its topic maps on close. Since the registry
   * intentionally shares ONE admin adapter per broker across ALL components in the cluster,
   * letting any one of them clear the state would invalidate every other component's
   * topic visibility. Real cleanup happens when {@link #deregister(String)} runs.
   */
  public static MockInMemoryAdminAdapter lookupOrCreateAdmin(String address) {
    InMemoryPubSubBroker broker = lookup(address);
    return ADMIN_ADAPTERS_BY_BROKER.computeIfAbsent(broker, b -> new MockInMemoryAdminAdapter(b) {
      @Override
      public void close() {
        // Intentionally a no-op: shared instance, owned by the registry.
      }
    });
  }

  /**
   * Look up the broker registered at the given address.
   *
   * @throws VeniceException if no broker is registered at the address (typically a wiring
   *                         bug -- the controller/server is dialing an address that no
   *                         in-memory broker wrapper exposed).
   */
  public static InMemoryPubSubBroker lookup(String address) {
    InMemoryPubSubBroker broker = BROKERS_BY_ADDRESS.get(address);
    if (broker == null) {
      throw new VeniceException(
          "No InMemoryPubSubBroker registered at address=" + address + ". Known addresses: "
              + BROKERS_BY_ADDRESS.keySet());
    }
    return broker;
  }

  /**
   * Test-only: return the count of currently registered brokers. Used by Phase 8
   * verification to confirm the multi-region wrapper produces 2-3 distinct brokers.
   */
  public static int size() {
    return BROKERS_BY_ADDRESS.size();
  }

  /**
   * Test-only: return the set of currently registered broker addresses (for debug/log).
   */
  public static java.util.Set<String> knownAddresses() {
    return java.util.Collections.unmodifiableSet(BROKERS_BY_ADDRESS.keySet());
  }
}
