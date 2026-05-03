# VT-Merge Read-Path Correctness — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-01 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation)
**Predecessor:** `autoresearch/vt-rocksdb-merge/` (write-side experiment, completed) **Execution model:** Phased, with
each phase verified by an existing integration test before continuing **Estimated effort:** 1–2 weeks

---

## 0. Starting state and prior work

### Branch state at experiment start

- **Working directory:** `/home/coder/Projects/venice`
- **Branch:** `haoxu07/vt-rocksdb-merge-design`
- **HEAD at experiment start:** `e8ff45a12`
  (`[autoresearch] Phase 3 decision: STOP & productionize Phase 1 (Java) design`)
- **Untracked files to ignore:** `docs/contributing/proposals/per-request-forensics.md`,
  `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/endToEnd/forensics/`,
  `scripts/ci/check-forensics-conventions.sh` — these belong to a separate line of work, do not touch.

### What's already built (from the predecessor experiment)

The predecessor `autoresearch/vt-rocksdb-merge/` experiment built the **write side** of this design. Existing pieces to
reuse, NOT rebuild:

| Class / file                                            | Location                                                                                                                                              | What it does                                                                                                                                                     |
| ------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Config keys                                             | `clients/da-vinci-client/.../ConfigKeys.java` + `VeniceServerConfig.java`                                                                             | `SERVER_VT_UPDATE_OPERAND_ENABLED`, `SERVER_MERGE_SWEEP_ENABLED`, `SERVER_MERGE_SWEEP_THRESHOLD/BUDGET_PER_CALL/DEBOUNCE_MS` already defined and wired           |
| `StorageEngine.merge(...)`                              | `clients/da-vinci-client/.../store/AbstractStorageEngine.java`, `AbstractStoragePartition.java`, `StorageEngine.java`, `DelegatingStorageEngine.java` | `merge` API plumbed through all storage-engine layers; default throws `VeniceUnsupportedOperationException`, RocksDB partition delegates to `rocksDB.merge(...)` |
| `RocksDBStoragePartition.merge(...)`                    | `clients/da-vinci-client/.../store/rocksdb/RocksDBStoragePartition.java`                                                                              | Implements `merge` against RocksDB; `getStoreOptions(...)` registers `StringAppendOperator` on the value column family when the flag is on                       |
| Leader RT-UPDATE bypass                                 | `clients/da-vinci-client/.../kafka/consumer/ActiveActiveStoreIngestionTask.java`                                                                      | When flag is on: skip `MergeConflictResolver.update()`, skip the value/RMD `Get`s, produce `MessageType.UPDATE` directly to VT carrying operand bytes            |
| Follower VT-UPDATE dispatch                             | `clients/da-vinci-client/.../kafka/consumer/StoreIngestionTask.java` (+ subclass)                                                                     | When flag is on: accept `MessageType.UPDATE` on VT consumption path, route to `storageEngine.merge(...)`                                                         |
| `DirtyKeyTracker`                                       | `clients/da-vinci-client/.../store/rocksdb/merge/DirtyKeyTracker.java`                                                                                | 100 LOC, complete and working — sweeper's to-do list                                                                                                             |
| `PartitionSweeper` (skeleton)                           | `clients/da-vinci-client/.../store/rocksdb/merge/PartitionSweeper.java`                                                                               | 125 LOC; **`sweepOnce` reads bytes but does NOT fold** — line ~96 is a placeholder comment. Phase D of this experiment replaces that placeholder.                |
| `LeanActiveActiveIngestionBenchmark` `designMode` param | `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/LeanActiveActiveIngestionBenchmark.java`                                      | Values `BASELINE`, `MERGE_OPERAND`, `MERGE_OPERAND_SWEPT` already wired to flag combinations                                                                     |

### What's intentionally missing (this experiment fills the gap)

| What                                                                              | Why missing                                  | This experiment builds it in |
| --------------------------------------------------------------------------------- | -------------------------------------------- | ---------------------------- |
| `MaterializingRocksDBStoragePartition` (kind-byte write framing + read-side fold) | Predecessor deferred to Phase 2; never built | Phase B                      |
| `ConcatBlobParser`                                                                | Same deferral                                | Phase A                      |
| `PartitionSweeper.sweepOnce` actual fold + write-back                             | Predecessor stubbed at line 96               | Phase D                      |

### Operand wire format — already partially in place

The predecessor's `RocksDBStoragePartition.merge(...)` writes raw operand bytes (no kind byte, no length prefix). This
experiment's Phase B introduces a wrapper class `MaterializingRocksDBStoragePartition` that prepends `0x01 + varint-len`
before delegating to super. **The base class behavior must remain unchanged for the flag-OFF case**; framing is added
only in the materializing subclass. This preserves the regression-test guarantee: flag OFF must be byte-equivalent to
today.

### Predecessor docs to read for context

Before starting Phase A, read:

- `autoresearch/vt-rocksdb-merge/GOAL.md` — original write-side experiment goals
- `autoresearch/vt-rocksdb-merge/phase-1-progress.md` — what was deferred and why
- `autoresearch/vt-rocksdb-merge/phase-3-decision.md` — the "STOP & productionize" decision and the read-path caveat
  that motivated this experiment
- `autoresearch/vt-rocksdb-merge/SUCCESS.md` — headline numbers (note: those numbers measure write-side only, do NOT
  extrapolate to end-to-end reader latency)

---

## 1. Goal

The prior experiment (`autoresearch/vt-rocksdb-merge/`) demonstrated a **2.16× E2E write-throughput win** by having the
leader skip RMW and the follower call `db.merge()` against `StringAppendOperator`. **But it left the read path broken**:
`engine.get()` returns concatenated operand bytes that no Java reader can decode back into a usable Avro record.
`MaterializingRocksDBStoragePartition`, the kind-byte wire framing, the concat-blob parser, and the read-side fold using
`WriteComputeProcessor.applyWriteCompute` were all deferred and never built.

This goal: **build the missing pieces such that the design becomes read-correct.**

**Success criterion:** with `server.vt.update.operand.enabled=true`, the following 7 invocations from
`PartialUpdateTest` pass — each with flag ON and again with flag OFF (regression check):

| Test method                                           | Parametrization                                        | Invocations |
| ----------------------------------------------------- | ------------------------------------------------------ | ----------- |
| `testPartialUpdateOnBatchPushedKeys`                  | `Compression-Strategies` (NO_OP, GZIP, ZSTD_WITH_DICT) | 3           |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys` | none                                                   | 1           |
| `testActiveActivePartialUpdateWithCompression`        | `Compression-Strategies` (NO_OP, GZIP, ZSTD_WITH_DICT) | 3           |
| **Total**                                             |                                                        | **7**       |

These 7 invocations are deliberately the subset of `PartialUpdateTest` that **does not depend on cross-record
DCR-by-explicit-timestamp**. They use Samza's default wall-clock logical timestamps, so leader-side DCR (which our
design skips) is a no-op even today, making them a clean correctness oracle for the read-side fold.

The other tests in the partial-update space are explicitly **out of scope**:

- `testBatchProcessorOrphanChunkDeletion` and `testBatchProcessorOrphanChunkDeletionWithIgnoredDCRRecord` (in
  `PartialUpdateTest`) — set explicit logical timestamps to assert DCR-ignore semantics; our design intentionally skips
  DCR.
- `TestPartialUpdateWithActiveActiveReplication` — true cross-region AA workload (both `dc-0` and `dc-1` produce
  concurrently); our design's "leader forwards in arrival order" doesn't preserve DCR-by-timestamp semantics that this
  test asserts.
- `PartialUpdateAAMetadataTest` — asserts RMD CF contents which our flag-ON path bypasses.

These tests will be skipped in the test target for this experiment with a comment pointing to this GOAL doc. They become
the oracle for the **follow-up** cross-region-DCR experiment, not this one.

The 7 in-scope invocations exercise: scalar SET, map merge, list merge, chunked values, all 3 compression strategies. If
they pass with the flag ON, reads return materialized records that are byte-equivalent to today's behavior for the
single-region writer case — which is the formal definition of "the design is read-correct" within this experiment's
scope.

---

## 2. Scope

### In scope

- New class `MaterializingRocksDBStoragePartition` extending `RocksDBStoragePartition`. Wraps `put()` and `merge()` to
  attach a 1-byte kind tag (and length-prefix on operands), and overrides `get()` to detect concat form and fold inline.
- New class `ConcatBlobParser` — pure helper, walks bytes, returns `(base: byte[], operands: List<byte[]>)`.
- Update `PartitionSweeper.sweepOnce(...)` to actually fold and write back, replacing the line-96 placeholder.
- Wiring change in `RocksDBStorageEngineFactory` (or wherever partitions are constructed) to use the materializing
  partition class when the feature flag is on.
- All gated by the existing `server.vt.update.operand.enabled` config flag — flag OFF behavior must remain
  byte-equivalent to today.

### Explicitly out of scope

- Performance re-measurement. This goal is correctness-only. We will run the existing JMH benchmark to confirm we
  haven't regressed catastrophically, but the ≥1.5× throughput target is not re-litigated here.
- Cross-region DCR. The integration tests cover both single-region and AA cases; we ensure the new code path works in
  both, but DCR via timestamps in the operator is still a follow-up.
- Native operator (Cassandra-style). This goal stays in pure Java.
- Read-path optimization beyond functional correctness. We don't need to hit a specific p99 target; we need reads to
  return the right bytes.

### Files modified

| File                                                                                                                 | Change                                                         |
| -------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------- |
| `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingRocksDBStoragePartition.java` | NEW                                                            |
| `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/ConcatBlobParser.java`               | NEW                                                            |
| `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/PartitionSweeper.java`               | implement the fold (line 96 placeholder)                       |
| `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStorageEngineFactory.java`          | conditional partition construction based on flag               |
| `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStoragePartition.java`              | small refactor if needed to support subclass overrides cleanly |

---

## 3. Wire format

This is the contract that read and write paths must agree on.

### Materialized record (from `put` or after sweeper materialization)

```
[ schemaId : int32 BE ][ kind=0x00 : 1B ][ avro-encoded value bytes ]
```

The schemaId prefix is unchanged from today (Venice already does this in `prependHeaderAndWriteToStorageEngine`). The
kind byte goes immediately after.

### Operand (from `merge`)

```
[ kind=0x01 : 1B ][ payload-len : varint ][ avro-WC payload bytes ]
```

No schemaId prefix on operands — operands are tagged against the value schema currently active at the leader, which the
reader can fetch from the base record. (For schema-evolution edge cases, see §6.)

### Stored blob after StringAppendOperator concatenation

After `Put(base) + Merge(op1) + Merge(op2)`, RocksDB's `StringAppendOperator` concatenates with delimiter `,` (`0x2C`):

```
[ schemaId ][ 0x00 ][ avro-base ][ 0x2C ][ 0x01 ][ len1 ][ op1 ][ 0x2C ][ 0x01 ][ len2 ][ op2 ]
```

After compaction's FullMerge, the stored value is the same concatenated blob in a single `Put` entry.

The reader uses kind bytes (0x00, 0x01) — not the `,` delimiter alone — to find boundaries, because operand payloads can
contain `,`. The varint length on operands gives a deterministic skip past variable-content payloads.

---

## 4. Phased plan with verification

Each phase ends in a passing integration test. If the test fails, halt and debug.

### Phase A — `ConcatBlobParser` + unit tests

**Build:**

- `ConcatBlobParser.parse(byte[] storedBlob)` returns `(base: byte[], operands: List<byte[]>)`.
- Handles three input shapes:
  1. Materialized only (`[schemaId][0x00][avro]`) — returns `(avro, [])`.
  2. Operand only, no base yet (`[0x01][len][op]…`) — returns `(null, [op1, op2, …])`.
  3. Base + operands (the normal case after StringAppendOperator FullMerge) — returns `(avro, [op1, op2, …])`.
- Robust against operand payload bytes containing `0x2C`. Tested by including such payloads in unit tests.
- ~80 LOC implementation, ~150 LOC unit tests (test class: `ConcatBlobParserTest`).

**Verify:**

- ✅ `./gradlew :clients:da-vinci-client:test --tests "*ConcatBlobParserTest*"` green
- ✅ Round-trip property test: `parse(concat(base, op1, op2)) == (base, [op1, op2])` for randomized inputs

**Exit:** parser unit tests pass.

### Phase B — `MaterializingRocksDBStoragePartition` + smoke test

**Build:**

- New class extending `RocksDBStoragePartition`. Overrides:
  - `put(byte[] key, ByteBuffer value)` → prepend `0x00` kind byte (the schemaId stays where it is in today's layout).
  - `merge(byte[] key, ByteBuffer operand)` → prepend `0x01` + varint length.
  - `get(byte[] key)` → call super.get; if returned bytes parse to a materialized-only blob, strip the kind byte and
    return the avro bytes; if they parse to base + operands, decode base via Avro, apply operands via
    `WriteComputeProcessor.applyWriteCompute`, encode result, and return it (without kind byte, ready for downstream
    Avro decode).
  - `get(byte[] key, byte[] valueToBePopulated)` and other overloads as needed for the existing API surface.
- Wire `RocksDBStorageEngineFactory.createStoragePartition(...)` to instantiate this subclass when the feature flag is
  on.
- One smoke test: `MaterializingPartitionSmokeTest` does `put → get` (expect base back) and `put → merge → merge → get`
  (expect base+operands folded into materialized form).

**Verify:**

- ✅ Smoke test green
- ✅ Existing `LeanHarnessStorageSmokeTest` still green when flag is OFF (regression check)

**Exit:** smoke test passes.

### Phase C — Run the 7 in-scope `PartialUpdateTest` invocations with flag ON

**Build:** No code changes. Configure the test's cluster config to set `server.vt.update.operand.enabled=true`. Use
TestNG's `-methods` filter (or equivalent) so only the in-scope test methods run; the two `testBatchProcessor*` methods
are excluded.

**Verify:**

- ✅ `testPartialUpdateOnBatchPushedKeys` × 3 (compression strategies) pass with flag ON
- ✅ `testActiveActivePartialUpdateOnBatchPushedChunkKeys` passes with flag ON
- ✅ `testActiveActivePartialUpdateWithCompression` × 3 pass with flag ON
- ✅ Same 7 invocations still pass with flag OFF (regression check)

**Exit:** all 7 invocations pass under both flag states.

If any fail:

- Common failure modes: chunked values (large records exceed RocksDB chunk threshold and use a different code path),
  compression interaction (compress/decompress wrapping the kind-byte-framed bytes), tombstones from DELETEs — confirm
  each passes through correctly.
- Per failure: write a focused unit test that reproduces it, fix the code, re-run.

### Phase D — Sweeper fold logic

**Build:** Replace the `// Phase 2 placeholder` block in `PartitionSweeper.sweepOnce` (line 96) with the actual fold:

- Read value via `partition.get(key)` (which now returns materialized bytes thanks to Phase B's read fold).
- If the underlying RAW bytes (read separately via a low-level path that bypasses the materializing override) are concat
  form, write back the materialized bytes via `partition.put(key, materialized)`.
- Honor `server.merge.sweep.threshold` — only fold if operand count exceeds the threshold.

This phase needs a low-level "read raw bytes" hook so the sweeper can decide whether folding is needed without paying
the cost of folding twice. Add `MaterializingRocksDBStoragePartition.getRaw(...)` that bypasses the auto-fold.

**Verify:**

- ✅ Unit test: feed sweeper a key with N>threshold operands, run `sweepOnce`, verify the stored bytes are now
  materialized form (single `[schemaId][0x00][avro]` blob).
- ✅ Run the 7 in-scope `PartialUpdateTest` invocations with flag ON AND `server.merge.sweep.enabled=true` — must still
  pass.
- ✅ Lean benchmark with `designMode=MERGE_OPERAND_SWEPT` — confirm storage size doesn't bloat over time (run with
  longer measurement window if needed to actually exercise sweep cycles).

**Exit:** sweeper actually materializes and the 7 in-scope integration tests still pass.

---

## 4.5 Failure handling and retry policy

When a phase's verification step fails, classify the failure into one of three tiers and respond accordingly.

### Tier 1 — Code bug

The test fails because of an obvious mistake in our new code: NPE, wrong byte offset, missing null check, mis-encoded
varint, wrong schema-id lookup, etc.

- **Response:** debug, fix, retry. No upper bound on attempts as long as each iteration produces a concrete identifiable
  bug + a localized fix that respects the design contracts in §3 (wire format) and §6 (edge cases).
- **Examples:** `ConcatBlobParser` returns wrong slice boundaries → fix slicing → re-run.
  `MaterializingRocksDBStoragePartition.get` forgets to strip the schema-id when re-encoding → fix → re-run.

### Tier 2 — Edge case the GOAL didn't fully cover

The test exposes a scenario §6 didn't account for, but the fix is bounded and consistent with the design.

- **Response:** either (a) fix the code AND extend §6 with the new edge case + its handling, or (b) document as
  out-of-scope, exclude the test from the target with justification, continue.
- **Examples:** Chunked-value manifest indirection requires the wrapper to be transparent for chunk-write paths → fix +
  add to §6. A test path uses a non-AA route that wasn't considered → either handle or exclude.
- **Iterate** until green. If "fix it" turns into 3+ different attempted fixes that each fail differently, escalate to
  Tier 3 — the edge case may not actually be tier-2.

### Tier 3 — Design problem

The failure indicates the design itself is wrong, not just an implementation gap. Fixing it would require changing Phase
A or B's wire format or breaking a §3 contract.

- **Response:** halt. Write a failure report to `phase-X-progress.md` with: the failing test, the raw failure, the last
  2-3 attempted fixes with why each didn't work, the agent's hypothesis about the design problem, the proposed change to
  §3 or §6 that would be needed. Escalate to user.
- **Examples:** Compaction's `StringAppendOperator::FullMerge` produces bytes that genuinely cannot be disambiguated by
  the parser. A schema-evolution scenario produces operands that can't be applied to a base of a different schema id
  without an unsupported re-encoding step. Leader/follower RocksDB layouts diverge in a way that breaks the read fold on
  followers but not the leader.

### How to classify a failure

A failure is **Tier 1** if all of these are true:

- The fix is local (one file, one method).
- The fix is consistent with §3 wire format and §6 edge cases.
- You can articulate "the bug is X, the fix is Y" in one sentence.

A failure is **Tier 2** if all of:

- The fix requires extending §6 with a new edge case.
- The new edge case doesn't violate §3.
- You can articulate the fix concretely without changing Phase A/B contracts.

A failure is **Tier 3** if any of:

- You've tried 3+ different fixes, each failed for an apparently independent reason.
- The fix requires changing Phase A/B's wire format.
- You cannot articulate a concrete fix that respects the existing §3.

### Per-phase iteration budget (soft caps)

These are signals to escalate, not hard stops. If the budget is hit and the phase still fails, write up the situation
and escalate even if no Tier-3 hallmark has appeared — at that point further iterating is unlikely to converge without
human input.

| Phase                               | Iteration budget                                                                                                  |
| ----------------------------------- | ----------------------------------------------------------------------------------------------------------------- |
| Phase A (parser)                    | 5 fix attempts                                                                                                    |
| Phase B (read fold)                 | 8 fix attempts (more surface area)                                                                                |
| Phase C (per failing test)          | 5 fix attempts per test; if 2+ tests fail with apparently independent root causes, that itself is a Tier 3 signal |
| Phase D (sweeper fold + write-back) | 5 fix attempts                                                                                                    |

### Test flakiness vs real bugs

The 7 in-scope integration tests use `TestUtils.waitForNonDeterministicAssertion(10s, ...)` which absorbs minor timing
variance. If a test passes flag=OFF deterministically but fails flag=ON intermittently, treat it as a real bug (likely a
race in the read fold or sweeper), not test flakiness. If both flag=OFF and flag=ON show the same intermittency, that's
existing test flakiness — log it and continue.

### What to commit for each retry cycle

Each iteration that produces code change should be its own commit (`--no-verify` per session convention) so the audit
trail shows the fix-attempt sequence. The phase progress doc records: failure → hypothesis → fix → result for each
iteration. This lets the next session (if a phase carries over) pick up cleanly.

---

## 5. Verification logistics

- All four integration test classes are run with both `flag=OFF` and `flag=ON`. Both must pass.
- A new CI smoke target: `./gradlew :internal:venice-test-common:integrationTest --tests "*PartialUpdate*"` runs
  everything in one go.
- For each phase, a `phase-X-progress.md` is committed with: code changes (commit SHAs), test results (pass/fail per
  `@Test`), issues encountered, fixes applied.
- Lean harness `LeanActiveActiveIngestionBenchmark` runs once with `flag=ON` after Phase D as a non-regression check on
  throughput. Throughput must remain within ~10% of the prior experiment's MERGE_OPERAND number (64.8K ops/s E2E). If it
  regresses materially, the read-side fold has overhead worth investigating.

**Note on test-target wiring:** all `@Test` invocations in `PartialUpdateTest` other than the 7 in-scope ones are
excluded from this experiment's test target. The two `testBatchProcessor*` tests stay in the codebase but are filtered
out via TestNG `-methods` (or excluded by adding a TestNG group). They become the oracle for the follow-up
cross-region-DCR experiment.

---

## 6. Edge cases to handle

| Case                                            | Expected behavior                                                                                                                                                                                 |
| ----------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Key has only a base PUT, no merges yet          | `parse` returns `(base, [])`, `get` strips kind byte and returns avro                                                                                                                             |
| Key has base + operands, no compaction yet      | `get` reads concat blob, folds, returns materialized avro                                                                                                                                         |
| Key has only operands (first write was a merge) | `get` decodes operands assuming an empty record as base, applies WC ops, returns materialized avro. Document the schema-id resolution rule (use the latest WC schema id from the schema repo).    |
| Key was deleted (`storage.delete`) then merged  | Standard RocksDB Delete semantics — operand goes onto a tombstone. `get` should return null. `MaterializingRocksDBStoragePartition.get` must correctly forward null from super.                   |
| Chunked values                                  | Today's chunking writes a manifest + chunks; the materializing wrapper must NOT touch the chunk-write path. Only the manifest's value bytes are wrapped.                                          |
| Schema evolution mid-test                       | If operand was written against schema-id N and base is schema-id M, the WC apply must look up the right WC schema. Today's `WriteComputeProcessor` already handles this — pass through correctly. |
| Empty operand list after parse                  | Defensive: skip the fold, return base bytes unchanged.                                                                                                                                            |
| Very large operand chains (1000+)               | No correctness issue, just slower fold. Document as a sweeper-tuning concern, not a correctness bug.                                                                                              |

---

## 7. Risks and what would invalidate the design

| Risk                                                                   | How we'd see it                                                                                                | Response                                                                                                                                                    |
| ---------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Avro round-trip not byte-equivalent                                    | Integration test fails on `assertEquals(record.get("field"), expected)` after write-then-read                  | Investigate WC apply edge case; usually a missing field default or a schema mismatch                                                                        |
| Chunked values break under wrapping                                    | `testActiveActivePartialUpdateOnBatchPushedChunkKeys` fails (1000-item map per record exceeds chunk threshold) | Make the wrapper transparent for chunk-manifest paths; only wrap the user-visible value bytes                                                               |
| StringAppendOperator delimiter collides with operand bytes             | Sporadic parse errors with non-deterministic key sets                                                          | Use length-prefix on operands as the authoritative boundary; treat `,` as a non-required hint, not a parser anchor                                          |
| Read fold doubles read latency p99 beyond acceptable bounds            | Read-amp regression in lean benchmark                                                                          | Acceptable for correctness goal; revisit only if a concrete SLA breaks                                                                                      |
| Compaction-time FullMerge produces different bytes than read-time fold | A key reads correctly initially but returns wrong bytes after compaction                                       | Verify by running the integration tests AFTER forcing a manual compaction (`db.compactRange(null, null)`). If divergent, add a compaction filter or rethink |

---

## 8. Decision criteria for "this is correct"

A YES requires all three conditions:

- ✅ All 7 in-scope invocations from `PartialUpdateTest` (`testPartialUpdateOnBatchPushedKeys` × 3 +
  `testActiveActivePartialUpdateOnBatchPushedChunkKeys` + `testActiveActivePartialUpdateWithCompression` × 3) pass with
  flag ON
- ✅ Same 7 invocations still pass with flag OFF (no regression to existing path)
- ✅ Phase D sweeper unit test passes (sweeper actually materializes and storage shrinks observably)

If any test fails after fixing the obvious issues from §6, halt and reframe. The integration tests are the contract;
nothing else is.

---

## 9. Why integration tests are the right correctness oracle

These tests already encode "Venice's partial-update semantics are what they are." They cover:

- Field-level partial updates on scalar, map, list, and nested-record fields
- Cross-region AA replication semantics
- DCR conflict resolution (timestamp-based)
- Schema evolution mid-test
- Tombstones and deletes
- Chunked values

If our new read-path fold passes all of these, it is observably correct against the same contract today's implementation
passes. We don't need to invent a new correctness oracle — we just need to satisfy the existing one.

The throughput-comparison goal (write-side win) is preserved: we run the existing JMH benchmark as a final sanity check,
but the bar there is "don't regress catastrophically," not "match the prior experiment's headline number." Correctness
comes first.
