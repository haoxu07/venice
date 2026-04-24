package com.linkedin.davinci.replication.rmdcache;

import com.linkedin.venice.annotation.Threadsafe;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Per-partition, in-memory cache of (keyHash, logicalTimestamp) pairs covering the time window
 * {@code [CurrentTime - T, CurrentTime]}, plus a scalar
 * {@code highestTimestampPersistedInDbButNotInCache}, plus a bloom filter for the "definitely not
 * in DB" fast path, used by the Active-Active leader to avoid RocksDB RMD reads during
 * Deterministic Conflict Resolution (DCR).
 *
 * <p>Implements the four-branch decision logic from the design proposal:
 * <pre>
 *   (A)     Bloom filter says key is DEFINITELY NOT IN DB
 *               -> new record wins; insert (keyHash, ts) into cache. NO RMD lookup.
 *   (B.1)   Bloom says MAYBE IN DB, keyHash IS in cache
 *               -> compare new.ts vs cached.ts; update cache entry if new wins,
 *                  otherwise drop. NO RMD lookup.
 *   (B.2.a) Bloom says MAYBE IN DB, keyHash NOT in cache,
 *           new.ts &gt; highestTimestampPersistedInDbButNotInCache
 *               -> new wins; insert (keyHash, new.ts) into cache. NO RMD lookup.
 *   (B.2.b) Bloom says MAYBE IN DB, keyHash NOT in cache,
 *           new.ts &lt;= highestTimestampPersistedInDbButNotInCache
 *               -> fall back to RocksDB RMD lookup.
 * </pre>
 *
 * <p>The cache evicts entries whose timestamp is older than (CurrentTime - T). On eviction, the
 * scalar {@link #highestTsInDbButNotInCache} is raised to {@code max(existing, evicted.ts)},
 * preserving the invariant: "every key in DB but absent from cache has timestamp &le;
 * highestTsInDbButNotInCache".</p>
 *
 * <p>Thread-safety: intended to be shared across the AA/WC parallel processing workers for ONE
 * partition. Uses {@link ConcurrentHashMap} for the main map and {@link AtomicLong} for the
 * highest-ts scalar. Per-key atomic updates use {@link ConcurrentHashMap#compute}.</p>
 */
@Threadsafe
public class RmdTimestampCache {
  /**
   * The result of a {@link #decideAndUpdate(long, long, long)} call, signaling which branch
   * was taken by the decision logic.
   */
  public enum Decision {
    /** Branch A: key definitely not in DB per bloom filter. New record wins. */
    BLOOM_NEW_KEY_WINS,
    /** Branch B.1, new record wins: cache hit, new.ts beats cached.ts. */
    CACHE_HIT_NEW_WINS,
    /** Branch B.1, new record loses: cache hit, cached.ts &ge; new.ts. */
    CACHE_HIT_NEW_LOSES,
    /**
     * Branch B.2.a: cache miss, bloom says maybe, new.ts beats the highest-ts-not-in-cache
     * watermark, so new record wins without an RMD lookup.
     */
    ABOVE_HIGH_WATERMARK_NEW_WINS,
    /**
     * Branch B.2.b: cache miss, bloom says maybe, new.ts &le; highest-ts-not-in-cache, so
     * caller MUST fall back to the RocksDB RMD lookup and run the normal DCR comparison.
     */
    FALLBACK_TO_RMD_LOOKUP;

    /**
     * @return true iff this decision corresponds to a branch that skipped the RocksDB RMD
     *         read (A, B.1, B.2.a). Branch B.2.b is the only fallback.
     */
    public boolean skippedRmdLookup() {
      return this != FALLBACK_TO_RMD_LOOKUP;
    }

    /**
     * @return true iff this decision indicates the new record wins DCR. When false for
     *         CACHE_HIT_NEW_LOSES, the caller should drop the record. When the decision is
     *         FALLBACK_TO_RMD_LOOKUP, the caller must run DCR on the RMD lookup result.
     */
    public boolean newRecordWins() {
      return this == BLOOM_NEW_KEY_WINS || this == CACHE_HIT_NEW_WINS
          || this == ABOVE_HIGH_WATERMARK_NEW_WINS;
    }
  }

  private final int partitionId;
  private final long timeWindowMs;
  private final int maxSize;

  private final ConcurrentHashMap<Long, Long> keyHashToTimestamp;
  private final PartitionBloomFilter bloomFilter;

  /**
   * Invariant: every key present in DB but absent from {@link #keyHashToTimestamp} has
   * timestamp &le; this value. Atomically raised on cache eviction.
   */
  private final AtomicLong highestTsInDbButNotInCache = new AtomicLong(Long.MIN_VALUE);

  /** Counter of decisions that skipped the RMD RocksDB lookup (branches A + B.1 + B.2.a). */
  private final AtomicLong skippedRmdLookupCount = new AtomicLong();
  /** Counter of decisions that fell back to the RMD RocksDB lookup (branch B.2.b). */
  private final AtomicLong fallbackRmdLookupCount = new AtomicLong();

  /** Counter of eviction events triggered by TTL expiry. */
  private final AtomicLong ttlEvictionCount = new AtomicLong();
  /** Counter of eviction events triggered by size cap. */
  private final AtomicLong sizeCapEvictionCount = new AtomicLong();

  /**
   * @param partitionId       identifies the partition this cache belongs to, used only for
   *                          logging and diagnostic counters.
   * @param timeWindowMs      T: entries older than (now - timeWindowMs) are evicted.
   * @param maxSize           hard cap on entries. When exceeded, the oldest-timestamp entry is
   *                          evicted (and the high-watermark is raised accordingly).
   * @param bloomFilter       shared-by-composition; the caller constructs the bloom filter
   *                          sized for this partition's expected insertion volume.
   */
  public RmdTimestampCache(int partitionId, long timeWindowMs, int maxSize, PartitionBloomFilter bloomFilter) {
    if (timeWindowMs <= 0) {
      throw new IllegalArgumentException("timeWindowMs must be > 0, got " + timeWindowMs);
    }
    if (maxSize <= 0) {
      throw new IllegalArgumentException("maxSize must be > 0, got " + maxSize);
    }
    this.partitionId = partitionId;
    this.timeWindowMs = timeWindowMs;
    this.maxSize = maxSize;
    this.keyHashToTimestamp = new ConcurrentHashMap<>(Math.min(maxSize, 16384));
    this.bloomFilter = bloomFilter;
  }

  /**
   * Applies the four-branch decision logic to a new record with the given key hash and
   * logical timestamp.
   *
   * <p>This method is the hot path and is called once per AA-leader PUT that reaches DCR.
   * It MUST run before any RocksDB RMD lookup. Callers must branch on
   * {@link Decision#skippedRmdLookup()}: when true, no RMD lookup is required (the cache
   * already decided); when false, the caller performs the RMD lookup and runs normal DCR.</p>
   *
   * <p>In the winning branches (A, B.1-win, B.2.a), the cache is updated in-place with the
   * new (keyHash, ts) pair AND the bloom filter records the insertion. Both operations are
   * thread-safe.</p>
   *
   * @param keyHash     64-bit hash of the record's key bytes.
   * @param newTimestamp logical timestamp of the incoming record (as read from KME).
   * @param nowMs       current wall-clock time in ms; used to expire cache entries older
   *                    than (nowMs - timeWindowMs). Passed in so callers can fix it for
   *                    testability.
   * @return the {@link Decision} taken for this record.
   */
  public Decision decideAndUpdate(long keyHash, long newTimestamp, long nowMs) {
    // Opportunistic eviction of stale entries. Keeps the cache memory bounded in the steady
    // state. We only scan when the cache is near-full to avoid per-record sweep overhead.
    if (keyHashToTimestamp.size() > (maxSize >>> 1)) {
      evictStaleEntries(nowMs);
    }

    // --- Branch A: bloom filter says DEFINITELY NOT IN DB ---
    if (!bloomFilter.mightContain(keyHash)) {
      insertOrUpdate(keyHash, newTimestamp);
      skippedRmdLookupCount.incrementAndGet();
      return Decision.BLOOM_NEW_KEY_WINS;
    }

    // --- Branch B: bloom says MAYBE IN DB ---
    Long cachedTimestamp = keyHashToTimestamp.get(keyHash);
    if (cachedTimestamp != null) {
      // --- Branch B.1: keyHash IS in cache ---
      if (newTimestamp > cachedTimestamp) {
        // Atomically swap to the later timestamp. Another concurrent update with an even
        // later timestamp must win, so we use compute() to serialize the compare+set.
        keyHashToTimestamp.compute(keyHash, (k, existing) -> {
          if (existing == null || newTimestamp > existing) {
            return newTimestamp;
          }
          return existing;
        });
        skippedRmdLookupCount.incrementAndGet();
        return Decision.CACHE_HIT_NEW_WINS;
      } else {
        // Cached.ts >= new.ts: drop the incoming record without an RMD lookup.
        skippedRmdLookupCount.incrementAndGet();
        return Decision.CACHE_HIT_NEW_LOSES;
      }
    }

    // --- Branch B.2: keyHash NOT in cache ---
    long highWatermark = highestTsInDbButNotInCache.get();
    if (newTimestamp > highWatermark) {
      // --- Branch B.2.a: new.ts strictly above high watermark. New wins. ---
      insertOrUpdate(keyHash, newTimestamp);
      skippedRmdLookupCount.incrementAndGet();
      return Decision.ABOVE_HIGH_WATERMARK_NEW_WINS;
    }

    // --- Branch B.2.b: fall back to RMD lookup ---
    fallbackRmdLookupCount.incrementAndGet();
    return Decision.FALLBACK_TO_RMD_LOOKUP;
  }

  /**
   * Called by the caller AFTER a B.2.b fallback RMD lookup has been performed and DCR has
   * decided that the new record wins. Adds (keyHash, newTs) to the cache so that future
   * lookups for this key will hit branch B.1 rather than paying for RocksDB again.
   *
   * <p>If the new record LOST DCR after the B.2.b fallback, the caller must NOT call this
   * method — we don't want to cache a timestamp that doesn't reflect what we persisted.</p>
   */
  public void rememberAfterFallback(long keyHash, long newTimestamp) {
    insertOrUpdate(keyHash, newTimestamp);
  }

  /**
   * Evicts entries whose timestamp is older than (nowMs - timeWindowMs). On each eviction,
   * the {@link #highestTsInDbButNotInCache} watermark is raised to max(existing,
   * evicted.ts), preserving the correctness invariant stated at the class javadoc.
   *
   * <p>This method is safe under concurrent readers. Multiple concurrent evictors may race
   * but this is fine: worst case, a key is evicted twice (idempotent) and the watermark is
   * CAS-raised twice (also idempotent). No key is "lost" without updating the watermark.</p>
   */
  public void evictStaleEntries(long nowMs) {
    long cutoff = nowMs - timeWindowMs;
    Iterator<Map.Entry<Long, Long>> it = keyHashToTimestamp.entrySet().iterator();
    while (it.hasNext()) {
      Map.Entry<Long, Long> e = it.next();
      long ts = e.getValue();
      if (ts < cutoff) {
        // Remove only if the timestamp matches — protects against concurrent updates that
        // refreshed the entry between our read and the evict.
        boolean removed = keyHashToTimestamp.remove(e.getKey(), ts);
        if (removed) {
          raiseHighWatermark(ts);
          ttlEvictionCount.incrementAndGet();
        }
      }
    }
  }

  private void insertOrUpdate(long keyHash, long newTimestamp) {
    bloomFilter.put(keyHash);
    keyHashToTimestamp.compute(keyHash, (k, existing) -> {
      if (existing == null || newTimestamp > existing) {
        return newTimestamp;
      }
      return existing;
    });
    // Enforce size cap: when over, evict the entry with the oldest timestamp.
    if (keyHashToTimestamp.size() > maxSize) {
      evictOldestBySize();
    }
  }

  private void evictOldestBySize() {
    // Linear scan is O(n) but only runs when the cache has overflowed — typically
    // never after a few evictions, because the TTL path keeps it trimmed.
    long oldestTs = Long.MAX_VALUE;
    Long oldestKey = null;
    for (Map.Entry<Long, Long> e: keyHashToTimestamp.entrySet()) {
      if (e.getValue() < oldestTs) {
        oldestTs = e.getValue();
        oldestKey = e.getKey();
      }
    }
    if (oldestKey != null && keyHashToTimestamp.remove(oldestKey, oldestTs)) {
      raiseHighWatermark(oldestTs);
      sizeCapEvictionCount.incrementAndGet();
    }
  }

  private void raiseHighWatermark(long evictedTs) {
    highestTsInDbButNotInCache.accumulateAndGet(evictedTs, Math::max);
  }

  public long getHighestTsInDbButNotInCache() {
    return highestTsInDbButNotInCache.get();
  }

  public int size() {
    return keyHashToTimestamp.size();
  }

  public long getSkippedRmdLookupCount() {
    return skippedRmdLookupCount.get();
  }

  public long getFallbackRmdLookupCount() {
    return fallbackRmdLookupCount.get();
  }

  public long getTtlEvictionCount() {
    return ttlEvictionCount.get();
  }

  public long getSizeCapEvictionCount() {
    return sizeCapEvictionCount.get();
  }

  public int getPartitionId() {
    return partitionId;
  }

  public long getTimeWindowMs() {
    return timeWindowMs;
  }

  public int getMaxSize() {
    return maxSize;
  }

  /**
   * Test-only. Forces the high-watermark to a specific value so branch B.2.b can be
   * exercised deterministically without having to wait for real TTL evictions.
   */
  public void setHighestTsInDbButNotInCacheForTest(long value) {
    highestTsInDbButNotInCache.set(value);
  }
}
