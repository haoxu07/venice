package com.linkedin.davinci.stats;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Process-global stderr diagnostic for the transient record cache's effectiveness on the
 * AA-leader RMD lookup path. Emits lines of the form
 *
 * <pre>[TRANSIENT] delta_lookup=L delta_hit=H delta_ratio=R</pre>
 *
 * every {@link #REPORT_INTERVAL_SECONDS} seconds. Values are per-interval deltas (not
 * cumulative) so a benchmark verifier can directly compare ticks against measurement
 * iterations.
 *
 * <p>Kept as a standalone helper rather than folding into {@link HostLevelIngestionStats}
 * to keep the hot-path change to a single {@code incrementAndGet} per sensor record call.</p>
 */
public final class TransientRecordCacheDiagnosticReporter {
  private static final long REPORT_INTERVAL_SECONDS = 20L;

  private static final AtomicLong LOOKUP_COUNT = new AtomicLong();
  private static final AtomicLong HIT_COUNT = new AtomicLong();
  private static final AtomicBoolean STARTED = new AtomicBoolean();

  private static volatile long lastLookup = 0L;
  private static volatile long lastHit = 0L;

  private TransientRecordCacheDiagnosticReporter() {
  }

  public static void recordLookup() {
    LOOKUP_COUNT.incrementAndGet();
    startIfNeeded();
  }

  public static void recordHit() {
    HIT_COUNT.incrementAndGet();
  }

  private static void startIfNeeded() {
    if (STARTED.compareAndSet(false, true)) {
      ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "TransientCacheReporter");
        t.setDaemon(true);
        return t;
      });
      scheduler.scheduleAtFixedRate(
          TransientRecordCacheDiagnosticReporter::reportOnce,
          REPORT_INTERVAL_SECONDS,
          REPORT_INTERVAL_SECONDS,
          TimeUnit.SECONDS);
      Runtime.getRuntime().addShutdownHook(new Thread(() -> {
        try {
          reportOnce();
        } catch (Throwable ignored) {
        }
      }, "TransientCacheReporterShutdown"));
    }
  }

  private static void reportOnce() {
    long lookup = LOOKUP_COUNT.get();
    long hit = HIT_COUNT.get();
    long deltaLookup = lookup - lastLookup;
    long deltaHit = hit - lastHit;
    lastLookup = lookup;
    lastHit = hit;
    if (deltaLookup == 0L) {
      return;
    }
    double ratio = (double) deltaHit / (double) deltaLookup;
    System.err.println(
        String.format(
            "[TRANSIENT] delta_lookup=%d delta_hit=%d delta_ratio=%.4f",
            deltaLookup,
            deltaHit,
            ratio));
  }
}
