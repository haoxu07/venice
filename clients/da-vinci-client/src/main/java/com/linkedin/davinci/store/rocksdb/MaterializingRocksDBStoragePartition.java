package com.linkedin.davinci.store.rocksdb;

import com.linkedin.davinci.stats.RocksDBMemoryStats;
import com.linkedin.davinci.store.StoragePartitionConfig;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFraming;
import com.linkedin.venice.utils.ByteUtils;
import java.nio.ByteBuffer;


/**
 * Subclass of {@link RocksDBStoragePartition} that wraps {@code put} and {@code merge} with the
 * VT-merge experiment's kind-byte framing, and folds concat blobs into materialized base bytes
 * on {@code get}.
 *
 * <p>See {@link MaterializingFraming} for the wire format details.
 *
 * <p>This subclass is used for non-RMD store-versions. For AA stores (which are the common case
 * in the in-scope tests), see {@code MaterializingReplicationMetadataRocksDBStoragePartition}.
 */
public class MaterializingRocksDBStoragePartition extends RocksDBStoragePartition {
  public MaterializingRocksDBStoragePartition(
      StoragePartitionConfig storagePartitionConfig,
      RocksDBStorageEngineFactory factory,
      String dbDir,
      RocksDBMemoryStats rocksDBMemoryStats,
      RocksDBThrottler rocksDbThrottler,
      RocksDBServerConfig rocksDBServerConfig) {
    super(storagePartitionConfig, factory, dbDir, rocksDBMemoryStats, rocksDbThrottler, rocksDBServerConfig);
  }

  // -------- WRITE PATH --------
  //
  // Note: we do NOT override put(byte[], byte[]). The base class implementation forwards
  // put(byte[], byte[]) -> put(byte[], ByteBuffer.wrap(value)), which lands on our
  // put(byte[], ByteBuffer) override. Overriding the byte[] form would cause double-framing
  // because super.put(key, framed) would recurse back into our ByteBuffer override.

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
    byte[] raw = super.get(key);
    // Diagnostic: log presence/absence at storage level so we can tell whether the bug is
    // upstream (data never made it to disk) or downstream (data is here but reader doesn't see it).
    org.apache.logging.log4j.LogManager.getLogger(MaterializingRocksDBStoragePartition.class)
        .debug("VT-merge READ-DIAG[byte[]]: storeVersion={} keyLen={} rawNull={} rawLen={}",
            storeNameAndVersion, key.length, raw == null, raw == null ? -1 : raw.length);
    return MaterializingFraming.materialize(raw, storeNameAndVersion);
  }

  @Override
  public ByteBuffer get(byte[] key, ByteBuffer valueToBePopulated) {
    byte[] raw = super.get(key);
    org.apache.logging.log4j.LogManager.getLogger(MaterializingRocksDBStoragePartition.class)
        .debug("VT-merge READ-DIAG[byte[],BB]: storeVersion={} keyLen={} rawNull={} rawLen={}",
            storeNameAndVersion, key.length, raw == null, raw == null ? -1 : raw.length);
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
    byte[] raw = super.get(keyBytes);
    org.apache.logging.log4j.LogManager.getLogger(MaterializingRocksDBStoragePartition.class)
        .debug("VT-merge READ-DIAG[ByteBuffer]: storeVersion={} keyLen={} rawNull={} rawLen={}",
            storeNameAndVersion, keyBytes.length, raw == null, raw == null ? -1 : raw.length);
    return MaterializingFraming.materialize(raw, storeNameAndVersion);
  }

  /**
   * Bypass-the-fold accessor used by the sweeper (Phase D) to read the raw on-disk concat blob
   * without paying the materialization cost. Returns the same bytes that {@code rocksDB.get}
   * would return, including the kind-byte framing.
   */
  public byte[] getRaw(byte[] key) {
    return super.get(key);
  }
}
