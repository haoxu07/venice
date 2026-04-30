package com.linkedin.venice.benchmark.lean;

import com.linkedin.venice.exceptions.VeniceNoStoreException;
import com.linkedin.venice.meta.BufferReplayPolicy;
import com.linkedin.venice.meta.HybridStoreConfigImpl;
import com.linkedin.venice.meta.OfflinePushStrategy;
import com.linkedin.venice.meta.PartitionerConfigImpl;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.meta.ReadOnlyStoreRepository;
import com.linkedin.venice.meta.ReadStrategy;
import com.linkedin.venice.meta.RoutingStrategy;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.StoreDataChangedListener;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.VersionImpl;
import com.linkedin.venice.meta.VersionStatus;
import com.linkedin.venice.meta.ZKStore;
import java.util.Collections;
import java.util.List;


/**
 * Lean in-memory implementation of {@link ReadOnlyStoreRepository} for the Active-Active ingestion harness.
 *
 * <p>Hosts exactly one {@link Store} (built via the production {@link ZKStore} class so the field-level layout is
 * byte-equivalent to what {@link com.linkedin.venice.helix.HelixReadOnlyStoreRepository} would expose) and exactly one
 * {@link Version} for that store. The store is configured to match the Phase-1 deliverable:
 *
 * <ul>
 *   <li>Active-Active replication enabled</li>
 *   <li>Write-compute enabled</li>
 *   <li>Native replication enabled</li>
 *   <li>Hybrid: rewind = 25 seconds, offset-lag threshold = 1</li>
 *   <li>Partition count = 2</li>
 *   <li>Single version (number = 1) marked {@code ONLINE} (ready-to-serve)</li>
 * </ul>
 *
 * <p>Lookups for any other store name throw {@link VeniceNoStoreException} so silent miss-as-null bugs are caught
 * loudly during ingestion. Listener registration is accepted but no-op (state never changes after construction).
 *
 * <p>This is the Phase 1 deliverable of the Lean AA Ingestion Harness project; see
 * {@code autoresearch/lean-aa-harness/GOAL.md} (entry #7 of {@code dep-graph.md}).
 */
public class InMemoryReadOnlyStoreRepository implements ReadOnlyStoreRepository {
  /** Default version number — the single ready-to-serve version. */
  public static final int DEFAULT_VERSION_NUMBER = 1;
  /** Default partition count required by the Phase-1 spec. */
  public static final int DEFAULT_PARTITION_COUNT = 2;
  /** Default hybrid rewind seconds required by the Phase-1 spec. */
  public static final long DEFAULT_HYBRID_REWIND_SECONDS = 25L;
  /** Default hybrid offset-lag threshold required by the Phase-1 spec. */
  public static final long DEFAULT_HYBRID_OFFSET_LAG_THRESHOLD = 1L;
  /**
   * Default RMD protocol version. Must match the version used by
   * {@link com.linkedin.venice.benchmark.lean.InMemoryReadOnlySchemaRepository#RMD_PROTOCOL_VERSION}.
   * VersionImpl defaults this to {@code -1}, which causes
   * {@code BiIntKeyCache.get(valueSchemaId, rmdVersionId)} inside the AA ingestion path to throw
   * {@code IllegalArgumentException: Index cannot be negative} on the first RT record.
   */
  public static final int DEFAULT_RMD_VERSION_ID = 1;

  private final Store store;

  /**
   * Construct the repository with default Phase-1-spec settings (AA + WC + hybrid + 2 partitions).
   *
   * @param storeName the single store name this repository serves.
   */
  public InMemoryReadOnlyStoreRepository(String storeName) {
    this(
        storeName,
        DEFAULT_PARTITION_COUNT,
        DEFAULT_HYBRID_REWIND_SECONDS,
        DEFAULT_HYBRID_OFFSET_LAG_THRESHOLD,
        DEFAULT_VERSION_NUMBER);
  }

  /**
   * Construct the repository with explicit settings. Useful for variants of the harness that need different
   * partition counts or rewind times.
   */
  public InMemoryReadOnlyStoreRepository(
      String storeName,
      int partitionCount,
      long hybridRewindSeconds,
      long hybridOffsetLagThreshold,
      int versionNumber) {
    if (storeName == null) {
      throw new IllegalArgumentException("storeName must not be null");
    }
    this.store = buildStore(storeName, partitionCount, hybridRewindSeconds, hybridOffsetLagThreshold, versionNumber);
  }

  private static Store buildStore(
      String storeName,
      int partitionCount,
      long hybridRewindSeconds,
      long hybridOffsetLagThreshold,
      int versionNumber) {
    // Use the production ZKStore class so the layout/equality is identical to what a real cluster would expose.
    ZKStore zkStore = new ZKStore(
        storeName,
        "lean-aa-harness",
        System.currentTimeMillis(),
        PersistenceType.ROCKS_DB,
        RoutingStrategy.CONSISTENT_HASH,
        ReadStrategy.ANY_OF_ONLINE,
        OfflinePushStrategy.WAIT_ALL_REPLICAS,
        versionNumber,
        Store.UNLIMITED_STORAGE_QUOTA,
        com.linkedin.venice.meta.AbstractStore.DEFAULT_READ_QUOTA,
        new HybridStoreConfigImpl(
            hybridRewindSeconds,
            hybridOffsetLagThreshold,
            HybridStoreConfigImpl.DEFAULT_HYBRID_TIME_LAG_THRESHOLD,
            BufferReplayPolicy.REWIND_FROM_EOP),
        new PartitionerConfigImpl(),
        1 /* replicationFactor */);
    zkStore.setPartitionCount(partitionCount);
    zkStore.setNativeReplicationEnabled(true);
    zkStore.setActiveActiveReplicationEnabled(true);
    zkStore.setWriteComputationEnabled(true);
    zkStore.setChunkingEnabled(false);
    zkStore.setRmdVersion(DEFAULT_RMD_VERSION_ID);
    // For the AA write-compute path, MergeConflictResolver.update() requires a non-null superset
    // value schema. Since this harness has only one value schema (id=1), that schema acts as the
    // superset. We need to advertise the id on the store so any code path that reads
    // {@code store.getLatestSuperSetValueSchemaId()} sees it (matches what the schema repo's
    // {@code getSupersetSchema()} returns).
    zkStore.setLatestSuperSetValueSchemaId(
        com.linkedin.venice.benchmark.lean.InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);
    // ZKStore constructor already sets leaderFollowerModelEnabled=true; no explicit setter needed.

    // Build version mirroring the store's hybrid + partitioner settings.
    VersionImpl version = new VersionImpl(storeName, versionNumber, "lean-aa-harness-push", partitionCount);
    version.setStatus(VersionStatus.ONLINE);
    version.setHybridStoreConfig(
        new HybridStoreConfigImpl(
            hybridRewindSeconds,
            hybridOffsetLagThreshold,
            HybridStoreConfigImpl.DEFAULT_HYBRID_TIME_LAG_THRESHOLD,
            BufferReplayPolicy.REWIND_FROM_EOP));
    version.setActiveActiveReplicationEnabled(true);
    version.setNativeReplicationEnabled(true);
    version.setUseVersionLevelHybridConfig(true);
    version.setUseVersionLevelIncrementalPushEnabled(true);
    version.setPartitionerConfig(new PartitionerConfigImpl());
    version.setRmdVersionId(DEFAULT_RMD_VERSION_ID);

    zkStore.addVersion(version);
    zkStore.setCurrentVersion(versionNumber);
    return zkStore;
  }

  @Override
  public Store getStore(String storeName) {
    return this.store.getName().equals(storeName) ? this.store : null;
  }

  @Override
  public Store getStoreOrThrow(String storeName) {
    Store s = getStore(storeName);
    if (s == null) {
      throw new VeniceNoStoreException(storeName);
    }
    return s;
  }

  @Override
  public boolean hasStore(String storeName) {
    return this.store.getName().equals(storeName);
  }

  @Override
  public Store refreshOneStore(String storeName) {
    return getStore(storeName);
  }

  @Override
  public List<Store> getAllStores() {
    return Collections.singletonList(this.store);
  }

  @Override
  public long getTotalStoreReadQuota() {
    return this.store.getReadQuotaInCU();
  }

  @Override
  public void registerStoreDataChangedListener(StoreDataChangedListener listener) {
    // No-op: store metadata is static for the lifetime of the harness.
  }

  @Override
  public void unregisterStoreDataChangedListener(StoreDataChangedListener listener) {
    // No-op.
  }

  @Override
  public int getBatchGetLimit(String storeName) {
    Store s = getStoreOrThrow(storeName);
    return s.getBatchGetLimit();
  }

  @Override
  public boolean isReadComputationEnabled(String storeName) {
    Store s = getStoreOrThrow(storeName);
    return s.isReadComputationEnabled();
  }

  @Override
  public void refresh() {
    // No-op.
  }

  @Override
  public void clear() {
    // No-op.
  }
}
