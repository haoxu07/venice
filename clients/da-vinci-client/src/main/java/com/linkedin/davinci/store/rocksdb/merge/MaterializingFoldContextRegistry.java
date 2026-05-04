package com.linkedin.davinci.store.rocksdb.merge;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


/**
 * Process-global registry mapping {@code storeNameAndVersion} (e.g.
 * {@code "store_name_v3"}) to the {@link MaterializingFoldContext} the
 * {@link com.linkedin.davinci.store.rocksdb.MaterializingRocksDBStoragePartition} should use to
 * fold concat blobs into materialized records on read.
 *
 * <p>Why a static registry: storage partitions are constructed by
 * {@link com.linkedin.davinci.store.rocksdb.RocksDBStorageEngine#createStoragePartition} which
 * runs as part of {@code StorageService} bootstrap, BEFORE the ingestion task and its schema
 * repository are wired. We can't pass the fold context through the partition's constructor
 * without restructuring the entire storage-engine factory chain (which would touch many call
 * sites unrelated to this experiment). Instead the ingestion task — which is what owns the
 * fold's dependencies — registers a fold context here when it starts a store-version, and the
 * partition looks it up lazily on each read.
 *
 * <p>Lifecycle is the integration tests' problem: register on ingestion-task start, unregister
 * on ingestion-task close. If a partition tries to fold without a registered context, the
 * partition falls back to returning raw concat-blob bytes (which downstream Avro decoding will
 * fail on, surfacing the missing-registration as a clear test failure).
 *
 * <p>Per the VT-merge experiment {@code GOAL.md} §4 Phase B.
 */
public final class MaterializingFoldContextRegistry {
  private static final ConcurrentMap<String, MaterializingFoldContext> REGISTRY = new ConcurrentHashMap<>();

  private MaterializingFoldContextRegistry() {
    // utility class
  }

  /** Register the fold context for {@code storeNameAndVersion}. Replaces any prior registration. */
  public static void register(String storeNameAndVersion, MaterializingFoldContext context) {
    REGISTRY.put(storeNameAndVersion, context);
  }

  /**
   * Look up the fold context for {@code storeNameAndVersion}, or {@code null} if none is
   * registered (e.g. before the ingestion task has started, or for store-versions that aren't
   * being ingested by this server).
   */
  public static MaterializingFoldContext get(String storeNameAndVersion) {
    return REGISTRY.get(storeNameAndVersion);
  }

  /** Remove the fold context for {@code storeNameAndVersion} (no-op if none registered). */
  public static void unregister(String storeNameAndVersion) {
    REGISTRY.remove(storeNameAndVersion);
  }

  /** Visible for testing. */
  public static void clearForTest() {
    REGISTRY.clear();
  }

  /**
   * Return the first registered context, or {@code null} if none. Used by the native
   * compaction filter callback's bootstrap path: in single-store-per-JVM deployments
   * (benchmarks, da-vinci-client embedded servers), the compaction filter only ever
   * sees values from one store-version, so any registered context is correct.
   * Multi-store production deployments need a different dispatch layer.
   */
  public static MaterializingFoldContext firstRegistered() {
    if (REGISTRY.isEmpty()) {
      return null;
    }
    return REGISTRY.values().iterator().next();
  }
}
