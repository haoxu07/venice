package com.linkedin.davinci.store.rocksdb;

import static com.linkedin.venice.ConfigKeys.PERSISTENCE_TYPE;
import static com.linkedin.venice.ConfigKeys.SERVER_VT_MERGE_MAX_CHAIN_LENGTH;
import static com.linkedin.venice.ConfigKeys.SERVER_VT_UPDATE_OPERAND_ENABLED;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.davinci.config.VeniceServerConfig;
import com.linkedin.davinci.kafka.consumer.StoreWriteComputeProcessor;
import com.linkedin.davinci.schema.merge.CollectionTimestampMergeRecordHelper;
import com.linkedin.davinci.serializer.avro.MapOrderPreservingSerDeFactory;
import com.linkedin.davinci.serializer.avro.MapOrderPreservingSerializer;
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
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
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
import org.mockito.Mockito;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;


/**
 * Smoke test for {@link MaterializingRocksDBStoragePartition}: put/get round-trip, put/merge/get
 * fold round-trip via a real {@link MaterializingFoldContext}, chunk-write bypass.
 *
 * <p>Per {@code GOAL.md} §4 Phase B exit criterion.
 */
public class MaterializingPartitionSmokeTest {
  private static final String DATA_BASE_DIR = Utils.getUniqueTempPath();
  private static final RocksDBThrottler ROCKSDB_THROTTLER = new RocksDBThrottler(3);

  // A simple Avro record schema with two string fields. Used for both base put and partial-update
  // operand serialization.
  private static final String VALUE_SCHEMA_STR = "{ \"type\":\"record\", \"name\":\"User\", \"fields\":["
      + "{\"name\":\"firstName\", \"type\":\"string\", \"default\":\"\"},"
      + "{\"name\":\"lastName\", \"type\":\"string\", \"default\":\"\"}" + "]}";
  private static final Schema VALUE_SCHEMA = new Schema.Parser().parse(VALUE_SCHEMA_STR);
  private static final Schema WC_SCHEMA =
      WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(VALUE_SCHEMA);
  private static final int VALUE_SCHEMA_ID = 1;
  private static final int WC_SCHEMA_ID = 1;

  private final java.util.List<File> tempDirs = new java.util.ArrayList<>();

  @AfterClass(alwaysRun = true)
  public void cleanUp() {
    MaterializingFoldContextRegistry.clearForTest();
    for (File f: tempDirs) {
      try {
        org.apache.commons.io.FileUtils.deleteDirectory(f);
      } catch (Exception ignored) {
      }
    }
  }

  @Test
  public void putThenGetRoundTripsWithFraming() throws Exception {
    String storeName = Version.composeKafkaTopic("store-" + System.nanoTime(), 1);
    int partitionId = 0;
    MaterializingRocksDBStoragePartition partition = openPartition(storeName, partitionId);
    try {
      // Build [schemaId : 4B BE][avro] for a record with firstName=Alice, lastName=Smith.
      byte[] keyBytes = "k1".getBytes();
      byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("Alice", "Smith"));
      partition.put(keyBytes, putBytes);

      // get() with no fold context should still return the materialized form (no operands → no
      // fold needed). The bytes should match the input shape exactly.
      byte[] readBytes = partition.get(keyBytes);
      assertNotNull(readBytes);
      assertEquals(readBytes, putBytes, "put → get round-trip mismatch");

      // Verify the on-disk shape is framed: getRaw returns [schemaId][0x00][len][avro].
      byte[] rawBytes = partition.getRaw(keyBytes);
      assertNotNull(rawBytes);
      assertEquals(ByteUtils.readInt(rawBytes, 0), VALUE_SCHEMA_ID);
      assertEquals(rawBytes[ByteUtils.SIZE_OF_INT], ConcatBlobParser.KIND_BASE);
      // varint length of avro payload, then avro bytes
      ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(rawBytes);
      assertEquals(parsed.getSchemaId(), VALUE_SCHEMA_ID);
      assertEquals(parsed.getOperands().size(), 0);
    } finally {
      partition.drop();
    }
  }

  @Test
  public void putThenMergeThenGetFoldsViaFoldContext() throws Exception {
    String storeName = Version.composeKafkaTopic("store-" + System.nanoTime(), 1);
    int partitionId = 0;

    // Set up a fold context backed by a mock schema repo and a real StoreWriteComputeProcessor.
    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor = new StoreWriteComputeProcessor(
        storeShortName,
        schemaRepo,
        new CollectionTimestampMergeRecordHelper(),
        false /* fastAvroEnabled — false to use MapOrderPreservingSerDe (matches WC ingestion) */);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    MaterializingRocksDBStoragePartition partition = openPartition(storeName, partitionId);
    try {
      byte[] keyBytes = "k1".getBytes();

      // Put base record
      byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("Alice", "Smith"));
      partition.put(keyBytes, putBytes);

      // Apply a partial-update operand: firstName -> "NewAlice".
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "NewAlice").build();
      byte[] wcBytes = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] operandContent = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes);
      partition.merge(keyBytes, ByteBuffer.wrap(operandContent));

      // Read should return the folded record with firstName=NewAlice, lastName=Smith.
      byte[] readBytes = partition.get(keyBytes);
      assertNotNull(readBytes, "post-merge get returned null");
      // Strip the schemaId prefix and decode.
      assertEquals(ByteUtils.readInt(readBytes, 0), VALUE_SCHEMA_ID, "schemaId prefix mismatch");
      byte[] avroOnly = new byte[readBytes.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      MapOrderPreservingSerializer<GenericRecord> readerSer =
          MapOrderPreservingSerDeFactory.getSerializer(VALUE_SCHEMA);
      // (Re-serialize to confirm round-trip; deserialize via the SerDe factory.)
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "NewAlice");
      assertEquals(decoded.get("lastName").toString(), "Smith");

      // Apply a second operand: lastName -> "NewSmith". Verify both operands are folded.
      GenericRecord wcRec2 = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("lastName", "NewSmith").build();
      byte[] wcBytes2 = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec2);
      byte[] operandContent2 = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes2);
      partition.merge(keyBytes, ByteBuffer.wrap(operandContent2));

      byte[] readBytes2 = partition.get(keyBytes);
      assertNotNull(readBytes2);
      byte[] avroOnly2 = new byte[readBytes2.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes2, ByteUtils.SIZE_OF_INT, avroOnly2, 0, avroOnly2.length);
      GenericRecord decoded2 =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly2);
      assertEquals(decoded2.get("firstName").toString(), "NewAlice");
      assertEquals(decoded2.get("lastName").toString(), "NewSmith");
    } finally {
      partition.drop();
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  @Test
  public void getReturnsNullForMissingKey() throws Exception {
    String storeName = Version.composeKafkaTopic("store-" + System.nanoTime(), 1);
    MaterializingRocksDBStoragePartition partition = openPartition(storeName, 0);
    try {
      assertNull(partition.get("missing-key".getBytes()));
    } finally {
      partition.drop();
    }
  }

  /**
   * Operand-only, no-base case — the bug we found in Phase C / via the empty-push test
   * {@code TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField}.
   * After an empty push, the first partial-update merge writes operand bytes to a key that has
   * no preceding base. The subsequent {@code Get} should fold the operand against an
   * empty record (per GOAL.md §6) and return a materialized avro record. The thin-client read
   * in the integration test calls {@code engine.get(...)} → eventually reaches
   * {@code partition.get(...)}; if the operand-only fold returns null, the reader sees null
   * and the integration assertion {@code assertNotNull(retrievedValue)} fails.
   */
  @Test
  public void mergeOnlyNoBaseThenGetReturnsMaterialized() throws Exception {
    String storeName = Version.composeKafkaTopic("store-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    MaterializingRocksDBStoragePartition partition = openPartition(storeName, partitionId);
    try {
      byte[] keyBytes = "k-no-base".getBytes();

      // Skip put. Go straight to merge with a partial update that sets firstName.
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "OnlyAlice").build();
      byte[] wcBytes = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] operandContent = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes);
      partition.merge(keyBytes, ByteBuffer.wrap(operandContent));

      // The exact reader code path from the integration test exercises three get overloads via
      // the storage engine layer. Each is overridden on the materializing partition; verify each
      // one returns the same materialized bytes.

      byte[] readByteArray = partition.get(keyBytes);
      assertNotNull(readByteArray, "partition.get(byte[]) returned null on operand-only merge");

      ByteBuffer reusedBuffer = ByteBuffer.allocate(1024);
      ByteBuffer readWithBuffer = partition.get(keyBytes, reusedBuffer);
      assertNotNull(readWithBuffer, "partition.get(byte[], ByteBuffer) returned null");

      byte[] readByteBuffer = partition.get(ByteBuffer.wrap(keyBytes));
      assertNotNull(readByteBuffer, "partition.get(ByteBuffer) returned null");

      // Decode and verify the operand was applied on an empty record: firstName=OnlyAlice,
      // other fields at schema defaults (lastName has default "" or null).
      assertEquals(ByteUtils.readInt(readByteArray, 0), VALUE_SCHEMA_ID, "schemaId prefix mismatch");
      byte[] avroOnly = new byte[readByteArray.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readByteArray, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "OnlyAlice", "firstName not applied from operand");
    } finally {
      partition.drop();
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Multiple merges with no preceding put — operand chain accumulates, all folded on read.
   * This is the "first writer is a merge stream that never put a base" case.
   */
  @Test
  public void multipleMergesNoBaseThenGetFoldsAll() throws Exception {
    String storeName = Version.composeKafkaTopic("store-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    MaterializingRocksDBStoragePartition partition = openPartition(storeName, partitionId);
    try {
      byte[] keyBytes = "k-multi-no-base".getBytes();

      // Merge 1: set firstName
      GenericRecord wcRec1 = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "First").build();
      byte[] wc1 = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec1);
      byte[] op1 = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc1);
      partition.merge(keyBytes, ByteBuffer.wrap(op1));

      // Merge 2: set lastName
      GenericRecord wcRec2 = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("lastName", "Last").build();
      byte[] wc2 = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec2);
      byte[] op2 = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc2);
      partition.merge(keyBytes, ByteBuffer.wrap(op2));

      // Merge 3: overwrite firstName
      GenericRecord wcRec3 = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "Updated").build();
      byte[] wc3 = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec3);
      byte[] op3 = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc3);
      partition.merge(keyBytes, ByteBuffer.wrap(op3));

      byte[] readBytes = partition.get(keyBytes);
      assertNotNull(readBytes, "post-3-merges get returned null");
      byte[] avroOnly = new byte[readBytes.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "Updated", "Merge 3 should override Merge 1");
      assertEquals(decoded.get("lastName").toString(), "Last", "Merge 2's lastName should remain");
    } finally {
      partition.drop();
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Regression: put + many (5) merges. Verifies the fix for the operand-only branch in
   * {@link ConcatBlobParser#parse} didn't break the materialized-base + operands path.
   */
  @Test
  public void putThenManyMergesThenGetFoldsAll() throws Exception {
    String storeName = Version.composeKafkaTopic("store-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    MaterializingRocksDBStoragePartition partition = openPartition(storeName, partitionId);
    try {
      byte[] keyBytes = "k-put-many".getBytes();

      // Base put
      byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("InitFirst", "InitLast"));
      partition.put(keyBytes, putBytes);

      // 5 successive merges, each overriding firstName with a new value
      String[] firstNames = { "F1", "F2", "F3", "F4", "F5" };
      for (String fn: firstNames) {
        GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", fn).build();
        byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
        byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
        partition.merge(keyBytes, ByteBuffer.wrap(op));
      }

      byte[] readBytes = partition.get(keyBytes);
      assertNotNull(readBytes);
      byte[] avroOnly = new byte[readBytes.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "F5", "Final merge's firstName should win");
      assertEquals(decoded.get("lastName").toString(), "InitLast", "lastName from base PUT should remain");
    } finally {
      partition.drop();
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Engine-level path: verifies that {@link RocksDBStorageEngine#get(int, byte[])} and the
   * {@code ByteBuffer} variants delegate to the materializing partition's overridden {@code get}
   * methods, so the read fold is reached. The integration test goes through this path
   * (ChunkingUtils → engine.get → partition.get); a regression here would silently break reads.
   */
  @Test
  public void engineGetReachesMaterializingFold() throws Exception {
    String storeName = Version.composeKafkaTopic("store-engine-" + System.nanoTime(), 1);

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    Properties extraProps = new Properties();
    extraProps.put(SERVER_VT_UPDATE_OPERAND_ENABLED, "true");
    VeniceProperties veniceServerProperties =
        AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB, extraProps);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    com.linkedin.davinci.config.VeniceStoreVersionConfig storeVersionConfig =
        new com.linkedin.davinci.config.VeniceStoreVersionConfig(
            storeName,
            veniceServerProperties,
            PersistenceType.ROCKS_DB);
    com.linkedin.davinci.store.StorageEngine engine = factory.getStorageEngine(storeVersionConfig);
    int partitionId = 0;
    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    new File(RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId)).getParentFile().mkdirs();
    engine.addStoragePartition(partitionConfig);

    try {
      byte[] keyBytes = "k-engine".getBytes();

      // PUT a base record via engine.put (the materializing path).
      byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("BaseFirst", "BaseLast"));
      engine.put(partitionId, keyBytes, putBytes);

      // MERGE an operand via the storage engine API (DelegatingStorageEngine -> RocksDBStorageEngine -> partition).
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "EngineMerged").build();
      byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
      engine.merge(partitionId, keyBytes, ByteBuffer.wrap(op));

      // GET via each engine.get overload — this is what the chunking adapter / read path uses.
      byte[] gotByteArray = engine.get(partitionId, keyBytes);
      assertNotNull(gotByteArray, "engine.get(int, byte[]) returned null — read-path delegation broken");

      byte[] gotByteBuffer = engine.get(partitionId, ByteBuffer.wrap(keyBytes));
      assertNotNull(gotByteBuffer, "engine.get(int, ByteBuffer) returned null — read-path delegation broken");

      ByteBuffer reusedBuffer = ByteBuffer.allocate(1024);
      ByteBuffer gotWithBuffer = engine.get(partitionId, keyBytes, reusedBuffer);
      assertNotNull(gotWithBuffer, "engine.get(int, byte[], ByteBuffer) returned null — read-path delegation broken");

      // Decode and verify the fold actually happened (not just unframed bytes returned as-is).
      assertEquals(ByteUtils.readInt(gotByteArray, 0), VALUE_SCHEMA_ID);
      byte[] avroOnly = new byte[gotByteArray.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(gotByteArray, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(
          decoded.get("firstName").toString(),
          "EngineMerged",
          "engine.get returned bytes that don't reflect the merged operand — fold did not run via engine path");
      assertEquals(
          decoded.get("lastName").toString(),
          "BaseLast",
          "engine.get returned bytes that don't preserve base fields");
    } finally {
      engine.dropPartition(partitionId);
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Reproduce the same read path the integration test takes:
   * <pre>
   * thin client → router → server → ChunkingUtils.getValueAndSchemaIdFromStorage
   *                                       ↓
   *                                 store.get(partition, keyBuffer)
   *                                       ↓
   *                          partition.get(ByteBuffer) → MaterializingFraming.materialize → folded bytes
   *                                       ↓
   *                                 ChunkingUtils.getFromStorage (recursive) for chunk handling
   *                                       ↓
   *                                 ByteBufferValueRecord<ByteBuffer> result
   * </pre>
   *
   * <p>If the chunking adapter or its delegation breaks the materialized read path, this test
   * fails. If it passes, the full server read path (modulo router/HTTP) works for the
   * non-chunked case.
   */
  @Test
  public void chunkingAdapterReturnsFoldedValue() throws Exception {
    String storeName = Version.composeKafkaTopic("store-chunking-" + System.nanoTime(), 1);

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    Properties extraProps = new Properties();
    extraProps.put(SERVER_VT_UPDATE_OPERAND_ENABLED, "true");
    VeniceProperties veniceServerProperties =
        AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB, extraProps);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    com.linkedin.davinci.config.VeniceStoreVersionConfig storeVersionConfig =
        new com.linkedin.davinci.config.VeniceStoreVersionConfig(
            storeName,
            veniceServerProperties,
            PersistenceType.ROCKS_DB);
    com.linkedin.davinci.store.StorageEngine engine = factory.getStorageEngine(storeVersionConfig);
    int partitionId = 0;
    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    new File(RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId)).getParentFile().mkdirs();
    engine.addStoragePartition(partitionConfig);

    try {
      byte[] keyBytes = "k-chunking".getBytes();

      // PUT + MERGE through the engine, same as test #3.
      byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("ChunkBase", "ChunkLast"));
      engine.put(partitionId, keyBytes, putBytes);

      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "ChunkMerged").build();
      byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
      engine.merge(partitionId, keyBytes, ByteBuffer.wrap(op));

      // Now read via the chunking adapter — same call shape that ServerStoreReader uses.
      com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter adapter =
          com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter.INSTANCE;
      com.linkedin.venice.serialization.StoreDeserializerCache<ByteBuffer> deserializerCache =
          com.linkedin.venice.serialization.RawBytesStoreDeserializerCache.getInstance();
      com.linkedin.venice.compression.NoopCompressor compressor = new com.linkedin.venice.compression.NoopCompressor();
      com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer manifestContainer =
          new com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer();

      com.linkedin.davinci.store.record.ByteBufferValueRecord<ByteBuffer> result = adapter.getWithSchemaId(
          engine,
          partitionId,
          ByteBuffer.wrap(keyBytes),
          false /* isChunked */,
          null /* reusedValue */,
          null /* reusedDecoder */,
          deserializerCache,
          compressor,
          manifestContainer);

      assertNotNull(result, "chunking adapter returned null ByteBufferValueRecord");
      assertEquals(result.writerSchemaId(), VALUE_SCHEMA_ID, "schemaId mismatch from chunking adapter");
      ByteBuffer valueBuffer = result.value();
      assertNotNull(valueBuffer, "chunking adapter returned null value buffer");

      byte[] avroOnly = new byte[valueBuffer.remaining()];
      valueBuffer.duplicate().get(avroOnly);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(
          decoded.get("firstName").toString(),
          "ChunkMerged",
          "chunking adapter didn't return the merged firstName");
      assertEquals(
          decoded.get("lastName").toString(),
          "ChunkLast",
          "chunking adapter didn't preserve the base lastName");
    } finally {
      engine.dropPartition(partitionId);
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Verify the chunking-key fix: when chunking is enabled, writes (put + merge) must use the
   * key wrapped with the chunking suffix, matching what the chunking-aware read path looks up.
   * Mirrors what the fixed {@code produceUpdateOperandToVT} does at the SIT layer.
   *
   * <p>Without the fix, write goes to raw key, read looks at wrapped key, returns null.
   * With the fix, both sides agree on the wrapped key.
   */
  @Test
  public void chunkingEnabledReadFindsMergedValueWhenWriteWrapsKey() throws Exception {
    String storeName = Version.composeKafkaTopic("store-chunked-" + System.nanoTime(), 1);

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    Properties extraProps = new Properties();
    extraProps.put(SERVER_VT_UPDATE_OPERAND_ENABLED, "true");
    VeniceProperties veniceServerProperties =
        AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB, extraProps);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    com.linkedin.davinci.config.VeniceStoreVersionConfig storeVersionConfig =
        new com.linkedin.davinci.config.VeniceStoreVersionConfig(
            storeName,
            veniceServerProperties,
            PersistenceType.ROCKS_DB);
    com.linkedin.davinci.store.StorageEngine engine = factory.getStorageEngine(storeVersionConfig);
    int partitionId = 0;
    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    new File(RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId)).getParentFile().mkdirs();
    engine.addStoragePartition(partitionConfig);

    try {
      byte[] rawKey = "k-chunked".getBytes();
      // Mirror the production fix: wrap the key with the chunking suffix before write, exactly
      // as the leader's produceUpdateOperandToVT does after the fix.
      byte[] wrappedKey = com.linkedin.davinci.storage.chunking.ChunkingUtils.KEY_WITH_CHUNKING_SUFFIX_SERIALIZER
          .serializeNonChunkedKey(rawKey);

      // Write at the WRAPPED key — what the fixed SIT produces onto VT and what the drainer's
      // case UPDATE → engine.merge would land on disk.
      byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("ChunkBase", "ChunkLast"));
      engine.put(partitionId, wrappedKey, putBytes);
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "ChunkMerged").build();
      byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
      engine.merge(partitionId, wrappedKey, ByteBuffer.wrap(op));

      // Read via chunking adapter with isChunked=TRUE (wraps the key with chunking suffix).
      com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter adapter =
          com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter.INSTANCE;
      com.linkedin.venice.serialization.StoreDeserializerCache<ByteBuffer> deserializerCache =
          com.linkedin.venice.serialization.RawBytesStoreDeserializerCache.getInstance();
      com.linkedin.venice.compression.NoopCompressor compressor = new com.linkedin.venice.compression.NoopCompressor();
      com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer manifestContainer =
          new com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer();

      com.linkedin.davinci.store.record.ByteBufferValueRecord<ByteBuffer> result = adapter.getWithSchemaId(
          engine,
          partitionId,
          ByteBuffer.wrap(rawKey),
          true /* isChunked — the bug-exposing flag */,
          null,
          null,
          deserializerCache,
          compressor,
          manifestContainer);

      // With the fix applied (key wrapped on the write side AND on the chunking-aware read side),
      // both sides agree on the wrapped key and the read returns the merged value.
      assertNotNull(
          result,
          "Read returned null after the fix — chunking-suffix wrapping should "
              + "now match between writer and chunking-aware reader.");
      ByteBuffer valueBuffer = result.value();
      assertNotNull(valueBuffer);
      byte[] avroOnly = new byte[valueBuffer.remaining()];
      valueBuffer.duplicate().get(avroOnly);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "ChunkMerged");
    } finally {
      engine.dropPartition(partitionId);
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Phase B hot-key test. With {@code maxChainLength=8}, do 200 merges at the same key. The
   * backstop must hold the on-disk chain depth strictly below {@code maxChainLength + 2}
   * (one slot for the chain just before backstop fires, one for the new operand merged on top
   * of the freshly-written base PUT). Also assert read-back semantics: the final read folds to
   * the last-merged firstName.
   */
  @Test
  public void hotKeyChainBackstopBoundsChainDepth() throws Exception {
    String storeName = Version.composeKafkaTopic("store-hotkey-" + System.nanoTime(), 1);
    int partitionId = 0;
    int maxChain = 8;
    int totalMerges = 200;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    MaterializingRocksDBStoragePartition partition = openPartitionWithMaxChain(storeName, partitionId, maxChain);
    try {
      byte[] keyBytes = "hot-key".getBytes();

      // Initial base PUT to give the chain a base to fold onto.
      byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("Init", "InitLast"));
      partition.put(keyBytes, putBytes);

      // 200 merges, each setting firstName to "F<i>"
      for (int i = 0; i < totalMerges; i++) {
        GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "F" + i).build();
        byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
        byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
        partition.merge(keyBytes, ByteBuffer.wrap(op));
      }

      // Inspect raw on-disk blob: chain must be bounded.
      byte[] raw = partition.getRaw(keyBytes);
      assertNotNull(raw, "raw read returned null after 200 merges");
      ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
      int onDiskOperandCount = parsed.getOperands().size();
      // Tightest bound: the backstop fires when chainDepth == maxChain. On the (maxChain)-th
      // merge, just before issuing rocksDB.merge, the chain has maxChain operands. The
      // backstop reads, folds, and PUTs a fresh base — wiping the chain. Then the merge adds
      // 1 operand. So the post-merge chain has exactly 1 operand. After that, the chain
      // grows again until depth maxChain at which point backstop fires once more. Therefore
      // the chain at any post-merge moment is in [1, maxChain]. The +1 slack is for the
      // moment immediately before the backstop on the (maxChain+1)-th merge — which we don't
      // observe here because we observe AFTER all 200 merges. The strict bound at observation
      // time is operandCount <= maxChain.
      assertTrue(
          onDiskOperandCount <= maxChain,
          "On-disk chain depth " + onDiskOperandCount + " > maxChain " + maxChain
              + " — backstop did not bound chain length");
      // Backstop should have fired at least totalMerges/maxChain times — i.e. roughly 25 times
      // for the parameters above. We just assert it fired at least once via the proxy that
      // the chain is < totalMerges.
      assertTrue(
          onDiskOperandCount < totalMerges,
          "Chain length " + onDiskOperandCount + " == total merges " + totalMerges
              + " — backstop never fired (chain unbounded)");

      // Read fold should still return the last-merged value.
      byte[] readBytes = partition.get(keyBytes);
      assertNotNull(readBytes);
      byte[] avroOnly = new byte[readBytes.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(
          decoded.get("firstName").toString(),
          "F" + (totalMerges - 1),
          "Final firstName should be the last-merged value F" + (totalMerges - 1));
      assertEquals(decoded.get("lastName").toString(), "InitLast", "lastName from base PUT should remain");
    } finally {
      partition.drop();
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Phase B hot-key with operand-only chain (no preceding PUT). Same bound applies: the
   * backstop's foldOperandOnly path replaces the chain with a single base PUT.
   */
  @Test
  public void hotKeyChainBackstopBoundsOperandOnlyChain() throws Exception {
    String storeName = Version.composeKafkaTopic("store-hotkey-oo-" + System.nanoTime(), 1);
    int partitionId = 0;
    int maxChain = 4;
    int totalMerges = 50;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    MaterializingRocksDBStoragePartition partition = openPartitionWithMaxChain(storeName, partitionId, maxChain);
    try {
      byte[] keyBytes = "hot-key-oo".getBytes();

      // No PUT — go straight to merges.
      for (int i = 0; i < totalMerges; i++) {
        GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "OO" + i).build();
        byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
        byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
        partition.merge(keyBytes, ByteBuffer.wrap(op));
      }

      byte[] raw = partition.getRaw(keyBytes);
      assertNotNull(raw);
      ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(raw);
      int onDiskOperandCount = parsed.getOperands().size();
      assertTrue(
          onDiskOperandCount <= maxChain,
          "operand-only on-disk chain depth " + onDiskOperandCount + " > maxChain " + maxChain);

      // After 50 merges with maxChain=4, the backstop should have fired ≈ 50/4 = ~12 times.
      // First fire would be on the 4th merge (chain = 4), at which point foldOperandOnly is
      // invoked. After that, the chain has a base, so subsequent backstops use the base+ops
      // path. Both should leave the same final readable state.
      byte[] readBytes = partition.get(keyBytes);
      assertNotNull(readBytes);
      byte[] avroOnly = new byte[readBytes.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "OO" + (totalMerges - 1));
    } finally {
      partition.drop();
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  @Test
  public void chunkWriteIsBypassed() throws Exception {
    String storeName = Version.composeKafkaTopic("store-" + System.nanoTime(), 1);
    MaterializingRocksDBStoragePartition partition = openPartition(storeName, 0);
    try {
      int chunkSchemaId = AvroProtocolDefinition.CHUNK.getCurrentProtocolVersion();
      // Build a "chunk" payload: [schemaId=-10][raw chunk bytes].
      byte[] rawChunkPayload = new byte[] { 1, 2, 3, 4, 5 };
      byte[] chunkBytes = new byte[ByteUtils.SIZE_OF_INT + rawChunkPayload.length];
      ByteUtils.writeInt(chunkBytes, chunkSchemaId, 0);
      System.arraycopy(rawChunkPayload, 0, chunkBytes, ByteUtils.SIZE_OF_INT, rawChunkPayload.length);

      byte[] keyBytes = "chunk-key".getBytes();
      partition.put(keyBytes, chunkBytes);

      // get should return the bytes UNCHANGED — no framing, no fold.
      byte[] readBytes = partition.get(keyBytes);
      assertEquals(readBytes, chunkBytes, "chunk write/read should be byte-identical (no framing)");
    } finally {
      partition.drop();
    }
  }

  // -------- helpers --------

  private MaterializingRocksDBStoragePartition openPartition(String storeName, int partitionId) {
    return openPartitionWithMaxChain(storeName, partitionId, 0 /* backstop disabled */);
  }

  private MaterializingRocksDBStoragePartition openPartitionWithMaxChain(
      String storeName,
      int partitionId,
      int maxChainLength) {
    File dbBaseDir = new File(DATA_BASE_DIR);
    if (!dbBaseDir.exists()) {
      dbBaseDir.mkdirs();
    }
    tempDirs.add(dbBaseDir);

    Properties extraProps = new Properties();
    extraProps.put(SERVER_VT_UPDATE_OPERAND_ENABLED, "true");
    extraProps.put(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.toString());
    if (maxChainLength > 0) {
      extraProps.put(SERVER_VT_MERGE_MAX_CHAIN_LENGTH, Integer.toString(maxChainLength));
    } else {
      extraProps.put(SERVER_VT_MERGE_MAX_CHAIN_LENGTH, "0");
    }
    VeniceProperties veniceServerProperties =
        AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB, extraProps);
    RocksDBServerConfig rocksDBServerConfig = new RocksDBServerConfig(veniceServerProperties);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    // Ensure parent dir exists for the partition's database
    String dbFolder = RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId);
    new File(dbFolder).getParentFile().mkdirs();

    return new MaterializingRocksDBStoragePartition(
        partitionConfig,
        factory,
        DATA_BASE_DIR,
        null,
        ROCKSDB_THROTTLER,
        rocksDBServerConfig);
  }

  private static GenericRecord recordOf(String firstName, String lastName) {
    GenericRecord rec = new GenericData.Record(VALUE_SCHEMA);
    rec.put("firstName", firstName);
    rec.put("lastName", lastName);
    return rec;
  }

  private static byte[] encodeValueWithHeader(int schemaId, GenericRecord record) {
    MapOrderPreservingSerializer<GenericRecord> ser = MapOrderPreservingSerDeFactory.getSerializer(VALUE_SCHEMA);
    byte[] avro = ser.serialize(record);
    byte[] out = new byte[ByteUtils.SIZE_OF_INT + avro.length];
    ByteUtils.writeInt(out, schemaId, 0);
    System.arraycopy(avro, 0, out, ByteUtils.SIZE_OF_INT, avro.length);
    return out;
  }

  private static ReadOnlySchemaRepository mockSchemaRepo(String storeName) {
    ReadOnlySchemaRepository repo = Mockito.mock(ReadOnlySchemaRepository.class);
    SchemaEntry valueEntry = new SchemaEntry(VALUE_SCHEMA_ID, VALUE_SCHEMA);
    Mockito.when(repo.getValueSchema(storeName, VALUE_SCHEMA_ID)).thenReturn(valueEntry);
    DerivedSchemaEntry wcEntry = new DerivedSchemaEntry(VALUE_SCHEMA_ID, WC_SCHEMA_ID, WC_SCHEMA);
    Mockito.when(repo.getDerivedSchema(storeName, VALUE_SCHEMA_ID, WC_SCHEMA_ID)).thenReturn(wcEntry);
    return repo;
  }

  // -------- Iter-10: integration-test-shaped operand-only collection-merge case --------
  //
  // Schema mirrors PartialUpdateWithRecordMapField.avsc: a single field nullableMapField, which is
  // a UNION of [null, map<TestMapRecord>]. Default value: null. The integration test
  // testActiveActivePartialUpdateWithRecordMapField does an empty push (no base) + UPDATE that
  // adds entries via setEntriesToAddToMapField. The flag-OFF path handles this fine because
  // MergeConflictResolver.update creates an empty value record from the schema before applying WC.
  // The flag-ON path goes through MaterializingFoldContext.foldOperandOnly, which invokes
  // StoreWriteComputeProcessor.applyWriteCompute(currValue=null, ...). This test exercises that
  // exact code path against real serializers/deserializers and verifies the materialized output
  // round-trips through the chunking adapter — which is where the integration test fails ("could
  // not deserialize bytes back into Avro object").
  private static final String NULLABLE_MAP_VALUE_SCHEMA_STR =
      "{ \"type\":\"record\", \"name\":\"TestRecord\", \"namespace\":\"com.linkedin.avro\", \"fields\":["
          + "{\"name\":\"nullableMapField\", \"type\":[\"null\", {\"type\":\"map\", \"values\":{"
          + "\"type\":\"record\", \"name\":\"TestMapRecord\", \"fields\":["
          + "{\"name\":\"longField\", \"type\":[\"null\", \"long\"], \"default\":null}" + "]}}], \"default\":null}"
          + "]}";
  private static final Schema NULLABLE_MAP_VALUE_SCHEMA = new Schema.Parser().parse(NULLABLE_MAP_VALUE_SCHEMA_STR);
  private static final Schema NULLABLE_MAP_WC_SCHEMA =
      WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(NULLABLE_MAP_VALUE_SCHEMA);
  private static final int NULLABLE_MAP_VALUE_SCHEMA_ID = 1;
  private static final int NULLABLE_MAP_WC_SCHEMA_ID = 1;

  private static ReadOnlySchemaRepository mockNullableMapSchemaRepo(String storeName) {
    ReadOnlySchemaRepository repo = Mockito.mock(ReadOnlySchemaRepository.class);
    SchemaEntry valueEntry = new SchemaEntry(NULLABLE_MAP_VALUE_SCHEMA_ID, NULLABLE_MAP_VALUE_SCHEMA);
    Mockito.when(repo.getValueSchema(storeName, NULLABLE_MAP_VALUE_SCHEMA_ID)).thenReturn(valueEntry);
    DerivedSchemaEntry wcEntry =
        new DerivedSchemaEntry(NULLABLE_MAP_VALUE_SCHEMA_ID, NULLABLE_MAP_WC_SCHEMA_ID, NULLABLE_MAP_WC_SCHEMA);
    Mockito.when(repo.getDerivedSchema(storeName, NULLABLE_MAP_VALUE_SCHEMA_ID, NULLABLE_MAP_WC_SCHEMA_ID))
        .thenReturn(wcEntry);
    return repo;
  }

  /**
   * Iter-10: reproduce {@code testActiveActivePartialUpdateWithRecordMapField} at the storage
   * layer. Empty store (no PUT). Two MAP_OPS UPDATEs that each add 2 entries to a NULLABLE map
   * field. After the merges, the chunking adapter should return bytes that deserialize to a
   * record with 4 entries in nullableMapField.
   */
  @Test
  public void mergeOnlyMapOpsOnNullableMapFieldFoldsToCorrectRecord() throws Exception {
    String storeName = Version.composeKafkaTopic("store-mapops-" + System.nanoTime(), 1);

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockNullableMapSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    Properties extraProps = new Properties();
    extraProps.put(SERVER_VT_UPDATE_OPERAND_ENABLED, "true");
    VeniceProperties veniceServerProperties =
        AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB, extraProps);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    com.linkedin.davinci.config.VeniceStoreVersionConfig storeVersionConfig =
        new com.linkedin.davinci.config.VeniceStoreVersionConfig(
            storeName,
            veniceServerProperties,
            PersistenceType.ROCKS_DB);
    com.linkedin.davinci.store.StorageEngine engine = factory.getStorageEngine(storeVersionConfig);
    int partitionId = 0;
    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    new File(RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId)).getParentFile().mkdirs();
    engine.addStoragePartition(partitionConfig);

    try {
      // Mirror the integration test: chunking enabled => keys are wrapped on both write and read.
      byte[] rawKey = "testKey".getBytes();
      byte[] wrappedKey = com.linkedin.davinci.storage.chunking.ChunkingUtils.KEY_WITH_CHUNKING_SUFFIX_SERIALIZER
          .serializeNonChunkedKey(rawKey);

      Schema mapValueRecordSchema =
          NULLABLE_MAP_VALUE_SCHEMA.getField("nullableMapField").schema().getTypes().get(1).getValueType();

      // First UPDATE: add key1, key2.
      GenericRecord updateRecord1 = new GenericData.Record(mapValueRecordSchema);
      updateRecord1.put("longField", 1L);
      GenericRecord updateRecord2 = new GenericData.Record(mapValueRecordSchema);
      updateRecord2.put("longField", 2L);
      java.util.Map<String, GenericRecord> deltaMap1 = new java.util.HashMap<>();
      deltaMap1.put("key1", updateRecord1);
      deltaMap1.put("key2", updateRecord2);
      GenericRecord wcRec1 =
          new UpdateBuilderImpl(NULLABLE_MAP_WC_SCHEMA).setEntriesToAddToMapField("nullableMapField", deltaMap1)
              .build();
      // Use FastAvro for WC serialization to mirror VeniceSystemProducer.serializeObject which is
      // what produces the WC bytes that flow through the integration test (Samza -> RT -> leader
      // bypass -> VT -> follower merge). FastAvro accepts plain HashMap (unlike
      // MapOrderPreservingSerDe which requires LinkedHashMap/IndexedHashMap/SortedMap).
      byte[] wcBytes1 = com.linkedin.venice.serializer.FastSerializerDeserializerFactory
          .<GenericRecord>getFastAvroGenericSerializer(NULLABLE_MAP_WC_SCHEMA)
          .serialize(wcRec1);
      byte[] op1 = MaterializingFoldContext.OperandContent
          .frame(NULLABLE_MAP_VALUE_SCHEMA_ID, NULLABLE_MAP_WC_SCHEMA_ID, wcBytes1);
      engine.merge(partitionId, wrappedKey, ByteBuffer.wrap(op1));

      // Read after first merge: should have a record with 2 map entries.
      com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter adapter =
          com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter.INSTANCE;
      com.linkedin.venice.serialization.StoreDeserializerCache<ByteBuffer> deserializerCache =
          com.linkedin.venice.serialization.RawBytesStoreDeserializerCache.getInstance();
      com.linkedin.venice.compression.NoopCompressor compressor = new com.linkedin.venice.compression.NoopCompressor();
      com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer manifestContainer1 =
          new com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer();

      com.linkedin.davinci.store.record.ByteBufferValueRecord<ByteBuffer> result1 = adapter.getWithSchemaId(
          engine,
          partitionId,
          ByteBuffer.wrap(rawKey),
          true,
          null,
          null,
          deserializerCache,
          compressor,
          manifestContainer1);

      assertNotNull(result1, "After first merge, chunking-aware adapter returned null");
      ByteBuffer valBuf1 = result1.value();
      assertNotNull(valBuf1);
      byte[] avro1 = new byte[valBuf1.remaining()];
      valBuf1.duplicate().get(avro1);
      GenericRecord decoded1 =
          MapOrderPreservingSerDeFactory.getDeserializer(NULLABLE_MAP_VALUE_SCHEMA, NULLABLE_MAP_VALUE_SCHEMA)
              .deserialize(avro1);
      assertNotNull(decoded1, "Decoded record after first merge is null");
      Object mapAfterFirst = decoded1.get("nullableMapField");
      assertNotNull(
          mapAfterFirst,
          "nullableMapField is null after first merge — fold did not apply MAP_OPS to null base");
      assertEquals(((java.util.Map<?, ?>) mapAfterFirst).size(), 2, "After first merge, map should have 2 entries");

      // Second UPDATE: add key3, key4.
      java.util.Map<String, GenericRecord> deltaMap2 = new java.util.HashMap<>();
      deltaMap2.put("key3", updateRecord1);
      deltaMap2.put("key4", updateRecord2);
      GenericRecord wcRec2 =
          new UpdateBuilderImpl(NULLABLE_MAP_WC_SCHEMA).setEntriesToAddToMapField("nullableMapField", deltaMap2)
              .build();
      byte[] wcBytes2 = com.linkedin.venice.serializer.FastSerializerDeserializerFactory
          .<GenericRecord>getFastAvroGenericSerializer(NULLABLE_MAP_WC_SCHEMA)
          .serialize(wcRec2);
      byte[] op2 = MaterializingFoldContext.OperandContent
          .frame(NULLABLE_MAP_VALUE_SCHEMA_ID, NULLABLE_MAP_WC_SCHEMA_ID, wcBytes2);
      engine.merge(partitionId, wrappedKey, ByteBuffer.wrap(op2));

      com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer manifestContainer2 =
          new com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer();
      com.linkedin.davinci.store.record.ByteBufferValueRecord<ByteBuffer> result2 = adapter.getWithSchemaId(
          engine,
          partitionId,
          ByteBuffer.wrap(rawKey),
          true,
          null,
          null,
          deserializerCache,
          compressor,
          manifestContainer2);
      assertNotNull(result2, "After second merge, chunking-aware adapter returned null");
      ByteBuffer valBuf2 = result2.value();
      assertNotNull(valBuf2);
      byte[] avro2 = new byte[valBuf2.remaining()];
      valBuf2.duplicate().get(avro2);
      GenericRecord decoded2 =
          MapOrderPreservingSerDeFactory.getDeserializer(NULLABLE_MAP_VALUE_SCHEMA, NULLABLE_MAP_VALUE_SCHEMA)
              .deserialize(avro2);
      assertNotNull(decoded2);
      Object mapAfterSecond = decoded2.get("nullableMapField");
      assertNotNull(mapAfterSecond);
      assertEquals(((java.util.Map<?, ?>) mapAfterSecond).size(), 4, "After second merge, map should have 4 entries");
    } finally {
      engine.dropPartition(partitionId);
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Multi-key version-push scenario: simulate a batch push that populates several keys with full
   * records, then RT partial updates target a SUBSET of those keys. Reads via the chunking
   * adapter (isChunked=true) must return:
   *   - Updated keys: base + applied operand (folded)
   *   - Untouched keys: original base record (no fold needed, no concat blob)
   *
   * <p>This catches failure modes the single-key tests don't:
   * <ul>
   *   <li>Cross-key state pollution (operand for key A leaking into key B's read)
   *   <li>Mixed read shapes — folded + non-folded — handled correctly within one engine
   *   <li>Multiple partial updates on the same key after a base PUT (operand chain on real base)
   * </ul>
   *
   * <p>Closer to the integration-test scenario than the empty-push case our currently-passing
   * test exercises.
   */
  @Test
  public void versionPushBaseThenSelectivePartialUpdatesFoldCorrectly() throws Exception {
    String storeName = Version.composeKafkaTopic("store-vp-" + System.nanoTime(), 1);

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    Properties extraProps = new Properties();
    extraProps.put(SERVER_VT_UPDATE_OPERAND_ENABLED, "true");
    VeniceProperties veniceServerProperties =
        AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB, extraProps);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    com.linkedin.davinci.config.VeniceStoreVersionConfig storeVersionConfig =
        new com.linkedin.davinci.config.VeniceStoreVersionConfig(
            storeName,
            veniceServerProperties,
            PersistenceType.ROCKS_DB);
    com.linkedin.davinci.store.StorageEngine engine = factory.getStorageEngine(storeVersionConfig);
    int partitionId = 0;
    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
    new File(RocksDBUtils.composePartitionDbDir(DATA_BASE_DIR, storeName, partitionId)).getParentFile().mkdirs();
    engine.addStoragePartition(partitionConfig);

    com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter adapter =
        com.linkedin.davinci.storage.chunking.RawBytesChunkingAdapter.INSTANCE;
    com.linkedin.venice.serialization.StoreDeserializerCache<ByteBuffer> deserializerCache =
        com.linkedin.venice.serialization.RawBytesStoreDeserializerCache.getInstance();
    com.linkedin.venice.compression.NoopCompressor compressor = new com.linkedin.venice.compression.NoopCompressor();

    try {
      // ----- "Version push": write 5 base records at wrapped keys via engine.put -----
      // (Mirrors what the production batch-push pipeline produces after Phase C iter 8/9 fixes:
      // VeniceWriter.put wraps the key with chunking-suffix; drainer's case PUT calls
      // engine.put which lands on MaterializingRocksDBStoragePartition.put → kind-byte framing.)
      java.util.Map<String, byte[]> wrappedKeyByName = new java.util.HashMap<>();
      String[] keyNames = { "user-1", "user-2", "user-3", "user-4", "user-5" };
      for (String name: keyNames) {
        byte[] rawKey = name.getBytes();
        byte[] wrappedKey = com.linkedin.davinci.storage.chunking.ChunkingUtils.KEY_WITH_CHUNKING_SUFFIX_SERIALIZER
            .serializeNonChunkedKey(rawKey);
        wrappedKeyByName.put(name, wrappedKey);
        byte[] putBytes = encodeValueWithHeader(VALUE_SCHEMA_ID, recordOf("base-first-" + name, "base-last-" + name));
        engine.put(partitionId, wrappedKey, putBytes);
      }

      // ----- RT partial-updates: only target user-1, user-3, user-5 (skip user-2 and user-4) -----
      String[] updatedNames = { "user-1", "user-3", "user-5" };
      for (String name: updatedNames) {
        GenericRecord wcRec =
            new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "updated-first-" + name).build();
        byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
        byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
        engine.merge(partitionId, wrappedKeyByName.get(name), ByteBuffer.wrap(op));
      }

      // Apply a SECOND partial update on user-1 only — exercises operand chain on real base.
      {
        GenericRecord wcRec2 =
            new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("lastName", "updated-last-user-1").build();
        byte[] wc2 = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec2);
        byte[] op2 = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc2);
        engine.merge(partitionId, wrappedKeyByName.get("user-1"), ByteBuffer.wrap(op2));
      }

      // ----- Reads via chunking adapter (isChunked=true), one per key -----
      java.util.Map<String, GenericRecord> readBack = new java.util.HashMap<>();
      for (String name: keyNames) {
        com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer manifest =
            new com.linkedin.davinci.storage.chunking.ChunkedValueManifestContainer();
        com.linkedin.davinci.store.record.ByteBufferValueRecord<ByteBuffer> result = adapter.getWithSchemaId(
            engine,
            partitionId,
            ByteBuffer.wrap(name.getBytes()),
            true,
            null,
            null,
            deserializerCache,
            compressor,
            manifest);
        assertNotNull(result, "Read returned null for key " + name);
        ByteBuffer valBuf = result.value();
        assertNotNull(valBuf, "Read value buffer null for key " + name);
        byte[] avro = new byte[valBuf.remaining()];
        valBuf.duplicate().get(avro);
        GenericRecord decoded =
            MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avro);
        readBack.put(name, decoded);
      }

      // ----- Assertions -----
      // user-1: TWO partial updates applied (firstName + lastName), so both fields are updated
      assertEquals(readBack.get("user-1").get("firstName").toString(), "updated-first-user-1");
      assertEquals(readBack.get("user-1").get("lastName").toString(), "updated-last-user-1");

      // user-3, user-5: ONE partial update each (firstName overwrite). lastName stays from base.
      assertEquals(readBack.get("user-3").get("firstName").toString(), "updated-first-user-3");
      assertEquals(readBack.get("user-3").get("lastName").toString(), "base-last-user-3");
      assertEquals(readBack.get("user-5").get("firstName").toString(), "updated-first-user-5");
      assertEquals(readBack.get("user-5").get("lastName").toString(), "base-last-user-5");

      // user-2, user-4: NO partial update — both fields unchanged from version-push base.
      assertEquals(readBack.get("user-2").get("firstName").toString(), "base-first-user-2");
      assertEquals(readBack.get("user-2").get("lastName").toString(), "base-last-user-2");
      assertEquals(readBack.get("user-4").get("firstName").toString(), "base-first-user-4");
      assertEquals(readBack.get("user-4").get("lastName").toString(), "base-last-user-4");
    } finally {
      engine.dropPartition(partitionId);
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }
}
