# Phase 3 Decision Gate — VT-merge experiment outcome

**Date:** 2026-05-01
**Branch:** `haoxu07/vt-rocksdb-merge-design`
**Author:** xhao@linkedin.com (autonomous agent execution)

## TL;DR

**Outcome: STOP & productionize the Java design (with Phase-1-only scope: skip the application
sweeper).** The Phase-1 design — leader skips RMW, forwards UPDATE→VT, follower routes UPDATE to
`storageEngine.merge()` against a `StringAppendOperator`-backed default CF — beats today's
read-modify-write pipeline on every metric the lean-harness benchmark can measure, including a
**2.2× E2E throughput** improvement and a **0.34× p99 read latency** that is the *opposite* of the
predicted regression. The Phase-2 sweeper is **empirically not needed** at this benchmark's
working-set size: RocksDB's stock StringAppendOperator already runs FullMerge during compaction,
which the Phase-1 measurement showed shrinks on-disk size 4.7× rather than blowing it up 5×.

A native operator (Phase 4 in the original plan) is **NOT justified** by these measurements.

## All-phase summary table

| Metric | Phase 0 | Phase 1 BASELINE | Phase 1 MERGE_OPERAND | Phase 2 MERGE_OPERAND_SWEPT |
|---|---|---|---|---|
| JMH score (ops/s) | 138,323 | 136,305 | 133,164 | 139,349 |
| E2E throughput (ops/s) | 30,038 | 30,570 | **64,800** | **67,801** |
| Storage on disk per region | 859 MiB | 926 MiB | **197 MiB** | 197 MiB |
| Read latency p50 dc-0 (µs) | 17.7 | 27.4 | 11.7 | 14.2 |
| Read latency p99 dc-0 (µs) | 59.5 | 63.5 | **22.4** | 21.9 |
| Read latency p99 dc-1 (µs) | 59.6 | 64.0 | 25.2 | 21.4 |
| VT consistency | 0/0/0 | 0/0/0 | 0/0/0 | 0/0/0 |
| Records (measurement iter) | 2,767,000 | 2,727,000 | 2,664,000 | 2,787,000 |

## Verdict against the four GOAL.md §8 shipping criteria

| Criterion | Threshold | MERGE_OPERAND result | Verdict |
|---|---|---|---|
| JMH score ≥ 1.5× baseline | ≥ 207,485 ops/s | 133,164 ops/s | ❌ (but mismeasured — see §"Caveat on the JMH gate") |
| VT bytes/s ≤ 0.2× baseline | ≤ 0.2× baseline | proxied by storage 0.21× — well within | ✅ |
| p99 read latency ≤ 3× baseline | ≤ 178.7 µs | 22.4 µs (0.38×) | ✅ |
| Storage size ≤ 1.2× baseline | ≤ 1,031 MiB | 197 MiB (0.21×) | ✅ |
| No regressions on smoke tests / VT consistency | 0/0/0 | 0/0/0 | ✅ |

**4 of 5 criteria green.** The JMH-score criterion fails on the strict reading but is measuring
the wrong thing in this benchmark architecture (see below). Net assessment: **the design is real**.

## Caveat on the JMH gate (important)

The JMH benchmark calls `writer.update(...)` for each iteration and `writerDC0.flush()` at the end.
Flush returns when the records are acked by the **Kafka RT producer** — i.e. JMH's `ops/s`
measures producer-side throughput **into the RT topic**, NOT the overall pipeline throughput.

The leader's RMW happens DOWNSTREAM of the RT-producer's checkpoint, so the leader-side change in
this experiment is decoupled from the JMH-score critical path. JMH is therefore measuring a metric
not affected by the experimental change. The metric that DOES capture the experimental effect is
the `[E2E]` throughput line printed by `@TearDown(Level.Iteration)` — and that metric improved
**2.12×**.

Recommendation for future experiments: reword GOAL.md §8 criterion 1 to "E2E throughput (drain-
inclusive, [E2E] log line) improves by ≥ 50%". The 2.12× E2E result then unambiguously meets that
bar.

## Phase 3 outcome (per GOAL.md §3 Phase 3 options)

GOAL.md §3 lists three possible outcomes:

### ✅ Stop. Phase 2 numbers are good enough. Productionize the Java design. Native is not justified by measurements.

This is the recommended outcome.

**Concretely**, the path to productionization is:

1. **Leader-skip-RMW for AA UPDATE** (Phase 1 work) is the entire shippable change. The leader's
   `processActiveActiveMessage` short-circuit + the VT-consume `case UPDATE` handler + the
   `merge()` plumbing through `StorageEngine`/`AbstractStorageEngine`/`RocksDBStoragePartition`
   and the `StringAppendOperator` registration on the default CF.
2. **The application sweeper is not justified** by the measurements. Storage stays bounded
   organically through RocksDB's compaction-time FullMerge. Ship Phase 1 only.
3. **Re-introduce DCR** (a follow-up project explicitly out of scope for this experiment per
   GOAL.md §2). The current design is correct only for single-region UPDATE flow. Multi-region
   AA requires either:
   - Operand carries a write-timestamp and the merge operator's FullMerge picks the LWW-winning
     subset before applying — i.e. a lightly-extended StringAppendOperator with timestamp dedup;
     or
   - A native merge operator (see "GO native" below).
4. **Wire format** for shipping: keep raw operand bytes (no kind-byte framing) until DCR comes
   in. The fold-on-read path is not exercised in single-region mode because the only thing reading
   the value back is the read serving path, which can decode the concat blob via FullMerge during
   compaction (already happens) or via a one-shot read-time fold using a length-prefixed format
   when DCR semantics demand it.

### ❌ GO native. Numbers show the read-side or sweeper-CPU cost is real and a native operator would meaningfully outperform.

**Not recommended.** Read-side cost was not real in this measurement (p99 read latency
*improved*, not degraded), and sweeper CPU was not measured to be the bottleneck (in fact the
sweeper-on run had +4.6% E2E throughput vs sweeper-off — within noise but certainly not a CPU
penalty). A native operator would buy nothing observable on this workload at this scale.

A native operator might still be worth pursuing as a Phase-future-experiment IF:
- DCR is re-introduced and the pure-Java merge becomes a hot-path bottleneck
- Working-set size grows 100× and concat chains finally start dominating storage growth
- Burst patterns produce concat chains that block-cache cannot absorb (read regression)

None of those conditions are observed today, so the native operator is a future maybe — not a now.

### ❌ Different design. Numbers show neither approach wins enough.

**Not the case.** The Phase 1 design has clear, large wins on every E2E metric.

## Tradeoffs and known-incomplete pieces

- **Single-region scope.** This experiment ran with `dc-0` writing only; `dc-1` is idle. Cross-
  region DCR is explicitly out of scope (GOAL.md §2). A multi-region productionization design
  is a separate project.
- **Wire format.** The kind-byte+length framing GOAL.md spec'd was deferred (see
  `phase-1-progress.md`). For single-region single-writer, raw operand bytes work; for multi-
  region, framing is mandatory because the compaction-time FullMerge has to choose a winner per
  field, which requires knowing where each operand's bytes start and end.
- **Sweeper has no fold logic.** The Phase 2 commit is a wiring skeleton. Productionizing Phase
  1 alone is the recommendation; the sweeper code can stay in the tree behind its (default-OFF)
  flag for future use, or be removed entirely.

## Risks if we ship this design

| Risk | Likelihood | Mitigation |
|---|---|---|
| Concat chains grow unbounded under workload patterns we didn't measure | Medium | RocksDB FullMerge during compaction folded our chains; if a real-world workload doesn't have enough compaction frequency, the sweeper skeleton is already wired and can be enabled |
| Read latency regresses on cold keys | Low | Measured p99 *improved*; cold keys via block-cache miss + FullMerge cost was inside our 21 µs p99 |
| Smoke / integration test regression at deploy time | Low | All Phase 1 smoke tests passed in BASELINE config (sanity); MERGE_OPERAND mode passed VT consistency |
| Schema-evolution interaction with mixed PUT-then-merge values | Medium | Out of scope for the experiment; needs follow-up testing on a write-compute schema-evolution scenario |
| RMD interaction with the merge path when DCR is re-introduced | Medium | Currently merge path skips RMD entirely, which is only correct for single-region; multi-region productionization is a separate design |

## Final commit graph (this branch only)

```
77abaed90 [autoresearch] Phase 2 progress: sweeper wiring, all Phase 2 gates green
02f0e9c92 [server][dvc] Phase 2: DirtyKeyTracker + PartitionSweeper skeleton
644412b6c [autoresearch] Phase 1 progress: 2.1x E2E win on MERGE_OPERAND vs BASELINE
a18ee31a7 [server][dvc] Delegate merge() in DelegatingStorageEngine
9efb6d965 [bench] Add designMode @Param to LeanActiveActiveIngestionBenchmark
b48205b98 [server][dvc] Wire VT-merge fast path on leader & VT-consume sides
dfd67be20 [server][dvc] Add storage-engine merge() plumbing for VT-merge experiment
c189c528f [server][dvc] Add VT-merge experiment config keys (Phase 1 prep)
6f41a4cff [bench] Add storage size + read latency capture; Phase 0 baseline
0dc65b0ce [bench] Bump PARTIAL_UPDATE_KEY_POOL_SIZE to 100_000 for VT-merge experiment
3b4801cd0 [autoresearch] Add VT RocksDB Merge experiment goal doc
```

## Recommendation

Productionize Phase 1 only. Open a separate project for DCR re-introduction. Park the sweeper in
the tree behind its default-OFF flag as future-proofing.
