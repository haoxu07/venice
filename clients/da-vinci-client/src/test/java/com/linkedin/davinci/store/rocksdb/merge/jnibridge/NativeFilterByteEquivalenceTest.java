package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.davinci.kafka.consumer.StoreWriteComputeProcessor;
import com.linkedin.davinci.schema.merge.CollectionTimestampMergeRecordHelper;
import com.linkedin.davinci.serializer.avro.MapOrderPreservingSerDeFactory;
import com.linkedin.davinci.store.rocksdb.merge.ConcatBlobParser;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.schema.writecompute.DerivedSchemaEntry;
import com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.mockito.Mockito;
import org.testng.SkipException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Phase B byte-equivalence gate (GOAL §5).
 *
 * <p>For the same input concat-blob, asserts that:
 * <ol>
 *   <li>The native filter (via {@code nativeInvokeFilter}) and the Java
 *       callback (via {@code foldConcatBlob}) produce byte-identical results.</li>
 *   <li>For an already-folded input (no operands), both paths return null
 *       (KEEP) — no rewriting noise.</li>
 *   <li>For an operand-only input, both paths produce a folded base with
 *       byte-identical avro bytes underneath.</li>
 * </ol>
 *
 * <p>This is the "no fold-time divergence between Phase D's read-path
 * materialize and Phase A2's write-path filter" guarantee.
 */
public class NativeFilterByteEquivalenceTest {
  private static final String STORE_SHORT = "store";
  private static final int VALUE_SCHEMA_ID = 1;
  private static final int WC_SCHEMA_ID = 1;
  private static final String VALUE_SCHEMA_STR = "{ \"type\":\"record\", \"name\":\"User\", \"fields\":["
      + "{\"name\":\"firstName\", \"type\":\"string\", \"default\":\"\"},"
      + "{\"name\":\"lastName\", \"type\":\"string\", \"default\":\"\"}" + "]}";
  private static final Schema VALUE_SCHEMA = new Schema.Parser().parse(VALUE_SCHEMA_STR);
  private static final Schema WC_SCHEMA =
      WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(VALUE_SCHEMA);

  private VeniceConcatFoldNativeCallback callback;
  private long filterHandle;

  @BeforeClass
  public void setUp() {
    String foldLibPath = System.getProperty("venice.rocksdb.fold.lib.path");
    String bridgePath = System.getProperty("venice.jni.bridge.lib.path");
    if (foldLibPath == null || !new File(foldLibPath).isFile() || bridgePath == null
        || !new File(bridgePath).isFile()) {
      throw new SkipException(
          "venice.rocksdb.fold.lib.path or venice.jni.bridge.lib.path missing; skipping byte-equivalence test");
    }

    org.rocksdb.RocksDB.loadLibrary();
    VeniceJniBridge.loadFromAbsolutePath(bridgePath);
    String rocksdbjniPath = VeniceConcatFoldNative.findLoadedLibraryPath("librocksdbjni");
    if (rocksdbjniPath == null) {
      throw new SkipException("librocksdbjni not loaded yet");
    }
    int promoteRc = VeniceJniBridge.nativePromoteLibraryToGlobal(rocksdbjniPath);
    if (promoteRc != 0) {
      throw new SkipException("Failed to promote rocksdbjni to RTLD_GLOBAL");
    }
    VeniceConcatFoldNative.loadFromAbsolutePath(foldLibPath);

    ReadOnlySchemaRepository schemaRepo = Mockito.mock(ReadOnlySchemaRepository.class);
    Mockito.when(schemaRepo.getValueSchema(STORE_SHORT, VALUE_SCHEMA_ID))
        .thenReturn(new SchemaEntry(VALUE_SCHEMA_ID, VALUE_SCHEMA));
    Mockito.when(schemaRepo.getDerivedSchema(STORE_SHORT, VALUE_SCHEMA_ID, WC_SCHEMA_ID))
        .thenReturn(new DerivedSchemaEntry(VALUE_SCHEMA_ID, WC_SCHEMA_ID, WC_SCHEMA));
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(STORE_SHORT, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(STORE_SHORT, schemaRepo, wcProcessor, false);
    callback = new VeniceConcatFoldNativeCallback(foldContext);
    VeniceConcatFoldNative.nativeRegisterCallback(callback);
    filterHandle = VeniceConcatFoldNative.nativeCreateFilter();
  }

  @Test
  public void baseAndOperandsByteEquivalent() {
    byte[] blob = buildBaseAndOperandsBlob(5);
    byte[] javaResult = callback.foldConcatBlob(ByteBuffer.wrap(blob));
    byte[] nativeResult = VeniceConcatFoldNative.nativeInvokeFilter(filterHandle, blob);
    assertNotNull(javaResult);
    assertNotNull(nativeResult);
    assertEquals(nativeResult, javaResult, "Native and Java fold paths must produce byte-equivalent output");
  }

  @Test
  public void operandOnlyByteEquivalent() {
    byte[] blob = buildOperandOnlyBlob(3);
    byte[] javaResult = callback.foldConcatBlob(ByteBuffer.wrap(blob));
    byte[] nativeResult = VeniceConcatFoldNative.nativeInvokeFilter(filterHandle, blob);
    assertNotNull(javaResult);
    assertNotNull(nativeResult);
    assertEquals(nativeResult, javaResult);
  }

  @Test
  public void alreadyFoldedBothReturnKeep() {
    GenericRecord rec = new GenericData.Record(VALUE_SCHEMA);
    rec.put("firstName", "Folded");
    rec.put("lastName", "Final");
    byte[] avro = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(VALUE_SCHEMA).serialize(rec);
    byte[] blob = ConcatBlobParser.frameBase(VALUE_SCHEMA_ID, avro);

    byte[] javaResult = callback.foldConcatBlob(ByteBuffer.wrap(blob));
    byte[] nativeResult = VeniceConcatFoldNative.nativeInvokeFilter(filterHandle, blob);
    assertNull(javaResult, "already-folded → Java returns null");
    assertNull(nativeResult, "already-folded → native returns null");
  }

  @Test
  public void manyOperandLengthsByteEquivalent() {
    // Sweep operand counts to build confidence that the byte-equivalence
    // holds across the typical chain depths the filter sees in production.
    for (int n: new int[] { 1, 2, 4, 8, 16, 32, 64 }) {
      byte[] blob = buildBaseAndOperandsBlob(n);
      byte[] javaResult = callback.foldConcatBlob(ByteBuffer.wrap(blob));
      byte[] nativeResult = VeniceConcatFoldNative.nativeInvokeFilter(filterHandle, blob);
      assertNotNull(javaResult, "n=" + n);
      assertNotNull(nativeResult, "n=" + n);
      assertEquals(nativeResult, javaResult, "byte-equivalence failed at operandCount=" + n);
      // Also verify the result has no operand suffix.
      ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(nativeResult);
      assertTrue(parsed.hasBase(), "n=" + n);
      assertEquals(parsed.getOperands().size(), 0, "n=" + n);
    }
  }

  // ---- helpers ----

  private static byte[] buildBaseAndOperandsBlob(int operandCount) {
    GenericRecord baseRec = new GenericData.Record(VALUE_SCHEMA);
    baseRec.put("firstName", "Init");
    baseRec.put("lastName", "Init");
    byte[] avroBase = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(VALUE_SCHEMA).serialize(baseRec);
    byte[] basePart = ConcatBlobParser.frameBase(VALUE_SCHEMA_ID, avroBase);
    List<byte[]> operandParts = new ArrayList<>(operandCount);
    for (int i = 0; i < operandCount; i++) {
      operandParts.add(buildOperandPart("op-" + i));
    }
    return concatWithDelim(basePart, operandParts);
  }

  private static byte[] buildOperandOnlyBlob(int operandCount) {
    List<byte[]> operandParts = new ArrayList<>(operandCount);
    for (int i = 0; i < operandCount; i++) {
      operandParts.add(buildOperandPart("op-only-" + i));
    }
    return concatWithDelim(null, operandParts);
  }

  private static byte[] buildOperandPart(String firstName) {
    GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", firstName).build();
    byte[] wcBytes = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
    byte[] content = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes);
    return ConcatBlobParser.frameOperand(content);
  }

  private static byte[] concatWithDelim(byte[] basePart, List<byte[]> operandParts) {
    int total = 0;
    if (basePart != null)
      total += basePart.length;
    for (int i = 0; i < operandParts.size(); i++) {
      total += operandParts.get(i).length;
      if (basePart != null || i > 0)
        total += 1;
    }
    byte[] out = new byte[total];
    int cursor = 0;
    if (basePart != null) {
      System.arraycopy(basePart, 0, out, cursor, basePart.length);
      cursor += basePart.length;
    }
    for (int i = 0; i < operandParts.size(); i++) {
      if (basePart != null || i > 0)
        out[cursor++] = (byte) 0x01;
      byte[] op = operandParts.get(i);
      System.arraycopy(op, 0, out, cursor, op.length);
      cursor += op.length;
    }
    return out;
  }
}
