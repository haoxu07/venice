# Phase C — Flush-time filter (skipped)

**Date:** 2026-05-03 **Status:** SKIPPED

## Why skipped

Phase C in `GOAL.md` §3 is conditional:

> Trigger: only run if Phase B's chain-length p99 still climbs across iters because L0→L1 compactions don't happen
> frequently enough on this workload.

Phase B's measured chain-length p99 across 3 × 180s iters is **{4, 57, 43}** — strictly bounded under the `MAX_CHAIN=64`
configured ceiling. The trigger condition (chain-length p99 climbs across iters) is not met. RocksDB compaction cadence
is irrelevant to the bound because the synchronous backstop fires deterministically when chain depth hits MAX_CHAIN,
regardless of whether/when L0→L1 compaction runs.

Independently, Phase C as designed requires the same Java compaction-filter callback API that Phase A required — see
`phase-A-NOTES.md` for the rocksdbjni 9.11.2 blocker analysis. The "flush-time filter" is just a configuration knob on
the same `setCompactionFilterFactory` mechanism that requires C++ filter logic. Since the Phase B backstop already meets
the chain bound, this work-stream skips Phase C and proceeds directly to Phase D.

## What would change this

Two scenarios would re-open Phase C:

1. **A future rocksdbjni release exposes a Java callback API for compaction filters** (e.g., an
   `AbstractCompactionFilterJava` analogue of `AbstractCompactionFilterFactory` that invokes a Java `filter(...)` method
   per value). If/when that ships, the GOAL.md §4 Phase A skeleton becomes implementable, and Phase C's flush-time
   filter becomes a one-line option toggle.
2. **A workload pattern emerges where the Phase B backstop's amortized cost is unacceptable.** The current backstop
   fires once every MAX_CHAIN merges per key and does one read + fold + put. For workloads with a heavy hot-key tail,
   this could become a hot-spot. A flush-time compaction-filter would amortize the same fold work across the natural
   memtable→L0 flush cadence, shifting the work off the merge() call path. This is a re-tuning, not a correctness
   change.

## Verification artifacts

None: no JMH run, no code change, no commits associated with Phase C.

## Bundled commit

This NOTES file is committed as part of the Phase D commit (since Phase D is the next active phase), keeping the
per-phase NOTES timeline preserved without an empty Phase C commit.
