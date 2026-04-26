# Autoresearch summary — AA partial-update optimization

## Goal

Maximize E2E throughput (measured as `e2e_throughput_ops_per_sec` from `ActiveActiveIngestionBenchmark` PARTIAL_UPDATE workload) by iteratively optimizing Venice's partial-update / DCR implementation.

## Configuration

- **Workload:** PARTIAL_UPDATE, bounded 10K key pool, 100-entry `tags` map per key (pre-populated), 3 update types rotated by `seq % 3`:
  - ⅓ scalar `name` (string) via `setNewFieldValue`
  - ⅓ scalar `age` (int) via `setNewFieldValue`
  - ⅓ `tags` `AddToMap` (cycled over the same 100 pre-populated map keys to keep map size bounded)
- **Verify:** benchmark run (`-f 1 -wi 1 -w 5s -i 1 -r 20s`, JVM heap 32 GB)
- **Guard:** all 5 tests in `TestPartialUpdateWithActiveActiveReplication`
- **Initial scope:** 3 files under `clients/da-vinci-client/.../schema/merge/`
- **Expanded scope:** added 4 files under `clients/da-vinci-client/.../replication/merge/`

## Iterations executed

| iter | change | E2E ops/s | Δ vs baseline | outcome |
|---|---|---|---|---|
| 0 | baseline (unchanged) | 30,016 | — | reference |
| 1 | Pre-size `IndexedHashMap` in `setNewMapActiveElementAndTs` | 29,460 | -1.85% | discard (noise) |
| — | **Variance measurement** (3 no-change runs) | 29,609 / 32,968 / 30,012 | ±11% range | signal floor |
| — | **Profile round 1** (instrument `SortBasedCollectionFieldOpHandler`) | 29,343 | — | diagnostic |
| — | **Profile round 2** (instrument `MergeConflictResolver` / `MergeGenericRecord` / `RmdSerDe`) | 30,680 | — | diagnostic |

## Key empirical findings

### 1. Baseline throughput variance is ~6% (1σ), ~11% (range)

Three back-to-back identical runs produced 29,609 / 32,968 / 30,012 ops/s. This sets a **signal floor**: an iteration needs ~+12% (2σ) improvement to be distinguishable from noise with a single run. Micro-optimizations (1–5% wins) are unmeasurable without multi-run averaging.

### 2. Collection-merge code is only ~11% of server CPU

From profiling `SortBasedCollectionFieldOpHandler.handleModifyCollectionMergeMap`:

- Calls: **2.26M** over a measurement (~940K AddToMap ops × 2 servers, plus warmup)
- Total time: **21.0 seconds** cumulative CPU
- Average per call: **9.3 μs**
- Internal breakdown:

  | phase | ms | % of hmcmm |
  |---|---|---|
  | `buildTsMap` (createElementToActiveTsMap) | 7,055 | 33.5% |
  | `setNewMap` (rebuild + put) | 8,123 | 38.6% |
  | `sort` step | 251 | **1.2%** (negligible) |
  | other | 5,602 | 26.6% |

- Even eliminating `handleModifyCollectionMergeMap` entirely would yield **at most ~11% E2E improvement** — well within noise.

### 3. The real hot spot is value (de)serialization

From profiling `MergeConflictResolver.update` (the top of the server-side pipeline):

- **mcrUpdate** (total) = **51.7 μs** per call, **350 s** cumulative CPU
- **mgrUpdate** (partial-update application) = 3.6 μs per call, 24.7 s cumulative — **7.1%** of mcrUpdate
- **rmdDeser** = 1.4 μs per call, 8.7 s — **2.5%**
- **rmdSer** = 0.4 μs per call, 2.9 s — **0.8%**
- **Unaccounted** = 46.3 μs per call, **313 s** — **89.5%**

The unaccounted 89.5% is the remaining work inside `MergeConflictResolver.update`:
- `deserializeWriteComputeBytes(...)` — parse the incoming update bytes
- `prepareValueAndRmdForUpdate(...)` — read + deserialize the old **value** (~3 KB record with 100-entry map)
- `serializeMergedValueRecord(...)` — re-serialize the merged **value** back to bytes

Value serde dominates because the record is ~3 KB (100 map entries × ~30 bytes each) and gets fully (de)serialized on every update, even for single-scalar-field updates.

## Conclusion

Within the expanded scope, there is **no micro-optimization opportunity that can clear the noise floor** (~12% 2σ) for this workload. The dominant cost is full-record Avro (de)serialization, which is:

1. Largely delegated to fastAvro (library outside our scope)
2. Structural — driven by the need to serialize the whole record per update
3. Sensitive to record size — the 100-entry `tags` map contributes most of the ~3 KB

## Recommendations for future work (out of scope for this run)

1. **Partial-value serialization** — intercept value serde to only (de)serialize changed fields. Requires changes to schema-layer (RmdSerDe analog for value, split by field). Major effort, potentially large win (~30–50%).
2. **Field-level storage** — store each top-level field separately in RocksDB (column-family per field). Avoids full-record serde for scalar updates. Major effort, very large win potential.
3. **In-memory RMW cache** — cache the deserialized record for recently-updated keys (keyed on store-version-partition). Hot keys avoid repeated deser. Moderate effort; helps bounded-key workloads.
4. **Value compression tuning** — evaluate if RocksDB compression on small records is adding per-read/write overhead. Check compaction amplification.
5. **Consumer parallelism** — single-partition was chosen for this benchmark; production AA stores usually have more partitions. Re-run with realistic partition counts to see if ingestion throughput scales linearly.

## Artifacts

- `config.md` — run configuration
- `results.tsv` — per-iteration results
- `/tmp/profile_run.log` — first profiling run (SortBased* instrumentation)
- `/tmp/profile2_run.log` — expanded profiling (MergeConflictResolver/MergeGenericRecord/RmdSerDe)
- `/tmp/variance_runs.log` — 3 no-change baseline runs for variance
- `/tmp/baseline_verify.log`, `/tmp/baseline_guard.log` — baseline runs
- `/tmp/iter1_verify.log` — iteration 1 (reverted)

## Final git state

- Branch `haoxu07/aa-ingestion-benchmark` contains:
  - `ae3111795` — baseline (benchmark changes from previous session)
  - `0231336d3` — experiment: pre-size IndexedHashMap (retained for history)
  - `3c31a7da0` — revert of 0231336d3
- Working tree has the `TAGS_MAP_SIZE=100` change uncommitted in `ActiveActiveIngestionBenchmark.java` (benchmark-config change, not an experiment).
