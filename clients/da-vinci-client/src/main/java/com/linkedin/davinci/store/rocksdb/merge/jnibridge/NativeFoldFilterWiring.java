package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.rocksdb.ColumnFamilyOptions;


/**
 * Production wiring point for the native compaction filter (Phase B / Phase C).
 *
 * <p>Activation: set the system property {@code vt.merge.native.compaction.filter.enabled=true}.
 * Wired into {@code RocksDBStoragePartition} at the column-family-options setup site;
 * a no-op when the property is unset (production default).
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>On the first call, loads {@code libvenice_jni_bridge.so} and
 *       {@code libvenice_rocksdb_fold.so} via paths supplied by the system properties
 *       {@code venice.jni.bridge.lib.path} and {@code venice.rocksdb.fold.lib.path}.
 *       Promotes the already-loaded {@code librocksdbjni} to {@code RTLD_GLOBAL} so the
 *       fold lib can resolve rocksdbjni's hidden internal symbols.</li>
 *   <li>On each call, allocates a fresh {@link VeniceConcatFoldFilter} and registers it
 *       on the supplied {@code ColumnFamilyOptions} via {@code setCompactionFilter}.
 *       The caller owns the filter handle's lifetime and must call {@code close()} on
 *       partition shutdown.</li>
 * </ol>
 *
 * <p>The fold callback object is registered separately, once per JVM, by the ingestion
 * task that owns the per-store {@link com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext}.
 * The callback singleton is process-wide because the rocksdbjni native filter needs a
 * stable {@code jobject} global ref. The callback's per-store dispatch is handled
 * inside the Java callback via {@code MaterializingFoldContextRegistry}.
 */
public final class NativeFoldFilterWiring {
  private static final Logger LOGGER = LogManager.getLogger(NativeFoldFilterWiring.class);

  /** System property that enables the native filter. */
  public static final String ENABLED_PROP = "vt.merge.native.compaction.filter.enabled";

  /** Path to libvenice_jni_bridge.so (Phase A bridge — required for the symbol promotion). */
  public static final String BRIDGE_PATH_PROP = "venice.jni.bridge.lib.path";

  /** Path to libvenice_rocksdb_fold.so (Phase B production filter). */
  public static final String FOLD_PATH_PROP = "venice.rocksdb.fold.lib.path";

  private static volatile boolean LIBRARIES_LOADED = false;
  private static volatile boolean LIBRARY_LOAD_FAILED = false;

  private NativeFoldFilterWiring() {
    // utility class
  }

  /** True if the system property is set and the native libs are loadable. */
  public static boolean isEnabled() {
    return Boolean.getBoolean(ENABLED_PROP);
  }

  /**
   * Load the native libraries (idempotent). Safe to call from any thread; returns
   * {@code true} if both libs are loaded after the call. If library load fails, logs
   * and returns false; subsequent calls return false without retrying.
   */
  public static synchronized boolean ensureLibrariesLoaded() {
    if (LIBRARIES_LOADED) {
      return true;
    }
    if (LIBRARY_LOAD_FAILED) {
      return false;
    }
    String bridgePath = System.getProperty(BRIDGE_PATH_PROP);
    String foldPath = System.getProperty(FOLD_PATH_PROP);
    if (bridgePath == null || foldPath == null) {
      LOGGER.warn(
          "VT-merge native filter requested but {} or {} is not set; falling back to Java-only path",
          BRIDGE_PATH_PROP,
          FOLD_PATH_PROP);
      LIBRARY_LOAD_FAILED = true;
      return false;
    }
    try {
      // Ensure rocksdbjni is loaded BEFORE we attempt to promote it to GLOBAL.
      org.rocksdb.RocksDB.loadLibrary();
      VeniceJniBridge.loadFromAbsolutePath(bridgePath);
      String rocksdbjniPath = VeniceConcatFoldNative.findLoadedLibraryPath("librocksdbjni");
      if (rocksdbjniPath == null) {
        LOGGER.warn("VT-merge native filter: librocksdbjni not found in /proc/self/maps; falling back");
        LIBRARY_LOAD_FAILED = true;
        return false;
      }
      int rc = VeniceJniBridge.nativePromoteLibraryToGlobal(rocksdbjniPath);
      if (rc != 0) {
        LOGGER.warn("VT-merge native filter: failed to promote rocksdbjni to RTLD_GLOBAL; falling back");
        LIBRARY_LOAD_FAILED = true;
        return false;
      }
      VeniceConcatFoldNative.loadFromAbsolutePath(foldPath);
      LIBRARIES_LOADED = true;
      LOGGER.info("VT-merge native filter: libraries loaded from bridge={} fold={}", bridgePath, foldPath);
      return true;
    } catch (RuntimeException e) {
      LOGGER.warn("VT-merge native filter: library load failed; falling back to Java-only path", e);
      LIBRARY_LOAD_FAILED = true;
      return false;
    }
  }

  /**
   * Allocate a fresh {@link VeniceConcatFoldFilter} and register it on the supplied
   * {@code ColumnFamilyOptions} via {@code setCompactionFilter}. The returned filter's
   * lifetime must be managed by the caller (close it when the partition closes).
   *
   * <p>Returns {@code null} if libraries failed to load — caller proceeds with no
   * filter installed, identical to today's behavior.
   */
  public static VeniceConcatFoldFilter maybeAttach(ColumnFamilyOptions cfOptions) {
    if (!ensureLibrariesLoaded()) {
      return null;
    }
    VeniceConcatFoldFilter filter = new VeniceConcatFoldFilter();
    cfOptions.setCompactionFilter(filter);
    return filter;
  }

  /**
   * Register the global Java callback. Called once per JVM by the first ingestion task
   * that wires up a {@link com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext}.
   * The callback is process-wide because the C++ filter holds a single JNI global ref
   * to its target.
   */
  public static synchronized void registerCallback(VeniceConcatFoldNativeCallback callback) {
    if (!ensureLibrariesLoaded()) {
      return;
    }
    VeniceConcatFoldNative.nativeRegisterCallback(callback);
    LOGGER.info("VT-merge native filter: registered Java callback {}", callback);
  }
}
