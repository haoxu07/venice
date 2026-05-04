# Phase C — Performance gate + backstop decision

**Date:** 2026-05-04 **Status:** PARTIAL (C-1 misses iter-1 ≥ 100K gate; C-2 conclusively shows the backstop is NOT
redundant)

## Run config

3 × 180s × 1 fork iterations of `LeanActiveActiveIngestionBenchmark` in `MERGE_OPERAND_SWEPT` mode with the native
filter enabled, system-property controlled. JVM heap 16 G.

```
JVM_OPENS="-XX:+IgnoreUnrecognizedVMOptions
  --add-opens=java.base/java.nio=ALL-UNNAMED
  --add-opens=java.base/sun.nio.ch=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED
  --add-opens=java.base/java.lang.invoke=ALL-UNNAMED
  --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED
  -DpubSubBrokerFactory=com.linkedin.venice.integration.utils.KafkaBrokerFactory"

PHASE_C_PROPS="-Dvt.merge.native.compaction.filter.enabled=true
  -Dvenice.jni.bridge.lib.path=.../libvenice_jni_bridge.so
  -Dvenice.rocksdb.fold.lib.path=.../libvenice_rocksdb_fold.so"

# C-1: backstop ON (default vt.merge.max.chain.length=64)
java -Xms16G -Xmx16G $JVM_OPENS $PHASE_C_PROPS -jar venice-test-common-jmh.jar \
  com.linkedin.venice.benchmark.LeanActiveActiveIngestionBenchmark \
  -p workloadType=PARTIAL_UPDATE -p designMode=MERGE_OPERAND_SWEPT \
  -f 1 -wi 1 -w 30s -i 3 -r 180s -foe true \
  -jvmArgs "-Xms16G -Xmx16G $JVM_OPENS $PHASE_C_PROPS"

# C-2: backstop OFF
# Add -Dvenice.server.vt.merge.max.chain.length=0 to PHASE_C_PROPS.
```

## Results

### C-1 (filter ON, backstop ON)

| Iter         | JMH (ops/s) | E2E (ops/s) | bp_wait_ms / 180s | operand_p99 | dc_writeAmp |
| ------------ | ----------: | ----------: | ----------------: | ----------: | ----------: |
| Warmup (30s) |     100,906 |          NA |                NA |          NA |          NA |
| 1            |      72,848 |      70,459 |  83,664 / 180,000 |          34 |        1.00 |
| 2            |      77,907 |      75,352 |  72,959 / 180,000 |          46 |        1.00 |
| 3            |      71,254 |      68,917 |  87,634 / 180,000 |          47 |        1.20 |

**JMH headline:** **74,003 ops/s** mean over 3 iter (Cnt=3). **Per-iter CV ≈ 4.9%** (very stable). **VT-CHECK:**
mismatches=0, missing=0, errors=0 ✅ **READ-VERIFY:** dc0 1000/1000, dc1 1000/1000 ✅ **Chain p99:** {34, 46, 47} —
bounded, all ≤ 50 ✅

### C-2 (filter ON, backstop OFF)

| Iter         | JMH (ops/s) | E2E (ops/s) | bp_wait_ms | operand_p99 | dc_writeAmp |
| ------------ | ----------: | ----------: | ---------: | ----------: | ----------: |
| Warmup (30s) |     127,163 |     121,068 |          0 |          39 |          NA |
| 1            |     144,047 |     142,854 |          0 |     **299** |        1.00 |
| 2            |     140,803 |     139,637 |          0 |     **551** |        1.90 |
| 3            |     140,558 |     140,558 |      3,830 |     **807** |        1.50 |

**JMH headline:** **142,194 ± 30,476 ops/s** mean over 3 iter. **VT-CHECK 0/0/0**, **READ-VERIFY 1000/1000**. **Chain
p99 grows monotonically** {39 → 299 → 551 → 807} across iters — clearly unbounded.

## Comparison: C-1 vs C-2 vs Phase D baseline

| Metric             | Phase D (no filter) | C-1 (filter ON, backstop ON) | C-2 (filter ON, backstop OFF) |
| ------------------ | ------------------: | ---------------------------: | ----------------------------: |
| Iter-1 JMH (ops/s) |              71,615 |                       72,848 |                       144,047 |
| Mean JMH (ops/s)   |              67,338 |                       74,003 |                       142,194 |
| Per-iter CV        |              10.49% |                         4.9% |                          3.7% |
| Chain p99 iter-1   |                  31 |                           34 |                           299 |
| Chain p99 iter-3   |                  11 |                           47 |                           807 |
| MAX_CHAIN config   |                  64 |                           64 |                  0 (disabled) |
| VT-CHECK           |               0/0/0 |                        0/0/0 |                         0/0/0 |
| READ-VERIFY        |           1000/1000 |                    1000/1000 |                     1000/1000 |

## Gate evaluation

### Phase C-1 gates (filter on, backstop on; required for overall PASS)

| GOAL §3 Phase C-1 exit criterion |  Met?  | Detail                                            |
| -------------------------------- | :----: | ------------------------------------------------- |
| Iter-1 throughput ≥ 100K ops/s   | **NO** | Measured 72,848. Hit 100K only during 30s warmup. |
| Sustained ≥ 90K ops/s            | **NO** | Measured 74,003 (3-iter mean).                    |
| CV < 15%                         |  YES   | 4.9% — very stable iter-over-iter.                |
| Chain p99 ≤ 50                   |  YES   | {34, 46, 47} — all under 50.                      |
| VT-CHECK 0/0/0                   |  YES   | Clean.                                            |
| READ-VERIFY 1000/1000            |  YES   | Both DCs.                                         |

### Phase C-2 gates (filter on, backstop off; only if C-1 passes)

C-1 did not pass, so C-2 is informational only. Even so:

| GOAL §3 Phase C-2 exit criterion |  Met?  | Detail                                                               |
| -------------------------------- | :----: | -------------------------------------------------------------------- |
| Iter-1 throughput ≥ 105K         |  YES   | 144,047 — far above target.                                          |
| Sustained ≥ 95K                  |  YES   | 142,194 mean.                                                        |
| Chain p99 ≤ 50                   | **NO** | 39 → 299 → 551 → 807 — chains grow unboundedly without the backstop. |

**The chain-p99 violation in C-2 is the headline finding.** Throughput targets are smashed, but chain depth grows
monotonically because compaction runs on RocksDB's natural cadence, not on demand. The native filter folds chains
_during_ compaction — but if compaction doesn't run often enough relative to the merge rate, chains accumulate. That's
exactly the failure mode the synchronous `ChainLengthBackstop` was added to prevent.

## Decision: KEEP `ChainLengthBackstop`

Phase C-2 was designed to test "is the backstop redundant when the native filter is in place?" The answer is a clear
**NO**:

1. With the backstop disabled, chain p99 grew unboundedly (39 → 299 → 551 → 807) within a 3-iter run.
2. Compaction filter runs are **not** triggered on every merge; they run during RocksDB's natural compaction schedule,
   which falls behind the merge rate under sustained load.
3. The synchronous backstop is the only mechanism that fires deterministically when chain depth hits MAX_CHAIN.

Per GOAL §9 non-goals: "DO NOT retire the synchronous Phase B backstop in this work-stream unless Phase C-2 explicitly
proves it's redundant." C-2 proves the OPPOSITE — the backstop is essential.

**Recommendation:** Do NOT delete `ChainLengthBackstop`.

## Why the C-1 throughput target wasn't met

The expected Phase C-1 win was that the native filter would amortize fold work across compaction threads, recovering the
iter-1 throughput from 72K (Phase D, all fold work synchronous) toward the pre-fix 110K. We measured 72,848 — basically
unchanged from Phase D's 71,615.

Likely causes:

1. **Compaction is not the bottleneck under steady-state.** With backstop ON, the synchronous fold-and-PUT path fires
   every MAX*CHAIN merges; that already does most of the fold work. The native compaction filter only sees values that
   \_escape* the backstop. So the win is small.
2. **Per-thread JNI attach amortization is real but not load-shifting.** The 309–467 ns per-call cost is fine, but the
   dominant cost is the Java fold (10–100 µs per fold) which the backstop already pays. The native filter doesn't reduce
   that work; it relocates it.
3. **The 100K Phase C-1 target was set against the pre-fix unbounded baseline.** Pre-fix iter-1 was 110K because chains
   were short. With chains bounded to 64, the read-back-and-fold cost on every MAX_CHAIN-th merge is structural — the
   filter can't eliminate it.

The native-filter-only run (C-2) shows the upper bound: 144K iter-1 if you accept unbounded chain growth. For most
production workloads the chain bound is non-negotiable, so the realistic operating point is C-1's 72K.

## What this work-stream did deliver

1. **Phase A — JNI bridge** — verified per-call cost ≤ 500 ns, 4× under the 2 µs gate.
2. **Phase B — working native filter** — solved 5 ABI traps (missing headers, RTTI, libstdc++ ABI, RTLD_LOCAL scope,
   shared_ptr ownership). The filter folds chains correctly during real RocksDB compaction. Byte-equivalence with the
   Java path verified across n ∈ {1..64}.
3. **Phase C-2 evidence** — proved the backstop is essential. This was the open question from
   `vt-merge-compaction-fold/RESULTS.md` ("Async backstop" follow-up); answered.

## Suggested next experiments (out of scope here)

1. **Increase MAX_CHAIN.** Raising MAX_CHAIN from 64 to e.g. 200 with the native filter on may let the filter pre-empt
   the backstop more often and recover throughput. Phase C ran with the default 64.
2. **Trigger compactRange more frequently.** A bounded async compactRange every N merges might let the native filter
   clean up before the backstop fires.
3. **Profile what the backstop actually costs.** If it's the read-then-PUT vs the fold itself, the read-back could be
   elided when the filter has a recent cache.

## Commit hash

`45e3d39ab` — `[chore][bench][dvc] Native A2 Phase C: throughput PARTIAL, backstop NOT redundant`
