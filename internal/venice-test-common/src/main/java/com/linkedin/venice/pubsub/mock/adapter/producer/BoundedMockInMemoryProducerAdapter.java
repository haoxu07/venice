package com.linkedin.venice.pubsub.mock.adapter.producer;

import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.pubsub.adapter.SimplePubSubProduceResultImpl;
import com.linkedin.venice.pubsub.api.PubSubMessageHeaders;
import com.linkedin.venice.pubsub.api.PubSubProduceResult;
import com.linkedin.venice.pubsub.api.PubSubProducerAdapter;
import com.linkedin.venice.pubsub.api.PubSubProducerCallback;
import com.linkedin.venice.pubsub.mock.BoundedInMemoryPubSubBroker;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubMessage;
import com.linkedin.venice.pubsub.mock.InMemoryPubSubPosition;
import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleMaps;
import java.util.concurrent.CompletableFuture;


/**
 * Phase 9: parallel of {@link MockInMemoryProducerAdapter} that targets the bounded broker
 * ({@link BoundedInMemoryPubSubBroker}). Required because the existing mock takes a typed
 * {@link com.linkedin.venice.pubsub.mock.InMemoryPubSubBroker} reference and Phase 9
 * forbids inheritance from that class.
 *
 * <p>Behaviour mirrors the existing producer adapter: synchronously appends to the in-memory
 * topic and immediately fires the producer callback with a completed future. The only
 * difference is the broker type — {@link BoundedInMemoryPubSubBroker#produce} can block
 * (with a 30 s timeout) when the partition queue is full, providing Kafka-style
 * back-pressure.
 */
public class BoundedMockInMemoryProducerAdapter implements PubSubProducerAdapter {
  private final BoundedInMemoryPubSubBroker broker;

  public BoundedMockInMemoryProducerAdapter(BoundedInMemoryPubSubBroker broker) {
    this.broker = broker;
  }

  @Override
  public int getNumberOfPartitions(String topic) {
    return broker.getPartitionCount(topic);
  }

  @Override
  public CompletableFuture<PubSubProduceResult> sendMessage(
      String topic,
      Integer partition,
      KafkaKey key,
      KafkaMessageEnvelope value,
      PubSubMessageHeaders headers,
      PubSubProducerCallback callback) {
    InMemoryPubSubPosition inMemoryPubSubPosition =
        broker.produce(topic, partition, new InMemoryPubSubMessage(key, value, headers));
    PubSubProduceResult produceResult = new SimplePubSubProduceResultImpl(topic, partition, inMemoryPubSubPosition, -1);
    if (callback != null) {
      callback.onCompletion(produceResult, null);
    }
    return CompletableFuture.completedFuture(produceResult);
  }

  @Override
  public void flush() {
    // no-op
  }

  @Override
  public void close(long closeTimeOutMs) {
    // no-op
  }

  @Override
  public Object2DoubleMap<String> getMeasurableProducerMetrics() {
    return Object2DoubleMaps.emptyMap();
  }

  @Override
  public String getBrokerAddress() {
    return broker.getPubSubBrokerAddress();
  }
}
