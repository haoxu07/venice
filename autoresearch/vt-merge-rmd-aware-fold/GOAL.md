# GOAL — VT-merge RMD-aware fold path (Active-Active correctness + perf)

**Branch:** `haoxu07/vt-rocksdb-merge-design` **Created:** 2026-05-12 **Prior context:**

- `autoresearch/chunked-value-read-path-fix/OUTCOME.md` — chunked-value read-path bug fixed; 12/15 PartialUpdateTest
  pass
- `autoresearch/late-replica-bootstrap-bypass/OUTCOME.md` — VeniceWriter partition-routing fix
- `clients/da-vinci-client/src/test/java/com/linkedin/davinci/schema/writecompute/WriteComputeArrayApplyMicrobenchmark.java`
  — empirically demonstrates V1 path is **10-58× slower** than V2 path on the same workload

## §1 Problem statement

The flag-on (`vt.update.operand.enabled=true`) write path skips the leader's AA read-modify-write entirely. The leader
produces raw UPDATE operands to VT; followers append them to a per-key chain in RocksDB; values are reconstructed on
read via `MaterializingFoldContext.foldOperands`.

**The fold path goes through `WriteComputeProcessor.updateRecord` → `WriteComputeHandlerV2.updateValueRecord` (inherited
from V1)**, which contains an O(N×M) `.contains()` loop in `WriteComputeHandlerV1.updateArray`:

```java
for (Object element: newElements) {
  if (!(originalArray).contains(element)) { // TODO: profile the performance since this is pretty expensive
    originalArray.add(element);
  }
}
```

The microbenchmark (`WriteComputeArrayApplyMicrobenchmark`) measured:

| baseSize | V1 path (ms) | V2 path (ms) | ratio |
| -------- | ------------ | ------------ | ----- |
| 10K      | 162          | 16           | 10×   |
| 50K      | 599          | 13           | 46×   |
| 380K     | 4249         | 136          | 31×   |

The V2 path (`SortBasedCollectionFieldOpHandler`) requires a `CollectionRmdTimestamp` input. The fold path has none —
flag-on intentionally bypasses the leader RMW that would have produced RMD.

**Consequence:** `testActiveActivePartialUpdateWithCompression` flag-on times out — per-read fold cost on the
39-operand, 380K-float chain is multi-second (server) → router 10-sec HTTP timeout fires → test fails.

**Architecturally:** the current flag-on design also does not maintain AA conflict-resolution semantics. Two DCs writing
concurrent UPDATEs to the same field have order-dependent outcomes (whichever operand lands later in the chain wins)
rather than deterministic per-field-ts DCR. This is _correctness_, not just performance.

## §2 Goal

Implement a per-key per-field RMD cache and route the flag-on fold path through the V2 algorithm with cache-sourced
timestamps. The result:

1. **Correctness:** AA conflict resolution becomes deterministic per-field (matches flag-off baseline semantics).
2. **Per-call cost:** V2 algorithm replaces V1; per-fold cost drops from sec-range to ~50ms range on the 380K-float
   workload.
3. **RocksDB reads:** the cache covers hot keys, so RocksDB RMD reads only happen on cold-key access (mirrors the
   existing `PartitionConsumptionState.TransientRecord` semantics on baseline AA leader).
4. **Write throughput preserved:** the leader still skips RMW; the cache is populated on the follower's `merge()` path
   with cheap timestamp updates, not full value writes.

## §3 Scope decisions

### In scope

- Per-(keyHash, fieldId) RMD cache (sibling of `RmdTimestampCache`, but field-level)
- Mode discriminator: track whether on-disk RMD is whole-record-ts or per-field-ts
- Scalar-field entries: `long` timestamp
- Collection-field entries: full `CollectionRmdTimestamp` record (or pointer)
- Cold-key fallback to RocksDB RMD read
- Wire-in at follower's flag-on `partition.merge()` path
- Wire-in at fold path (`MaterializingFoldContext`) to route through V2 with cache-sourced RMD
- Bloom filter for "definitely not in DB" fast path
- Microbenchmark proving V2 matches baseline AA RMW cost
- Integration test validation (all 7 flag-on PartialUpdateTest invocations pass)

### Out of scope

- Schema evolution semantics across versions (assume single-version stores for this work; field name is the cache key,
  not position)
- Nested-record partial update (Venice intentionally one-layer-of-collection — `WriteComputeSchemaConverter.java:40-41`)
- Migration of existing flag-on stores (this is for new stores only)
- JMH benchmark integration (a microbenchmark suffices)
- Cache persistence/checkpointing (in-memory only, rebuilds on partition reopen)
- Cross-DC cache coherence (cache is partition-local; each DC builds its own)

## §4 Design

### §4.1 Cache shape

```java
public final class FieldLevelRmdCache {

  // Pack (keyHashHi : 48 bits) | (fieldOrdinal : 16 bits) → primitive long key
  // Each value record has < 65536 fields, so 16 bits is safe.
  private final LockFreeLongObjectMap<FieldRmdEntry> cache;

  // For collection fields, full RMD subtree. For scalar fields, a Long.
  // Entry type is union-ish; keep it minimal.
  static final class FieldRmdEntry {

    final long topLevelFieldTs; // For scalar: the field's ts. For collection: collection-replace ts.
    final CollectionRmdTimestamp rmdSubtree; // Non-null only for collection fields. May be detached lazily.
    final long lastAccessNs; // For LRU eviction
  }

  // Per-key mode: 0 = whole-record-ts mode, 1 = per-field-ts mode
  // Stored alongside the (keyHash, fieldId=-1) sentinel slot.
  private final LockFreeLongLongMap perKeyMode;

  private final PartitionBloomFilter bloomFilter;
  private final AtomicLong watermarkTsForEvictedEntries;
}

```

**Cache key encoding:** `(keyHash : 48 bits) | (fieldOrdinal : 16 bits)`. We use field **ordinal in the value schema**,
not name, because:

- Single-version assumption (out of scope: schema evolution)
- Avoids storing strings in the cache
- 16-bit ordinal is more than enough (Venice value records typically have < 100 fields)

For schema evolution beyond this work: introduce a stable field-id assigned at schema-registration time (not field
position), and use that. Documented as a follow-up.

**Field ordinal -1 reserved** for the per-key mode flag entry.

### §4.2 Mode tracking

Each key is in one of two RMD modes (mirrors Venice's RMD union at the root):

| Mode                | Meaning                                                                                  |
| ------------------- | ---------------------------------------------------------------------------------------- |
| 0 (whole-record-ts) | Last touched by a wholesale PUT or full DELETE. Single `long` ts gates the whole record. |
| 1 (per-field-ts)    | Last touched by a partial UPDATE. Per-field ts records gate individual fields.           |

Stored as a single bit per key. On mode transition (whole-PUT after partial UPDATEs, or vice versa), the cache must
invalidate stale entries:

- **Mode 0 → 1 (partial-update arrives after a PUT):** Evict the mode-0 sentinel; populate per-field entries lazily as
  fields are touched. Top-level-field-ts on cold collection fields = the prior whole-record-ts.
- **Mode 1 → 0 (whole-PUT arrives after partial updates):** Evict ALL per-field entries for this key; install a single
  mode-0 entry with the PUT's ts.

This is the cross-field invalidation cost from prior discussion. Worst case: O(field-count) per mode-flip. Mode-flips
should be rare in steady-state workloads.

### §4.3 Cold-key fallback

On a cache miss, fall back to the same `getRmdWithValueSchemaByteBufferFromStorage` path that the baseline AA leader
uses. After the fallback read, eagerly populate ALL per-field cache entries from the decoded RMD record — single RocksDB
read amortizes across N fields.

### §4.4 Fold path wire-in

`MaterializingFoldContext.foldOperands` currently calls `wcProcessor.applyWriteCompute(...)` → V1 path. Change to:

1. For each operand in the chain, parse its WC payload to determine which fields are modified.
2. For each modified field:
   - Look up the field's cached RMD entry.
   - If scalar field: just a ts comparison against the operand's ts; either apply (V2 doesn't matter for scalars at this
     granularity) or skip.
   - If collection field: pass the cached `CollectionRmdTimestamp` to
     `SortBasedCollectionFieldOpHandler.handleModifyList(...)` (V2 algorithm). Update cached RMD with the result.
3. After folding all operands, serialize the updated base value bytes.

This collapses the chain into a materialized value via the V2 algorithm, using cache-sourced RMD instead of reading from
RocksDB.

### §4.5 Merge-path wire-in

When an operand arrives at `partition.merge()`:

1. Parse operand to get (modify-ts, modified-field-set).
2. For each modified field, consult the cache:
   - Cache HIT, cached.ts ≥ modify.ts → operand loses for this field. If loses for ALL fields → drop the operand
     entirely (don't append to chain).
   - Cache HIT, cached.ts < modify.ts → operand wins for this field. Update cache entry.
   - Cache MISS → bloom check. Definitely-not-in-DB → win, populate. Maybe-in-DB → if modify.ts > watermark, optimistic
     win + populate; else fallback to RocksDB read.
3. If operand wins for ANY field, append it to the chain.

For the compression-test workload (single DC, monotonically increasing ts, 39 sequential updates to one key): step 1
finds cache HITs for updates 2-39 → no RocksDB reads after the first.

### §4.6 Why this matches baseline AA RMW semantics

Baseline AA leader's MergeConflictResolver does exactly this work:

- Reads current RMD
- Decides per-field which winning side
- Updates RMD
- Produces new PUT with updated RMD

Our flag-on path does:

- Reads cached RMD (or RocksDB on cache miss)
- Decides per-field which winning side
- Updates cached RMD
- Appends operand to chain (NO new PUT — preserves write-throughput win)

The semantics match; the storage substrate differs (chain in RocksDB instead of replacement PUT).

## §5 Phases

### Phase 1 — Cache infrastructure (iter 1-3)

**Goal:** Build `FieldLevelRmdCache` with full API + unit tests.

Tasks:

1. New package: `com.linkedin.davinci.replication.rmdcache.field`
2. `FieldLevelRmdCache` class with:
   - Lock-free `(keyHash, fieldOrdinal) → entry` map (custom open-addressing primitive map)
   - Per-key mode bit
   - Cold-key fallback hook
   - Bloom filter + watermark (reuse `PartitionBloomFilter`)
   - LRU eviction with watermark-raising
3. `FieldRmdEntry` POJO supporting both scalar (long ts) and collection (`CollectionRmdTimestamp`) forms
4. Unit tests for:
   - Scalar field DCR decisions (win/lose/fallback)
   - Collection field DCR decisions
   - Mode-0 → mode-1 transitions
   - Mode-1 → mode-0 transitions (cross-field invalidation)
   - Eviction + watermark raising
   - Bloom filter integration (B.2.a, B.2.b branches)

**Unit-test-first per prior feedback:** each piece of behavior gets a regression-guard test BEFORE the implementation is
wired into production paths.

**Halt at end of Phase 1 if:** unit tests don't cleanly demonstrate the cache state machine. Reassess shape before
continuing.

### Phase 2 — Follower merge-path wire-in (iter 4-5)

**Goal:** Consult cache on `partition.merge()` for flag-on stores; drop losing operands.

Tasks:

1. `MaterializingReplicationMetadataRocksDBStoragePartition.merge`: parse incoming operand to extract modify-ts + field
   set
2. Per-field cache consultation
3. If all fields lose → drop operand (no chain append). Stats counter.
4. If any field wins → update cache + append operand to chain (current behavior)
5. Cold-key fallback wired through the same `getRmdWithValueSchemaByteBufferFromStorage` path the AA leader uses

Tests:

- Unit test: feed a sequence of operands with mixed ts; assert cache state + chain content
- Cross-DC scenario: dc-0 op at ts=100, dc-1 op at ts=80 → dc-0 wins, dc-1 dropped
- Cold-key scenario: first op arrives → cache miss → simulated RocksDB read → cache populated

**Halt at end of Phase 2 if:** baseline AA flag-off tests regress. The new merge-path code should be active only when
flag-on; flag-off must be untouched.

### Phase 3 — Fold-path V2 routing (iter 6-7)

**Goal:** Route `foldOperands` through V2 algorithm using cached RMD.

Tasks:

1. `MaterializingFoldContext.foldOperands`: extend constructor / context to hold a `FieldLevelRmdCache` reference
2. For each operand in chain:
   - Parse WC payload, identify modified fields
   - For scalar field: apply via existing V1 path (algorithmic difference is negligible for scalars — the win is at
     collection fields)
   - For collection field: build `ValueAndRmd<GenericRecord>` from current base + cached RMD; call
     `MergeGenericRecord.update(...)` or directly `SortBasedCollectionFieldOpHandler.handleModifyList(...)`
3. Update cache with new RMD post-fold

Tests:

- Microbenchmark `WriteComputeArrayApplyMicrobenchmark` extended to measure the new fold path; should be within 2× of
  baseline AA V2 path
- Unit test: chain of 8 setUnion operands on a growing list → final state equals what baseline AA leader would produce
  on the same operand sequence

**Halt at end of Phase 3 if:** microbenchmark shows the new fold path isn't materially faster than V1. Means the RMD
construction overhead is dominating; reassess.

### Phase 4 — Integration test validation (iter 8-9)

**Goal:** Pass all gated integration tests under flag-on without regressing flag-off baseline.

Tests are grouped by what they validate. All run with `-Dvt.update.operand.flag=true` (flag-on) unless noted as
baseline-regression.

#### §5.4.1 Primary regression-guard tests (`PartialUpdateTest`)

These were the tests previously failing or already fixed through this branch's chain of work; they must continue to
pass.

| #   | Test                                                                      | Flag-on status pre-this-work | Why it matters here                       |
| --- | ------------------------------------------------------------------------- | ---------------------------- | ----------------------------------------- |
| 1-3 | `testPartialUpdateOnBatchPushedKeys[NO_OP/GZIP/ZSTD_WITH_DICT]`           | PASS post-H5                 | Regression check                          |
| 4   | `testActiveActivePartialUpdateOnEmptyPush`                                | PASS post-H5                 | Regression check                          |
| 5   | `testActiveActivePartialUpdateOnBatchPushedChunkKeys`                     | PASS post-chunked-value      | Regression check                          |
| 6-8 | `testActiveActivePartialUpdateWithCompression[NO_OP/GZIP/ZSTD_WITH_DICT]` | FAIL (router timeout)        | **Primary target — must pass post-cache** |

Plus the same 8 tests run **flag-off** as baseline-regression guards (= 16 total invocations for `PartialUpdateTest`).

#### §5.4.2 Cross-DC AA-semantics tests (`TestPartialUpdateWithActiveActiveReplication`)

These tests produce to BOTH dc-0 and dc-1 on the same key. They exercise the element-level DCR semantics our cache
design must preserve.

| #   | Test                                                 | What it exercises                                                                                                                 |
| --- | ---------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| 9   | `testAAReplicationForPartialUpdateOnFields`          | Field-level cross-DC partial-update — core AA semantics                                                                           |
| 10  | `testAAReplicationForPartialUpdateOnListField`       | **List-field cross-DC ops — element-level DCR** (the case §4 acknowledged as needing per-element-ts)                              |
| 11  | `testAAReplicationForPartialUpdateOnMapField`        | **Map-field cross-DC ops — element-level DCR**                                                                                    |
| 12  | `testActiveActivePartialUpdateWithRecordMapField`    | Map-field with record values                                                                                                      |
| 13  | `testAAPartialUpdateWithNestedRecordSchemaEvolution` | **Schema evolution interaction** — gates whether our fieldOrdinal-as-key design holds, or needs the follow-up of stable field-ids |

#### §5.4.3 Direct RMD-machinery tests (`PartialUpdateAAMetadataTest`)

These tests exercise the per-field-ts RMD machinery directly. They map onto what our cache mimics.

| #   | Test                                            | What it exercises                                                                |
| --- | ----------------------------------------------- | -------------------------------------------------------------------------------- |
| 14  | `testUpdateValueWithOldSchemaWithFieldLevelRMD` | **Per-field-ts RMD pathway** — direct test of behavior our cache reproduces      |
| 15  | `testConvertRmdType`                            | **RMD mode transitions** — directly tests the mode-0 ↔ mode-1 flip logic in §4.2 |
| 16  | `testEnablePartialUpdateOnActiveActiveStore`    | Store-config / enablement path                                                   |

**Total integration gate:** 16 flag-on invocations + 8 flag-off baseline = **24 invocations must pass**.

#### §5.4.4 Per-run discipline

- Use `--init-script /tmp/no-retry.gradle` (see §6 for the script body) on every run.
- Use `--tests "<class>.<method>"` to target individual tests when iterating; only run the full set at the end of
  Phase 4.
- Kill stale gradle workers before each multi-test run (see §6).

**Halt at end of Phase 4 if:**

- Any flag-on test still fails. Diagnose by category:
  - Primary (§5.4.1) fails → likely cache wire-in or V2 routing bug
  - Cross-DC (§5.4.2) fails → likely element-level DCR cache logic bug
  - RMD machinery (§5.4.3) fails → likely mode-flip or per-field-ts encoding bug
- Any flag-off test regresses → halt as hard blocker.

### Phase 5 — Cleanup, docs, regression guards (iter 10)

**Goal:** Land the change with appropriate documentation.

Tasks:

1. Minimize diff: remove diagnostic logs added during investigation; keep only the metrics/counters needed for
   production observability
2. Document the cache config knobs (max size, time window, bloom params) in `ConfigKeys.java`
3. Write `OUTCOME.md` summarizing: bug → fix → test results → commits → sanity-check item
4. Commit any remaining work with `[vt-merge-rmd]` prefix

## §6 Iteration budget

**10 iterations total.** Per-iteration:

- Write `iter-N-NOTES.md` with hypothesis → change → result → next step
- Commit on the branch with `[vt-merge-rmd]` prefix
- Unit-test-first: every public method/behavior change has a regression-guard test
- **Fail-fast on ALL integration test runs** (not just Phase 4). Always pass the conditional retry init-script:
  ```groovy
  allprojects {
    tasks.withType(Test).configureEach {
      if (extensions.findByName('retry')) {
        retry { maxRetries = 0 }
      }
    }
  }
  ```
  Save to `/tmp/no-retry.gradle` and pass via `--init-script /tmp/no-retry.gradle`. The per-test `@Test(timeOut = ...)`
  annotations remain unchanged — those are per-attempt budgets, not retry budgets. With `maxRetries = 0`, a failing test
  fails once at its per-test timeout rather than 5× per-test timeout (default `maxRetries = 4` in `build.gradle:476`).
- **Kill stale gradle workers between major test runs** if the host has accumulated more than ~10 `GradleWorkerMain`
  JVMs. Stale workers from prior killed/timed-out runs slow new runs significantly. Use
  `ps -eo pid,lstart,cmd | grep GradleWorkerMain` to identify; `kill -9` to clean up.

## §7 Halt triggers

- **All 24 invocations pass (16 flag-on + 8 flag-off baseline)** → write `COMPLETE.md`, stop
- **10 iterations exhausted with PARTIAL state** → write `OUTCOME.md` summarizing what's working and what's left
- **Encounter design question genuinely needing human input** → write `BLOCKED.md`, stop
- **Baseline AA flag-off regression** → halt, this is a blocker
- **Any §5.4.3 RMD-machinery test fails** → halt before deeper work; the cache's RMD-mode semantics are wrong at a
  fundamental level if these break

## §8 Final report shape

`OUTCOME.md` or `COMPLETE.md` under `autoresearch/vt-merge-rmd-aware-fold/`. Report back to human in < 400 words:

1. **Bug:** what was broken
2. **Fix:** cache shape + V2 routing decisions
3. **Files changed:** main classes touched + line counts
4. **Test results:** N/15 invocations + microbenchmark numbers
5. **Commits:** list of `[vt-merge-rmd]`-prefixed commits
6. **Sanity-check item:** the one thing the human should review carefully (e.g., concurrency of the cache, eviction
   semantics, RMD-mode transition logic)
7. **Per-test pass/fail breakdown** for the 24-invocation gate, grouped by §5.4.1 / §5.4.2 / §5.4.3 with wall times.
   Helps the human see which category of behavior was hardest to get right.

## §9 Risk register

| Risk                                                                                             | Mitigation                                                                                                                                                                                                             |
| ------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| Cache memory grows unbounded on wide-record stores                                               | LRU eviction sized by partition memory budget; watermark covers evicted-but-still-in-DB entries                                                                                                                        |
| Mode-flip cross-field invalidation is slow on records with many fields                           | Mode flips should be rare in steady-state; track + monitor flip rate. If concerning, lazy-invalidate (mark-and-recheck on next access)                                                                                 |
| Cold-start RocksDB read fan-out (N fields per record × M keys per partition)                     | Eager populate all fields from one RMD read on cold-key miss                                                                                                                                                           |
| Schema evolution breaks fieldOrdinal stability                                                   | Out of scope; document as TODO for follow-up work                                                                                                                                                                      |
| Cache-RocksDB inconsistency on partition crash mid-merge                                         | Cache is reconstructable from RocksDB; restart re-warms                                                                                                                                                                |
| Collection field `CollectionRmdTimestamp` cache entries become large (long active-elem-ts lists) | Trade-off: keep full RMD subtree for correctness, accept memory cost. If a problem, switch to "watermark-only" cache for collection fields and full-fallback for element-level DCR — but document the semantics change |

## §10 References

- `WriteComputeProcessor` / `WriteComputeHandlerV2` / `SortBasedCollectionFieldOpHandler` — the V2 algorithmic
  primitives we'll reuse
- `MergeConflictResolver.update()` / `MergeGenericRecord.update()` — the baseline AA leader RMW pattern we're mirroring
- `RmdTimestampCache` / `RmdTimestampCacheManager` — existing PUT-side cache; structurally similar but per-key (vs
  per-(key, field))
- `PartitionConsumptionState.TransientRecord` — the existing per-key cache on the AA leader that we're effectively
  reproducing on the follower
- `CollectionRmdTimestamp` — the per-collection-field RMD subtree we'll cache for collection fields
- `WriteComputeSchemaConverter.java:40-41` — documents the one-layer-of-collection constraint that simplifies our design
- `MaterializingFoldContext` / `MaterializingReplicationMetadataRocksDBStoragePartition` — where flag-on read-path and
  merge-path code lives
- `clients/da-vinci-client/src/test/java/com/linkedin/davinci/schema/writecompute/WriteComputeArrayApplyMicrobenchmark.java`
  — empirical baseline of the V1 vs V2 gap

## §11 Open questions to resolve early

1. **Should the cache be per-store-version or per-partition?** Prior PUT cache is per-partition. Probably same for
   consistency, but worth confirming the lifecycle semantics (when partition closes, cache must drop).
2. **Should mode transition (per-field-ts ↔ whole-record-ts) be detected from operand type, or from a separate RMD-mode
   field?** Operand type tells us "incoming UPDATE → per-field mode" or "incoming PUT → whole-record mode", but the
   cache needs to know the on-disk state. May need a sentinel cache entry that records last-observed-mode.
3. **What about the existing chunked-manifest chain backstop (`maybeBackstopChunkedManifestChain` at `b7acafba5`)?** It
   currently uses V1. Should it be updated to V2 too? Probably yes, for consistency, but it's a separate code path.
4. **Operand carries `updateOperationTimestamp` in its WC payload — is that the right ts to use for DCR?** Or should we
   use the Kafka offset / produce time? Confirm in design phase.

These should be resolved in Phase 1 design notes before deep implementation begins.
