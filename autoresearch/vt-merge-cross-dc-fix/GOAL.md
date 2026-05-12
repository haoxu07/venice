# GOAL — Cross-DC AA-DCR via FieldLevelRmdCache (final 3 tests)

**Branch:** `haoxu07/vt-rocksdb-merge-design` **Created:** 2026-05-12 **Predecessors:**

- `autoresearch/vt-merge-rmd-aware-fold/OUTCOME.md` (commit `54803d4d6`) — V2 fold landed
- `autoresearch/vt-merge-rmd-cache-and-safety/OUTCOME.md` (commit `0f55cb133`) — Utf8 + Phase A wire-format landed

## §1 Context

After two prior work-streams, the integration matrix is at **20/24 PASS**. The remaining **3 failures are all cross-DC
AA-DCR** (`TestPartialUpdateWithActiveActiveReplication`):

| Test                                           | Symptom                                                                       |
| ---------------------------------------------- | ----------------------------------------------------------------------------- |
| `testAAReplicationForPartialUpdateOnFields`    | dc-1 UPDATE@ts=1 overrides dc-0 PUT@ts=2 — should LOSE                        |
| `testAAReplicationForPartialUpdateOnListField` | List elements from both DCs accumulate; no element-level DCR                  |
| `testAAReplicationForPartialUpdateOnMapField`  | Functional DCR fails (the 502 ClassCastException is already fixed by Track 1) |

### What's already in place from prior work

1. **V2 fold path** (`d661d400b`): `MaterializingFoldContext` uses V2 algorithm; 44× faster than V1 on append workloads
2. **Wire-format extension** (`59ffe77f5`): Operand carries real `updateOperationTimestamp` end-to-end (leader → wire →
   follower → fold)
3. **Utf8/HashMap coercion** (`6afb38b5e`, `5e4038ef0`): V2 handler tolerates Utf8 keys + HashMap PUT-NEW-FIELD payloads
4. **Phase B attempt notes** (reverted but preserved): `autoresearch/vt-merge-rmd-cache-and-safety/iter-2b-1-NOTES.md`
   documents what was tried + the two gotchas it surfaced

### What's missing — the `FieldLevelRmdCache`

Today's flag-on fold uses a put-only seed RMD with `topLevelTs=0`. That works for single-DC chains (every operand wins,
chain order = logical order) but breaks cross-DC. The fix is a per-(key, fieldOrdinal) RMD cache populated on the
follower's `merge()` path and consulted by the fold path. The cache mirrors the AA leader's `TransientRecord` pattern
from baseline, applied at the follower layer.

## §2 Goal

End-state: **24/24 integration tests pass.** Flag-on AA-DCR semantics match flag-off baseline. Per-fold cost stays
within 2× of the iter-3 V2 baseline (~93ms at 380K-float base size).

## §3 Scope

### In scope

- `FieldLevelRmdCache` implementation per the design in `autoresearch/vt-merge-rmd-aware-fold/GOAL.md` §3-§4
- Two new design requirements surfaced by Phase B (see §4.2 and §4.3)
- Follower merge() wire-in
- Fold path wire-in with cache-sourced RMD (replaces today's put-only seed)
- §5.4.2 cross-DC tests pass
- Microbenchmark proving per-fold cost stays within 2× of iter-3 baseline

### Out of scope

- Schema evolution (cache keys on field ordinal; stable field-id is follow-up)
- JMH benchmark integration
- Cache persistence across partition reopen (in-memory only, rebuilds on partition open)
- Cross-DC cache coherence (cache is partition-local; each DC builds its own)
- Production safety rails

## §4 Design

The full design lives in `autoresearch/vt-merge-rmd-aware-fold/GOAL.md` §3-§4. This section recaps the key pieces with
the two new requirements integrated.

### §4.1 Cache shape (unchanged from original)

```java
public final class FieldLevelRmdCache {

  // Packed (keyHashHi : 48 bits) | (fieldOrdinal : 16 bits) → primitive long key
  private final LockFreeLongObjectMap<FieldRmdEntry> cache;

  // Per-key mode at sentinel slot (keyHash, fieldOrdinal=-1)
  private final LockFreeLongLongMap perKeyMode;

  private final PartitionBloomFilter bloomFilter;
  private final AtomicLong watermarkTsForEvictedEntries;

  static final class FieldRmdEntry {

    final long topLevelFieldTs;
    final CollectionRmdTimestamp rmdSubtree; // non-null for collection fields
    final boolean populatedByPut; // NEW — see §4.3
    final long lastAccessNs;
  }
}

```

Mode 0 = whole-record-ts (PUT/DELETE was last operation). Mode 1 = per-field-ts (partial UPDATE was last operation).
Field ordinal -1 reserved for mode sentinel.

### §4.2 NEW design requirement — `isInPutOnlyState` invariant for empty collection fields

**Surfaced by Phase B's `OnListField` regression.**

`SortBasedCollectionFieldOpHandler.handleModifyList` branches on `collectionFieldRmd.isInPutOnlyState()`, which checks
if both `activeElementsTimestamps` and `deletedElementsTimestamps` are empty.

When a cache entry is synthesized from a PUT (Mode 0 → Mode 1 transition) for a **collection field that was empty at PUT
time**, the naive synthesis produces `{topLevelTs = PUT.ts, activeElemTs = [], deletedElemTs = []}` — which is
`isInPutOnlyState() == true`. V2 then treats every existing element as having ts = `PUT.ts`, which is wrong for elements
that came in via later UPDATEs.

**Requirement:** when synthesizing a cache entry for a collection field, track whether the field was actually populated
by the PUT. If empty-at-PUT, set the synthesized `topLevelTs` to `Long.MIN_VALUE` (or 0) so subsequent UPDATEs win and
establish per-element-ts naturally.

### §4.3 NEW design requirement — "untouched-by-PUT" fields shouldn't have PUT-ts as DCR floor

**Surfaced by Phase B's per-field-ts analysis.**

If a record is PUT at ts=100 with field A populated and field B left at default, then UPDATE arrives at ts=50 touching
field B:

- Field A's ts is genuinely 100 → UPDATE at ts=50 must LOSE on A ✓
- Field B was never set by the PUT → using ts=100 as its floor wrongly rejects the UPDATE on B

**Requirement:** distinguish "field was set by PUT" vs "field was at default at PUT time". Add a
`populatedByPut: boolean` bit per cache entry. On Mode 0 → Mode 1 conversion:

- Field populated by PUT → `topLevelFieldTs = PUT.ts`, `populatedByPut = true` → DCR floor applies
- Field default at PUT → `topLevelFieldTs = Long.MIN_VALUE`, `populatedByPut = false` → no DCR floor

The PUT-only-part-length in `CollectionRmdTimestamp` already encodes this partially for collection fields
(`putOnlyPartLength = 0` means no elements came in via PUT). Lift that distinction to a scalar bit at the cache-entry
level for uniformity.

### §4.4 Mode transitions (unchanged conceptually + integrated with §4.2/§4.3)

- **Mode 0 → Mode 1** (UPDATE after PUT): consult the read base record, eagerly synthesize all per-field entries with
  `populatedByPut` correctly set per field. Then apply the UPDATE through V2.
- **Mode 1 → Mode 0** (PUT after UPDATEs): evict ALL per-field entries for this key; install single mode-0 sentinel with
  PUT's ts.

### §4.5 Cold-key fallback (unchanged)

On cache miss + bloom branch B.2.b: read RMD from RocksDB via `getRmdWithValueSchemaByteBufferFromStorage`. Eagerly
populate all per-field entries from one read.

### §4.6 Fold-path wire-in (replaces today's put-only seed)

`MaterializingFoldContext.foldOperands` becomes:

1. For each operand, fetch per-field cache entries for the operand's modified fields (cold-fetch from RocksDB if cache
   miss)
2. For each modified field, pass the cached `topLevelFieldTs` + `CollectionRmdTimestamp` to V2 algorithm
3. V2 does proper DCR using the operand's real `updateOperationTimestamp` (from Phase A wire format) against the cached
   per-field-ts
4. Update cache with new per-element-ts post-fold

The Phase B plumbing code that was reverted is reusable — it had the right shape, just missing §4.2/§4.3 handling.

### §4.7 Merge-path wire-in

`MaterializingReplicationMetadataRocksDBStoragePartition.merge`:

1. Parse incoming operand to get `(updateOperationTimestamp, modifiedFieldSet)` (Phase A wire-format makes this trivial)
2. For each modified field, consult cache via 4-branch decision logic
3. If all fields lose → drop the operand from the chain entirely (stats counter)
4. If any field wins → update cache + append operand to chain

## §5 Phases

### Phase 1 — Cache infrastructure + state machine unit tests (iter 1-2)

Tasks:

- New package `com.linkedin.davinci.replication.rmdcache.field`
- `FieldLevelRmdCache` class with lock-free map shape, per-key mode sentinel, bloom filter, LRU eviction, watermark
- `FieldRmdEntry` POJO including the new `populatedByPut` bit
- Unit tests:
  - 4-branch DCR decisions (scalar + collection)
  - Mode 0 ↔ Mode 1 transitions with cross-field invalidation
  - **NEW:** synthesize Mode-0→Mode-1 with `populatedByPut=false` for empty collection fields (Gotcha #1)
  - **NEW:** synthesize Mode-0→Mode-1 with `populatedByPut=false` for default scalar fields (Gotcha #2)
  - Eviction + watermark raising
  - Bloom branches (definitely-not, maybe → hit, maybe → miss + watermark, maybe → miss + fallback)

**Halt:** unit tests cleanly demonstrate the state machine including both gotchas. If they don't, the synthesis logic is
wrong; halt and reassess.

### Phase 2 — Follower merge() wire-in (iter 3)

Tasks:

- `MaterializingReplicationMetadataRocksDBStoragePartition.merge`: cache consultation + drop-or-append decision
- Cold-key fallback hook
- Stats counter for dropped operands
- Unit tests for cross-DC win/lose decisions; cold-key fallback path

**Halt:** baseline AA flag-off tests must not regress (merge() code only active under flag-on).

### Phase 3 — Fold-path V2 routing with cache-sourced RMD (iter 4)

Tasks:

- Restore the Phase B plumbing from the reverted notes
- Pass cache-sourced `topLevelFieldTs` + `CollectionRmdTimestamp` into V2 (replace today's put-only seed)
- Honor `populatedByPut` bit: if false, treat the field as having no DCR floor
- Microbenchmark: confirm per-fold cost within 2× of iter-3 baseline (~93ms at 380K-float base)

**Halt:** microbenchmark regression > 2× → halt; cache-lookup or RMD-construction is dominating.

### Phase 4 — Integration test validation (iter 5)

Run all 24 invocations from the prior gate:

- §5.4.1 flag-on ×7 — must still pass (no regression)
- §5.4.1 flag-off ×7 — must still pass
- §5.4.2 flag-on ×3 — **must NOW pass** (this is the goal)
- §5.4.2 flag-off ×3 — must still pass
- §5.4.3 flag-on ×3 — must still pass
- 1 sister test — must still pass

**Halt:** any §5.4.2 flag-on still fails → halt; cache semantics are wrong on a specific failure category. Diagnose:

- `OnFields` fail → scalar Gotcha #2 (`populatedByPut`) not honored
- `OnListField` fail → collection Gotcha #1 (`isInPutOnlyState` invariant) not honored
- `OnMapField` fail → could be either (map fields hit both)

### Phase 5 — Cleanup (iter 6)

- Remove diagnostic logs
- Document config knobs in `ConfigKeys.java`
- Write `COMPLETE.md` (or `OUTCOME.md` if not 24/24)

## §6 Per-iteration discipline

- Write `iter-N-NOTES.md` with hypothesis → change → result → next step
- Commit with `[vt-merge-cross-dc]` subject prefix + Co-Authored-By trailer
- Unit-test-first for any new behavior
- Fail-fast init script (`/tmp/no-retry.gradle` with `extensions.findByName('retry')` guard)
- Kill stale gradle workers (`pkill -9 -f GradleWorkerMain`) when count > 10

## §7 Iteration policy — keep iterating until the gap is fixed

**No fixed iteration cap.** The user has explicitly asked the agent to keep iterating until the cross-DC AA-DCR failures
are fixed (24/24 PASS). Phase order below is a guide, not a budget; agent may revisit phases as diagnostic findings
demand.

**Indicative phase sizes** (for self-orientation, not gates):

- Phase 1: ~2 iter (cache infra + state machine)
- Phase 2: ~1 iter (merge wire-in)
- Phase 3: ~1 iter (fold wire-in)
- Phase 4: ~1 iter (integration validation; iterate here as needed until 24/24)
- Phase 5: ~1 iter (cleanup + report)

If validation reveals issues, iterate further on Phase 1-3 to fix the cache state machine, then re-validate. Loop until
24/24 PASS or one of the soft halts in §8 fires.

## §8 Halt triggers

**Hard halts (stop and report immediately):**

- All 24 invocations pass → `COMPLETE.md`, stop
- Design question genuinely needing human input → `BLOCKED.md`
- Baseline AA flag-off regression → hard halt as blocker
- API/infrastructure errors persisting more than ~5 retries → write `BLOCKED.md`

**Soft halts (stop and write `OUTCOME.md` only if these fire — otherwise keep iterating):**

- **No forward progress for 3 consecutive iterations**, defined as: the §5.4.2 flag-on PASS count doesn't increase AND
  no new diagnostic finding is committed. Indicates the agent is spinning on the same issue and human input is needed to
  unstick.
- **The same §5.4.2 test fails with the same root-cause symptom across 3 separate fix attempts.** Indicates the fix
  shape is wrong; halt and reassess design.
- **Phase 3 microbenchmark > 2× regression from iter-3 baseline** AND no path back to within 2× after 2 attempts.
  Indicates cache overhead is structural, not implementation-detail.

**Self-orienting halts (these don't stop the run, just signal to redirect effort):**

- Phase 1 state-machine unit tests can't demonstrate both gotchas → revisit cache shape before continuing to Phase 2
- Phase 4 any §5.4.2 still fails → diagnose by failure category, return to relevant earlier phase
  - `OnFields` failure → Gotcha #2 (`populatedByPut`) not honored — fix in Phase 1, retry
  - `OnListField` failure → Gotcha #1 (`isInPutOnlyState` invariant) — fix in Phase 1, retry
  - `OnMapField` failure → diagnose which of the two gotchas, then fix and retry

## §9 Final report shape

`COMPLETE.md` if 24/24 PASS — this is the target outcome. Otherwise `OUTCOME.md` (PARTIAL) only if one of the soft halts
in §8 fires.

Report back in < 400 words:

1. Per-phase outcome (including any phase that was revisited multiple times for refinement)
2. Test results 24/24 or x/24 with §5.4.1/§5.4.2/§5.4.3 × flag-on/flag-off breakdown
3. Total iterations used
4. Commits list with one-line descriptions
5. Microbenchmark numbers (Phase 3)
6. Sanity-check item (most likely: cache concurrency, mode-transition logic, or `populatedByPut` correctness)
7. Confirmation that both Gotcha #1 and Gotcha #2 are explicitly handled in the cache state machine
8. If PARTIAL: which soft halt fired + what specifically remains

## §10 References

- Predecessor PARTIAL handoffs (read both):
  - `autoresearch/vt-merge-rmd-aware-fold/OUTCOME.md`
  - `autoresearch/vt-merge-rmd-cache-and-safety/OUTCOME.md`
- Phase B design + gotchas (the reverted attempt): `autoresearch/vt-merge-rmd-cache-and-safety/iter-2b-1-NOTES.md`
- Cache design (full spec): `autoresearch/vt-merge-rmd-aware-fold/GOAL.md` §3-§4
- AA leader RMW pattern (the model we're mirroring): `MergeConflictResolver.update()` + `MergeGenericRecord.update()` +
  `PartitionConsumptionState.TransientRecord`
- Existing PUT-side cache pattern: `RmdTimestampCache` / `RmdTimestampCacheManager`
- iter-3 V2 fold code: `MaterializingFoldContext.foldOperands/foldOperandOnly` (commit `d661d400b`)
- iter-2a-1 wire-format extension: commit `59ffe77f5`
- Microbenchmark for perf baseline: `WriteComputeArrayApplyMicrobenchmark`
- Test class:
  `clients/da-vinci-client/src/test/java/com/linkedin/davinci/schema/writecompute/TestWriteComputeSeedRmd.java` —
  existing scaffolding for V2-with-RMD tests
- Schema-converter one-layer-of-collection constraint: `WriteComputeSchemaConverter.java:40-41`
