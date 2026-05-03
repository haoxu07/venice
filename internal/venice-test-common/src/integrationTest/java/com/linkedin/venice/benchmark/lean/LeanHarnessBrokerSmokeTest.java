package com.linkedin.venice.benchmark.lean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.venice.kafka.protocol.GUID;
import com.linkedin.venice.kafka.protocol.KafkaMessageEnvelope;
import com.linkedin.venice.kafka.protocol.ProducerMetadata;
import com.linkedin.venice.kafka.protocol.Put;
import com.linkedin.venice.kafka.protocol.enums.MessageType;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.pubsub.PubSubAdminAdapterContext;
import com.linkedin.venice.pubsub.PubSubClientsFactory;
import com.linkedin.venice.pubsub.PubSubConsumerAdapterContext;
import com.linkedin.venice.pubsub.PubSubProducerAdapterContext;
import com.linkedin.venice.pubsub.PubSubTopicPartitionImpl;
import com.linkedin.venice.pubsub.api.DefaultPubSubMessage;
import com.linkedin.venice.pubsub.api.PubSubAdminAdapter;
import com.linkedin.venice.pubsub.api.PubSubConsumerAdapter;
import com.linkedin.venice.pubsub.api.PubSubMessageDeserializer;
import com.linkedin.venice.pubsub.api.PubSubProduceResult;
import com.linkedin.venice.pubsub.api.PubSubProducerAdapter;
import com.linkedin.venice.pubsub.api.PubSubSymbolicPosition;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


/**
 * Phase 2 smoke test for {@link MinimalAAIngestionHarness}: brings up the harness's brokers and topics,
 * confirms topic presence (and partition count), and round-trips a single KME-encoded message through one
 * of the RT topics.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Two brokers come up cleanly under {@link MinimalAAIngestionHarness#start()}.</li>
 *   <li>Each broker has the configured RT and VT topics with the configured partition count.</li>
 *   <li>A KME message produced to the RT topic on each region can be consumed back, and the consumed
 *       {@code KafkaMessageEnvelope#payloadUnion} bytes match the produced bytes byte-for-byte.</li>
 *   <li>{@link MinimalAAIngestionHarness#stop()} shuts the brokers down and is idempotent.</li>
 * </ul>
 */
public class LeanHarnessBrokerSmokeTest {
  private static final String STORE_NAME = "lean-harness-smoke-store";
  private static final int REGION_COUNT = 2;
  private static final int PARTITION_COUNT = 2;
  private static final int VERSION_NUMBER = 1;

  private MinimalAAIngestionHarness harness;

  @BeforeMethod(alwaysRun = true)
  public void setUp() {
    MinimalAAIngestionHarness.Config config =
        new MinimalAAIngestionHarness.Config(REGION_COUNT, PARTITION_COUNT, STORE_NAME);
    harness = new MinimalAAIngestionHarness(config);
  }

  @AfterMethod(alwaysRun = true)
  public void tearDown() {
    if (harness != null) {
      harness.stop();
    }
  }

  @Test(timeOut = 60_000)
  public void testHarnessStartsBrokersAndCreatesTopicsOnEveryRegion() {
    long startNanos = System.nanoTime();
    harness.start();
    long startMs = (System.nanoTime() - startNanos) / 1_000_000L;
    System.out.println("[LeanHarnessBrokerSmokeTest] harness.start() took " + startMs + " ms");
    assertTrue(harness.isStarted(), "Harness must report started after start()");

    PubSubTopic realTimeTopic = harness.getRealTimeTopic();
    PubSubTopic versionTopic = harness.getVersionTopic();
    assertEquals(realTimeTopic.getName(), STORE_NAME + "_rt");
    assertEquals(versionTopic.getName(), STORE_NAME + "_v" + VERSION_NUMBER);

    for (int region = 0; region < REGION_COUNT; region++) {
      String address = harness.getBrokerAddress(region);
      assertNotNull(address);
      assertFalse(address.isEmpty(), "Broker address for region " + region + " must be non-empty");

      PubSubAdminAdapter admin = newAdminAdapter(harness, region);
      try {
        Set<PubSubTopic> listedTopics = admin.listAllTopics();
        assertTrue(
            listedTopics.contains(realTimeTopic),
            "Region " + region + " missing RT topic " + realTimeTopic.getName() + "; listed=" + listedTopics);
        assertTrue(
            listedTopics.contains(versionTopic),
            "Region " + region + " missing VT topic " + versionTopic.getName() + "; listed=" + listedTopics);

        // Confirm partition counts via PubSubAdminAdapter#containsTopicWithPartitionCheck for partition-1 (which only
        // exists if PARTITION_COUNT>=2). For PARTITION_COUNT=2, partition-0 and partition-1 must exist; partition-2
        // must
        // not.
        for (int p = 0; p < PARTITION_COUNT; p++) {
          assertTrue(
              admin.containsTopicWithPartitionCheck(new PubSubTopicPartitionImpl(realTimeTopic, p)),
              "Region " + region + " RT topic missing partition " + p);
          assertTrue(
              admin.containsTopicWithPartitionCheck(new PubSubTopicPartitionImpl(versionTopic, p)),
              "Region " + region + " VT topic missing partition " + p);
        }
        assertFalse(
            admin.containsTopicWithPartitionCheck(new PubSubTopicPartitionImpl(realTimeTopic, PARTITION_COUNT)),
            "Region " + region + " RT topic should NOT have partition " + PARTITION_COUNT);
      } finally {
        Utils.closeQuietlyWithErrorLogged(admin);
      }
    }
  }

  @Test(timeOut = 60_000)
  public void testRoundTripKmeMessageOnRealTimeTopicForEveryRegion() throws Exception {
    harness.start();

    for (int region = 0; region < REGION_COUNT; region++) {
      // Produce 1 KME message to partition 0 of the RT topic and read it back via a fresh consumer.
      PubSubProducerAdapter producer = newProducerAdapter(harness, region);
      PubSubConsumerAdapter consumer = newConsumerAdapter(harness, region);
      try {
        KafkaKey kafkaKey = new KafkaKey(
            MessageType.PUT,
            ("smoke-key-region-" + region).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        byte[] expectedPayload = ("smoke-payload-region-" + region).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        KafkaMessageEnvelope kme = buildKmeWithPut(expectedPayload, /*sequenceNumber*/ 0);

        // Send to partition 0 and wait for the produce to complete.
        PubSubProduceResult sendResult = producer
            .sendMessage(harness.getRealTimeTopic().getName(), 0, kafkaKey, kme, /*headers*/ null, /*callback*/ null)
            .get(15, TimeUnit.SECONDS);
        assertNotNull(sendResult, "PubSubProduceResult must not be null for region " + region);

        // Subscribe and poll until we see the message we just produced.
        PubSubTopicPartition partition = new PubSubTopicPartitionImpl(harness.getRealTimeTopic(), 0);
        consumer.subscribe(partition, PubSubSymbolicPosition.EARLIEST);

        DefaultPubSubMessage consumed = pollUntilOneMessage(consumer, partition, 15_000);
        assertNotNull(consumed, "Should have consumed a single message back from RT topic on region " + region);

        // Verify byte-equality of the produced payload vs the consumed payload.
        KafkaMessageEnvelope consumedKme = consumed.getValue();
        assertNotNull(consumedKme.payloadUnion, "Consumed KME must have a payloadUnion");
        assertTrue(consumedKme.payloadUnion instanceof Put, "Expected Put union variant on RT topic");
        Put consumedPut = (Put) consumedKme.payloadUnion;
        ByteBuffer consumedBuf = consumedPut.putValue;
        byte[] consumedBytes = new byte[consumedBuf.remaining()];
        consumedBuf.get(consumedBytes);
        assertEquals(consumedBytes, expectedPayload, "Round-tripped payload bytes must match for region " + region);

        // Verify the key bytes round-trip too.
        assertEquals(consumed.getKey().getKey(), kafkaKey.getKey(), "Round-tripped key bytes must match");
      } finally {
        producer.close(0);
        Utils.closeQuietlyWithErrorLogged(consumer);
      }
    }
  }

  @Test(timeOut = 60_000)
  public void testStopIsIdempotent() {
    harness.start();
    assertTrue(harness.isStarted());
    harness.stop();
    assertFalse(harness.isStarted());
    // Calling stop again must not throw.
    harness.stop();
    assertFalse(harness.isStarted());
  }

  // -- helpers ---------------------------------------------------------------------------------------

  private static PubSubAdminAdapter newAdminAdapter(MinimalAAIngestionHarness harness, int region) {
    PubSubClientsFactory clientsFactory = harness.getBrokerForRegion(region).getPubSubClientsFactory();
    Properties properties = MinimalAAIngestionHarness.buildPubSubProperties(harness.getBrokerForRegion(region));
    VeniceProperties veniceProperties = new VeniceProperties(properties);
    return clientsFactory.getAdminAdapterFactory()
        .create(
            new PubSubAdminAdapterContext.Builder().setAdminClientName(Utils.getUniqueString("smoke-test-admin-"))
                .setVeniceProperties(veniceProperties)
                .setPubSubTopicRepository(harness.getPubSubTopicRepository())
                .setPubSubPositionTypeRegistry(harness.getBrokerForRegion(region).getPubSubPositionTypeRegistry())
                .build());
  }

  private static PubSubProducerAdapter newProducerAdapter(MinimalAAIngestionHarness harness, int region) {
    PubSubClientsFactory clientsFactory = harness.getBrokerForRegion(region).getPubSubClientsFactory();
    Properties properties = MinimalAAIngestionHarness.buildPubSubProperties(harness.getBrokerForRegion(region));
    VeniceProperties veniceProperties = new VeniceProperties(properties);
    return clientsFactory.getProducerAdapterFactory()
        .create(
            new PubSubProducerAdapterContext.Builder().setVeniceProperties(veniceProperties)
                .setProducerName(Utils.getUniqueString("smoke-test-producer-"))
                .setBrokerAddress(harness.getBrokerAddress(region))
                .setPubSubPositionTypeRegistry(harness.getBrokerForRegion(region).getPubSubPositionTypeRegistry())
                .build());
  }

  private static PubSubConsumerAdapter newConsumerAdapter(MinimalAAIngestionHarness harness, int region) {
    PubSubClientsFactory clientsFactory = harness.getBrokerForRegion(region).getPubSubClientsFactory();
    Properties properties = MinimalAAIngestionHarness.buildPubSubProperties(harness.getBrokerForRegion(region));
    VeniceProperties veniceProperties = new VeniceProperties(properties);
    return clientsFactory.getConsumerAdapterFactory()
        .create(
            new PubSubConsumerAdapterContext.Builder().setVeniceProperties(veniceProperties)
                .setConsumerName(Utils.getUniqueString("smoke-test-consumer-"))
                .setPubSubMessageDeserializer(PubSubMessageDeserializer.createDefaultDeserializer())
                .setPubSubTopicRepository(harness.getPubSubTopicRepository())
                .setPubSubPositionTypeRegistry(harness.getBrokerForRegion(region).getPubSubPositionTypeRegistry())
                .build());
  }

  /**
   * Build a minimal {@link KafkaMessageEnvelope} carrying a {@link Put} with the given payload bytes.
   *
   * <p>This is the absolute minimum for the smoke test — no DIV-relevant segment / sequence-number machinery,
   * no compression, no replication metadata. Just enough for the message to round-trip through Kafka.
   */
  private static KafkaMessageEnvelope buildKmeWithPut(byte[] payload, long sequenceNumber) {
    KafkaMessageEnvelope kme = new KafkaMessageEnvelope();
    kme.messageType = MessageType.PUT.getValue();
    kme.producerMetadata = new ProducerMetadata();
    kme.producerMetadata.messageTimestamp = System.currentTimeMillis();
    kme.producerMetadata.messageSequenceNumber = (int) sequenceNumber;
    kme.producerMetadata.segmentNumber = 0;
    kme.producerMetadata.producerGUID = new GUID();
    Put put = new Put();
    put.putValue = ByteBuffer.wrap(payload);
    put.schemaId = 1;
    put.replicationMetadataVersionId = -1;
    put.replicationMetadataPayload = ByteBuffer.allocate(0);
    kme.payloadUnion = put;
    return kme;
  }

  /**
   * Poll the consumer until one message is observed on the given partition or the deadline passes.
   *
   * @return the first {@link DefaultPubSubMessage} on the partition, or {@code null} if none was observed
   *         before the deadline.
   */
  private static DefaultPubSubMessage pollUntilOneMessage(
      PubSubConsumerAdapter consumer,
      PubSubTopicPartition partition,
      long timeoutMs) {
    long deadline = System.currentTimeMillis() + timeoutMs;
    while (System.currentTimeMillis() < deadline) {
      Map<PubSubTopicPartition, List<DefaultPubSubMessage>> polled = consumer.poll(500);
      if (polled != null) {
        List<DefaultPubSubMessage> partitionMessages = polled.get(partition);
        if (partitionMessages != null && !partitionMessages.isEmpty()) {
          return partitionMessages.get(0);
        }
      }
    }
    return null;
  }
}
