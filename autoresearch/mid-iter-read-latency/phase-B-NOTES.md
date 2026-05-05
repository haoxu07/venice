# Phase B — Mid-Iter Read Latency: 4-Cell Comparison Run Notes

**Date:** 2026-05-05 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Phase A bench commit:** `063291a71` **Spec:**
`autoresearch/mid-iter-read-latency/GOAL.md` §3 Phase B

## Run inventory

All 4 cells executed sequentially (shared `/tmp/jmh-rocksdb-cfstats` and `/tmp/jmh-rocksdb-timeline` precludes parallel
runs). Between cells: `rm -rf /tmp/jmh-rocksdb-cfstats /tmp/jmh-rocksdb-timeline`.

|   # | designMode          | record |    pool | maxBacklog | Xmx | Wall (~ min) | exit | VT-CHECK | READ-VERIFY |    JMH score |
| --: | ------------------- | -----: | ------: | ---------: | --: | -----------: | ---: | -------: | ----------: | -----------: |
|   1 | BASELINE            |  1.6KB | 100,000 |    100,000 | 32G |           ~7 |    0 |    0/0/0 |   1000/1000 | 31,984 ops/s |
|   2 | MERGE_OPERAND_SWEPT |  1.6KB | 100,000 |    100,000 | 32G |           ~6 |    0 |    0/0/0 |   1000/1000 | 67,265 ops/s |
|   3 | BASELINE            | 100 KB |  50,000 |     10,000 | 64G |          ~10 |    0 |    0/0/0 |   1000/1000 |    396 ops/s |
|   4 | MERGE_OPERAND_SWEPT | 100 KB |  50,000 |     10,000 | 64G |           ~7 |    0 |    0/0/0 |   1000/1000 | 14,826 ops/s |

Total Phase B wall: ~30 minutes. Significantly under the GOAL §1 estimate of 80–100 min — the system was under low
external load, and the iter-end drain on the small-record cells happened quickly because the back-pressure backlog was
near zero at end-of-iter.

## Per-cell artifacts

For each cell, the relevant `[READ-LAT]` lines (3 mid-iter measurement + 1 mid-iter warmup, 3 iter-end measurement + 1
iter-end warmup, 1 trial-end = **9 lines per cell**) are committed at:

- `data/cell-1.tsv`
- `data/cell-2.tsv`
- `data/cell-3.tsv`
- `data/cell-4.tsv`

Full run logs (`/tmp/jmh-midlat-cell{1..4}.log`) are not committed — they're large (containing every JMH info line +
spark output for the VT consistency check) and reproducible from the bench commit. Re-run via the commands in
`RESULTS.md` §8.

## Smoke gates per cell

Each cell verified before moving to the next:

| gate                                            | required |           cell-1 |           cell-2 |             cell-3 |           cell-4 |
| ----------------------------------------------- | -------: | ---------------: | ---------------: | -----------------: | ---------------: |
| Exit 0                                          |      YES |             PASS |             PASS |               PASS |             PASS |
| 4 mid-iter `[READ-LAT]` lines (warmup + 3 meas) |      YES |             PASS |             PASS |               PASS |             PASS |
| 4 iter-end `[READ-LAT]` lines (warmup + 3 meas) |      YES |             PASS |             PASS |               PASS |             PASS |
| 1 trial-end `[READ-LAT]` line                   |      YES |             PASS |             PASS |               PASS |             PASS |
| Each mid-iter samples ≥ 100                     |      YES | PASS (951–5,965) | PASS (951–5,952) | PASS (1,119–6,524) | PASS (955–5,959) |
| VT-CHECK 0/0/0                                  |      YES |             PASS |             PASS |               PASS |             PASS |
| READ-VERIFY 1000/1000 (both regions)            |      YES |             PASS |             PASS |               PASS |             PASS |
| JMH score within 5% of prior no-sampler run     |      YES |     PASS (+0.5%) |     PASS (+4.7%) |       PASS (+2.9%) |     PASS (-2.9%) |

Per GOAL halt-conditions, all 4 cells avoided every halt trigger. No `BLOCKED-NOTES.md` written.

## Sampler observations

- **Sample count behavior:** mid-iter-1 (warmup) consistently had ~950–1,100 samples (10s warmup window with 500ms delay
  before first fire = ~9.5s × 100Hz = ~950 expected). Measurement iters had 5,950–6,520 samples (60s × 100Hz with 500ms
  delay = ~5,950 expected). All within the `MID_ITER_MAX_SAMPLES = 20,000` bound — bound was never hit.
- **Filter rate:** All cells reported `samples=N` matching the attempted-tick count, indicating both regions returned
  non-null on every attempt. The pre-populate phase guarantees pool keys are visible before the @Benchmark loop starts;
  this gate held.
- **No sampler exceptions:** No `[READ-LAT] stopMidIterSamplerAndEmit threw:` lines in any log. The all-throwable
  try/catch in `sampleOneMidIterRead()` was effective at suppressing transient errors (RocksDB compaction-window reads
  are normally robust but rare exceptions can fire during teardown).

## Throughput perturbation analysis

Comparison vs prior runs (no sampler, same params), summarized in `RESULTS.md` §4. All deltas in ±5%. The largest delta
(+4.7% on Cell 2) is well inside JMH's iter-to-iter noise band for that cell (reported error bar: ±209,862 ops/s on a
mean of 67,265). At the per-second-CPU level, the sampler's load is bounded:

```
100 ticks/sec × ~50 µs per tick (mean across regimes) = 5 ms / sec / core ≈ 0.5%
```

This is well under GOAL §7's 1% threshold for the YES gate.

## Status: PASS

All 4 cells completed cleanly. RESULTS.md written with the 4×2 latency comparison, ratio analysis, and
partition-lock-contention hypothesis verdict. Ready for Phase B commit.
