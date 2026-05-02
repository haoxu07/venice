package com.linkedin.davinci.store.rocksdb;

import static com.linkedin.venice.ConfigKeys.PERSISTENCE_TYPE;
import static com.linkedin.venice.ConfigKeys.SERVER_VT_UPDATE_OPERAND_ENABLED;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;

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
import com.linkedin.venice.exceptions.VeniceException;
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
  private static final String VALUE_SCHEMA_STR =
      "{ \"type\":\"record\", \"name\":\"User\", \"fields\":["
          + "{\"name\":\"firstName\", \"type\":\"string\", \"default\":\"\"},"
          + "{\"name\":\"lastName\", \"type\":\"string\", \"default\":\"\"}" + "]}";
  private static final Schema VALUE_SCHEMA = new Schema.Parser().parse(VALUE_SCHEMA_STR);
  private static final Schema WC_SCHEMA = WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(VALUE_SCHEMA);
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
      byte[] operandContent =
          MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes);
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
      GenericRecord decoded = MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "NewAlice");
      assertEquals(decoded.get("lastName").toString(), "Smith");

      // Apply a second operand: lastName -> "NewSmith". Verify both operands are folded.
      GenericRecord wcRec2 = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("lastName", "NewSmith").build();
      byte[] wcBytes2 = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec2);
      byte[] operandContent2 =
          MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes2);
      partition.merge(keyBytes, ByteBuffer.wrap(operandContent2));

      byte[] readBytes2 = partition.get(keyBytes);
      assertNotNull(readBytes2);
      byte[] avroOnly2 = new byte[readBytes2.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes2, ByteUtils.SIZE_OF_INT, avroOnly2, 0, avroOnly2.length);
      GenericRecord decoded2 = MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly2);
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
    File dbBaseDir = new File(DATA_BASE_DIR);
    if (!dbBaseDir.exists()) {
      dbBaseDir.mkdirs();
    }
    tempDirs.add(dbBaseDir);

    Properties extraProps = new Properties();
    extraProps.put(SERVER_VT_UPDATE_OPERAND_ENABLED, "true");
    extraProps.put(PERSISTENCE_TYPE, PersistenceType.ROCKS_DB.toString());
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
}
