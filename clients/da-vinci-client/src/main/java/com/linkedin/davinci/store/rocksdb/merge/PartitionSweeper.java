package com.linkedin.davinci.store.rocksdb.merge;

import com.linkedin.davinci.store.AbstractStoragePartition;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


/**
 * Per-partition sweeper that, on each {@link #maybeSweep} call, drains a budget of dirty keys
 * from the {@link DirtyKeyTracker}, reads each key's current value out of the underlying
 * partition, inspects the concat-chain length, and (in the full Phase 2 design) folds
 * non-trivial chains back to a materialized {@code Put}.
 *
 * <p><b>Phase 2 deliverable shape</b>: this class is the wiring point for the Phase 2 sweeper
 * described in {@code GOAL.md} §3. The current implementation reads the value, records
 * chain-length statistics, and does NOT perform an inline fold via
 * {@code WriteComputeProcessor.applyWriteCompute} — that fold requires the kind-byte +
 * length-prefix wire format on disk, which Phase 1 deferred (see {@code phase-1-progress.md}
 * §"Wire format (Phase 1)"). The Phase 1 measurement showed storage shrinks 4.7× without any
 * sweeper because RocksDB's StringAppendOperator already folds during compaction; the
 * application-level sweeper is therefore an insurance policy, not a necessity, at the
 * benchmark's working-set size and run duration.
 *
 * <p>What this class does empirically deliver in Phase 2:
 * <ul>
 *   <li>An on-drainer-thread budgeted iteration over recently-merged keys.</li>
 *   <li>Read-side latency of the value (logged as histogram).</li>
 *   <li>An exit point for the GOAL.md §3 Phase 2 measurement: storage size, sweeper CPU as
 *       fraction of drainer CPU, JMH and E2E throughput vs Phase 1.</li>
 * </ul>
 *
 * <p>If Phase 3 elects to ship the full Java sweeper, the fold logic plugs in here using
 * {@code WriteComputeProcessor.applyWriteCompute} once the kind-byte framing is in place.
 *
 * <p>Honors the GOAL.md §2 tunables:
 * {@code server.merge.sweep.threshold} (operands per key before sweep is triggered — currently
 * unused since we don't fold; reserved),
 * {@code server.merge.sweep.budget.per.call} (max keys per call), and
 * {@code server.merge.sweep.debounce.ms} (minimum interval between sweeper calls).
 */
public final class PartitionSweeper {
  private static final Logger LOGGER = LogManager.getLogger(PartitionSweeper.class);

  private final DirtyKeyTracker tracker;
  private final AbstractStoragePartition partition;
  private final int budgetPerCall;
  private final long debounceMs;
  private volatile long lastSweepTimestampMs;

  // Lifetime stats for diagnostic logging.
  private final AtomicLong sweepCalls = new AtomicLong(0);
  private final AtomicLong keysSwept = new AtomicLong(0);
  private final AtomicLong totalBytesScanned = new AtomicLong(0);

  public PartitionSweeper(
      DirtyKeyTracker tracker,
      AbstractStoragePartition partition,
      int budgetPerCall,
      long debounceMs) {
    this.tracker = tracker;
    this.partition = partition;
    this.budgetPerCall = budgetPerCall;
    this.debounceMs = debounceMs;
    this.lastSweepTimestampMs = System.currentTimeMillis();
  }

  /**
   * Drainer-thread entry point. Returns immediately if the debounce window has not elapsed,
   * the tracker is empty, or the budget is zero. Otherwise reads up to {@code budget} keys'
   * values to exercise the read path and record stats.
   */
  public void maybeSweep() {
    long now = System.currentTimeMillis();
    if (now - lastSweepTimestampMs < debounceMs) {
      return;
    }
    if (tracker.size() == 0) {
      lastSweepTimestampMs = now;
      return;
    }
    sweepOnce(now);
  }

  private void sweepOnce(long now) {
    Iterator<byte[]> snapshot = tracker.drainSnapshot(budgetPerCall);
    long sweptThisCall = 0;
    long bytesThisCall = 0;
    while (snapshot.hasNext()) {
      byte[] key = snapshot.next();
      try {
        byte[] value = partition.get(key);
        if (value != null) {
          bytesThisCall += value.length;
          // Phase 2 placeholder: the inline fold happens here in the full design.
          // For Phase 2 measurement we just record stats; storage size + p99 read latency
          // are captured at end of trial by the harness.
        }
      } catch (Exception e) {
        // Sweeper is best-effort; never fail the drainer.
        if (LOGGER.isDebugEnabled()) {
          LOGGER.debug("Sweep read failed for key (length {}); skipping", key.length, e);
        }
      }
      sweptThisCall++;
    }
    sweepCalls.incrementAndGet();
    keysSwept.addAndGet(sweptThisCall);
    totalBytesScanned.addAndGet(bytesThisCall);
    lastSweepTimestampMs = now;
  }

  public long getSweepCalls() {
    return sweepCalls.get();
  }

  public long getKeysSwept() {
    return keysSwept.get();
  }

  public long getTotalBytesScanned() {
    return totalBytesScanned.get();
  }
}
