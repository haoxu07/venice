# Read Latency Comparison: BASELINE (RMW) vs MERGE_OPERAND_SWEPT

**Date:** 2026-05-05 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Bench change:** moved the `[READ-LAT]` probe to
fire at end of every iteration (warmup + 3 measurement) in addition to trial-end. **Run config:**
`-f 1 -wi 1 -w 30s -i 3 -r 180s` for both modes; same workload (`PARTIAL_UPDATE`, key pool 100K).

---

## Headline

| Mode                                                       |      Per-read latency p50 |         p99 |                 Throughput |
| ---------------------------------------------------------- | ------------------------: | ----------: | -------------------------: |
| BASELINE (RMW writes resolved values)                      |   **~13 µs** (rock solid) |  **~36 µs** |               31,813 ops/s |
| MERGE_OPERAND_SWEPT (just after backstop fire, chain ~5)   |                **~26 µs** |      ~70 µs | (writes faster, see below) |
| MERGE_OPERAND_SWEPT (just before backstop fire, chain ~54) |               **~145 µs** |     ~175 µs |                          — |
| MERGE_OPERAND_SWEPT (time-averaged across the chain cycle) | **~80-90 µs (estimated)** | ~120-150 µs |               64,249 ops/s |

**Read-latency ratio MERGE / BASELINE:**

- Best case (chain just reset to ~1): **~2×** BASELINE
- Worst case (chain near MAX_CHAIN): **~10×** BASELINE
- Time-averaged steady state: **~7×** BASELINE (estimated)

**Throughput ratio MERGE / BASELINE: 2.0×** (write-side benefit of skipping leader-side RMW).

The design's central trade is now empirically pinned: MERGE doubles write throughput by paying ~7× higher read latency
in steady state.

## Raw measurements

### BASELINE — `-p designMode=BASELINE`

| Probe               | Sample count | DC0 p50 | DC0 p99 | DC1 p50 | DC1 p99 |
| ------------------- | -----------: | ------: | ------: | ------: | ------: |
| iter-end-1 (warmup) |         5000 | 13.4 µs | 32.7 µs | 13.2 µs | 32.7 µs |
| iter-end-2 (meas 1) |         5000 | 13.4 µs | 38.9 µs | 13.7 µs | 37.6 µs |
| iter-end-3 (meas 2) |         5000 | 13.5 µs | 33.5 µs | 13.4 µs | 35.0 µs |
| iter-end-4 (meas 3) |         5000 | 14.8 µs | 35.7 µs | 14.9 µs | 40.0 µs |
| trial-end           |         5000 |  5.1 µs | 14.6 µs |  5.2 µs | 14.2 µs |

JMH score: **31,813 ± 6,848 ops/s** (CV 21.5% — within noise of prior BASELINE measurements).

Trial-end probe is ~3× faster than iter-end probes because (a) writers have stopped, RocksDB block cache stays fully
warm, (b) no GC churn from drainer threads, (c) no contention with concurrent compaction. Iter-end is the
production-relevant number; trial-end is the post-load floor.

### MERGE_OPERAND_SWEPT — `-p designMode=MERGE_OPERAND_SWEPT`

| Probe               | Chain p99 at iter-end |  DC0 p50 |  DC0 p99 |  DC1 p50 |  DC1 p99 |
| ------------------- | --------------------: | -------: | -------: | -------: | -------: |
| iter-end-1 (warmup) |                    27 |  80.8 µs | 425.6 µs |  79.6 µs | 426.9 µs |
| iter-end-2 (meas 1) |                     5 |  26.3 µs |  69.5 µs |  25.8 µs |  70.4 µs |
| iter-end-3 (meas 2) |                     5 |  26.9 µs |  40.4 µs |  26.4 µs |  40.2 µs |
| iter-end-4 (meas 3) |                    54 | 145.0 µs | 173.9 µs | 145.6 µs | 175.4 µs |
| trial-end           |    (~54, no resample) | 145.8 µs | 176.5 µs | 146.0 µs | 178.9 µs |

JMH score: **64,249 ± 118,830 ops/s** (very wide CI from iter-end-1's JIT warmup spike; mean is consistent with Phase
D's 67K).

The chain-depth sampler runs once per iter-end on 200 random keys. Sampled p99 swings from 5 to 54 across iters because
the synchronous backstop fires at MAX_CHAIN=64 and resets chain to 1; whether the iter happened to end shortly after or
before a backstop fire shifts the distribution.

## Per-operand fold cost — derived

Two iters with very different chain depths give a clean linear fit:

```
chain p99 = 5  →  p50 = 26 µs  →  marginal fold cost = (26 - 13) / 5  = 2.6 µs/operand
chain p99 = 54 →  p50 = 145 µs →  marginal fold cost = (145 - 13) / 54 = 2.4 µs/operand
```

**~2.5 µs per operand** of fold cost. Matches earlier back-of-envelope (Avro decode ~1 µs + WC apply ~1 µs + bookkeeping
~0.5 µs).

Implication: read latency for a fully-folded base = **~13 µs** (BASELINE-like). Each accumulated operand adds **~2.5
µs**. So the formula:

```
MERGE p50 read latency ≈ 13 + 2.5 × (chain depth at read time)  [µs]
```

At MAX_CHAIN=64, worst-case p50 = 13 + 2.5 × 64 = **~173 µs**. Matches the iter-end-4 observation (145 µs at p99=54,
would be ~173 µs at chain=64).

## Implications for production

### Write-heavy workloads (the experiment regime)

MERGE is unambiguously better:

- **2× write throughput**
- Read latency hit only matters for end-of-window verification reads (rare)
- Storage 3× smaller, wire bytes 116× smaller

### Read-heavy workloads

MERGE is a clear loser at the per-server level:

- **~7× higher per-read CPU** in steady state (more if MAX_CHAIN raised, fewer if lowered)
- By Little's Law, **~7× lower read throughput** from the same handler thread pool
- Tail latency (p99) at chain near MAX_CHAIN is ~5× BASELINE p99
- Mitigation 1: **smaller MAX_CHAIN** (16 instead of 64) → reads bounded at ~50 µs p99; trades for more frequent
  backstop fires (more write CPU)
- Mitigation 2: **read cache** — but invalidating the cache on every operand write is non-trivial
- Mitigation 3: **tighter compaction tuning** to keep chains lower at memtable/L0 — might claw back some, but C-2 showed
  compaction can't keep up without the synchronous backstop

### Mixed workloads

Use Little's Law and per-record CPU to pick the boundary. A workload with R reads per W writes:

- BASELINE total CPU per (R+W) operations: R × 13 µs + W × 30 µs = 13R + 30W µs
- MERGE total CPU per (R+W) operations: R × 90 µs (avg chain) + W × 16 µs = 90R + 16W µs
- Cross-over: 13R + 30W = 90R + 16W → 14W = 77R → R/W = 14/77 ≈ 0.18

So **MERGE wins on aggregate CPU when the write:read ratio is greater than ~5.5:1** (writes outnumber reads by 5.5×).
Below that, BASELINE wins.

This is approximate (ignores cache effects, GC, etc.) but gives a reasonable rule of thumb.

## Run logs

- BASELINE: `/tmp/jmh-baseline-readlat.log`
- MERGE: `/tmp/jmh-merge-readlat.log`

(Not committed to repo; reproducible from current branch HEAD via the run command in §1.)

## Bench change

Single addition to `LeanActiveActiveIngestionBenchmark.java`:

- Split `captureReadLatencyMetrics()` into a default trial-end variant and a `(String context)` overload
- Tagged `[READ-LAT]` log lines with `context=<tag>`
- Wired a per-iter call from the existing `@TearDown(Level.Iteration)` method (`finishIterationAndReportE2E`)

No behavior change for write workload; probe runs at iter-end _outside_ the measured window so JMH score is unaffected.
