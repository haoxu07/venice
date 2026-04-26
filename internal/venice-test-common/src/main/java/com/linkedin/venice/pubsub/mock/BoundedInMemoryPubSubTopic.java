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
   *
   * <p>iter6: bumped to 100k because integration tests produce more than the original
   * 10k assumption (admin topics, heartbeats, version pushes all share a partition in the
   * in-memory broker and aggregate over the full BeforeClass lifecycle). At 10k some
   * partitions hit eviction during long multi-test runs which then caused slow consumers
   * to read empty.
   */
  public static final int DEFAULT_CAPACITY = 100_000;

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
   * Consumer-side hook: report the highest offset this consumer has READ. The bounded
   * topic uses this to decide which messages can be evicted when the queue is full.
   *
   * <p>iter6: previous versions tracked the MAX of all reported positions, which made
   * eviction too aggressive in multi-consumer scenarios (a fast consumer reading admin
   * messages would advance the watermark past offsets the slower consumer hasn't yet
   * read; eviction inside produce() would then drop those offsets and the slow consumer
   * would see Optional.empty()). With capacity bumped to 100k this rarely triggered in
   * the integration-test workloads, but switching to MIN-of-reported-positions is the
   * safer correct behaviour and adds no runtime cost.
   *
   * <p>Implementation: we track per-consumer positions in a small map keyed by
   * Thread.currentThread() (the bounded mock consumer adapter calls into this from
   * within its synchronized poll()). The aggregate watermark is the MIN of all reported
   * positions, so a slow consumer's pending offsets are NEVER evicted as long as it has
   * reported at least once. (The first reportConsumerPosition by a thread is always
   * recorded but the MIN doesn't drop; subsequent calls just monotonically advance that
   * thread's recorded position.)
   *
   * <p>If a consumer thread never reports (e.g. the consumer hasn't started yet), the
   * watermark stays at -1 (its initial value), so produce() blocks instead of evicting,
   * preserving the conservative-deadlock behaviour from earlier iterations.
   */
  public void reportConsumerPosition(int partition, long readPosition) {
    checkPartitionRange(partition);
    Partition p = partitions[partition];
    p.lock.lock();
    try {
      Long prior = p.consumerPositions.get(Thread.currentThread());
      if (prior == null || readPosition > prior) {
        p.consumerPositions.put(Thread.currentThread(), readPosition);
      }
      // Recompute MIN across all reported consumer positions for this partition.
      long min = Long.MAX_VALUE;
      for (Long pos: p.consumerPositions.values()) {
        if (pos < min) {
          min = pos;
        }
      }
      if (min != Long.MAX_VALUE && min > p.lowWaterMark) {
        p.lowWaterMark = min;
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
    /**
     * Highest offset known to be consumed by ALL reporting consumers (MIN across them).
     * -1 means "no consumer has reported yet".
     */
    long lowWaterMark = -1L;
    /**
     * iter6: per-consumer position map (keyed by reporting thread). The aggregate
     * lowWaterMark is the MIN of these. WeakHashMap so a finished consumer thread does
     * not pin its last reported position forever (which would block eviction
     * indefinitely).
     */
    final java.util.WeakHashMap<Thread, Long> consumerPositions = new java.util.WeakHashMap<>();

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
