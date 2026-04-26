package com.linkedin.venice.pubsub.mock;

import com.linkedin.venice.utils.TestUtils;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Phase 9: bounded in-memory pubsub broker.
 *
 * <p>This is a self-contained NEW class. It does NOT extend or otherwise inherit from
 * {@link InMemoryPubSubBroker}. It exposes the same public method surface
 * ({@code createTopic}, {@code produce}, {@code consume}, {@code getPartitionCount},
 * {@code endOffsets}, {@code endPosition}, {@code getPubSubBrokerAddress}, {@code getPort})
 * so the bounded mock adapter classes can call into it identically — but the static type
 * at every callsite that needs back-pressure is this class, not the unbounded one. The
 * existing {@link InMemoryPubSubBroker} is unchanged and continues to serve the unit-test
 * path.
 *
 * <p>Internally manages a {@code Map<String, BoundedInMemoryPubSubTopic>} (NOT
 * {@code Map<String, InMemoryPubSubTopic>}). All blocking / capacity / eviction behaviour
 * lives in {@link BoundedInMemoryPubSubTopic}; this broker is a thin dispatcher.
 *
 * <p>See {@code aa-phase9-progress.md} for the architectural rationale (the existing
 * {@code MockInMemory*Adapter} classes are typed against {@link InMemoryPubSubBroker} and
 * cannot be modified, so we accompany this broker with a parallel set of bounded mock
 * adapters wired by the integration-test factories).
 */
public class BoundedInMemoryPubSubBroker {
  private static final Logger LOGGER = LogManager.getLogger(BoundedInMemoryPubSubBroker.class);

  private final Map<String, BoundedInMemoryPubSubTopic> topics = new VeniceConcurrentHashMap<>();
  private final String brokerAddress;
  private final int port;
  private final int defaultCapacity;

  /**
   * Construct a broker with the {@link BoundedInMemoryPubSubTopic#DEFAULT_CAPACITY} per
   * partition.
   */
  public BoundedInMemoryPubSubBroker(String brokerNamePrefix) {
    this(brokerNamePrefix, BoundedInMemoryPubSubTopic.DEFAULT_CAPACITY);
  }

  public BoundedInMemoryPubSubBroker(String brokerNamePrefix, int defaultCapacity) {
    this.port = TestUtils.getFreePort();
    this.brokerAddress = brokerNamePrefix + "_BoundedInMemoryKafkaBroker:" + port;
    this.defaultCapacity = defaultCapacity;
    LOGGER.info(
        "Created a new {} with address: {} (defaultCapacity={})",
        BoundedInMemoryPubSubBroker.class.getSimpleName(),
        brokerAddress,
        defaultCapacity);
  }

  public synchronized void createTopic(String topicName, int partitionCount) {
    if (topics.containsKey(topicName)) {
      LOGGER.warn("The topic {} already exists in this {}, not creating it again.", topicName, brokerAddress);
      throw new IllegalStateException(
          "The topic " + topicName + " already exists in this " + BoundedInMemoryPubSubBroker.class.getSimpleName());
    }
    topics.put(topicName, new BoundedInMemoryPubSubTopic(partitionCount, defaultCapacity));
  }

  /**
   * Produce a message into the topic/partition. Will block (with a timeout) if the
   * partition queue is full — see {@link BoundedInMemoryPubSubTopic#produce}.
   */
  public InMemoryPubSubPosition produce(String topicName, int partition, InMemoryPubSubMessage message) {
    BoundedInMemoryPubSubTopic topic = getTopic(topicName);
    return topic.produce(partition, message);
  }

  public Optional<InMemoryPubSubMessage> consume(String topicName, int partition, InMemoryPubSubPosition position) {
    BoundedInMemoryPubSubTopic topic = getTopic(topicName);
    return topic.consume(partition, position);
  }

  /**
   * Consumer-side hook. Bounded mock consumer adapter calls this to advance the
   * low-water-mark for the partition so the producer can evict consumed entries to make
   * room for new ones.
   */
  public void reportConsumerPosition(String topicName, int partition, long readPosition) {
    BoundedInMemoryPubSubTopic topic = topics.get(topicName);
    if (topic == null) {
      return; // Topic may have been deleted; ignore.
    }
    topic.reportConsumerPosition(partition, readPosition);
  }

  public int getPartitionCount(String topicName) {
    BoundedInMemoryPubSubTopic topic = getTopic(topicName);
    return topic.getPartitionCount();
  }

  /**
   * @throws IllegalArgumentException if the topic does not exist
   */
  private BoundedInMemoryPubSubTopic getTopic(String topicName) {
    BoundedInMemoryPubSubTopic topic = topics.get(topicName);
    if (topic == null) {
      throw new IllegalArgumentException(
          "The topic " + topicName + " does not exist in this " + BoundedInMemoryPubSubBroker.class.getSimpleName());
    }
    return topic;
  }

  public String getPubSubBrokerAddress() {
    return brokerAddress;
  }

  public Long endOffsets(String topicName, int partition) {
    BoundedInMemoryPubSubTopic topic = topics.get(topicName);
    if (topic == null) {
      return 0L;
    }
    return topic.getEndOffsets(partition);
  }

  public InMemoryPubSubPosition endPosition(String topicName, int partition) {
    BoundedInMemoryPubSubTopic topic = topics.get(topicName);
    if (topic == null) {
      return InMemoryPubSubPosition.of(0L);
    }
    return topic.endPosition(partition);
  }

  public int getPort() {
    return port;
  }

  /** Test-only: returns whether the topic exists. */
  public boolean containsTopic(String topicName) {
    return topics.containsKey(topicName);
  }

  /** Test-only inspection: queue size for a partition (delegates to topic). */
  public int sizeFor(String topicName, int partition) {
    BoundedInMemoryPubSubTopic topic = topics.get(topicName);
    if (topic == null) {
      return 0;
    }
    return topic.sizeFor(partition);
  }
}
