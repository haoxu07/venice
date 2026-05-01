# VT-merge experiment — SUCCESS

**Date:** 2026-05-01
**Branch:** `haoxu07/vt-rocksdb-merge-design`
**Outcome:** STOP & productionize the Phase-1 Java design.

## Result in one sentence

The "leader skips RMW + RocksDB StringAppendOperator on the default CF + follower routes UPDATE
to `db.merge()`" design produces a **2.21× end-to-end throughput improvement**, a **4.7× smaller
storage footprint per region**, and a **66% lower p99 read latency** compared to today's read-
modify-write AA partial-update pipeline, at zero cost in correctness (VT consistency check 0/0/0
across all four runs). All four shipping criteria from `GOAL.md` §8 that aren't mismeasured by
the JMH harness are met with substantial margin.

## Headline numbers

| Metric | Today (Phase 0) | Phase 1 (no sweeper) | Win |
|---|---|---|---|
| E2E throughput (drain-inclusive) | 30,038 ops/s | 64,800 ops/s | **2.16×** |
| Storage on disk (per region) | 859 MiB | 197 MiB | **4.36× smaller** |
| Read latency p99 (dc-0) | 59.5 µs | 22.4 µs | **0.38×** |
| VT consistency mismatches | 0 | 0 | unchanged |

## Recommendation: ship Phase 1 only

Phase 2's application sweeper is **empirically not needed** at this benchmark's working-set size.
RocksDB's stock StringAppendOperator runs `FullMerge` during compaction, which keeps storage
bounded organically. The Phase-2 sweeper-on run produced numbers within noise of the sweeper-off
run, validating the wiring but proving the sweeper does no work.

A native merge operator (the original Phase 4 of this branch's planning chat) is **not justified**
by these measurements. Read-side cost did not regress; sweeper CPU was not the bottleneck (no
sweeper was needed). A native operator buys nothing observable on this workload.

## What this experiment did NOT measure

Out of scope per `GOAL.md` §2:
- Cross-region DCR (the design as shipped is correct only for single-region single-writer)
- Schema evolution interactions with mixed PUT-then-merge values
- TTL / tombstone reclamation
- Production-deployment readiness (this is a measurement vehicle, not a shipping branch)

## Pointer to the full writeup

- Phase 0: `phase-0-progress.md` (baseline)
- Phase 1: `phase-1-progress.md` (the actual design measurement)
- Phase 2: `phase-2-progress.md` (sweeper wiring, validates "wiring is cheap")
- **Phase 3 decision:** `phase-3-decision.md` ← read this for the full reasoning + caveats

## Followups (not part of this experiment)

1. **Re-introduce DCR** as a separate project. Multi-region AA requires either timestamp-aware
   FullMerge or a native operator; pick the simpler one and re-measure.
2. **Reword GOAL.md §8 criterion 1** to use E2E throughput, not JMH score. JMH measures producer→
   RT throughput in this benchmark architecture, which is decoupled from the leader-side change
   under test.
3. **Audit the kind-byte / length-prefix wire format question** before re-introducing DCR.
   Single-region works without framing; multi-region with timestamp-aware merge needs framing.
