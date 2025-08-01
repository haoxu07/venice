package com.linkedin.davinci.kafka.consumer;

import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

import com.linkedin.venice.pubsub.api.DefaultPubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubBroker;
import com.linkedin.venice.throttle.EventThrottler;
import com.linkedin.venice.utils.TestMockTime;
import com.linkedin.venice.utils.Time;
import com.linkedin.venice.utils.Utils;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.Assert;
import org.testng.annotations.Test;


public class KafkaClusterBasedRecordThrottlerTest {
  @Test
  public void testRecordsCanBeThrottledPerRegion() {
    String topic = Utils.getUniqueString("topic");
    InMemoryPubSubBroker inMemoryLocalKafkaBroker = new InMemoryPubSubBroker("local");
    inMemoryLocalKafkaBroker.createTopic(topic, 2);
    InMemoryPubSubBroker inMemoryRemoteKafkaBroker = new InMemoryPubSubBroker("remote");
    inMemoryRemoteKafkaBroker.createTopic(topic, 2);

    AtomicLong remoteKafkaQuota = new AtomicLong(10);

    TestMockTime testTime = new TestMockTime();
    long timeWindowMS = 1000L;
    // Unlimited
    EventThrottler localThrottler =
        new EventThrottler(testTime, -1, timeWindowMS, "local_throttler", true, EventThrottler.REJECT_STRATEGY);

    // Modifiable remote throttler
    EventThrottler remoteThrottler = new EventThrottler(
        testTime,
        remoteKafkaQuota::get,
        timeWindowMS,
        "remote_throttler",
        true,
        EventThrottler.REJECT_STRATEGY);

    Map<String, EventThrottler> kafkaUrlToRecordsThrottler = new HashMap<>();
    kafkaUrlToRecordsThrottler.put(inMemoryLocalKafkaBroker.getPubSubBrokerAddress(), localThrottler);
    kafkaUrlToRecordsThrottler.put(inMemoryRemoteKafkaBroker.getPubSubBrokerAddress(), remoteThrottler);

    KafkaClusterBasedRecordThrottler kafkaClusterBasedRecordThrottler =
        new KafkaClusterBasedRecordThrottler(kafkaUrlToRecordsThrottler);

    Map<PubSubTopicPartition, List<DefaultPubSubMessage>> consumerRecords = new HashMap<>();
    PubSubTopicPartition pubSubTopicPartition = mock(PubSubTopicPartition.class);
    consumerRecords.put(pubSubTopicPartition, new ArrayList<>());
    for (int i = 0; i < 10; i++) {
      consumerRecords.get(pubSubTopicPartition).add(mock(DefaultPubSubMessage.class));
    }

    PubSubConsumerAdapter localConsumer = mock(PubSubConsumerAdapter.class);
    PubSubConsumerAdapter remoteConsumer = mock(PubSubConsumerAdapter.class);

    // Assume consumer.poll always returns some records
    doReturn(consumerRecords).when(localConsumer).poll(anyLong());
    doReturn(consumerRecords).when(remoteConsumer).poll(anyLong());

    // Verify can ingest at least some record from local and remote Kafka
    Map<PubSubTopicPartition, List<DefaultPubSubMessage>> localPubSubMessages = kafkaClusterBasedRecordThrottler
        .poll(localConsumer, inMemoryLocalKafkaBroker.getPubSubBrokerAddress(), Time.MS_PER_SECOND);
    Map<PubSubTopicPartition, List<DefaultPubSubMessage>> remotePubSubMessages = kafkaClusterBasedRecordThrottler
        .poll(remoteConsumer, inMemoryRemoteKafkaBroker.getPubSubBrokerAddress(), Time.MS_PER_SECOND);
    Assert.assertSame(localPubSubMessages, consumerRecords);
    Assert.assertSame(remotePubSubMessages, consumerRecords);

    // Pause remote kafka consumption
    remoteKafkaQuota.set(0);

    // Verify does not ingest from remote Kafka
    localPubSubMessages = kafkaClusterBasedRecordThrottler
        .poll(localConsumer, inMemoryLocalKafkaBroker.getPubSubBrokerAddress(), Time.MS_PER_SECOND);
    remotePubSubMessages = kafkaClusterBasedRecordThrottler
        .poll(remoteConsumer, inMemoryRemoteKafkaBroker.getPubSubBrokerAddress(), Time.MS_PER_SECOND);
    Assert.assertSame(localPubSubMessages, consumerRecords);
    Assert.assertNotSame(remotePubSubMessages, consumerRecords);
    Assert.assertTrue(remotePubSubMessages.isEmpty());

    // Resume remote Kafka consumption
    remoteKafkaQuota.set(10);
    testTime.sleep(timeWindowMS); // sleep so throttling window is reset and we don't run into race conditions

    // Verify resumes ingestion from remote Kafka
    localPubSubMessages = kafkaClusterBasedRecordThrottler
        .poll(localConsumer, inMemoryLocalKafkaBroker.getPubSubBrokerAddress(), Time.MS_PER_SECOND);
    remotePubSubMessages = kafkaClusterBasedRecordThrottler
        .poll(remoteConsumer, inMemoryRemoteKafkaBroker.getPubSubBrokerAddress(), Time.MS_PER_SECOND);
    Assert.assertSame(localPubSubMessages, consumerRecords);
    Assert.assertSame(remotePubSubMessages, consumerRecords);
  }
}
