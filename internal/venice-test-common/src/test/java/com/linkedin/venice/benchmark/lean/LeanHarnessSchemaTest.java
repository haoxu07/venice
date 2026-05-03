package com.linkedin.venice.benchmark.lean;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;
import static org.testng.Assert.fail;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.VeniceNoStoreException;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.meta.VersionStatus;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.schema.rmd.RmdSchemaEntry;
import com.linkedin.venice.schema.rmd.v1.RmdSchemaGeneratorV1;
import com.linkedin.venice.schema.writecompute.DerivedSchemaEntry;
import com.linkedin.venice.schema.writecompute.WriteComputeSchemaConverter;
import com.linkedin.venice.serializer.FastSerializerDeserializerFactory;
import com.linkedin.venice.serializer.RecordDeserializer;
import com.linkedin.venice.serializer.RecordSerializer;
import com.linkedin.venice.writer.update.UpdateBuilder;
import com.linkedin.venice.writer.update.UpdateBuilderImpl;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;


/**
 * Phase 1 unit test for the Lean AA Ingestion Harness — verifies the in-memory schema and store repositories
 * pre-load correctly and round-trip through fastAvro serde.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@link InMemoryReadOnlySchemaRepository} returns key, value, RMD, and write-compute schemas via the
 *       expected lookup methods.</li>
 *   <li>A {@code BenchmarkRecord} can be serialized and deserialized via fastAvro using the looked-up value
 *       schema.</li>
 *   <li>An RMD record (per the looked-up RMD schema) round-trips correctly.</li>
 *   <li>A write-compute UPDATE record built via {@code UpdateBuilderImpl} round-trips correctly.</li>
 *   <li>{@link InMemoryReadOnlyStoreRepository} exposes a single store with the expected AA / WC / hybrid
 *       configuration and a single ready-to-serve version.</li>
 *   <li>Lookups for unknown stores throw the documented exceptions.</li>
 * </ul>
 */
public class LeanHarnessSchemaTest {
  // Same value schema as ActiveActiveIngestionBenchmark.VALUE_SCHEMA_STR (string fields + 100-entry tags map shape).
  private static final String VALUE_SCHEMA_STR = "{\n" + "  \"type\": \"record\",\n"
      + "  \"name\": \"BenchmarkRecord\",\n" + "  \"namespace\": \"com.linkedin.venice.benchmark\",\n"
      + "  \"fields\": [\n" + "    { \"name\": \"name\", \"type\": \"string\", \"default\": \"default_name\" },\n"
      + "    { \"name\": \"age\", \"type\": \"int\", \"default\": -1 },\n"
      + "    { \"name\": \"score\", \"type\": \"double\", \"default\": 0.0 },\n"
      + "    { \"name\": \"tags\", \"type\": " + "{ \"type\": \"map\", \"values\": \"string\" }, \"default\": {} }\n"
      + "  ]\n" + "}";

  private static final String KEY_SCHEMA_STR = "\"string\"";
  private static final String STORE_NAME = "lean-harness-store";

  private Schema keySchema;
  private Schema valueSchema;
  private Schema rmdSchema;
  private Schema writeComputeSchema;
  private InMemoryReadOnlySchemaRepository schemaRepo;
  private InMemoryReadOnlyStoreRepository storeRepo;

  @BeforeClass
  public void setUp() {
    keySchema = new Schema.Parser().parse(KEY_SCHEMA_STR);
    valueSchema = new Schema.Parser().parse(VALUE_SCHEMA_STR);
    rmdSchema = new RmdSchemaGeneratorV1().generateMetadataSchema(valueSchema);
    writeComputeSchema = WriteComputeSchemaConverter.getInstance().convertFromValueRecordSchema(valueSchema);
    schemaRepo =
        new InMemoryReadOnlySchemaRepository(STORE_NAME, keySchema, valueSchema, rmdSchema, writeComputeSchema);
    storeRepo = new InMemoryReadOnlyStoreRepository(STORE_NAME);
  }

  @Test
  public void testKeyAndValueSchemaLookup() {
    SchemaEntry key = schemaRepo.getKeySchema(STORE_NAME);
    assertNotNull(key, "key schema must be registered");
    assertEquals(key.getId(), InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);
    assertEquals(key.getSchema(), keySchema);

    SchemaEntry value = schemaRepo.getValueSchema(STORE_NAME, InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);
    assertNotNull(value, "value schema must be registered");
    assertEquals(value.getId(), InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);
    assertEquals(value.getSchema(), valueSchema);

    // Latest / superset-or-latest must return the value schema.
    assertEquals(schemaRepo.getSupersetOrLatestValueSchema(STORE_NAME).getSchema(), valueSchema);

    assertTrue(schemaRepo.hasValueSchema(STORE_NAME, InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID));
    assertFalse(schemaRepo.hasValueSchema(STORE_NAME, 99));
    assertNull(schemaRepo.getValueSchema(STORE_NAME, 99), "unknown id must return null per the documented contract");
  }

  @Test
  public void testRmdAndWriteComputeSchemaLookup() {
    RmdSchemaEntry rmdEntry = schemaRepo.getReplicationMetadataSchema(
        STORE_NAME,
        InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID,
        InMemoryReadOnlySchemaRepository.RMD_PROTOCOL_VERSION);
    assertNotNull(rmdEntry);
    assertEquals(rmdEntry.getValueSchemaID(), InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);
    assertEquals(rmdEntry.getId(), InMemoryReadOnlySchemaRepository.RMD_PROTOCOL_VERSION);
    assertEquals(rmdEntry.getSchema(), rmdSchema);

    DerivedSchemaEntry derivedEntry = schemaRepo.getDerivedSchema(
        STORE_NAME,
        InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID,
        InMemoryReadOnlySchemaRepository.WRITE_COMPUTE_PROTOCOL_VERSION);
    assertNotNull(derivedEntry);
    assertEquals(derivedEntry.getValueSchemaID(), InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);
    assertEquals(derivedEntry.getId(), InMemoryReadOnlySchemaRepository.WRITE_COMPUTE_PROTOCOL_VERSION);
    assertEquals(derivedEntry.getSchema(), writeComputeSchema);

    // Latest derived schema must point at the same WC entry.
    DerivedSchemaEntry latest =
        schemaRepo.getLatestDerivedSchema(STORE_NAME, InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);
    assertEquals(latest.getSchema(), writeComputeSchema);

    // Collection accessors return single-entry collections.
    assertEquals(schemaRepo.getValueSchemas(STORE_NAME).size(), 1);
    assertEquals(schemaRepo.getDerivedSchemas(STORE_NAME).size(), 1);
    assertEquals(schemaRepo.getReplicationMetadataSchemas(STORE_NAME).size(), 1);
  }

  @Test
  public void testValueRecordRoundTripsViaFastAvro() {
    SchemaEntry valueEntry = schemaRepo.getValueSchema(STORE_NAME, InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);
    Schema lookedUp = valueEntry.getSchema();

    GenericRecord original = new GenericData.Record(lookedUp);
    original.put("name", "alice");
    original.put("age", 42);
    original.put("score", 3.14);
    Map<String, String> tags = new HashMap<>();
    tags.put("region", "dc-0");
    tags.put("rank", "gold");
    original.put("tags", tags);

    RecordSerializer<GenericRecord> serializer =
        FastSerializerDeserializerFactory.getFastAvroGenericSerializer(lookedUp);
    RecordDeserializer<GenericRecord> deserializer =
        FastSerializerDeserializerFactory.getFastAvroGenericDeserializer(lookedUp, lookedUp);

    byte[] bytes = serializer.serialize(original);
    GenericRecord roundTripped = deserializer.deserialize(bytes);

    assertEquals(roundTripped.get("name").toString(), "alice");
    assertEquals(roundTripped.get("age"), 42);
    assertEquals((double) roundTripped.get("score"), 3.14, 1e-9);
    Object roundTrippedTags = roundTripped.get("tags");
    assertNotNull(roundTrippedTags);
    assertTrue(roundTrippedTags instanceof Map, "tags must deserialise to a Map");
    Map<?, ?> tagsMap = (Map<?, ?>) roundTrippedTags;
    assertEquals(tagsMap.size(), 2);
    // Avro uses Utf8 for string keys/values in generic records — compare via toString().
    assertEquals(tagsMap.get(new org.apache.avro.util.Utf8("region")).toString(), "dc-0");
    assertEquals(tagsMap.get(new org.apache.avro.util.Utf8("rank")).toString(), "gold");
  }

  @Test
  public void testRmdRecordRoundTripsViaFastAvro() {
    RmdSchemaEntry rmdEntry = schemaRepo.getReplicationMetadataSchema(
        STORE_NAME,
        InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID,
        InMemoryReadOnlySchemaRepository.RMD_PROTOCOL_VERSION);
    Schema rmd = rmdEntry.getSchema();

    GenericRecord rmdRecord = new GenericData.Record(rmd);
    // Set "timestamp" as the long-typed value-level timestamp variant of the union.
    rmdRecord.put("timestamp", 1234567890L);
    java.util.List<Long> offsetVector = new java.util.ArrayList<>();
    offsetVector.add(100L);
    offsetVector.add(200L);
    rmdRecord.put("replication_checkpoint_vector", offsetVector);

    RecordSerializer<GenericRecord> serializer = FastSerializerDeserializerFactory.getFastAvroGenericSerializer(rmd);
    RecordDeserializer<GenericRecord> deserializer =
        FastSerializerDeserializerFactory.getFastAvroGenericDeserializer(rmd, rmd);
    byte[] bytes = serializer.serialize(rmdRecord);
    GenericRecord roundTripped = deserializer.deserialize(bytes);

    assertEquals(roundTripped.get("timestamp"), 1234567890L);
    Object rcv = roundTripped.get("replication_checkpoint_vector");
    assertNotNull(rcv);
    assertTrue(rcv instanceof java.util.List, "checkpoint vector must deserialise to a List");
    java.util.List<?> rcvList = (java.util.List<?>) rcv;
    assertEquals(rcvList.size(), 2);
    assertEquals(rcvList.get(0), 100L);
    assertEquals(rcvList.get(1), 200L);
  }

  @Test
  public void testWriteComputeUpdateRecordRoundTripsViaFastAvro() {
    DerivedSchemaEntry derivedEntry = schemaRepo.getDerivedSchema(
        STORE_NAME,
        InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID,
        InMemoryReadOnlySchemaRepository.WRITE_COMPUTE_PROTOCOL_VERSION);
    Schema wcSchema = derivedEntry.getSchema();

    UpdateBuilder builder = new UpdateBuilderImpl(wcSchema);
    builder.setNewFieldValue("name", "test");
    GenericRecord updateRecord = builder.build();
    assertEquals(updateRecord.getSchema(), wcSchema);

    RecordSerializer<GenericRecord> serializer =
        FastSerializerDeserializerFactory.getFastAvroGenericSerializer(wcSchema);
    RecordDeserializer<GenericRecord> deserializer =
        FastSerializerDeserializerFactory.getFastAvroGenericDeserializer(wcSchema, wcSchema);
    byte[] bytes = serializer.serialize(updateRecord);
    GenericRecord roundTripped = deserializer.deserialize(bytes);

    // The WC schema replaces field types with unions of their native type and "noop" / "delete-field" markers.
    // setNewFieldValue("name", "test") populates the "name" field with the string; other fields remain at their
    // schema-default values. We assert the "name" field deserialised to the original value.
    Object roundTrippedName = roundTripped.get("name");
    assertNotNull(roundTrippedName);
    assertEquals(roundTrippedName.toString(), "test");
  }

  @Test
  public void testStoreRepositoryExposesPhase1Configuration() {
    assertTrue(storeRepo.hasStore(STORE_NAME));
    Store store = storeRepo.getStoreOrThrow(STORE_NAME);
    assertNotNull(store);
    assertEquals(store.getName(), STORE_NAME);
    assertTrue(store.isActiveActiveReplicationEnabled(), "AA must be enabled");
    assertTrue(store.isWriteComputationEnabled(), "WC must be enabled");
    assertTrue(store.isNativeReplicationEnabled(), "Native replication must be enabled");
    assertTrue(store.isHybrid(), "Store must be hybrid");
    assertEquals(store.getHybridStoreConfig().getRewindTimeInSeconds(), 25L);
    assertEquals(store.getHybridStoreConfig().getOffsetLagThresholdToGoOnline(), 1L);
    assertEquals(store.getPartitionCount(), InMemoryReadOnlyStoreRepository.DEFAULT_PARTITION_COUNT);

    // Single ready-to-serve version
    assertEquals(store.getCurrentVersion(), InMemoryReadOnlyStoreRepository.DEFAULT_VERSION_NUMBER);
    Version version = store.getVersion(InMemoryReadOnlyStoreRepository.DEFAULT_VERSION_NUMBER);
    assertNotNull(version, "version 1 must be registered");
    assertEquals(version.getStatus(), VersionStatus.ONLINE, "version must be ready-to-serve");
    assertEquals(version.getPartitionCount(), InMemoryReadOnlyStoreRepository.DEFAULT_PARTITION_COUNT);

    assertEquals(storeRepo.getAllStores().size(), 1);
    assertEquals(storeRepo.getAllStores().get(0).getName(), STORE_NAME);
  }

  @Test
  public void testUnknownStoreLookupsFailLoudly() {
    assertFalse(storeRepo.hasStore("does-not-exist"));
    assertNull(storeRepo.getStore("does-not-exist"));
    expectThrows(VeniceNoStoreException.class, () -> storeRepo.getStoreOrThrow("does-not-exist"));

    // Schema repo must throw on unknown stores, NOT silently return null — that is what entry #6 of dep-graph.md
    // explicitly calls out as the medium-risk failure mode to guard against.
    expectThrows(VeniceException.class, () -> schemaRepo.getKeySchema("does-not-exist"));
    expectThrows(VeniceException.class, () -> schemaRepo.getValueSchema("does-not-exist", 1));
    expectThrows(VeniceException.class, () -> schemaRepo.getReplicationMetadataSchema("does-not-exist", 1, 1));
    expectThrows(VeniceException.class, () -> schemaRepo.getDerivedSchema("does-not-exist", 1, 1));
  }

  @Test
  public void testValueSchemaIdLookupByCanonicalString() {
    int id = schemaRepo.getValueSchemaId(STORE_NAME, valueSchema.toString());
    assertEquals(id, InMemoryReadOnlySchemaRepository.VALUE_SCHEMA_ID);

    try {
      schemaRepo.getValueSchemaId(STORE_NAME, "{\"type\":\"record\",\"name\":\"Bogus\",\"fields\":[]}");
      fail("expected VeniceException for unregistered schema string");
    } catch (VeniceException expected) {
      // correct
    }
  }
}
