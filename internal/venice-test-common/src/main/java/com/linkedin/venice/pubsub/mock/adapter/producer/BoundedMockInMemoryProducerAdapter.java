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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


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
  private static final Logger LOGGER = LogManager.getLogger(BoundedMockInMemoryProducerAdapter.class);
  private final BoundedInMemoryPubSubBroker broker;

  public BoundedMockInMemoryProducerAdapter(BoundedInMemoryPubSubBroker broker) {
    this.broker = broker;
  }

  @Override
  public int getNumberOfPartitions(String topic) {
    writeDebugFile("getNumberOfPartitions ENTER topic=" + topic + " broker=" + broker.getPubSubBrokerAddress());
    long t0 = System.nanoTime();
    String msg;
    try {
      int n = broker.getPartitionCount(topic);
      msg = String.format(
          "[bench-iter5] getNumberOfPartitions(topic=%s) -> %d on broker=%s (%d ms)",
          topic,
          n,
          broker.getPubSubBrokerAddress(),
          (System.nanoTime() - t0) / 1_000_000L);
      LOGGER.info(msg);
      writeDebugFile(msg);
      return n;
    } catch (RuntimeException e) {
      msg = String.format(
          "[bench-iter5] getNumberOfPartitions(topic=%s) THREW %s on broker=%s (%d ms)",
          topic,
          e.getClass().getSimpleName() + ": " + e.getMessage(),
          broker.getPubSubBrokerAddress(),
          (System.nanoTime() - t0) / 1_000_000L);
      LOGGER.warn(msg);
      writeDebugFile(msg);
      throw e;
    }
  }

  private static void writeDebugFile(String msg) {
    try (java.io.FileWriter fw = new java.io.FileWriter("/tmp/aa-phase9-iter5-debug.log", true)) {
      fw.write(System.currentTimeMillis() + " " + Thread.currentThread().getName() + " " + msg + "\n");
    } catch (Exception ignored) {
    }
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
    // [iter6] log meta-store-rt produces so we can verify whether the controller
    // actually wrote STORE_CLUSTER_CONFIG (metadataType=4) into the meta store RT.
    if (topic.contains("venice_system_store_meta_store_") && topic.endsWith("_rt")) {
      try {
        int msgType = (value != null) ? value.messageType : -1;
        boolean ctrl = (key != null) && key.isControlMessage();
        int keyLen = (key != null && key.getKey() != null) ? key.getKey().length : -1;
        writeDebugFile(
            "produce meta-store-rt topic=" + topic + " partition=" + partition + " offset="
                + inMemoryPubSubPosition + " msgType=" + msgType + " isCtrl=" + ctrl + " keyLen=" + keyLen
                + " broker=" + broker.getPubSubBrokerAddress());
      } catch (Exception ignored) {
      }
    }
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
