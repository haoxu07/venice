# Phase 0 Progress — Baseline measurement

## Goal recap

Establish a baseline for the BASELINE design mode: today's AA partial-update path on the lean harness with
`PARTIAL_UPDATE_KEY_POOL_SIZE=100_000`. No production code changes; only the bumping of the pool size and small
additions of metric instrumentation in the JMH harness.

## Code changes

| Commit        | File                                                   | Description                                                                                          |
| ------------- | ------------------------------------------------------ | ---------------------------------------------------------------------------------------------------- |
| `0dc65b0ce`   | `internal/.../AAIngestionWorkloadHelper.java`          | PARTIAL_UPDATE_KEY_POOL_SIZE 10_000 → 100_000                                                        |
| (this commit) | `internal/.../LeanActiveActiveIngestionBenchmark.java` | Add storage size + read latency capture in @TearDown(Trial), before harness.stop() deletes temp dirs |

## Benchmark numbers

| Metric                         | Value                  | Units |
| ------------------------------ | ---------------------- | ----- |
| JMH score (measurement iter)   | **138,323.0**          | ops/s |
| JMH score (warmup iter)        | 54,914.9               | ops/s |
| E2E throughput (measurement)   | **30,037.7**           | ops/s |
| E2E throughput (warmup)        | 19,413.4               | ops/s |
| Records in measurement iter    | 2,767,000              | count |
| Storage (dc-0 RocksDB on disk) | 900,961,598 (≈859 MiB) | bytes |
| Storage (dc-1 RocksDB on disk) | 900,923,026 (≈859 MiB) | bytes |
| Read latency p50 (dc-0)        | 17.7                   | µs    |
| Read latency p99 (dc-0)        | 59.5                   | µs    |
| Read latency p50 (dc-1)        | 19.1                   | µs    |
| Read latency p99 (dc-1)        | 59.6                   | µs    |
| VT consistency mismatches      | 0                      | count |
| VT consistency missing         | 0                      | count |
| VT consistency errors          | 0                      | count |
| Harness start() time           | 13,774                 | ms    |

Raw log: `data/raw/phase0-baseline-v2.log` Tabulated: `data/phase0-baseline.tsv`

## Decisions made

### Why two log files

The first run (`phase0-baseline.log`) was captured before I added storage-size + read-latency instrumentation. The
second run (`phase0-baseline-v2.log`) is the canonical one with both metrics. Both runs produced JMH scores within 5% of
each other (131K vs 138K), which I treat as expected JIT/warmup noise on a single-iteration benchmark.

### Why measure storage size before harness.stop()

The harness intentionally deletes its per-region temp dirs in stop() to keep the benchmark hermetic. For Phase-N
comparisons we need the on-disk size **just before teardown**. Adding the captureStorageMetrics() hook in
@TearDown(Trial) immediately before harness.stop() captures it without disturbing the workload window or the JMH score.
Same rationale for the read-latency probe.

### Why a 5,000-sample read-latency probe and not more

The probe runs single-threaded after the workload completes, with the engine quiescent. 5K samples is enough to get
tight p50 and p99 estimates without stretching teardown by tens of seconds.

## Gate evaluation

This is the baseline — no gate to pass. Subsequent phases will compare against these numbers per GOAL.md §3 / §8.
Specifically (from §8 success criteria):

- Phase 2 must hit JMH score ≥ 1.5× baseline = ≥ 207,485 ops/s
- Phase 2 must hit storage size ≤ 1.2× baseline = ≤ 1,080,153,917 bytes
- Phase 2 must hit p99 read latency ≤ 3× baseline = ≤ 178,650 ns ≈ 178.7 µs
- Phase 2 must hit VT bytes/s ≤ 0.2× baseline (TBD — capture in Phase 1)

## Issues encountered

None substantive. The first run's JMH score was 131K, the second's 138K — within JIT noise on a single-iteration
benchmark. We use the v2 number as canonical.

## Decision

Continue to Phase 1.
