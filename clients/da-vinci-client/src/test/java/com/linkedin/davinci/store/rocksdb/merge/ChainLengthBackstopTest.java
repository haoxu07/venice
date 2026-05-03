package com.linkedin.davinci.store.rocksdb.merge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.davinci.kafka.consumer.StoreWriteComputeProcessor;
import com.linkedin.davinci.schema.merge.CollectionTimestampMergeRecordHelper;
import com.linkedin.davinci.serializer.avro.MapOrderPreservingSerDeFactory;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.schema.writecompute.DerivedSchemaEntry;
import com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;


/**
 * Unit tests for {@link ChainLengthBackstop}. Boundary cases for the Phase B
 * chain-length-bounding logic: depth {@code maxChainLength-1}, {@code =maxChainLength},
 * the disabled flag, the operand-only branch, the missing-fold-context branch, and the
 * fold-failure branch.
 *
 * <p>Per the VT-merge experiment {@code GOAL.md} §3 Phase B / §5 unit-test cases.
 */
public class ChainLengthBackstopTest {
  private static final String STORE_VERSION = "store_v1";
  private static final String STORE_SHORT = "store";
  private static final int VALUE_SCHEMA_ID = 1;
  private static final int WC_SCHEMA_ID = 1;
  private static final String VALUE_SCHEMA_STR = "{ \"type\":\"record\", \"name\":\"User\", \"fields\":["
      + "{\"name\":\"firstName\", \"type\":\"string\", \"default\":\"\"},"
      + "{\"name\":\"lastName\", \"type\":\"string\", \"default\":\"\"}" + "]}";
  private static final Schema VALUE_SCHEMA = new Schema.Parser().parse(VALUE_SCHEMA_STR);
  private static final Schema WC_SCHEMA =
      WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(VALUE_SCHEMA);

  private MaterializingFoldContext foldContext;

  @BeforeMethod
  public void setUp() {
    ReadOnlySchemaRepository schemaRepo = Mockito.mock(ReadOnlySchemaRepository.class);
    Mockito.when(schemaRepo.getValueSchema(STORE_SHORT, VALUE_SCHEMA_ID))
        .thenReturn(new SchemaEntry(VALUE_SCHEMA_ID, VALUE_SCHEMA));
    Mockito.when(schemaRepo.getDerivedSchema(STORE_SHORT, VALUE_SCHEMA_ID, WC_SCHEMA_ID))
        .thenReturn(new DerivedSchemaEntry(VALUE_SCHEMA_ID, WC_SCHEMA_ID, WC_SCHEMA));
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(STORE_SHORT, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    foldContext = new MaterializingFoldContext(STORE_SHORT, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(STORE_VERSION, foldContext);
  }

  @AfterMethod(alwaysRun = true)
  public void tearDown() {
    MaterializingFoldContextRegistry.unregister(STORE_VERSION);
  }

  /**
   * Case 1: backstop disabled (maxChainLength <= 0). Even with a long chain, the backstop must
   * not fire.
   */
  @Test
  public void disabledThresholdSkipsBackstop() {
    byte[] blob = buildBaseAndOperandsBlob(50);
    AtomicReference<byte[]> putBytes = new AtomicReference<>();
    AtomicInteger getCalls = new AtomicInteger();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, 0 /* disabled */, () -> {
      getCalls.incrementAndGet();
      return blob;
    }, putBytes::set);

    assertFalse(fired);
    assertNull(putBytes.get(), "PUT writer must not be invoked when backstop is disabled");
    // The disabled-fast-path doesn't even bother calling the reader.
    assertEquals(getCalls.get(), 0, "raw reader must not be invoked when backstop is disabled");
  }

  /**
   * Case 2: chain depth strictly less than maxChainLength → backstop must NOT fire.
   */
  @Test
  public void chainDepthBelowThresholdDoesNotFire() {
    int maxChain = 64;
    byte[] blob = buildBaseAndOperandsBlob(maxChain - 1);
    AtomicReference<byte[]> putBytes = new AtomicReference<>();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, maxChain, () -> blob, putBytes::set);

    assertFalse(fired, "backstop fired below threshold");
    assertNull(putBytes.get(), "PUT writer must not be invoked below threshold");
  }

  /**
   * Case 3: chain depth exactly equals maxChainLength → backstop fires; PUT writer receives a
   * KIND_BASE-framed blob with no operand suffix.
   */
  @Test
  public void chainDepthAtThresholdFires() {
    int maxChain = 8;
    byte[] blob = buildBaseAndOperandsBlob(maxChain);
    AtomicReference<byte[]> putBytes = new AtomicReference<>();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, maxChain, () -> blob, putBytes::set);

    assertTrue(fired, "backstop did not fire at threshold");
    byte[] reframed = putBytes.get();
    assertNotNull(reframed, "PUT writer was not invoked");

    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(reframed);
    assertEquals(parsed.getSchemaId(), VALUE_SCHEMA_ID);
    assertTrue(parsed.hasBase(), "reframed blob has no base");
    assertEquals(parsed.getOperands().size(), 0, "reframed blob still has operands");
  }

  /**
   * Case 4: chain depth above threshold (maxChainLength + 1) → still fires.
   */
  @Test
  public void chainDepthAboveThresholdFires() {
    int maxChain = 4;
    byte[] blob = buildBaseAndOperandsBlob(maxChain + 5);
    AtomicReference<byte[]> putBytes = new AtomicReference<>();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, maxChain, () -> blob, putBytes::set);

    assertTrue(fired);
    assertNotNull(putBytes.get());
    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(putBytes.get());
    assertEquals(parsed.getOperands().size(), 0);
  }

  /**
   * Case 5: operand-only chain (no base) — backstop must fire when depth >= maxChainLength,
   * and the PUT writer gets a base-framed blob (the foldOperandOnly path).
   */
  @Test
  public void operandOnlyChainFires() {
    int maxChain = 3;
    byte[] blob = buildOperandOnlyBlob(maxChain);
    AtomicReference<byte[]> putBytes = new AtomicReference<>();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, maxChain, () -> blob, putBytes::set);

    assertTrue(fired);
    byte[] reframed = putBytes.get();
    assertNotNull(reframed);
    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(reframed);
    assertTrue(parsed.hasBase());
    assertEquals(parsed.getOperands().size(), 0);
  }

  /**
   * Case 6: missing fold context → backstop must NOT fire (best-effort skip).
   */
  @Test
  public void missingFoldContextSkipsBackstop() {
    MaterializingFoldContextRegistry.unregister(STORE_VERSION);
    int maxChain = 4;
    byte[] blob = buildBaseAndOperandsBlob(maxChain);
    AtomicReference<byte[]> putBytes = new AtomicReference<>();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, maxChain, () -> blob, putBytes::set);

    assertFalse(fired);
    assertNull(putBytes.get());
  }

  /**
   * Case 7: raw reader returns null (key absent) → backstop is a no-op.
   */
  @Test
  public void rawReaderNullSkipsBackstop() {
    AtomicReference<byte[]> putBytes = new AtomicReference<>();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, 4, () -> null, putBytes::set);

    assertFalse(fired);
    assertNull(putBytes.get());
  }

  /**
   * Case 8: raw reader throws → backstop swallows and skips (must not fail the merge).
   */
  @Test
  public void rawReaderThrowsSkipsBackstop() {
    AtomicReference<byte[]> putBytes = new AtomicReference<>();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, 4, () -> {
      throw new RuntimeException("simulated read failure");
    }, putBytes::set);

    assertFalse(fired);
    assertNull(putBytes.get());
  }

  /**
   * Case 9: malformed raw blob → parse fails; backstop skips.
   */
  @Test
  public void malformedBlobSkipsBackstop() {
    byte[] garbage = { 0x42, 0x42, 0x42 };
    AtomicReference<byte[]> putBytes = new AtomicReference<>();

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, 4, () -> garbage, putBytes::set);

    assertFalse(fired);
    assertNull(putBytes.get());
  }

  /**
   * Case 10: PUT writer throws → backstop returns false (chain depth not reset, but the merge
   * proceeds without a thrown exception bubbling up).
   */
  @Test
  public void putWriterThrowsBackstopReturnsFalse() {
    int maxChain = 4;
    byte[] blob = buildBaseAndOperandsBlob(maxChain);

    boolean fired = ChainLengthBackstop.maybeBackstop(STORE_VERSION, maxChain, () -> blob, framed -> {
      throw new RuntimeException("simulated PUT failure");
    });

    assertFalse(fired);
  }

  /**
   * Case 11: round-trip equivalence — for both the base+operands case and the operand-only
   * case, the read-path materialize of the original blob equals the read-path materialize of
   * the reframed (post-backstop) blob. This is the strongest correctness invariant: the
   * backstop must not change observable read semantics.
   */
  @Test
  public void roundTripEquivalenceBaseAndOperands() {
    int n = 5;
    byte[] originalBlob = buildBaseAndOperandsBlob(n);
    AtomicReference<byte[]> reframedRef = new AtomicReference<>();

    ChainLengthBackstop.maybeBackstop(STORE_VERSION, n /* fire at exactly n */, () -> originalBlob, reframedRef::set);
    byte[] reframed = reframedRef.get();
    assertNotNull(reframed);

    // Original blob, materialized via the read path: fold operands onto base.
    byte[] originalMaterialized = MaterializingFraming.materialize(originalBlob, STORE_VERSION);
    // Reframed blob, materialized via the read path: should yield the same materialized value
    // (no operands left to fold; just unwrap the base).
    byte[] reframedMaterialized = MaterializingFraming.materialize(reframed, STORE_VERSION);

    assertEquals(reframedMaterialized, originalMaterialized, "round-trip: read materialize differs after backstop");
  }

  @Test
  public void roundTripEquivalenceOperandOnly() {
    int n = 5;
    byte[] originalBlob = buildOperandOnlyBlob(n);
    AtomicReference<byte[]> reframedRef = new AtomicReference<>();

    ChainLengthBackstop.maybeBackstop(STORE_VERSION, n, () -> originalBlob, reframedRef::set);
    byte[] reframed = reframedRef.get();
    assertNotNull(reframed);

    byte[] originalMaterialized = MaterializingFraming.materialize(originalBlob, STORE_VERSION);
    byte[] reframedMaterialized = MaterializingFraming.materialize(reframed, STORE_VERSION);
    assertEquals(reframedMaterialized, originalMaterialized);
  }

  // ---- helpers ----

  /**
   * Build an on-disk concat blob shape: {@code [schemaId][0x00][len][avroBase] DELIM [0x01][len1][op1] ...}.
   * The base record is firstName="Init", lastName="Init"; each operand sets firstName="op-N".
   * The structural delimiter byte is 0x01 (the value Venice's StringAppendOperator uses).
   */
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

  /**
   * Build an operand-only on-disk concat blob: {@code [0x01][len1][op1] DELIM [0x01][len2][op2] ...}.
   */
  private static byte[] buildOperandOnlyBlob(int operandCount) {
    List<byte[]> operandParts = new ArrayList<>(operandCount);
    for (int i = 0; i < operandCount; i++) {
      operandParts.add(buildOperandPart("op-only-" + i));
    }
    return concatWithDelim(null /* no base */, operandParts);
  }

  private static byte[] buildOperandPart(String firstName) {
    GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", firstName).build();
    byte[] wcBytes = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
    byte[] content = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes);
    return ConcatBlobParser.frameOperand(content);
  }

  /**
   * Concatenate parts with a single delimiter byte (0x01) between successive parts —
   * matches what RocksDB's StringAppendOperator produces with delimiter 0x01.
   */
  private static byte[] concatWithDelim(byte[] basePart, List<byte[]> operandParts) {
    int total = 0;
    if (basePart != null) {
      total += basePart.length;
    }
    for (int i = 0; i < operandParts.size(); i++) {
      total += operandParts.get(i).length;
      // Delimiter goes between successive parts. If we have a base, every operand is preceded
      // by a delimiter. If we have no base, the FIRST operand is NOT preceded by a delimiter.
      if (basePart != null || i > 0) {
        total += 1;
      }
    }
    byte[] out = new byte[total];
    int cursor = 0;
    if (basePart != null) {
      System.arraycopy(basePart, 0, out, cursor, basePart.length);
      cursor += basePart.length;
    }
    for (int i = 0; i < operandParts.size(); i++) {
      if (basePart != null || i > 0) {
        out[cursor++] = (byte) 0x01;
      }
      byte[] op = operandParts.get(i);
      System.arraycopy(op, 0, out, cursor, op.length);
      cursor += op.length;
    }
    return out;
  }
}
