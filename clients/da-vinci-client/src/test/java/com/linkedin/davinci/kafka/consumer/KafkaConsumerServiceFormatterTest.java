package com.linkedin.davinci.kafka.consumer;

import com.linkedin.venice.pubsub.PubSubTopicPartitionImpl;
import com.linkedin.venice.pubsub.PubSubTopicRepository;
import com.linkedin.venice.pubsub.api.PubSubTopic;
import com.linkedin.venice.pubsub.api.PubSubTopicPartition;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;


/**
 * Unit tests for {@link KafkaConsumerService#convertTopicPartitionIngestionInfoMapToStr}.
 *
 * <p>The formatter renders a per-consumer ingestion-info map as a fixed-width table for logging.
 * These tests exercise edge cases (null/empty input, single row, mixed lag, long partition names,
 * unusual values) and verify that the output is sorted, aligned, and that the optional triggering
 * partition is marked exactly once on the right row.
 */
public class KafkaConsumerServiceFormatterTest {
  private final PubSubTopicRepository topicRepository = new PubSubTopicRepository();

  /** Builds a partition for the given store version and partition number. */
  private PubSubTopicPartition tp(String storeVersion, int partition) {
    PubSubTopic topic = topicRepository.getTopic(storeVersion);
    return new PubSubTopicPartitionImpl(topic, partition);
  }

  /**
   * Builds an ingestion info object with realistic shared-consumer fields and a generic
   * version-topic name. Use {@link #infoWithVersionTopic} when the version-topic name matters
   * (e.g. testing the hybrid-leader case where the partition is on `_rt` but consuming for `_vN`).
   */
  private TopicPartitionIngestionInfo info(long lag, long latestOffset, double msgRate, double byteRate, long lastRec) {
    return infoWithVersionTopic(lag, latestOffset, msgRate, byteRate, lastRec, "version-topic");
  }

  private TopicPartitionIngestionInfo infoWithVersionTopic(
      long lag,
      long latestOffset,
      double msgRate,
      double byteRate,
      long lastRec,
      String versionTopicName) {
    return new TopicPartitionIngestionInfo(
        latestOffset,
        lag,
        msgRate,
        byteRate,
        "shared-consumer-0",
        53694L, // elapsedTimeSinceLastConsumerPollInMs (consumer-level)
        lastRec,
        versionTopicName);
  }

  @Test
  public void nullMapReturnsEmptyString() {
    Assert.assertEquals(KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(null, null), "");
  }

  @Test
  public void emptyMapReturnsEmptyString() {
    Assert.assertEquals(
        KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(Collections.emptyMap(), null),
        "");
  }

  @Test
  public void headerCarriesConsumerLevelFields() {
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("store_v1", 0), info(0, 100, 0.0, 0.0, 1000));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    String firstLine = out.split("\n")[0];
    Assert.assertTrue(firstLine.contains("consumer=shared-consumer-0"), firstLine);
    Assert.assertTrue(firstLine.contains("lastPoll=53694ms"), firstLine);
    Assert.assertTrue(firstLine.contains("partitions=1"), firstLine);
  }

  @Test
  public void columnHeaderListsAllColumnsInOrder() {
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("store_v1", 0), info(0, 100, 0.0, 0.0, 1000));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    // Second line is the column header
    String header = out.split("\n")[1];
    int iPart = header.indexOf("partition");
    int iLag = header.indexOf("lag");
    int iMsg = header.indexOf("msgRate");
    int iByte = header.indexOf("byteRate");
    int iRec = header.indexOf("lastRecord(ms)");
    int iOff = header.indexOf("latestOffset");
    int iVt = header.indexOf("versionTopic");
    Assert.assertTrue(
        iPart >= 0 && iLag > iPart && iMsg > iLag && iByte > iMsg && iRec > iByte && iOff > iRec && iVt > iOff,
        "column header order is wrong; got: " + header);
  }

  @Test
  public void singleEntryProducesHeaderColumnHeaderAndOneRow() {
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("store_v1", 0), info(42, 100, 1.5, 256.0, 1000));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    String[] lines = out.split("\n");
    Assert.assertEquals(lines.length, 3, "expect 3 lines: header, column header, one row");
    Assert.assertTrue(lines[2].contains("store_v1-0"), lines[2]);
    Assert.assertTrue(lines[2].contains("42"), "row should contain lag value; got: " + lines[2]);
    Assert.assertTrue(lines[2].contains("1.50"), "msgRate must be formatted with 2 decimals; got: " + lines[2]);
    Assert.assertTrue(lines[2].contains("256.00"), "byteRate must be formatted with 2 decimals; got: " + lines[2]);
  }

  @Test
  public void rowsAreSortedByLagDescending() {
    // Use LinkedHashMap to insert in a deliberately wrong order so we can be sure the formatter
    // is doing the sort, not relying on insertion order.
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new LinkedHashMap<>();
    map.put(tp("a_v1", 0), info(0, 100, 0, 0, 100));
    map.put(tp("b_v1", 0), info(5_000_000, 200, 0, 0, 100));
    map.put(tp("c_v1", 0), info(50, 300, 0, 0, 100));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    String[] dataRows = dataRows(out);
    Assert.assertEquals(dataRows.length, 3);
    // Sorted by lag desc: 5_000_000 first, then 50, then 0.
    Assert.assertTrue(dataRows[0].contains("b_v1-0"), "highest-lag partition should come first; got: " + dataRows[0]);
    Assert.assertTrue(dataRows[1].contains("c_v1-0"), "mid-lag partition should come second; got: " + dataRows[1]);
    Assert.assertTrue(dataRows[2].contains("a_v1-0"), "zero-lag partition should come last; got: " + dataRows[2]);
  }

  @Test
  public void triggeringPartitionIsMarkedAndAnnotated() {
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("a_v1", 0), info(100, 0, 0, 0, 0));
    PubSubTopicPartition trigger = tp("b_v1", 0);
    map.put(trigger, info(0, 0, 0, 0, 0));
    map.put(tp("c_v1", 0), info(50, 0, 0, 0, 0));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, trigger);

    String[] dataRows = dataRows(out);
    int triggerRows = 0;
    int annotatedRows = 0;
    for (String row: dataRows) {
      if (row.startsWith("  * ")) {
        triggerRows++;
        Assert.assertTrue(row.contains("b_v1-0"), "marker must be on the trigger row; got: " + row);
        Assert.assertTrue(row.endsWith("(triggered)"), "trigger row must end with annotation; got: " + row);
      }
      if (row.contains("(triggered)")) {
        annotatedRows++;
      }
    }
    Assert.assertEquals(triggerRows, 1, "exactly one row should be marked; got " + triggerRows);
    Assert.assertEquals(annotatedRows, 1, "exactly one row should carry the annotation; got " + annotatedRows);
  }

  @Test
  public void nullTriggeringPartitionMarksNoRow() {
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("a_v1", 0), info(100, 0, 0, 0, 0));
    map.put(tp("b_v1", 0), info(0, 0, 0, 0, 0));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    Assert.assertFalse(out.contains("(triggered)"), "no trigger should produce no marker; got:\n" + out);
    Assert.assertFalse(out.contains("  * "), "no trigger should produce no asterisk marker; got:\n" + out);
    for (String row: dataRows(out)) {
      Assert
          .assertTrue(row.startsWith("    "), "every row must start with 4-space indent when no trigger; got: " + row);
    }
  }

  @Test
  public void triggerNotInMapMarksNoRow() {
    // Trigger is non-null but doesn't match any map key — exercises the equals fallback.
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("a_v1", 0), info(100, 0, 0, 0, 0));
    PubSubTopicPartition trigger = tp("not_in_map_v1", 99);
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, trigger);

    Assert.assertFalse(out.contains("(triggered)"), "trigger absent from map should not be marked; got:\n" + out);
  }

  @Test
  public void columnsAlignAcrossRowsRegardlessOfDataWidth() {
    // Mix short and very long partition names to force column-width adaptation.
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new LinkedHashMap<>();
    map.put(tp("a_v1", 0), info(1, 1, 0.0, 0.0, 1));
    map.put(
        tp("very_long_store_name_with_many_chars_v999", 12345),
        info(999_999_999L, 999_999_999L, 5.5, 7777.77, 9_999_999L));
    map.put(tp("m_v2", 7), info(1000, 50000, 0.5, 100.0, 5000));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    String[] lines = out.split("\n");
    String columnHeader = lines[1];
    String[] dataRows = dataRows(out);

    // Find column boundaries in the header. They are 2-space gaps between fields.
    // Use the position of "lag" (right-aligned) — every row must end its lag field at the same column.
    int lagEndInHeader = columnHeader.indexOf("lag") + "lag".length();
    int latestOffsetEndInHeader = columnHeader.indexOf("latestOffset") + "latestOffset".length();

    // For every data row, find the column boundaries by matching whitespace structure.
    // Since column widths in headers were padded to fit data, every row should have column endings
    // at the same horizontal positions as the header (or beyond, in the case of the longest data row).
    // Verify all rows have the same length up to latestOffsetEndInHeader (excluding trailing trigger annotation).
    int expectedFixedWidth = -1;
    for (String row: dataRows) {
      String trimmed = row.endsWith("(triggered)") ? row.substring(0, row.length() - "  (triggered)".length()) : row;
      if (expectedFixedWidth < 0) {
        expectedFixedWidth = trimmed.length();
      } else {
        Assert.assertEquals(
            trimmed.length(),
            expectedFixedWidth,
            "all data rows should have identical fixed width when alignment is correct;\n got: '" + trimmed
                + "'\n exp: " + expectedFixedWidth);
      }
    }
    // And the column header should align with the rows (same length as a non-trigger row).
    Assert.assertEquals(
        columnHeader.length(),
        expectedFixedWidth,
        "column header should be the same width as data rows;\n columnHeader: '" + columnHeader + "' (len="
            + columnHeader.length() + ")\n expected: " + expectedFixedWidth);

    // Sanity: make sure the column-header positions are within the data row range.
    Assert.assertTrue(lagEndInHeader > 0 && latestOffsetEndInHeader > lagEndInHeader);
  }

  @Test
  public void consumerLevelFieldsAreNotDuplicatedPerRow() {
    // The original toString() repeated `consumerIdStr:` and `elapsedTimeSinceLastConsumerPollInMs:`
    // on every row. With the new formatter those fields live in the header only. Verify each
    // appears exactly once.
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    for (int i = 0; i < 5; i++) {
      map.put(tp("store_v1", i), info(i, 100 + i, 0, 0, 1000));
    }
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    Assert.assertEquals(occurrences(out, "shared-consumer-0"), 1, "consumer id should appear exactly once in header");
    Assert.assertEquals(occurrences(out, "lastPoll="), 1, "lastPoll should appear exactly once in header");
    Assert.assertFalse(out.contains("consumerIdStr:"), "old key:value form should be gone");
    Assert.assertFalse(out.contains("elapsedTimeSinceLastConsumerPollInMs:"), "old key:value form should be gone");
    Assert.assertFalse(out.contains("versionTopicName:"), "versionTopicName key:value form should be gone");
  }

  @Test
  public void versionTopicColumnDistinguishesHybridLeaderFromBatch() {
    // Hybrid leader past EOP: partition is on the real-time topic but consumes into a versioned
    // topic. The partition name alone does not reveal which version is consuming, so the
    // versionTopic column must surface that mapping.
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new LinkedHashMap<>();
    map.put(tp("store1_rt", 0), infoWithVersionTopic(100, 0, 0, 0, 0, "store1_v3"));
    map.put(tp("store1_v3", 0), infoWithVersionTopic(0, 0, 0, 0, 0, "store1_v3"));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    String[] dataRows = dataRows(out);
    boolean rtRowHasVersionTopic = false;
    boolean batchRowHasVersionTopic = false;
    for (String row: dataRows) {
      if (row.contains("store1_rt-0")) {
        Assert.assertTrue(
            row.contains("store1_v3"),
            "real-time partition row must include the consuming versionTopic; got: " + row);
        rtRowHasVersionTopic = true;
      } else if (row.contains("store1_v3-0")) {
        Assert.assertTrue(row.contains("store1_v3"), "batch partition row must still print versionTopic; got: " + row);
        batchRowHasVersionTopic = true;
      }
    }
    Assert.assertTrue(rtRowHasVersionTopic && batchRowHasVersionTopic);
  }

  @Test
  public void emptyVersionTopicIsRenderedAsBlankNotNull() {
    // The producing path coerces a null destinationVersionTopic to "" (see
    // KafkaConsumerService.getIngestionInfoFromConsumer). Defensive: even if a null slips through
    // (e.g. via direct construction), the formatter must not throw or print "null".
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("store_v1", 0), infoWithVersionTopic(0, 1, 0, 0, 0, null));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);
    Assert.assertFalse(out.contains("null"), "null versionTopic must not leak into output; got:\n" + out);
  }

  @Test
  public void zeroAndNegativeRatesFormatCorrectly() {
    // Rates can legitimately be 0 (idle) or, in some pubsub adapters, return -1 to signal "unknown".
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("a_v1", 0), info(0, 0, 0.0, 0.0, 0));
    map.put(tp("b_v1", 0), info(0, 0, -1.0, -1.0, 0));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    Assert.assertTrue(out.contains("0.00"), "zero rate should format to two decimals; got:\n" + out);
    Assert.assertTrue(out.contains("-1.00"), "negative rate should format to two decimals; got:\n" + out);
  }

  @Test
  public void widthsAdaptToHeaderWhenDataIsSmall() {
    // All values are short; header titles ("latestOffset" = 12, "lastRecord(ms)" = 14,
    // "versionTopic" = 12) should dominate the column widths and the row should pad to fit them.
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new HashMap<>();
    map.put(tp("a_v1", 0), infoWithVersionTopic(0, 1, 0, 0, 0, "vt"));
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    String[] lines = out.split("\n");
    String header = lines[1];
    Assert.assertTrue(header.contains("latestOffset"));
    Assert.assertTrue(header.contains("lastRecord(ms)"));
    Assert.assertTrue(header.contains("versionTopic"));
    // Header and the single data row should be the same length (versionTopic is left-aligned and
    // padded). The data row is the last column-aligned column, so length parity is the alignment
    // invariant.
    String row = lines[2];
    Assert.assertEquals(
        row.length(),
        header.length(),
        "row and header should be same length; row='" + row + "' header='" + header + "'");
  }

  @Test
  public void realisticSharedConsumerScenarioFromIncident() {
    // Reconstruct the dump from the user's reported log: 16 partitions across 3 stores.
    // Verify (a) the output is much smaller than the original blob, (b) it's sorted, (c) the
    // worst lag clusters together at the top, (d) the trigger marker lands on the right row,
    // (e) the versionTopic column reflects each partition's destination version.
    String ng = "cert-basic-dataset-northguard_v3";
    String aa = "aa-partial-update-benchmark-medium_v2";
    String v89 = "cert-basic-dataset_v89";
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new LinkedHashMap<>();
    map.put(tp(ng, 7), infoWithVersionTopic(3971738, 4000961, 0, 0, 385294, ng));
    map.put(tp(aa, 84), infoWithVersionTopic(0, 5346109, 0, 0, 385301, aa));
    PubSubTopicPartition trigger = tp(v89, 40);
    map.put(trigger, infoWithVersionTopic(0, 8232319, 0, 0, 1226927, v89));
    map.put(tp(v89, 10), infoWithVersionTopic(0, 8235941, 0, 0, 268715, v89));
    map.put(tp(aa, 44), infoWithVersionTopic(128, 5351890, 0, 0, 969889, aa));
    map.put(tp(aa, 45), infoWithVersionTopic(168, 5391638, 0, 0, 385309, aa));
    map.put(tp(ng, 32), infoWithVersionTopic(3979827, 4005186, 0, 0, 1120585, ng));
    map.put(tp(ng, 0), infoWithVersionTopic(3970739, 3997774, 0, 0, 1226920, ng));
    map.put(tp(v89, 82), infoWithVersionTopic(44, 8243155, 0, 0, 969896, v89));
    map.put(tp(v89, 21), infoWithVersionTopic(0, 8227759, 0, 0, 385313, v89));
    map.put(tp(ng, 25), infoWithVersionTopic(3972689, 4002268, 0, 0, 268715, ng));
    map.put(tp(v89, 90), infoWithVersionTopic(0, 8220505, 0, 0, 1120579, v89));
    map.put(tp(ng, 58), infoWithVersionTopic(3971168, 3999572, 0, 0, 936733, ng));
    map.put(tp(ng, 48), infoWithVersionTopic(3971503, 3999731, 0, 0, 1527710, ng));
    map.put(tp(aa, 33), infoWithVersionTopic(353, 5361438, 5.14, 735.75, 53694, aa));
    map.put(tp(ng, 52), infoWithVersionTopic(3968092, 3997262, 0, 0, 576128, ng));

    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, trigger);

    String[] dataRows = dataRows(out);
    Assert.assertEquals(dataRows.length, 16, "expected 16 data rows");

    // First seven rows are all the high-lag northguard partitions (lag ~3.97M).
    for (int i = 0; i < 7; i++) {
      Assert.assertTrue(
          dataRows[i].contains("cert-basic-dataset-northguard_v3-"),
          "top 7 rows should all be northguard partitions; row " + i + ": " + dataRows[i]);
    }
    // Triggering partition is somewhere in the bottom (lag=0) cluster, marked.
    boolean foundTriggeredRow = false;
    for (String row: dataRows) {
      if (row.contains("(triggered)")) {
        Assert.assertTrue(row.startsWith("  * "), "trigger row should start with '  * '; got: " + row);
        Assert.assertTrue(row.contains("cert-basic-dataset_v89-40"), "wrong trigger row; got: " + row);
        foundTriggeredRow = true;
      }
    }
    Assert.assertTrue(foundTriggeredRow, "trigger row not found in:\n" + out);

    // Sanity: 5.14 msgRate row's byteRate must be formatted to 2 decimals.
    Assert.assertTrue(out.contains("735.75"), "byteRate 735.749... must round to 735.75; got:\n" + out);
    Assert.assertTrue(out.contains("5.14"), "msgRate 5.139... must format to 5.14; got:\n" + out);

    // versionTopic column must appear for each store at least once. (Per-row assertion is tedious,
    // and the test above already exercises versionTopic-per-row separately.)
    Assert.assertTrue(out.contains(ng), "versionTopic " + ng + " must appear; got:\n" + out);
    Assert.assertTrue(out.contains(aa), "versionTopic " + aa + " must appear; got:\n" + out);
    Assert.assertTrue(out.contains(v89), "versionTopic " + v89 + " must appear; got:\n" + out);
  }

  @Test
  public void onlyTriggerRowGetsMarkerEvenIfTopicNamesPartiallyOverlap() {
    // Defensive: a partition with the same partition number on a different topic must not
    // be mistaken for the trigger.
    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new LinkedHashMap<>();
    map.put(tp("alpha_v1", 5), info(100, 0, 0, 0, 0));
    map.put(tp("beta_v1", 5), info(50, 0, 0, 0, 0));
    PubSubTopicPartition trigger = tp("alpha_v1", 5);
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, trigger);

    Assert.assertEquals(occurrences(out, "(triggered)"), 1);
    String[] rows = dataRows(out);
    for (String row: rows) {
      if (row.contains("alpha_v1-5")) {
        Assert.assertTrue(row.contains("(triggered)"), "alpha row should be marked; got: " + row);
      }
      if (row.contains("beta_v1-5")) {
        Assert.assertFalse(row.contains("(triggered)"), "beta row must not be marked; got: " + row);
      }
    }
  }

  @Test
  public void firstRowsConsumerFieldsAreUsedInHeader() {
    // The formatter pulls consumer-level fields from rows.get(0) (worst-lag row after sort).
    // If two entries have inconsistent consumer-level data (which shouldn't happen in practice
    // because they're per-consumer), the first one wins. Document this behavior.
    TopicPartitionIngestionInfo highLag =
        new TopicPartitionIngestionInfo(100, 999, 0, 0, "consumer-A", 12345L, 0, "version-topic");
    TopicPartitionIngestionInfo lowLag =
        new TopicPartitionIngestionInfo(100, 1, 0, 0, "consumer-B", 67890L, 0, "version-topic");

    Map<PubSubTopicPartition, TopicPartitionIngestionInfo> map = new LinkedHashMap<>();
    map.put(tp("a_v1", 0), lowLag); // inserted first, but lower lag → won't be at row 0 after sort
    map.put(tp("b_v1", 0), highLag); // higher lag → ends up at row 0
    String out = KafkaConsumerService.convertTopicPartitionIngestionInfoMapToStr(map, null);

    String firstLine = out.split("\n")[0];
    Assert.assertTrue(
        firstLine.contains("consumer-A"),
        "header should reflect post-sort row 0 consumer; got: " + firstLine);
    Assert.assertTrue(firstLine.contains("12345"), "header lastPoll should reflect post-sort row 0; got: " + firstLine);
  }

  // -------- helpers --------

  /** Returns only the data rows (skipping header line and column-header line). */
  private static String[] dataRows(String out) {
    String[] all = out.split("\n");
    if (all.length < 3) {
      return new String[0];
    }
    String[] data = new String[all.length - 2];
    System.arraycopy(all, 2, data, 0, data.length);
    return data;
  }

  private static int occurrences(String haystack, String needle) {
    int count = 0;
    int idx = 0;
    while ((idx = haystack.indexOf(needle, idx)) >= 0) {
      count++;
      idx += needle.length();
    }
    return count;
  }
}
