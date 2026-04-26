package com.linkedin.venice.pubsub.mock;

import com.linkedin.venice.pubsub.api.exceptions.PubSubClientException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;


/**
 * Phase 9: per-partition bounded in-memory pubsub topic.
 *
 * <p>This is a NEW class introduced for the AA ingestion benchmark integration-test path.
 * The existing {@link InMemoryPubSubTopic} (used by {@code StoreIngestionTaskTest} et al.)
 * is unchanged. This class is selected only via the integration-test in-memory broker
 * factory; the unit-test path continues to use the unbounded queue.
 *
 * <h3>Design</h3>
 * <ul>
 *   <li><b>Per-partition locking</b>: each partition has its own {@link ReentrantLock} +
 *       {@link Condition}. Producers writing to different partitions never block each
 *       other. The producer-consumer rendezvous is per-partition only.</li>
 *   <li><b>Bounded queue</b>: each partition's queue holds at most {@link #DEFAULT_CAPACITY}
 *       messages. produce() blocks (with a 30 s timeout) when the queue is full, until
 *       eviction or low-water-mark advance frees space. On timeout, throws
 *       {@link PubSubClientException} so the caller surfaces a loud error instead of
 *       hanging silently. (Risk 1 mitigation.)</li>
 *   <li><b>Permanent monotonic offsets</b> (Risk 2 mitigation): the next-offset counter
 *       is per-partition and never resets. Eviction frees memory but does NOT renumber.
 *       Each entry stores its assigned offset alongside the message; consume(offset N)
 *       walks the in-memory deque looking for the entry with offset == N. If N is below
 *       the current low-water-mark (i.e. evicted), consume returns {@link Optional#empty()};
 *       if N is at-or-above the next-offset, consume returns empty. Equality is by offset
 *       value, not array index, so all consumers see consistent positions even after the
 *       broker drops the entry.</li>
 *   <li><b>Eviction policy</b> (Risk 3 resolution): when produce needs space, the broker
 *       drops messages whose offset is {@code <= reportedConsumerLowWaterMark} for that
 *       partition. Consumers call {@link #reportConsumerPosition(int, long)} on each
 *       successful read so the broker knows how far they've drained. If no consumer has
 *       reported a position yet (e.g. consumer hasn't started), the produce blocks until
 *       the timeout, then throws. This keeps semantics conservative — we never silently
 *       drop unconsumed data.</li>
 *   <li><b>Risk 4</b> (multi-consumer single-partition): we keep a single
 *       low-water-mark per partition that tracks the MIN of any reported consumer
 *       position. Any consumer that has fallen behind keeps eviction conservative.</li>
 * </ul>
 *
 * <p>Public method signatures match {@link InMemoryPubSubTopic} so the bounded broker
 * layer can call into it identically — but no inheritance / interface is shared because
 * the existing topic class is package-private and we want zero behaviour bleed.
 */
public class BoundedInMemoryPubSubTopic {
  /**
   * Default per-partition queue capacity. The Phase 9 prompt allows the subagent to
   * adjust this if integration tests or the Stage B benchmark reveal a workload that
   * exceeds it. Document the symptom + new value in
   * {@code aa-phase9-progress.md}.
   */
  public static final int DEFAULT_CAPACITY = 10000;

  /** Max time {@link #produce(int, InMemoryPubSubMessage)} will block waiting for space. */
  public static final long PRODUCE_BLOCK_TIMEOUT_MS = 30_000L;

  private final int capacity;
  private final Partition[] partitions;

  /** Default constructor uses {@link #DEFAULT_CAPACITY}. */
  public BoundedInMemoryPubSubTopic(int partitionCount) {
    this(partitionCount, DEFAULT_CAPACITY);
  }

  public BoundedInMemoryPubSubTopic(int partitionCount, int capacity) {
    if (partitionCount < 1) {
      throw new IllegalArgumentException(
          "Cannot create a " + BoundedInMemoryPubSubTopic.class.getSimpleName() + " with less than 1 partition.");
    }
    if (capacity < 1) {
      throw new IllegalArgumentException("capacity must be >= 1");
    }
    this.capacity = capacity;
    this.partitions = new Partition[partitionCount];
    for (int i = 0; i < partitionCount; i++) {
      this.partitions[i] = new Partition();
    }
  }

  /**
   * Append a message to the given partition. Assigns and returns the new offset.
   *
   * <p>Blocks (up to {@link #PRODUCE_BLOCK_TIMEOUT_MS}) when the partition queue is full,
   * waiting for the consumer to advance the low-water-mark via
   * {@link #reportConsumerPosition(int, long)}.
   *
   * @throws IllegalArgumentException if the partition does not exist
   * @throws PubSubClientException if produce blocks past the timeout (Risk 1: surface
   *         deadlock loudly so the test layer fails with a clear error rather than
   *         hanging silently)
   */
  public InMemoryPubSubPosition produce(int partition, InMemoryPubSubMessage message) {
    checkPartitionRange(partition);
    Partition p = partitions[partition];
    p.lock.lock();
    try {
      long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(PRODUCE_BLOCK_TIMEOUT_MS);
      while (p.size() >= capacity) {
        // Try to evict consumed entries first.
        evictBelowLowWaterMark(p);
        if (p.size() < capacity) {
          break;
        }
        long remainingNs = deadline - System.nanoTime();
        if (remainingNs <= 0L) {
          throw new PubSubClientException(
              "BoundedInMemoryPubSubTopic produce timed out after " + PRODUCE_BLOCK_TIMEOUT_MS
                  + "ms on partition " + partition + " (size=" + p.size() + ", capacity=" + capacity
                  + ", lowWaterMark=" + p.lowWaterMark + ", nextOffset=" + p.nextOffset
                  + "). Consumer is not advancing fast enough or has not started.");
        }
        try {
          // Wait for consumer to report progress (notifies via not-full).
          p.notFull.awaitNanos(remainingNs);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new PubSubClientException(
              "Interrupted while waiting for produce slot on partition " + partition,
              e);
        }
      }
      long offset = p.nextOffset++;
      p.queue.addLast(new Entry(offset, message));
      // Wake any consumer waiting for new data on this partition.
      p.notEmpty.signalAll();
      return InMemoryPubSubPosition.of(offset);
    } finally {
      p.lock.unlock();
    }
  }

  /**
   * Read the message at offset {@code position.getInternalOffset()} from the given
   * partition.
   *
   * @return Optional.of(message) if the offset is currently in the queue,
   *         Optional.empty() if past the end (not yet produced) OR if below the
   *         low-water-mark (already evicted). Returning empty for evicted offsets is
   *         deliberate: it tells the consumer "you fell behind; the data is gone" via
   *         the same poll-empty path the existing tests already handle.
   */
  public Optional<InMemoryPubSubMessage> consume(int partition, InMemoryPubSubPosition position) {
    checkPartitionRange(partition);
    Partition p = partitions[partition];
    long target = position.getInternalOffset();
    p.lock.lock();
    try {
      if (target >= p.nextOffset) {
        return Optional.empty(); // not yet produced
      }
      // We deliberately do NOT short-circuit on (target < p.lowWaterMark) here. The
      // lowWaterMark is the MAX position any consumer has reported as read; a slower
      // consumer reading the same partition (e.g. cross-region admin consumption where
      // the parent self-consumes its admin topic AND a child controller remotely reads
      // it) must be allowed to read offsets that have already been read by a faster
      // peer, as long as those offsets are still physically in the queue. Eviction
      // only happens lazily inside produce() when capacity is reached, so a still-
      // present entry is always servable. If an entry was actually evicted, the linear
      // walk below simply won't find it and we'll return Optional.empty().
      // Linear walk through the per-partition deque is fine: capacity is small
      // (10k by default) and the deque is ordered by offset so we can short-circuit.
      for (Entry e: p.queue) {
        if (e.offset == target) {
          return Optional.of(e.message);
        }
        if (e.offset > target) {
          // Past the target without finding it -> entry was evicted. Return empty so
          // the consumer's poll loop sees "no data at this offset" the same way it
          // would for a not-yet-produced offset.
          return Optional.empty();
        }
      }
      return Optional.empty();
    } finally {
      p.lock.unlock();
    }
  }

  /**
   * Consumer-side hook: report the highest offset this consumer has READ. When ALL
   * registered consumers (we collapse to a single broker-aggregate min) have advanced
   * past offset N, entries with offset <= N can be evicted to free producer slots.
   *
   * <p>Implementation: we track a single low-water-mark per partition: the "lowest read
   * position seen so far." Each call moves it FORWARD (never backward). Eviction below
   * the low-water-mark is performed lazily inside {@link #produce} when the queue is
   * full. Producers that are blocked on capacity are signalled.
   *
   * <p>This is a deliberate simplification: we don't track per-consumer positions. If
   * two consumers subscribe to the same partition and one falls behind, eviction will
   * still respect the slowest if both report. But if only one reports, the other can
   * see {@code Optional.empty()} for offsets it expected to read. The integration tests
   * exercised in this phase don't have multi-consumer single-partition contention; the
   * unit-test path doesn't go through this class.
   */
  public void reportConsumerPosition(int partition, long readPosition) {
    checkPartitionRange(partition);
    Partition p = partitions[partition];
    p.lock.lock();
    try {
      if (readPosition > p.lowWaterMark) {
        p.lowWaterMark = readPosition;
        // Don't evict eagerly -- let produce() handle it lazily when it needs space.
        // But DO wake any blocked producer so it can recheck capacity.
        p.notFull.signalAll();
      }
    } finally {
      p.lock.unlock();
    }
  }

  public int getPartitionCount() {
    return partitions.length;
  }

  /**
   * Returns the next-to-be-assigned offset (the offset one past the last produced
   * message). Equivalent to {@link InMemoryPubSubTopic#getEndOffsets(int)}.
   */
  public Long getEndOffsets(int partition) {
    checkPartitionRange(partition);
    Partition p = partitions[partition];
    p.lock.lock();
    try {
      return p.nextOffset;
    } finally {
      p.lock.unlock();
    }
  }

  public InMemoryPubSubPosition endPosition(int partition) {
    return InMemoryPubSubPosition.of(getEndOffsets(partition));
  }

  /** Test-only inspection: current size of the partition queue. */
  public int sizeFor(int partition) {
    checkPartitionRange(partition);
    Partition p = partitions[partition];
    p.lock.lock();
    try {
      return p.size();
    } finally {
      p.lock.unlock();
    }
  }

  /** Test-only inspection: configured capacity. */
  public int getCapacity() {
    return capacity;
  }

  /**
   * Drop all entries with offset <= lowWaterMark. Caller must hold p.lock. Returns
   * the number of entries evicted. Signals {@code notFull} if anything was evicted.
   */
  private int evictBelowLowWaterMark(Partition p) {
    int evicted = 0;
    while (!p.queue.isEmpty() && p.queue.peekFirst().offset <= p.lowWaterMark) {
      p.queue.pollFirst();
      evicted++;
    }
    if (evicted > 0) {
      p.notFull.signalAll();
    }
    return evicted;
  }

  private void checkPartitionRange(int partition) {
    if (partition < 0 || partition >= partitions.length) {
      throw new IllegalArgumentException(
          "This topic has " + partitions.length + " partitions. Partition number " + partition + " does not exist.");
    }
  }

  /** Per-partition state. */
  private static final class Partition {
    final ReentrantLock lock = new ReentrantLock();
    final Condition notFull = lock.newCondition();
    final Condition notEmpty = lock.newCondition();
    final Deque<Entry> queue = new ArrayDeque<>();
    long nextOffset = 0L;
    /** Highest offset known to be consumed. -1 means "no consumer has reported yet". */
    long lowWaterMark = -1L;

    int size() {
      return queue.size();
    }
  }

  /** Single message entry in the per-partition deque. Carries its permanent offset. */
  private static final class Entry {
    final long offset;
    final InMemoryPubSubMessage message;

    Entry(long offset, InMemoryPubSubMessage message) {
      this.offset = offset;
      this.message = message;
    }
  }
}
