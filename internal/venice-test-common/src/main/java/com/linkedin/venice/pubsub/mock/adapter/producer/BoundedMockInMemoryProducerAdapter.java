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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
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
  private static final AtomicLong CALLBACK_THREAD_ID = new AtomicLong(0);

  /**
   * [iter9 / Bug 6] Real Kafka producers fire {@link PubSubProducerCallback#onCompletion} asynchronously
   * after the network round-trip. The previous implementation invoked the callback synchronously on the
   * producer thread inside {@link #sendMessage}, which broke the
   * {@link com.linkedin.davinci.kafka.consumer.ActiveActiveStoreIngestionTask#getProduceToTopicFunction}
   * resultReuseInput contract: the callback truncates the int header on {@code Put.putValue}, but the
   * consumer side still needs to read the same buffer (whose reference is stored verbatim in the bounded
   * broker). With sync callbacks the buffer was mutated before the consumer read, leading to
   * {@code "Start position of 'putValue' ByteBuffer shouldn't be less than 4"}.
   *
   * <p>Switching the callback to a single-thread executor mirrors Kafka's async semantics and keeps the
   * latency overhead at O(microseconds) per produce.
   */
  private static final ExecutorService CALLBACK_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "BoundedMockInMemoryProducerAdapter-callback-" + CALLBACK_THREAD_ID.incrementAndGet());
    t.setDaemon(true);
    return t;
  });

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
    // [iter9 / Bug 6] Fire the callback asynchronously so it cannot mutate buffers (e.g. Put.putValue
    // header truncation in ActiveActiveStoreIngestionTask#getProduceToTopicFunction's resultReuseInput
    // path) before the broker / consumer have finished reading them. This mirrors real Kafka's async
    // callback semantics. The returned future is still completed eagerly so callers that block on it
    // (e.g. SegmentedKafkaConsumer end-of-push wait) are not slowed down.
    if (callback != null) {
      final PubSubProducerCallback cb = callback;
      CALLBACK_EXECUTOR.execute(() -> {
        try {
          cb.onCompletion(produceResult, null);
        } catch (Throwable t) {
          LOGGER.warn("BoundedMockInMemoryProducerAdapter async callback threw", t);
        }
      });
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
