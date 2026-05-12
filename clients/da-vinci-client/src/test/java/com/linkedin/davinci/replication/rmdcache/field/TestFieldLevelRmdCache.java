package com.linkedin.davinci.replication.rmdcache.field;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

import com.linkedin.avroutil1.compatibility.AvroCompatibilityHelper;
import com.linkedin.venice.schema.rmd.v1.CollectionRmdTimestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;
import org.testng.annotations.Test;


/**
 * Unit tests for {@link FieldLevelRmdCache} state machine + Gotchas #1/#2.
 *
 * <p>Per {@code autoresearch/vt-merge-cross-dc-fix/GOAL.md} §4.2/§4.3:
 * <ul>
 *   <li>Gotcha #1: empty collection field at PUT must synthesize topLevelFieldTs=Long.MIN_VALUE
 *       so subsequent UPDATEs (and collection-element-level DCR) work correctly.</li>
 *   <li>Gotcha #2: scalar fields at schema default at PUT must synthesize
 *       populatedByPut=false + topLevelFieldTs=Long.MIN_VALUE so subsequent UPDATEs targeting
 *       those fields are not blocked by the PUT's ts.</li>
 * </ul>
 */
public class TestFieldLevelRmdCache {
  private static final String SCALAR_SCHEMA = "{\n" //
      + "  \"type\": \"record\",\n" //
      + "  \"name\": \"Scalars\",\n" //
      + "  \"fields\": [\n" //
      + "    {\"name\": \"name\", \"type\": \"string\", \"default\": \"\"},\n" //
      + "    {\"name\": \"age\", \"type\": \"int\", \"default\": -1}\n" //
      + "  ]\n" //
      + "}";

  private static final String MIXED_SCHEMA = "{\n" //
      + "  \"type\": \"record\",\n" //
      + "  \"name\": \"Mixed\",\n" //
      + "  \"fields\": [\n" //
      + "    {\"name\": \"name\", \"type\": \"string\", \"default\": \"\"},\n" //
      + "    {\"name\": \"items\", \"type\": {\"type\": \"array\", \"items\": \"int\"}, \"default\": []},\n" //
      + "    {\"name\": \"props\", \"type\": {\"type\": \"map\", \"values\": \"string\"}, \"default\": {}}\n" //
      + "  ]\n" //
      + "}";

  // ---------------- mode-transition + per-field decision tests ----------------

  @Test
  public void testEmptyCacheIsMissForAnyKey() {
    FieldLevelRmdCache cache = new FieldLevelRmdCache(0);
    byte[] key = "k1".getBytes();
    assertNull(cache.getEntry(key, 0));
    assertNull(cache.getModeEntry(key));
    assertFalse(cache.isInPerFieldMode(key));
  }

  @Test
  public void testPutModeZeroInstallsWholeRecordSentinel() {
    FieldLevelRmdCache cache = new FieldLevelRmdCache(0);
    byte[] key = "k1".getBytes();
    cache.putModeZero(key, 42L);

    FieldRmdEntry mode = cache.getModeEntry(key);
    assertNotNull(mode);
    assertFalse(FieldLevelRmdCache.isPerFieldMode(mode));
    assertEquals(mode.getTopLevelFieldTs(), 42L);
    assertFalse(cache.isInPerFieldMode(key));
    assertNull(cache.getEntry(key, 0), "Mode 0 must not return per-field entries");
  }

  @Test
  public void testPutPerFieldEntriesInstallsMode1() {
    FieldLevelRmdCache cache = new FieldLevelRmdCache(0);
    byte[] key = "k1".getBytes();
    Map<Integer, FieldRmdEntry> entries = new HashMap<>();
    entries.put(0, FieldRmdEntry.forScalar(10L, true));
    entries.put(1, FieldRmdEntry.forScalar(20L, true));
    cache.putPerFieldEntries(key, entries);

    assertTrue(cache.isInPerFieldMode(key));
    assertEquals(cache.getEntry(key, 0).getTopLevelFieldTs(), 10L);
    assertEquals(cache.getEntry(key, 1).getTopLevelFieldTs(), 20L);
    assertEquals(cache.getModeFlipToPerFieldCount(), 1);
  }

  @Test
  public void testMode1ToMode0EvictsAllPerFieldEntries() {
    FieldLevelRmdCache cache = new FieldLevelRmdCache(0);
    byte[] key = "k1".getBytes();
    Map<Integer, FieldRmdEntry> entries = new HashMap<>();
    entries.put(0, FieldRmdEntry.forScalar(10L, true));
    entries.put(1, FieldRmdEntry.forScalar(20L, true));
    cache.putPerFieldEntries(key, entries);
    assertTrue(cache.isInPerFieldMode(key));

    cache.putModeZero(key, 99L);
    assertFalse(cache.isInPerFieldMode(key));
    assertNull(cache.getEntry(key, 0));
    assertNull(cache.getEntry(key, 1));
    assertEquals(cache.getModeFlipToWholeRecordCount(), 1);
  }

  @Test
  public void testUpdateEntryNoopIfKeyInMode0() {
    FieldLevelRmdCache cache = new FieldLevelRmdCache(0);
    byte[] key = "k1".getBytes();
    cache.putModeZero(key, 10L);
    cache.updateEntry(key, 0, FieldRmdEntry.forScalar(99L, true));
    assertNull(cache.getEntry(key, 0), "updateEntry must not promote a mode-0 key to mode-1");
  }

  @Test
  public void testInvalidateRemovesAllEntries() {
    FieldLevelRmdCache cache = new FieldLevelRmdCache(0);
    byte[] key = "k1".getBytes();
    cache.putModeZero(key, 10L);
    cache.invalidate(key);
    assertNull(cache.getModeEntry(key));
  }

  @Test
  public void testNewWinsHonorsPopulatedByPut() {
    // Field populated by PUT at ts=10. Operand at ts=5 must lose; at ts=15 must win.
    FieldRmdEntry populated = FieldRmdEntry.forScalar(10L, true);
    assertFalse(populated.newWins(5L));
    assertTrue(populated.newWins(15L));

    // Field NOT populated by PUT (default-at-PUT). Any operand wins.
    FieldRmdEntry unpopulated = FieldRmdEntry.forScalar(Long.MIN_VALUE, false);
    assertTrue(unpopulated.newWins(1L));
    assertTrue(unpopulated.newWins(Long.MIN_VALUE + 1));
  }

  // ---------------- Gotcha #1: empty collection at PUT ----------------

  @Test
  public void testSynthesizeFromPut_emptyArrayFieldGetsMinValueTs() {
    Schema valueSchema = AvroCompatibilityHelper.parse(MIXED_SCHEMA);
    GenericRecord base = new GenericData.Record(valueSchema);
    base.put("name", "Foo");
    base.put("items", new java.util.ArrayList<Integer>()); // EMPTY array
    base.put("props", new HashMap<String, String>()); // EMPTY map

    Map<Integer, FieldRmdEntry> entries = FieldLevelRmdCache.synthesizeFromPut(base, valueSchema, 100L);

    // name (ordinal 0) is populated.
    assertEquals(entries.get(0).getTopLevelFieldTs(), 100L);
    assertTrue(entries.get(0).isPopulatedByPut());

    // items (ordinal 1) is empty → Long.MIN_VALUE, populatedByPut=false. Gotcha #1.
    FieldRmdEntry items = entries.get(1);
    assertEquals(items.getTopLevelFieldTs(), Long.MIN_VALUE);
    assertFalse(items.isPopulatedByPut());
    assertTrue(items.isCollection());

    // props (ordinal 2) is empty map → Gotcha #1.
    FieldRmdEntry props = entries.get(2);
    assertEquals(props.getTopLevelFieldTs(), Long.MIN_VALUE);
    assertFalse(props.isPopulatedByPut());
  }

  @Test
  public void testSynthesizeFromPut_nonEmptyArrayFieldGetsPutTs() {
    Schema valueSchema = AvroCompatibilityHelper.parse(MIXED_SCHEMA);
    GenericRecord base = new GenericData.Record(valueSchema);
    base.put("name", "Foo");
    base.put("items", Arrays.asList(1, 2, 3)); // POPULATED
    base.put("props", new HashMap<String, String>()); // empty

    Map<Integer, FieldRmdEntry> entries = FieldLevelRmdCache.synthesizeFromPut(base, valueSchema, 100L);

    // items is populated, so topLevelTs = PUT ts.
    FieldRmdEntry items = entries.get(1);
    assertEquals(items.getTopLevelFieldTs(), 100L);
    assertTrue(items.isPopulatedByPut());

    // props is still empty → Gotcha #1.
    FieldRmdEntry props = entries.get(2);
    assertEquals(props.getTopLevelFieldTs(), Long.MIN_VALUE);
    assertFalse(props.isPopulatedByPut());
  }

  // ---------------- Gotcha #2: scalar at schema default at PUT ----------------

  @Test
  public void testSynthesizeFromPut_scalarAtSchemaDefaultGetsMinValueTs() {
    Schema valueSchema = AvroCompatibilityHelper.parse(SCALAR_SCHEMA);
    GenericRecord base = new GenericData.Record(valueSchema);
    // name = "" (schema default) — Gotcha #2 fires.
    base.put("name", "");
    base.put("age", 42); // populated

    Map<Integer, FieldRmdEntry> entries = FieldLevelRmdCache.synthesizeFromPut(base, valueSchema, 100L);

    // name at default → no DCR floor.
    FieldRmdEntry name = entries.get(0);
    assertEquals(name.getTopLevelFieldTs(), Long.MIN_VALUE);
    assertFalse(name.isPopulatedByPut());

    // age populated → DCR floor at 100.
    FieldRmdEntry age = entries.get(1);
    assertEquals(age.getTopLevelFieldTs(), 100L);
    assertTrue(age.isPopulatedByPut());
  }

  @Test
  public void testSynthesizeFromPut_scalarWithNonDefaultButSameTypeStillPopulated() {
    Schema valueSchema = AvroCompatibilityHelper.parse(SCALAR_SCHEMA);
    GenericRecord base = new GenericData.Record(valueSchema);
    base.put("name", "Foo");
    base.put("age", -1); // age default is -1; this matches → Gotcha #2.

    Map<Integer, FieldRmdEntry> entries = FieldLevelRmdCache.synthesizeFromPut(base, valueSchema, 100L);

    assertEquals(entries.get(0).getTopLevelFieldTs(), 100L);
    assertTrue(entries.get(0).isPopulatedByPut());

    // age was set to its schema default → no DCR floor.
    assertEquals(entries.get(1).getTopLevelFieldTs(), Long.MIN_VALUE);
    assertFalse(entries.get(1).isPopulatedByPut());
  }

  @Test
  public void testSynthesizeFromPut_nullBaseRecordTreatsAllAsDefault() {
    Schema valueSchema = AvroCompatibilityHelper.parse(MIXED_SCHEMA);
    Map<Integer, FieldRmdEntry> entries = FieldLevelRmdCache.synthesizeFromPut(null, valueSchema, 100L);

    for (FieldRmdEntry e: entries.values()) {
      assertEquals(e.getTopLevelFieldTs(), Long.MIN_VALUE);
      assertFalse(e.isPopulatedByPut());
    }
  }

  // ---------------- end-to-end cross-DC DCR decision exercise ----------------

  @Test
  public void testCrossDcDecision_putThenLowerTsUpdateLoses() {
    Schema valueSchema = AvroCompatibilityHelper.parse(SCALAR_SCHEMA);
    GenericRecord base = new GenericData.Record(valueSchema);
    base.put("name", "val2f1_b");
    base.put("age", 20);

    Map<Integer, FieldRmdEntry> synthesized = FieldLevelRmdCache.synthesizeFromPut(base, valueSchema, 2L);

    // dc-1 sends UPDATE@ts=1 setting name=val2f1_a → must LOSE on name.
    assertFalse(synthesized.get(0).newWins(1L));

    // dc-1 sends UPDATE@ts=3 setting age=40 → must WIN on age.
    assertTrue(synthesized.get(1).newWins(3L));
  }

  @Test
  public void testCrossDcDecision_putWithEmptyListThenUpdateAlwaysWins() {
    Schema valueSchema = AvroCompatibilityHelper.parse(MIXED_SCHEMA);
    GenericRecord base = new GenericData.Record(valueSchema);
    base.put("name", "Foo");
    base.put("items", new java.util.ArrayList<Integer>()); // empty
    base.put("props", new HashMap<String, String>()); // empty

    Map<Integer, FieldRmdEntry> synthesized = FieldLevelRmdCache.synthesizeFromPut(base, valueSchema, 100L);

    // Gotcha #1: list field empty at PUT → ANY operand ts wins.
    FieldRmdEntry items = synthesized.get(1);
    assertTrue(items.newWins(1L));
    assertTrue(items.newWins(Long.MIN_VALUE + 1));
  }

  // ---------------- buildPerFieldTsRecord ----------------

  @Test
  public void testBuildPerFieldTsRecordPopulatesScalarFields() {
    Schema valueSchema = AvroCompatibilityHelper.parse(SCALAR_SCHEMA);
    Schema rmdSchema = com.linkedin.venice.schema.rmd.RmdSchemaGenerator
        .generateMetadataSchema(valueSchema, com.linkedin.venice.schema.rmd.RmdSchemaGenerator.getLatestVersion());

    Map<Integer, FieldRmdEntry> entries = new HashMap<>();
    entries.put(0, FieldRmdEntry.forScalar(42L, true));
    entries.put(1, FieldRmdEntry.forScalar(99L, true));

    GenericRecord perFieldTs = FieldLevelRmdCache.buildPerFieldTsRecord(rmdSchema, valueSchema, entries);
    assertEquals(perFieldTs.get("name"), 42L);
    assertEquals(perFieldTs.get("age"), 99L);
  }

  @Test
  public void testBuildPerFieldTsRecordPopulatesCollectionFields() {
    Schema valueSchema = AvroCompatibilityHelper.parse(MIXED_SCHEMA);
    Schema rmdSchema = com.linkedin.venice.schema.rmd.RmdSchemaGenerator
        .generateMetadataSchema(valueSchema, com.linkedin.venice.schema.rmd.RmdSchemaGenerator.getLatestVersion());

    GenericRecord base = new GenericData.Record(valueSchema);
    base.put("name", "Foo");
    base.put("items", Arrays.asList(1, 2, 3));
    base.put("props", new HashMap<String, String>()); // empty -> Gotcha #1

    Map<Integer, FieldRmdEntry> entries = FieldLevelRmdCache.synthesizeFromPut(base, valueSchema, 50L);

    GenericRecord perFieldTs = FieldLevelRmdCache.buildPerFieldTsRecord(rmdSchema, valueSchema, entries);

    // name ts
    assertEquals(perFieldTs.get("name"), 50L);

    // items: collection with non-empty population → topLevelFieldTs = 50
    GenericRecord itemsRmd = (GenericRecord) perFieldTs.get("items");
    assertEquals(itemsRmd.get(CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME), 50L);

    // props: empty collection → topLevelFieldTs = Long.MIN_VALUE per Gotcha #1
    GenericRecord propsRmd = (GenericRecord) perFieldTs.get("props");
    assertEquals(propsRmd.get(CollectionRmdTimestamp.TOP_LEVEL_TS_FIELD_NAME), Long.MIN_VALUE);
  }

  @Test
  public void testBuildPerFieldTsRecordFallsBackToZeroForMissingEntry() {
    Schema valueSchema = AvroCompatibilityHelper.parse(SCALAR_SCHEMA);
    Schema rmdSchema = com.linkedin.venice.schema.rmd.RmdSchemaGenerator
        .generateMetadataSchema(valueSchema, com.linkedin.venice.schema.rmd.RmdSchemaGenerator.getLatestVersion());

    GenericRecord perFieldTs = FieldLevelRmdCache.buildPerFieldTsRecord(rmdSchema, valueSchema, null);
    assertEquals(perFieldTs.get("name"), 0L);
    assertEquals(perFieldTs.get("age"), 0L);
  }
}
