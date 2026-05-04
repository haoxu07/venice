package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


/**
 * Phase B native filter loader + entry points.
 *
 * <p>The native library {@code libvenice_rocksdb_fold.so} contains a
 * subclass of {@code rocksdb::CompactionFilter} ("VeniceConcatFoldFilter")
 * that calls back into a registered Java callback for each compaction-output
 * value. The library compiles against rocksdb v9.11.2 headers so its vtable
 * layout matches rocksdbjni 9.11.2's internal RocksDB build.
 *
 * <p>Wiring (see GOAL §4):
 * <ol>
 *   <li>Load the library via {@link #loadDefault} or {@link #loadFromAbsolutePath}.</li>
 *   <li>Register the singleton callback via {@link #nativeRegisterCallback}
 *       — the callback object is the per-store {@code MaterializingFoldContext}
 *       wrapper, which exposes a {@code byte[] foldConcatBlob(ByteBuffer)} method.</li>
 *   <li>Allocate native filter instances via {@link #nativeCreateFilter} and
 *       hand the resulting {@code long} handle to a
 *       {@link org.rocksdb.AbstractCompactionFilter} subclass — see
 *       {@code VeniceConcatFoldFilterFactory}.</li>
 * </ol>
 */
public final class VeniceConcatFoldNative {
  private static volatile boolean LIBRARY_LOADED = false;

  private VeniceConcatFoldNative() {
    // utility class
  }

  public static synchronized void loadFromAbsolutePath(String absolutePath) {
    loadFromAbsolutePath(absolutePath, null);
  }

  /**
   * Load the production native fold library, optionally promoting rocksdbjni
   * to RTLD_GLOBAL first so the Customizable/Configurable base-class symbols
   * resolve at load time. Pass {@code rocksdbjniPath} as the absolute path to
   * the already-loaded librocksdbjni .so; pass {@code null} to skip the
   * promotion (only safe if rocksdbjni was loaded with RTLD_GLOBAL already,
   * which the standard JVM does NOT do).
   */
  public static synchronized void loadFromAbsolutePath(String absolutePath, String rocksdbjniPath) {
    if (LIBRARY_LOADED) {
      return;
    }
    File file = new File(absolutePath);
    if (!file.isFile()) {
      throw new RuntimeException("Native library not found: " + absolutePath);
    }
    if (rocksdbjniPath != null) {
      // VeniceJniBridge must be loaded first; it provides
      // nativePromoteLibraryToGlobal which dlopens rocksdbjni with
      // RTLD_GLOBAL|RTLD_NOLOAD to make its hidden symbols visible to us.
      if (!VeniceJniBridge.isLoaded()) {
        throw new RuntimeException(
            "VeniceJniBridge must be loaded before VeniceConcatFoldNative when rocksdbjniPath is supplied");
      }
      int rc = VeniceJniBridge.nativePromoteLibraryToGlobal(rocksdbjniPath);
      if (rc != 0) {
        throw new RuntimeException("Failed to promote rocksdbjni to RTLD_GLOBAL: " + rocksdbjniPath);
      }
    }
    System.load(absolutePath);
    LIBRARY_LOADED = true;
  }

  public static synchronized void loadDefault() throws IOException {
    if (LIBRARY_LOADED) {
      return;
    }
    String overridePath = System.getProperty("venice.rocksdb.fold.lib.path");
    if (overridePath != null && !overridePath.isEmpty()) {
      loadFromAbsolutePath(overridePath);
      return;
    }
    String resourceName = "/native/linux-x86_64/libvenice_rocksdb_fold.so";
    try (InputStream is = VeniceConcatFoldNative.class.getResourceAsStream(resourceName)) {
      if (is == null) {
        throw new IOException("Native library resource missing: " + resourceName);
      }
      Path tmp = Files.createTempFile("libvenice_rocksdb_fold_", ".so");
      Files.copy(is, tmp, StandardCopyOption.REPLACE_EXISTING);
      tmp.toFile().deleteOnExit();
      System.load(tmp.toAbsolutePath().toString());
      LIBRARY_LOADED = true;
    }
  }

  public static boolean isLoaded() {
    return LIBRARY_LOADED;
  }

  /**
   * Find the runtime path of an already-loaded shared library by scanning
   * {@code /proc/self/maps}. Used to discover where rocksdbjni extracted its
   * .so so we can pass the path to {@link #loadFromAbsolutePath(String, String)}.
   *
   * <p>{@code librarySubstring} is matched as a substring against each map
   * entry's path; e.g. "librocksdbjni" matches both
   * {@code librocksdbjni-linux64.so} and {@code librocksdbjni7234567.so}.
   *
   * @return the absolute path of the first matching library, or null if none.
   */
  public static String findLoadedLibraryPath(String librarySubstring) {
    File maps = new File("/proc/self/maps");
    if (!maps.canRead()) {
      return null;
    }
    try (BufferedReader br = new BufferedReader(new FileReader(maps))) {
      String line;
      while ((line = br.readLine()) != null) {
        // Each line: addr_range perms offset dev inode pathname
        int spaceIdx = line.lastIndexOf(' ');
        if (spaceIdx < 0) {
          continue;
        }
        String pathPart = line.substring(spaceIdx + 1).trim();
        if (pathPart.contains(librarySubstring) && pathPart.endsWith(".so")) {
          return pathPart;
        }
      }
    } catch (IOException e) {
      return null;
    }
    return null;
  }

  /**
   * Allocate a fresh {@code VeniceConcatFoldFilter} on the native heap and
   * return its raw pointer as a {@code long}. The pointer ABI matches what
   * {@code rocksdbjni}'s {@code AbstractCompactionFilterFactory} expects —
   * a {@code rocksdb::CompactionFilter*} that will be {@code unique_ptr}-deleted
   * by RocksDB at the end of its compaction job.
   */
  public static native long nativeCreateFilter();

  /**
   * Register the singleton Java callback object. The callback must implement
   * {@code byte[] foldConcatBlob(java.nio.ByteBuffer)} — typically the
   * {@code VeniceConcatFoldNativeCallback} wrapper around
   * {@code MaterializingFoldContext}.
   */
  public static native void nativeRegisterCallback(Object callback);

  /**
   * Read counters [calls, change, keep, exceptions] for observability/test.
   */
  public static native long[] nativeReadCounters();

  /** Reset counters. */
  public static native void nativeResetCounters();

  /**
   * Direct invoke of {@code FilterV2} on a previously-allocated filter handle —
   * exercises the same code path RocksDB will exercise during compaction, but
   * without standing up a real DB. Used by the byte-equivalence test.
   *
   * @return null if the filter returned KEEP; the new value bytes if CHANGE_VALUE.
   */
  public static native byte[] nativeInvokeFilter(long filterHandle, byte[] value);

  /** Free a filter pointer that was created via {@link #nativeCreateFilter}. */
  public static native void nativeDeleteFilter(long filterHandle);
}
