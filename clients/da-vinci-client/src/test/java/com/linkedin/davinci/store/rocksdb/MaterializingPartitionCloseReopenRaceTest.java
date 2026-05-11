package com.linkedin.davinci.store.rocksdb;

import static com.linkedin.venice.ConfigKeys.PERSISTENCE_TYPE;
import static com.linkedin.venice.ConfigKeys.SERVER_VT_UPDATE_OPERAND_ENABLED;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.davinci.config.VeniceServerConfig;
import com.linkedin.davinci.kafka.consumer.StoreWriteComputeProcessor;
import com.linkedin.davinci.schema.merge.CollectionTimestampMergeRecordHelper;
import com.linkedin.davinci.serializer.avro.MapOrderPreservingSerDeFactory;
import com.linkedin.davinci.store.AbstractStorageEngineTest;
import com.linkedin.davinci.store.StoragePartitionConfig;
import com.linkedin.davinci.store.rocksdb.merge.ConcatBlobParser;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContextRegistry;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.schema.writecompute.DerivedSchemaEntry;
import com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter;
import com.linkedin.venice.store.rocksdb.RocksDBUtils;
import com.linkedin.venice.utils.ByteUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.Properties;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.apache.commons.io.FileUtils;
import org.mockito.Mockito;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;


/**
 * H3 unit-test reproducer for the followers-race walkdown
 * (see {@code autoresearch/partial-update-followers-race-fix/HYPOTHESIS-WALKDOWN-GOAL.md} §3 Phase 4).
 *
 * <p>Hypothesis: {@code MaterializingFoldContextRegistry} or the partition's framing state is
 * reset/missing at merge time after a partition close+reopen cycle, causing the merge to land on
 * a partition object that has no base bytes. Symptom in production: the read back shows operand
 * only (no base), exactly matching the {@code [VT-MERGE-READBACK]} evidence from prior fix work.
 *
 * <p>Test shape (per GOAL §3 Phase 4 sketch):
 * <ol>
 *   <li>Register fold context (mirrors ingestion-task wiring)</li>
 *   <li>Open partition; {@code put} a base value</li>
 *   <li>Simulate the {@code adjustStoragePartition} close+reopen (the lifecycle event from
 *       BEGIN_BATCH_PUSH / END_BATCH_PUSH / PREPARE_FOR_READ)</li>
 *   <li>{@code merge} an operand on the reopened partition</li>
 *   <li>{@code getRaw} and assert the framing shape (BASE + delimiter + OPERAND, not OPERAND-only)</li>
 * </ol>
 *
 * <p>If this test reproduces the operand-only readback, H3 is CONFIRMED at the unit level and the
 * fix iterates against this test. If it does NOT reproduce, the bug lives in machinery this test
 * doesn't exercise (concurrent threads, multi-partition adjacency, late-replica bootstrap path,
 * etc.) — falls back to integration diagnostic.
 *
 * <p>Both partition classes are exercised by parameterized tests: the AA RMD-aware variant
 * (production class for the failing PartialUpdateTest) and the plain materializing variant.
 */
public class MaterializingPartitionCloseReopenRaceTest {
  private static final String DATA_BASE_DIR = Utils.getUniqueTempPath();
  private static final RocksDBThrottler ROCKSDB_THROTTLER = new RocksDBThrottler(3);

  private static final String VALUE_SCHEMA_STR = "{ \"type\":\"record\", \"name\":\"User\", \"fields\":["
      + "{\"name\":\"firstName\", \"type\":\"string\", \"default\":\"\"},"
      + "{\"name\":\"lastName\", \"type\":\"string\", \"default\":\"\"}" + "]}";
  private static final Schema VALUE_SCHEMA = new Schema.Parser().parse(VALUE_SCHEMA_STR);
  private static final Schema WC_SCHEMA =
      WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(VALUE_SCHEMA);
  private static final int VALUE_SCHEMA_ID = 1;
  private static final int WC_SCHEMA_ID = 1;

  @AfterMethod(alwaysRun = true)
  public void cleanRegistryAndDir() {
    MaterializingFoldContextRegistry.clearForTest();
    try {
      FileUtils.deleteDirectory(new File(DATA_BASE_DIR));
    } catch (Exception ignored) {
    }
  }

  /**
   * H3 reproducer for the AA RMD-aware partition (the class actually used by the failing
   * PartialUpdateTest).
   */
  @Test
  public void rmdPartition_putThenCloseReopenThenMerge_baseSurvives() throws Exception {
    String storeName = Version.composeKafkaTopic("h3-rmd-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    registerFoldContext(storeName, storeShortName);
    VeniceProperties veniceServerProperties = serverProps();
    RocksDBServerConfig rocksDBServerConfig = new RocksDBServerConfig(veniceServerProperties);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    String dbFolder = RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId);
    new File(dbFolder).getParentFile().mkdirs();

    byte[] keyBytes = "h3-rmd-k".getBytes();
    byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("Base", "Last"));

    // Open partition #1, put a base, close.
    MaterializingReplicationMetadataRocksDBStoragePartition partition1 =
        new MaterializingReplicationMetadataRocksDBStoragePartition(
            partitionConfig,
            factory,
            DATA_BASE_DIR,
            null,
            ROCKSDB_THROTTLER,
            rocksDBServerConfig);
    partition1.put(keyBytes, putBytes);
    // Close without explicit sync — same call shape as AbstractStorageEngine#closePartition.
    partition1.close();

    // Re-open at the SAME path with a fresh partition object — same call shape as
    // adjustStoragePartition's addStoragePartition after closePartition.
    MaterializingReplicationMetadataRocksDBStoragePartition partition2 =
        new MaterializingReplicationMetadataRocksDBStoragePartition(
            partitionConfig,
            factory,
            DATA_BASE_DIR,
            null,
            ROCKSDB_THROTTLER,
            rocksDBServerConfig);
    try {
      // Merge an operand on the reopened partition.
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "Merged").build();
      byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
      partition2.merge(keyBytes, ByteBuffer.wrap(op));

      // Inspect raw on-disk shape. With WAL off (the production default), if the close on
      // partition1 lost the in-memtable base, the readback would be OPERAND-only.
      byte[] raw = partition2.getRaw(keyBytes);
      assertNotNull(raw, "raw readback is null after merge — partition reopened with no value at all");
      assertTrue(raw.length >= 5, "raw too short to contain a kind byte: rawLen=" + raw.length);
      // Frame layout: [schemaId : 4B BE][kind : 1B][len : varint][bytes].
      // KIND_BASE indicates the base is present. KIND_OPERAND only would mean base was lost.
      ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
      assertEquals(parsed.getSchemaId(), VALUE_SCHEMA_ID, "schemaId mismatch on readback");
      // The critical check: the parsed blob MUST have a base (otherwise the merge readback
      // returns operand-only and the integration test fails with "expected [Merged] but found [Base]").
      assertNotNull(
          parsed.getBase(),
          "H3 REPRODUCED: readback has no base — the close on partition1 dropped the in-memtable "
              + "PUT and the merge on partition2 landed on a base-less partition. This is the "
              + "operand-only readback symptom from production.");
      assertEquals(parsed.getOperands().size(), 1, "Expected exactly one operand on the chain after a single merge");

      // Fold check: with the base present, the fold-on-get should produce the merged value.
      byte[] folded = partition2.get(keyBytes);
      assertNotNull(folded, "folded read returned null");
      byte[] avroOnly = new byte[folded.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(folded, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(
          decoded.get("firstName").toString(),
          "Merged",
          "Fold did not pick up the merged firstName — base or operand was lost");
      assertEquals(
          decoded.get("lastName").toString(),
          "Last",
          "Fold did not preserve the base lastName — base was lost");
    } finally {
      partition2.drop();
    }
  }

  /**
   * Same as the above but exercises the plain (non-RMD) {@link MaterializingRocksDBStoragePartition}.
   * If H3 reproduces only for the RMD variant, the bug is RMD-specific; if it reproduces for both,
   * it's a generic close+reopen + materializing-framing interaction.
   */
  @Test
  public void plainPartition_putThenCloseReopenThenMerge_baseSurvives() throws Exception {
    String storeName = Version.composeKafkaTopic("h3-plain-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    registerFoldContext(storeName, storeShortName);
    VeniceProperties veniceServerProperties = serverProps();
    RocksDBServerConfig rocksDBServerConfig = new RocksDBServerConfig(veniceServerProperties);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    String dbFolder = RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId);
    new File(dbFolder).getParentFile().mkdirs();

    byte[] keyBytes = "h3-plain-k".getBytes();
    byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("Base", "Last"));

    MaterializingRocksDBStoragePartition p1 = new MaterializingRocksDBStoragePartition(
        partitionConfig,
        factory,
        DATA_BASE_DIR,
        null,
        ROCKSDB_THROTTLER,
        rocksDBServerConfig);
    p1.put(keyBytes, putBytes);
    p1.close();

    MaterializingRocksDBStoragePartition p2 = new MaterializingRocksDBStoragePartition(
        partitionConfig,
        factory,
        DATA_BASE_DIR,
        null,
        ROCKSDB_THROTTLER,
        rocksDBServerConfig);
    try {
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "Merged").build();
      byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
      p2.merge(keyBytes, ByteBuffer.wrap(op));

      byte[] raw = p2.getRaw(keyBytes);
      assertNotNull(raw, "raw readback null after merge");
      ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
      assertNotNull(
          parsed.getBase(),
          "H3 REPRODUCED (plain variant): readback has no base — close+reopen dropped the PUT");
      assertEquals(parsed.getOperands().size(), 1);
    } finally {
      p2.drop();
    }
  }

  /**
   * Multi-key variant matching the production split-half pattern: PUT 14 keys, close+reopen,
   * merge on all 14. If H3's mechanism is "the close drops keys whose PUT was in the memtable",
   * we'd expect some fraction of keys (potentially all 14) to show operand-only readbacks.
   */
  @Test
  public void rmdPartition_multipleKeys_putCloseReopenMerge_allBasesSurvive() throws Exception {
    String storeName = Version.composeKafkaTopic("h3-multi-" + System.nanoTime(), 1);
    int partitionId = 0;
    int numKeys = 28; // matches the production "14 good / 14 bad" half-pattern

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    registerFoldContext(storeName, storeShortName);
    VeniceProperties veniceServerProperties = serverProps();
    RocksDBServerConfig rocksDBServerConfig = new RocksDBServerConfig(veniceServerProperties);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    String dbFolder = RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId);
    new File(dbFolder).getParentFile().mkdirs();

    byte[][] keys = new byte[numKeys][];
    for (int i = 0; i < numKeys; i++) {
      keys[i] = ("h3-multi-k" + i).getBytes();
    }

    MaterializingReplicationMetadataRocksDBStoragePartition p1 =
        new MaterializingReplicationMetadataRocksDBStoragePartition(
            partitionConfig,
            factory,
            DATA_BASE_DIR,
            null,
            ROCKSDB_THROTTLER,
            rocksDBServerConfig);
    for (int i = 0; i < numKeys; i++) {
      byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("Base" + i, "Last" + i));
      p1.put(keys[i], putBytes);
    }
    p1.close();

    MaterializingReplicationMetadataRocksDBStoragePartition p2 =
        new MaterializingReplicationMetadataRocksDBStoragePartition(
            partitionConfig,
            factory,
            DATA_BASE_DIR,
            null,
            ROCKSDB_THROTTLER,
            rocksDBServerConfig);
    int missingBase = 0;
    try {
      for (int i = 0; i < numKeys; i++) {
        GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "M" + i).build();
        byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
        byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
        p2.merge(keys[i], ByteBuffer.wrap(op));
      }
      for (int i = 0; i < numKeys; i++) {
        byte[] raw = p2.getRaw(keys[i]);
        if (raw == null) {
          missingBase++;
          continue;
        }
        ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
        if (parsed.getBase() == null) {
          missingBase++;
        }
      }
      assertEquals(
          missingBase,
          0,
          "H3 REPRODUCED: " + missingBase + " of " + numKeys + " keys show operand-only readbacks "
              + "after close+reopen — the production split-half symptom is reproduced at the unit level.");
    } finally {
      p2.drop();
    }
  }

  /**
   * Variant matching the production BEGIN_BATCH_PUSH → END_BATCH_PUSH lifecycle transition:
   * the first partition is opened with {@code deferredWrite=true} (SST file writer path, used
   * during batch push), and the second partition is opened with {@code deferredWrite=false}
   * (memtable path, used after END_BATCH_PUSH). The PUT-during-batch-push lands in the SST file
   * writer's in-flight state, NOT the rocksdb memtable. Closing partition #1 commits the SST or
   * drops it, depending on whether the deferred-write writer was finalized.
   */
  @Test
  public void rmdPartition_deferredWritePutThenSwitchModeThenMerge_baseSurvives() throws Exception {
    String storeName = Version.composeKafkaTopic("h3-deferred-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    registerFoldContext(storeName, storeShortName);
    VeniceProperties veniceServerProperties = serverProps();
    RocksDBServerConfig rocksDBServerConfig = new RocksDBServerConfig(veniceServerProperties);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    String dbFolder = RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId);
    new File(dbFolder).getParentFile().mkdirs();

    byte[] keyBytes = "h3-deferred-k".getBytes();
    byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("Base", "Last"));

    // ----- BEGIN_BATCH_PUSH state: deferredWrite=true -----
    StoragePartitionConfig batchPushConfig = new StoragePartitionConfig(storeName, partitionId);
    batchPushConfig.setDeferredWrite(true);
    MaterializingReplicationMetadataRocksDBStoragePartition p1 =
        new MaterializingReplicationMetadataRocksDBStoragePartition(
            batchPushConfig,
            factory,
            DATA_BASE_DIR,
            null,
            ROCKSDB_THROTTLER,
            rocksDBServerConfig);
    p1.beginBatchWrite(new java.util.HashMap<>(), java.util.Optional.empty());
    p1.put(keyBytes, putBytes);
    // sync() in deferred-write mode commits the SST file writer's accumulated state. Production
    // calls endBatchWrite() (which calls sync + ingestSSTFiles) via the drainer thread before
    // END_BATCH_PUSH adjust. Mirror that here.
    p1.endBatchWrite();
    p1.close();

    // ----- END_BATCH_PUSH state: deferredWrite=false -----
    StoragePartitionConfig nonDeferredConfig = new StoragePartitionConfig(storeName, partitionId);
    nonDeferredConfig.setDeferredWrite(false);
    MaterializingReplicationMetadataRocksDBStoragePartition p2 =
        new MaterializingReplicationMetadataRocksDBStoragePartition(
            nonDeferredConfig,
            factory,
            DATA_BASE_DIR,
            null,
            ROCKSDB_THROTTLER,
            rocksDBServerConfig);
    try {
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "Merged").build();
      byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
      p2.merge(keyBytes, ByteBuffer.wrap(op));

      byte[] raw = p2.getRaw(keyBytes);
      assertNotNull(raw, "raw readback null after merge in non-deferred mode");
      ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
      assertNotNull(
          parsed.getBase(),
          "H3 REPRODUCED (deferred→non-deferred): readback has no base — the SST-writer commit on "
              + "partition1 + close lost the PUT, leaving partition2 with operand-only readback.");
    } finally {
      p2.drop();
    }
  }

  /**
   * H4 (partial) unit test from GOAL §3 Phase 3: verify that {@code partition.merge} produces
   * identical on-disk framing regardless of arrival source. At the partition layer, there is no
   * "DC" — the framing is a pure function of the operand bytes. If this test passes, the
   * structural splitter that produces the production split-half pattern is upstream of
   * {@code partition.merge} (in the consumer routing layer or topic dispatch), not at the
   * partition framing layer.
   */
  @Test
  public void mergeFramingIsPureFunctionOfOperandBytes() throws Exception {
    String storeName = Version.composeKafkaTopic("h4-framing-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    registerFoldContext(storeName, storeShortName);
    VeniceProperties veniceServerProperties = serverProps();
    RocksDBServerConfig rocksDBServerConfig = new RocksDBServerConfig(veniceServerProperties);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    String dbFolder = RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId);
    new File(dbFolder).getParentFile().mkdirs();

    MaterializingReplicationMetadataRocksDBStoragePartition partition =
        new MaterializingReplicationMetadataRocksDBStoragePartition(
            partitionConfig,
            factory,
            DATA_BASE_DIR,
            null,
            ROCKSDB_THROTTLER,
            rocksDBServerConfig);
    try {
      // Pre-put a base for both keys (so the readback shape has a base for comparison).
      byte[] basePut = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("Base", "Last"));
      byte[] keyLocal = "h4-local-k".getBytes();
      byte[] keyRemote = "h4-remote-k".getBytes();
      partition.put(keyLocal, basePut);
      partition.put(keyRemote, basePut);

      // Same logical operand (setName=Merged) — operand bytes are byte-equal.
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "Merged").build();
      byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);

      // "Local-DC" merge — fresh wrap.
      partition.merge(keyLocal, ByteBuffer.wrap(op));
      // "Remote-DC" merge — fresh wrap of byte-equal bytes.
      partition.merge(keyRemote, ByteBuffer.wrap(op.clone()));

      byte[] rawLocal = partition.getRaw(keyLocal);
      byte[] rawRemote = partition.getRaw(keyRemote);
      assertNotNull(rawLocal);
      assertNotNull(rawRemote);
      assertEquals(
          rawLocal,
          rawRemote,
          "H4 partition-level parity broken: same operand bytes through partition.merge produced "
              + "different on-disk framing. The structural splitter would be AT the partition layer.");

      ConcatBlobParser.Parsed parsedLocal = ConcatBlobParser.parse(rawLocal);
      ConcatBlobParser.Parsed parsedRemote = ConcatBlobParser.parse(rawRemote);
      assertNotNull(parsedLocal.getBase());
      assertNotNull(parsedRemote.getBase());
      assertEquals(parsedLocal.getOperands().size(), parsedRemote.getOperands().size());
    } finally {
      partition.drop();
    }
  }

  // -------- helpers --------

  private static void registerFoldContext(String storeName, String storeShortName) {
    ReadOnlySchemaRepository schemaRepo = Mockito.mock(ReadOnlySchemaRepository.class);
    Mockito.when(schemaRepo.getValueSchema(storeShortName, VALUE_SCHEMA_ID))
        .thenReturn(new SchemaEntry(VALUE_SCHEMA_ID, VALUE_SCHEMA));
    Mockito.when(schemaRepo.getDerivedSchema(storeShortName, VALUE_SCHEMA_ID, WC_SCHEMA_ID))
        .thenReturn(new DerivedSchemaEntry(VALUE_SCHEMA_ID, WC_SCHEMA_ID, WC_SCHEMA));
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext ctx = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, ctx);
  }

  private static VeniceProperties serverProps() {
    Properties extraProps = new Properties();
    extraProps.put(SERVER_VT_UPDATE_OPERAND_ENABLED, "true");
    extraProps.put(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.toString());
    return AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB, extraProps);
  }

  private static GenericRecord recordOf(String firstName, String lastName) {
    GenericRecord rec = new GenericData.Record(VALUE_SCHEMA);
    rec.put("firstName", firstName);
    rec.put("lastName", lastName);
    return rec;
  }

  private static byte[] encodeValueWithHeader(int schemaId, GenericRecord record) {
    byte[] avro = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(VALUE_SCHEMA).serialize(record);
    byte[] out = new byte[ByteUtils.SIZE_OF_INT + avro.length];
    ByteUtils.writeInt(out, schemaId, 0);
    System.arraycopy(avro, 0, out, ByteUtils.SIZE_OF_INT, avro.length);
    return out;
  }
}
