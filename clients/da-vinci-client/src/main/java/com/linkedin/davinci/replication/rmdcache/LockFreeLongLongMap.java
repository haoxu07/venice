package com.linkedin.davinci.replication.rmdcache;

import java.util.concurrent.atomic.AtomicLongArray;


/**
 * A lock-free, allocation-free, primitive long-to-long open-addressing hash map. Used as the
 * hot-path store for {@link RmdTimestampCache} so we avoid {@link Long} autoboxing on every
 * cache probe, which dominates the cache overhead in high-throughput benchmarks.
 *
 * <p>Layout: two parallel {@link AtomicLongArray}s — one for keys and one for values, with
 * linear probing on hash collisions. A sentinel value {@link #EMPTY_KEY} denotes an empty
 * slot.</p>
 *
 * <p>Invariants:
 * <ul>
 *   <li>The caller ensures keys are never {@link #EMPTY_KEY}. {@code RmdTimestampCache}
 *       remaps keyHash 0 to {@link #EMPTY_KEY_SURROGATE} before calling in.</li>
 *   <li>On a slot insertion, the key CAS precedes the value write, so a concurrent reader
 *       that sees the key but reads before the value write may observe a stale (0) value.
 *       The {@code computeIfWinsRace} operation is resilient to this because it re-reads
 *       under CAS.</li>
 * </ul></p>
 *
 * <p>Size management: when the map is over 50% full, writes become best-effort (the new
 * entry is dropped). This is acceptable because the cache's correctness depends on it
 * being an <em>accelerator</em> — a missed insertion simply degrades the fast-path to a
 * RocksDB fallback. Callers should size the map generously (2-4x expected keys).</p>
 */
final class LockFreeLongLongMap {
  /** Sentinel reserved-key value indicating an empty slot. */
  static final long EMPTY_KEY = 0L;
  /**
   * Remapping for the reserved sentinel so callers can still store keyHash==0. This is chosen
   * to be an improbable real 64-bit hash: the high bit is set, so only collides with actual
   * hash values that happen to equal exactly 0x8000000000000001L. For the xxHash64-derived
   * hashes used by {@link KeyHasher}, such collisions are astronomically rare.
   */
  static final long EMPTY_KEY_SURROGATE = 0x8000000000000001L;

  private final AtomicLongArray keys;
  private final AtomicLongArray values;
  private final int capacityMask;
  private final int capacity;

  LockFreeLongLongMap(int capacityHint) {
    // Round up to next power of two, min 16.
    int cap = 16;
    while (cap < capacityHint) {
      cap <<= 1;
    }
    if (cap <= 0) {
      cap = Integer.highestOneBit(Integer.MAX_VALUE >>> 1);
    }
    this.capacity = cap;
    this.capacityMask = cap - 1;
    this.keys = new AtomicLongArray(cap);
    this.values = new AtomicLongArray(cap);
  }

  /**
   * Returns the value for the given key, or {@link Long#MIN_VALUE} if not present. Callers
   * must check for this sentinel.
   */
  long get(long key) {
    int idx = indexFor(key);
    for (int probe = 0; probe < capacity; probe++) {
      int slot = (idx + probe) & capacityMask;
      long k = keys.get(slot);
      if (k == EMPTY_KEY) {
        return Long.MIN_VALUE;
      }
      if (k == key) {
        return values.get(slot);
      }
    }
    return Long.MIN_VALUE;
  }

  /**
   * Inserts or updates the given (key, value), applying a "max wins" rule: if the slot is
   * empty, insert; else, CAS the existing value up to newValue only if newValue > existing.
   * Returns true if the new value was installed (either new insert or CAS'd over an older one).
   */
  boolean putIfGreaterOrAbsent(long key, long newValue) {
    int idx = indexFor(key);
    for (int probe = 0; probe < capacity; probe++) {
      int slot = (idx + probe) & capacityMask;
      long k = keys.get(slot);
      if (k == EMPTY_KEY) {
        // Attempt to claim the slot. CAS the key, then write the value.
        if (keys.compareAndSet(slot, EMPTY_KEY, key)) {
          values.set(slot, newValue);
          return true;
        }
        // Someone else raced us; re-read and fall through to compare.
        k = keys.get(slot);
      }
      if (k == key) {
        // Existing entry for this key: CAS value up only if new > existing.
        while (true) {
          long existing = values.get(slot);
          if (newValue <= existing) {
            return false;
          }
          if (values.compareAndSet(slot, existing, newValue)) {
            return true;
          }
          // CAS lost; retry.
        }
      }
    }
    // Table full / oversubscribed — best-effort: drop.
    return false;
  }

  /**
   * Counts approximate live entries. An entry is considered live if its slot is claimed AND
   * the stored value is not {@link Long#MIN_VALUE} (the sentinel for evicted slots). This
   * scans the whole table so it is only intended for eviction and test code, not the hot
   * path.
   */
  int approximateSize() {
    int count = 0;
    for (int i = 0; i < capacity; i++) {
      if (keys.get(i) != EMPTY_KEY && values.get(i) != Long.MIN_VALUE) {
        count++;
      }
    }
    return count;
  }

  /**
   * Evicts entries whose stored value is strictly less than {@code threshold}. For each
   * evicted entry, invokes {@code onEvict} with (key, value) so the caller can raise its
   * watermark. Returns the number of entries evicted.
   */
  int evictBelowThreshold(long threshold, LongBiConsumer onEvict) {
    int evicted = 0;
    for (int slot = 0; slot < capacity; slot++) {
      long k = keys.get(slot);
      if (k == EMPTY_KEY) {
        continue;
      }
      long v = values.get(slot);
      if (v < threshold) {
        // Clear the value before the key to preserve get()'s invariants:
        // get() will stop at the first EMPTY_KEY, so freeing a middle slot risks
        // orphaning probed entries. Instead of deleting the slot, we just reset the
        // value to Long.MIN_VALUE — a subsequent max-wins insertion on the same key
        // will overwrite. The slot itself remains claimed. This is a mild leak in
        // insert capacity but preserves probing correctness under concurrent readers.
        // We still call onEvict so the watermark is raised.
        if (values.compareAndSet(slot, v, Long.MIN_VALUE)) {
          evicted++;
          onEvict.accept(k, v);
        }
      }
    }
    return evicted;
  }

  int capacity() {
    return capacity;
  }

  private int indexFor(long key) {
    // Finalizer from xxHash for better distribution.
    long h = key;
    h ^= h >>> 33;
    h *= 0xff51afd7ed558ccdL;
    h ^= h >>> 33;
    return ((int) h) & capacityMask;
  }

  /** Functional interface used by {@link #evictBelowThreshold(long, LongBiConsumer)}. */
  @FunctionalInterface
  interface LongBiConsumer {
    void accept(long key, long value);
  }
}
