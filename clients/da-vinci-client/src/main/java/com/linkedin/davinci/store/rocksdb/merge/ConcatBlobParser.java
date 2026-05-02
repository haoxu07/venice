package com.linkedin.davinci.store.rocksdb.merge;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.utils.ByteUtils;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;


/**
 * Parser for the on-disk blob shape produced by RocksDB's {@code StringAppendOperator} when used
 * by the VT-merge experiment design (see {@code GOAL.md} §3).
 *
 * <h3>Wire format</h3>
 *
 * <p>Materialized record (from a {@code put} or after sweeper materialization):
 * <pre>{@code [ schemaId : int32 BE ][ kind=0x00 : 1B ][ payload-len : varint ][ avro-encoded value ] }</pre>
 *
 * <p>Operand record (from a {@code merge}):
 * <pre>{@code [ kind=0x01 : 1B ][ payload-len : varint ][ avro-WC payload ] }</pre>
 *
 * <p>After RocksDB's {@code StringAppendOperator} concatenation (with a 1-byte structural
 * delimiter between successive chunks — Venice currently uses {@code 0x01}; the parser is
 * delim-content-agnostic), the stored blob looks like one of:
 * <ul>
 *   <li>Materialized only: {@code [schemaId][0x00][len][avro-base]} — the result after a
 *       single Put with no merge operands yet.</li>
 *   <li>Operands only: {@code [0x01][len1][op1] DELIM [0x01][len2][op2] ...} — the result when
 *       the first write to the key was a Merge and no Put has happened yet.</li>
 *   <li>Base + operands: {@code [schemaId][0x00][len][avro-base] DELIM [0x01][len1][op1] DELIM ...}
 *       — the normal case after Put(base) + Merge(op1) + Merge(op2).</li>
 * </ul>
 *
 * <p>This deviates from the §3 spec only by adding the {@code [payload-len : varint]} prefix to
 * the materialized base; without it, the parser would have to scan for the StringAppendOperator
 * delimiter byte to find the base→operand boundary, which is unsafe because Avro-encoded base
 * bytes can contain any byte value (including the delimiter). The varint length makes the
 * boundary deterministic. See {@code phase-A-progress.md} for the rationale.
 *
 * <h3>Behavior</h3>
 *
 * <p>{@link #parse} returns a {@link Parsed} record carrying:
 * <ul>
 *   <li>{@code schemaId}: the int32 BE schemaId from the materialized prefix, or
 *       {@link #NO_SCHEMA_ID_PRESENT} if the blob has no materialized base.</li>
 *   <li>{@code base}: the {@code avro-encoded value} bytes (kind/length headers stripped), or
 *       {@code null} if no base is present.</li>
 *   <li>{@code operands}: list of {@code avro-WC payload} byte arrays in arrival order, headers
 *       stripped. Empty list if no operands.</li>
 * </ul>
 *
 * <p>Robust against operand payload bytes containing the StringAppendOperator delimiter — the
 * varint length is the authoritative payload boundary, the delimiter byte (when present) is
 * treated as opaque structural padding the parser skips.
 */
public final class ConcatBlobParser {
  /** Kind byte for a materialized base record. */
  public static final byte KIND_BASE = 0x00;
  /** Kind byte for an operand record. */
  public static final byte KIND_OPERAND = 0x01;
  /** Sentinel returned in {@link Parsed#schemaId} when the blob has no materialized base. */
  public static final int NO_SCHEMA_ID_PRESENT = -1;

  private static final int SCHEMA_ID_HEADER_LEN = ByteUtils.SIZE_OF_INT;

  private ConcatBlobParser() {
    // utility class
  }

  /**
   * Parse a concat-blob produced by {@link com.linkedin.davinci.store.rocksdb.MaterializingRocksDBStoragePartition}
   * + RocksDB's StringAppendOperator. Caller-provided {@code blob} is never mutated.
   *
   * @throws VeniceException if {@code blob} is null, empty, or structurally malformed.
   */
  public static Parsed parse(byte[] blob) {
    if (blob == null) {
      throw new VeniceException("ConcatBlobParser: blob is null");
    }
    if (blob.length == 0) {
      throw new VeniceException("ConcatBlobParser: blob is empty");
    }

    // First, decide if there's a materialized base prefix. The base case starts with
    // [schemaId : 4B][kind=0x00][len:varint][...]. If there's not enough room for the schemaId,
    // we treat as an operand-only blob (which never has a schemaId prefix).
    int schemaId = NO_SCHEMA_ID_PRESENT;
    byte[] base = null;
    int cursor = 0;

    // Discriminate between materialized-base form and operand-only form:
    //   materialized-base: [schemaId : 4B][kind=0x00 : 1B][len : varint][avro][optional concat ops]
    //   operand-only:      [kind=0x01 : 1B][len : varint][operand][optional more ops]
    //
    // We MUST check blob[0]==KIND_OPERAND first. The previous version only checked
    // blob[SCHEMA_ID_HEADER_LEN] == KIND_BASE, which collides for operand-only blobs whose
    // schemaId byte sequence happens to contain 0x00 at offset 4 (e.g., when the operand's
    // inner envelope embeds a 4-byte BE schema-id like 0x00 0x00 0x00 0x01 starting at offset 2,
    // the byte at offset 4 is 0x00 == KIND_BASE → false-positive base detection).
    if (blob[0] == KIND_OPERAND) {
      // Operand-only blob: parse as a chain starting at offset 0.
      // schemaId stays NO_SCHEMA_ID_PRESENT, base stays null, cursor stays 0.
    } else if (blob.length >= SCHEMA_ID_HEADER_LEN + 1 && blob[SCHEMA_ID_HEADER_LEN] == KIND_BASE) {
      schemaId = ByteUtils.readInt(blob, 0);
      cursor = SCHEMA_ID_HEADER_LEN + 1; // past schemaId + kind byte
      // Read varint length, then the base payload.
      VarintResult vlen = readVarint(blob, cursor);
      cursor = vlen.nextOffset;
      int baseLen = vlen.value;
      if (cursor + baseLen > blob.length) {
        throw new VeniceException(
            "ConcatBlobParser: declared base length " + baseLen + " exceeds remaining bytes "
                + (blob.length - cursor));
      }
      base = new byte[baseLen];
      System.arraycopy(blob, cursor, base, 0, baseLen);
      cursor += baseLen;
    } else {
      throw new VeniceException(
          "ConcatBlobParser: first byte is " + Integer.toHexString(blob[0] & 0xff)
              + " which is neither a materialized-base prefix nor the operand kind byte 0x01");
    }

    List<byte[]> operands = parseOperandChain(blob, cursor);
    return new Parsed(schemaId, base, operands);
  }

  /**
   * Walk the operand chain starting at {@code start}. Successive operands are separated by
   * exactly one structural delimiter byte (whose value is irrelevant — set by the
   * StringAppendOperator at construction time on the column family options). The parser only
   * cares about the {@code [kind=0x01][len:varint][payload]} structure of each operand.
   */
  private static List<byte[]> parseOperandChain(byte[] blob, int start) {
    if (start >= blob.length) {
      return Collections.emptyList();
    }
    List<byte[]> operands = new ArrayList<>();
    int cursor = start;
    while (cursor < blob.length) {
      // Skip exactly one delimiter byte if we're not at the very start of the operand region
      // (i.e. there was something before us — base, or a prior operand).
      if (cursor > start || start > 0) {
        // After a base or a prior operand, StringAppendOperator inserted a delimiter byte.
        cursor++;
        if (cursor >= blob.length) {
          throw new VeniceException(
              "ConcatBlobParser: trailing delimiter at end of blob with no operand following");
        }
      }
      byte kind = blob[cursor];
      if (kind != KIND_OPERAND) {
        throw new VeniceException(
            "ConcatBlobParser: expected operand kind byte 0x01 at offset " + cursor + " but found 0x"
                + Integer.toHexString(kind & 0xff));
      }
      cursor++;
      VarintResult vlen = readVarint(blob, cursor);
      cursor = vlen.nextOffset;
      int opLen = vlen.value;
      if (cursor + opLen > blob.length) {
        throw new VeniceException(
            "ConcatBlobParser: declared operand length " + opLen + " exceeds remaining bytes "
                + (blob.length - cursor));
      }
      byte[] op = new byte[opLen];
      System.arraycopy(blob, cursor, op, 0, opLen);
      operands.add(op);
      cursor += opLen;
    }
    return operands;
  }

  /**
   * Frame a base value as a materialized blob: {@code [schemaId : 4B BE][0x00][len:varint][value]}.
   * The {@code value} bytes are the avro-encoded record (without the schemaId prefix that
   * {@link com.linkedin.davinci.store.record.ValueRecord#serialize()} would prepend).
   */
  public static byte[] frameBase(int schemaId, byte[] value) {
    if (value == null) {
      throw new VeniceException("ConcatBlobParser.frameBase: value is null");
    }
    byte[] varlen = encodeVarint(value.length);
    byte[] out = new byte[SCHEMA_ID_HEADER_LEN + 1 + varlen.length + value.length];
    ByteUtils.writeInt(out, schemaId, 0);
    out[SCHEMA_ID_HEADER_LEN] = KIND_BASE;
    System.arraycopy(varlen, 0, out, SCHEMA_ID_HEADER_LEN + 1, varlen.length);
    System.arraycopy(value, 0, out, SCHEMA_ID_HEADER_LEN + 1 + varlen.length, value.length);
    return out;
  }

  /**
   * Frame an operand as an operand blob: {@code [0x01][len:varint][operand-bytes]}. The
   * {@code operand} bytes are the raw Avro WC payload as produced by the leader.
   */
  public static byte[] frameOperand(byte[] operand) {
    if (operand == null) {
      throw new VeniceException("ConcatBlobParser.frameOperand: operand is null");
    }
    byte[] varlen = encodeVarint(operand.length);
    byte[] out = new byte[1 + varlen.length + operand.length];
    out[0] = KIND_OPERAND;
    System.arraycopy(varlen, 0, out, 1, varlen.length);
    System.arraycopy(operand, 0, out, 1 + varlen.length, operand.length);
    return out;
  }

  /**
   * Encode {@code value} as an unsigned LEB128 varint. Used for the payload-length prefix.
   */
  public static byte[] encodeVarint(int value) {
    if (value < 0) {
      throw new VeniceException("ConcatBlobParser.encodeVarint: negative value " + value);
    }
    int v = value;
    int n = 0;
    int t = v;
    do {
      n++;
      t >>>= 7;
    } while (t != 0);
    byte[] out = new byte[n];
    int i = 0;
    while ((v & ~0x7F) != 0) {
      out[i++] = (byte) ((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    out[i] = (byte) (v & 0x7F);
    return out;
  }

  /** Decode an unsigned LEB128 varint at {@code offset}, returning value + next-offset. */
  static VarintResult readVarint(byte[] blob, int offset) {
    int result = 0;
    int shift = 0;
    int cursor = offset;
    while (cursor < blob.length) {
      byte b = blob[cursor++];
      result |= (b & 0x7F) << shift;
      if ((b & 0x80) == 0) {
        return new VarintResult(result, cursor);
      }
      shift += 7;
      if (shift >= 32) {
        throw new VeniceException("ConcatBlobParser.readVarint: overflow at offset " + offset);
      }
    }
    throw new VeniceException("ConcatBlobParser.readVarint: truncated varint at offset " + offset);
  }

  /** Result of {@link #parse}. */
  public static final class Parsed {
    private final int schemaId;
    private final byte[] base;
    private final List<byte[]> operands;

    Parsed(int schemaId, byte[] base, List<byte[]> operands) {
      this.schemaId = schemaId;
      this.base = base;
      this.operands = operands;
    }

    /** SchemaId from the materialized prefix, or {@link #NO_SCHEMA_ID_PRESENT}. */
    public int getSchemaId() {
      return schemaId;
    }

    /** Avro-encoded base value bytes, or {@code null} if no base. */
    public byte[] getBase() {
      return base;
    }

    /** Operand payloads in arrival order. */
    public List<byte[]> getOperands() {
      return operands;
    }

    public boolean hasBase() {
      return base != null;
    }
  }

  /** Result of decoding a varint: value plus offset of the byte after the varint. */
  static final class VarintResult {
    final int value;
    final int nextOffset;

    VarintResult(int value, int nextOffset) {
      this.value = value;
      this.nextOffset = nextOffset;
    }
  }
}
