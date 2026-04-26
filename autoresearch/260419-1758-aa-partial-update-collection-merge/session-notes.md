# AA Ingestion Benchmark — Session Log & Pickup Notes

Session spanned 2026-04-17 → 2026-04-23. This doc is a pickup reference so future you (or someone else) can resume the benchmark work without re-deriving everything.

## Repo & branch

- **Local repo:** `/home/coder/Projects/venice`
- **Branch:** `haoxu07/aa-ingestion-benchmark`
- **Fork remote:** `haoxu07` → `https://github.com/haoxu07/venice`
- **Upstream remote:** `origin` → `https://github.com/linkedin/venice`
- **Committed baseline:** `ae3111795` — adds E2E timing + bounded-pool workloads
- **Working-tree uncommitted:** benchmark-config tweaks (`TAGS_MAP_SIZE`, partition count, VT consistency check, etc.) — not yet pushed

## Benchmark file

`internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java`

### Current configuration (end of session)

| Constant / setting | Value | Why |
|---|---|---|
| `NUM_RECORDS_PER_INVOCATION` | 1,000 | One "batch" per JMH invocation |
| `PARTIAL_UPDATE_KEY_POOL_SIZE` | 10,000 | Bounded pool → real RMW merges, not fresh creates |
| `PUT_KEY_POOL_SIZE` | 10,000 | Same idea for PUT AA-conflict testing |
| `TAGS_MAP_SIZE` | 100 | Pre-populated tags entries — stable record size |
| `DEFAULT_MAX_NUMBER_OF_PARTITIONS` | 10 | Cap on controller |
| `setPartitionCount(n)` | 2 (currently) | Tuned during session; swap to test scaling |
| `setHybridRewindSeconds(25L)` | 25s | Standard |
| `setHybridOffsetLagThreshold(1L)` | 1 | Standard |
| `setActiveActiveReplicationEnabled(true)` | — | AA on |
| `setWriteComputationEnabled(true)` | — | Partial updates enabled |
| `SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_ENABLED` | `true` | via `serverProperties(...)` on the builder |

### Workload types

- `PUT` — bounded pool, alternating DC0/DC1 producers for value-level DCR
- `PARTIAL_UPDATE` — bounded pool, 3-way field rotation (name, age, tags via AddToMap), cycles map keys to keep tags size constant
- `MIXED` — unbounded key pool; 40% PUT, 30% partial update, 20% map merge, 10% delete

### Per-invocation & per-iteration flow

1. Loop writes NUM_RECORDS_PER_INVOCATION records alternating DC0/DC1 producers
2. Sends 20 sentinels (PARTIAL_UPDATE) via DC1 to verify cross-region drain
3. Flushes both producers
4. `@Setup(Level.Iteration)` resets record counter + start timestamp
5. `@TearDown(Level.Iteration)` waits for sentinels visible on DC0 router; computes E2E ops/s = totalRecords / (startNanos → lastVisibleNanos); prints `[E2E]` line to stderr
6. `@TearDown(Level.Trial)` (PARTIAL_UPDATE only) runs VTConsistencyCheckerJob

## Key code modifications made this session

| Change | Where | Why |
|---|---|---|
| Pre-populate tags map + cycle map keys | `prePopulatePartialUpdatePool()` + `runPartialUpdateWorkload()` | Keep record size constant; no unbounded growth |
| Bounded pool for PUT & PARTIAL_UPDATE | Both `runXxxWorkload()` | Enable AA conflicts + real RMW |
| Sentinel-based drain verification | End of each workload loop | Replace 100ms-polling `waitForKeyIngested` with fresh-key visibility check |
| Iteration-level E2E timing | `@Setup/@TearDown(Level.Iteration)` | JMH doesn't count drain; we measure (startNanos → lastVisibleNanos) ourselves |
| Dual-region readers | `readClient` (DC0) + `readClientDC1` | DC1 reader needed for VT-check drain verification |
| `.setPartitionCount(1|2|10)` | Store creation | Used to isolate partition scaling effect |
| `.serverProperties(serverProps)` with AA/WC parallel flag | Builder | Enable merge-pool parallelism |
| VT consistency check via Spark job | `runVTConsistencyCheck()` | Detect DCR determinism bugs at trial teardown |
| `jmhJar { doFirst { ... } }` — merge META-INF/services | `build.gradle` | Spark's Parquet SPI was being overwritten by Avro in the fat-jar |
| `jmhImplementation project(':clients:venice-push-job')` | `build.gradle` | Pull VTConsistencyCheckerJob + Spark deps |
| `jdk.incubator.vector` INFO log for ActiveActiveStoreIngestionTask | `log4j2.properties` | Verify parallel processing activated |

## Scope files examined / modified

### Core benchmark
- `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java` — modified throughout

### Build/config
- `internal/venice-test-common/build.gradle` — added push-job dep + SPI merge
- `internal/venice-test-common/src/jmh/resources/log4j2.properties` — INFO logger for AA task

### Hot-path files (touched during profiling, all reverted)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/schema/merge/SortBasedCollectionFieldOpHandler.java`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/schema/merge/CollectionTimestampMergeRecordHelper.java`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/schema/merge/PerFieldTimestampMergeRecordHelper.java`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/replication/merge/MergeConflictResolver.java`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/replication/merge/MergeGenericRecord.java`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/replication/merge/RmdSerDe.java`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/replication/merge/MergeUtils.java`

### Reference files (read, not modified)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/IngestionBatchProcessor.java` — merge parallelism impl
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTask.java` — main ingestion loop
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/LeaderFollowerStoreIngestionTask.java` — `produceToLocalKafka`
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreBufferService.java` — drainer routing
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/config/VeniceServerConfig.java` — `isAAWCWorkloadParallelProcessingEnabled` etc.
- `internal/venice-common/src/main/java/com/linkedin/venice/ConfigKeys.java` — `SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_*`, `SERVER_CROSS_TP_PARALLEL_PROCESSING_*`, `STORE_WRITER_NUMBER`
- `clients/venice-push-job/src/main/java/com/linkedin/venice/spark/consistency/VTConsistencyCheckerJob.java` — Spark VT checker
- `clients/venice-push-job/src/main/java/com/linkedin/venice/spark/consistency/LilyPadUtils.java` — algorithm core
- `clients/venice-push-job/src/main/java/com/linkedin/venice/spark/consistency/LilyPadSnapshotBuilder.java` — per-DC snapshot builder
- Integration-test references: `TestPartialUpdateWithActiveActiveReplication`, `PartialUpdateAAMetadataTest`, `TestVTConsistencyCheckerJob`, `PartialUpdateTest`, `TestActiveActiveIngestion`

## How to run

```bash
cd /home/coder/Projects/venice
./gradlew :internal:venice-test-common:jmhJar --console=plain

# Spark on JDK 17 needs --add-opens for sun.nio.ch etc. These must be set on BOTH
# the outer JVM and the JMH-forked JVM (via -jvmArgs).
JVM_OPENS='--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.lang.invoke=ALL-UNNAMED --add-opens=java.base/java.lang.reflect=ALL-UNNAMED --add-opens=java.base/java.io=ALL-UNNAMED --add-opens=java.base/java.net=ALL-UNNAMED --add-opens=java.base/java.nio=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/sun.nio.ch=ALL-UNNAMED --add-opens=java.base/sun.nio.cs=ALL-UNNAMED --add-opens=java.base/sun.security.action=ALL-UNNAMED --add-opens=java.base/sun.util.calendar=ALL-UNNAMED'

java -Xms32G -Xmx32G $JVM_OPENS \
  -jar internal/venice-test-common/build/libs/venice-test-common-jmh.jar \
  'ActiveActiveIngestionBenchmark' \
  -p workloadType=PARTIAL_UPDATE \
  -f 1 -wi 1 -w 5s -i 1 -r 20s -foe true \
  -jvmArgs "-Xms32G -Xmx32G $JVM_OPENS" \
  > /tmp/aa_benchmark.log 2>&1

# Inspect
grep -E '\[E2E\]|\[VT-CHECK\]|\[COLL-MERGE\]|\[MERGE-PIPE\]|^Benchmark.*Mode|benchmarkAAIngestion.*thrpt' /tmp/aa_benchmark.log
```

## Measured results (snapshot, single-run each)

### Throughput vs partition count (PARTIAL_UPDATE, 100-entry bounded tags, AA/WC parallel ON)

| Partitions | Measurement E2E ops/s | Speedup vs 1p |
|---|---|---|
| 1 (parallel OFF baseline mean of 3 runs) | 30,863 | 1.00× |
| 1 (parallel ON) | 30,336 | 0.98× (noise) |
| 2 (parallel ON) | 61,756 | **2.04×** |
| 10 (parallel ON) | 102,709 | **3.39×** |

### Throughput vs record size (PARTIAL_UPDATE, 1 partition, parallel ON... caveats below)

| `TAGS_MAP_SIZE` | E2E ops/s (measurement) | Notes |
|---|---|---|
| 0 (scalar-only, no AddToMap) | 48,556 | Baseline |
| 10 | 45,134 | -7% |
| 100 | 29,430 | -39% |
| Unbounded (grows 0→~97) | 9,877 | -80% — steady-state effect, not peak |

### Baseline variance (same commit, no change, 3 runs)

- Run 1: 29,608 · Run 2: 32,968 · Run 3: 30,012
- Mean 30,863 · Std dev 1,834 · **CoV 5.9% · Range 11%**
- **Minimum detectable improvement with single-run:** ~12% (2σ)

### Profiling — where the server CPU goes per record

(From instrumentation on `MergeConflictResolver.update` and friends)

| Phase | Per-call avg | % of `mcrUpdate` |
|---|---|---|
| `mcrUpdate` total (top of merge pipeline) | **51.7 μs** | 100% |
| `mgrUpdate` (partial-update application) | 3.6 μs | 7.1% |
| `rmdDeser` | 1.4 μs | 2.5% |
| `rmdSer` | 0.4 μs | 0.8% |
| **Unaccounted** (mostly value (de)serialization) | **46.3 μs** | **89.5%** |

### Profiling — collection-merge breakdown (`handleModifyCollectionMergeMap`)

| Phase | Time | % of hmcmm |
|---|---|---|
| `createElementToActiveTsMap` (build TS map) | 7,055 ms | 33.5% |
| `setNewMapActiveElementAndTs` (rebuild map + put to record) | 8,123 ms | 38.6% |
| `sort` step | 251 ms | **1.2%** ← negligible |
| Other (KeyVal pair ArrayList + misc) | 5,602 ms | 26.6% |

**Takeaway:** collection-merge is ~11% of server CPU. Value serde is the real bottleneck (~90% of `mcrUpdate`).

## Why AA/WC parallel processing does NOT help 1 partition

Critical-path thread map:

```
Kafka consumer thread → AA/WC merge pool (8 threads, parallel)
    → back to consumer thread → produceToLocalKafka (async)
    → KafkaProducer I/O thread (network send)
    → storeBufferService.putConsumerRecord (enqueue)
    → Drainer thread (1 of 8, partition-pinned by hash) → RocksDB write
```

`StoreBufferService.getDrainerIndexForConsumerRecord()` uses `(topicHash + partition) % drainerNum` → one partition pins to one drainer. Parallelism downstream requires >1 partition.

`STORE_WRITER_NUMBER` default: 8 drainers. `SERVER_AA_WC_WORKLOAD_PARALLEL_PROCESSING_THREAD_POOL_SIZE` default: 8 merge workers.

## Serde analysis — the ~46 μs value serde cost

- **Library:** Apache Avro + linkedin/avro-fastserde (bytecode-generated specialized readers/writers per schema)
- **Cost source:** full record (~3 KB for 100-entry map) fully (de)serialized on EVERY update, even scalar-field updates
- **Object allocation per deser:** ~205 objects per record (GenericRecord, 100 map-key Strings, 100 map-value Strings, IndexedHashMap + internals, etc.)
- **CPU-bound** (not memory-bound) — records fit in L2, memory bandwidth barely touched; scales linearly with cores (2× at 2 partitions)
- **Already highly optimized** — fastAvro JIT-friendly; Vector API / C++ port would give ~1.5× at best due to JNI overhead

## Feasible speedup levers, ranked

| # | Approach | Effort | Win | Risk |
|---|---|---|---|---|
| 1 | Bump partition count (config) | hours | 2-4× | Low |
| 2 | Enable AA/WC parallel processing | single flag | 0× at 1p, compounds at >1p | Low |
| 3 | Reduce record size (bound collections, shorter field names) | days-weeks | 1.5-10× | Low |
| 4 | In-memory record cache for bounded key pool | weeks-months | 2-5× per partition | Medium (durability semantics) |
| 5 | Drainer batching (VT produce + RocksDB writes) | weeks | 1.5-2× | Medium |
| 6 | Partial-field serde (only decode touched fields) | **9-14 months platform project** | 4-6× for scalar updates | High (correctness, protocol, RMD coherence) |
| 7 | Zero-copy format (Cap'n Proto / FlatBuffers) | 1-2 years | 2-5× | Very high (platform-wide migration) |
| 8 | Native Avro via JNI | months | ~1.5× net | Medium-High |
| 9 | Vector API tuning of fastAvro | months | 5-10% | Medium — wrong tool for this workload |

**Recommended:** do 1-3 first (config + record-size), then 4 if still bottlenecked. Anything below that is a platform project.

## VT consistency check (PARTIAL_UPDATE)

Wired up at `@TearDown(Level.Trial)`:

1. Send 20 sentinels via each producer (40 total)
2. Wait for ALL sentinels visible on BOTH DC0 AND DC1 routers
3. Invoke `VTConsistencyCheckerJob.run(jobProps)` — local[*] Spark job
4. Read Parquet output, count `VALUE_MISMATCH`, `MISSING`, `ERROR` rows
5. Print `[VT-CHECK] ... mismatches=N missing=M errors=K` to stderr

### Lily Pad algorithm — what it does and what it misses

**Per-key pair walking**: for each (a from DC0, b from DC1), checks if `a.highWatermark` covered `b.upstreamRTPosition` AND vice versa. If yes (both had mutual visibility AT WRITE TIME), compares value hashes.

**Catches:** DCR determinism bugs (same input → different output)
**Misses:** stuck-consumer / lag-induced final-state divergence (because per-write HW snapshots may never find a matching pair)

**Measured in our run:** 0 mismatches / 0 missing / 0 errors across ~7.6M VT messages spanning ~84K unique keys. Necessary but not sufficient evidence of full correctness.

## Open questions & future work

1. **Augment consistency check** — add final-state mismatch detection (compare `dc0History.last().valueHash` vs `dc1History.last().valueHash` per key) to catch stuck-consumer divergence that Lily Pad misses.
2. **Try 30+ partitions** — find scaling ceiling (is it 8 drainers? other limits?).
3. **In-memory record cache prototype** — measure hit-rate impact on 10K bounded pool; likely 2-5× single-partition win if done.
4. **Profile drainer thread specifically** — instrument `processConsumerRecord` / drainer loop to pinpoint whether the 1p bottleneck is RocksDB, VT produce, or offset commit.
5. **Zipfian-skewed access pattern** — current cyclic workload has terrible recency locality; production is typically skewed. Test with skewed access to evaluate cache feasibility.
6. **Drainer batching proof-of-concept** — batch VT produces and RocksDB writes; measure.
7. **Document AA/WC parallel processing caveats** — it's only useful with >1 partition; currently nothing in Venice docs calls this out.

## Prior iterations (from autoresearch results.tsv)

See `results.tsv` in the same directory for the autoresearch loop log (iter 0 baseline, iter 1 pre-size IndexedHashMap attempt — discarded as noise).

## Relevant logs

All in `/tmp/` — may be cleaned at reboot. Move anywhere you want to preserve:
- `/tmp/baseline_verify.log`, `/tmp/baseline_guard.log` — baseline capture
- `/tmp/variance_runs.log` — 3 no-change runs for variance
- `/tmp/profile_run.log` — SortBased* instrumentation
- `/tmp/profile2_run.log` — MergeConflictResolver/MergeGenericRecord/RmdSerDe instrumentation
- `/tmp/aawc_parallel_run*.log` — AA/WC parallel processing trials
- `/tmp/p2_run.log`, `/tmp/p10_run.log` — 2-partition and 10-partition runs
- `/tmp/vt_check_run4.log` — successful VT consistency check run (mismatches=0)

## Quick restart checklist

To resume work in a fresh session:

```bash
cd /home/coder/Projects/venice
git status                          # confirm branch haoxu07/aa-ingestion-benchmark
git log --oneline -5                # check commits ae3111795 + later
cat autoresearch/260419-1758-aa-partial-update-collection-merge/session-notes.md
./gradlew :internal:venice-test-common:jmhJar --console=plain
# then use the run command above
```

## Contact notes for context

- User: xhao@linkedin.com
- This session: Claude Opus 4.7 (1M context), spanning 2026-04-17 to 2026-04-23
