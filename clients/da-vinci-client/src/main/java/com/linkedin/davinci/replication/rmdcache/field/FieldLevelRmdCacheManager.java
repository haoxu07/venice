package com.linkedin.davinci.replication.rmdcache.field;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Owner of per-partition {@link FieldLevelRmdCache} instances for one store-version.
 *
 * <p>Used by the AA flag-on path to filter losing UPDATE operands at merge time (i.e. at
 * follower consume time) and to seed the fold path's V2 algorithm with per-field-ts state.
 *
 * <p>Per the design in {@code autoresearch/vt-merge-cross-dc-fix/GOAL.md} §4, this lives at
 * the ingestion-task level (one per store-version) and is consulted by both
 * {@code ActiveActiveStoreIngestionTask} (merge path) and {@code MaterializingFoldContext}
 * (fold path).
 */
public class FieldLevelRmdCacheManager {
  private final ConcurrentHashMap<Integer, FieldLevelRmdCache> perPartitionCache = new ConcurrentHashMap<>();

  public FieldLevelRmdCache getOrCreate(int partitionId) {
    return perPartitionCache.computeIfAbsent(partitionId, FieldLevelRmdCache::new);
  }

  public FieldLevelRmdCache get(int partitionId) {
    return perPartitionCache.get(partitionId);
  }

  public Collection<FieldLevelRmdCache> getAllPartitionCaches() {
    return Collections.unmodifiableCollection(perPartitionCache.values());
  }

  public void removePartition(int partitionId) {
    perPartitionCache.remove(partitionId);
  }

  public long getDroppedOperandCountTotal() {
    long total = 0L;
    for (FieldLevelRmdCache c: perPartitionCache.values()) {
      total += c.getDroppedOperandCount();
    }
    return total;
  }
}
