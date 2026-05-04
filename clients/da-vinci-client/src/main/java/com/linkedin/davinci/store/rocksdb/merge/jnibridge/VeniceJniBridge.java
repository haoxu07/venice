package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;


/**
 * Phase A JNI bridge: minimal C++ shim that exposes a single round-trip
 * call from Java into a per-thread cached method id and back. Used to
 * measure the steady-state per-call cost of the JNI plumbing the production
 * compaction filter (Phase B) will use.
 *
 * <p>The native library {@code libvenice_jni_bridge.so} is loaded either via
 * {@code System.loadLibrary} (if the standard library path picks it up) or
 * via {@link #loadFromAbsolutePath(String)} from a caller-supplied path. The
 * loader uses {@code System.load} — the library must be a real file, not a
 * resource inside a jar.
 *
 * <p>Phase A does NOT depend on rocksdb headers; the JNI symbols are bound
 * to a stand-alone C++ object with no rocksdb linkage. Phase B will replace
 * this with the actual {@code rocksdb::CompactionFilter} subclass.
 */
public final class VeniceJniBridge {
  private static volatile boolean LIBRARY_LOADED = false;

  private VeniceJniBridge() {
    // utility class
  }

  /** Load the native library from the given absolute path. Throws if it fails. */
  public static synchronized void loadFromAbsolutePath(String absolutePath) {
    if (LIBRARY_LOADED) {
      return;
    }
    File file = new File(absolutePath);
    if (!file.isFile()) {
      throw new RuntimeException("Native library not found: " + absolutePath);
    }
    System.load(absolutePath);
    LIBRARY_LOADED = true;
  }

  /**
   * Load the native library from a JAR resource (or a filesystem path supplied
   * via the {@code venice.jni.bridge.lib.path} system property). The latter
   * takes priority when set.
   */
  public static synchronized void loadDefault() throws IOException {
    if (LIBRARY_LOADED) {
      return;
    }
    String overridePath = System.getProperty("venice.jni.bridge.lib.path");
    if (overridePath != null && !overridePath.isEmpty()) {
      loadFromAbsolutePath(overridePath);
      return;
    }
    String resourceName = "/native/linux-x86_64/libvenice_jni_bridge.so";
    try (InputStream is = VeniceJniBridge.class.getResourceAsStream(resourceName)) {
      if (is == null) {
        throw new IOException("Native library resource missing: " + resourceName);
      }
      Path tmp = Files.createTempFile("libvenice_jni_bridge_", ".so");
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
   * Initialize the global callback object and method id used by all subsequent
   * {@link #nativeRoundTrip} calls. The callback is wrapped in a JNI global ref
   * inside the native library so it survives across compaction threads.
   *
   * @param callback the Java callback object (any class with a method matching
   *     {@code methodSig})
   * @param methodName name of the method to call on {@code callback}
   * @param methodSig JNI signature; for the production callback this is
   *     {@code "(Ljava/nio/ByteBuffer;)[B"}
   */
  public static native void nativeInit(Object callback, String methodName, String methodSig);

  /**
   * Single round-trip into the registered callback. Returns the length of the
   * value returned by Java (or the input length if the callback returned null,
   * mirroring KEEP), or a negative status code on error.
   */
  public static native int nativeRoundTrip(byte[] input);

  /**
   * Stress-test entry point: run the JNI hot path {@code iterations} times
   * entirely from C++ so the JMH measurement overhead is amortized. Returns
   * the total number of bytes the callbacks reported, used as a sanity-check
   * sum.
   */
  public static native long nativeRoundTripLoop(byte[] input, int iterations);

  /**
   * Exception-handling probe: invokes the callback once. If the callback
   * throws, the native code clears the exception and returns {@code -2};
   * else returns {@code 0}.
   */
  public static native int nativeProbeException(byte[] input);

  /**
   * Promote an already-loaded shared library from {@code RTLD_LOCAL} to
   * {@code RTLD_GLOBAL} so its symbols become visible to subsequently-loaded
   * libraries. Required because the JVM's {@code System.load} uses
   * {@code RTLD_LOCAL}, which hides rocksdbjni's internal C++ symbols (e.g.
   * {@code rocksdb::Customizable::GetOption}) from our Phase B native filter.
   *
   * <p>Returns 0 on success, -1 if the path could not be opened.
   */
  public static native int nativePromoteLibraryToGlobal(String absolutePath);
}
