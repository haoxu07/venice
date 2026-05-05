# Mid-Iter Read Latency Probe — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-05 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation;
bench-only change) **Execution model:** Two phases. Phase A implements + smoke-tests; Phase B runs the 4-cell comparison
and writes NOTES. **Estimated effort:** 1–2 sessions; ~3 hours of implementation + 4 × ~25 min JMH runs (~2 hours of run
wall) + analysis.

---

## 0. Problem statement

The current `[READ-LAT]` probe in `LeanActiveActiveIngestionBenchmark` fires at `@TearDown(Level.Iteration)` — i.e.,
**after** the @Benchmark write window has ended and the back-pressure backlog has drained. It captures read latency in a
brief quiescent window between iters, not under concurrent write pressure.

Captured today (and thus the numbers in `read-latency-comparison-NOTES.md` and `big-record-comparison-NOTES.md`):

| Scenario              | What the probe measures     |
| --------------------- | --------------------------- |
| Producer thread state | Idle                        |
| Compaction state      | Settling — light contention |
| Memtable state        | Recently flushed            |
| GC state              | Mostly old-gen settled      |

Under production-realistic conditions, reads run **concurrently with writes**, with active compaction, memtable churn,
GC pressure, and I/O contention. The estimated gap is ~1.5–2× higher latency mid-ingestion than what we measured. We
need the actual number.

## 1. Goal

Add a low-overhead background sampler that fires during the @Benchmark write window — capturing read latency under
concurrent write pressure — and rerun the 4 measurement cells (BASELINE × MERGE × small × big record) to produce a
complete comparison.

1. **Sampler runs during ingestion** — same JVM, same engine, same partitions; reads run concurrently with writers.
2. **Low workload perturbation** — sampler rate bounded so it adds ≤ 1% of writer-thread CPU time.
3. **Sufficient statistical power** — ≥ 5,000 samples per iter for reliable p99.
4. **Same log/output format** — reuses the existing `[READ-LAT]` log line with a new `context=mid-iter-N` tag, so
   analysis tooling stays compatible.
5. **Correctness preserved** — VT-CHECK 0/0/0 and READ-VERIFY 1000/1000 unchanged.

## 2. Scope

### In scope

- New per-iter ScheduledExecutorService sampler (parallel to the existing back-pressure poller and RocksDB stats poller;
  same shape).
- Sampler picks random pool keys, calls `engineDC0.get(...)` and `engineDC1.get(...)`, records `System.nanoTime()`
  deltas.
- Sample rate: **100 reads/sec** (10 ms period). At 60 s measurement window = **6,000 samples per iter** — sufficient
  for p99.
- At @TearDown(Level.Iteration), sampler is stopped before the existing E2E + cfstats logging fires; latency histogram
  is emitted with `[READ-LAT] context=mid-iter-N samples=...`.
- New @Setup(Level.Iteration) to start the sampler.

### Out of scope

- Router-side latency (RPC + queue overhead). Direct engine.get only.
- p99.9 / p99.99 tail. Sample size and run duration don't support these reliably.
- Mixed JMH benchmark with reads as a separate @Benchmark method (different mechanism; bigger refactor).
- Production code changes — the sampler is bench-only.

## 3. Phased plan

### Phase A — Implement + smoke

**Build:**

- New field: `ScheduledExecutorService midIterReadSampler` (mirrors `backpressurePoller` shape).
- New field: thread-safe ring buffer for sampled latencies (e.g., `AtomicLongArray` with rolling index, or
  `ArrayList<Long>` guarded by `synchronized`). Bounded size = `maxSamplesPerIter` (default 10,000) to avoid unbounded
  growth.
- `@Setup(Level.Iteration)` annotation on a new `startMidIterReadSampler()` method:
  - Skip if `workloadType != PARTIAL_UPDATE`.
  - Reset the histogram.
  - Schedule `sampleOneMidIterRead()` at fixed-rate 10ms.
- New private `sampleOneMidIterRead()` method:
  - Pick a random `poolIdx ∈ [0, partialUpdateKeyPoolSize)`.
  - Compute key, partition, time `engineDC0.get()` and `engineDC1.get()`.
  - Record both latencies in the histogram if non-null returns.
- Modify existing `@TearDown(Level.Iteration)` `finishIterationAndReportE2E()`:
  - At very start, shut down `midIterReadSampler` (give it 2 sec to drain).
  - At end, after the existing `[E2E-ROCKSDB]` log line, call `emitMidIterReadLatencies(ordinal)` which sorts the
    histogram and emits a `[READ-LAT] context=mid-iter-{ordinal}` log line.
- New `@Param({"100"}) private int midIterReadSampleRateHz` so sample rate is configurable.

**Verify:**

- Build clean.
- Smoke run:
  `-p workloadType=PARTIAL_UPDATE -p designMode=BASELINE -p partialUpdateKeyPoolSize=2000 -p recordSizeBytes=1600 -f 1 -wi 1 -w 5s -i 1 -r 30s`
  (small-record, fast).
- Expected: 1 mid-iter `[READ-LAT]` line per iter (warmup + 1 measurement) with non-zero samples.
- Verify VT-CHECK 0/0/0 and READ-VERIFY 1000/1000 unchanged.
- Verify JMH score unchanged from prior small-record runs (within 5% — sampler shouldn't perturb writes meaningfully).

**Exit:**

- Smoke completes.
- Mid-iter `[READ-LAT]` lines emitted with sane numbers (p50 in expected range from prior estimates).
- VT-CHECK / READ-VERIFY clean.
- Throughput within 5% of prior baseline.

### Phase B — 4-cell comparison

**Build:** none (config + run only).

**Verify:** Run all 4 cells with full-scale params + extra time:

| Run | designMode          | recordSize | fieldUpdateSize | Pool    | maxBacklog | Window  | Wall time |
| --- | ------------------- | ---------- | --------------- | ------- | ---------- | ------- | --------: |
| 1   | BASELINE            | 1,600      | 16              | 100,000 | 100,000    | 3 × 60s |   ~15 min |
| 2   | MERGE_OPERAND_SWEPT | 1,600      | 16              | 100,000 | 100,000    | 3 × 60s |   ~15 min |
| 3   | BASELINE            | 102,400    | 1,024           | 50,000  | 10,000     | 3 × 60s |   ~25 min |
| 4   | MERGE_OPERAND_SWEPT | 102,400    | 1,024           | 50,000  | 10,000     | 3 × 60s |   ~25 min |

For each: capture both `[READ-LAT] context=iter-end-N` (existing — quiescent) and `[READ-LAT] context=mid-iter-N` (new —
concurrent).

Compose the comparison NOTES doc with a 4×2 table (4 cells × {iter-end, mid-iter}).

**Exit:**

- All 4 runs complete cleanly with VT-CHECK + READ-VERIFY green.
- Mid-iter and iter-end p50/p99 captured for all 4 cells.
- NOTES doc written: `autoresearch/mid-iter-read-latency/RESULTS.md` with the 4×2 latency table + analysis of
  "concurrent vs quiescent ratio".

## 4. Concrete design — sampler skeleton

```java
// New @Param
@Param({"100"})
private int midIterReadSampleRateHz;

// State
private ScheduledExecutorService midIterReadSampler;
private long[] midIterLatNanosDc0;
private long[] midIterLatNanosDc1;
private final AtomicInteger midIterSampleIdx = new AtomicInteger();
private static final int MID_ITER_MAX_SAMPLES = 20_000;
private static final ThreadLocalRandom RANDOM = ThreadLocalRandom.current();

@Setup(Level.Iteration)
public void startMidIterReadSampler() {
  if (workloadType != WorkloadType.PARTIAL_UPDATE) {
    return;
  }
  midIterLatNanosDc0 = new long[MID_ITER_MAX_SAMPLES];
  midIterLatNanosDc1 = new long[MID_ITER_MAX_SAMPLES];
  midIterSampleIdx.set(0);
  long periodMs = Math.max(1, 1000 / midIterReadSampleRateHz);

  midIterReadSampler = Executors.newSingleThreadScheduledExecutor(r -> {
    Thread t = new Thread(r, "jmh-mid-iter-read-sampler");
    t.setDaemon(true);
    return t;
  });
  midIterReadSampler.scheduleAtFixedRate(
      this::sampleOneMidIterRead,
      // first delay: wait briefly for the workload to warm up
      500, periodMs, TimeUnit.MILLISECONDS);
}

private void sampleOneMidIterRead() {
  try {
    int idx = midIterSampleIdx.getAndIncrement();
    if (idx >= MID_ITER_MAX_SAMPLES) {
      return;  // ring would lose samples; just drop
    }
    int poolIdx = ThreadLocalRandom.current().nextInt(partialUpdateKeyPoolSize);
    String key = AAIngestionWorkloadHelper.partialUpdatePoolPrePopulateKey(poolIdx);
    byte[] keyBytes = keySerializer.serialize(key);
    int partition = partitioner.getPartitionId(keyBytes, PARTITION_COUNT);

    long t0 = System.nanoTime();
    byte[] dc0 = engineDC0.get(partition, keyBytes);
    long t1 = System.nanoTime();
    byte[] dc1 = engineDC1.get(partition, keyBytes);
    long t2 = System.nanoTime();

    if (dc0 != null && dc1 != null) {
      midIterLatNanosDc0[idx] = t1 - t0;
      midIterLatNanosDc1[idx] = t2 - t1;
    }
  } catch (Throwable ignored) {
    // never let sampler throw
  }
}

// In existing finishIterationAndReportE2E(), at the very start:
if (midIterReadSampler != null) {
  midIterReadSampler.shutdownNow();
  try { midIterReadSampler.awaitTermination(2, TimeUnit.SECONDS); }
  catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
  emitMidIterReadLatencies(ordinal);  // helper that sorts and logs [READ-LAT]
}
```

The histogram emit reuses the existing pattern from `captureReadLatencyMetrics`: sort, pick percentiles, emit the
`[READ-LAT] context=mid-iter-N samples=...` line.

## 5. Test gates per phase

### Reused test assets (do not rewrite)

- All existing JMH benchmark correctness (VT-CHECK, READ-VERIFY)
- `MaterializingPartitionSmokeTest`, `ConcatBlobParserTest`, etc.
- The existing iter-end + trial-end `[READ-LAT]` probes — untouched

### New tests per phase

| Phase | Gate                                                                        | Threshold |
| ----- | --------------------------------------------------------------------------- | --------- |
| A     | Bench compiles; small-record smoke run completes                            | yes       |
| A     | Smoke produces ≥ 1 `mid-iter` `[READ-LAT]` line with non-zero samples       | yes       |
| A     | VT-CHECK 0/0/0; READ-VERIFY 1000/1000 unchanged                             | yes       |
| A     | JMH score within 5% of prior small-record BASELINE                          | yes       |
| B     | All 4 measurement cells complete cleanly                                    | yes       |
| B     | Each cell produces 4 `mid-iter` `[READ-LAT]` lines (warmup + 3 measurement) | yes       |
| B     | NOTES doc with 4×2 comparison table written                                 | yes       |

## 6. Verification logistics

- Per-phase TSV at `autoresearch/mid-iter-read-latency/data/phase-{A,B}.tsv`.
- Per-phase NOTES at `autoresearch/mid-iter-read-latency/phase-{A,B}-NOTES.md`.
- Final RESULTS at `autoresearch/mid-iter-read-latency/RESULTS.md`.
- Run logs in `/tmp/jmh-mid-iter-{cell}.log` (not committed; reproducible from branch HEAD).

## 7. Decision criteria

A YES on the overall goal requires all of:

- ✅ Sampler implemented with `≤ 1%` JMH-throughput perturbation (verify by comparing JMH score to recent prior runs)
- ✅ All 4 cells produce iter-end and mid-iter `[READ-LAT]` lines for all 3 measurement iters
- ✅ VT-CHECK + READ-VERIFY clean across all 4 runs
- ✅ Mid-iter / iter-end ratio computed for each cell; documented in RESULTS

Bonus (not required):

- Mid-iter / iter-end ratio is similar across BASELINE and MERGE → both modes pay similar concurrent-write tax
- OR ratio is significantly larger for MERGE → MERGE-specific concurrent-write penalty exposed

Either finding is informative; which one we get is the experimental question.

## 8. Risks

| Risk                                                                                                                 | How we'd see it                                                                              | Response                                                                                                               |
| -------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------- |
| Sampler perturbs writes (throughput drops > 5%)                                                                      | JMH score on Phase A smoke is below recent prior BASELINE                                    | Lower sample rate to 50 Hz or 20 Hz; if still bad, the bench itself is a confounder — flag in NOTES                    |
| Sampler hits idle keys → null returns skew                                                                           | `samples=N` is much less than expected                                                       | Verify all pool keys are pre-populated (they are); fall back to round-robin instead of random                          |
| Histogram allocation perturbs GC                                                                                     | Large allocation jitter in JMH score                                                         | Pre-allocate the long[] arrays once at @Setup(Trial), reuse with reset; avoid per-iter allocation                      |
| Sampler's `engine.get` competes with workload writers for partition lock (Materializing partition is `synchronized`) | Mid-iter latency artificially inflated; worse for MERGE which does fold inside the partition | This is a feature, not a bug — production reads also contend. Flag in NOTES that this is part of what we're measuring. |
| Engine.get from a non-handler thread returns differently than from a router                                          | Numbers don't directly translate to client-visible latency                                   | Document scope: "this measures engine-side latency, not router-side." Out of scope for this experiment.                |
| 6000 samples per iter is too few for stable p99                                                                      | High p99 variance across iters                                                               | Increase sample rate to 200 Hz or run longer iters; also report p90 as a less-noisy supplementary number               |

## 9. Non-goals

- **Not** measuring router-side latency. That requires a router + client harness; out of scope.
- **Not** measuring p99.9 / p99.99 tail. Sample size + run duration don't support reliably.
- **Not** changing the workload itself (no concurrent reads as part of the @Benchmark; the sampler is auxiliary).
- **Not** porting the sampler to production code. It's bench-only.
- **Not** running additional record sizes (10 KB, 1 MB, etc.). Just the existing 1.6 KB and 100 KB cells.

## 10. What we expect to learn

The experiment answers two specific questions:

1. **What's the actual concurrent-write read latency** (not the post-settle quiescent number we have today)?
2. **Does MERGE pay a disproportionate concurrent-write tax** vs BASELINE — i.e., is the partition lock contention worse
   for MERGE because each read does more work while holding the lock?

If MERGE's mid-iter / iter-end ratio is materially larger than BASELINE's, then the design has a hidden read-side cost
that surfaces only under load — and any production deployment should size compute capacity accordingly. If the ratios
are similar, the iter-end numbers we already have are a valid (if optimistic) characterization.

Either result calibrates the production decision.
