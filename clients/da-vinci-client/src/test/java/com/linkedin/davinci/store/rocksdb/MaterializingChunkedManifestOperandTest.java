package com.linkedin.davinci.store.rocksdb;

import static com.linkedin.venice.ConfigKeys.PERSISTENCE_TYPE;
import static com.linkedin.venice.ConfigKeys.SERVER_VT_MERGE_MAX_CHAIN_LENGTH;
import static com.linkedin.venice.ConfigKeys.SERVER_VT_UPDATE_OPERAND_ENABLED;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.davinci.config.VeniceServerConfig;
import com.linkedin.davinci.kafka.consumer.StoreWriteComputeProcessor;
import com.linkedin.davinci.schema.merge.CollectionTimestampMergeRecordHelper;
import com.linkedin.davinci.serializer.avro.MapOrderPreservingSerDeFactory;
import com.linkedin.davinci.serializer.avro.MapOrderPreservingSerializer;
import com.linkedin.davinci.store.AbstractStorageEngineTest;
import com.linkedin.davinci.store.StoragePartitionConfig;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContext;
import com.linkedin.davinci.store.rocksdb.merge.MaterializingFoldContextRegistry;
import com.linkedin.venice.meta.PersistenceType;
import com.linkedin.venice.meta.ReadOnlySchemaRepository;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.schema.writecompute.DerivedSchemaEntry;
import com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter;
import com.linkedin.venice.serialization.KeyWithChunkingSuffixSerializer;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serialization.avro.ChunkedValueManifestSerializer;
import com.linkedin.venice.storage.protocol.ChunkedKeySuffix;
import com.linkedin.venice.storage.protocol.ChunkedValueManifest;
import com.linkedin.venice.store.rocksdb.RocksDBUtils;
import com.linkedin.venice.utils.ByteUtils;
import com.linkedin.venice.utils.Utils;
import com.linkedin.venice.utils.VeniceProperties;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.mockito.Mockito;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;


/**
 * Regression-guard tests for the chunked-value read-path fix in
 * {@link com.linkedin.davinci.store.rocksdb.merge.MaterializingFraming#materialize(byte[], String,
 * java.util.function.Function)}.
 *
 * <p>Bug surface: when partial-update operands are merged onto a chunked-manifest top-level key
 * (the post-H5-fix shape exposed by {@code testActiveActivePartialUpdateOnBatchPushedChunkKeys}),
 * the on-disk bytes are
 * {@code [chunkManifestSchemaId][serializedManifest][delim][KIND_OPERAND][len][operand]...}.
 *
 * <p>Pre-fix: {@code MaterializingFraming.materialize} saw the negative schemaId and returned the
 * raw bytes verbatim. The upper-layer {@code ChunkingUtils.getFromStorage} then deserialized the
 * manifest, reassembled chunks, and returned the pre-update value — operands silently dropped.
 *
 * <p>Post-fix: {@code materialize} detects appended operand bytes, reassembles chunks via the
 * partition's chunk-fetch callback, folds the operands onto the assembled base via
 * {@link MaterializingFoldContext#foldOperands}, and returns the materialized value with the
 * manifest's user-schemaId in the header. The chunking layer sees a positive schemaId and
 * treats the result as a non-chunked value.
 */
public class MaterializingChunkedManifestOperandTest {
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

  private static final int CHUNK_SCHEMA_ID = AvroProtocolDefinition.CHUNK.getCurrentProtocolVersion();
  private static final int CHUNK_MANIFEST_SCHEMA_ID =
      AvroProtocolDefinition.CHUNKED_VALUE_MANIFEST.getCurrentProtocolVersion();

  private final List<File> tempDirs = new ArrayList<>();

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

  /**
   * Core regression: chunked-manifest with a single trailing operand should fold to a record that
   * reflects the operand applied to the reassembled chunked base.
   *
   * <p>We build the on-disk state manually (no high-level VeniceWriter chunking machinery) so the
   * test is hermetic to the partition + materialize layer:
   * <ol>
   *   <li>Serialize a base record {@code (firstName="Alice", lastName="Smith")}.</li>
   *   <li>Split the serialized bytes into two chunks; write each chunk at its chunk-suffixed key
   *       as raw bytes {@code [CHUNK_SCHEMA_ID][chunkBytes]} (bypassing framing because chunks
   *       have negative schemaId).</li>
   *   <li>Build a {@link ChunkedValueManifest} pointing at those chunk keys; write it at the
   *       top-level (non-chunk-suffix) key as raw bytes
   *       {@code [CHUNK_MANIFEST_SCHEMA_ID][manifestAvro]}.</li>
   *   <li>Merge an operand at the top-level key — this exercises the production merge path which
   *       appends the framed operand bytes via {@code StringAppendOperator}.</li>
   *   <li>Read via {@code partition.get(topLevelKey)} — the post-fix path must return a
   *       materialized value with the operand applied.</li>
   * </ol>
   */
  @Test
  public void chunkedManifestWithOneOperandFoldsToMaterializedValue() throws Exception {
    String storeName = Version.composeKafkaTopic("store-chunked-1-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    MaterializingRocksDBStoragePartition partition = openPartition(storeName, partitionId);
    try {
      byte[] rawUserKey = "k-chunked-base".getBytes();
      KeyWithChunkingSuffixSerializer keySer =
          com.linkedin.davinci.storage.chunking.ChunkingUtils.KEY_WITH_CHUNKING_SUFFIX_SERIALIZER;
      byte[] topLevelKey = keySer.serializeNonChunkedKey(rawUserKey);

      // Build the base record's Avro bytes (no schemaId prefix).
      MapOrderPreservingSerializer<GenericRecord> valueSer = MapOrderPreservingSerDeFactory.getSerializer(VALUE_SCHEMA);
      byte[] baseAvro = valueSer.serialize(recordOf("Alice", "Smith"));
      assertTrue(baseAvro.length >= 6, "base must be at least 6 bytes for the split test");

      // Split into two chunks of roughly equal size.
      int split = baseAvro.length / 2;
      byte[] chunk0Content = Arrays.copyOfRange(baseAvro, 0, split);
      byte[] chunk1Content = Arrays.copyOfRange(baseAvro, split, baseAvro.length);

      // Write each chunk at a chunk-suffixed key. Chunk values on disk: [CHUNK_SCHEMA_ID][chunkBytes].
      List<ByteBuffer> chunkKeys = new ArrayList<>(2);
      for (int i = 0; i < 2; i++) {
        ChunkedKeySuffix suffix = makeChunkKeySuffix(i);
        ByteBuffer chunkedKey = keySer.serializeChunkedKey(rawUserKey, suffix);
        byte[] chunkedKeyBytes = chunkedKey.array();
        byte[] chunkContent = (i == 0) ? chunk0Content : chunk1Content;
        byte[] chunkOnDisk = wrapWithSchemaId(CHUNK_SCHEMA_ID, chunkContent);
        partition.put(chunkedKeyBytes, ByteBuffer.wrap(chunkOnDisk));
        chunkKeys.add(ByteBuffer.wrap(chunkedKeyBytes));
      }

      // Build manifest and write at top-level key. Manifest on disk: [CHUNK_MANIFEST_SCHEMA_ID][manifestAvro].
      ChunkedValueManifest manifest = new ChunkedValueManifest();
      manifest.keysWithChunkIdSuffix = chunkKeys;
      manifest.schemaId = VALUE_SCHEMA_ID;
      manifest.size = baseAvro.length;
      byte[] manifestOnDisk = serializeManifestOnDisk(manifest);
      partition.put(topLevelKey, ByteBuffer.wrap(manifestOnDisk));

      // Merge an operand that overwrites firstName.
      GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", "NewAlice").build();
      byte[] wcBytes = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
      byte[] operandContent = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wcBytes);
      partition.merge(topLevelKey, ByteBuffer.wrap(operandContent));

      // Read via partition.get — post-fix should return the materialized record with the operand
      // applied to the reassembled chunked base.
      byte[] readBytes = partition.get(topLevelKey);
      assertNotNull(readBytes, "post-merge get returned null");
      assertEquals(ByteUtils.readInt(readBytes, 0), VALUE_SCHEMA_ID, "header schemaId should be user value schemaId");
      byte[] avroOnly = new byte[readBytes.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "NewAlice", "operand should override firstName");
      assertEquals(decoded.get("lastName").toString(), "Smith", "lastName from chunked base should remain");
    } finally {
      partition.drop();
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Multiple operands on a chunked manifest — verifies the trailing-operand-chain parser handles
   * more than one operand and the fold applies them in order.
   */
  @Test
  public void chunkedManifestWithMultipleOperandsFoldsAllInOrder() throws Exception {
    String storeName = Version.composeKafkaTopic("store-chunked-multi-" + System.nanoTime(), 1);
    int partitionId = 0;

    String storeShortName = Version.parseStoreFromKafkaTopicName(storeName);
    ReadOnlySchemaRepository schemaRepo = mockSchemaRepo(storeShortName);
    StoreWriteComputeProcessor wcProcessor =
        new StoreWriteComputeProcessor(storeShortName, schemaRepo, new CollectionTimestampMergeRecordHelper(), false);
    MaterializingFoldContext foldContext = new MaterializingFoldContext(storeShortName, schemaRepo, wcProcessor, false);
    MaterializingFoldContextRegistry.register(storeName, foldContext);

    MaterializingRocksDBStoragePartition partition = openPartition(storeName, partitionId);
    try {
      byte[] rawUserKey = "k-chunked-multi".getBytes();
      KeyWithChunkingSuffixSerializer keySer =
          com.linkedin.davinci.storage.chunking.ChunkingUtils.KEY_WITH_CHUNKING_SUFFIX_SERIALIZER;
      byte[] topLevelKey = keySer.serializeNonChunkedKey(rawUserKey);

      MapOrderPreservingSerializer<GenericRecord> valueSer = MapOrderPreservingSerDeFactory.getSerializer(VALUE_SCHEMA);
      byte[] baseAvro = valueSer.serialize(recordOf("InitFirst", "InitLast"));

      // Single chunk for simplicity (chunking semantics doesn't depend on chunk count).
      ChunkedKeySuffix suffix = makeChunkKeySuffix(0);
      ByteBuffer chunkedKey = keySer.serializeChunkedKey(rawUserKey, suffix);
      partition.put(chunkedKey.array(), ByteBuffer.wrap(wrapWithSchemaId(CHUNK_SCHEMA_ID, baseAvro)));

      ChunkedValueManifest manifest = new ChunkedValueManifest();
      manifest.keysWithChunkIdSuffix = new ArrayList<>();
      manifest.keysWithChunkIdSuffix.add(ByteBuffer.wrap(chunkedKey.array()));
      manifest.schemaId = VALUE_SCHEMA_ID;
      manifest.size = baseAvro.length;
      partition.put(topLevelKey, ByteBuffer.wrap(serializeManifestOnDisk(manifest)));

      // Merge 3 successive operands.
      String[] firstNames = { "A", "B", "C" };
      for (String fn: firstNames) {
        GenericRecord wcRec = new UpdateBuilderImpl(WC_SCHEMA).setNewFieldValue("firstName", fn).build();
        byte[] wc = MapOrderPreservingSerDeFactory.<GenericRecord>getSerializer(WC_SCHEMA).serialize(wcRec);
        byte[] op = MaterializingFoldContext.OperandContent.frame(VALUE_SCHEMA_ID, WC_SCHEMA_ID, wc);
        partition.merge(topLevelKey, ByteBuffer.wrap(op));
      }

      byte[] readBytes = partition.get(topLevelKey);
      assertNotNull(readBytes);
      assertEquals(ByteUtils.readInt(readBytes, 0), VALUE_SCHEMA_ID);
      byte[] avroOnly = new byte[readBytes.length - ByteUtils.SIZE_OF_INT];
      System.arraycopy(readBytes, ByteUtils.SIZE_OF_INT, avroOnly, 0, avroOnly.length);
      GenericRecord decoded =
          MapOrderPreservingSerDeFactory.getDeserializer(VALUE_SCHEMA, VALUE_SCHEMA).deserialize(avroOnly);
      assertEquals(decoded.get("firstName").toString(), "C", "last operand should win");
      assertEquals(decoded.get("lastName").toString(), "InitLast");
    } finally {
      partition.drop();
      MaterializingFoldContextRegistry.unregister(storeName);
    }
  }

  /**
   * Chunked manifest with NO appended operands — the pre-fix code path. Verify the post-fix code
   * doesn't break this case (must return raw bytes so the upper-layer chunking adapter can
   * reassemble normally).
   */
  @Test
  public void chunkedManifestWithoutOperandsReturnsRaw() throws Exception {
    String storeName = Version.composeKafkaTopic("store-chunked-no-op-" + System.nanoTime(), 1);
    int partitionId = 0;

    // No fold context registered (intentional — pure manifest path should not need fold).
    MaterializingRocksDBStoragePartition partition = openPartition(storeName, partitionId);
    try {
      byte[] rawUserKey = "k-chunked-no-op".getBytes();
      KeyWithChunkingSuffixSerializer keySer =
          com.linkedin.davinci.storage.chunking.ChunkingUtils.KEY_WITH_CHUNKING_SUFFIX_SERIALIZER;
      byte[] topLevelKey = keySer.serializeNonChunkedKey(rawUserKey);

      ChunkedKeySuffix suffix = makeChunkKeySuffix(0);
      ByteBuffer chunkedKey = keySer.serializeChunkedKey(rawUserKey, suffix);
      byte[] chunkContent = "chunk-payload-bytes".getBytes();
      partition.put(chunkedKey.array(), ByteBuffer.wrap(wrapWithSchemaId(CHUNK_SCHEMA_ID, chunkContent)));

      ChunkedValueManifest manifest = new ChunkedValueManifest();
      manifest.keysWithChunkIdSuffix = new ArrayList<>();
      manifest.keysWithChunkIdSuffix.add(ByteBuffer.wrap(chunkedKey.array()));
      manifest.schemaId = VALUE_SCHEMA_ID;
      manifest.size = chunkContent.length;
      byte[] manifestOnDisk = serializeManifestOnDisk(manifest);
      partition.put(topLevelKey, ByteBuffer.wrap(manifestOnDisk));

      byte[] readBytes = partition.get(topLevelKey);
      assertNotNull(readBytes);
      // The post-fix materialize MUST return raw bytes for a pure manifest (no operands) so the
      // upper-layer chunking adapter handles reassembly. Verify shape: starts with the negative
      // chunked-manifest schemaId.
      assertEquals(
          ByteUtils.readInt(readBytes, 0),
          CHUNK_MANIFEST_SCHEMA_ID,
          "pure manifest read should return raw bytes preserving the negative chunked-manifest schemaId");
      assertEquals(readBytes, manifestOnDisk, "pure manifest read should be byte-identical to on-disk bytes");
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
    extraProps.put(SERVER_VT_MERGE_MAX_CHAIN_LENGTH, "0");
    VeniceProperties veniceServerProperties =
        AbstractStorageEngineTest.getServerProperties(PersistenceType.ROCKS_DB, extraProps);
    RocksDBServerConfig rocksDBServerConfig = new RocksDBServerConfig(veniceServerProperties);
    VeniceServerConfig serverConfig = new VeniceServerConfig(veniceServerProperties);
    RocksDBStorageEngineFactory factory = new RocksDBStorageEngineFactory(serverConfig);

    StoragePartitionConfig partitionConfig = new StoragePartitionConfig(storeName, partitionId);
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

  private static ReadOnlySchemaRepository mockSchemaRepo(String storeName) {
    ReadOnlySchemaRepository repo = Mockito.mock(ReadOnlySchemaRepository.class);
    SchemaEntry valueEntry = new SchemaEntry(VALUE_SCHEMA_ID, VALUE_SCHEMA);
    Mockito.when(repo.getValueSchema(storeName, VALUE_SCHEMA_ID)).thenReturn(valueEntry);
    DerivedSchemaEntry wcEntry = new DerivedSchemaEntry(VALUE_SCHEMA_ID, WC_SCHEMA_ID, WC_SCHEMA);
    Mockito.when(repo.getDerivedSchema(storeName, VALUE_SCHEMA_ID, WC_SCHEMA_ID)).thenReturn(wcEntry);
    return repo;
  }

  private static byte[] wrapWithSchemaId(int schemaId, byte[] payload) {
    byte[] out = new byte[ByteUtils.SIZE_OF_INT + payload.length];
    ByteUtils.writeInt(out, schemaId, 0);
    System.arraycopy(payload, 0, out, ByteUtils.SIZE_OF_INT, payload.length);
    return out;
  }

  /**
   * Serialize a {@link ChunkedValueManifest} into the on-disk shape:
   * {@code [CHUNK_MANIFEST_SCHEMA_ID : 4B BE][manifest avro]}.
   *
   * <p>The {@link ChunkedValueManifestSerializer} prepends a 4-byte zero-padding header; we
   * replace those 4 bytes with the negative chunked-manifest schemaId (this matches the wire
   * format used by VeniceWriter's chunking layer — see
   * {@code VeniceWriter.putLargeValue} which writes the manifest with the negative schemaId in
   * the header).
   */
  private static byte[] serializeManifestOnDisk(ChunkedValueManifest manifest) {
    ChunkedValueManifestSerializer ser = new ChunkedValueManifestSerializer(true /* withoutHeader */);
    byte[] avro = ser.serialize(null, manifest);
    return wrapWithSchemaId(CHUNK_MANIFEST_SCHEMA_ID, avro);
  }

  private static ChunkedKeySuffix makeChunkKeySuffix(int chunkIndex) {
    ChunkedKeySuffix suffix = new ChunkedKeySuffix();
    suffix.isChunk = true;
    suffix.chunkId = new com.linkedin.venice.storage.protocol.ChunkId();
    suffix.chunkId.segmentNumber = 0;
    suffix.chunkId.messageSequenceNumber = 0;
    suffix.chunkId.chunkIndex = chunkIndex;
    suffix.chunkId.producerGUID = new com.linkedin.venice.kafka.protocol.GUID(new byte[16]);
    return suffix;
  }
}
