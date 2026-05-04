package com.linkedin.venice.benchmark;

import com.linkedin.davinci.store.rocksdb.merge.jnibridge.EchoFoldCallback;
import com.linkedin.davinci.store.rocksdb.merge.jnibridge.VeniceJniBridge;
import java.io.File;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;


/**
 * Phase A JMH microbench for the JNI bridge round-trip cost. Measures the
 * per-call latency of {@code VeniceJniBridge.nativeRoundTrip}, which is the
 * Phase B compaction filter's hot path (JNI marshal + Java callback + JNI
 * return).
 *
 * <p>Run via:
 * <pre>{@code
 *   java -jar internal/venice-test-common/build/libs/venice-test-common-jmh.jar \
 *     com.linkedin.venice.benchmark.VeniceJniBridgeBenchmark \
 *     -Dvenice.jni.bridge.lib.path=.../libvenice_jni_bridge.so \
 *     -p valueSize=64,256,1024 -f 1 -wi 2 -w 5s -i 5 -r 5s
 * }</pre>
 *
 * <p>Phase A gate: ≤ 2 µs per call.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 2, time = 5)
@Measurement(iterations = 5, time = 5)
@Fork(1)
public class VeniceJniBridgeBenchmark {
  @Param({ "64", "256", "1024" })
  public int valueSize;

  private byte[] input;
  private EchoFoldCallback callback;

  @Setup
  public void setup() {
    String libPath = System.getProperty("venice.jni.bridge.lib.path");
    if (libPath == null || !new File(libPath).isFile()) {
      throw new IllegalStateException(
          "venice.jni.bridge.lib.path must point to libvenice_jni_bridge.so; got " + libPath);
    }
    VeniceJniBridge.loadFromAbsolutePath(libPath);
    callback = new EchoFoldCallback();
    VeniceJniBridge.nativeInit(callback, "foldConcatBlob", "(Ljava/nio/ByteBuffer;)[B");
    input = new byte[valueSize];
    for (int i = 0; i < valueSize; i++)
      input[i] = (byte) i;
  }

  @Benchmark
  public void singleRoundTrip(Blackhole bh) {
    bh.consume(VeniceJniBridge.nativeRoundTrip(input));
  }

  @Benchmark
  public void roundTripLoop1024(Blackhole bh) {
    bh.consume(VeniceJniBridge.nativeRoundTripLoop(input, 1024));
  }
}
