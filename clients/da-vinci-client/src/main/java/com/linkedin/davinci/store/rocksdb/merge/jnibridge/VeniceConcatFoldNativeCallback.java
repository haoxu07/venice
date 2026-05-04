package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import com.linkedin.davinci.store.rocksdb.merge.ConcatBlobParser;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext;
import com.linkedin.venice.exceptions.VeniceException;
import java.nio.ByteBuffer;
import java.util.List;


/**
 * The Java callback the native compaction filter invokes for every
 * compaction-output value. Reuses the well-tested
 * {@link ConcatBlobParser} + {@link MaterializingFoldContext} machinery —
 * the whole point of the GOAL §0 "Path A2" approach is to keep all WC /
 * Avro logic in Java.
 *
 * <p>The native side calls {@link #foldConcatBlob} via JNI signature
 * {@code (Ljava/nio/ByteBuffer;)[B}. Returns:
 * <ul>
 *   <li>{@code null} → no change (signal KEEP to RocksDB).</li>
 *   <li>byte[] → CHANGE_VALUE: replace the on-disk value with these bytes.</li>
 * </ul>
 *
 * <p>Defensive: any exception thrown from this method is caught on the
 * native side via {@code ExceptionCheck} + {@code ExceptionClear}; the
 * filter then returns KEEP, so a Java-side fold failure never crashes
 * the engine.
 */
public final class VeniceConcatFoldNativeCallback {
  private final MaterializingFoldContext foldContext;

  public VeniceConcatFoldNativeCallback(MaterializingFoldContext foldContext) {
    if (foldContext == null) {
      throw new VeniceException("VeniceConcatFoldNativeCallback: foldContext is null");
    }
    this.foldContext = foldContext;
  }

  /**
   * Apply the operand chain (if any) to the base value bytes. Native side
   * passes a {@link ByteBuffer} that wraps a thread-local staging buffer
   * holding the concat-blob bytes.
   *
   * <p>Behavior cases (mirror {@code MaterializingRocksDBStoragePartition.get}):
   * <ul>
   *   <li>No operands → return null (KEEP, no rewrite needed).</li>
   *   <li>Base + operands → fold via {@code foldOperands}, reframe via
   *       {@code frameBase}, return the framed bytes.</li>
   *   <li>Operands only (no base on disk yet) → fold via
   *       {@code foldOperandOnly}, reframe.</li>
   *   <li>Tombstone (fold result is null) → return null (KEEP). RocksDB will
   *       process the delete via its own delete machinery; we don't try to
   *       transform a delete into a different shape here.</li>
   *   <li>Malformed blob or fold failure → caught and re-thrown; the native
   *       side clears the exception and returns KEEP.</li>
   * </ul>
   */
  public byte[] foldConcatBlob(ByteBuffer inputView) {
    if (inputView == null) {
      return null;
    }
    int len = inputView.remaining();
    if (len == 0) {
      return null;
    }
    byte[] input = new byte[len];
    int origPos = inputView.position();
    inputView.get(input);
    inputView.position(origPos);

    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(input);
    List<byte[]> operands = parsed.getOperands();
    if (operands.isEmpty()) {
      return null; // KEEP
    }
    if (parsed.hasBase()) {
      byte[] folded = foldContext.foldOperands(parsed.getSchemaId(), parsed.getBase(), operands);
      if (folded == null) {
        // WC-delete tombstone — leave the value to RocksDB's delete handling.
        return null;
      }
      return ConcatBlobParser.frameBase(parsed.getSchemaId(), folded);
    }
    // Operand-only: fold against an empty base.
    MaterializingFoldContext.FoldOnlyResult result = foldContext.foldOperandOnly(operands);
    if (result.getBytes() == null) {
      return null;
    }
    return ConcatBlobParser.frameBase(result.getSchemaId(), result.getBytes());
  }
}
