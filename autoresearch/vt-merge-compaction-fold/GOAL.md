# VT-Merge Compaction-Time Fold — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-03 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation; same
branch as JMH back-pressure work) **Execution model:** Phased, autonomous agent. Each phase ends with a JMH
MERGE_OPERAND_SWEPT 3-min × 3-iter run that proves the chain bound holds. **Estimated effort:** 4–6 days of agent work
(1 week wall, including benchmark cycles)

---

## 0. Problem statement

The `MERGE_OPERAND_SWEPT` design relies on no compaction-time fold today. RocksDB's `StringAppendOperator` only
**concatenates** operand bytes during compaction — nothing materializes them back to a base record. `PartitionSweeper`
is a measurement-only no-op (its class doc, `PartitionSweeper.java:17–25`, explicitly says it does not fold). The read
path uses `MaterializingFoldContext.foldOperands()` to fold on every `get`, but throws the materialized result away —
never writes it back.

The Phase C JMH RocksDB-internal forensics (`autoresearch/jmh-backpressure/data/phase-C-rocksdb.tsv`, commit
`d5ccc5061`) caught the consequence:

| Iter | JMH ops/s | Chain-length p50 | Write-amp | Stalls |
| ---: | --------: | ---------------: | --------: | -----: |
|    1 |   110,714 |              236 |       1.0 |      0 |
|    2 |    73,590 |              369 |       1.7 |      0 |
|    3 |    33,915 |              430 |       1.6 |      0 |

RocksDB itself is healthy (no stalls, write-amp ≤ 1.7). The throughput collapse tracks chain length linearly — the
bottleneck is **fold-on-read latency growing with unbounded chain depth**.

## 1. Goal

Bound operand-chain length under any sustained workload by moving fold work into RocksDB's own compaction path, plus a
synchronous backstop for hot keys whose chain grows between compactions:

1. **Chain-length p99 bounded** to a small constant (target: ≤ 100) under a 3-minute × 3-iteration MERGE_OPERAND_SWEPT
   run.
2. **Iter-over-iter throughput stable** (CV < 15%) at MERGE_OPERAND_SWEPT iter 1 levels (~110K ops/s).
3. **No regression on read correctness** (`READ-VERIFY` 1000/1000, `VT-CHECK` 0/0/0).
4. **Net code reduction**: retire the placeholder `PartitionSweeper` once the compaction filter is stable.

## 2. Scope

### In scope

- New Java class `VeniceConcatFoldCompactionFilter` (and its `Factory`) under
  `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/`.
- Wiring in `RocksDBStoragePartition.java` near the existing `setMergeOperator` call (lines ~274, ~473) to also
  `setCompactionFilterFactory(...)`.
- Optional flush-time fold (Phase C) via the RocksDB option that runs the filter on memtable flush.
- Synchronous chain-length backstop in the follower's apply-operand path (Phase B), guarded by `MAX_CHAIN` config.
- Removal of `PartitionSweeper`, `DirtyKeyTracker`, related sweep configs, and registration sites (Phase D).

### Out of scope

- Custom C++ MergeOperator (Path 1 from the design discussion). Filter does the same job in pure Java.
- Read-side fold-and-write-back (Solution #4 from the categorization).
- Dual-CF / log+snapshot storage layouts (Solution #5).
- Changing the leader-side write contract (still: leader produces UPDATE operands to VT, never RMW + PUT).
- BASELINE-mode changes — this is purely a MERGE_OPERAND_SWEPT improvement.

## 3. Phased plan

Each phase ends with a benchmark run that proves the phase's exit criterion. Reuse the existing JMH RocksDB-internal
instrumentation from commit `d5ccc5061` — chain-length distribution sampler, cfstats dump, 1Hz timeline poller — they're
already in the harness.

### Phase A — Java CompactionFilter that materializes during compaction

**Build:**

- Implement `VeniceConcatFoldCompactionFilter extends AbstractCompactionFilter<Slice>`. On each `filter(...)` call:
  1. `Parsed parsed = ConcatBlobParser.parse(value)`.
  2. If `parsed.getOperands().isEmpty()`, return `KEEP`.
  3. Otherwise call `foldContext.foldOperands(parsed.getSchemaId(), parsed.getBase(), parsed.getOperands())`.
  4. Re-frame with `ConcatBlobParser.frameBase(schemaId, foldedBytes)` and emit via `CHANGE_VALUE`.
- Implement `VeniceConcatFoldCompactionFilterFactory` that constructs filters with the per-store
  `MaterializingFoldContext`. The factory needs access to: `StoreWriteComputeProcessor`, schema repository, store name +
  version. These are already available wherever `MaterializingRocksDBStoragePartition` is constructed — pass them
  through.
- Register in `RocksDBStoragePartition.java` at both merge-operator setup sites (`~line 274` for the Materializing path
  and `~line 473` for the legacy default-CF path), guarded by the same flag that enables MERGE_OPERAND_SWEPT today.

**Verify:**

- Unit tests: feed concat blobs through the filter directly; assert output is a clean `KIND_BASE` frame with no operand
  suffix; assert that already-folded blobs pass through as `KEEP`.
- Smoke run: JMH MERGE_OPERAND_SWEPT `-i 1 -r 30s -wi 1 -w 10s`. `READ-VERIFY` 1000/1000. Chain-length p99 < 50.
- Phase C run: `-i 3 -r 180s -wi 1 -w 30s`. Chain-length p99 should stay flat across iters.

**Exit:**

- Iter-1 throughput within 10% of current 110K ops/s (filter doesn't tank perf).
- Iter-3 chain-length p99 ≤ 100.
- Iter-3 throughput within 15% of iter-1 throughput (CV < 15%).
- `VT-CHECK 0/0/0`, `READ-VERIFY 1000/1000`.

### Phase B — Chain-length backstop at follower

**Build:**

- Add `@Param`-style config `vt.merge.max.chain.length` (default 64) to `VeniceServerConfig` + `ConfigKeys`.
- In `ActiveActiveStoreIngestionTask` (or wherever the follower applies operands via `rocksDB.merge(...)`), check chain
  depth before applying. If depth ≥ MAX_CHAIN, do a synchronous read + fold + put, then continue.
- Use `MaterializingFoldContext.foldOperands()` for the fold.

**Verify:**

- Unit test: feed N+1 operands at the same key, assert that on the (N+1)th the storage observes a base PUT (chain depth
  resets).
- Phase C run: chain-length p99 ≤ MAX_CHAIN strictly.
- Compare to Phase A: throughput should be nearly identical (backstop fires rarely on the workload's random-key
  distribution).

**Exit:**

- Chain-length p99 ≤ 64 (= MAX_CHAIN) under any iter.
- Iter-over-iter throughput stable (CV < 10% — tighter than Phase A).
- Hot-key tail latency: synthetic test with 99% writes targeting the same key shows bounded p99 read latency.

### Phase C (optional) — Flush-time filter

**Trigger:** only run if Phase B's chain-length p99 still climbs across iters because L0→L1 compactions don't happen
frequently enough on this workload.

**Build:**

- Set the RocksDB option that enables compaction filter on memtable flush (`db_options.experimental_mempurge_threshold`
  or equivalent — check current rocksdbjni 9.11.2 API surface for the right knob).
- No new code; configuration only.

**Verify:** rerun Phase C. Chain-length p99 should drop further. Skip if Phase B already meets exit criterion.

### Phase D — Retire `PartitionSweeper`

**Build:**

- Delete `PartitionSweeper.java`, `DirtyKeyTracker.java` (if not used elsewhere — check).
- Remove sweeper config keys from `VeniceServerConfig` and `ConfigKeys`.
- Remove sweeper registration / scheduling sites in the storage engine setup.
- Update tests that reference these classes.

**Verify:**

- All existing unit + integration tests pass.
- Phase C 3-min × 3-iter MERGE_OPERAND_SWEPT shows no regression vs Phase B.
- `git diff --stat` shows net code reduction (no replacement code).

**Exit:**

- Sweeper code gone; tree compiles; tests green; benchmark numbers unchanged from Phase B.

## 4. Concrete design — Phase A skeleton

```java
public final class VeniceConcatFoldCompactionFilter extends AbstractCompactionFilter<Slice> {

  private final MaterializingFoldContext foldContext;

  public VeniceConcatFoldCompactionFilter(MaterializingFoldContext foldContext) {
    this.foldContext = foldContext;
  }

  @Override
  protected Decision filter(int level, Slice key, Slice existingValue, ByteBuffer newValue) {
    byte[] blob = existingValue.data();
    Parsed parsed = ConcatBlobParser.parse(blob);
    boolean hasBase = parsed.hasBase();
    boolean hasOperands = !parsed.getOperands().isEmpty();
    if (!hasBase && !hasOperands) {
      return Decision.KEEP; // empty/malformed; defensive
    }
    if (!hasOperands) {
      return Decision.KEEP; // already a clean base — nothing to fold
    }
    try {
      byte[] reframed;
      if (hasBase) {
        // Standard case: base + operand chain → fold operands onto base.
        byte[] foldedAvroBytes = foldContext.foldOperands(parsed.getSchemaId(), parsed.getBase(), parsed.getOperands());
        reframed = ConcatBlobParser.frameBase(parsed.getSchemaId(), foldedAvroBytes);
      } else {
        // Operand-only chain (no base ever produced for this key in this region):
        // consolidate via foldOperandOnly which applies operands to a null record using
        // the schema's defaults. This bounds chain depth even for keys whose first
        // appearance was an UPDATE rather than a PUT.
        FoldOnlyResult result = foldContext.foldOperandOnly(parsed.getOperands());
        reframed = ConcatBlobParser.frameBase(result.getSchemaId(), result.getMaterializedBytes());
      }
      newValue.put(reframed);
      return Decision.CHANGE_VALUE;
    } catch (Throwable t) {
      LOGGER.warn("Compaction-time fold failed; keeping unfolded blob", t);
      return Decision.KEEP;
    }
  }
}

public final class VeniceConcatFoldCompactionFilterFactory
  extends AbstractCompactionFilterFactory<VeniceConcatFoldCompactionFilter> {

  private final MaterializingFoldContext foldContext;

  public VeniceConcatFoldCompactionFilterFactory(MaterializingFoldContext foldContext) {
    this.foldContext = foldContext;
  }

  @Override
  public VeniceConcatFoldCompactionFilter createCompactionFilter(Context ctx) {
    return new VeniceConcatFoldCompactionFilter(foldContext);
  }

  @Override
  public String name() {
    return "VeniceConcatFoldCompactionFilterFactory";
  }
}

```

Registration site (in `RocksDBStoragePartition.java`, near the existing `setMergeOperator` call):

```java
if (vtMergeEnabled && foldContext != null) {  // foldContext is per-store, threaded down from engine
  columnFamilyOptions.setCompactionFilterFactory(new VeniceConcatFoldCompactionFilterFactory(foldContext));
}
```

The `foldContext` is the same `MaterializingFoldContext` that's already used on the read path. No new fold logic; the
filter is glue.

## 5. Test gates per phase

Each phase has unit, integration, and regression gates the agent must pass before moving on. Reuse existing suites where
they cover the same surface; only write new tests for genuinely new code paths. The JMH benchmark is the headline perf
gate, but it's the **last** check, not the dev loop.

### Reused test assets (do not rewrite)

| Suite                                                                                                       | Tests   | Reuse purpose                                                                                                              |
| ----------------------------------------------------------------------------------------------------------- | ------- | -------------------------------------------------------------------------------------------------------------------------- |
| `ConcatBlobParserTest`                                                                                      | 14      | Parser correctness — filter parses the same shape; this guards against the parser regressing                               |
| `MaterializingPartitionSmokeTest`                                                                           | 12      | Read-path fold via `MaterializingFoldContext` — guards that the read fold logic the filter shares with reads stays correct |
| `RocksDbMergeReproTest`                                                                                     | 6       | RocksDB JNI merge-operator behavior — filter sits on the same column family, must not break merge dispatch                 |
| `RocksDBStoragePartitionTest`, `RocksDBStorageEngineTest`, `ReplicationMetadataRocksDBStoragePartitionTest` | many    | Partition/engine wiring — Phase A modifies `RocksDBStoragePartition`, these are the direct regression guards               |
| `TestPartialUpdateWithActiveActiveReplication`                                                              | 5       | E2E flag-on partial-update with AA. Already wired to enable the flag via `getExtraServerProperties()`.                     |
| `Lean*SmokeTest` (4 files)                                                                                  | several | Lean harness sanity — proves the benchmark scaffold still builds + runs                                                    |
| `LeanActiveActiveIngestionBenchmark` (with READ-VERIFY, VT-CHECK, RocksDB instrumentation, back-pressure)   | —       | Performance + correctness gate; Phase-end run                                                                              |

### New tests per phase

| Phase | New unit                                                                                                                                | New integration                                                                                                                                                                        | Regression gates                                                               | Perf gate                                                    |
| ----- | --------------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------ | ------------------------------------------------------------ |
| **A** | `VeniceConcatFoldCompactionFilterTest` (5+ cases — see below)                                                                           | Add `compactionFoldRoundTrip` test to `MaterializingPartitionSmokeTest` (open RocksDB w/ filter, write base + N operands, force `compactRange()`, read back, assert no operand suffix) | All reused suites green; `compileJava` clean                                   | JMH MERGE_OPERAND_SWEPT 3-min × 3-iter passing exit criteria |
| **B** | `ChainLengthBackstopTest` (boundary cases: depth `MAX_CHAIN-1`, `=MAX_CHAIN`, `+1`, concurrent operands at same key, fold failure path) | Hot-key test in `MaterializingPartitionSmokeTest` (200 operands at same key → assert ≥ `200/MAX_CHAIN` base PUTs land)                                                                 | All reused suites green                                                        | JMH 3-min × 3-iter; chain-length p99 ≤ MAX_CHAIN strictly    |
| **C** | (none)                                                                                                                                  | Smoke: 30s JMH run; grep RocksDB LOG for filter callbacks at flush time                                                                                                                | All reused suites green                                                        | JMH 3-min × 3-iter; chain p99 tightens further               |
| **D** | (delete sweeper tests if any — none exist today)                                                                                        | (none)                                                                                                                                                                                 | All reused suites green; `compileJava` clean post-deletion; net code reduction | JMH 3-min × 3-iter unchanged from Phase B                    |

### Phase A unit-test cases (concrete)

`VeniceConcatFoldCompactionFilterTest` should cover:

1. **Already-folded blob (base, no operands)** → `Decision.KEEP`; output unchanged.
2. **Base + N operands** → `Decision.CHANGE_VALUE`; output is `KIND_BASE` framed;
   `ConcatBlobParser.parse(output).getOperands().isEmpty()` is true.
3. **Operand-only chain (no base, N operands)** → `Decision.CHANGE_VALUE`; output is `KIND_BASE` framed at the schema-id
   resolved by `foldOperandOnly`; the materialized record has the operands applied to a default record.
4. **Empty/malformed blob** → `Decision.KEEP`; no exception thrown.
5. **Fold throws (e.g., schema-id unknown)** → `Decision.KEEP`; warning logged; compaction continues.
6. **Round-trip equivalence**: read-path materialize of the input blob equals read-path materialize of the filter's
   output blob (for cases 2 and 3). This is the strongest correctness invariant.

### Operand-only-blob policy (decision)

The filter consolidates operand-only chains via `MaterializingFoldContext.foldOperandOnly()`. Rationale: Avro schemas
have field defaults, so `applyWriteCompute(null, operand)` produces a valid base from the operand chain even when no
base PUT was ever produced for the key in this region. This bounds chain depth uniformly regardless of whether the key's
first appearance was a PUT or an UPDATE. The Phase A unit suite case (3) above is the gate.

Alternative considered: keep operand-only blobs as-is. Rejected because hot keys whose entire history is UPDATEs (no
PUT) would otherwise grow chains unbounded — the compaction filter would help only the more common base+operand case,
defeating the chain bound for half the workload shape.

### Dev-loop ordering

For each phase, the agent runs gates in this order, halting on the first failure:

1. New unit tests (sub-second feedback)
2. Modified existing unit tests (`compactionFoldRoundTrip`, `MaterializingPartitionSmokeTest` hot-key test for Phase B)
3. All reused unit suites (`./gradlew :clients:da-vinci-client:test`) — minutes
4. `TestPartialUpdateWithActiveActiveReplication` integration test with flag ON
5. JMH MERGE_OPERAND_SWEPT 3-min × 3-iter

Phases A and B do not advance until step 4 passes; Phase D does not commit until step 3 passes (Phase D has no perf gate
beyond not regressing Phase B).

## 6. Verification logistics

- Each phase commits results to `autoresearch/vt-merge-compaction-fold/data/phase-{A,B,C,D}.tsv`.
- Each phase ends with a one-paragraph progress note in
  `autoresearch/vt-merge-compaction-fold/phase-{A,B,C,D}-NOTES.md`.
- Final comparison written to `autoresearch/vt-merge-compaction-fold/RESULTS.md` once Phase D is done.
- Reuse the existing JMH RocksDB-internal instrumentation (commit `d5ccc5061`) — the chain-length distribution sampler
  is the primary regression signal.
- BASELINE is **not** rerun in this work; back-pressure mechanism from `cfc112293` continues to apply.

## 7. Decision criteria

A YES on the overall goal requires all of:

- ✅ Chain-length p99 ≤ 100 sustained across 3 × 180s iters of MERGE_OPERAND_SWEPT (Phase A) — bounded further to ≤ 64
  by Phase B.
- ✅ Iter-over-iter throughput CV < 15% on JMH score (Phase A) — < 10% by Phase B.
- ✅ Iter-1 throughput within 10% of pre-fix 110K ops/s (the compaction filter doesn't introduce a new bottleneck).
- ✅ `VT-CHECK 0/0/0`, `READ-VERIFY 1000/1000` on every run.
- ✅ Net code reduction after Phase D (sweeper + tracker + configs deleted).

If Phase A or B fails its exit, halt and escalate. Phases C and D are conditional on Phase B success.

## 8. Risks

| Risk                                                                                                                             | How we'd see it                                    | Response                                                                                                                           |
| -------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| `MaterializingFoldContext` requires per-store context (schema repo, WC processor) that isn't wired into the partition setup path | Compile error or NPE in factory                    | Trace the existing wiring at `MaterializingRocksDBStoragePartition` construction; thread the same context to the filter factory    |
| Java `AbstractCompactionFilter` JNI callback per output value too expensive                                                      | Iter-1 throughput drops below 100K ops/s           | Check rocksdbjni `BlockBasedTableConfig` filter-batching options; measure with JFR; fall back to flush-time filter only            |
| `applyWriteCompute` mutates compressed bytes; compaction filter receives compressed values                                       | Decoding error mid-compaction                      | Inspect `MaterializingFoldContext`'s compress/decompress wrapping (it already handles this for read path); reuse the same wrappers |
| Backstop synchronous fold blocks ingestion drain thread                                                                          | E2E throughput drops noticeably; bp_wait_ms climbs | Lower MAX_CHAIN; or move fold to async with bounded queue                                                                          |
| Filter doesn't run on enough compactions for hot keys                                                                            | Chain p99 still climbs                             | Tune `level0_file_num_compaction_trigger` lower; or proceed to Phase C                                                             |
| Phase D removal breaks an unrelated test that uses sweeper as setup fixture                                                      | Test failure post-deletion                         | Sweep tests carefully; if a test uses the sweeper as a real fixture, rewrite it; otherwise delete                                  |
| `compaction filter on flush` knob not exposed in rocksdbjni 9.11.2                                                               | Phase C blocked                                    | Skip Phase C; Phase B's MAX_CHAIN backstop is the alternative                                                                      |

## 9. Non-goals

- This is **not** about read-heavy workload optimization. Reads still fold operand chains above the most recent base; we
  only bound how deep that chain can be.
- This is **not** a write-amplification reduction project. Filter materializes on compaction, which means the compacted
  SST values get rewritten — that's the whole point.
- This is **not** about touching BASELINE mode. RMW path is unchanged.
- This is **not** introducing C++ code or a custom rocksdbjni fork.
- This is **not** changing the on-disk wire format. `ConcatBlobParser`'s `KIND_BASE`/`KIND_OPERAND` framing is
  preserved.
