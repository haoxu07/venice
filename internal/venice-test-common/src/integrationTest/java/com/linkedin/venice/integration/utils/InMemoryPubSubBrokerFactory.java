package com.linkedin.venice.integration.utils;

import static com.linkedin.venice.integration.utils.ProcessWrapper.DEFAULT_HOST_NAME;

import com.linkedin.venice.ConfigKeys;
import com.linkedin.venice.pubsub.PubSubClientsFactory;
import com.linkedin.venice.pubsub.PubSubPositionTypeRegistry;
import com.linkedin.venice.pubsub.mock.BoundedInMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubPosition;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubPositionFactory;
import com.linkedin.venice.utils.SslUtils;
import com.linkedin.venice.utils.SslUtils.VeniceTlsConfiguration;
import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.VeniceProperties;
import java.io.File;
import java.util.Collections;
import java.util.Map;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Phase 8: in-memory drop-in replacement for {@link KafkaBrokerFactory}, used to remove
 * the in-process Apache Kafka broker (and its ~30% allocation tax + co-resident IO/network
 * threads) from the AA ingestion JMH benchmark and isolate the true non-Kafka cap.
 *
 * <p>Wired via the {@code pubSubBrokerFactory} system property:
 * <pre>
 *   System.setProperty("pubSubBrokerFactory",
 *     "com.linkedin.venice.integration.utils.InMemoryPubSubBrokerFactory");
 * </pre>
 * which must be set BEFORE {@link ServiceFactory}'s static initializer fires (i.e. before
 * any {@code ServiceFactory.*} call). The benchmark's {@code setUp()} flips this when
 * the system property {@code venice.benchmark.use.inmemory.pubsub=true}.
 *
 * <p>Default behaviour for ALL non-benchmark integration tests is preserved: the
 * {@code pubSubBrokerFactory} sysprop is unset, so {@link KafkaBrokerFactory} (Apache Kafka)
 * is used. This factory is opt-in.
 *
 * <p>Each cluster wrapper invocation produces one broker wrapper; the multi-region cluster
 * spins up 3 (parent + 2 child regions). Each broker wrapper allocates its own
 * {@code host:freePort} address and registers itself in {@link InMemoryPubSubBrokerRegistry}.
 * The three adapter factories (producer/consumer/admin) look the broker up by address at
 * {@code create()} time -- so controllers, servers, and Samza producers in a region all
 * dial the same address and therefore find the same broker, and cross-region traffic
 * works transparently because the consumer's "remote broker URL" is just another lookup
 * key in the same registry.
 */
public class InMemoryPubSubBrokerFactory implements PubSubBrokerFactory<InMemoryPubSubBrokerFactory.InMemoryPubSubBrokerWrapper> {
  private static final Logger LOGGER = LogManager.getLogger(InMemoryPubSubBrokerFactory.class);
  public static final String SERVICE_NAME = "InMemoryPubSub";

  // Phase 9 (bench, iter5): broadcast the matching client adapter class names as JVM-wide system
  // properties so that callsites which build a VeniceWriterFactory without explicit producer/admin
  // factory configs (notably the Samza VeniceSystemProducer in integration tests, which only sets
  // KAFKA_BOOTSTRAP_SERVERS / PUBSUB_BROKER_ADDRESS in its writer Properties) still pick up the
  // in-memory adapter. Without this, the default ApacheKafkaProducerAdapterFactory is used and
  // dials the in-memory broker URL with the Kafka protocol, which hangs (Bug 2 from iter4).
  // PubSubClientsFactory.createFactory was extended in iter5 to fall back to System.getProperty
  // before the hardcoded default class.
  static {
    setSystemPropertyIfAbsent(
        com.linkedin.venice.ConfigKeys.PUB_SUB_PRODUCER_ADAPTER_FACTORY_CLASS,
        InMemoryProducerAdapterFactory.class.getName());
    setSystemPropertyIfAbsent(
        com.linkedin.venice.ConfigKeys.PUB_SUB_CONSUMER_ADAPTER_FACTORY_CLASS,
        InMemoryConsumerAdapterFactory.class.getName());
    setSystemPropertyIfAbsent(
        com.linkedin.venice.ConfigKeys.PUB_SUB_ADMIN_ADAPTER_FACTORY_CLASS,
        InMemoryAdminAdapterFactory.class.getName());
    setSystemPropertyIfAbsent(
        com.linkedin.venice.ConfigKeys.PUB_SUB_SOURCE_OF_TRUTH_ADMIN_ADAPTER_FACTORY_CLASS,
        InMemoryAdminAdapterFactory.class.getName());
    LOGGER.info(
        "Phase 9 iter5: set in-memory client adapter factory system properties (producer/consumer/admin).");
  }

  private static void setSystemPropertyIfAbsent(String key, String value) {
    if (System.getProperty(key) == null) {
      System.setProperty(key, value);
    }
  }

  // Anchor: a single PubSubClientsFactory instance shared by every broker wrapper this
  // factory produces. The three adapter factories inside it have public no-arg constructors
  // and look the broker up by address at create() time, so they don't need to be re-built
  // per-broker.
  private static final PubSubClientsFactory IN_MEMORY_CLIENTS_FACTORY = new PubSubClientsFactory(
      new InMemoryProducerAdapterFactory(),
      new InMemoryConsumerAdapterFactory(),
      new InMemoryAdminAdapterFactory());

  // Position-type registry used by every component that talks to the in-memory broker.
  // Carries the reserved entries (EARLIEST=-2, LATEST=-1, ApacheKafkaOffset=0) AND the
  // in-memory entry (-42). The reserved entries are required so the registry-merging
  // validation in PubSubPositionTypeRegistry's constructor doesn't reject this map. The
  // in-memory entry is required so MockInMemoryProducerAdapter's positions round-trip
  // through Venice's checkpoint metadata.
  private static final PubSubPositionTypeRegistry IN_MEMORY_PUBSUB_POSITION_TYPE_REGISTRY =
      buildInMemoryPositionTypeRegistry();

  private static PubSubPositionTypeRegistry buildInMemoryPositionTypeRegistry() {
    it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<String> map =
        new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(4);
    PubSubPositionTypeRegistry.RESERVED_POSITION_TYPE_ID_TO_CLASS_NAME_MAP.forEach(map::put);
    map.put(InMemoryPubSubPosition.INMEMORY_PUBSUB_POSITION_TYPE_ID, InMemoryPubSubPositionFactory.class.getName());
    return new PubSubPositionTypeRegistry(map);
  }

  /** Public no-arg constructor required for reflective instantiation by {@link ServiceFactory}. */
  public InMemoryPubSubBrokerFactory() {
  }

  @Override
  public StatefulServiceProvider<InMemoryPubSubBrokerWrapper> generateService(PubSubBrokerConfigs configs) {
    return (String serviceName, File dir) -> {
      int port = TestUtils.getFreePort();
      int sslPort = TestUtils.getFreePort();
      String address = DEFAULT_HOST_NAME + ":" + port;
      BoundedInMemoryPubSubBroker broker = new BoundedInMemoryPubSubBroker(SERVICE_NAME + "-" + configs.getRegionName());
      LOGGER.info(
          "InMemoryPubSubBroker for region:{} url: {} (sslPort={})",
          configs.getRegionName(),
          address,
          sslPort);
      return new InMemoryPubSubBrokerWrapper(
          broker,
          dir,
          configs.getRegionName(),
          configs.getClusterName(),
          DEFAULT_HOST_NAME,
          port,
          sslPort);
    };
  }

  @Override
  public String getServiceName() {
    return SERVICE_NAME;
  }

  @Override
  public PubSubClientsFactory getClientsFactory() {
    return IN_MEMORY_CLIENTS_FACTORY;
  }

  /**
   * In-memory analogue of {@link KafkaBrokerFactory.KafkaBrokerWrapper}. Carries an
   * {@link InMemoryPubSubBroker} and a synthetic {@code host:port} pair that downstream
   * Venice services use as their bootstrap-servers config. On start it registers itself
   * with {@link InMemoryPubSubBrokerRegistry}; on stop it deregisters.
   */
  public static class InMemoryPubSubBrokerWrapper extends PubSubBrokerWrapper {
    private static final Logger LOGGER = LogManager.getLogger(InMemoryPubSubBrokerWrapper.class);

    private final BoundedInMemoryPubSubBroker broker;
    private final String regionName;
    private final String pubSubClusterName;
    private final String host;
    private final int port;
    private final int sslPort;
    private final VeniceTlsConfiguration tlsConfiguration;

    InMemoryPubSubBrokerWrapper(
        BoundedInMemoryPubSubBroker broker,
        File dataDirectory,
        String regionName,
        String pubSubClusterName,
        String host,
        int port,
        int sslPort) {
      super(SERVICE_NAME + "-" + regionName, dataDirectory);
      this.broker = broker;
      this.regionName = regionName;
      this.pubSubClusterName = pubSubClusterName;
      this.host = host;
      this.port = port;
      this.sslPort = sslPort;
      // SSL is faked: the TLS config is a synthetic local one that the in-memory adapters
      // ignore. Cluster wrappers may insist on a non-null VeniceTlsConfiguration for SSL
      // ports, so we provide one.
      this.tlsConfiguration = SslUtils.getTlsConfiguration();
    }

    @Override
    public String getHost() {
      return host;
    }

    @Override
    public int getPort() {
      return port;
    }

    @Override
    public int getSslPort() {
      return sslPort;
    }

    @Override
    public VeniceTlsConfiguration getTlsConfiguration() {
      return tlsConfiguration;
    }

    @Override
    public String getAddress() {
      return host + ":" + port;
    }

    @Override
    protected void internalStart() {
      InMemoryPubSubBrokerRegistry.register(getAddress(), broker);
      // Also register the SSL address as an alias for the same broker, in case any
      // downstream Venice path resolves the SSL bootstrap and looks it up here.
      InMemoryPubSubBrokerRegistry.register(getSSLAddress(), broker);
      LOGGER.info("Started InMemoryPubSubBrokerWrapper at {} (sslAddress={})", getAddress(), getSSLAddress());
    }

    @Override
    protected void internalStop() {
      InMemoryPubSubBrokerRegistry.deregister(getAddress());
      InMemoryPubSubBrokerRegistry.deregister(getSSLAddress());
    }

    @Override
    protected void newProcess() {
      // No-op: the in-memory broker is just a Java object; no separate process to restart.
    }

    @Override
    public PubSubClientsFactory getPubSubClientsFactory() {
      return IN_MEMORY_CLIENTS_FACTORY;
    }

    @Override
    public String getRegionName() {
      return regionName;
    }

    @Override
    public String getPubSubClusterName() {
      return pubSubClusterName;
    }

    public BoundedInMemoryPubSubBroker getBroker() {
      return broker;
    }

    /**
     * Publish the position-type-id-to-class-name map so downstream consumers can resolve
     * position type ids. The reserved entries (EARLIEST=-1, LATEST=-2, ApacheKafka=0) are
     * preserved AND the in-memory entry (-42) is added so the in-memory broker's positions
     * (returned by {@link com.linkedin.venice.pubsub.mock.adapter.producer.MockInMemoryProducerAdapter})
     * round-trip cleanly through Venice's checkpoint/offset metadata.
     */
    @Override
    public Map<String, String> getAdditionalConfig() {
      Map<String, String> mergedTypeMap = new java.util.HashMap<>();
      // Reserved entries first (Kafka offset, EARLIEST/LATEST symbolic).
      PubSubPositionTypeRegistry.RESERVED_POSITION_TYPE_ID_TO_CLASS_NAME_MAP
          .forEach((typeId, className) -> mergedTypeMap.put(Integer.toString(typeId), className));
      // Add in-memory.
      mergedTypeMap.put(
          Integer.toString(InMemoryPubSubPosition.INMEMORY_PUBSUB_POSITION_TYPE_ID),
          InMemoryPubSubPositionFactory.class.getName());
      return Collections.singletonMap(
          ConfigKeys.PUBSUB_TYPE_ID_TO_POSITION_CLASS_NAME_MAP,
          VeniceProperties.mapToString(mergedTypeMap));
    }

    @Override
    public PubSubPositionTypeRegistry getPubSubPositionTypeRegistry() {
      // Use a registry that contains BOTH the reserved entries AND the in-memory factory.
      // Built once and cached statically.
      return IN_MEMORY_PUBSUB_POSITION_TYPE_REGISTRY;
    }

    @Override
    public String toString() {
      return "InMemoryPubSubBrokerWrapper{address='" + getAddress() + "', sslAddress='" + getSSLAddress() + "'}";
    }
  }
}
