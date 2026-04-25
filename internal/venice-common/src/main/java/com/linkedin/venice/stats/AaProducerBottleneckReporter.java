package com.linkedin.venice.stats;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;


/**
 * Process-global stderr diagnostic for the producer-side (benchmark thread)
 * portion of the AA ingestion bottleneck Phase 2 study.
 *
 * <p>Lives in venice-common so it can be wired both from
 * {@code com.linkedin.venice.samza.VeniceSystemProducer} (the entry point used
 * by the canonical JMH benchmark) and from the server-side ingestion code
 * without introducing new module dependencies.</p>
 *
 * <p>Gated by the system property
 * {@code venice.server.aa.bottleneck.instrumentation.enabled} — the SAME flag
 * as {@link com.linkedin.davinci.stats.AaLeaderBottleneckReporter}, so a single
 * {@code -D} switch turns on both halves of the Phase 2 instrumentation.</p>
 *
 * <p>When enabled, every {@link #REPORT_INTERVAL_SECONDS} seconds emits the
 * following lines on stderr:</p>
 *
 * <pre>
 * [PRODUCER] stage=rt_producer_send_call calls=&lt;N&gt; total_ns=&lt;T&gt; avg_ns=&lt;T/N&gt; max_ns=&lt;M&gt;
 * [PRODUCER] stage=rt_producer_buffer_full calls=&lt;N&gt;
 * [PRODUCER] stage=rt_producer_records_per_sec value=&lt;V&gt;
 * </pre>
 *
 * <p>{@code rt_producer_buffer_full} is a threshold-based count — every
 * {@code send()} that took longer than {@link #BUFFER_FULL_THRESHOLD_NS}
 * (default 1 ms) is counted as a "buffer full" event, which captures the
 * common case of {@code RecordAccumulator}-blocked sends without requiring
 * us to instrument internal Kafka producer state.</p>
 */
public final class AaProducerBottleneckReporter {
  private static final long REPORT_INTERVAL_SECONDS = 20L;

  /** 1 ms threshold above which {@code send()} is counted as buffer-full. */
  private static final long BUFFER_FULL_THRESHOLD_NS = 1_000_000L;

  /**
   * Shares the leader-side instrumentation gate so a single {@code -D} flips
   * both. Read at class-load time.
   */
  public static final boolean ENABLED =
      Boolean.getBoolean("venice.server.aa.bottleneck.instrumentation.enabled");

  private static final LongAdder SEND_CALL_COUNT = new LongAdder();
  private static final LongAdder SEND_CALL_NANOS = new LongAdder();
  private static final AtomicLong SEND_CALL_MAX_NANOS = new AtomicLong(0L);
  private static final LongAdder BUFFER_FULL_COUNT = new LongAdder();

  private static long lastSendCallCount = 0L;
  private static long lastSendCallNanos = 0L;
  private static long lastBufferFullCount = 0L;
  private static long tickNumber = 0L;

  private static final AtomicBoolean STARTED = new AtomicBoolean();

  static {
    if (ENABLED) {
      startReporter();
    }
  }

  private AaProducerBottleneckReporter() {
  }

  /**
   * Record a single producer send-call invocation and its synchronous duration.
   * Callers SHOULD guard with {@link #ENABLED} to skip the call on the fast
   * path.
   *
   * @param nanos wall-clock nanoseconds spent in the {@code send()} body
   */
  public static void recordSendCall(long nanos) {
    if (!ENABLED) {
      return;
    }
    SEND_CALL_COUNT.increment();
    SEND_CALL_NANOS.add(nanos);
    long prev = SEND_CALL_MAX_NANOS.get();
    while (nanos > prev && !SEND_CALL_MAX_NANOS.compareAndSet(prev, nanos)) {
      prev = SEND_CALL_MAX_NANOS.get();
    }
    if (nanos >= BUFFER_FULL_THRESHOLD_NS) {
      BUFFER_FULL_COUNT.increment();
    }
  }

  private static void startReporter() {
    if (STARTED.compareAndSet(false, true)) {
      ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "AaProducerBottleneckReporter");
        t.setDaemon(true);
        return t;
      });
      scheduler.scheduleAtFixedRate(
          AaProducerBottleneckReporter::reportOnce,
          REPORT_INTERVAL_SECONDS,
          REPORT_INTERVAL_SECONDS,
          TimeUnit.SECONDS);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          reportOnce();
        } catch (Throwable ignored) {
          // best effort
        }
      }, "AaProducerBottleneckReporterShutdown"));
    }
  }

  private static synchronized void reportOnce() {
    long sendCallTotal = SEND_CALL_COUNT.sum();
    long sendNanosTotal = SEND_CALL_NANOS.sum();
    long bufferFullTotal = BUFFER_FULL_COUNT.sum();

    long deltaCalls = sendCallTotal - lastSendCallCount;
    long deltaNanos = sendNanosTotal - lastSendCallNanos;
    long deltaBufferFull = bufferFullTotal - lastBufferFullCount;

    lastSendCallCount = sendCallTotal;
    lastSendCallNanos = sendNanosTotal;
    lastBufferFullCount = bufferFullTotal;

    long maxNanos = SEND_CALL_MAX_NANOS.getAndSet(0L);

    if (deltaCalls <= 0L && deltaBufferFull <= 0L) {
      return;
    }

    tickNumber++;
    double avgNs = deltaCalls > 0 ? (double) deltaNanos / (double) deltaCalls : 0.0;
    double recordsPerSec = (double) deltaCalls / (double) REPORT_INTERVAL_SECONDS;

    System.err.println(
        String.format(
            "[PRODUCER] stage=rt_producer_send_call calls=%d total_ns=%d avg_ns=%.1f max_ns=%d",
            deltaCalls,
            deltaNanos,
            avgNs,
            maxNanos));
    System.err.println(
        String.format(
            "[PRODUCER] stage=rt_producer_buffer_full calls=%d threshold_ns=%d",
            deltaBufferFull,
            BUFFER_FULL_THRESHOLD_NS));
    System.err.println(
        String.format("[PRODUCER] stage=rt_producer_records_per_sec value=%.2f", recordsPerSec));
    System.err.println(
        String.format(
            "[PRODUCER-SUMMARY] tick=%d send_calls=%d buffer_full=%d records_per_sec=%.2f avg_ns=%.1f max_ns=%d",
            tickNumber,
            deltaCalls,
            deltaBufferFull,
            recordsPerSec,
            avgNs,
            maxNanos));
  }
}
