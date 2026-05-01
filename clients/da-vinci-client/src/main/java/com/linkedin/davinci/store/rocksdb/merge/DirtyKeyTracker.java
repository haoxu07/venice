package com.linkedin.davinci.store.rocksdb.merge;

import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;


/**
 * Per-partition concurrent set of keys that have received {@code merge()} operations and may
 * have a non-trivial concat chain pending fold. Used by {@link PartitionSweeper} to know which
 * keys to inspect next.
 *
 * <p>The semantic shape: keys are recorded on every {@code merge()} call and lazily cleared
 * when the sweeper successfully folds the value back to a single materialized PUT. Duplicates
 * are deduped by key bytes (a key seen 100 times in 1ms produces one tracker entry).
 *
 * <p>Bounded by {@code maxSize}; once the bound is hit, further records are dropped (the
 * sweeper-CPU vs storage-bound trade-off lives here — dropping a key delays its sweep but
 * prevents unbounded memory growth in the tracker itself).
 *
 * <p>Threading: any thread can call {@link #recordMerge}; the sweeper thread reads via
 * {@link #drainSnapshot} which copies the keys and clears the underlying map.
 *
 * <p>Per the VT-merge experiment (see {@code GOAL.md} §3 Phase 2). Active when both
 * {@code server.vt.update.operand.enabled=true} AND {@code server.merge.sweep.enabled=true}.
 */
public final class DirtyKeyTracker {
  /**
   * The map's key is a wrapped byte[]; the value is unused (we use it as a concurrent set).
   * Wrapping is necessary because byte[] uses identity equality, which would defeat dedup.
   */
  private final ConcurrentMap<KeyHolder, Boolean> dirty;
  private final int maxSize;

  public DirtyKeyTracker(int maxSize) {
    this.maxSize = maxSize;
    this.dirty = new ConcurrentHashMap<>(Math.min(maxSize, 16_384));
  }

  /**
   * Record that a {@code merge()} happened on {@code key}. Caller may pass the same key
   * repeatedly; only one tracker entry results. Returns {@code true} if the key was newly
   * inserted, {@code false} if it was already present or the tracker was full.
   */
  public boolean recordMerge(byte[] key) {
    if (dirty.size() >= maxSize) {
      return false;
    }
    return dirty.putIfAbsent(new KeyHolder(key), Boolean.TRUE) == null;
  }

  /**
   * Atomically take a snapshot of up to {@code budget} dirty keys and remove them from the
   * tracker. Returns an iterator over the snapshot. The sweeper iterates this list and
   * processes each key.
   */
  public Iterator<byte[]> drainSnapshot(int budget) {
    if (dirty.isEmpty() || budget <= 0) {
      return java.util.Collections.emptyIterator();
    }
    int taken = 0;
    byte[][] result = new byte[Math.min(budget, dirty.size())][];
    Iterator<KeyHolder> it = dirty.keySet().iterator();
    while (it.hasNext() && taken < result.length) {
      KeyHolder holder = it.next();
      result[taken++] = holder.bytes;
      it.remove();
    }
    return Arrays.asList(taken == result.length ? result : Arrays.copyOf(result, taken)).iterator();
  }

  public int size() {
    return dirty.size();
  }

  /** Identity-by-content wrapper around byte[]. */
  private static final class KeyHolder {
    final byte[] bytes;
    final int hash;

    KeyHolder(byte[] bytes) {
      this.bytes = bytes;
      this.hash = Arrays.hashCode(bytes);
    }

    @Override
    public boolean equals(Object o) {
      if (!(o instanceof KeyHolder)) {
        return false;
      }
      return Arrays.equals(bytes, ((KeyHolder) o).bytes);
    }

    @Override
    public int hashCode() {
      return hash;
    }
  }
}
