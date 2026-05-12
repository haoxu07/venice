# GOAL — VT-merge cross-DC correctness via FieldLevelRmdCache

**Branch:** `haoxu07/vt-rocksdb-merge-design` **Created:** 2026-05-12 **Predecessor:**
`autoresearch/vt-merge-rmd-aware-fold/OUTCOME.md` (commit `54803d4d6`) — PARTIAL handoff

## §1 Context

The prior work-stream (`vt-merge-rmd-aware-fold`) landed the V2 fold-path routing (44× per-call speedup) and closed
`testActiveActivePartialUpdateWithCompression` flag-on timeout. Result: 16/24 integration tests pass.

Three things remain to reach 24/24:

1. **Utf8 → String ClassCastException** in `applyWriteComputeV2` map-field path —
   `testAAReplicationForPartialUpdateOnMapField` hits a 502 from this bug specifically. Small concrete fix, ~5 LOC.
2. **Cross-DC AA-DCR correctness** — `testAAReplicationForPartialUpdateOnFields/OnListField/OnMapField` fail because the
   iter-3 fold path uses a synthesized seed-RMD with chain-position timestamps instead of the operand's real
   `updateOperationTimestamp`. Whichever operand lands later in the chain wins, regardless of logical ts. **The fix is
   the `FieldLevelRmdCache` design from `vt-merge-rmd-aware-fold/GOAL.md` §3-§4.**
3. **5 untested §5.4.1 flag-off baselines** — gates added under `if (vtMergeFlagOn)`, so flag-off is logically unchanged
   but not empirically re-confirmed. Re-run at the end as a sanity check.

## §2 Goal

End-state: full 24-invocation integration matrix passes. Cross-DC AA-DCR semantics under flag-on match flag-off
baseline. V2 fold path uses real operand timestamps + per-(key, fieldId) cache.

## §3 Scope

### In scope

- Track 1 — Utf8 → String ClassCastException fix
- Track 2 — `FieldLevelRmdCache` end-to-end (full GOAL §3-§4 design)
- Track 3 — 5 flag-off baseline regression confirmation
- §5.4.2 cross-DC tests pass under flag-on
- Microbenchmark proving cache + V2 with real ts is within 2× of current seed-RMD V2

### Out of scope

- Schema evolution (cache uses field ordinal; stable field-id is follow-up)
- JMH benchmark integration (microbenchmark suffices)
- Cache persistence across partition reopen (in-memory only)
- Cross-DC cache coherence (cache is partition-local)
- Production safety rails (out of scope per user direction)

## §4 Tracks

### §4.1 Track 1 — Utf8 → String ClassCastException fix (1-2 iterations)

**Goal:** Fix `testAAReplicationForPartialUpdateOnMapField` flag-on 502.

**Symptom (from `vt-merge-rmd-aware-fold/iter-6-NOTES.md`):** Server returns 502 with
`ClassCastException: Utf8 cannot be cast to String` when applying map operands via V2 fold.

**Diagnostic step:**

1. Run `testAAReplicationForPartialUpdateOnMapField` flag-on with current branch tip; capture stack trace from server
   logs or `forensics/` directory.
2. Identify cast site: likely in `StoreWriteComputeProcessor.applyWriteComputeV2`'s seed-RMD construction for map
   fields, or in V2's `handleModifyMap` reading map keys (Avro produces `Utf8`, downstream uses `String`).

**Hypothesis (working theory):** Avro `MAP` deserialization in the seed-RMD pathway produces `Utf8` keys, but V2's
collection handler casts to `String`. The V1 path may have coerced via `.toString()` somewhere we're not replicating in
`applyWriteComputeV2`.

**Fix shape:** ~5 LOC `.toString()` coercion at the cast site + a unit test in `TestWriteComputeSeedRmd` covering
map-field SET_UNION + MAP_DIFF operands.

**Halt:**

- Unit test passes
- `testAAReplicationForPartialUpdateOnMapField` flag-on no longer 502s (may still fail at the functional DCR assertion —
  that's Track 2's territory; the 502 specifically must be gone)

**Why first:** small, concrete, decouples cleanly from the cache work. Lets Track 2 start with one less failure mode in
scope.

### §4.2 Track 2 — `FieldLevelRmdCache` end-to-end (~7 iterations)

The full design is in `autoresearch/vt-merge-rmd-aware-fold/GOAL.md` §3-§4 — re-read it before starting. Below is the
abbreviated execution plan.

**Goal:** Restore cross-DC AA-DCR correctness under flag-on by replacing iter-3's seed-RMD synthesis with a per-(key,
fieldId) RMD cache populated on the follower's merge() path, sourced from RocksDB on cold-key miss.

#### Phase 2a — Cache infrastructure (iter 2a.1, 2a.2)

- New package: `com.linkedin.davinci.replication.rmdcache.field`
- `FieldLevelRmdCache` class:
  - Lock-free `(keyHashHi : 48 bits) | (fieldOrdinal : 16 bits) → entry` map
  - Per-key mode flag (whole-record-ts vs per-field-ts) at `(keyHash, fieldOrdinal=-1)` sentinel
  - Scalar entries (long ts) and collection entries (`CollectionRmdTimestamp`)
  - LRU eviction + watermark for evicted-but-still-in-DB entries
  - Bloom filter for "definitely-not-in-DB" branch
- Unit tests for the state machine:
  - Scalar DCR (win/lose/fallback)
  - Collection DCR (top-level-ts gate, element-level via SortBasedCollectionFieldOpHandler)
  - Mode-0 ↔ mode-1 transitions with cross-field invalidation
  - Eviction + watermark raising
  - Bloom branches (definitely-not, maybe → cache hit, maybe → cache miss → watermark, maybe → cache miss → fallback)

**Halt at end of Phase 2a:** unit tests must cleanly demonstrate the state machine. If they don't, reassess cache shape
before continuing.

#### Phase 2b — Follower merge() wire-in (iter 2b.1, 2b.2)

- `MaterializingReplicationMetadataRocksDBStoragePartition.merge`:
  - Parse incoming operand (operand-content bytes already framed) to extract `updateOperationTimestamp` + modified-field
    set
  - For each modified field, consult cache → win/lose decision
  - If all fields lose → drop the operand (no chain append). Add a stats counter for dropped operands.
  - If any field wins → update cache entries + append operand to chain (current behavior preserved)
- Cold-key fallback: same `getRmdWithValueSchemaByteBufferFromStorage` path the AA leader uses; eagerly populate all
  per-field cache entries from one RocksDB read
- Tests:
  - Cross-DC scenario: dc-0 op at ts=100, dc-1 op at ts=80 → dc-0 wins, dc-1 dropped
  - Cold-key scenario: first op arrives → cache miss → simulated RocksDB read → cache populated

**Halt at end of Phase 2b:** baseline AA flag-off tests must not regress (merge() code only active under flag-on).

#### Phase 2c — Fold-path V2 routing with cache-sourced RMD (iter 2c.1)

- Replace iter-3's seed-RMD synthesis (single-DC-only correct) with cache-sourced `CollectionRmdTimestamp`
- For each operand in chain (foldOperands and foldOperandOnly):
  - Get cached `CollectionRmdTimestamp` for each modified collection field
  - Pass real operand `updateOperationTimestamp` into V2 algorithm
  - V2's `SortBasedCollectionFieldOpHandler.handleModifyList` now does proper element-level DCR
- Update cached RMD with new active_elem_ts / deleted_elem_ts post-fold
- Microbenchmark: confirm cache + real-ts V2 fold is within 2× of iter-3's seed-RMD V2 fold

**Halt at end of Phase 2c:** if microbenchmark shows >2× slowdown, the cache lookup or RMD construction is dominating;
reassess.

#### Phase 2d — Integration test validation (iter 2d.1)

- Run §5.4.2 cross-DC tests flag-on:
  - `testAAReplicationForPartialUpdateOnFields` → expected PASS (dc-1 UPDATE at ts=1 should now lose to dc-0 PUT at
    ts=2)
  - `testAAReplicationForPartialUpdateOnListField` → expected PASS (element-level DCR via V2)
  - `testAAReplicationForPartialUpdateOnMapField` → expected PASS (depends on Track 1 also being landed)
- Run §5.4.1 + §5.4.3 flag-on regression — must still pass
- Run §5.4.2 flag-off baseline — must still pass

**Halt at end of Phase 2d:** 3/3 §5.4.2 flag-on must pass. If any fail, diagnose: cache logic bug, cold-key fallback
bug, or per-element-ts construction bug.

#### Phase 2e — Cleanup (iter 2e.1)

- Remove diagnostic logs added during investigation
- Document cache config knobs in `ConfigKeys.java` (max size, time window, bloom params)
- Confirm `FieldLevelRmdCache` invariants in a comment block

### §4.3 Track 3 — Flag-off regression rerun for §5.4.1[1-5] (1 iteration)

**Goal:** Confirm iter-4's `if (vtMergeFlagOn)` gates didn't accidentally break flag-off behavior in the 5 unrun tests.

**Tests (all flag-off):** | # | Test | |---|---| | 1-3 | `testPartialUpdateOnBatchPushedKeys[NO_OP/GZIP/ZSTD_WITH_DICT]`
| | 4 | `testActiveActivePartialUpdateOnEmptyPush` | | 5 | `testActiveActivePartialUpdateOnBatchPushedChunkKeys` |

Run in one gradle invocation. Confirm all pass. If any fails: diagnose — gate logic bug or pre-existing flake.

**Halt:** 5/5 pass.

**Why last:** validation step, no code changes expected. Done after Track 1 and Track 2 to confirm none of their work
regressed flag-off.

## §5 Per-iteration discipline (all tracks)

- Write `iter-<track>-<n>-NOTES.md` (e.g. `iter-1-1-NOTES.md`, `iter-2c-1-NOTES.md`)
- Commit with `[vt-merge-rmd2]` subject prefix + Co-Authored-By trailer
- Unit-test-first for any new behavior
- Fail-fast on integration tests with `--init-script /tmp/no-retry.gradle`:
  ```groovy
  allprojects {
    tasks.withType(Test).configureEach {
      if (extensions.findByName('retry')) {
        retry { maxRetries = 0 }
      }
    }
  }
  ```
  The `if (extensions.findByName('retry'))` guard is mandatory — modules without the plugin fail config-time without it.
- Kill stale gradle workers between major test runs (count > 10 → `pkill -9 -f GradleWorkerMain`)

## §6 Iteration budget

Total: **11 iterations**.

- Track 1: 1-2 iter
- Track 2: 7 iter (matches the cache design's original budget in predecessor GOAL)
- Track 3: 1 iter
- Buffer: 1-2 iter

## §7 Halt triggers

**Global:**

- All 24 invocations pass → `COMPLETE.md`, stop
- 11 iterations exhausted with PARTIAL → `OUTCOME.md`
- Design question genuinely needing human input → `BLOCKED.md`
- Baseline AA flag-off regression at any phase → hard halt

**Track-specific:**

- Track 1 fix doesn't eliminate the 502 → halt; deeper investigation needed
- Track 2 Phase 2a unit tests can't demonstrate the state machine → halt; reassess shape
- Track 2 Phase 2c microbenchmark >2× regression from iter-3 → halt; cache overhead too high
- Track 2 Phase 2d any §5.4.2 test still fails → halt; diagnose cache logic

## §8 Final report shape

`COMPLETE.md` if 24/24 PASS. Otherwise `OUTCOME.md` under `autoresearch/vt-merge-rmd-cache-and-safety/`.

Report back to human in < 500 words:

1. **Per-track outcome:** Track 1 = done/partial/blocked, Track 2 phase-by-phase, Track 3 = done/partial
2. **Test results:** N/24 with category breakdown (§5.4.1 / §5.4.2 / §5.4.3 × flag-on / flag-off)
3. **Commits:** list of `[vt-merge-rmd2]`-prefixed commits with one-line descriptions
4. **Microbenchmark numbers:** cache + real-ts V2 vs iter-3's seed-RMD V2 per-fold cost at the 380K-float base size
5. **Sanity-check item:** the one thing the human should review carefully (likely cache concurrency, mode-transition
   logic, or cold-key fallback eager-population)
6. **If PARTIAL:** what's left, what specifically unblocks it

## §9 References

- Predecessor: `autoresearch/vt-merge-rmd-aware-fold/OUTCOME.md` — PARTIAL handoff describing the V2 fold work that's
  already landed
- Cache design (primary): `autoresearch/vt-merge-rmd-aware-fold/GOAL.md` §3-§4 — full `FieldLevelRmdCache` shape,
  cold-key fallback, mode tracking, fold/merge wire-in
- §5.4.2 failure analysis: `autoresearch/vt-merge-rmd-aware-fold/iter-6-NOTES.md`
- iter-3 V2-fold-path code: `MaterializingFoldContext.foldOperands/foldOperandOnly` (commit `d661d400b`)
- Schema-converter one-layer-of-collection constraint: `WriteComputeSchemaConverter.java:40-41`
- AA leader RMW pattern (the model we're mirroring): `MergeConflictResolver.update()` + `MergeGenericRecord.update()` +
  `PartitionConsumptionState.TransientRecord`
- Existing PUT-side cache pattern: `RmdTimestampCache` / `RmdTimestampCacheManager`
- Microbenchmark for perf baseline: `WriteComputeArrayApplyMicrobenchmark`
