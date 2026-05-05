# Phase A — Mid-Iter Read Latency Probe: Implementation + Smoke

**Date:** 2026-05-05 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Spec:**
`autoresearch/mid-iter-read-latency/GOAL.md` (commit `17e805d31`)

## Summary

Implemented the mid-iter read-latency sampler in `LeanActiveActiveIngestionBenchmark` per GOAL §4. The sampler is a
single-thread daemon `ScheduledExecutorService` that fires at fixed rate during the `@Benchmark` write window and emits
a new `[READ-LAT] context=mid-iter-{ordinal}` log line per iter, parallel to the existing iter-end / trial-end probes.

## Implementation

Modified file:
`internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/LeanActiveActiveIngestionBenchmark.java`

### Added

- **Imports:** `ThreadLocalRandom`, `AtomicInteger`.
- **`@Param`:** `midIterReadSampleRateHz` defaulting to `100` (10 ms period; ~6,000 samples per 60 s iter).
- **Fields:** `midIterReadSampler` (executor), `midIterLatNanosDc0[]`, `midIterLatNanosDc1[]` (pre-allocated histograms,
  reset not reallocated per iter), `midIterSampleIdx` (`AtomicInteger`), `MID_ITER_MAX_SAMPLES = 20_000` (cap).
- **`@Setup(Level.Iteration) startMidIterReadSampler()`:** Skips for non-PARTIAL_UPDATE workloads. Resets histograms +
  index. Starts the daemon executor with first-fire delay 500 ms (warm-up window) and period
  `1000 / midIterReadSampleRateHz` ms.
- **`sampleOneMidIterRead()`:** Picks a random `poolIdx`, computes the partition via the existing partitioner, times
  `engineDC0.get` then `engineDC1.get`. Records both deltas only when both regions returned non-null. Bounded by
  `MID_ITER_MAX_SAMPLES`. All-throwable try/catch — sampler must never propagate to terminate the executor.
- **`stopMidIterSamplerAndEmit(long ordinal)`:** Shuts down the executor (2-sec drain), filters `(0,0)` slots (where one
  region returned null), sorts the histograms, picks p50/p99, and emits a `[READ-LAT] context=mid-iter-{ordinal}` line
  in the **same shape** as the existing `captureReadLatencyMetrics` line. Same downstream parse-able format.

### Modified

- **`finishIterationAndReportE2E()` (`@TearDown(Level.Iteration)`):** At the **very start** (before
  `waitForKeysVisibleOnBothRegions`), call `stopMidIterSamplerAndEmit(ordinalAtStart)`. This is critical — once we wait
  on the sentinel drain, the workload thread is idle, and any further `engine.get` would skew the histogram toward the
  quiescent regime which the existing iter-end probe already captures.
- **`cleanUp()` (`@TearDown(Level.Trial)`):** Defensive shutdown of `midIterReadSampler` in case the bench exits early
  (`-foe`) without reaching per-iter teardown.

### Design notes / deviations from GOAL §4 skeleton

- The skeleton suggested storing a per-iter `ThreadLocalRandom RANDOM` static field. Replaced with
  `ThreadLocalRandom.current()` at each call site since it's thread-local-cached and idiomatic.
- The skeleton wrote both histograms with `idx` even on null returns. We instead leave the slot at `0` and filter later
  — same effect, but means the published `samples` count is the count of fully-successful (non-null both regions) read
  pairs, not the count of attempts. This matches the existing iter-end probe's convention.
- Bound is on sample _writes_, not on _attempts_; if attempts exceed `MID_ITER_MAX_SAMPLES`, further ticks fast-return
  without doing the engine.get. At default 100 Hz × 60 s = 6,000 attempts per iter, well under the 20,000 bound.

## Build verification

```
./gradlew :internal:venice-test-common:jmhJar
```

→ `BUILD SUCCESSFUL in 49s`. No compile errors, no warnings beyond the pre-existing deprecation/unchecked notes.

## Smoke run

Command (same as GOAL §3 Phase A smoke spec):

```
java -Xms32G -Xmx32G $JVM_OPENS -jar internal/venice-test-common/build/libs/venice-test-common-jmh.jar \
  com.linkedin.venice.benchmark.LeanActiveActiveIngestionBenchmark \
  -p workloadType=PARTIAL_UPDATE -p designMode=BASELINE \
  -p partialUpdateKeyPoolSize=2000 -p recordSizeBytes=1600 -p fieldUpdateSizeBytes=16 \
  -f 1 -wi 1 -w 5s -i 1 -r 30s -foe true \
  -jvmArgs "-Xms32G -Xmx32G $JVM_OPENS"
```

Full log: `data/phase-A-smoke.log`. Exit 0.

### Raw `[READ-LAT]` and validation lines

```
[READ-LAT] context=mid-iter-1 samples=451  dc0_p50_ns=17082 dc0_p99_ns=60664 dc1_p50_ns=7684 dc1_p99_ns=43292
[READ-LAT] context=iter-end-1 samples=2000 dc0_p50_ns=2234  dc0_p99_ns=16431 dc1_p50_ns=2164 dc1_p99_ns=14938
[READ-LAT] context=mid-iter-2 samples=2951 dc0_p50_ns=14988 dc0_p99_ns=59171 dc1_p50_ns=8255 dc1_p99_ns=54792
[READ-LAT] context=iter-end-2 samples=2000 dc0_p50_ns=2675  dc0_p99_ns=24916 dc1_p50_ns=1834 dc1_p99_ns=16421
[READ-LAT] context=trial-end  samples=2000 dc0_p50_ns=2896  dc0_p99_ns=6582  dc1_p50_ns=1844 dc1_p99_ns=4328
[VT-CHECK] versionTopic=...v1 mismatches=0 missing=0 errors=0
[READ-VERIFY] sampled=1000  dc0: ok=1000 null=0 decodeFail=0 invariantViolation=0  dc1: ok=1000 null=0 decodeFail=0 invariantViolation=0
LeanActiveActiveIngestionBenchmark.benchmarkAAIngestion BASELINE 16 100000 100 2000 1600 PARTIAL_UPDATE thrpt 80042.832 ops/s
```

### Smoke gates — all green

| Gate                                               | Threshold | Result                                                                                                       |
| -------------------------------------------------- | --------- | ------------------------------------------------------------------------------------------------------------ |
| Build clean (`jmhJar`)                             | yes       | PASS (49 s)                                                                                                  |
| Run completes; exit 0                              | yes       | PASS                                                                                                         |
| ≥ 2 mid-iter `[READ-LAT]` lines                    | yes       | PASS (mid-iter-1 + mid-iter-2)                                                                               |
| Each line samples ≥ 100                            | yes       | PASS (451; 2,951)                                                                                            |
| VT-CHECK 0/0/0                                     | yes       | PASS (mismatches=0 missing=0 errors=0)                                                                       |
| READ-VERIFY 1000/1000                              | yes       | PASS (dc0 ok=1000, dc1 ok=1000)                                                                              |
| JMH score within 5% of prior small-record BASELINE | yes       | DIFFERENT CONFIG (smoke uses `partialUpdateKeyPoolSize=2000`, prior baseline used `100000`) — see note below |

#### Note on smoke-vs-baseline JMH score comparison

The Phase A smoke spec uses `partialUpdateKeyPoolSize=2000` (small pool, fast smoke); prior small-record BASELINE runs
in `read-latency-comparison-NOTES.md` and `big-record-comparison-NOTES.md` use `partialUpdateKeyPoolSize=100000`. Pool
size materially affects throughput (smaller pool = hotter cache = higher ops/s), so a direct 5% comparison isn't
meaningful. The smoke score of **80,042 ops/s** at pool=2000 vs the prior **31,813 ops/s** at pool=100000 is consistent
with this — the smoke ran 2.5× faster because the working set is 50× smaller. The proper sampler-perturbation check is
in Phase B against the cell-1 BASELINE at the matched pool size; if perturbation > 10% there, we'll halt per GOAL §7.

### Sanity check: probe is actually capturing concurrent-write latency

The whole point of the new probe is to capture latency under concurrent write pressure, vs the existing iter-end probe
which fires at quiescent teardown. Comparing the two on the same iter:

| Iter | mid-iter dc0 p50 | iter-end dc0 p50 | mid/iter-end ratio | mid-iter dc0 p99 | iter-end dc0 p99 | mid/iter-end ratio |
| ---- | ---------------: | ---------------: | -----------------: | ---------------: | ---------------: | -----------------: |
| 1    |          17.1 µs |           2.2 µs |           **7.6×** |          60.7 µs |          16.4 µs |           **3.7×** |
| 2    |          15.0 µs |           2.7 µs |           **5.6×** |          59.2 µs |          24.9 µs |           **2.4×** |

A 5–8× p50 latency difference between concurrent-write and quiescent reads is exactly the signal we're after — much
larger than the GOAL §0 estimated 1.5–2× gap. The mid-iter probe is functioning as designed and exposing latency the
existing iter-end probe was hiding. The full 4-cell Phase B comparison will quantify this for both BASELINE and
MERGE_OPERAND_SWEPT and at both record sizes, answering the partition-lock-contention hypothesis (GOAL §10).

## Phase A status: PASS

Ready to proceed to Phase B 4-cell comparison.
