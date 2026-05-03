package com.linkedin.davinci.replication.rmdcache;

import com.linkedin.venice.annotation.Threadsafe;
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
 *               -&gt; new record wins; insert (keyHash, ts) into cache. NO RMD lookup.
 *   (B.1)   Bloom says MAYBE IN DB, keyHash IS in cache
 *               -&gt; compare new.ts vs cached.ts; update cache entry if new wins,
 *                  otherwise drop. NO RMD lookup.
 *   (B.2.a) Bloom says MAYBE IN DB, keyHash NOT in cache,
 *           new.ts &gt; highestTimestampPersistedInDbButNotInCache
 *               -&gt; new wins; insert (keyHash, new.ts) into cache. NO RMD lookup.
 *   (B.2.b) Bloom says MAYBE IN DB, keyHash NOT in cache,
 *           new.ts &lt;= highestTimestampPersistedInDbButNotInCache
 *               -&gt; fall back to RocksDB RMD lookup.
 * </pre>
 *
 * <p>The cache evicts entries whose timestamp is older than (CurrentTime - T). On eviction, the
 * scalar {@link #highestTsInDbButNotInCache} is raised to {@code max(existing, evicted.ts)},
 * preserving the invariant: "every key in DB but absent from cache has timestamp &le;
 * highestTsInDbButNotInCache".</p>
 *
 * <p>Thread-safety: shared across the AA/WC parallel processing workers for ONE partition. The
 * main map is a lock-free, allocation-free {@link LockFreeLongLongMap} that uses CAS on long
 * slots — no autoboxing, no per-call allocation. The high-watermark scalar is an
 * {@link AtomicLong}.</p>
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
      return this == BLOOM_NEW_KEY_WINS || this == CACHE_HIT_NEW_WINS || this == ABOVE_HIGH_WATERMARK_NEW_WINS;
    }
  }

  private final int partitionId;
  private final long timeWindowMs;
  private final int maxSize;

  private final LockFreeLongLongMap keyHashToTimestamp;
  private final PartitionBloomFilter bloomFilter;

  /**
   * Invariant: every key present in DB but absent from {@link #keyHashToTimestamp} has
   * timestamp &le; this value. Atomically raised on cache eviction.
   *
   * <p>Initialized to {@link Long#MAX_VALUE} so the invariant holds at startup even when
   * the DB has pre-existing records (batch push data, backup restore, etc.) that the cache
   * never observed. Under this initialization, branch B.2.a (new.ts &gt; watermark) can only
   * fire once the cache has observed AND evicted at least one record whose timestamp
   * establishes a concrete upper bound on "absent keys", which requires an explicit
   * {@link #priming} event from the caller (typically the first post-EOP PUT ingested by
   * the ingestion task).</p>
   */
  private final AtomicLong highestTsInDbButNotInCache = new AtomicLong(Long.MAX_VALUE);

  /** Counter of decisions that skipped the RMD RocksDB lookup (branches A + B.1 + B.2.a). */
  private final AtomicLong skippedRmdLookupCount = new AtomicLong();
  /** Counter of decisions that fell back to the RMD RocksDB lookup (branch B.2.b). */
  private final AtomicLong fallbackRmdLookupCount = new AtomicLong();

  /** Counter of eviction events triggered by TTL expiry. */
  private final AtomicLong ttlEvictionCount = new AtomicLong();

  /**
   * Last wall-clock time (ms) at which we invoked {@link #evictStaleEntries(long)}. Used to
   * rate-limit the eviction sweep so the hot path doesn't do O(N) work on every call.
   * Initialized to {@link Long#MIN_VALUE} and lazily set to {@code nowMs} on the first
   * {@link #decideAndUpdate} — so the first call doesn't prematurely evict everything
   * with a cutoff of {@code nowMs - timeWindowMs}.
   */
  private final AtomicLong lastEvictionTimeMs = new AtomicLong(Long.MIN_VALUE);

  /**
   * Minimum interval between two consecutive eviction sweeps, derived from
   * {@code timeWindowMs / 4} so we sweep a handful of times per window. Rate-limiting
   * eviction keeps steady-state per-record overhead at O(1).
   */
  private final long evictionSweepIntervalMs;

  /**
   * @param partitionId       identifies the partition this cache belongs to, used only for
   *                          logging and diagnostic counters.
   * @param timeWindowMs      T: entries older than (now - timeWindowMs) are evicted.
   * @param maxSize           target entry count. The backing map is sized at 4x this for
   *                          low load factor under open-addressing probing.
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
    // Size at ~4x to keep the load factor under 0.25, which gives near-zero probe length.
    // Cap at Integer.MAX_VALUE/2 to avoid overflow in the LockFreeLongLongMap sizing.
    int backingCapacity;
    long desired = Math.min((long) maxSize * 4L, (long) Integer.MAX_VALUE >>> 2);
    backingCapacity = (int) Math.max(desired, 16L);
    this.keyHashToTimestamp = new LockFreeLongLongMap(backingCapacity);
    this.bloomFilter = bloomFilter;
    this.evictionSweepIntervalMs = Math.max(1L, timeWindowMs / 4L);
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
   * @param keyHash     64-bit hash of the record's key bytes. Callers must not pass
   *                    {@code LockFreeLongLongMap.EMPTY_KEY}; we remap 0 internally.
   * @param newTimestamp logical timestamp of the incoming record (as read from KME).
   * @param nowMs       current wall-clock time in ms; used to amortize eviction sweeps.
   * @return the {@link Decision} taken for this record.
   */
  public Decision decideAndUpdate(long keyHash, long newTimestamp, long nowMs) {
    long mappedKey = (keyHash == LockFreeLongLongMap.EMPTY_KEY) ? LockFreeLongLongMap.EMPTY_KEY_SURROGATE : keyHash;

    // Rate-limited opportunistic eviction. Allocates no objects in the common case.
    maybeEvict(nowMs);

    // --- Branch A: bloom filter says DEFINITELY NOT IN DB ---
    // Only safe when the bloom filter is AUTHORITATIVE for DB contents — i.e. it has
    // observed every write ever made to this partition's DB. In Venice the partition
    // may contain records from batch push / backup restore / data recovery that the
    // cache never observed; for those keys the bloom would incorrectly say "not in DB"
    // while in fact a record WITH A LATER TIMESTAMP is in DB, causing branch A to
    // erroneously win DCR and corrupt data.
    //
    // We therefore only fire branch A after the caller has explicitly marked the
    // bloom filter as authoritative via {@link #setBloomFilterAuthoritative(boolean)}.
    // By default the flag is false — equivalent to always skipping branch A.
    // Note this means a true "new key" still pays one RocksDB RMD read on first
    // sighting; future writes to the same key hit branch B.1.
    if (bloomFilterAuthoritative && !bloomFilter.mightContain(keyHash)) {
      keyHashToTimestamp.putIfGreaterOrAbsent(mappedKey, newTimestamp);
      bloomFilter.put(keyHash);
      skippedRmdLookupCount.incrementAndGet();
      return Decision.BLOOM_NEW_KEY_WINS;
    }

    // --- Branch B: bloom says MAYBE IN DB ---
    long cachedTs = keyHashToTimestamp.get(mappedKey);
    if (cachedTs != Long.MIN_VALUE) {
      // --- Branch B.1: keyHash IS in cache ---
      if (newTimestamp > cachedTs) {
        keyHashToTimestamp.putIfGreaterOrAbsent(mappedKey, newTimestamp);
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
      keyHashToTimestamp.putIfGreaterOrAbsent(mappedKey, newTimestamp);
      bloomFilter.put(keyHash);
      skippedRmdLookupCount.incrementAndGet();
      return Decision.ABOVE_HIGH_WATERMARK_NEW_WINS;
    }

    // --- Branch B.2.b: fall back to RMD lookup ---
    fallbackRmdLookupCount.incrementAndGet();
    return Decision.FALLBACK_TO_RMD_LOOKUP;
  }

  /**
   * Whether the bloom filter has been fully warmed with all keys present in the partition's
   * DB. When false (the default), branch A is effectively disabled to preserve DCR
   * correctness in the presence of pre-existing DB records that the cache never observed
   * (batch push, backup restore, data recovery). When true, branch A fires whenever the
   * bloom reports "definitely not in DB".
   */
  private volatile boolean bloomFilterAuthoritative = false;

  /**
   * Opt-in switch to enable branch A. Callers must only flip this to {@code true} after
   * populating the bloom filter with every key present in the partition's DB (either via
   * a full-partition scan at startup or by asserting that the partition is freshly
   * created and empty).
   */
  public void setBloomFilterAuthoritative(boolean authoritative) {
    this.bloomFilterAuthoritative = authoritative;
  }

  /** For tests and diagnostics. */
  public boolean isBloomFilterAuthoritative() {
    return bloomFilterAuthoritative;
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
    long mappedKey = (keyHash == LockFreeLongLongMap.EMPTY_KEY) ? LockFreeLongLongMap.EMPTY_KEY_SURROGATE : keyHash;
    keyHashToTimestamp.putIfGreaterOrAbsent(mappedKey, newTimestamp);
    bloomFilter.put(keyHash);
  }

  private void maybeEvict(long nowMs) {
    long last = lastEvictionTimeMs.get();
    if (last == Long.MIN_VALUE) {
      // First call: just anchor the eviction clock so the next actual sweep is a full
      // interval away. We don't evict on the very first call because there's nothing to
      // evict and we want to avoid a spurious initial sweep.
      lastEvictionTimeMs.compareAndSet(last, nowMs);
      return;
    }
    if (nowMs - last < evictionSweepIntervalMs) {
      return;
    }
    if (!lastEvictionTimeMs.compareAndSet(last, nowMs)) {
      // Another thread won the race.
      return;
    }
    evictStaleEntries(nowMs);
  }

  /**
   * Evicts entries whose timestamp is older than (nowMs - timeWindowMs). On each eviction,
   * the {@link #highestTsInDbButNotInCache} watermark is raised to max(existing,
   * evicted.ts), preserving the correctness invariant stated at the class javadoc.
   */
  public void evictStaleEntries(long nowMs) {
    long cutoff = nowMs - timeWindowMs;
    int evicted = keyHashToTimestamp.evictBelowThreshold(cutoff, (k, v) -> raiseHighWatermark(v));
    if (evicted > 0) {
      ttlEvictionCount.addAndGet(evicted);
    }
  }

  private void raiseHighWatermark(long evictedTs) {
    highestTsInDbButNotInCache.accumulateAndGet(evictedTs, Math::max);
  }

  public long getHighestTsInDbButNotInCache() {
    return highestTsInDbButNotInCache.get();
  }

  /** O(N) full scan. Test / diagnostics only; not called from the hot path. */
  public int size() {
    return keyHashToTimestamp.approximateSize();
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
