package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

import java.io.File;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Phase A JNI bridge unit + stress tests. Runs sub-second for the basic
 * round-trip cases; the {@code stressMillionIterations} test is bounded at
 * 1M iterations and runs in roughly 1–3 seconds on a single core.
 *
 * <p>The native library is loaded from the path supplied via the
 * {@code venice.jni.bridge.lib.path} system property. If the property is not
 * set, the test self-skips (so a clean checkout without the prebuilt .so
 * still passes). The ./gradlew invocation in the Phase A workflow sets this
 * property to {@code autoresearch/.../native-bin/linux-x86_64/libvenice_jni_bridge.so}.
 */
public class VeniceJniBridgeTest {
  private EchoFoldCallback callback;

  @BeforeClass
  public void setUp() {
    String libPath = System.getProperty("venice.jni.bridge.lib.path");
    if (libPath == null || libPath.isEmpty()) {
      throw new SkipException("venice.jni.bridge.lib.path system property not set; skipping Phase A JNI bridge tests");
    }
    if (!new File(libPath).isFile()) {
      throw new SkipException("Phase A native library missing at " + libPath);
    }
    VeniceJniBridge.loadFromAbsolutePath(libPath);
    callback = new EchoFoldCallback();
    VeniceJniBridge.nativeInit(callback, "foldConcatBlob", "(Ljava/nio/ByteBuffer;)[B");
  }

  @Test
  public void evenLengthInputEchoesAsChangeValue() {
    byte[] input = new byte[16]; // even → CHANGE_VALUE path
    for (int i = 0; i < 16; i++)
      input[i] = (byte) i;
    int returnedLength = VeniceJniBridge.nativeRoundTrip(input);
    assertEquals(returnedLength, 16, "Echo callback should report matching length");
  }

  @Test
  public void oddLengthInputReportsKeep() {
    byte[] input = new byte[15]; // odd → KEEP (null) path
    int returnedLength = VeniceJniBridge.nativeRoundTrip(input);
    // KEEP path returns the input length per the C++ contract.
    assertEquals(returnedLength, 15);
  }

  @Test
  public void exceptionPathRecoversCleanly() {
    // EchoFoldCallback throws on empty input; nativeProbeException must clear
    // and return -2 (no JVM crash).
    byte[] input = new byte[0];
    int code = VeniceJniBridge.nativeProbeException(input);
    assertEquals(code, -2, "Java exception path must be recovered cleanly");
  }

  @Test
  public void stressMillionIterations() {
    byte[] input = new byte[64];
    for (int i = 0; i < 64; i++)
      input[i] = (byte) i;
    long total = VeniceJniBridge.nativeRoundTripLoop(input, 1_000_000);
    // Each iteration returns exactly 64 bytes (even length → echo).
    assertEquals(total, 64L * 1_000_000L, "All 1M iterations must round-trip cleanly");
  }

  @Test
  public void microBenchSteadyStateUnder2Microseconds() {
    // Phase A gate: per-call cost must be < 2 µs across multiple value sizes
    // and sample rounds. We use the in-C++ tight loop so we measure the JNI
    // hot path directly without JMH/test infra overhead.
    int[] valueSizes = { 64, 256, 1024 };
    int iterations = 500_000;

    System.out.println("Phase A JNI-bridge per-call cost measurements:");
    System.out.println("valueSize\tround\tns_per_call\titerations");
    for (int valueSize: valueSizes) {
      byte[] input = new byte[valueSize];
      for (int i = 0; i < valueSize; i++)
        input[i] = (byte) i;
      // Warm up (JIT + ICache).
      VeniceJniBridge.nativeRoundTripLoop(input, 100_000);

      // 3 sample rounds per value size.
      for (int round = 1; round <= 3; round++) {
        long t0 = System.nanoTime();
        long total = VeniceJniBridge.nativeRoundTripLoop(input, iterations);
        long elapsedNs = System.nanoTime() - t0;
        assertEquals(total, (long) valueSize * iterations, "Round-trip count must match");

        double nsPerCall = (double) elapsedNs / iterations;
        System.out.println(valueSize + "\t" + round + "\t" + String.format("%.1f", nsPerCall) + "\t" + iterations);
        assertTrue(
            nsPerCall < 2000.0,
            "Phase A gate: per-call cost must be < 2 µs. valueSize=" + valueSize + " round=" + round + " measured="
                + nsPerCall + " ns");
      }
    }
  }
}
