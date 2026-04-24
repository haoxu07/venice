package com.linkedin.davinci.replication.rmdcache;

import java.util.concurrent.atomic.AtomicLongArray;


/**
 * A minimal lock-free bloom filter backed by an {@link AtomicLongArray} bit-set. Used by
 * {@link RmdTimestampCache} to short-circuit the "definitely not in DB" branch of the
 * AA-leader DCR path (branch A in the proposal).
 *
 * <p>This implementation trades absolute accuracy for thread-safety without a lock: CAS-free
 * writes via {@link AtomicLongArray#getAndUpdate}.</p>
 *
 * <p>Hashing: derives two 64-bit values from the key hash using a cheap bit-mixing technique
 * (Kirsch-Mitzenmacher double-hashing). False positive rate is controlled by the number of
 * expected insertions and the target {@code fpp}.</p>
 */
public final class PartitionBloomFilter {
  private final AtomicLongArray bits;
  private final int numBits;
  private final int numHashFunctions;

  public PartitionBloomFilter(long expectedInsertions, double fpp) {
    if (expectedInsertions <= 0) {
      throw new IllegalArgumentException("expectedInsertions must be > 0, got " + expectedInsertions);
    }
    if (fpp <= 0 || fpp >= 1) {
      throw new IllegalArgumentException("fpp must be in (0, 1), got " + fpp);
    }
    // Optimal number of bits: m = -n * ln(p) / (ln(2)^2)
    long m = (long) Math.ceil(-expectedInsertions * Math.log(fpp) / (Math.log(2) * Math.log(2)));
    // Cap at 2^31-1 bits to fit in an int index.
    if (m > Integer.MAX_VALUE - 64) {
      m = Integer.MAX_VALUE - 64;
    }
    if (m < 64) {
      m = 64;
    }
    this.numBits = (int) m;
    // Optimal number of hash functions: k = (m/n) * ln(2)
    int k = (int) Math.round((double) numBits / expectedInsertions * Math.log(2));
    this.numHashFunctions = Math.max(1, Math.min(k, 30));
    int longCount = (numBits + 63) >>> 6;
    this.bits = new AtomicLongArray(longCount);
  }

  /**
   * Inserts the given 64-bit key hash into the bloom filter. Idempotent and thread-safe
   * under concurrent callers.
   */
  public void put(long keyHash) {
    long h1 = keyHash;
    long h2 = mix(keyHash);
    for (int i = 0; i < numHashFunctions; i++) {
      long combined = h1 + (long) i * h2;
      int bitIndex = (int) Math.floorMod(combined, (long) numBits);
      int wordIndex = bitIndex >>> 6;
      long mask = 1L << (bitIndex & 63);
      bits.getAndUpdate(wordIndex, existing -> existing | mask);
    }
  }

  /**
   * Returns false only when the key is DEFINITELY NOT in the filter. Returns true when the
   * key is POSSIBLY in the filter (or definitely in, within the fpp bound).
   */
  public boolean mightContain(long keyHash) {
    long h1 = keyHash;
    long h2 = mix(keyHash);
    for (int i = 0; i < numHashFunctions; i++) {
      long combined = h1 + (long) i * h2;
      int bitIndex = (int) Math.floorMod(combined, (long) numBits);
      int wordIndex = bitIndex >>> 6;
      long mask = 1L << (bitIndex & 63);
      if ((bits.get(wordIndex) & mask) == 0L) {
        return false;
      }
    }
    return true;
  }

  /** Clears the filter. Not meant to be called under concurrent writers. */
  public void clear() {
    for (int i = 0; i < bits.length(); i++) {
      bits.set(i, 0L);
    }
  }

  // 64-bit finalizer from xxHash / Mix13 — gives good avalanche for a second hash value.
  private static long mix(long x) {
    x ^= (x >>> 33);
    x *= 0xff51afd7ed558ccdL;
    x ^= (x >>> 33);
    x *= 0xc4ceb9fe1a85ec53L;
    x ^= (x >>> 33);
    return x;
  }

  int numBits() {
    return numBits;
  }

  int numHashFunctions() {
    return numHashFunctions;
  }
}
