package com.linkedin.davinci.store.rocksdb;

import com.linkedin.davinci.stats.RocksDBMemoryStats;
import com.linkedin.davinci.store.StoragePartitionConfig;
import com.linkedin.davinci.store.rocksdb.merge.ChainLengthBackstop;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFraming;
import com.linkedin.venice.utils.ByteUtils;
import java.nio.ByteBuffer;


/**
 * Subclass of {@link ReplicationMetadataRocksDBStoragePartition} that wraps {@code put},
 * {@code putWithReplicationMetadata}, and {@code merge} with the VT-merge experiment's
 * kind-byte framing, and folds concat blobs into materialized base bytes on {@code get}.
 *
 * <p>Used for AA store-versions when {@code server.vt.update.operand.enabled} is on.
 *
 * <p>The RMD column family is unaffected by framing — only the value column family carries
 * concat blobs (operands are merged into VALUE bytes, not RMD bytes). RMD writes via
 * {@code putReplicationMetadata} go through unchanged.
 *
 * <p>See {@link MaterializingFraming} for the wire-format details.
 */
public class MaterializingReplicationMetadataRocksDBStoragePartition
    extends ReplicationMetadataRocksDBStoragePartition {
  /**
   * VT-merge experiment Phase B: per-key chain-length backstop threshold. {@code <= 0} disables
   * the backstop. Cached at construction so each {@code merge()} call avoids re-resolving from
   * the factory.
   */
  private final int vtMergeMaxChainLength;

  public MaterializingReplicationMetadataRocksDBStoragePartition(
      StoragePartitionConfig storagePartitionConfig,
      RocksDBStorageEngineFactory factory,
      String dbDir,
      RocksDBMemoryStats rocksDBMemoryStats,
      RocksDBThrottler rocksDbThrottler,
      RocksDBServerConfig rocksDBServerConfig) {
    super(storagePartitionConfig, factory, dbDir, rocksDBMemoryStats, rocksDbThrottler, rocksDBServerConfig);
    this.vtMergeMaxChainLength = factory.getVtMergeMaxChainLength();
    org.apache.logging.log4j.LogManager.getLogger(MaterializingReplicationMetadataRocksDBStoragePartition.class)
        .info(
            "VT-merge: constructed materializing-RMD partition for storeVersion={} partition={}",
            storeNameAndVersion,
            partitionId);
  }

  /**
   * Chunk-fetch callback for the materializing read path. See
   * {@link MaterializingRocksDBStoragePartition#chunkFetch(byte[])} for rationale.
   */
  private byte[] chunkFetch(byte[] chunkKey) {
    return super.get(chunkKey);
  }

  /**
   * RMD-fetch callback for the materializing read path. Reads the on-disk replication metadata
   * bytes (with 4-byte valueSchemaId prefix) for the given key. Used by the fold path's V2
   * algorithm to seed cross-DC per-field-ts DCR (cross-DC fix per
   * {@code autoresearch/vt-merge-cross-dc-fix/GOAL.md}).
   *
   * <p>Returns null if no RMD is stored for the key (e.g. key has only received UPDATE
   * operands under flag-on, never a PUT/DELETE that would write RMD).
   */
  private byte[] rmdFetch(byte[] key) {
    return super.getReplicationMetadata(ByteBuffer.wrap(key));
  }

  /**
   * Override that synthesizes RMD from the operand chain when:
   * <ol>
   *   <li>on-disk RMD is null (e.g. key has only received UPDATE operands under flag-on), AND</li>
   *   <li>on-disk value bytes have an operand chain.</li>
   * </ol>
   * Lets the AA leader's RMW see the correct per-field-ts RMD on PUT-after-chain, preserving
   * element-level DCR across the leader's PUT-time merge with the existing chain state.
   */
  @Override
  public byte[] getReplicationMetadata(ByteBuffer key) {
    byte[] rmdBytes = super.getReplicationMetadata(key);
    System.err.println(
        "[VT-MERGE-RMD-GET] storeVersion=" + storeNameAndVersion + " partition=" + partitionId + " rmdLen="
            + (rmdBytes == null ? -1 : rmdBytes.length));
    if (rmdBytes != null && rmdBytes.length > 4) {
      return rmdBytes;
    }
    // RMD is null. Check if value has an operand chain; if so, synthesize RMD via fold.
    byte[] keyBytes = com.linkedin.venice.utils.ByteUtils.extractByteArray(key);
    byte[] rawValue = super.get(keyBytes);
    System.err.println(
        "[VT-MERGE-RMD-GET] storeVersion=" + storeNameAndVersion + " partition=" + partitionId + " rawValueLen="
            + (rawValue == null ? -1 : rawValue.length) + " firstByte="
            + (rawValue == null || rawValue.length == 0 ? "none" : String.format("0x%02x", rawValue[0] & 0xff)));
    if (rawValue == null || rawValue.length < 1) {
      return rmdBytes;
    }
    // Only synthesize when we have a real fold context registered.
    com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext ctx =
        com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContextRegistry.get(storeNameAndVersion);
    System.err.println(
        "[VT-MERGE-RMD-GET] storeVersion=" + storeNameAndVersion + " partition=" + partitionId + " ctxNull="
            + (ctx == null));
    if (ctx == null) {
      return rmdBytes;
    }
    try {
      byte[] synthesized = MaterializingFraming.maybeSynthesizeRmdFromChain(rawValue, storeNameAndVersion, ctx);
      System.err.println(
          "[VT-MERGE-RMD-SYNTH-OK] storeVersion=" + storeNameAndVersion + " partition=" + partitionId + " rawValueLen="
              + rawValue.length + " synthesizedRmdLen=" + (synthesized == null ? -1 : synthesized.length));
      return synthesized;
    } catch (Exception e) {
      System.err.println(
          "[VT-MERGE-RMD-SYNTH-ERR] storeVersion=" + storeNameAndVersion + " partition=" + partitionId + " err=" + e);
      e.printStackTrace(System.err);
      org.apache.logging.log4j.LogManager.getLogger(MaterializingReplicationMetadataRocksDBStoragePartition.class)
          .warn(
              "VT-merge: failed to synthesize RMD from chain for storeVersion={} partition={}; returning null",
              storeNameAndVersion,
              partitionId,
              e);
      return rmdBytes;
    }
  }

  @Override
  public byte[] get(byte[] key) {
    byte[] raw = super.get(key);
    if (raw != null && raw.length >= 5 && raw[com.linkedin.venice.utils.ByteUtils.SIZE_OF_INT] == 0x00) {
      org.apache.logging.log4j.LogManager.getLogger(MaterializingReplicationMetadataRocksDBStoragePartition.class)
          .debug(
              "VT-merge: get(byte[]) called: storeVersion={} partition={} keyLen={} rawLen={}",
              storeNameAndVersion,
              partitionId,
              key.length,
              raw.length);
    }
    return MaterializingFraming.materialize(raw, storeNameAndVersion, this::chunkFetch, key, this::rmdFetch);
  }

  // -------- WRITE PATH --------
  // Note: we do NOT override put(byte[], byte[]) because it forwards to put(byte[], ByteBuffer)
  // which we DO override. Overriding both would cause double-framing.

  @Override
  public synchronized void put(byte[] key, ByteBuffer valueBuffer) {
    if (MaterializingFraming.shouldBypassFraming(valueBuffer)) {
      super.put(key, valueBuffer);
      return;
    }
    byte[] framed = MaterializingFraming.frameForPut(valueBuffer);
    MaterializingFraming.beginFraming();
    try {
      super.put(key, ByteBuffer.wrap(framed));
    } finally {
      MaterializingFraming.endFraming();
    }
  }

  // Note: we override only the byte[]-form putWithReplicationMetadata. The ByteBuffer form in
  // the base class converts to byte[] and forwards to the byte[] form, which lands on our
  // override below. Overriding both would double-frame.
  //
  // Inside the deferred-write branch of the parent's putWithReplicationMetadata, the call
  // super.put(key, framedValue) lands BACK on our put(byte[], ByteBuffer) override via virtual
  // dispatch. We use the FRAMING_IN_PROGRESS thread-local re-entry guard to skip framing on the
  // recursive call. The non-deferred-write branch writes directly to the value column family
  // via writeBatch — that path receives our pre-framed bytes directly, no recursion concern.

  @Override
  public synchronized void putWithReplicationMetadata(byte[] key, byte[] value, byte[] metadata) {
    byte[] valueToWrite =
        MaterializingFraming.shouldBypassFraming(value) ? value : MaterializingFraming.frameForPut(value);
    MaterializingFraming.beginFraming();
    try {
      super.putWithReplicationMetadata(key, valueToWrite, metadata);
    } finally {
      MaterializingFraming.endFraming();
    }
  }

  @Override
  public synchronized void merge(byte[] key, ByteBuffer operand) {
    // Phase B chain-length backstop. See MaterializingRocksDBStoragePartition.merge for the
    // detailed contract; this RMD-aware partition uses the same backstop logic since the value
    // column family carries the concat blobs.
    ChainLengthBackstop.maybeBackstop(storeNameAndVersion, vtMergeMaxChainLength, () -> super.get(key), framedBytes -> {
      MaterializingFraming.beginFraming();
      try {
        super.put(key, ByteBuffer.wrap(framedBytes));
      } finally {
        MaterializingFraming.endFraming();
      }
    });
    // Chunked-manifest-specific backstop: ChainLengthBackstop above only handles concat blobs that
    // start with [schemaId][KIND_BASE][len][...] OR [KIND_OPERAND]; it can't parse chunked-manifest
    // blobs (which have a different prefix shape). For chunked-manifest keys we need our own
    // backstop to keep per-read fold cost bounded.
    // Threshold is 1 (collapse on first operand-on-chunked-manifest) — reads of a chunked-manifest
    // pay O(chunks) extra I/O plus O(operands) fold cost; collapsing to an inline base eliminates
    // both costs on subsequent reads.
    if (vtMergeMaxChainLength > 0) {
      byte[] framedReplacement = MaterializingFraming
          .maybeBackstopChunkedManifestChain(super.get(key), storeNameAndVersion, this::chunkFetch, 1);
      if (framedReplacement != null) {
        MaterializingFraming.beginFraming();
        try {
          super.put(key, ByteBuffer.wrap(framedReplacement));
        } finally {
          MaterializingFraming.endFraming();
        }
      }
    }
    byte[] framed = MaterializingFraming.frameForMerge(operand);
    MaterializingFraming.beginFraming();
    try {
      super.merge(key, ByteBuffer.wrap(framed));
    } finally {
      MaterializingFraming.endFraming();
    }
  }

  // -------- READ PATH --------
  // get(byte[]) is overridden above with diagnostic logging.

  @Override
  public ByteBuffer get(byte[] key, ByteBuffer valueToBePopulated) {
    byte[] raw = super.get(key);
    if (raw == null) {
      return null;
    }
    byte[] materialized =
        MaterializingFraming.materialize(raw, storeNameAndVersion, this::chunkFetch, key, this::rmdFetch);
    if (materialized == null) {
      return null;
    }
    if (materialized.length > valueToBePopulated.capacity()) {
      valueToBePopulated = ByteBuffer.allocate(materialized.length);
    }
    valueToBePopulated.position(0);
    valueToBePopulated.put(materialized);
    valueToBePopulated.position(0);
    valueToBePopulated.limit(materialized.length);
    return valueToBePopulated;
  }

  @Override
  public byte[] get(ByteBuffer keyBuffer) {
    byte[] keyBytes = ByteUtils.extractByteArray(keyBuffer);
    byte[] raw = super.get(keyBytes);
    if (raw != null && raw.length > 0) {
      StringBuilder hex = new StringBuilder(Math.min(raw.length, 64) * 3);
      int n = Math.min(raw.length, 64);
      for (int i = 0; i < n; i++) {
        hex.append(String.format("%02x ", raw[i] & 0xff));
      }
      org.apache.logging.log4j.LogManager.getLogger(MaterializingReplicationMetadataRocksDBStoragePartition.class)
          .debug(
              "VT-merge: get(ByteBuffer) raw bytes: storeVersion={} partition={} rawLen={} firstBytes={}",
              storeNameAndVersion,
              partitionId,
              raw.length,
              hex);
    }
    return MaterializingFraming.materialize(raw, storeNameAndVersion, this::chunkFetch, keyBytes, this::rmdFetch);
  }

  /** Bypass-the-fold accessor used by the sweeper. */
  public byte[] getRaw(byte[] key) {
    return super.get(key);
  }
}
