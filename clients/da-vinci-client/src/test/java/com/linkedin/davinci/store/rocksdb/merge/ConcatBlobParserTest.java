package com.linkedin.davinci.store.rocksdb.merge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

import com.linkedin.davinci.store.rocksdb.merge.ConcatBlobParser.Parsed;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.utils.ByteUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;


/**
 * Unit tests for {@link ConcatBlobParser}. Covers the three input shapes documented in
 * {@code GOAL.md} §3, plus property-based round-trip and adversarial inputs (operand payloads
 * containing the StringAppendOperator delimiter byte).
 */
public class ConcatBlobParserTest {
  /**
   * The structural delimiter byte that {@code RocksDBStoragePartition} configures the
   * StringAppendOperator with. The parser must be agnostic to its exact value, so we test with
   * both 0x01 (the value Venice's code currently uses) and 0x2C (the value GOAL.md §3 spec'd).
   */
  private static final byte DELIM_01 = 0x01;
  private static final byte DELIM_2C = 0x2C;

  // -------- Shape 1: materialized only --------

  @Test
  public void parseMaterializedOnlyReturnsBaseAndEmptyOperands() {
    byte[] avroBase = "hello-base-bytes".getBytes();
    byte[] blob = ConcatBlobParser.frameBase(42, avroBase);

    Parsed result = ConcatBlobParser.parse(blob);
    assertEquals(result.getSchemaId(), 42);
    assertTrue(result.hasBase());
    assertEquals(result.getBase(), avroBase);
    assertEquals(result.getOperands().size(), 0);
  }

  @Test
  public void parseMaterializedOnlyHandlesZeroLengthBase() {
    byte[] blob = ConcatBlobParser.frameBase(7, new byte[0]);
    Parsed result = ConcatBlobParser.parse(blob);
    assertEquals(result.getSchemaId(), 7);
    assertTrue(result.hasBase());
    assertEquals(result.getBase().length, 0);
    assertEquals(result.getOperands().size(), 0);
  }

  // -------- Shape 2: operand-only (first write was a merge) --------

  @Test
  public void parseOperandOnlySingleReturnsNullBaseAndOneOperand() {
    byte[] op = "operand-1-payload".getBytes();
    byte[] blob = ConcatBlobParser.frameOperand(op);

    Parsed result = ConcatBlobParser.parse(blob);
    assertEquals(result.getSchemaId(), ConcatBlobParser.NO_SCHEMA_ID_PRESENT);
    assertNull(result.getBase());
    assertEquals(result.getOperands().size(), 1);
    assertEquals(result.getOperands().get(0), op);
  }

  @Test(dataProvider = "delimiters")
  public void parseOperandOnlyChainReturnsAllOperandsInOrder(byte delim) {
    byte[] op1 = "first-op".getBytes();
    byte[] op2 = "second-op-with-different-len".getBytes();
    byte[] op3 = new byte[] { 0x33 };

    byte[] blob = concat(delim, ConcatBlobParser.frameOperand(op1), ConcatBlobParser.frameOperand(op2),
        ConcatBlobParser.frameOperand(op3));

    Parsed result = ConcatBlobParser.parse(blob);
    assertNull(result.getBase());
    assertEquals(result.getOperands().size(), 3);
    assertEquals(result.getOperands().get(0), op1);
    assertEquals(result.getOperands().get(1), op2);
    assertEquals(result.getOperands().get(2), op3);
  }

  // -------- Shape 3: base + operands --------

  @Test(dataProvider = "delimiters")
  public void parseBaseAndOperandsReturnsAll(byte delim) {
    byte[] base = "base-record".getBytes();
    byte[] op1 = "op1".getBytes();
    byte[] op2 = "op2-longer".getBytes();

    byte[] blob = concat(delim, ConcatBlobParser.frameBase(101, base), ConcatBlobParser.frameOperand(op1),
        ConcatBlobParser.frameOperand(op2));

    Parsed result = ConcatBlobParser.parse(blob);
    assertEquals(result.getSchemaId(), 101);
    assertEquals(result.getBase(), base);
    assertEquals(result.getOperands().size(), 2);
    assertEquals(result.getOperands().get(0), op1);
    assertEquals(result.getOperands().get(1), op2);
  }

  // -------- Adversarial: operand payloads contain delimiter byte --------

  @Test(dataProvider = "delimiters")
  public void parseHandlesOperandPayloadsContainingDelimiterByte(byte delim) {
    byte[] base = new byte[] { (byte) 0xAA, delim, (byte) 0xBB, delim, delim, 0x00 };
    byte[] op1 = new byte[] { delim, delim, delim, 0x42, delim };
    byte[] op2 = new byte[] { 0x01, delim, 0x01 }; // operand contains kind-like bytes
    byte[] op3 = new byte[] { delim };

    byte[] blob = concat(delim, ConcatBlobParser.frameBase(7, base), ConcatBlobParser.frameOperand(op1),
        ConcatBlobParser.frameOperand(op2), ConcatBlobParser.frameOperand(op3));

    Parsed result = ConcatBlobParser.parse(blob);
    assertEquals(result.getSchemaId(), 7);
    assertEquals(result.getBase(), base);
    assertEquals(result.getOperands().size(), 3);
    assertEquals(result.getOperands().get(0), op1);
    assertEquals(result.getOperands().get(1), op2);
    assertEquals(result.getOperands().get(2), op3);
  }

  @Test
  public void parseHandlesBasePayloadContainingKindBytes() {
    // Base content includes both 0x00 (KIND_BASE) and 0x01 (KIND_OPERAND) — these must NOT
    // confuse the parser since base length is determined by the varint, not by scanning.
    byte[] base = new byte[] { 0x01, 0x00, 0x01, 0x00, 0x00, 0x01 };
    byte[] op1 = new byte[] { 0x55, 0x66 };

    byte[] blob = concat(DELIM_01, ConcatBlobParser.frameBase(13, base), ConcatBlobParser.frameOperand(op1));
    Parsed result = ConcatBlobParser.parse(blob);
    assertEquals(result.getSchemaId(), 13);
    assertEquals(result.getBase(), base);
    assertEquals(result.getOperands().size(), 1);
    assertEquals(result.getOperands().get(0), op1);
  }

  // -------- Round-trip property test --------

  @Test
  public void roundTripPropertyTest() {
    Random rng = new Random(0xC0FFEEL);
    for (int trial = 0; trial < 200; trial++) {
      int schemaId = rng.nextInt(1000);
      int baseLen = rng.nextInt(2048);
      byte[] base = randomBytes(rng, baseLen);
      int numOps = rng.nextInt(8);
      byte[][] ops = new byte[numOps][];
      List<byte[]> opChunks = new ArrayList<>();
      opChunks.add(ConcatBlobParser.frameBase(schemaId, base));
      for (int i = 0; i < numOps; i++) {
        ops[i] = randomBytes(rng, rng.nextInt(512));
        opChunks.add(ConcatBlobParser.frameOperand(ops[i]));
      }
      byte delim = (byte) (rng.nextBoolean() ? DELIM_01 : DELIM_2C);
      byte[] blob = concat(delim, opChunks.toArray(new byte[0][]));

      Parsed result = ConcatBlobParser.parse(blob);
      assertEquals(result.getSchemaId(), schemaId, "trial " + trial);
      assertEquals(result.getBase(), base, "trial " + trial);
      assertEquals(result.getOperands().size(), numOps, "trial " + trial);
      for (int i = 0; i < numOps; i++) {
        assertEquals(result.getOperands().get(i), ops[i], "trial " + trial + " op " + i);
      }
    }
  }

  @Test
  public void roundTripPropertyTestOperandOnly() {
    Random rng = new Random(0xDEADBEEFL);
    for (int trial = 0; trial < 100; trial++) {
      int numOps = 1 + rng.nextInt(10); // at least one operand
      byte[][] ops = new byte[numOps][];
      List<byte[]> chunks = new ArrayList<>();
      for (int i = 0; i < numOps; i++) {
        ops[i] = randomBytes(rng, rng.nextInt(256));
        chunks.add(ConcatBlobParser.frameOperand(ops[i]));
      }
      byte delim = (byte) (rng.nextBoolean() ? DELIM_01 : DELIM_2C);
      byte[] blob = concat(delim, chunks.toArray(new byte[0][]));

      Parsed result = ConcatBlobParser.parse(blob);
      assertEquals(result.getSchemaId(), ConcatBlobParser.NO_SCHEMA_ID_PRESENT, "trial " + trial);
      assertNull(result.getBase(), "trial " + trial);
      assertEquals(result.getOperands().size(), numOps, "trial " + trial);
      for (int i = 0; i < numOps; i++) {
        assertEquals(result.getOperands().get(i), ops[i], "trial " + trial + " op " + i);
      }
    }
  }

  // -------- Defensive: malformed input --------

  @Test
  public void parseRejectsNull() {
    try {
      ConcatBlobParser.parse(null);
      fail("expected VeniceException");
    } catch (VeniceException expected) {
    }
  }

  @Test
  public void parseRejectsEmpty() {
    try {
      ConcatBlobParser.parse(new byte[0]);
      fail("expected VeniceException");
    } catch (VeniceException expected) {
    }
  }

  @Test
  public void parseRejectsTruncatedBaseLen() {
    // schemaId + kind=0x00 + varint claims 99 bytes but the payload is only 3 bytes
    byte[] blob = new byte[ByteUtils.SIZE_OF_INT + 1 + 1 + 3];
    ByteUtils.writeInt(blob, 1, 0);
    blob[ByteUtils.SIZE_OF_INT] = ConcatBlobParser.KIND_BASE;
    blob[ByteUtils.SIZE_OF_INT + 1] = 99; // varint = 99
    // 3 bytes of "payload" (insufficient)
    blob[ByteUtils.SIZE_OF_INT + 2] = 'x';
    blob[ByteUtils.SIZE_OF_INT + 3] = 'y';
    blob[ByteUtils.SIZE_OF_INT + 4] = 'z';
    try {
      ConcatBlobParser.parse(blob);
      fail("expected VeniceException for truncated base");
    } catch (VeniceException expected) {
    }
  }

  @Test
  public void parseRejectsUnknownKindByte() {
    // 4-byte schemaId + a kind byte that's neither 0x00 nor 0x01.
    // Parser sees blob[4] != 0x00, falls through to operand-only branch, then sees blob[0] != 0x01.
    byte[] blob = new byte[] { 0, 0, 0, 1, 0x42, 0x43 };
    try {
      ConcatBlobParser.parse(blob);
      fail("expected VeniceException for unknown kind byte");
    } catch (VeniceException expected) {
    }
  }

  // -------- Varint encode/decode --------

  @Test
  public void varintEncodeDecodeRoundTrip() {
    int[] vals = { 0, 1, 127, 128, 16383, 16384, 2097151, 2097152, 268435455, 268435456, Integer.MAX_VALUE };
    for (int v: vals) {
      byte[] encoded = ConcatBlobParser.encodeVarint(v);
      ConcatBlobParser.VarintResult decoded = ConcatBlobParser.readVarint(encoded, 0);
      assertEquals(decoded.value, v, "value " + v);
      assertEquals(decoded.nextOffset, encoded.length, "offset for value " + v);
    }
  }

  // -------- Helpers --------

  @DataProvider(name = "delimiters")
  public Object[][] delimiters() {
    return new Object[][] { { DELIM_01 }, { DELIM_2C } };
  }

  /** Concatenate {@code chunks} with {@code delim} between successive elements. */
  private static byte[] concat(byte delim, byte[]... chunks) {
    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      for (int i = 0; i < chunks.length; i++) {
        if (i > 0) {
          out.write(delim);
        }
        out.write(chunks[i]);
      }
      return out.toByteArray();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private static byte[] randomBytes(Random rng, int len) {
    byte[] b = new byte[len];
    rng.nextBytes(b);
    return b;
  }
}
