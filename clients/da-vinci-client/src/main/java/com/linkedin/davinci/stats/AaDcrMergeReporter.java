package com.linkedin.davinci.stats;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;


/**
 * Phase 4 — sub-stage decomposition of the {@code dcr_merge} stage of the
 * Active-Active leader PUT hot loop. Mirrors {@link AaLeaderBottleneckReporter}
 * but is scoped to sub-stages of {@link com.linkedin.davinci.replication.merge.MergeConflictResolver#put}.
 *
 * <p>Per-sub-stage counters (count + total_ns + max_ns) are recorded by
 * {@link #record(SubStage, long)} and emitted every {@link #REPORT_INTERVAL_SECONDS}
 * seconds to stderr as lines of the form:
 *
 * <pre>[DCR-MERGE] sub_stage=&lt;name&gt; calls=&lt;N&gt; total_ns=&lt;T&gt; avg_ns=&lt;T/N&gt; max_ns=&lt;M&gt; pct_of_dcr_merge=&lt;P&gt;</pre>
 *
 * plus a single summary line per tick:
 *
 * <pre>[DCR-MERGE-SUMMARY] tick=&lt;n&gt; dcr_merge_total_ns=&lt;X&gt; coverage_pct=&lt;C&gt; top3=&lt;s1:P1,s2:P2,s3:P3&gt;</pre>
 *
 * <p>The entire mechanism is gated by the system property
 * {@code venice.server.aa.dcr.merge.instrumentation.enabled}. When the flag is
 * unset or {@code false}, {@link #ENABLED} is {@code false} and callers must
 * early-out so the production hot path is untouched.</p>
 *
 * <p>This reporter is independent from {@link AaLeaderBottleneckReporter} so the
 * two flags can be toggled separately. Both flags will be ON for the Phase 4
 * measurement runs; the criterion-6 OFF run keeps the bottleneck flag ON but
 * sets this flag to false to measure the per-call overhead of the new sub-stage
 * timers.</p>
 */
public final class AaDcrMergeReporter {
  private static final long REPORT_INTERVAL_SECONDS = 20L;

  /**
   * Master enable flag. Set at class-load time from the system property
   * {@code venice.server.aa.dcr.merge.instrumentation.enabled}. When
   * {@code false}, all {@link #record(SubStage, long)} calls should be skipped
   * by the caller before any work is done.
   */
  public static final boolean ENABLED =
      Boolean.getBoolean("venice.server.aa.dcr.merge.instrumentation.enabled");

  /**
   * Sub-stages of {@link com.linkedin.davinci.replication.merge.MergeConflictResolver#put}
   * (PUT-only path, field-level timestamp variant). The order here is also the
   * reporting order. The ordering follows the natural flow of
   * {@code mergePutWithFieldLevelTimestamp}.
   */
  public enum SubStage {
    /**
     * The full {@code MergeConflictResolver.put} invocation wall, recorded once
     * per call. {@code pct_of_dcr_merge} for every other sub-stage is computed
     * relative to this stage's {@code total_ns} for the same tick.
     */
    OUTER("outer_merge_put"),
    /** {@code ignoreNewPut} early-exit timestamp-compare check. */
    IGNORE_NEW_PUT_CHECK("ignore_new_put_check"),
    /** {@code MergeResultValueSchemaResolver.getMergeResultValueSchema(oldId,newId)} resolution. */
    SCHEMA_RESOLVE("schema_resolve"),
    /** Avro decode of the incoming PUT value bytes via {@code deserializerCacheForFullValue}. */
    NEW_VALUE_DESERIALIZE("new_value_deserialize"),
    /** Total wrapper around old-value lookup+decode (= OLD_VALUE_FETCH + OLD_VALUE_DECODE). */
    OLD_VALUE_DESERIALIZE("old_value_deserialize"),
    /** [Phase 4.5] Storage fetch only: {@code oldValueBytesProvider.get()} — transient cache or RocksDB. */
    OLD_VALUE_FETCH("old_value_fetch"),
    /** [Phase 4.5] Avro decode only: {@code createValueRecordFromByteBuffer} on the fetched bytes. */
    OLD_VALUE_DECODE("old_value_decode"),
    /**
     * [Phase 4.6] Inside getValueBytesForKey: transient record map lookup
     * ({@code partitionConsumptionState.getTransientRecord(key)}). Always called.
     */
    VALUE_FETCH_TRANSIENT_LOOKUP("value_fetch_transient_lookup"),
    /**
     * [Phase 4.6] Inside getValueBytesForKey: TRANSIENT-HIT branch — reconstruct
     * ByteBufferValueRecord from the transient entry, possibly decompress.
     * Only counted when the transient lookup returned non-null.
     */
    VALUE_FETCH_TRANSIENT_HIT_PATH("value_fetch_transient_hit_path"),
    /**
     * [Phase 4.6] Inside getValueBytesForKey: TRANSIENT-MISS branch — full RocksDB
     * lookup via {@code databaseLookupWithConcurrencyLimit} + chunking adapter.
     * Only counted when the transient lookup returned null.
     */
    VALUE_FETCH_ROCKSDB_PATH("value_fetch_rocksdb_path"),
    /**
     * RMD prep: {@code convertToPerFieldTimestampRmd} + {@code convertRmdToUseReaderValueSchema}
     * + {@code ValueAndRmd<>} wrapper allocation inside {@code createOldValueAndRmd}.
     */
    RMD_PREPARE("rmd_prepare"),
    /**
     * The actual per-field merge: {@code MergeGenericRecord.put} dispatching to
     * {@code handlePutWithPerFieldLevelTimestamp} which iterates fields and
     * delegates to {@code MergeRecordHelper.putOnField} (incl.
     * {@code CollectionTimestampMergeRecordHelper.handlePutMap} for map fields).
     */
    MERGE_PUT_APPLY("merge_put_apply"),
    /** Avro encode of the merged value record back to bytes for VT produce. */
    MERGED_VALUE_SERIALIZE("merged_value_serialize"),
    /** {@code new MergeConflictResult(...)} return-value allocation. */
    MERGE_RESULT_ALLOC("merge_result_alloc");

    private final String label;

    SubStage(String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  private static final SubStage[] SUB_STAGES = SubStage.values();
  private static final int N_SUB_STAGES = SUB_STAGES.length;

  // One count + one nanos adder per sub-stage.
  private static final LongAdder[] STAGE_COUNT;
  private static final LongAdder[] STAGE_NANOS;
  // Per-sub-stage max nanos observed during the current report interval, reset on each tick.
  private static final AtomicLong[] STAGE_MAX_NANOS;
  private static final long[] LAST_COUNT;
  private static final long[] LAST_NANOS;

  private static final AtomicBoolean STARTED = new AtomicBoolean();

  // Tick counter used for the SUMMARY line.
  private static long tickNumber = 0L;

  static {
    STAGE_COUNT = new LongAdder[N_SUB_STAGES];
    STAGE_NANOS = new LongAdder[N_SUB_STAGES];
    STAGE_MAX_NANOS = new AtomicLong[N_SUB_STAGES];
    LAST_COUNT = new long[N_SUB_STAGES];
    LAST_NANOS = new long[N_SUB_STAGES];
    for (int i = 0; i < N_SUB_STAGES; i++) {
      STAGE_COUNT[i] = new LongAdder();
      STAGE_NANOS[i] = new LongAdder();
      STAGE_MAX_NANOS[i] = new AtomicLong(0L);
    }
    if (ENABLED) {
      startReporter();
    }
  }

  private AaDcrMergeReporter() {
  }

  /**
   * Record a single invocation of {@code subStage} that took {@code nanos}.
   * Callers SHOULD guard this with {@link #ENABLED} to skip the call + its
   * argument evaluation on the fast path.
   */
  public static void record(SubStage subStage, long nanos) {
    if (!ENABLED) {
      return;
    }
    int idx = subStage.ordinal();
    STAGE_COUNT[idx].increment();
    STAGE_NANOS[idx].add(nanos);
    AtomicLong maxRef = STAGE_MAX_NANOS[idx];
    long prev = maxRef.get();
    while (nanos > prev && !maxRef.compareAndSet(prev, nanos)) {
      prev = maxRef.get();
    }
  }

  private static void startReporter() {
    if (STARTED.compareAndSet(false, true)) {
      ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AaDcrMergeReporter");
        t.setDaemon(true);
        return t;
      });
      scheduler.scheduleAtFixedRate(
          AaDcrMergeReporter::reportOnce,
          REPORT_INTERVAL_SECONDS,
          REPORT_INTERVAL_SECONDS,
          TimeUnit.SECONDS);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          reportOnce();
        } catch (Throwable ignored) {
        }
      }, "AaDcrMergeReporterShutdown"));
    }
  }

  private static synchronized void reportOnce() {
    // Snapshot all sub-stages first.
    long[] deltaCount = new long[N_SUB_STAGES];
    long[] deltaNanos = new long[N_SUB_STAGES];
    long[] maxNanos = new long[N_SUB_STAGES];
    for (int i = 0; i < N_SUB_STAGES; i++) {
      long c = STAGE_COUNT[i].sum();
      long n = STAGE_NANOS[i].sum();
      deltaCount[i] = c - LAST_COUNT[i];
      deltaNanos[i] = n - LAST_NANOS[i];
      LAST_COUNT[i] = c;
      LAST_NANOS[i] = n;
      // getAndSet resets the per-sub-stage max for the next interval.
      maxNanos[i] = STAGE_MAX_NANOS[i].getAndSet(0L);
    }

    long outerNs = deltaNanos[SubStage.OUTER.ordinal()];
    long outerCalls = deltaCount[SubStage.OUTER.ordinal()];
    if (outerNs <= 0L && outerCalls <= 0L) {
      // Nothing meaningful happened this tick — skip quiet ticks to keep log clean.
      return;
    }
    tickNumber++;

    // Per-sub-stage lines.
    StringBuilder buf = new StringBuilder();
    long namedAccountedNs = 0L;
    for (int i = 0; i < N_SUB_STAGES; i++) {
      long cnt = deltaCount[i];
      long nanos = deltaNanos[i];
      double avg = cnt > 0 ? (double) nanos / (double) cnt : 0.0;
      double pct = outerNs > 0 ? 100.0 * (double) nanos / (double) outerNs : 0.0;
      buf.setLength(0);
      buf.append("[DCR-MERGE] sub_stage=").append(SUB_STAGES[i].label())
          .append(" calls=").append(cnt)
          .append(" total_ns=").append(nanos)
          .append(" avg_ns=").append(String.format("%.1f", avg))
          .append(" max_ns=").append(maxNanos[i])
          .append(" pct_of_dcr_merge=").append(String.format("%.2f", pct));
      System.err.println(buf.toString());
      if (SUB_STAGES[i] != SubStage.OUTER) {
        namedAccountedNs += nanos;
      }
    }

    // OTHER bucket: outer minus the sum of named sub-stages. Negative means
    // the sub-stage timers overcounted (overlap) — should be 0 by design.
    long otherNs = Math.max(0L, outerNs - namedAccountedNs);
    double otherPct = outerNs > 0 ? 100.0 * (double) otherNs / (double) outerNs : 0.0;
    System.err.println(
        String.format(
            "[DCR-MERGE] sub_stage=other_merge calls=0 total_ns=%d avg_ns=0.0 max_ns=0 pct_of_dcr_merge=%.2f",
            otherNs,
            otherPct));

    // Coverage = sum-of-named-sub-stages-pct = 100 - other_pct.
    double coveragePct = 100.0 - otherPct;

    // Summary line: top-3 named sub-stages by pct_of_dcr_merge.
    int a = -1, b = -1, c = -1;
    long aN = -1L, bN = -1L, cN = -1L;
    for (int i = 0; i < N_SUB_STAGES; i++) {
      if (SUB_STAGES[i] == SubStage.OUTER) {
        continue;
      }
      long n = deltaNanos[i];
      if (n > aN) {
        c = b;
        cN = bN;
        b = a;
        bN = aN;
        a = i;
        aN = n;
      } else if (n > bN) {
        c = b;
        cN = bN;
        b = i;
        bN = n;
      } else if (n > cN) {
        c = i;
        cN = n;
      }
    }
    String top3 = formatTop(a, aN, outerNs) + "," + formatTop(b, bN, outerNs) + "," + formatTop(c, cN, outerNs);
    System.err.println(
        String.format(
            "[DCR-MERGE-SUMMARY] tick=%d dcr_merge_total_ns=%d outer_calls=%d coverage_pct=%.2f top3=%s",
            tickNumber,
            outerNs,
            outerCalls,
            coveragePct,
            top3));
  }

  private static String formatTop(int idx, long nanos, long outerNs) {
    if (idx < 0) {
      return "none:0.00";
    }
    double pct = outerNs > 0 ? 100.0 * (double) nanos / (double) outerNs : 0.0;
    return SUB_STAGES[idx].label() + ":" + String.format("%.2f", pct);
  }
}
