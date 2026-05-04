package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import java.nio.ByteBuffer;


/**
 * Phase A callback: echoes the input bytes, mimicking the work shape Phase B's
 * real callback will do, without doing any actual fold work. Used for the
 * JNI per-call cost microbenchmark.
 *
 * <p>The {@link #foldConcatBlob} method has the same signature the real Phase B
 * callback ({@code VeniceConcatFoldNativeCallback}) will expose, so the JNI
 * symbol cache and round-trip plumbing measured in Phase A is directly
 * applicable to Phase B.
 *
 * <p>Behavior:
 * <ul>
 *   <li>If the input length is even → return a byte[] of the same content
 *       (mirrors CHANGE_VALUE).</li>
 *   <li>If the input length is odd → return null (mirrors KEEP).</li>
 *   <li>If the input length is 0 → throw a {@link RuntimeException} (used by
 *       the exception-handling probe).</li>
 * </ul>
 */
public final class EchoFoldCallback {
  /** Pre-allocated buffer reused per thread to avoid GC churn. Resized lazily. */
  private final ThreadLocal<byte[]> scratch = ThreadLocal.withInitial(() -> new byte[64]);

  /**
   * Called from native code via {@code CallObjectMethod}. JNI signature:
   * {@code (Ljava/nio/ByteBuffer;)[B}.
   */
  public byte[] foldConcatBlob(ByteBuffer inputView) {
    int len = inputView.remaining();
    if (len == 0) {
      throw new RuntimeException("EchoFoldCallback: empty input");
    }
    if ((len & 1) == 1) {
      return null; // KEEP
    }
    byte[] out = scratch.get();
    if (out.length < len) {
      out = new byte[Integer.highestOneBit(len - 1) << 1];
      scratch.set(out);
    }
    int pos = inputView.position();
    inputView.get(out, 0, len);
    inputView.position(pos);
    // The native side calls GetByteArrayRegion(0, length) so it sees the
    // first 'len' bytes. We must therefore return an array sized exactly to
    // 'len' if we want byte-equivalence; for the hot-path measurement,
    // returning the scratch buffer would be wrong (length too large). Copy
    // out to an exact-sized array so the measurement reflects the real
    // production cost (which would also produce a fresh byte[] from
    // ConcatBlobParser.frameBase).
    byte[] result = new byte[len];
    System.arraycopy(out, 0, result, 0, len);
    return result;
  }
}
