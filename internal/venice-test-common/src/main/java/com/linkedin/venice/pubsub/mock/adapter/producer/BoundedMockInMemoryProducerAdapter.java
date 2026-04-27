package com.linkedin.venice.pubsub.mock.adapter.producer;

import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
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
import java.nio.ByteBuffer;
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
 *
 * <p>[iter9 / Bug 6] Real Kafka serialises the message bytes into a network buffer before
 * invoking the producer callback, which means any post-send mutation of the original buffer
 * by the callback (e.g.
 * {@link com.linkedin.davinci.kafka.consumer.ActiveActiveStoreIngestionTask#getProduceToTopicFunction}'s
 * resultReuseInput path that truncates the int header on {@code Put.putValue}) cannot affect
 * the broker's stored bytes. The previous in-memory implementation stored the
 * {@link KafkaMessageEnvelope} reference verbatim, so the post-send mutation would race with
 * the consumer's read of {@code put.putValue} — leading to
 * {@code "Start position of 'putValue' ByteBuffer shouldn't be less than 4"}.
 *
 * <p>To restore Kafka-equivalent semantics with minimal overhead, we deep-copy
 * {@code Put.putValue} (and {@code Put.replicationMetadataPayload}) into a freshly-cloned
 * {@link KafkaMessageEnvelope} before handing it to the broker. The producer callback can
 * then run synchronously (matching the existing unbounded mock) without corrupting the bytes
 * the consumer side will eventually read.
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

  /**
   * [iter9 / Bug 6] Return a {@link KafkaMessageEnvelope} whose {@link Put#putValue} (and the
   * RMD payload) is deep-copied so subsequent mutation by the producer callback cannot affect
   * the bytes the broker has handed to the consumer. Mirrors Kafka's network-serialisation
   * boundary. Non-PUT messages (CONTROL, UPDATE, DELETE) are passed through verbatim because
   * their payloads are not mutated post-send.
   */
  private static KafkaMessageEnvelope detachPutPayload(KafkaMessageEnvelope value) {
    if (value == null || value.payloadUnion == null) {
      return value;
    }
    if (MessageType.valueOf(value) != MessageType.PUT) {
      return value;
    }
    Put originalPut = (Put) value.payloadUnion;
    Put copy = new Put();
    copy.schemaId = originalPut.schemaId;
    copy.replicationMetadataVersionId = originalPut.replicationMetadataVersionId;
    copy.putValue = cloneByteBuffer(originalPut.putValue);
    copy.replicationMetadataPayload = cloneByteBuffer(originalPut.replicationMetadataPayload);

    KafkaMessageEnvelope detached = new KafkaMessageEnvelope();
    detached.messageType = value.messageType;
    detached.producerMetadata = value.producerMetadata;
    detached.payloadUnion = copy;
    detached.leaderMetadataFooter = value.leaderMetadataFooter;
    return detached;
  }

  private static ByteBuffer cloneByteBuffer(ByteBuffer src) {
    if (src == null) {
      return null;
    }
    int position = src.position();
    int remaining = src.remaining();
    byte[] bytes = new byte[position + remaining];
    if (src.hasArray()) {
      // Capture the live region [arrayOffset, arrayOffset+position+remaining) so the cloned
      // buffer's [0, position) header bytes are intact and [position, position+remaining)
      // payload bytes are intact. Position-aware semantics let downstream code rewind by 4
      // to read the schema-id header just like Kafka would after deserialisation.
      System.arraycopy(src.array(), src.arrayOffset(), bytes, 0, position + remaining);
    } else {
      // Slow path: fall back to absolute reads. Should not happen for Venice's heap buffers.
      ByteBuffer dup = src.duplicate();
      dup.position(0);
      dup.get(bytes, 0, position + remaining);
    }
    ByteBuffer copy = ByteBuffer.wrap(bytes);
    copy.position(position);
    copy.limit(position + remaining);
    return copy;
  }

  @Override
  public CompletableFuture<PubSubProduceResult> sendMessage(
      String topic,
      Integer partition,
      KafkaKey key,
      KafkaMessageEnvelope value,
      PubSubMessageHeaders headers,
      PubSubProducerCallback callback) {
    KafkaMessageEnvelope detachedValue = detachPutPayload(value);
    InMemoryPubSubPosition inMemoryPubSubPosition =
        broker.produce(topic, partition, new InMemoryPubSubMessage(key, detachedValue, headers));
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
