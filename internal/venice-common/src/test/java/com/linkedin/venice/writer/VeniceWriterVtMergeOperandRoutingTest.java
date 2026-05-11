package com.linkedin.venice.writer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotEquals;

import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.partitioner.DefaultVenicePartitioner;
import com.linkedin.venice.pubsub.api.PubSubProducerAdapter;
import com.linkedin.venice.serialization.DefaultSerializer;
import com.linkedin.venice.serialization.KeyWithChunkingSuffixSerializer;
import com.linkedin.venice.utils.VeniceProperties;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.mockito.ArgumentCaptor;
import org.testng.annotations.Test;


/**
 * Regression guards for H5 partition-routing fix.
 *
 * <p>Before the fix, the leader's VT-merge UPDATE forwarding path
 * ({@code ActiveActiveStoreIngestionTask.produceUpdateOperandToVT}) wrapped the key with the
 * chunking suffix BEFORE calling {@code VeniceWriter.update}. Inside {@code VeniceWriter.update},
 * the partition is computed by hashing whatever {@code serializedKey} the caller passed — i.e.,
 * the WRAPPED key. Meanwhile, the corresponding base PUT during VPJ batch push passed UNWRAPPED
 * keys to {@code VeniceWriter.put}, which hashes the UNWRAPPED key and then wraps it inside the
 * put body before sending the {@code KafkaKey} downstream.
 *
 * <p>The result: PUT and UPDATE for the same user key, on a chunking-enabled VT store, landed on
 * DIFFERENT VT partitions for ~half the keys (the half where {@code hash(unwrapped) % N !=
 * hash(wrapped) % N}). The follower's storage partition that received the UPDATE had no base for
 * that key, yielding an operand-only readback shape (rawLen = framed-operand-only-len = 24 bytes
 * in the iter-1 evidence) and a "no base, return null" response from the read path — manifesting
 * as the test failure "expected new_name_X but found first_name_X".
 *
 * <p>The fix adds {@link VeniceWriter#updateForVtMergeOperand(byte[], byte[], int, int,
 * com.linkedin.venice.pubsub.api.PubSubProducerCallback)} which:
 * <ul>
 *   <li>routes by the UNWRAPPED key hash (same as {@code put})</li>
 *   <li>wraps the key with the chunking suffix BEFORE constructing the {@code KafkaKey} (same as
 *       {@code put}'s line ~1200)</li>
 * </ul>
 *
 * <p>These tests verify both invariants on a chunking-enabled writer with a partition count where
 * the bug actually manifests for at least one key.
 */
public class VeniceWriterVtMergeOperandRoutingTest {
  private static final String TEST_TOPIC = "test_vt";

  /**
   * <strong>Failing-before-fix reproducer.</strong> Demonstrates that calling
   * {@code VeniceWriter.update} with a pre-wrapped key (the iter-11 code path) routes to a
   * DIFFERENT partition than {@code VeniceWriter.put} for the same UNWRAPPED user-key — at least
   * for one key out of a small set. Asserts that the NEW
   * {@link VeniceWriter#updateForVtMergeOperand} agrees with {@code put} on the destination
   * partition for every key. The first assertion proves the bug exists in the legacy path; the
   * second proves the fix eliminates the divergence.
   */
  @Test
  public void updateForVtMergeOperand_routesSamePartitionAsPut_whenChunkingEnabled() {
    final int partitionCount = 3;
    final boolean chunkingEnabled = true;

    KeyWithChunkingSuffixSerializer wrapper = new KeyWithChunkingSuffixSerializer();
    DefaultVenicePartitioner partitioner = new DefaultVenicePartitioner();

    int divergedKeyCount = 0;
    for (int i = 1; i < 100; i++) {
      byte[] unwrapped = String.valueOf(i).getBytes();
      byte[] wrapped = wrapper.serializeNonChunkedKey(unwrapped);
      int putPartition = partitioner.getPartitionId(unwrapped, partitionCount);
      int legacyUpdatePartition = partitioner.getPartitionId(wrapped, partitionCount);
      if (putPartition != legacyUpdatePartition) {
        divergedKeyCount++;
      }
    }
    // Sanity: assert that at least one key actually diverges under chunking + this partition
    // count. If this fails, the test scenario itself is broken (wrapper produced identical hashes
    // for all 99 keys, which would mean the bug couldn't manifest in production either).
    assertNotEquals(
        divergedKeyCount,
        0,
        "Test setup is broken: with chunkingEnabled and partitionCount=" + partitionCount
            + ", at least one key in 1..99 must produce different partitions for hash(unwrapped) "
            + "vs hash(wrapped); got 0 diverged keys.");

    // Now verify the fix: updateForVtMergeOperand routes every key the same way put does.
    PubSubProducerAdapter mockedProducer = mock(PubSubProducerAdapter.class);
    CompletableFuture mockedFuture = mock(CompletableFuture.class);
    when(mockedProducer.sendMessage(any(), any(), any(), any(), any(), any())).thenReturn(mockedFuture);
    VeniceWriterOptions options =
        new VeniceWriterOptions.Builder(TEST_TOPIC).setKeyPayloadSerializer(new DefaultSerializer())
            .setValuePayloadSerializer(new DefaultSerializer())
            .setWriteComputePayloadSerializer(new DefaultSerializer())
            .setPartitioner(partitioner)
            .setPartitionCount(partitionCount)
            .setChunkingEnabled(chunkingEnabled)
            .build();
    VeniceWriter<byte[], byte[], byte[]> writer = new VeniceWriter<>(options, VeniceProperties.empty(), mockedProducer);

    Map<Integer, Integer> putPartitions = new HashMap<>();
    for (int i = 1; i < 100; i++) {
      byte[] keyBytes = String.valueOf(i).getBytes();
      writer.put(keyBytes, new byte[] { 0x1, 0x2 }, /* valueSchemaId */ 1, null);
      ArgumentCaptor<Integer> p = ArgumentCaptor.forClass(Integer.class);
      verify(mockedProducer, atLeast(1))
          .sendMessage(anyString(), p.capture(), any(KafkaKey.class), any(), any(), any());
      putPartitions.put(i, p.getValue());
      org.mockito.Mockito.clearInvocations(mockedProducer);
    }

    Map<Integer, Integer> updatePartitions = new HashMap<>();
    for (int i = 1; i < 100; i++) {
      byte[] keyBytes = String.valueOf(i).getBytes();
      writer
          .updateForVtMergeOperand(keyBytes, new byte[] { 0x5 }, /* valueSchemaId */ 1, /* derivedSchemaId */ 1, null);
      ArgumentCaptor<Integer> p = ArgumentCaptor.forClass(Integer.class);
      verify(mockedProducer, atLeast(1))
          .sendMessage(anyString(), p.capture(), any(KafkaKey.class), any(), any(), any());
      updatePartitions.put(i, p.getValue());
      org.mockito.Mockito.clearInvocations(mockedProducer);
    }

    for (int i = 1; i < 100; i++) {
      assertEquals(
          updatePartitions.get(i),
          putPartitions.get(i),
          "Key " + i + ": updateForVtMergeOperand routed to partition " + updatePartitions.get(i)
              + " but put routed to partition " + putPartitions.get(i)
              + ". The two operations must hash the SAME key bytes (UNWRAPPED) so they land on the same partition.");
    }
  }

  /**
   * Verifies that {@code updateForVtMergeOperand} produces a {@code KafkaKey} whose serialized
   * bytes are the WRAPPED key (when chunking is enabled). This is the on-disk format the read
   * path looks up via {@code SingleGetChunkingAdapter}, so the merge applies to the correct
   * RocksDB key.
   */
  @Test
  public void updateForVtMergeOperand_wrapsKeyWithChunkingSuffix_whenChunkingEnabled() {
    final int partitionCount = 3;

    PubSubProducerAdapter mockedProducer = mock(PubSubProducerAdapter.class);
    CompletableFuture mockedFuture = mock(CompletableFuture.class);
    when(mockedProducer.sendMessage(any(), any(), any(), any(), any(), any())).thenReturn(mockedFuture);
    VeniceWriterOptions options =
        new VeniceWriterOptions.Builder(TEST_TOPIC).setKeyPayloadSerializer(new DefaultSerializer())
            .setValuePayloadSerializer(new DefaultSerializer())
            .setWriteComputePayloadSerializer(new DefaultSerializer())
            .setPartitioner(new DefaultVenicePartitioner())
            .setPartitionCount(partitionCount)
            .setChunkingEnabled(true)
            .build();
    VeniceWriter<byte[], byte[], byte[]> writer = new VeniceWriter<>(options, VeniceProperties.empty(), mockedProducer);

    byte[] unwrappedKey = "hello".getBytes();
    byte[] expectedWrappedKey = new KeyWithChunkingSuffixSerializer().serializeNonChunkedKey(unwrappedKey);

    writer.updateForVtMergeOperand(unwrappedKey, new byte[] { 0x5 }, 1, 1, null);

    ArgumentCaptor<KafkaKey> keyCaptor = ArgumentCaptor.forClass(KafkaKey.class);
    verify(mockedProducer, atLeast(1)).sendMessage(anyString(), anyInt(), keyCaptor.capture(), any(), any(), any());
    // Find the UPDATE message — earlier messages are control messages (SOS / TopicSwitch / etc.).
    KafkaKey updateKafkaKey = null;
    for (KafkaKey kk: keyCaptor.getAllValues()) {
      if (kk.getKeyHeaderByte() == com.linkedin.venice.kafka.protocol.enums.MessageType.UPDATE.getKeyHeaderByte()) {
        updateKafkaKey = kk;
        break;
      }
    }
    org.testng.Assert.assertNotNull(updateKafkaKey, "no UPDATE-typed KafkaKey was sent");
    assertEquals(
        updateKafkaKey.getKey(),
        expectedWrappedKey,
        "updateForVtMergeOperand should send the WRAPPED key as the KafkaKey payload when "
            + "chunking is enabled; got " + java.util.Arrays.toString(updateKafkaKey.getKey()) + " vs expected "
            + java.util.Arrays.toString(expectedWrappedKey));
  }

  /**
   * When chunking is DISABLED, {@code updateForVtMergeOperand} should pass the raw key through
   * unchanged (no wrapping). Verifies the non-chunking-enabled path.
   */
  @Test
  public void updateForVtMergeOperand_doesNotWrapKey_whenChunkingDisabled() {
    final int partitionCount = 3;

    PubSubProducerAdapter mockedProducer = mock(PubSubProducerAdapter.class);
    CompletableFuture mockedFuture = mock(CompletableFuture.class);
    when(mockedProducer.sendMessage(any(), any(), any(), any(), any(), any())).thenReturn(mockedFuture);
    VeniceWriterOptions options =
        new VeniceWriterOptions.Builder(TEST_TOPIC).setKeyPayloadSerializer(new DefaultSerializer())
            .setValuePayloadSerializer(new DefaultSerializer())
            .setWriteComputePayloadSerializer(new DefaultSerializer())
            .setPartitioner(new DefaultVenicePartitioner())
            .setPartitionCount(partitionCount)
            .setChunkingEnabled(false)
            .build();
    VeniceWriter<byte[], byte[], byte[]> writer = new VeniceWriter<>(options, VeniceProperties.empty(), mockedProducer);

    byte[] unwrappedKey = "hello".getBytes();
    writer.updateForVtMergeOperand(unwrappedKey, new byte[] { 0x5 }, 1, 1, null);

    ArgumentCaptor<KafkaKey> keyCaptor = ArgumentCaptor.forClass(KafkaKey.class);
    verify(mockedProducer, atLeast(1)).sendMessage(anyString(), anyInt(), keyCaptor.capture(), any(), any(), any());
    KafkaKey updateKafkaKey = null;
    for (KafkaKey kk: keyCaptor.getAllValues()) {
      if (kk.getKeyHeaderByte() == com.linkedin.venice.kafka.protocol.enums.MessageType.UPDATE.getKeyHeaderByte()) {
        updateKafkaKey = kk;
        break;
      }
    }
    org.testng.Assert.assertNotNull(updateKafkaKey, "no UPDATE-typed KafkaKey was sent");
    assertEquals(
        updateKafkaKey.getKey(),
        unwrappedKey,
        "updateForVtMergeOperand should pass the raw key through unchanged when chunking is disabled");
  }
}
