package com.linkedin.venice.pubsub.mock.adapter.consumer.poll;

import com.linkedin.venice.controller.kafka.AdminTopicUtils;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
import com.linkedin.venice.pubsub.ImmutablePubSubMessage;
import com.linkedin.venice.pubsub.api.DefaultPubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubMessage;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubPosition;
import com.linkedin.venice.pubsub.mock.adapter.MockInMemoryPartitionPosition;
import com.linkedin.venice.utils.ByteUtils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;


/**
 * A base class which encapsulates the common plumbing needed by all {@link PollStrategy} implementations.
 */
public abstract class AbstractPollStrategy implements PollStrategy {
  private static final int DEFAULT_MAX_MESSAGES_PER_POLL = 3; // We can make this configurable later on if need be...
  private final int maxMessagePerPoll;
  protected final boolean keepPollingWhenEmpty;

  public AbstractPollStrategy(boolean keepPollingWhenEmpty) {
    this(keepPollingWhenEmpty, DEFAULT_MAX_MESSAGES_PER_POLL);
  }

  public AbstractPollStrategy(boolean keepPollingWhenEmpty, int maxMessagePerPoll) {
    this.keepPollingWhenEmpty = keepPollingWhenEmpty;
    this.maxMessagePerPoll = maxMessagePerPoll;
  }

  protected abstract MockInMemoryPartitionPosition getNextPoll(
      Map<PubSubTopicPartition, InMemoryPubSubPosition> offsets);

  public synchronized Map<PubSubTopicPartition, List<DefaultPubSubMessage>> poll(
      InMemoryPubSubBroker broker,
      Map<PubSubTopicPartition, InMemoryPubSubPosition> offsets,
      long timeout) {

    Map<PubSubTopicPartition, List<DefaultPubSubMessage>> records = new HashMap<>();

    long startTime = System.currentTimeMillis();
    int numberOfRecords = 0;

    while (numberOfRecords < maxMessagePerPoll && System.currentTimeMillis() < startTime + timeout) {
      MockInMemoryPartitionPosition nextPoll = getNextPoll(offsets);
      if (nextPoll == null) {
        if (keepPollingWhenEmpty) {
          continue;
        } else {
          break;
        }
      }
      PubSubTopicPartition pubSubTopicPartition = nextPoll.getPubSubTopicPartition();
      InMemoryPubSubPosition position = nextPoll.getPubSubPosition();
      String topic = pubSubTopicPartition.getPubSubTopic().getName();
      int partition = pubSubTopicPartition.getPartitionNumber();
      /**
       * TODO: need to understand why "+ 1" here, since for {@link ArbitraryOrderingPollStrategy}, it always
       * returns the next message specified in the delivery order, which is causing confusion.
        */
      InMemoryPubSubPosition nextPosition = position.getNextPosition();
      Optional<InMemoryPubSubMessage> message = broker.consume(topic, partition, nextPosition);
      if (message.isPresent()) {
        if (!AdminTopicUtils.isAdminTopic(topic)) {
          /**
           * Skip putValue adjustment since admin consumer is still using {@link com.linkedin.venice.serialization.avro.KafkaValueSerializer}.
           */
          KafkaMessageEnvelope kafkaMessageEnvelope = message.get().value;
          if (!message.get().key.isControlMessage() && MessageType.valueOf(kafkaMessageEnvelope) == MessageType.PUT
              && !message.get().isPutValueChanged()) {
            /**
             * This is used to simulate the deserialization in {@link com.linkedin.venice.serialization.avro.OptimizedKafkaValueSerializer}
             * to leave some room in {@link Put#putValue} byte buffer.
             */
            Put put = (Put) kafkaMessageEnvelope.payloadUnion;
            put.putValue = ByteUtils.enlargeByteBufferForIntHeader(put.putValue);
            message.get().putValueChanged();
          }
        }

        DefaultPubSubMessage consumerRecord = new ImmutablePubSubMessage(
            message.get().key,
            message.get().value,
            pubSubTopicPartition,
            nextPosition,
            System.currentTimeMillis(),
            -1,
            message.get().headers);
        if (!records.containsKey(pubSubTopicPartition)) {
          records.put(pubSubTopicPartition, new ArrayList<>());
        }
        records.get(pubSubTopicPartition).add(consumerRecord);
        incrementOffset(offsets, pubSubTopicPartition, position);
        numberOfRecords++;
      } else if (keepPollingWhenEmpty) {
        continue;
      } else {
        offsets.remove(pubSubTopicPartition);
        continue;
      }
    }

    return records;
  }

  protected void incrementOffset(
      Map<PubSubTopicPartition, InMemoryPubSubPosition> positionMap,
      PubSubTopicPartition topicPartition,
      InMemoryPubSubPosition position) {
    positionMap.put(topicPartition, position.getNextPosition());
  }
}
