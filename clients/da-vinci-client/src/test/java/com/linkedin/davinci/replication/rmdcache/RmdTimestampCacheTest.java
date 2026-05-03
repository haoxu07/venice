package com.linkedin.davinci.replication.rmdcache;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;


/**
 * Unit tests for {@link RmdTimestampCache}, exercising the four PDF branches
 * (A, B.1-win, B.1-lose, B.2.a, B.2.b) and thread-safe concurrent writers.
 *
 * <p>Criterion 5 of the goal: drive PUT-like records through the cache and assert the DCR
 * decision matches the non-cached reference for every branch, including a batch fully routed
 * through B.2.b and a batch with two concurrent writers racing on the same key.</p>
 */
public class RmdTimestampCacheTest {
  private static final long TIME_WINDOW_MS = 60_000L;
  private static final int MAX_SIZE = 10_000;
  private static final long NOW_MS = 1_700_000_000_000L;
  private static final long BLOOM_EXPECTED = 100_000L;
  private static final double BLOOM_FPP = 0.01d;

  private RmdTimestampCache newCache() {
    return new RmdTimestampCache(0, TIME_WINDOW_MS, MAX_SIZE, new PartitionBloomFilter(BLOOM_EXPECTED, BLOOM_FPP));
  }

  /**
   * Branch A: bloom filter says "definitely not in DB" -&gt; new record wins without RMD read.
   * Requires the cache to be explicitly told the bloom filter is authoritative (i.e. the
   * partition was cold-created or every DB key has been pre-populated into the bloom).
   */
  @Test
  public void branchA_bloomSaysDefinitelyNotInDb_newWinsWithoutLookup() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true);
    long keyHash = 12345L;
    long ts = 1000L;

    RmdTimestampCache.Decision d = cache.decideAndUpdate(keyHash, ts, NOW_MS);

    assertEquals(d, RmdTimestampCache.Decision.BLOOM_NEW_KEY_WINS);
    assertTrue(d.skippedRmdLookup(), "Branch A must not trigger an RMD lookup");
    assertTrue(d.newRecordWins());
    assertEquals(cache.size(), 1);
    assertEquals(cache.getSkippedRmdLookupCount(), 1L);
    assertEquals(cache.getFallbackRmdLookupCount(), 0L);
  }

  /**
   * Safety: when bloomFilterAuthoritative=false (default), a brand-new key must NOT hit
   * branch A — even if the bloom trivially says "definitely not in DB" because it's empty.
   * The cache must fall back to RMD lookup to avoid incorrectly winning DCR for a key that
   * could exist in DB from a pre-existing batch push.
   */
  @Test
  public void branchA_disabled_whenBloomNotAuthoritative() {
    RmdTimestampCache cache = newCache();
    assertFalse(cache.isBloomFilterAuthoritative());
    long keyHash = 12345L;

    RmdTimestampCache.Decision d = cache.decideAndUpdate(keyHash, 1000L, NOW_MS);

    assertEquals(d, RmdTimestampCache.Decision.FALLBACK_TO_RMD_LOOKUP);
    assertEquals(cache.getFallbackRmdLookupCount(), 1L);
    assertEquals(cache.getSkippedRmdLookupCount(), 0L);
  }

  /** Branch B.1 win: second record for same key wins because its timestamp is higher. */
  @Test
  public void branchB1_win_cacheHitAndNewWins() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true); // allow branch A for the initial seed
    long keyHash = 42L;

    // First write: branch A (seed the cache).
    cache.decideAndUpdate(keyHash, 1000L, NOW_MS);
    // Second write with higher ts: branch B.1-win.
    RmdTimestampCache.Decision d = cache.decideAndUpdate(keyHash, 2000L, NOW_MS);

    assertEquals(d, RmdTimestampCache.Decision.CACHE_HIT_NEW_WINS);
    assertTrue(d.skippedRmdLookup());
    assertTrue(d.newRecordWins());
    assertEquals(cache.getSkippedRmdLookupCount(), 2L);
  }

  /** Branch B.1 lose: cached ts is greater-or-equal, drop the new record. */
  @Test
  public void branchB1_lose_cacheHitAndNewLoses() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true);
    long keyHash = 42L;

    cache.decideAndUpdate(keyHash, 5000L, NOW_MS);
    // Lower incoming timestamp — should be dropped.
    RmdTimestampCache.Decision d = cache.decideAndUpdate(keyHash, 3000L, NOW_MS);

    assertEquals(d, RmdTimestampCache.Decision.CACHE_HIT_NEW_LOSES);
    assertTrue(d.skippedRmdLookup(), "Branch B.1-lose must still skip the RMD lookup");
    assertFalse(d.newRecordWins());
    assertEquals(cache.getFallbackRmdLookupCount(), 0L);

    // Equal timestamp must also lose (strict >).
    RmdTimestampCache.Decision eq = cache.decideAndUpdate(keyHash, 5000L, NOW_MS);
    assertEquals(eq, RmdTimestampCache.Decision.CACHE_HIT_NEW_LOSES);
  }

  /**
   * Branch B.2.a: bloom says "maybe in DB" for a key NOT in cache, but the new ts is strictly
   * greater than the high-watermark, so new wins without an RMD lookup.
   *
   * <p>The cache's watermark is initialized to {@link Long#MAX_VALUE} so that B.2.a is safe
   * in the presence of pre-existing DB records that the cache never observed. For this test
   * we simulate the "cache has learned the upper bound" steady state by lowering the
   * watermark via {@link RmdTimestampCache#setHighestTsInDbButNotInCacheForTest(long)}.</p>
   */
  @Test
  public void branchB2a_aboveHighWatermark_newWinsWithoutLookup() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true); // allow branch A for the seed insert
    long keyHash = 42L;

    // Force-poison the bloom filter for keyHash=42 (so bloom says "maybe") without leaving
    // a live cache entry for it. We do this via insert-then-evict: the bloom bit persists
    // after the cache entry is cleared.
    cache.decideAndUpdate(keyHash, 1000L, NOW_MS);
    cache.evictStaleEntries(NOW_MS + 2 * TIME_WINDOW_MS); // drop the cache entry but keep bloom
    assertEquals(cache.size(), 0);

    // Simulate the "cache has learned an upper bound on absent-DB-keys' timestamps" state.
    cache.setHighestTsInDbButNotInCacheForTest(1000L);

    // 2. Now decide for same key with a NEW ts strictly greater than the watermark.
    // Bloom says "maybe", key not in cache, ts > watermark -> B.2.a.
    long evictionTimeNowMs = NOW_MS + 2 * TIME_WINDOW_MS;
    RmdTimestampCache.Decision d = cache.decideAndUpdate(keyHash, 2000L, evictionTimeNowMs);
    assertEquals(d, RmdTimestampCache.Decision.ABOVE_HIGH_WATERMARK_NEW_WINS);
    assertTrue(d.skippedRmdLookup());
    assertTrue(d.newRecordWins());
  }

  /**
   * Branch B.2.b: bloom says "maybe in DB", key NOT in cache, ts &le; watermark. MUST fall
   * back to RocksDB. This is criterion 5(a): a batch fully routed through B.2.b.
   */
  @Test
  public void branchB2b_belowOrEqualHighWatermark_fallsBackToRmdLookup() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true);

    // Seed bloom + raise watermark by inserting and evicting many keys.
    final int seedCount = 100;
    for (int i = 0; i < seedCount; i++) {
      long kh = 10000L + i;
      cache.decideAndUpdate(kh, 5000L + i, NOW_MS);
    }
    // Force-evict all: the bloom bits persist so keys will probe as "maybe in DB".
    cache.evictStaleEntries(NOW_MS + 2 * TIME_WINDOW_MS);
    assertEquals(cache.size(), 0);

    // Watermark initializes to Long.MAX_VALUE for correctness under pre-existing DB data.
    // For this test we want B.2.b to fire, so we need incomingTs <= watermark. Since
    // watermark = MAX_VALUE, any finite incomingTs is <= watermark automatically.

    // Now every subsequent query for one of those keys with ts <= watermark must
    // return FALLBACK_TO_RMD_LOOKUP. This is the batch from criterion 5(a).
    long nowForQueries = NOW_MS + 2 * TIME_WINDOW_MS;
    int fallbackCount = 0;
    for (int i = 0; i < seedCount; i++) {
      long kh = 10000L + i;
      long incomingTs = 4000L + i;
      RmdTimestampCache.Decision d = cache.decideAndUpdate(kh, incomingTs, nowForQueries);
      assertEquals(
          d,
          RmdTimestampCache.Decision.FALLBACK_TO_RMD_LOOKUP,
          "Every decision in a B.2.b batch must fall back");
      assertFalse(d.skippedRmdLookup(), "Fallback decisions MUST trigger the RMD lookup");
      fallbackCount++;
    }
    assertEquals(fallbackCount, seedCount);
    assertEquals(cache.getFallbackRmdLookupCount(), (long) seedCount);
    assertEquals(cache.getSkippedRmdLookupCount(), (long) seedCount); // the seed inserts
  }

  /**
   * Eviction invariant: after evicting entries whose ts &lt; (now - T), the watermark is
   * raised via {@code accumulateAndGet(evicted.ts, Math::max)}. Because the initial value
   * of the watermark is {@link Long#MAX_VALUE} (safety default for pre-existing DB data),
   * raises to smaller evicted values are no-ops — the invariant "any record in DB absent
   * from cache has ts &le; watermark" is preserved. We verify that evictions do reduce the
   * cache size and do NOT lower the watermark below its current value.
   */
  @Test
  public void evictionPreservesInvariantAndReducesSize() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true);
    for (int i = 0; i < 50; i++) {
      cache.decideAndUpdate(100L + i, 1_000L + i * 10L, NOW_MS);
    }
    long now = 1_200L + TIME_WINDOW_MS + 1L;
    long preWm = cache.getHighestTsInDbButNotInCache();
    cache.evictStaleEntries(now);
    long postWm = cache.getHighestTsInDbButNotInCache();

    // Watermark never decreases (accumulateAndGet with max).
    assertTrue(postWm >= preWm);
    // Size must shrink.
    assertTrue(cache.size() < 50);
  }

  /**
   * With the watermark initialized to {@link Long#MAX_VALUE}, branch B.2.a is unreachable
   * in the absence of an explicit caller-driven override
   * ({@link RmdTimestampCache#setHighestTsInDbButNotInCacheForTest(long)}) — a crucial
   * safety property to ensure the cache never incorrectly wins DCR for a key whose real
   * timestamp in DB is unknown.
   */
  @Test
  public void b2a_unreachable_whenWatermarkStaysAtMaxValue() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true);
    long keyHash = 777L;
    cache.decideAndUpdate(keyHash, 1_000L, NOW_MS);
    cache.evictStaleEntries(NOW_MS + 2 * TIME_WINDOW_MS);
    assertEquals(cache.size(), 0);
    // Watermark still MAX_VALUE (can only rise, never above MAX_VALUE).
    assertEquals(cache.getHighestTsInDbButNotInCache(), Long.MAX_VALUE);

    // Any finite incoming ts is < MAX_VALUE, so B.2.a cannot fire. We fall back.
    RmdTimestampCache.Decision d = cache.decideAndUpdate(keyHash, Long.MAX_VALUE - 1L, NOW_MS + 2 * TIME_WINDOW_MS);
    assertEquals(d, RmdTimestampCache.Decision.FALLBACK_TO_RMD_LOOKUP);
  }

  /**
   * Criterion 5(b): two concurrent writers race on the same key. Cache updates must be
   * thread-safe and produce the same final cached timestamp as the serial reference
   * (i.e. the max of all contributing timestamps).
   */
  @Test(invocationCount = 5)
  public void concurrentWritersOnSameKey_produceMaxTimestamp() throws InterruptedException {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true); // allow branch A for initial seeding
    long keyHash = 99L;

    final int threadCount = 16;
    final int perThread = 2_000;
    final AtomicLong maxTs = new AtomicLong(Long.MIN_VALUE);

    ExecutorService exec = Executors.newFixedThreadPool(threadCount);
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threadCount);

    for (int t = 0; t < threadCount; t++) {
      final int threadIdx = t;
      exec.submit(() -> {
        try {
          start.await();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        }
        Random rng = new Random(System.nanoTime() + threadIdx);
        for (int i = 0; i < perThread; i++) {
          long ts = Math.abs(rng.nextLong() % 1_000_000L);
          cache.decideAndUpdate(keyHash, ts, NOW_MS);
          maxTs.accumulateAndGet(ts, Math::max);
        }
        done.countDown();
      });
    }

    start.countDown();
    assertTrue(done.await(20, TimeUnit.SECONDS));
    exec.shutdown();
    assertTrue(exec.awaitTermination(5, TimeUnit.SECONDS));

    // The cache entry for keyHash must equal the max-ts across all concurrent submissions.
    // We probe via a decision: a decision with ts = maxTs - 1 should be CACHE_HIT_NEW_LOSES
    // (because cached = maxTs).
    RmdTimestampCache.Decision probeBelow = cache.decideAndUpdate(keyHash, maxTs.get() - 1, NOW_MS);
    assertEquals(probeBelow, RmdTimestampCache.Decision.CACHE_HIT_NEW_LOSES);

    // And a decision with ts = maxTs + 1 should be CACHE_HIT_NEW_WINS (bumps the entry).
    RmdTimestampCache.Decision probeAbove = cache.decideAndUpdate(keyHash, maxTs.get() + 1, NOW_MS);
    assertEquals(probeAbove, RmdTimestampCache.Decision.CACHE_HIT_NEW_WINS);
  }

  /**
   * Reference (serial) vs. cache parity: drive a common workload through both the serial
   * reference implementation (a plain {@code Map<Long, Long>}) and the concurrent cache.
   * The DCR decisions (win/lose) must match bit-for-bit.
   */
  @Test
  public void parityWithSerialReference() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true);
    java.util.HashMap<Long, Long> serialRef = new java.util.HashMap<>();
    // Seed bloom for a known set of keys — mirrors the B.2.a/b setup for a realistic scenario.
    Random rng = new Random(0xBADCAFEL);
    final int keyCount = 500;
    final int opCount = 5_000;
    List<long[]> ops = new ArrayList<>(opCount);
    for (int i = 0; i < opCount; i++) {
      long kh = rng.nextInt(keyCount);
      long ts = 1_000L + rng.nextInt(1_000_000);
      ops.add(new long[] { kh, ts });
    }
    AtomicInteger cacheWins = new AtomicInteger();
    AtomicInteger serialWins = new AtomicInteger();
    for (long[] op: ops) {
      long kh = op[0];
      long ts = op[1];
      // Serial reference: classic "if new > old then win, else lose; if no old then win".
      Long old = serialRef.get(kh);
      boolean serialNewWins = (old == null) || ts > old;
      if (serialNewWins) {
        serialRef.put(kh, ts);
        serialWins.incrementAndGet();
      }

      RmdTimestampCache.Decision d = cache.decideAndUpdate(kh, ts, NOW_MS);
      boolean cacheDecidedNewWins;
      if (d == RmdTimestampCache.Decision.FALLBACK_TO_RMD_LOOKUP) {
        // For parity, the fallback outcome matches the serial reference — because the cache
        // itself doesn't decide, the caller does (and the caller uses the same compare).
        cacheDecidedNewWins = serialNewWins;
        // Simulate caller behavior after fallback: if new won, update the cache.
        if (cacheDecidedNewWins) {
          cache.rememberAfterFallback(kh, ts);
        }
      } else {
        cacheDecidedNewWins = d.newRecordWins();
      }
      if (cacheDecidedNewWins) {
        cacheWins.incrementAndGet();
      }
      assertEquals(
          cacheDecidedNewWins,
          serialNewWins,
          "Cache decision must match serial ref for op (" + kh + "," + ts + ")");
    }
    // Also assert the total wins match — equivalent to the above per-op check, but a
    // final cross-check for paranoia.
    assertEquals(cacheWins.get(), serialWins.get());
  }

  /**
   * Criterion 5(a) — additional scenario: drive the cache to a state where EVERY subsequent
   * PUT falls back to the RMD lookup, and verify the fallback counter ratio is 1.0 (i.e.
   * nothing was incorrectly short-circuited through branches A/B.1/B.2.a). This test
   * would FAIL if the fallback path were removed from the cache, as the B.2.b decisions
   * would instead take some other (incorrect) branch.
   */
  @Test
  public void allDecisionsMustFallBack_whenKeysAreBelowWatermark() {
    RmdTimestampCache cache = newCache();
    cache.setBloomFilterAuthoritative(true);
    final int keyCount = 50;

    // Seed + evict: leaves bloom bits set (keys probe as "maybe") and entries removed.
    for (int i = 0; i < keyCount; i++) {
      cache.decideAndUpdate(1_000L + i, 1_000L + i, NOW_MS);
    }
    cache.evictStaleEntries(NOW_MS + 2 * TIME_WINDOW_MS);
    assertEquals(cache.size(), 0);
    // Watermark remains at Long.MAX_VALUE because accumulateAndGet with max never lowers.
    assertEquals(cache.getHighestTsInDbButNotInCache(), Long.MAX_VALUE);

    long nowForQueries = NOW_MS + 2 * TIME_WINDOW_MS;
    long before = cache.getFallbackRmdLookupCount();
    for (int i = 0; i < keyCount; i++) {
      long kh = 1_000L + i;
      // Any finite ts <= MAX_VALUE so B.2.b fires unconditionally.
      RmdTimestampCache.Decision d = cache.decideAndUpdate(kh, 3_000L + i, nowForQueries);
      assertEquals(d, RmdTimestampCache.Decision.FALLBACK_TO_RMD_LOOKUP);
    }
    assertEquals(cache.getFallbackRmdLookupCount() - before, keyCount);
  }
}
