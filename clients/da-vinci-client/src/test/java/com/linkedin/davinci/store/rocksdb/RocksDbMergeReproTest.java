package com.linkedin.davinci.store.rocksdb;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.rocksdb.ColumnFamilyDescriptor;
import org.rocksdb.ColumnFamilyHandle;
import org.rocksdb.ColumnFamilyOptions;
import org.rocksdb.CompactionStyle;
import org.rocksdb.CompressionType;
import org.rocksdb.DBOptions;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.RocksIterator;
import org.rocksdb.StringAppendOperator;
import org.testng.annotations.Test;


/**
 * Standalone JNI-level reproduction of the StringAppendOperator merge behavior, outside any
 * Venice wrapping. The Phase C autonomous run halted with a Tier-3 hypothesis that
 * {@code rocksDB.merge()} silently no-ops in the multi-CF + derived-options construction style
 * Venice uses.
 *
 * <p>Each test prints what {@code Get} returns after a {@code Put + Merge + Merge} sequence.
 * Expected: the concatenated form. Anything else means the merge operator is not effective in
 * that variant.
 */
public class RocksDbMergeReproTest {
  static {
    RocksDB.loadLibrary();
  }

  private static final byte[] KEY = "k".getBytes();
  private static final byte[] BASE = "BASE".getBytes();
  private static final byte[] OP1 = "OP1".getBytes();
  private static final byte[] OP2 = "OP2".getBytes();

  /** Variant 1: single CF, operator set via the convenience builtin name. */
  @Test
  public void variant1_singleCf_operatorByName() throws Exception {
    Path dir = Files.createTempDirectory("rocks-merge-v1");
    try (Options opts = new Options().setCreateIfMissing(true).setMergeOperatorName("stringappend");
        RocksDB db = RocksDB.open(opts, dir.toString())) {
      db.put(KEY, BASE);
      db.merge(KEY, OP1);
      db.merge(KEY, OP2);
      byte[] got = db.get(KEY);
      System.out.println("[V1 single-cf, name] result: " + (got == null ? "null" : new String(got)));
    }
  }

  /** Variant 2: single CF, operator constructed explicitly with comma delimiter. */
  @Test
  public void variant2_singleCf_operatorExplicitComma() throws Exception {
    Path dir = Files.createTempDirectory("rocks-merge-v2");
    try (StringAppendOperator op = new StringAppendOperator(',');
        Options opts = new Options().setCreateIfMissing(true).setMergeOperator(op);
        RocksDB db = RocksDB.open(opts, dir.toString())) {
      db.put(KEY, BASE);
      db.merge(KEY, OP1);
      db.merge(KEY, OP2);
      byte[] got = db.get(KEY);
      System.out.println("[V2 single-cf, ',' delim] result: " + (got == null ? "null" : new String(got)));
    }
  }

  /** Variant 3: multi-CF, operator set on a fresh {@link ColumnFamilyOptions}, ',' delim. */
  @Test
  public void variant3_multiCf_operatorOnFreshCfOptions() throws Exception {
    Path dir = Files.createTempDirectory("rocks-merge-v3");
    DBOptions dbOptions = new DBOptions().setCreateIfMissing(true).setCreateMissingColumnFamilies(true);
    StringAppendOperator op = new StringAppendOperator(',');
    ColumnFamilyOptions defaultCfOpts = new ColumnFamilyOptions().setMergeOperator(op);
    ColumnFamilyOptions rmdCfOpts = new ColumnFamilyOptions();
    List<ColumnFamilyDescriptor> descriptors = Arrays.asList(
        new ColumnFamilyDescriptor("default".getBytes(), defaultCfOpts),
        new ColumnFamilyDescriptor("rmd".getBytes(), rmdCfOpts));
    List<ColumnFamilyHandle> handles = new ArrayList<>();
    try (RocksDB db = RocksDB.open(dbOptions, dir.toString(), descriptors, handles)) {
      ColumnFamilyHandle defaultCf = handles.get(0);
      db.put(defaultCf, KEY, BASE);
      db.merge(defaultCf, KEY, OP1);
      db.merge(defaultCf, KEY, OP2);
      byte[] got = db.get(defaultCf, KEY);
      System.out.println("[V3 multi-cf, fresh CfOptions] result: " + (got == null ? "null" : new String(got)));
    } finally {
      for (ColumnFamilyHandle h: handles) {
        h.close();
      }
      defaultCfOpts.close();
      rmdCfOpts.close();
      op.close();
      dbOptions.close();
    }
  }

  /** Variant 4: multi-CF, operator on parent {@link Options} then derived via {@code new ColumnFamilyOptions(opts)}. */
  @Test
  public void variant4_multiCf_operatorViaDerivedCfOptions() throws Exception {
    Path dir = Files.createTempDirectory("rocks-merge-v4");
    StringAppendOperator op = new StringAppendOperator(',');
    Options parentOpts = new Options().setCreateIfMissing(true).setMergeOperator(op);
    DBOptions dbOptions = new DBOptions(parentOpts).setCreateMissingColumnFamilies(true);
    ColumnFamilyOptions defaultCfOpts = new ColumnFamilyOptions(parentOpts);
    ColumnFamilyOptions rmdCfOpts = new ColumnFamilyOptions(parentOpts);
    List<ColumnFamilyDescriptor> descriptors = Arrays.asList(
        new ColumnFamilyDescriptor("default".getBytes(), defaultCfOpts),
        new ColumnFamilyDescriptor("rmd".getBytes(), rmdCfOpts));
    List<ColumnFamilyHandle> handles = new ArrayList<>();
    try (RocksDB db = RocksDB.open(dbOptions, dir.toString(), descriptors, handles)) {
      ColumnFamilyHandle defaultCf = handles.get(0);
      db.put(defaultCf, KEY, BASE);
      db.merge(defaultCf, KEY, OP1);
      db.merge(defaultCf, KEY, OP2);
      byte[] got = db.get(defaultCf, KEY);
      System.out
          .println("[V4 multi-cf, derived CfOptions Venice-style] result: " + (got == null ? "null" : new String(got)));
    } finally {
      for (ColumnFamilyHandle h: handles) {
        h.close();
      }
      defaultCfOpts.close();
      rmdCfOpts.close();
      parentOpts.close();
      op.close();
      dbOptions.close();
    }
  }

  /**
   * Variant 5: matches Venice's exact construction style — including {@code (char) 0x01} as the
   * StringAppendOperator delimiter, plus the same options Venice's
   * {@link RocksDBStoragePartition#getStoreOptions} sets that could plausibly interact with
   * merge. If this fails when V4 passes, we've localized the bug to a Venice option.
   */
  @Test
  public void variant5_veniceStyle_with0x01Delim_andSomeOptions() throws Exception {
    Path dir = Files.createTempDirectory("rocks-merge-v5");
    StringAppendOperator op = new StringAppendOperator((char) 0x01);
    Options parentOpts = new Options().setCreateIfMissing(true)
        .setMergeOperator(op)
        // A subset of options Venice's getStoreOptions sets:
        .setCompressionType(CompressionType.NO_COMPRESSION)
        .setCompactionStyle(CompactionStyle.LEVEL)
        .setBytesPerSync(1024 * 1024)
        .setMaxOpenFiles(-1)
        .setTargetFileSizeBase(64 * 1024 * 1024)
        .setMinWriteBufferNumberToMerge(1)
        .setStatsDumpPeriodSec(0)
        .setStatsPersistPeriodSec(0);
    DBOptions dbOptions = new DBOptions(parentOpts).setCreateMissingColumnFamilies(true);
    ColumnFamilyOptions defaultCfOpts = new ColumnFamilyOptions(parentOpts);
    // Also explicitly set the merge operator on the CF, like Venice's code does at line 274:
    defaultCfOpts.setMergeOperator(new StringAppendOperator((char) 0x01));
    ColumnFamilyOptions rmdCfOpts = new ColumnFamilyOptions(parentOpts);
    List<ColumnFamilyDescriptor> descriptors = Arrays.asList(
        new ColumnFamilyDescriptor("default".getBytes(), defaultCfOpts),
        new ColumnFamilyDescriptor("rmd".getBytes(), rmdCfOpts));
    List<ColumnFamilyHandle> handles = new ArrayList<>();
    try (RocksDB db = RocksDB.open(dbOptions, dir.toString(), descriptors, handles)) {
      ColumnFamilyHandle defaultCf = handles.get(0);
      db.put(defaultCf, KEY, BASE);
      db.merge(defaultCf, KEY, OP1);
      db.merge(defaultCf, KEY, OP2);
      byte[] got = db.get(defaultCf, KEY);
      // Render bytes as hex since 0x01 is a control char.
      StringBuilder hex = new StringBuilder();
      if (got != null) {
        for (byte b: got) {
          hex.append(String.format("%02x ", b & 0xff));
        }
      }
      System.out.println(
          "[V5 Venice-style 0x01 delim + Venice opts] resultLen=" + (got == null ? -1 : got.length) + " hex=" + hex);
    } finally {
      for (ColumnFamilyHandle h: handles) {
        h.close();
      }
      defaultCfOpts.close();
      rmdCfOpts.close();
      parentOpts.close();
      op.close();
      dbOptions.close();
    }
  }

  /**
   * Variant 6: if any prior variant returned just the base, this dumps every CF's contents to
   * see whether the operand bytes ended up in another CF. Run after writing put + merge + merge.
   * This uses V4's setup to start from a known-good baseline.
   */
  @Test
  public void variant6_dumpAllCfs() throws Exception {
    Path dir = Files.createTempDirectory("rocks-merge-v6");
    StringAppendOperator op = new StringAppendOperator((char) 0x01);
    Options parentOpts = new Options().setCreateIfMissing(true).setMergeOperator(op);
    DBOptions dbOptions = new DBOptions(parentOpts).setCreateMissingColumnFamilies(true);
    ColumnFamilyOptions defaultCfOpts = new ColumnFamilyOptions(parentOpts);
    defaultCfOpts.setMergeOperator(new StringAppendOperator((char) 0x01));
    ColumnFamilyOptions rmdCfOpts = new ColumnFamilyOptions(parentOpts);
    List<ColumnFamilyDescriptor> descriptors = Arrays.asList(
        new ColumnFamilyDescriptor("default".getBytes(), defaultCfOpts),
        new ColumnFamilyDescriptor("rmd".getBytes(), rmdCfOpts));
    List<ColumnFamilyHandle> handles = new ArrayList<>();
    try (RocksDB db = RocksDB.open(dbOptions, dir.toString(), descriptors, handles)) {
      ColumnFamilyHandle defaultCf = handles.get(0);
      db.put(defaultCf, KEY, BASE);
      db.merge(defaultCf, KEY, OP1);
      db.merge(defaultCf, KEY, OP2);

      System.out.println("[V6] dumping all CFs:");
      for (int i = 0; i < handles.size(); i++) {
        ColumnFamilyHandle cf = handles.get(i);
        System.out.println("  CF[" + i + "]: " + new String(descriptors.get(i).getName()));
        try (RocksIterator it = db.newIterator(cf)) {
          for (it.seekToFirst(); it.isValid(); it.next()) {
            StringBuilder kHex = new StringBuilder();
            for (byte b: it.key()) {
              kHex.append(String.format("%02x", b & 0xff));
            }
            StringBuilder vHex = new StringBuilder();
            for (byte b: it.value()) {
              vHex.append(String.format("%02x ", b & 0xff));
            }
            System.out.println("    key=" + kHex + " valueLen=" + it.value().length + " hex=" + vHex);
          }
        }
      }
    } finally {
      for (ColumnFamilyHandle h: handles) {
        h.close();
      }
      defaultCfOpts.close();
      rmdCfOpts.close();
      parentOpts.close();
      op.close();
      dbOptions.close();
    }
  }
}
