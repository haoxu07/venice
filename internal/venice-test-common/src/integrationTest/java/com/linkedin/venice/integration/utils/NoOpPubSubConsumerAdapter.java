package com.linkedin.venice.integration.utils;

import com.linkedin.venice.pubsub.PubSubTopicPartitionInfo;
import com.linkedin.venice.pubsub.api.DefaultPubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.api.PubSubPosition;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubPosition;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;


/**
 * Phase 8: minimal {@link PubSubConsumerAdapter} delegate used internally by
 * {@link com.linkedin.venice.pubsub.mock.adapter.consumer.MockInMemoryConsumerAdapter}.
 *
 * <p>{@code MockInMemoryConsumerAdapter}'s public API forwards subscribe/unsubscribe and
 * other lifecycle calls to a delegate (originally to allow Mockito-based call
 * verification in unit tests), and asserts that the delegate's {@code poll()} returns
 * empty (so the delegate doesn't accidentally inject extra messages). For integration
 * use we just need a delegate that:
 * <ul>
 *   <li>accepts every lifecycle call without throwing</li>
 *   <li>returns an empty {@code poll()} result so the mock's delegate-emptiness assertion holds</li>
 *   <li>tracks subscribed partitions just enough to satisfy {@code hasSubscription} /
 *       {@code getAssignment} (the mock doesn't ever call these on the delegate, but we
 *       implement them defensively)</li>
 * </ul>
 *
 * <p>This class is benchmark/test-only.
 */
public class NoOpPubSubConsumerAdapter implements PubSubConsumerAdapter {
  private final Set<PubSubTopicPartition> subscribed = new HashSet<>();

  @Override
  public void subscribe(PubSubTopicPartition pubSubTopicPartition, PubSubPosition lastReadPubSubPosition) {
    subscribed.add(pubSubTopicPartition);
  }

  @Override
  public void subscribe(
      PubSubTopicPartition pubSubTopicPartition,
      PubSubPosition position,
      boolean isInclusive) {
    subscribed.add(pubSubTopicPartition);
  }

  @Override
  public void unSubscribe(PubSubTopicPartition pubSubTopicPartition) {
    subscribed.remove(pubSubTopicPartition);
  }

  @Override
  public void batchUnsubscribe(Set<PubSubTopicPartition> pubSubTopicPartitionSet) {
    subscribed.removeAll(pubSubTopicPartitionSet);
  }

  @Override
  public void resetOffset(PubSubTopicPartition pubSubTopicPartition) {
    // No-op.
  }

  @Override
  public void close() {
    subscribed.clear();
  }

  @Override
  public Map<PubSubTopicPartition, List<DefaultPubSubMessage>> poll(long timeoutMs) {
    return Collections.emptyMap();
  }

  @Override
  public boolean hasAnySubscription() {
    return !subscribed.isEmpty();
  }

  @Override
  public boolean hasSubscription(PubSubTopicPartition pubSubTopicPartition) {
    return subscribed.contains(pubSubTopicPartition);
  }

  @Override
  public void pause(PubSubTopicPartition pubSubTopicPartition) {
    // No-op.
  }

  @Override
  public void resume(PubSubTopicPartition pubSubTopicPartition) {
    // No-op.
  }

  @Override
  public Set<PubSubTopicPartition> getAssignment() {
    return new HashSet<>(subscribed);
  }

  @Override
  public PubSubPosition getPositionByTimestamp(
      PubSubTopicPartition pubSubTopicPartition,
      long timestamp,
      Duration timeout) {
    return null;
  }

  @Override
  public PubSubPosition getPositionByTimestamp(PubSubTopicPartition pubSubTopicPartition, long timestamp) {
    return null;
  }

  @Override
  public PubSubPosition beginningPosition(PubSubTopicPartition pubSubTopicPartition, Duration timeout) {
    return InMemoryPubSubPosition.of(0);
  }

  @Override
  public Map<PubSubTopicPartition, PubSubPosition> beginningPositions(
      Collection<PubSubTopicPartition> partitions,
      Duration timeout) {
    Map<PubSubTopicPartition, PubSubPosition> result = new HashMap<>();
    for (PubSubTopicPartition p: partitions) {
      result.put(p, InMemoryPubSubPosition.of(0));
    }
    return result;
  }

  @Override
  public Map<PubSubTopicPartition, PubSubPosition> endPositions(
      Collection<PubSubTopicPartition> partitions,
      Duration timeout) {
    Map<PubSubTopicPartition, PubSubPosition> result = new HashMap<>();
    for (PubSubTopicPartition p: partitions) {
      result.put(p, InMemoryPubSubPosition.of(0));
    }
    return result;
  }

  @Override
  public PubSubPosition endPosition(PubSubTopicPartition pubSubTopicPartition) {
    return InMemoryPubSubPosition.of(0);
  }

  @Override
  public List<PubSubTopicPartitionInfo> partitionsFor(PubSubTopic pubSubTopic) {
    return Collections.emptyList();
  }

  @Override
  public long comparePositions(PubSubTopicPartition partition, PubSubPosition position1, PubSubPosition position2) {
    return position1.getNumericOffset() - position2.getNumericOffset();
  }

  @Override
  public long positionDifference(PubSubTopicPartition partition, PubSubPosition position1, PubSubPosition position2) {
    return position1.getNumericOffset() - position2.getNumericOffset();
  }

  @Override
  public PubSubPosition advancePosition(
      PubSubTopicPartition pubSubTopicPartition,
      PubSubPosition startInclusive,
      long n) {
    return InMemoryPubSubPosition.of(startInclusive.getNumericOffset() + n);
  }

  @Override
  public PubSubPosition decodePosition(PubSubTopicPartition partition, int positionTypeId, ByteBuffer buffer) {
    if (buffer == null || buffer.remaining() < Long.BYTES) {
      return InMemoryPubSubPosition.of(0);
    }
    return InMemoryPubSubPosition.of(buffer.getLong());
  }
}
