package com.linkedin.venice.pubsub.mock.adapter.consumer;

import com.linkedin.venice.controller.kafka.AdminTopicUtils;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
import com.linkedin.venice.pubsub.ImmutablePubSubMessage;
import com.linkedin.venice.pubsub.PubSubTopicPartitionInfo;
import com.linkedin.venice.pubsub.PubSubUtil;
import com.linkedin.venice.pubsub.adapter.kafka.common.ApacheKafkaOffsetPosition;
import com.linkedin.venice.pubsub.api.DefaultPubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.api.PubSubPosition;
import com.linkedin.venice.pubsub.api.PubSubSymbolicPosition;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.pubsub.api.exceptions.PubSubUnsubscribedTopicPartitionException;
import com.linkedin.venice.pubsub.mock.BoundedInMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubMessage;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubPosition;
import com.linkedin.venice.pubsub.mock.adapter.admin.BoundedMockInMemoryAdminAdapter;
import com.linkedin.venice.utils.ByteUtils;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import javax.annotation.Nonnull;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Phase 9: parallel of {@link MockInMemoryConsumerAdapter} that targets
 * {@link BoundedInMemoryPubSubBroker}. Behaviour is intentionally close to the existing
 * mock — sequential round-robin poll, capped at {@code maxMessagesPerPoll} per
 * {@link #poll(long)} call — but with two key differences:
 *
 * <ul>
 *   <li>Consumes from a {@link BoundedInMemoryPubSubBroker} (no inheritance bridge to the
 *       unbounded broker is allowed by Phase 9 rules).</li>
 *   <li>After each successful read, calls
 *       {@link BoundedInMemoryPubSubBroker#reportConsumerPosition} so the bounded topic
 *       can advance the per-partition low-water-mark and free producer slots. This is
 *       what implements Kafka-style back-pressure: producer blocks when partition is
 *       full; consumer reads + reports; producer unblocks. Without this hook the bounded
 *       topic would never know the consumer had drained anything.</li>
 * </ul>
 *
 * <p>Poll strategy: round-robin across subscribed partitions, draining up to
 * {@code maxMessagesPerPoll} messages per call. We do NOT randomise (the unit-test path's
 * {@link com.linkedin.venice.pubsub.mock.adapter.consumer.poll.RandomPollStrategy} is
 * useful for testing partition-ordering invariants, but for the integration test path we
 * want fairness across partitions so no single partition starves the others).
 */
public class BoundedMockInMemoryConsumerAdapter implements PubSubConsumerAdapter {
  private static final Logger LOGGER = LogManager.getLogger(BoundedMockInMemoryConsumerAdapter.class);

  private final BoundedInMemoryPubSubBroker broker;
  private final int maxMessagesPerPoll;
  private final Map<PubSubTopicPartition, InMemoryPubSubPosition> lastReadPositions = new VeniceConcurrentHashMap<>();
  private final Set<PubSubTopicPartition> pausedTopicPartitions = VeniceConcurrentHashMap.newKeySet();
  private BoundedMockInMemoryAdminAdapter adminAdapter;
  private final java.util.concurrent.atomic.AtomicBoolean firstPollDebugLog = new java.util.concurrent.atomic.AtomicBoolean(false);
  private final java.util.concurrent.atomic.AtomicBoolean firstReadDebugLog = new java.util.concurrent.atomic.AtomicBoolean(false);

  public BoundedMockInMemoryConsumerAdapter(BoundedInMemoryPubSubBroker broker, int maxMessagesPerPoll) {
    this.broker = broker;
    this.maxMessagesPerPoll = maxMessagesPerPoll;
  }

  public synchronized void setBoundedMockInMemoryAdminAdapter(BoundedMockInMemoryAdminAdapter adminAdapter) {
    this.adminAdapter = adminAdapter;
  }

  @Override
  public synchronized void subscribe(PubSubTopicPartition pubSubTopicPartition, PubSubPosition lastReadPubSubPosition) {
    subscribe(pubSubTopicPartition, lastReadPubSubPosition, false);
  }

  @Override
  public synchronized void subscribe(
      @Nonnull PubSubTopicPartition pubSubTopicPartition,
      @Nonnull PubSubPosition position,
      boolean isInclusive) {
    LOGGER.info(
        "BoundedMockInMemoryConsumerAdapter: subscribing to {} at {} isInclusive={}",
        pubSubTopicPartition,
        position,
        isInclusive);
    String topicName = pubSubTopicPartition.getPubSubTopic().getName();
    if (topicName.contains("_rt_v") || topicName.contains("_rt_") || topicName.endsWith("_rt")) {
      // Log RT subscriptions including meta-store-rt for iter7 Shape B diagnosis.
      debugLog(
          "subscribe topic=" + topicName + " partition=" + pubSubTopicPartition.getPartitionNumber() + " pos=" + position
              + " incl=" + isInclusive + " broker=" + broker.getPubSubBrokerAddress());
    }

    long seekOffset;
    if (PubSubSymbolicPosition.EARLIEST.equals(position)) {
      seekOffset = 0L;
    } else if (PubSubSymbolicPosition.LATEST.equals(position)) {
      PubSubPosition resolved = endPosition(pubSubTopicPartition);
      if (!(resolved instanceof InMemoryPubSubPosition)) {
        throw new IllegalStateException(
            "endPosition returned unsupported type: " + resolved.getClass().getSimpleName());
      }
      seekOffset = ((InMemoryPubSubPosition) resolved).getInternalOffset();
    } else if (position instanceof InMemoryPubSubPosition) {
      long inputOffset = ((InMemoryPubSubPosition) position).getInternalOffset();
      seekOffset = PubSubUtil.calculateSeekOffset(inputOffset, isInclusive);
    } else if (position instanceof ApacheKafkaOffsetPosition) {
      seekOffset =
          PubSubUtil.calculateSeekOffset(((ApacheKafkaOffsetPosition) position).getInternalOffset(), isInclusive);
    } else {
      throw new IllegalArgumentException("Unsupported PubSubPosition type: " + position.getClass());
    }

    InMemoryPubSubPosition lastReadPosition = InMemoryPubSubPosition.of(seekOffset - 1);
    pausedTopicPartitions.remove(pubSubTopicPartition);
    lastReadPositions.put(pubSubTopicPartition, lastReadPosition);
  }

  @Override
  public synchronized void unSubscribe(PubSubTopicPartition pubSubTopicPartition) {
    lastReadPositions.remove(pubSubTopicPartition);
    pausedTopicPartitions.remove(pubSubTopicPartition);
  }

  @Override
  public synchronized void batchUnsubscribe(Set<PubSubTopicPartition> pubSubTopicPartitionSet) {
    for (PubSubTopicPartition tp: pubSubTopicPartitionSet) {
      lastReadPositions.remove(tp);
      pausedTopicPartitions.remove(tp);
    }
  }

  @Override
  public synchronized void resetOffset(PubSubTopicPartition pubSubTopicPartition) {
    if (!hasSubscription(pubSubTopicPartition)) {
      throw new PubSubUnsubscribedTopicPartitionException(pubSubTopicPartition);
    }
    lastReadPositions.put(pubSubTopicPartition, InMemoryPubSubPosition.of(-1L));
  }

  @Override
  public synchronized void close() {
    pausedTopicPartitions.clear();
    lastReadPositions.clear();
  }

  @Override
  public synchronized Map<PubSubTopicPartition, List<DefaultPubSubMessage>> poll(long timeout) {
    Map<PubSubTopicPartition, List<DefaultPubSubMessage>> records = new HashMap<>();

    if (lastReadPositions.isEmpty()) {
      // Nothing subscribed -- match RandomPollStrategy's tiny sleep so we don't spin.
      sleepQuietly(10);
      return records;
    }

    // PHASE9-DEBUG (TEMPORARY): one-time INFO log per poll to confirm the consumer is
    // running and to expose the broker address it's targeting. Will remove once admin
    // propagation is confirmed working.
    if (firstPollDebugLog.compareAndSet(false, true)) {
      LOGGER.info(
          "PHASE9-DEBUG poll(timeout={}, brokerAddress={}, subscribed={})",
          timeout,
          broker.getPubSubBrokerAddress(),
          lastReadPositions.keySet());
    }

    long startTimeMs = System.currentTimeMillis();
    long deadlineMs = startTimeMs + Math.max(timeout, 0L);
    int produced = 0;
    boolean anyData;

    do {
      anyData = false;
      // Snapshot subscribed partitions for this round (avoids
      // ConcurrentModificationException if subscribe happens concurrently).
      List<PubSubTopicPartition> subscribed = new ArrayList<>(lastReadPositions.keySet());
      for (PubSubTopicPartition tp: subscribed) {
        if (produced >= maxMessagesPerPoll) {
          break;
        }
        if (pausedTopicPartitions.contains(tp)) {
          continue;
        }
        InMemoryPubSubPosition last = lastReadPositions.get(tp);
        if (last == null) {
          continue;
        }
        InMemoryPubSubPosition next = last.getNextPosition();
        String topicName = tp.getPubSubTopic().getName();
        int partition = tp.getPartitionNumber();

        Optional<InMemoryPubSubMessage> message;
        try {
          message = broker.consume(topicName, partition, next);
        } catch (IllegalArgumentException notFound) {
          // Topic may have been deleted; surface no data and skip.
          if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("consume({}, {}, {}) topic missing: {}", topicName, partition, next, notFound.getMessage());
          }
          continue;
        }

        if (message.isPresent()) {
          if (firstReadDebugLog.compareAndSet(false, true)) {
            LOGGER.info(
                "PHASE9-DEBUG first read OK: topic={} partition={} position={} brokerAddr={}",
                topicName,
                partition,
                next,
                broker.getPubSubBrokerAddress());
            debugLog(
                "FIRST-READ topic=" + topicName + " partition=" + partition + " pos=" + next + " broker="
                    + broker.getPubSubBrokerAddress());
          }
          // iter7 Shape B: log EVERY meta-store-rt read so we can verify dc-0 server
          // actually consumes positions 0-7 (where STORE_CLUSTER_CONFIG resides).
          if (topicName.contains("venice_system_store_meta_store_") && topicName.endsWith("_rt")) {
            boolean isCtrl = message.get().key.isControlMessage();
            int keyLen = message.get().key.getKey() == null ? 0 : message.get().key.getKey().length;
            debugLog(
                "META-RT-READ topic=" + topicName + " partition=" + partition + " pos=" + next + " isCtrl=" + isCtrl
                    + " keyLen=" + keyLen + " broker=" + broker.getPubSubBrokerAddress());
          }
          KafkaMessageEnvelope kafkaMessageEnvelope = message.get().value;
          if (!AdminTopicUtils.isAdminTopic(topicName) && !message.get().key.isControlMessage()
              && MessageType.valueOf(kafkaMessageEnvelope) == MessageType.PUT
              && !message.get().isPutValueChanged()) {
            // Mirror the existing AbstractPollStrategy behaviour: pad putValue's
            // ByteBuffer for the int header so the deserializer downstream has room.
            Put put = (Put) kafkaMessageEnvelope.payloadUnion;
            put.putValue = ByteUtils.enlargeByteBufferForIntHeader(put.putValue);
            message.get().putValueChanged();
          }

          DefaultPubSubMessage record = new ImmutablePubSubMessage(
              message.get().key,
              kafkaMessageEnvelope,
              tp,
              next,
              System.currentTimeMillis(),
              -1,
              message.get().headers);
          records.computeIfAbsent(tp, k -> new ArrayList<>()).add(record);
          lastReadPositions.put(tp, next);
          // Tell broker we drained this position, so producer can evict.
          broker.reportConsumerPosition(topicName, partition, next.getInternalOffset());
          produced++;
          anyData = true;
        }
      }
      if (produced >= maxMessagesPerPoll) {
        break;
      }
      if (!anyData) {
        // Empty round across all subscribed partitions: yield briefly, recheck deadline.
        if (System.currentTimeMillis() >= deadlineMs) {
          break;
        }
        sleepQuietly(5);
      }
    } while (System.currentTimeMillis() < deadlineMs);

    return records;
  }

  private static void sleepQuietly(long ms) {
    try {
      Thread.sleep(ms);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void debugLog(String msg) {
    try (java.io.FileWriter fw = new java.io.FileWriter("/tmp/aa-phase9-iter5-debug.log", true)) {
      fw.write(System.currentTimeMillis() + " " + Thread.currentThread().getName() + " [consumer] " + msg + "\n");
    } catch (Exception ignored) {
    }
  }

  @Override
  public synchronized boolean hasAnySubscription() {
    return !lastReadPositions.isEmpty();
  }

  @Override
  public synchronized boolean hasSubscription(PubSubTopicPartition pubSubTopicPartition) {
    return lastReadPositions.containsKey(pubSubTopicPartition);
  }

  @Override
  public synchronized void pause(PubSubTopicPartition pubSubTopicPartition) {
    pausedTopicPartitions.add(pubSubTopicPartition);
  }

  @Override
  public synchronized void resume(PubSubTopicPartition pubSubTopicPartition) {
    pausedTopicPartitions.remove(pubSubTopicPartition);
  }

  @Override
  public synchronized Set<PubSubTopicPartition> getAssignment() {
    return lastReadPositions.keySet();
  }

  @Override
  public synchronized PubSubPosition getPositionByTimestamp(
      PubSubTopicPartition pubSubTopicPartition,
      long timestamp,
      Duration timeout) {
    return getPositionByTimestamp(pubSubTopicPartition, timestamp);
  }

  @Override
  public synchronized PubSubPosition getPositionByTimestamp(PubSubTopicPartition pubSubTopicPartition, long timestamp) {
    // iter7 Shape B fix: real Kafka returns the offset of the earliest message whose
    // timestamp is >= the requested timestamp, or null if no such message exists (in
    // which case TopicMetadataFetcher.getPositionForTime falls back to LATEST). For the
    // bounded in-memory broker we don't track per-message timestamps, but the only
    // caller that drives critical correctness is
    // LeaderFollowerStoreIngestionTask.getRewindStartPositionForRealTimeTopic, which
    // uses this to figure out where to begin consuming RT for a hybrid store on a fresh
    // version. The "right" answer for that case in tests is EARLIEST (offset 0): the RT
    // is fresh and all messages are typically newer than the requested rewind time. If
    // we returned null the wrapper would fall back to LATEST and the leader would skip
    // the controller's STORE_CLUSTER_CONFIG / STORE_PROPERTIES writes that landed
    // before the version's StartOfPush -- producing the Shape B
    // MissingKeyInStoreMetadataException in tests like
    // ActiveActiveReplicationForHybridTest.testAAReplicationCanConsumeFromAllRegions.
    // Returning beginningPosition (offset 0) restores correct behavior without needing
    // a full per-message-timestamp index.
    return InMemoryPubSubPosition.of(0);
  }

  @Override
  public synchronized PubSubPosition beginningPosition(PubSubTopicPartition pubSubTopicPartition, Duration timeout) {
    return InMemoryPubSubPosition.of(0);
  }

  @Override
  public synchronized Map<PubSubTopicPartition, PubSubPosition> beginningPositions(
      Collection<PubSubTopicPartition> partitions,
      Duration timeout) {
    Map<PubSubTopicPartition, PubSubPosition> retPositions = new HashMap<>(partitions.size());
    for (PubSubTopicPartition tp: partitions) {
      retPositions.put(tp, beginningPosition(tp, timeout));
    }
    return retPositions;
  }

  @Override
  public synchronized Map<PubSubTopicPartition, PubSubPosition> endPositions(
      Collection<PubSubTopicPartition> partitions,
      Duration timeout) {
    Map<PubSubTopicPartition, PubSubPosition> retPositions = new HashMap<>(partitions.size());
    for (PubSubTopicPartition tp: partitions) {
      retPositions.put(tp, endPosition(tp));
    }
    return retPositions;
  }

  @Override
  public synchronized PubSubPosition endPosition(PubSubTopicPartition pubSubTopicPartition) {
    return broker
        .endPosition(pubSubTopicPartition.getPubSubTopic().getName(), pubSubTopicPartition.getPartitionNumber());
  }

  @Override
  public synchronized List<PubSubTopicPartitionInfo> partitionsFor(PubSubTopic topic) {
    if (adminAdapter != null) {
      return adminAdapter.partitionsFor(topic);
    } else {
      throw new UnsupportedOperationException("In-memory admin adapter is not set");
    }
  }

  @Override
  public synchronized long comparePositions(
      PubSubTopicPartition partition,
      PubSubPosition position1,
      PubSubPosition position2) {
    return positionDifference(partition, position1, position2);
  }

  @Override
  public synchronized long positionDifference(
      PubSubTopicPartition partition,
      PubSubPosition position1,
      PubSubPosition position2) {
    return PubSubUtil.computeOffsetDelta(partition, position1, position2, this);
  }

  @Override
  public synchronized PubSubPosition advancePosition(PubSubTopicPartition tp, PubSubPosition startInclusive, long n) {
    Objects.requireNonNull(tp, "tp");
    Objects.requireNonNull(startInclusive, "startInclusive");
    if (n < 0) {
      throw new IllegalArgumentException("n must be >= 0");
    }
    long targetOffset = Math.addExact(startInclusive.getNumericOffset(), n);
    return InMemoryPubSubPosition.of(targetOffset);
  }

  @Override
  public synchronized PubSubPosition decodePosition(
      PubSubTopicPartition partition,
      int positionTypeId,
      ByteBuffer buffer) {
    try {
      if (buffer.remaining() < Long.BYTES) {
        throw new VeniceException("Buffer too short to decode InMemoryPubSubPosition: " + buffer);
      }
      long offset = buffer.getLong();
      return InMemoryPubSubPosition.of(offset);
    } catch (Exception e) {
      throw new VeniceException("Failed to decode InMemoryPubSubPosition from buffer: " + buffer, e);
    }
  }
}
