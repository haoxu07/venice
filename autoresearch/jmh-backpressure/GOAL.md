# JMH Producer Back-Pressure — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-03 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation; no
separate branch needed since change is benchmark-only) **Execution model:** Phased, fast unit-iteration. Each phase ends
with a benchmark run that verifies the back-pressure mechanism works end-to-end. **Estimated effort:** 1-2 sessions

---

## 0. Problem statement

The current `LeanActiveActiveIngestionBenchmark` has no producer back-pressure. The benchmark's `writer.update(...)`
loop runs as fast as the JMH producer can push records to RT. Downstream (leader RT consumer, follower VT consumer,
RocksDB drainer) is slower. Backlog grows linearly during the measurement window:

```
Producer rate = JMH score (~140K ops/s)
Consumer rate = E2E rate    (~107K ops/s for MERGE_OPERAND_SWEPT, ~30K for BASELINE)
Backlog/sec   = 33K / 110K
```

**Symptoms:**

- 20s measurement: backlog ≈ 660K (MERGE) / 2.2M (BASELINE) → drain 6s / 95s — fits in 450s timeout
- 150s measurement: backlog ≈ 5M (MERGE) / 16.5M (BASELINE) → drain 47s / 12 min — BASELINE exceeds timeout
- 180s+ measurement: BASELINE always times out, MERGE eventually does on iteration 2+

**Methodological consequence:** the benchmark cannot run for >~30s per iteration on BASELINE without exceeding its drain
timeout. We can't get statistically averaged numbers (3+ iterations × 60s+) for the BASELINE comparison.

## 1. Goal

Add a producer-side back-pressure mechanism to `LeanActiveActiveIngestionBenchmark` so that:

1. **Backlog stays bounded** under any measurement duration. Producer paces itself to consumer rate when consumer is
   slower.
2. **Both modes (BASELINE + MERGE_OPERAND_SWEPT) can run a 3-minute measurement** with 3 iterations averaged, without
   drain timeouts.
3. **JMH score and E2E throughput converge** to the same number (= sustained pipeline rate). The "max producer rate"
   headline becomes "max sustainable rate" — a more honest production-relevant metric.
4. **Per-call overhead of back-pressure is cheap** — should add < 5% latency to the producer hot loop. Specifically,
   `lastSeenStorageCount` lookup must not poll storage on every call.

## 2. Scope

### In scope

- Modify `LeanActiveActiveIngestionBenchmark.java`:
  - Add an `AtomicLong producedCount` updated in the producer loop
  - Add a `volatile long lastSeenStorageCount` updated by a background poller
  - Add a wait-loop in `runPartialUpdateWorkload` (and `runPutWorkload`, `runMixedWorkload`) that blocks if
    `producedCount - lastSeenStorageCount > MAX_BACKLOG`
- Background poller that updates `lastSeenStorageCount` every ~100ms
- Configurable `MAX_BACKLOG` via JMH `@Param` (default 100_000 records)
- No changes to the design-under-test (storage engine, SIT, materializing partition, etc.)

### Three approaches to try

The previous design discussion identified three back-pressure shapes. We'll implement Option A first (most flexible). If
A doesn't behave well, try B or C.

| Option | Approach                                                                            | When to try                                                   |
| ------ | ----------------------------------------------------------------------------------- | ------------------------------------------------------------- |
| **A**  | Lag-bounded gate: producer blocks when `produced - consumed > MAX_BACKLOG`          | First — preserves max-rate measurement when consumer keeps up |
| **B**  | Fixed-rate token-bucket: producer rate-limited to a known target                    | Fallback if A's polling is too noisy or too expensive         |
| **C**  | Per-iteration synchronous drain: each invocation waits for its 1000 records to land | Fallback if A and B both have issues                          |

### Cheap `lastSeenStorageCount` strategy

Goal: `< 1ms overhead per poll`, and poll only every 100ms (not per producer call).

**Approach 1 (preferred):** read `StorageEngine.getPartitionOffset(partitionId)` for each region's storage engine. This
returns the last-consumed RT offset per partition. Sum across partitions = total consumed records. Pure metadata read,
no storage lookup.

**Approach 2 (fallback):** read a sentinel key from `engineDC0.get(...)` periodically. The sentinel's seq encodes the
produced count at the time it was sent. Slower but already known to work.

### Out of scope

- Changing the design-under-test (this is benchmark methodology, not design changes)
- Multi-fork / multi-iteration JMH improvements beyond the closed-loop change
- Producer pipelining tuning (Kafka batch size, linger.ms, etc.)
- Read-side benchmarking (separate concern)

---

## 3. Phased plan

Each phase ends with a benchmark run that confirms back-pressure works.

### Phase A — Implement Option A (lag-bounded gate)

**Build:**

- Add `producedCount` AtomicLong; increment by 1000 in each invocation.
- Add `lastSeenStorageCount` volatile long.
- Add background poller (`ScheduledExecutorService`) that updates `lastSeenStorageCount` every 100ms by summing
  `engineDC0.getPartitionOffset(p)` across partitions.
- Add wait-loop in `runPartialUpdateWorkload` (and the other two workloads) that blocks if lag > MAX_BACKLOG.
- Add `@Param` for `maxBacklog` (default 100_000).

**Verify:**

- Compile + smoke-run with `-i 1 -r 20s -wi 1 -w 5s` for both BASELINE and MERGE_OPERAND_SWEPT.
- Both should complete cleanly. Check that JMH score now matches E2E rate (within ~10%).
- Drain at end of iteration should be bounded by MAX_BACKLOG / consumer_rate (~3s for BASELINE, ~1s for
  MERGE_OPERAND_SWEPT).

**Exit:** both modes run cleanly at 20s; JMH ≈ E2E; drain < 10s.

### Phase B — Verify at 60s measurement

**Build:** none (configuration change only).

**Verify:**

- Run both modes with `-i 3 -r 60s -wi 1 -w 20s`. 3 iterations averaged.
- JMH score variance across iterations < 10% (stability check).
- Drain at end of each iteration < 10s.
- VT-CHECK 0/0/0 and READ-VERIFY all-OK on both modes.

**Exit:** stable 3-iteration numbers at 60s; both modes complete; correctness checks green.

### Phase C — Extend to 3-minute measurement

**Build:** none.

**Verify:**

- Run both modes with `-i 3 -r 180s -wi 1 -w 30s`. 3 × 3-min iterations.
- JMH ≈ E2E within 5%.
- VT-CHECK and READ-VERIFY clean.
- Drain bounded.
- Compare numbers to Phase B's 60s — they should be very close (validates that the design-under-test is at steady
  state).

**Exit:** 3-minute measurements complete cleanly. Numbers stable. Final A/B comparison report.

### Phase D (only if needed) — Try Options B or C

If Option A has issues (excessive blocking, noisy polling, JIT artifacts from the wait loop), fall back to:

- Option B: replace the gate with a `RateLimiter.acquire()` per record at a fixed rate (e.g., 100K ops/s)
- Option C: add `waitForKeysVisibleOnBothRegions(lastKey, ...)` at the end of `runPartialUpdateWorkload` with a small
  timeout

---

## 4. Concrete design — Option A skeleton

```java
// JMH state additions
private final AtomicLong producedCount = new AtomicLong();
private volatile long lastSeenStorageCount = 0L;
private ScheduledExecutorService backpressurePoller;

@Param({ "100000" })
private long maxBacklog;

@Setup(Level.Trial)
public void setUp() {
  // ... existing setUp code ...

  backpressurePoller = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "jmh-backpressure-poller");
    t.setDaemon(true);
    return t;
  });
  backpressurePoller.scheduleAtFixedRate(
      this::updateLastSeenStorageCount,
      100, 100, TimeUnit.MILLISECONDS);
}

@TearDown(Level.Trial)
public void cleanUp() {
  // ... existing teardown ...
  if (backpressurePoller != null) {
    backpressurePoller.shutdownNow();
  }
}

private void updateLastSeenStorageCount() {
  try {
    long sum = 0;
    for (int p = 0; p < PARTITION_COUNT; p++) {
      OffsetRecord o = engineDC0.getPartitionOffset(p);
      if (o != null) sum += o.getLocalVersionTopicOffset();
    }
    lastSeenStorageCount = sum;
  } catch (Throwable ignored) {}
}

private void waitForBackpressure() {
  long lag;
  while ((lag = producedCount.get() - lastSeenStorageCount) > maxBacklog) {
    try { Thread.sleep(2); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
  }
}

// In runPartialUpdateWorkload (after the loop, before flush):
producedCount.addAndGet(NUM_RECORDS_PER_INVOCATION);
waitForBackpressure();
```

The block-loop sleeps 2ms between checks. A poller running every 100ms means at most ~2ms staleness in the lag estimate
— well under the producer's per-record latency.

---

## 5. Verification logistics

- Each phase commits its measurement results to `autoresearch/jmh-backpressure/data/phase-N.tsv`.
- Each phase ends with a one-paragraph progress note in `autoresearch/jmh-backpressure/phase-N-NOTES.md`.
- Final comparison written to `autoresearch/jmh-backpressure/RESULTS.md` once Phase C is done.
- Both modes (BASELINE, MERGE_OPERAND_SWEPT) run at each phase.
- Same JMH params per phase for both modes.

---

## 6. Decision criteria

A YES requires all four:

- ✅ Both modes complete cleanly at 3-min measurement (no drain timeouts)
- ✅ JMH score and E2E throughput within 10% of each other in steady state (validates that producer is actually
  rate-matched)
- ✅ VT-CHECK 0/0/0 and READ-VERIFY all-OK on both modes
- ✅ Per-iteration JMH scores have CV < 15% across the 3 iterations (statistical stability)

If any of these fails after trying Options A → B → C, halt and escalate.

---

## 7. Risks

| Risk                                                | How we'd see it                                              | Response                                                                       |
| --------------------------------------------------- | ------------------------------------------------------------ | ------------------------------------------------------------------------------ |
| Polling overhead too high                           | Steady-state E2E drops noticeably with poller running        | Increase poll interval (200ms, 500ms) or switch to Option B                    |
| `getPartitionOffset` returns wrong offset semantics | Lag estimate is wildly off; backlog grows or producer stalls | Switch to Approach 2 (sentinel-based) for `lastSeenStorageCount`               |
| 100K backlog too small                              | JMH score artificially low (producer always blocked)         | Tune up to 500K-1M                                                             |
| 100K backlog too large                              | Drain still long; defeats the purpose                        | Tune down to 10K-50K                                                           |
| Producer blocking interacts badly with JMH timing   | JMH score includes block time, distorts comparisons          | Document as "JMH score = sustained rate"; that's the intended semantics anyway |

---

## 8. Non-goals

- This is **not** about making the merge-operator design faster
- This is **not** about multi-fork JMH stability
- This is **not** about read-side benchmarking
- This is purely about making the benchmark methodologically sound for long measurement windows
