# VT RocksDB Merge — Goal Document

**Owner:** xhao@linkedin.com
**Drafted:** 2026-05-01
**Branch:** `haoxu07/vt-rocksdb-merge-design`
**Execution model:** Manual — phased, with measurement after each phase. Decision gate before committing to native code.
**Estimated effort:** 2–3 weeks for Phase 0–1; longer if Phase 2 (native) is justified by measurements.

---

## 1. Goal

Validate that Venice's AA partial-update path can be redesigned so that **the leader does not read from RocksDB before producing to VT**, and the resulting design beats today's read-modify-write (RMW) pipeline on E2E throughput, VT bandwidth, and storage write amplification — without sacrificing read-side latency on hot keys.

The redesign moves the merge work out of the leader's write path and into the storage layer at every replica:

- **Leader**: receives `UPDATE` KME on RT, validates DIV, **forwards an operand directly to VT without any RocksDB lookup or DCR**. (DCR is intentionally skipped in this experiment — see §2.)
- **Follower / leader-local replica**: consumes the `UPDATE` from VT and writes it via `db.merge(key, operand)` against RocksDB's `StringAppendOperator`. No full-record materialization on the write path.
- **Storage compaction**: RocksDB's StringAppendOperator concatenates operands during compaction. Operand chains stay bounded by application-level sweeping (see §3).
- **Read path**: `db.get(key)` returns either a fully-materialized record (kind=0x00) or a concat blob (kind=0x01). The reader handles both shapes; concat blobs are folded inline using existing `WriteComputeProcessor` apply code.

**Success criterion:** on the existing partial-update workload (`LeanActiveActiveIngestionBenchmark` with `workloadType=PARTIAL_UPDATE`), the redesigned pipeline shows:

- ≥ 1.5× improvement in JMH score (producer-side throughput) — leader has no read, no Avro decode, no DCR
- ≥ 5× reduction in VT bytes/s — operand size vs full-record PUT bytes
- p50 read latency on materialized keys within 10% of today's read latency (no overhead for the hot path)
- p99 read latency on concat-form keys within 3× of today's read latency (acceptable degradation on the rare cold-path)
- Steady-state RocksDB on-disk size on the follower replica within 1.2× of today's size at the same operation count (sweeper bounds storage growth)

If those numbers hold, this becomes the foundation for a cross-region AA design (re-introducing DCR in a Phase-3 follow-up), and provides empirical justification for whether to commit to a native merge operator (Phase 4).

---

## 2. Scope

### In scope

- **Single-region workload** — `AAIngestionWorkloadHelper` writes PARTIAL_UPDATE KMEs into only one region's RT topic (e.g. `dc-0`); `dc-1` is idle for this experiment. The two-region lean-harness infrastructure (two Kafka brokers, two RT topics, leader consuming both) is unchanged, but the workload side ensures only one stream of UPDATEs ever reaches the leader. This makes "skip DCR" trivially correct: there is only one writer, so no conflict can exist.
- Existing `LeanActiveActiveIngestionBenchmark` with `workloadType=PARTIAL_UPDATE` as the workload generator, **gated by a new mode flag** `-p designMode=BASELINE|MERGE_OPERAND` so baseline and experimental runs share identical workload and measurement code (no separate JMH class)
- **Production code modifications** (in `clients/da-vinci-client/`):
  - `ActiveActiveStoreIngestionTask` (or a feature-flag-gated subclass): for RT `UPDATE` messages, bypass `MergeConflictResolver.update()` and produce `MessageType.UPDATE` directly to VT (today the leader produces only `PUT` / `DELETE` to VT)
  - `StoreIngestionTask` (and/or `LeaderFollowerStoreIngestionTask`): on the **follower** VT-consumption path, recognize `MessageType.UPDATE` and route to `storageEngine.merge(...)` instead of `storageEngine.put(...)` — today the VT-side dispatch only handles PUT/DELETE because all UPDATEs are pre-resolved at the leader
  - `AbstractStorageEngine` / `AbstractStoragePartition`: new `merge(int partitionId, byte[] key, ByteBuffer operand)` method
  - `RocksDBStoragePartition.getStoreOptions(...)`: register `StringAppendOperator` on column-family options when the new mode is enabled
  - `KafkaMessageEnvelope` / `Update` schema: confirm UPDATE on VT is wire-compatible (the schema already permits it; today's restriction is by convention, not enforcement)
- New classes (in production code, behind feature flag):
  - `MaterializingRocksDBStoragePartition` extends `RocksDBStoragePartition` — kind-byte handling, both materialized and concat-form values
  - `DirtyKeyTracker` — per-partition concurrent queue
  - `PartitionSweeper` — drainer-thread-resident sweeper
- Measurement plumbing: emit JMH score, E2E ops/s, VT bytes, RocksDB on-disk size per second, sweeper CPU, concat-chain-length histogram

### Production code touch points (summary)

| File | Change |
|---|---|
| `clients/da-vinci-client/.../ActiveActiveStoreIngestionTask.java` | New code path that, when feature-flag is on, forwards UPDATE→VT without RMW |
| `clients/da-vinci-client/.../StoreIngestionTask.java` (and/or `LeaderFollowerStoreIngestionTask.java`) | Allow `MessageType.UPDATE` on VT consumption; dispatch to `storageEngine.merge()` |
| `clients/da-vinci-client/.../AbstractStorageEngine.java` | Add `merge(...)` passthrough |
| `clients/da-vinci-client/.../AbstractStoragePartition.java` | Add `merge(...)` abstract method |
| `clients/da-vinci-client/.../RocksDBStoragePartition.java` | Implement `merge(...)`; register `StringAppendOperator` |
| `clients/da-vinci-client/.../MaterializingRocksDBStoragePartition.java` | NEW — kind-byte + sweeper integration |
| `clients/da-vinci-client/.../rocksdb/merge/DirtyKeyTracker.java` | NEW |
| `clients/da-vinci-client/.../rocksdb/merge/PartitionSweeper.java` | NEW |
| `clients/da-vinci-client/.../config/VeniceServerConfig.java` | New feature-flag config keys: `SERVER_VT_UPDATE_OPERAND_ENABLED`, `SERVER_MERGE_SWEEP_THRESHOLD`, etc. |

All production changes are gated by server-side config flags (default OFF) so they are no-ops on existing deployments. Lean harness flips the flags per JMH run via the `designMode` parameter, which lets us measure the **same binary** with the feature OFF and ON in successive runs and produce an apples-to-apples before/after comparison.

### Config flags introduced

| Config key | Default | Phase introduced | Effect when ON |
|---|---|---|---|
| `server.vt.update.operand.enabled` | `false` | Phase 1 | (a) Leader for AA partial-update path skips the value/RMD `Get`s and `MergeConflictResolver.update()`, and produces `MessageType.UPDATE` directly to VT carrying the operand bytes. (b) Follower's VT-consumption path accepts `MessageType.UPDATE` and routes it to `storageEngine.merge(...)`. (c) RocksDB column family is opened with `StringAppendOperator` registered. (d) All written values get a `kind` byte prefix; reads handle both forms. |
| `server.merge.sweep.enabled` | `false` | Phase 2 | Enables the `PartitionSweeper` running on the drainer thread. Has effect only when `server.vt.update.operand.enabled=true`. |
| `server.merge.sweep.threshold` | `4` | Phase 2 | Number of operands per key before sweep is triggered. |
| `server.merge.sweep.budget.per.call` | `500` | Phase 2 | Max keys swept per drainer-loop iteration. |
| `server.merge.sweep.debounce.ms` | `500` | Phase 2 | Minimum interval between two sweeps of the same key. |

### How the benchmark uses the flags

The new `LeanActiveActiveIngestionBenchmark` parameter `-p designMode=BASELINE|MERGE_OPERAND` maps to flag combinations:

| `designMode` | `vt.update.operand.enabled` | `merge.sweep.enabled` |
|---|---|---|
| `BASELINE` | false | false |
| `MERGE_OPERAND` (Phase 1) | true | false |
| `MERGE_OPERAND_SWEPT` (Phase 2) | true | true |

This is the central comparison: the same benchmark JAR, the same workload generator, the same JMH config — only the flag combination changes between runs. Any difference in measured numbers is attributable to the flagged code path.

A backstop sanity check on every run: when both flags are OFF, the code path must be **byte-equivalent to today's behavior** (no new allocations, no extra dispatch). The first commit of Phase 1 should include a smoke test asserting this — flag OFF, run the lean harness, expect identical-to-today VT byte stream and RocksDB on-disk format.

### Explicitly out of scope (NOT in this experiment)

- **Cross-region DCR.** This experiment uses a single-region workload (only `dc-0` RT receives traffic), so no cross-region conflicts exist. Resolving cross-region conflicts via merge operator is a follow-up project after this design is validated.
- **Replication metadata (RMD).** Without DCR, RMD is unused on the write path. Existing RMD code paths remain but are skipped for the experimental UPDATE flow.
- **Native C++ code.** Phase 0–1 use only `StringAppendOperator` (already in stock RocksJava 9.11.2). A native operator is a Phase-2+ decision gated on measurements.
- **Full-record PUT path.** The existing PUT flow is unchanged. Only `UPDATE` (partial-update) takes the new code path.
- **Cross-region rebalancing, controller, Helix.** Lean harness already abstracts these.
- **Read-after-write consistency guarantees beyond what today's design provides.** Same eventual-consistency model.

### Existing infrastructure to reuse

| Class | Use as |
|---|---|
| `LeanActiveActiveIngestionBenchmark` | JMH driver; existing PARTIAL_UPDATE workload |
| `MinimalAAIngestionHarness` | Test rig with real Kafka + RocksDB |
| `AAIngestionWorkloadHelper` | Workload generator (operand contents, key pool, sentinels) |
| `WriteComputeProcessor` | Avro WC apply logic — reused at sweeper and read time |
| `RocksDBStoragePartition` | Base class for `MaterializingRocksDBStoragePartition` |
| `StringAppendOperator` | Already in `org.rocksdb` — we just register it |

---

## 3. Phased plan with decision gates

Each phase is independently measurable and ends with a go/no-go decision before the next phase starts.

### Phase 0 — Baseline measurements (no code changes)

**Deliverables:**
- Run `LeanActiveActiveIngestionBenchmark` once with `workloadType=PARTIAL_UPDATE` on the existing branch HEAD.
- Capture: JMH score, E2E ops/s, VT bytes/s, RocksDB on-disk size at end of run, p50/p99 read latency on hot keys, leader CPU breakdown (Avro vs other).
- Establish this as the baseline for subsequent phases.

**Verification:**
- ✅ Baseline file `data/phase0-baseline.tsv` committed with raw measurements

**Exit criterion:** baseline table written. No code changes yet.

---

### Phase 1 — Leader-skip-RMW with StringAppendOperator transport (no sweeper)

**Production code deliverables** (all gated by `server.vt.update.operand.enabled` from §2; default OFF, no behavior change unless explicitly flipped):

- `AbstractStoragePartition.merge(byte[], ByteBuffer)` + `AbstractStorageEngine.merge(int, byte[], ByteBuffer)` — new abstract method + passthrough
- `RocksDBStoragePartition.merge(...)` — calls `rocksDB.merge(writeOptions, ...)`
- `RocksDBStoragePartition.getStoreOptions(...)` — register `StringAppendOperator` on the value column family when the flag is on
- `MaterializingRocksDBStoragePartition extends RocksDBStoragePartition`:
  - On every write, prepends `kind=0x00` for full PUT or `kind=0x01` for operand
  - Operand wire format: `[kind=0x01][len:varint][avro-WC-payload]`
  - On read, detects concat form vs materialized via the kind byte and folds inline using `WriteComputeProcessor.applyWriteCompute`
- `ActiveActiveStoreIngestionTask`: when flag is on, for RT `UPDATE` messages, skip `MergeConflictResolver.update()` and the value/RMD `Get`s, and produce a `MessageType.UPDATE` directly to VT carrying the operand bytes
- `StoreIngestionTask` / `LeaderFollowerStoreIngestionTask`: when flag is on, accept `MessageType.UPDATE` on the VT consumption path and route it to `storageEngine.merge(partition, key, operand)` instead of dispatching to the existing PUT/DELETE handlers

**Benchmark / harness deliverables:**

- New `LeanActiveActiveIngestionBenchmark` parameter `-p designMode=BASELINE|MERGE_OPERAND` (default `BASELINE`). When set to `MERGE_OPERAND`, the lean harness:
  - Sets `SERVER_VT_UPDATE_OPERAND_ENABLED=true` on the server config
  - Workload writes UPDATE KMEs to only `dc-0` RT topic (one region); `dc-1` RT remains idle. `setActiveActiveReplicationEnabled` left as-is on the store but no cross-region traffic is generated.
- No new JMH benchmark class. Reuse the existing `LeanActiveActiveIngestionBenchmark` for both modes so workload/measurement/JMH-config are identical.
- Run benchmark once with `-p workloadType=PARTIAL_UPDATE -p designMode=MERGE_OPERAND` and compare against the Phase 0 baseline (same benchmark with implicit `BASELINE` mode).

**Measurements:**
- JMH score, E2E ops/s, VT bytes/s, RocksDB on-disk size growth over time, p50/p99 read latency on hot keys, leader CPU (should drop dramatically), follower drainer CPU (should stay similar).
- New: histogram of concat-chain length at end of run.

**Decision gate:**
- ✅ JMH score improves by ≥ 50% vs Phase 0 → continue to Phase 2
- ✅ VT bytes/s drops by ≥ 5× → continue
- ⚠️ Storage size grows ≥ 5× over baseline → expected, motivates Phase 2 (sweeper)
- ⚠️ p99 read latency on hot keys ≤ 3× baseline → continue. If greater, pause and investigate; sweeper in Phase 2 may help.
- ❌ JMH score does not improve, or VT bytes don't drop → STOP. The hypothesis "leader RMW is the bottleneck" is wrong; reframe.

**Exit criterion:** decision gate passed; numbers logged; storage growth confirmed as the next problem to solve.

---

### Phase 2 — Java-side sweeper to bound storage

**Deliverables** (gated by `server.merge.sweep.enabled` from §2; default OFF, requires `server.vt.update.operand.enabled=true` to have effect):
- `DirtyKeyTracker` — per-partition concurrent queue of keys with operands. Populated on `merge`, drained by sweeper.
- `PartitionSweeper` — runs on the drainer thread at end of each batch. Materializes keys with concat-form values back to materialized form. Uses existing `WriteComputeProcessor`.
- Sweeper tunables read from `server.merge.sweep.threshold`, `server.merge.sweep.budget.per.call`, `server.merge.sweep.debounce.ms` (defaults in §2 table).
- Run benchmark once with `-p designMode=MERGE_OPERAND_SWEPT` (flips both `vt.update.operand.enabled` and `merge.sweep.enabled` ON).

**Measurements:**
- Same as Phase 1, plus:
- Sweeper CPU as fraction of drainer CPU
- Storage on-disk size over time (should be near Phase 0 baseline, not Phase 1's bloated value)
- Compaction CPU (expect a *decrease* vs Phase 1 because materialized values are smaller through every level)
- Concat-chain length histogram (expect p99 ≤ ~10 with default tunables)

**Decision gate:**
- ✅ Storage size within 1.2× of Phase 0 baseline → success
- ✅ JMH score / VT bytes wins from Phase 1 retained (within 5%) → continue
- ✅ p99 read latency within 3× baseline → continue
- ⚠️ Sweeper CPU > 1 core sustained → tune thresholds and re-run; if still high, Phase 3 considers native operator
- ❌ JMH score regressed by > 10% vs Phase 1 → sweeper is too aggressive; tune down or pause

**Exit criterion:** the design works end-to-end in pure Java with bounded storage. Numbers tabulated and ready to inform the native-vs-stay-Java decision.

---

### Phase 3 (decision gate) — Is native worth it?

**Not a code phase. A decision conversation.**

**Inputs:**
- All Phase 0–2 measurements
- Estimate of native-operator engineering cost (LinkedIn platform-team conversation needed)
- Read-side cost trajectory: does p99 read latency stay acceptable, or does sweeper backlog cause cliffs under realistic burst patterns?

**Possible outcomes:**
- **Stop.** Phase 2 numbers are good enough. Productionize the Java design. Native is not justified by measurements.
- **Native operator.** Numbers show the read-side or sweeper-CPU cost is real and a native operator (Cassandra-style: deserialize → dedupe by `(field-id, op-type)` → re-serialize) would meaningfully outperform. Open a separate project for it (multi-quarter, includes upstream-vs-fork decision).
- **Different design.** Numbers show neither approach wins enough to justify the migration. Reframe what we're optimizing for.

---

## 4. Metrics — what we capture every run

| Metric | Source | Today (Phase 0) | Hypothesis (Phase 2) |
|---|---|---|---|
| JMH score (ops/s, producer throughput) | JMH | baseline | ≥ 1.5× baseline |
| E2E ops/s (drain-inclusive) | benchmark `[E2E]` log line | baseline | ≥ 1.3× baseline |
| VT bytes/s | Kafka broker metrics | baseline | ≤ 0.2× baseline |
| RocksDB on-disk size at end-of-run | `du -sb /tmp/.../rocksdb-temp` | baseline | ≤ 1.2× baseline |
| Leader CPU on Avro decode/encode | JFR / async profiler | baseline | ≤ 0.1× baseline |
| Drainer + sweeper CPU on Avro work | JFR / async profiler | n/a | ≤ baseline leader Avro CPU |
| Sweeper CPU as fraction of one core | sweeper instrumentation | n/a | ≤ 0.5 cores per partition |
| p50/p99 read latency on hot keys | direct GET probe | baseline | p50 ≤ 1.1× baseline; p99 ≤ 3× baseline |
| Concat-chain length p50/p99/max | sweeper instrumentation | n/a | p99 ≤ 10 |
| Compaction CPU & bytes moved | RocksDB stats | baseline | ≤ baseline (smaller per-key bytes wins) |

---

## 5. Verification logistics

- Each phase commits its measurements to `autoresearch/vt-rocksdb-merge/data/phase-N-runs.tsv`
- Each phase ends with a `phase-N-summary.md` written by the implementer that compares against prior phase, lists decision-gate outcome, links commit SHA
- All benchmark runs use the same JMH params: `-f 1 -wi 1 -w 5s -i 1 -r 20s -foe true -p workloadType=PARTIAL_UPDATE`
- **Key pool size: 100,000.** Set `AAIngestionWorkloadHelper.PARTIAL_UPDATE_KEY_POOL_SIZE = 100_000` (currently `10_000`). The larger pool is closer to a realistic AA partial-update working set, exercises the sweeper at a representative working-set size, and matches the 100K pool we used in the prior RMD-cache benchmark for consistency across experiments. Apply this change before Phase 0 baseline so all phases compare against a 100K-pool baseline.
- All runs use the same JVM args: `-Xms32G -Xmx32G` plus the standard `--add-opens`
- One run per phase as the headline measurement; if a number looks anomalous, repeat ad-hoc to disambiguate signal from noise (no fixed N up front)
- Repro commands captured in `data/repro.md`

---

## 6. Risks and what would invalidate the design

| Risk | Where it hits | How we'd see it | Response |
|---|---|---|---|
| Sweeper falls behind under bursty writes | Phase 2 | Concat-chain p99 climbs above ~50; p99 read latency spikes | Tune sweeper thresholds; if still bad, indicates native operator necessary |
| Block-cache hit rate degrades from concat bloat | Phase 1–2 | p99 read latency degrades further than expected from per-key fold cost | Quantify cache impact separately; may need bigger cache or smarter sweep priority |
| RocksDB compaction CPU goes up despite smaller payloads | Phase 1–2 | Compaction CPU regresses vs Phase 0 | Investigate StringAppendOperator FullMerge cost during compaction; may need a CompactionFilter (which would push us toward native) |
| Operand format requires changes that break the lean harness's existing assertions | Phase 1 | Smoke tests fail | Roll back; refactor operand format; the harness's VT consistency check is the integration test |
| The "leader skips RMW" assumption breaks correctness for some edge case (chunked values, schema mismatch) | Phase 1 | Smoke tests fail / VT consistency check finds mismatches | Document the edge case; adjust the experiment scope; this experiment is single-region single-schema, so most edges shouldn't apply |
| Phase 1 numbers don't show a write-side win at all | Phase 1 | JMH score flat | The leader RMW isn't the bottleneck for partial updates as we hypothesized. Re-investigate where the time is actually spent. |

---

## 7. Non-goals — do NOT design for these in this experiment

- Multi-region cross-region DCR (separate project)
- Backward compatibility with existing on-disk format (can break since experiment uses fresh RocksDB instances)
- Production deployment readiness (this is a measurement vehicle, not a shippable feature)
- Schema evolution of operand format (one schema only for the benchmark)
- TTL / tombstone reclamation (not exercised by the partial-update workload)

---

## 8. Decision criteria for "this is a real design"

After Phase 2, the answer to "should LinkedIn invest in productionizing this design" is YES if:

- JMH score ≥ 1.5× baseline
- VT bytes/s ≤ 0.2× baseline
- p99 read latency ≤ 3× baseline
- Storage size ≤ 1.2× baseline
- No regressions on smoke tests / VT consistency check

If any of those fail by more than measurement noise, the answer is NO and we go back to the design table or stop.

A YES answer triggers the Phase 3 conversation about whether the production version uses the Java sweeper (cheap and quick to land) or commits to a native operator (slower but more robust under heavy load). That conversation is *informed* by Phase 2's numbers, not pre-decided.

---

## 9. Reference: design discussion

Full design discussion (including the alternative paths and why they were ruled out — composite-key Put log, native operator from day one, full upstreaming to RocksDB) lives in this branch's chat history (`/home/coder/.claude/projects/...`). The key conclusions that motivate this GOAL doc:

1. RocksJava 9.11.2 does not allow custom Java merge operators (verified from upstream source — `MergeOperator.java` is just a constructor wrapping a native handle, no callback path; PR #12122 to add Java-side support has been open since 2023 and not merged). Custom merge semantics in pure Java therefore require either (a) `StringAppendOperator` as transport, or (b) a composite-key Put log.
2. Flink's `RocksDBListState` uses `StringAppendOperator` in production at very large scale, with read-time fold and no sweeper — but Flink relies on application-bounded list sizes to avoid storage growth.
3. Cassandra's Rocksandra uses native semantic merge operator + native compaction filter for bounded storage. Not viable in pure Java.
4. The "StringAppendOperator + Java sweeper" composition is a **synthesis** without a known famous-project precedent. This GOAL serves both as Venice's measurement and as a viability test for the pattern.
