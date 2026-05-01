package com.linkedin.venice.benchmark;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericRecord;


/**
 * Shared workload generation helpers for the AA ingestion benchmarks.
 *
 * <p>This class exists so that {@link ActiveActiveIngestionBenchmark} (full multi-region wrapper)
 * and {@link LeanActiveActiveIngestionBenchmark} (lean harness) can be apples-to-apples compared:
 * the workload (record shape, key pool, sentinel logic, mixed-op distribution) is identical;
 * only the cluster / harness underlying them differs.
 *
 * <p>Stateless: every method either takes the inputs it needs or returns a fresh object.
 * The caller is responsible for sending the produced records through whatever production path
 * (Samza system producer for the full benchmark, {@code VeniceWriter} for the lean benchmark).
 *
 * <p>Constants on this class are the canonical values both benchmarks should use.
 */
public final class AAIngestionWorkloadHelper {
  /** Number of records produced per benchmark invocation. */
  public static final int NUM_RECORDS_PER_INVOCATION = 1000;

  /**
   * Bounded key pool for PARTIAL_UPDATE so updates actually hit existing records and exercise
   * the server-side read-modify-write + field-level DCR path.
   */
  public static final int PARTIAL_UPDATE_KEY_POOL_SIZE = 100_000;

  /**
   * Bounded key pool for PUT so both producers concurrently hit the same keys and exercise the
   * value-level DCR path (timestamp-based conflict resolution).
   */
  public static final int PUT_KEY_POOL_SIZE = 100_000;

  /**
   * Each PARTIAL_UPDATE pool key is pre-populated with a tags map of this size. AddToMap updates
   * during the benchmark only overwrite values for map keys in {@code [0, TAGS_MAP_SIZE)}, so
   * the map size stays constant rather than growing.
   */
  public static final int TAGS_MAP_SIZE = 100;

  /** Number of sentinel records appended at the end of each PARTIAL_UPDATE invocation. */
  public static final int PARTIAL_UPDATE_SENTINEL_COUNT = 20;

  /** Number of sentinel records appended at end of trial for VT-consistency drain verification. */
  public static final int VT_CHECK_SENTINEL_COUNT = 20;

  public static final String KEY_SCHEMA_STR = "\"string\"";

  /**
   * Schema with regular fields + map field for collection merge benchmarking. Identical (line for
   * line) to {@code ActiveActiveIngestionBenchmark.VALUE_SCHEMA_STR} and
   * {@code MinimalAAIngestionHarness.DEFAULT_VALUE_SCHEMA_STR}.
   */
  public static final String VALUE_SCHEMA_STR = "{\n" + "  \"type\": \"record\",\n"
      + "  \"name\": \"BenchmarkRecord\",\n" + "  \"namespace\": \"com.linkedin.venice.benchmark\",\n"
      + "  \"fields\": [\n" + "    { \"name\": \"name\", \"type\": \"string\", \"default\": \"default_name\" },\n"
      + "    { \"name\": \"age\", \"type\": \"int\", \"default\": -1 },\n"
      + "    { \"name\": \"score\", \"type\": \"double\", \"default\": 0.0 },\n"
      + "    { \"name\": \"tags\", \"type\": " + "{ \"type\": \"map\", \"values\": \"string\" }, \"default\": {} }\n"
      + "  ]\n" + "}";

  /** PUT/PARTIAL_UPDATE/MIXED workload selection. */
  public enum WorkloadType {
    /** Full record PUTs from both regions — value-level timestamp DCR. */
    PUT,
    /** Field-level partial updates from both regions — field-level timestamp DCR + write-compute. */
    PARTIAL_UPDATE,
    /** Interleaved PUTs, partial updates, and deletes — realistic mixed workload. */
    MIXED
  }

  private AAIngestionWorkloadHelper() {
    // utility — no instances
  }

  /**
   * Build the canary "alive" record used by both benchmarks during setup to verify the pipeline
   * is up before the workload starts.
   */
  public static GenericRecord buildCanaryRecord(Schema valueSchema) {
    GenericRecord rec = new GenericData.Record(valueSchema);
    rec.put("name", "canary");
    rec.put("age", 0);
    rec.put("score", 0.0);
    rec.put("tags", Collections.<String, String>emptyMap());
    return rec;
  }

  /**
   * Build the per-pool-key initial record used to pre-populate the PARTIAL_UPDATE pool. Each pool
   * key carries a {@link #TAGS_MAP_SIZE}-entry tags map so subsequent AddToMap updates don't grow
   * the map.
   */
  public static GenericRecord buildPartialUpdatePoolInitRecord(Schema valueSchema, int poolIdx) {
    GenericRecord rec = new GenericData.Record(valueSchema);
    rec.put("name", "init-" + poolIdx);
    rec.put("age", 0);
    rec.put("score", 0.0);
    Map<String, String> initTags = new HashMap<>(TAGS_MAP_SIZE * 2);
    for (int m = 0; m < TAGS_MAP_SIZE; m++) {
      initTags.put("k-" + m, "init-v-" + m);
    }
    rec.put("tags", initTags);
    return rec;
  }

  /**
   * Build the canonical PUT record used by {@code runPutWorkload}. {@code seq} is the globally
   * unique key counter; {@code dcIndex} is 0 or 1 (for tagging the record's region).
   */
  public static GenericRecord buildPutRecord(Schema valueSchema, long seq, int dcIndex) {
    GenericRecord rec = new GenericData.Record(valueSchema);
    rec.put("name", "user-" + seq);
    rec.put("age", (int) (seq % 100));
    rec.put("score", seq * 1.1);
    Map<String, String> tags = new HashMap<>(2);
    tags.put("region", dcIndex == 0 ? "dc-0" : "dc-1");
    rec.put("tags", tags);
    return rec;
  }

  /**
   * Build the canonical PUT-sentinel record used at the end of each PUT invocation.
   */
  public static GenericRecord buildPutSentinelRecord(Schema valueSchema, long seq) {
    GenericRecord rec = new GenericData.Record(valueSchema);
    rec.put("name", "sentinel-" + seq);
    rec.put("age", 0);
    rec.put("score", 0.0);
    rec.put("tags", Collections.<String, String>emptyMap());
    return rec;
  }

  /** Compute the bounded-pool key for the i-th invocation step of a PUT workload. */
  public static String putPoolKey(long seq) {
    return "put-pool-" + (seq % PUT_KEY_POOL_SIZE);
  }

  /** Compute the bounded-pool key for the i-th invocation step of a PARTIAL_UPDATE workload. */
  public static String partialUpdatePoolKey(long seq) {
    return "pu-pool-" + (seq % PARTIAL_UPDATE_KEY_POOL_SIZE);
  }

  /** Compute the pre-populate key for pool index {@code poolIdx} (0..PARTIAL_UPDATE_KEY_POOL_SIZE). */
  public static String partialUpdatePoolPrePopulateKey(int poolIdx) {
    return "pu-pool-" + poolIdx;
  }

  /** PUT-sentinel key: a unique key used to verify per-iteration drain. */
  public static String putSentinelKey(long seq) {
    return "put-sentinel-" + seq;
  }

  /** PARTIAL_UPDATE per-iteration sentinel key. */
  public static String partialUpdateSentinelKey(long seq) {
    return "pu-sentinel-" + seq;
  }

  /** PARTIAL_UPDATE end-of-trial sentinel keys (separate to avoid colliding with iteration sentinels). */
  public static String[] partialUpdateFinalSentinelKeys(long startSeq, int sentinelCount, int dcIndex) {
    String[] keys = new String[sentinelCount];
    String dcSuffix = dcIndex == 0 ? "dc0" : "dc1";
    for (int s = 0; s < sentinelCount; s++) {
      keys[s] = "pu-final-" + dcSuffix + "-" + (startSeq + s);
    }
    return keys;
  }

  /**
   * MIXED workload key generator: sequential keys (no pool collision).
   */
  public static String mixedKey(long baseKey, int i) {
    return "mixed-" + (baseKey + i);
  }

  /**
   * Indices to spot-check during PARTIAL_UPDATE pool pre-population — covers a spread of partitions
   * to ensure all partitions have drained before the benchmark starts measuring.
   */
  public static int[] partialUpdatePoolCheckIndices() {
    return new int[] { 0, 1234, 2345, 3456, 4567, 5678, 6789, 7890, PARTIAL_UPDATE_KEY_POOL_SIZE - 1 };
  }
}
