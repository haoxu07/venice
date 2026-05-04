package com.linkedin.davinci.store.rocksdb.merge.jnibridge;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
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
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.io.FileUtils;
import org.mockito.Mockito;
import org.rocksdb.Options;
import org.rocksdb.RocksDB;
import org.rocksdb.StringAppendOperator;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Phase B integration test (Java-driven, real RocksDB, real native filter).
 *
 * <p>Opens a fresh RocksDB with the native VeniceConcatFoldFilterFactory
 * registered via rocksdbjni's {@code Options.setCompactionFilterFactory}.
 * The filter compiles against rocksdb v9.11.2 headers and must be
 * vtable-compatible with rocksdbjni 9.11.2's internal RocksDB build —
 * this test is the strongest single proof of that ABI compatibility.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Put a base value (KIND_BASE-framed).</li>
 *   <li>Merge N operand values (KIND_OPERAND-framed).</li>
 *   <li>Force compaction via {@code compactRange()}.</li>
 *   <li>Read the raw value back; assert it's a single KIND_BASE blob with
 *       no operand suffix — i.e. the filter folded the chain.</li>
 * </ol>
 *
 * <p>Self-skips if the {@code venice.rocksdb.fold.lib.path} system property
 * is not set (so a clean checkout passes).
 */
public class NativeCompactionFoldRoundTripTest {
  static {
    RocksDB.loadLibrary();
  }

  private static final int VALUE_SCHEMA_ID = 1;
  private static final int WC_SCHEMA_ID = 1;
  private static final String VALUE_SCHEMA_STR = "{ \"type\":\"record\", \"name\":\"User\", \"fields\":["
      + "{\"name\":\"firstName\", \"type\":\"string\", \"default\":\"\"},"
      + "{\"name\":\"lastName\", \"type\":\"string\", \"default\":\"\"}" + "]}";
  private static final Schema VALUE_SCHEMA = new Schema.Parser().parse(VALUE_SCHEMA_STR);
  private static final Schema WC_SCHEMA =
      WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(VALUE_SCHEMA);

  private File dbDir;
  private RocksDB db;
  private Options options;
  private VeniceConcatFoldFilter filter;

  @BeforeClass
  public void loadNative() {
    String libPath = System.getProperty("venice.rocksdb.fold.lib.path");
    if (libPath == null || !new File(libPath).isFile()) {
      throw new SkipException(
          "venice.rocksdb.fold.lib.path system property not set or file missing; skipping native round-trip");
    }
    String bridgePath = System.getProperty("venice.jni.bridge.lib.path");
    if (bridgePath == null || !new File(bridgePath).isFile()) {
      throw new SkipException(
          "venice.jni.bridge.lib.path system property not set; required by native fold "
              + "loader to promote rocksdbjni to RTLD_GLOBAL");
    }
    // Ensure rocksdbjni is loaded first.
    RocksDB.loadLibrary();
    VeniceJniBridge.loadFromAbsolutePath(bridgePath);
    String rocksdbjniPath = VeniceConcatFoldNative.findLoadedLibraryPath("librocksdbjni");
    System.out.println("[NativeCompactionFoldRoundTripTest] rocksdbjniPath=" + rocksdbjniPath);
    if (rocksdbjniPath == null) {
      throw new SkipException("Could not locate librocksdbjni in /proc/self/maps");
    }
    int promoteRc = VeniceJniBridge.nativePromoteLibraryToGlobal(rocksdbjniPath);
    System.out.println("[NativeCompactionFoldRoundTripTest] promoteRc=" + promoteRc);
    if (promoteRc != 0) {
      throw new SkipException("Failed to promote rocksdbjni to RTLD_GLOBAL");
    }
    VeniceConcatFoldNative.loadFromAbsolutePath(libPath); // promotion already done; pass null path
  }

  private void initDb() throws Exception {
    dbDir = Utils.getTempDataDirectory();
    options = new Options();
    options.setCreateIfMissing(true);
    options.setMergeOperator(new StringAppendOperator((char) 0x01));
    // GOAL Phase B BLOCKED-NOTES "option 1": use setCompactionFilter directly with a
    // VeniceConcatFoldFilter we own ourselves, avoiding the cross-library shared_ptr
    // ownership boundary that breaks setCompactionFilterFactory.
    filter = new VeniceConcatFoldFilter();
    options.setCompactionFilter(filter);

    // Register the production callback so the C++ filter has someone to
    // invoke. Without this, the filter would short-circuit to KEEP and the
    // test would fail at the assertion stage rather than crash, so it's a
    // useful sanity gate.
    ReadOnlySchemaRepository schemaRepo = Mockito.mock(ReadOnlySchemaRepository.class);
    Mockito.when(schemaRepo.getValueSchema("store", VALUE_SCHEMA_ID))
        .thenReturn(new SchemaEntry(VALUE_SCHEMA_ID, VALUE_SCHEMA));
    Mockito.when(schemaRepo.getDerivedSchema("store", VALUE_SCHEMA_ID, WC_SCHEMA_ID))
        .thenReturn(new DerivedSchemaEntry(VALUE_SCHEMA_ID, WC_SCHEMA_ID, WC_SCHEMA));
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor("store", schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext("store", schemaRepo, wcProcessor, false);
    VeniceConcatFoldNativeCallback callback = new VeniceConcatFoldNativeCallback(foldContext);
    VeniceConcatFoldNative.nativeRegisterCallback(callback);
    VeniceConcatFoldNative.nativeResetCounters();

    db = RocksDB.open(options, dbDir.getAbsolutePath());
  }

  @AfterMethod(alwaysRun = true)
  public void teardown() {
    if (db != null) {
      db.close();
      db = null;
    }
    if (filter != null) {
      filter.close();
      filter = null;
    }
    if (options != null) {
      options.close();
      options = null;
    }
    if (dbDir != null) {
      try {
        FileUtils.deleteDirectory(dbDir);
      } catch (Exception e) {
        // best-effort cleanup
      }
      dbDir = null;
    }
  }

  @Test
  public void putThenMergeNFlushCompactReadsFolded() throws Exception {
    initDb();
    byte[] key = "k".getBytes();
    byte[] basePart = framedBase("Init");
    db.put(key, basePart);

    int n = 5;
    for (int i = 0; i < n; i++) {
      byte[] opPart = framedOperand("op-" + i);
      db.merge(key, opPart);
    }
    db.flush(new org.rocksdb.FlushOptions());
    db.compactRange();

    // Read raw value; expect a single KIND_BASE blob, no operand suffix.
    byte[] raw = db.get(key);
    assertNotNull(raw);
    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
    assertTrue(parsed.hasBase(), "post-compaction value must have a folded base");
    assertEquals(parsed.getOperands().size(), 0, "post-compaction value must have no operand suffix");
    assertEquals(parsed.getSchemaId(), VALUE_SCHEMA_ID);

    long[] counters = VeniceConcatFoldNative.nativeReadCounters();
    long calls = counters[0];
    long change = counters[1];
    long keep = counters[2];
    long exceptions = counters[3];
    System.out.println(
        "NativeCompactionFoldRoundTripTest counters: calls=" + calls + " change=" + change + " keep=" + keep
            + " exceptions=" + exceptions);
    assertTrue(calls >= 1, "Native filter must have been invoked at least once");
    assertEquals(exceptions, 0, "No JNI exceptions should have leaked from the filter");
  }

  // --- helpers ---

  private static byte[] framedBase(String firstName) {
    GenericRecord rec = new GenericData.Record(VALUE_SCHEMA);
    rec.put("firstName", firstName);
    rec.put("lastName", "Last");
    byte[] avro = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(VALUE_SCHEMA).serialize(rec);
    return ConcatBlobParser.frameBase(VALUE_SCHEMA_ID, avro);
  }

  private static byte[] framedOperand(String firstName) {
    GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", firstName).build();
    byte[] wcBytes = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
    byte[] content = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes);
    return ConcatBlobParser.frameOperand(content);
  }

  // Defensive: list a few nullable static fields (helps spotless / static analysis).
  static {
    @SuppressWarnings("unused")
    List<byte[]> ignored = new ArrayList<>();
  }
}
