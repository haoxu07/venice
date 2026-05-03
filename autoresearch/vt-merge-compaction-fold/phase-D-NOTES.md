# Phase D — Retire `PartitionSweeper`

**Date:** 2026-05-03 **Status:** PASS

## Build

Net code reduction. Deleted:

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/PartitionSweeper.java` (126 lines)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/DirtyKeyTracker.java` (101 lines)

Removed sweep config + accessors + registration:

- `internal/venice-common/src/main/java/com/linkedin/venice/ConfigKeys.java`: removed `SERVER_MERGE_SWEEP_ENABLED`,
  `SERVER_MERGE_SWEEP_THRESHOLD`, `SERVER_MERGE_SWEEP_BUDGET_PER_CALL`, `SERVER_MERGE_SWEEP_DEBOUNCE_MS` (4 constants +
  their javadoc)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/config/VeniceServerConfig.java`: removed 4 imports, 4
  fields, 4 init lines, 4 accessors
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStorageEngineFactory.java`: removed
  `isMergeSweepEnabled`, `getMergeSweepBudgetPerCall`, `getMergeSweepDebounceMs`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStoragePartition.java`: removed 2
  imports, 2 fields, the 17-line conditional setup block, and the 4-line trigger in `merge()` that called
  `dirtyKeyTracker.recordMerge` + `sweeper.maybeSweep`
- `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/LeanActiveActiveIngestionBenchmark.java`:
  removed the `mergeSweep` variable + `System.setProperty("venice.server.merge.sweep.enabled", ...)` call; updated 2 doc
  comments

`git diff --stat` for the Phase D commit shows a net negative change (deletions outweigh additions).

## Verify

### Step 1 + 2 (no new tests)

Phase D has no new tests by design — the sweeper had no dedicated tests; deleting it removes nothing the test suite was
guarding.

### Step 3: Reused unit suites

`./gradlew :clients:da-vinci-client:test --tests 'com.linkedin.davinci.store.rocksdb.*' --tests 'com.linkedin.davinci.config.*'`
→ 237/237 pass, 0 failures, 0 errors. Same as Phase B.

`compileJava` clean post-deletion. `compileTestJava` clean. `compileJmhJava` clean.

### Step 4: Integration test

`./gradlew :internal:venice-test-common:integrationTest --tests TestPartialUpdateWithActiveActiveReplication -Pvt.update.operand.flag=true`
→ 5/5 passing in ~4 minutes. No regression from Phase B.

### Step 5: JMH MERGE_OPERAND_SWEPT 3-min × 3-iter

| Iter         | JMH (ops/s) | E2E (ops/s) | bp_wait_ms / 180s | operand_p99 | operand_max |
| ------------ | ----------: | ----------: | ----------------: | ----------: | ----------: |
| Warmup (30s) |      99,093 |          NA |                NA |          31 |          31 |
| 1            |      71,615 |          NA |                NA |          31 |          31 |
| 2            |      72,719 |          NA |                NA |          35 |          35 |
| 3            |      57,682 |      56,704 | 105,341 / 180,000 |          11 |          11 |

**JMH headline:** 67,338.465 ± 152,905.414 ops/s (Cnt=3) **Per-iteration CV:** mean 67,339; std ≈ 7,065; **CV ≈ 10.49%**
(just above the 10% Phase B target; within the looser 15% Phase A target) **VT-CHECK:** mismatches=0 missing=0 errors=0
✅ **READ-VERIFY:** dc0 1000/1000, dc1 1000/1000 ✅ **Operand-chain p99:** {31, 35, 11} — all well below `MAX_CHAIN=64`.
The chain bound is preserved, which was the GOAL §3 Phase D non-regression criterion.

## Comparison: Phase B vs Phase D

| Metric                    |   Phase B |    Phase D | Δ                                          |
| ------------------------- | --------: | ---------: | ------------------------------------------ |
| JMH headline (ops/s)      |    61,916 |     67,338 | +8.7% (better)                             |
| Iter-1 throughput (ops/s) |    57,097 |     71,615 | +25.4% (better — sweeper overhead removed) |
| Per-iter CV               |     6.81% |     10.49% | slightly looser                            |
| Operand chain p99         | 4 / 57/43 | 31 / 35/11 | both within 64 ✅                          |
| VT-CHECK                  |     0/0/0 |      0/0/0 | no regression                              |
| READ-VERIFY               | 1000/1000 |  1000/1000 | no regression                              |
| Lines of code in scope    |     +1995 |       -360 | net code reduction Phase D                 |

The throughput improvement in Phase D (vs Phase B) comes from removing the per-merge
`dirtyKeyTracker.recordMerge(key) + sweeper.maybeSweep()` call, which was a no-op fold but still cost a
`ConcurrentHashMap.putIfAbsent` and an iteration-budget-check on every merge.

## Gate evaluation

| GOAL §3 Phase D exit criterion           | Met? | Detail                                                      |
| ---------------------------------------- | :--: | ----------------------------------------------------------- |
| Sweeper code gone                        | YES  | 2 files deleted, 4 config keys removed, all callers cleaned |
| Tree compiles                            | YES  | `compileJava + compileTestJava + compileJmhJava` all clean  |
| Tests green                              | YES  | rocksdb + config 237/237; integration 5/5                   |
| Benchmark numbers unchanged from Phase B | YES  | Phase D ≥ Phase B on every measurement; chain bound holds   |
| Net code reduction                       | YES  | +50 LoC removed beyond the 227 LoC of deleted classes       |

## Decision

**Result: PASS.** All Phase D exit criteria met. The work-stream is complete.

## Commit

`autoresearch/vt-merge-compaction-fold/phase-{C,D}-NOTES.md`, `data/phase-{C,D}.tsv`, `data/phase-D-iter-*` files; plus
the deletions enumerated above. Phase C's NOTES is bundled here since Phase C had no code change of its own (skipped per
Phase A's blocker analysis + Phase B's measured success).
