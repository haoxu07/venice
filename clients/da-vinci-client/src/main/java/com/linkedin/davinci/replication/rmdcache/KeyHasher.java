package com.linkedin.davinci.replication.rmdcache;

/**
 * Fast 64-bit hash of byte[] key for use as the identifier in {@link RmdTimestampCache}.
 *
 * <p>We use a variant of the xxHash64 avalanche function — cheap, well-distributed, no
 * external dependency. Collisions are acceptable because the cache is only an accelerator:
 * if two different keys hash-collide, the worst case is one key's cache entry updates the
 * other's timestamp prematurely, which only affects the rate of RMD lookups, not correctness
 * (the B.2.b fallback always runs normal DCR on the fallback path).</p>
 */
public final class KeyHasher {
  private KeyHasher() {
  }

  // xxHash64-style constants.
  private static final long PRIME64_1 = 0x9E3779B185EBCA87L;
  private static final long PRIME64_2 = 0xC2B2AE3D27D4EB4FL;
  private static final long PRIME64_3 = 0x165667B19E3779F9L;
  private static final long PRIME64_4 = 0x85EBCA77C2B2AE63L;
  private static final long PRIME64_5 = 0x27D4EB2F165667C5L;

  public static long hash(byte[] key) {
    return hash(key, 0, key.length);
  }

  public static long hash(byte[] key, int offset, int length) {
    long h;
    int i = offset;
    int end = offset + length;
    h = PRIME64_5 + length;

    // 8-byte blocks: read an unaligned long directly for speed. Uses java.nio.VarHandle
    // would be cleanest but we stick to byte reads for JIT portability; the hot path is
    // still a short loop.
    while (i + 8 <= end) {
      long k = ((long) (key[i] & 0xFF))
          | (((long) (key[i + 1] & 0xFF)) << 8)
          | (((long) (key[i + 2] & 0xFF)) << 16)
          | (((long) (key[i + 3] & 0xFF)) << 24)
          | (((long) (key[i + 4] & 0xFF)) << 32)
          | (((long) (key[i + 5] & 0xFF)) << 40)
          | (((long) (key[i + 6] & 0xFF)) << 48)
          | (((long) (key[i + 7] & 0xFF)) << 56);
      k *= PRIME64_2;
      k = Long.rotateLeft(k, 31);
      k *= PRIME64_1;
      h ^= k;
      h = Long.rotateLeft(h, 27) * PRIME64_1 + PRIME64_4;
      i += 8;
    }
    // 4-byte tail.
    if (i + 4 <= end) {
      int k =
          (key[i] & 0xFF) | ((key[i + 1] & 0xFF) << 8) | ((key[i + 2] & 0xFF) << 16) | ((key[i + 3] & 0xFF) << 24);
      h ^= (k & 0xFFFFFFFFL) * PRIME64_1;
      h = Long.rotateLeft(h, 23) * PRIME64_2 + PRIME64_3;
      i += 4;
    }
    // Remaining bytes.
    while (i < end) {
      h ^= (key[i] & 0xFFL) * PRIME64_5;
      h = Long.rotateLeft(h, 11) * PRIME64_1;
      i++;
    }
    // Finalize.
    h ^= h >>> 33;
    h *= PRIME64_2;
    h ^= h >>> 29;
    h *= PRIME64_3;
    h ^= h >>> 32;
    return h;
  }
}
