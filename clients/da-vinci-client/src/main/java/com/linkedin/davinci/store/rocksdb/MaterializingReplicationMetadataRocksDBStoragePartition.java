package com.linkedin.davinci.store.rocksdb;

import com.linkedin.davinci.stats.RocksDBMemoryStats;
import com.linkedin.davinci.store.StoragePartitionConfig;
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
public class MaterializingReplicationMetadataRocksDBStoragePartition extends ReplicationMetadataRocksDBStoragePartition {
  public MaterializingReplicationMetadataRocksDBStoragePartition(
      StoragePartitionConfig storagePartitionConfig,
      RocksDBStorageEngineFactory factory,
      String dbDir,
      RocksDBMemoryStats rocksDBMemoryStats,
      RocksDBThrottler rocksDbThrottler,
      RocksDBServerConfig rocksDBServerConfig) {
    super(storagePartitionConfig, factory, dbDir, rocksDBMemoryStats, rocksDbThrottler, rocksDBServerConfig);
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
    byte[] valueToWrite = MaterializingFraming.shouldBypassFraming(value)
        ? value
        : MaterializingFraming.frameForPut(value);
    MaterializingFraming.beginFraming();
    try {
      super.putWithReplicationMetadata(key, valueToWrite, metadata);
    } finally {
      MaterializingFraming.endFraming();
    }
  }

  @Override
  public synchronized void merge(byte[] key, ByteBuffer operand) {
    byte[] framed = MaterializingFraming.frameForMerge(operand);
    MaterializingFraming.beginFraming();
    try {
      super.merge(key, ByteBuffer.wrap(framed));
    } finally {
      MaterializingFraming.endFraming();
    }
  }

  // -------- READ PATH --------

  @Override
  public byte[] get(byte[] key) {
    return MaterializingFraming.materialize(super.get(key), storeNameAndVersion);
  }

  @Override
  public ByteBuffer get(byte[] key, ByteBuffer valueToBePopulated) {
    byte[] raw = super.get(key);
    if (raw == null) {
      return null;
    }
    byte[] materialized = MaterializingFraming.materialize(raw, storeNameAndVersion);
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
    return MaterializingFraming.materialize(super.get(keyBytes), storeNameAndVersion);
  }

  /** Bypass-the-fold accessor used by the sweeper. */
  public byte[] getRaw(byte[] key) {
    return super.get(key);
  }
}
