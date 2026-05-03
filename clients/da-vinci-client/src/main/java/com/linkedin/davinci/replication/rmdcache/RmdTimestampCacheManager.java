package com.linkedin.davinci.replication.rmdcache;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;


/**
 * Owner of per-partition {@link RmdTimestampCache} instances for one store-version, plus a
 * global stderr reporter that emits
 * {@code [RMD-CACHE] workload=PUT skipped=S fallback=F ratio=R} lines at a fixed cadence so
 * the JMH benchmark harness can pick them up without requiring modifications to the benchmark
 * source.
 *
 * <p>Per the proposal, a cache is per-partition (not shared across partitions), so this
 * manager lazily creates one {@link RmdTimestampCache} per partition.</p>
 */
public class RmdTimestampCacheManager {
  // Shared bloom-filter expected-insertions / fpp are reused across partitions in the same
  // store-version. These values are tuned to keep memory bounded while maintaining a low
  // false-positive rate for the target workload (10k bounded-pool PUT benchmark).
  private final long bloomExpectedInsertions;
  private final double bloomFpp;
  private final long timeWindowMs;
  private final int maxCacheSize;
  private final boolean bloomAuthoritative;

  private final ConcurrentHashMap<Integer, RmdTimestampCache> perPartitionCache = new ConcurrentHashMap<>();

  public RmdTimestampCacheManager(
      long timeWindowMs,
      int maxCacheSize,
      long bloomExpectedInsertions,
      double bloomFpp,
      boolean bloomAuthoritative) {
    this.timeWindowMs = timeWindowMs;
    this.maxCacheSize = maxCacheSize;
    this.bloomExpectedInsertions = bloomExpectedInsertions;
    this.bloomFpp = bloomFpp;
    this.bloomAuthoritative = bloomAuthoritative;
  }

  /**
   * Returns the cache for the given partition, creating a new one on first access.
   */
  public RmdTimestampCache getOrCreate(int partitionId) {
    return perPartitionCache.computeIfAbsent(partitionId, id -> {
      RmdTimestampCache cache = new RmdTimestampCache(
          id,
          timeWindowMs,
          maxCacheSize,
          new PartitionBloomFilter(bloomExpectedInsertions, bloomFpp));
      cache.setBloomFilterAuthoritative(bloomAuthoritative);
      return cache;
    });
  }

  public Collection<RmdTimestampCache> getAllPartitionCaches() {
    return Collections.unmodifiableCollection(perPartitionCache.values());
  }

  public void removePartition(int partitionId) {
    perPartitionCache.remove(partitionId);
  }

  /**
   * Registers this manager with the process-global reporter so its counters appear in the
   * periodic {@code [RMD-CACHE]} stderr lines.
   */
  public void registerWithGlobalReporter() {
    GlobalRmdCacheReporter.INSTANCE.register(this);
  }

  public void unregisterFromGlobalReporter() {
    GlobalRmdCacheReporter.INSTANCE.unregister(this);
  }

  long getSkippedRmdLookupCountTotal() {
    long total = 0L;
    for (RmdTimestampCache c: perPartitionCache.values()) {
      total += c.getSkippedRmdLookupCount();
    }
    return total;
  }

  long getFallbackRmdLookupCountTotal() {
    long total = 0L;
    for (RmdTimestampCache c: perPartitionCache.values()) {
      total += c.getFallbackRmdLookupCount();
    }
    return total;
  }

  public long getTimeWindowMs() {
    return timeWindowMs;
  }

  public int getMaxCacheSize() {
    return maxCacheSize;
  }

  /**
   * Process-global singleton that snapshots every registered {@link RmdTimestampCacheManager}
   * every 20s and emits a single {@code [RMD-CACHE] workload=PUT skipped=S fallback=F ratio=R}
   * line to stderr. 20s matches the JMH measurement-iteration length in the canonical
   * benchmark command, so each measurement iteration gets ~one line, which the master agent's
   * verifier averages across.
   *
   * <p>The reporter thread is a daemon and starts on first {@link #register} call.</p>
   */
  static final class GlobalRmdCacheReporter {
    static final GlobalRmdCacheReporter INSTANCE = new GlobalRmdCacheReporter();
    private static final long REPORT_INTERVAL_SECONDS = 20L;

    private final ConcurrentHashMap<RmdTimestampCacheManager, Boolean> registry = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean(false);
    private ScheduledExecutorService scheduler;
    private ScheduledFuture<?> reportTask;

    // Snapshots of totals at the previous tick, used to emit per-iteration deltas rather
    // than cumulative numbers — this gives the master agent a clean per-iteration ratio
    // rather than a smeared cumulative ratio that is biased by early-warmup misses.
    private volatile long lastSkipped = 0L;
    private volatile long lastFallback = 0L;

    private GlobalRmdCacheReporter() {
    }

    void register(RmdTimestampCacheManager mgr) {
      registry.put(mgr, Boolean.TRUE);
      startIfNeeded();
    }

    void unregister(RmdTimestampCacheManager mgr) {
      registry.remove(mgr);
    }

    private void startIfNeeded() {
      if (started.compareAndSet(false, true)) {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
          Thread t = new Thread(r, "RmdCacheReporter");
          t.setDaemon(true);
          return t;
        });
        reportTask = scheduler
            .scheduleAtFixedRate(this::reportOnce, REPORT_INTERVAL_SECONDS, REPORT_INTERVAL_SECONDS, TimeUnit.SECONDS);
        // Also emit a final report on JVM shutdown so short runs still produce at least
        // one line.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
          try {
            reportOnce();
          } catch (Throwable ignored) {
          }
        }, "RmdCacheReporterShutdown"));
      }
    }

    private void reportOnce() {
      long skipped = 0L;
      long fallback = 0L;
      for (RmdTimestampCacheManager m: registry.keySet()) {
        skipped += m.getSkippedRmdLookupCountTotal();
        fallback += m.getFallbackRmdLookupCountTotal();
      }
      long deltaSkipped = skipped - lastSkipped;
      long deltaFallback = fallback - lastFallback;
      lastSkipped = skipped;
      lastFallback = fallback;
      long total = deltaSkipped + deltaFallback;
      if (total == 0L) {
        // No traffic this interval — skip to avoid flooding stderr with zero lines.
        return;
      }
      double ratio = (double) deltaSkipped / total;
      System.err.println(
          String.format(
              "[RMD-CACHE] workload=PUT skipped=%d fallback=%d ratio=%.4f",
              deltaSkipped,
              deltaFallback,
              ratio));
    }

    // Test-only hook.
    void reportOnceForTest() {
      reportOnce();
    }
  }
}
