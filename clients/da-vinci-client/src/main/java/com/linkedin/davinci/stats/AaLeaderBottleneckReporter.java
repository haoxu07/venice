package com.linkedin.davinci.stats;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;


/**
 * Process-global stderr diagnostic for the Active-Active leader PUT hot loop.
 * When enabled, per-stage counters (count + total nanoseconds) are recorded by
 * {@link #record(Stage, long)} and emitted every {@link #REPORT_INTERVAL_SECONDS}
 * seconds to stderr as lines:
 *
 * <pre>[BOTTLENECK] stage=&lt;name&gt; calls=&lt;N&gt; total_ns=&lt;T&gt; avg_ns=&lt;T/N&gt; pct_of_wall=&lt;P&gt;</pre>
 *
 * plus a single summary line per tick:
 *
 * <pre>[BOTTLENECK-SUMMARY] tick=&lt;n&gt; total_records=&lt;R&gt; wall_ns=&lt;W&gt; top3=&lt;s1:P1,s2:P2,s3:P3&gt;</pre>
 *
 * <p>The entire mechanism is gated by the system property
 * {@code venice.server.aa.bottleneck.instrumentation.enabled}. When the flag is
 * unset or {@code false}, {@link #ENABLED} is {@code false} and callers are
 * expected to early-out. Counters themselves are lazily populated, so the cost
 * of the flag-off path (one boolean check at each call site) is negligible.</p>
 *
 * <p>Design mirrors {@link TransientRecordCacheDiagnosticReporter}: a single
 * daemon scheduler, cumulative {@code LongAdder}s, and interval-delta reporting.</p>
 */
public final class AaLeaderBottleneckReporter {
  private static final long REPORT_INTERVAL_SECONDS = 20L;

  /**
   * Master enable flag. Set at class-load time from the system property
   * {@code venice.server.aa.bottleneck.instrumentation.enabled}. When
   * {@code false}, all {@link #record(Stage, long)} calls should be skipped
   * by the caller before any work is done.
   */
  public static final boolean ENABLED =
      Boolean.getBoolean("venice.server.aa.bottleneck.instrumentation.enabled");

  /**
   * Stages of the AA leader PUT hot loop instrumented by this reporter.
   * The order here is also the reporting order. Keep in sync with the
   * AA bottleneck study prompt.
   */
  public enum Stage {
    LEADER_RECORD_WALL("leader_record_wall_ns", false),
    // rt_poll_wait runs on the shared consumer thread, NOT inside the per-record
    // wall on the parallel batch pool thread, so its pct-of-wall is informational
    // only (often >100% per record because one poll covers many records).
    RT_POLL_WAIT("rt_poll_wait", true),
    RT_DESERIALIZE("rt_deserialize", false),
    RMD_LOOKUP_TOTAL("rmd_lookup_total", false),
    RMD_LOOKUP_TRANSIENT("rmd_lookup_transient", false),
    RMD_LOOKUP_ROCKSDB("rmd_lookup_rocksdb", false),
    RMD_DESERIALIZE("rmd_deserialize", false),
    DCR_MERGE("dcr_merge", false),
    // aa_wc_pool_handoff measures pool queuing latency from CompletableFuture.runAsync
    // submission to lambda start. It is NOT inside per-record wall (it's submitter-side
    // blocking time), so we mark it off-wall.
    AA_WC_POOL_HANDOFF("aa_wc_pool_handoff", true),
    // key_lock_wait is per-batch (not per-record), so reported as off-wall and
    // interpreted as "batch lock-acquire wall / batch_size" if needed.
    KEY_LOCK_WAIT("key_lock_wait", true),
    VALUE_SERIALIZE("value_serialize", false),
    VALUE_CHUNK("value_chunk", false),
    // vt_produce_send runs on the leader consumer thread (post-batch), NOT inside
    // processActiveActiveMessage on the pool thread, so it is also off-wall relative
    // to the per-record wall denominator.
    VT_PRODUCE_SEND("vt_produce_send", true),
    VT_PRODUCE_ACK_WAIT("vt_produce_ack_wait", true), // producer IO callback thread
    TRANSIENT_MAP_PUT("transient_map_put", false),
    DRAINER_ENQUEUE("drainer_enqueue", true), // producer callback thread
    ROCKSDB_VALUE_WRITE("rocksdb_value_write", true), // drainer thread
    TRANSIENT_MAP_REMOVE("transient_map_remove", true), // drainer thread
    // Time the leader pool worker thread spent IDLE between consecutive per-record
    // processings. If LEADER_IDLE is non-zero in steady state, the leader thread
    // has slack and the throughput-limiting bottleneck is downstream (producer ack,
    // drainer, or RT consumer feed). Off-leader-wall by definition.
    LEADER_IDLE("leader_idle", true),
    // ---- Phase 2 stages (poll decomposition + dispatcher submit count) ----
    // Wall time of consumer.poll(timeout). Per-call is the total ns spent
    // inside the poll across all topic partitions. Off-leader-wall by definition.
    RT_POLL_BLOCK_NS("rt_poll_block_ns", true),
    // Sum of records returned per poll across the tick. Encoded count-only:
    // each record counted once, nanos always 0. Read as `calls` in the report.
    RT_POLL_RECORDS_RETURNED("rt_poll_records_returned", true),
    // Polls that returned 0 records. Count-only.
    RT_POLL_EMPTY_COUNT("rt_poll_empty_count", true),
    // Polls that returned >= 0.9 * SERVER_KAFKA_MAX_POLL_RECORDS (default 100,
    // so threshold = 90). Count-only.
    RT_POLL_FULL_COUNT("rt_poll_full_count", true),
    // Tasks submitted to the AA/WC parallel processing pool by the consumer.
    // Per-tick rate = aa_pool_submit_count.calls / 20s. Count-only.
    AA_POOL_SUBMIT_COUNT("aa_pool_submit_count", true);

    private final String label;
    /**
     * {@code true} if this stage is not part of the leader consumer thread's
     * per-record wall. Used purely for reporting — such stages are excluded
     * from the "pct of leader wall" sum but still reported.
     */
    private final boolean offLeaderWall;

    Stage(String label, boolean offLeaderWall) {
      this.label = label;
      this.offLeaderWall = offLeaderWall;
    }

    public String label() {
      return label;
    }

    public boolean offLeaderWall() {
      return offLeaderWall;
    }
  }

  private static final Stage[] STAGES = Stage.values();
  private static final int N_STAGES = STAGES.length;

  // One count + one nanos adder per stage.
  private static final LongAdder[] STAGE_COUNT;
  private static final LongAdder[] STAGE_NANOS;
  // Per-stage max nanos observed during the current report interval, reset on each
  // tick. Used to distinguish "fast on average, occasionally slow" from "uniformly
  // slow", which matters for diagnosing producer-queue-full backpressure.
  private static final AtomicLong[] STAGE_MAX_NANOS;
  private static final long[] LAST_COUNT;
  private static final long[] LAST_NANOS;

  // Per-thread last leader-record exit timestamp, used to compute LEADER_IDLE gaps
  // between consecutive records processed on the same pool thread.
  private static final ThreadLocal<Long> LAST_LEADER_EXIT_NS = new ThreadLocal<>();

  private static final LongAdder RECORD_COUNT = new LongAdder();
  private static long lastRecordCount = 0L;

  private static final AtomicBoolean STARTED = new AtomicBoolean();

  // Tick counter used for the SUMMARY line.
  private static long tickNumber = 0L;

  static {
    STAGE_COUNT = new LongAdder[N_STAGES];
    STAGE_NANOS = new LongAdder[N_STAGES];
    STAGE_MAX_NANOS = new AtomicLong[N_STAGES];
    LAST_COUNT = new long[N_STAGES];
    LAST_NANOS = new long[N_STAGES];
    for (int i = 0; i < N_STAGES; i++) {
      STAGE_COUNT[i] = new LongAdder();
      STAGE_NANOS[i] = new LongAdder();
      STAGE_MAX_NANOS[i] = new AtomicLong(0L);
    }
    if (ENABLED) {
      startReporter();
    }
  }

  private AaLeaderBottleneckReporter() {
  }

  /**
   * Record a single invocation of {@code stage} that took {@code nanos}. Callers
   * SHOULD guard this with {@link #ENABLED} to skip the call + its argument
   * evaluation on the fast path.
   */
  public static void record(Stage stage, long nanos) {
    if (!ENABLED) {
      return;
    }
    int idx = stage.ordinal();
    STAGE_COUNT[idx].increment();
    STAGE_NANOS[idx].add(nanos);
    AtomicLong maxRef = STAGE_MAX_NANOS[idx];
    long prev = maxRef.get();
    while (nanos > prev && !maxRef.compareAndSet(prev, nanos)) {
      prev = maxRef.get();
    }
  }

  /**
   * Record a count-only event for {@code stage}. Use for stages where the
   * "nanos" dimension is not meaningful (e.g. record-counts returned per
   * poll, or pool-submit counts). Equivalent to {@link #record(Stage, long)}
   * with {@code nanos = 0}.
   *
   * <p>Caller SHOULD guard with {@link #ENABLED} on the fast path.</p>
   */
  public static void recordCount(Stage stage) {
    if (!ENABLED) {
      return;
    }
    STAGE_COUNT[stage.ordinal()].increment();
  }

  /**
   * Add {@code n} to the count of {@code stage} (no nanos). Useful for
   * batched count emissions like "records returned per poll = N".
   */
  public static void recordCount(Stage stage, long n) {
    if (!ENABLED || n <= 0L) {
      return;
    }
    STAGE_COUNT[stage.ordinal()].add(n);
  }

  /**
   * Mark the start of a per-record leader processing on the current thread.
   * If the thread has previously processed a record, the gap since the prior
   * exit is recorded as {@link Stage#LEADER_IDLE}. Call before starting the
   * {@link Stage#LEADER_RECORD_WALL} timer.
   */
  public static void leaderRecordEntry() {
    if (!ENABLED) {
      return;
    }
    Long lastExit = LAST_LEADER_EXIT_NS.get();
    long now = System.nanoTime();
    if (lastExit != null) {
      long idle = now - lastExit;
      if (idle > 0L) {
        record(Stage.LEADER_IDLE, idle);
      }
    }
  }

  /**
   * Mark the end of a per-record leader processing on the current thread, so
   * the next entry's gap is captured as {@link Stage#LEADER_IDLE}. Call after
   * stopping the {@link Stage#LEADER_RECORD_WALL} timer.
   */
  public static void leaderRecordExit() {
    if (!ENABLED) {
      return;
    }
    LAST_LEADER_EXIT_NS.set(System.nanoTime());
  }

  /**
   * Count one processed leader record. Distinct from {@code LEADER_RECORD_WALL}
   * because the per-record wall can be recorded only at the successful exit
   * path whereas the record counter is incremented at entry.
   */
  public static void countRecord() {
    if (!ENABLED) {
      return;
    }
    RECORD_COUNT.increment();
  }

  private static void startReporter() {
    if (STARTED.compareAndSet(false, true)) {
      ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AaLeaderBottleneckReporter");
        t.setDaemon(true);
        return t;
      });
      scheduler.scheduleAtFixedRate(
          AaLeaderBottleneckReporter::reportOnce,
          REPORT_INTERVAL_SECONDS,
          REPORT_INTERVAL_SECONDS,
          TimeUnit.SECONDS);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          reportOnce();
        } catch (Throwable ignored) {
        }
      }, "AaLeaderBottleneckReporterShutdown"));
    }
  }

  private static synchronized void reportOnce() {
    long recordTotal = RECORD_COUNT.sum();
    long deltaRecords = recordTotal - lastRecordCount;
    lastRecordCount = recordTotal;

    // Snapshot all stages first.
    long[] deltaCount = new long[N_STAGES];
    long[] deltaNanos = new long[N_STAGES];
    long[] maxNanos = new long[N_STAGES];
    for (int i = 0; i < N_STAGES; i++) {
      long c = STAGE_COUNT[i].sum();
      long n = STAGE_NANOS[i].sum();
      deltaCount[i] = c - LAST_COUNT[i];
      deltaNanos[i] = n - LAST_NANOS[i];
      LAST_COUNT[i] = c;
      LAST_NANOS[i] = n;
      // getAndSet resets the per-stage max for the next interval.
      maxNanos[i] = STAGE_MAX_NANOS[i].getAndSet(0L);
    }

    long wallNs = deltaNanos[Stage.LEADER_RECORD_WALL.ordinal()];
    if (wallNs <= 0L && deltaRecords <= 0L) {
      // Nothing meaningful happened this tick — skip quiet ticks to keep log clean.
      return;
    }
    tickNumber++;

    // Per-stage lines.
    StringBuilder buf = new StringBuilder();
    for (int i = 0; i < N_STAGES; i++) {
      long cnt = deltaCount[i];
      long nanos = deltaNanos[i];
      double avg = cnt > 0 ? (double) nanos / (double) cnt : 0.0;
      double pct = wallNs > 0 ? 100.0 * (double) nanos / (double) wallNs : 0.0;
      buf.setLength(0);
      buf.append("[BOTTLENECK] stage=").append(STAGES[i].label())
          .append(" calls=").append(cnt)
          .append(" total_ns=").append(nanos)
          .append(" avg_ns=").append(String.format("%.1f", avg))
          .append(" max_ns=").append(maxNanos[i])
          .append(" pct_of_wall=").append(String.format("%.2f", pct));
      if (STAGES[i].offLeaderWall()) {
        buf.append(" off_leader_wall=true");
      }
      System.err.println(buf.toString());
    }

    // OTHER bucket — leader wall not accounted for by named leader-thread stages.
    long accounted = 0L;
    for (int i = 0; i < N_STAGES; i++) {
      if (STAGES[i].offLeaderWall() || STAGES[i] == Stage.LEADER_RECORD_WALL) {
        continue;
      }
      accounted += deltaNanos[i];
    }
    long other = Math.max(0L, wallNs - accounted);
    double otherPct = wallNs > 0 ? 100.0 * (double) other / (double) wallNs : 0.0;
    System.err.println(
        String.format(
            "[BOTTLENECK] stage=OTHER calls=0 total_ns=%d avg_ns=0.0 pct_of_wall=%.2f",
            other,
            otherPct));

    // Summary line: top-3 leader-wall stages by pct_of_wall.
    int a = -1, b = -1, c = -1;
    long aN = -1, bN = -1, cN = -1;
    for (int i = 0; i < N_STAGES; i++) {
      if (STAGES[i].offLeaderWall() || STAGES[i] == Stage.LEADER_RECORD_WALL) {
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
    String top3 = formatTop(a, aN, wallNs) + "," + formatTop(b, bN, wallNs) + "," + formatTop(c, cN, wallNs);
    System.err.println(
        String.format(
            "[BOTTLENECK-SUMMARY] tick=%d total_records=%d wall_ns=%d top3=%s",
            tickNumber,
            deltaRecords,
            wallNs,
            top3));
  }

  private static String formatTop(int idx, long nanos, long wallNs) {
    if (idx < 0) {
      return "none:0.00";
    }
    double pct = wallNs > 0 ? 100.0 * (double) nanos / (double) wallNs : 0.0;
    return STAGES[idx].label() + ":" + String.format("%.2f", pct);
  }
}
