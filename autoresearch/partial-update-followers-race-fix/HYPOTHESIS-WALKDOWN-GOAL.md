# Follower-Race Hypothesis Walkdown — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-11 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation of
partial-update-followers-race-fix work-stream) **Execution model:** Autonomous agent. Sequential walkdown of 4
hypotheses (H1 → H2 → H4 → H3). Fail-fast: each hypothesis is verified before moving to the next; once one is CONFIRMED
and a fix passes the in-scope tests, halt and declare success. **Estimated effort:** 1-2 sessions. Best case (H1 passes
first): ~30 min. Worst case (all 4 must be tested, last one yields fix): ~6 hours.

---

## 0. Background

Prior work-streams established:

- The flag-on PartialUpdateTest invocations fail with a follower-side persistence race
  (`autoresearch/partial-update-batch-pushed-keys-validation/`)
- Phase 1 of the fix work-stream confirmed: **134 of 368 follower merge readbacks (~36%) show operand-only bytes — base
  missing at merge time** (`autoresearch/partial-update-followers-race-fix/iter-1-NOTES.md`)
- Phase 2 disproved three plausible fixes (`partition.sync()` before close, `rocksDB.flush()` in close,
  put-preserves-operand-suffix). Combined attempts also failed (`iter-2-NOTES.md` through `iter-4-NOTES.md`)
- The "split-half" observation rules out simple memtable loss: exactly half the keys on the same partition object are
  affected within the same second of activity
- `EXTRA-NOTES.md` enumerates 4 hypotheses for **why flag-OFF doesn't share the symptom**, with concrete experiments

**Required reading before the agent starts** (all in `autoresearch/partial-update-followers-race-fix/`):

- `OUTCOME.md` — prior fix attempt outcome
- `BLOCKED-NOTES.md` — full triage from the previous halt
- `iter-1-NOTES.md` — Phase 1 RCA confirmation evidence
- `iter-2-NOTES.md`, `iter-3-NOTES.md`, `iter-4-NOTES.md` — three failed fix attempts
- **`EXTRA-NOTES.md`** — the 4 hypotheses being walked down in this work-stream

## 1. Goal

Sequentially test 4 hypotheses for the flag-OFF vs flag-ON asymmetry. Once any hypothesis is CONFIRMED AND a fix passes
the in-scope tests, halt and declare success. If all 4 are disproven, write `BLOCKED-NOTES.md` and escalate to human
follow-up.

**Success criterion:** all 7 in-scope flag-on PartialUpdateTest invocations PASS, flag-OFF baseline still PASS, sister
test still PASS = 15/15 invocations across the standard validation gate.

## 2. Scope

### In scope

- **Production code modifications AUTHORIZED** for diagnostics and fixes (same as the prior fix work-stream)
- Sequential hypothesis-testing per the order in §3
- Reverting any production code change that proves ineffective (keep the tree clean per hypothesis)
- Modifying `log4j2.properties` for test-time diagnostics (revert before declaring success)
- Reading prior iter-N-NOTES.md as primary evidence input

### Out of scope

- Skipping hypotheses (must walk down in order; can only stop early on CONFIRMED + fix-passes-tests)
- Modifying test code to make tests pass
- Changing on-disk wire format
- Changing the leader-side write contract
- Re-doing the 3 fix attempts the prior agent already disproved (sync-before-close, flush-in-close,
  put-preserves-operand-suffix) UNLESS the new hypothesis suggests a meaningfully different variant

## 3. Phased plan — sequential hypothesis walkdown

Each hypothesis gets its own phase with verification + (if applicable) fix attempt. Decision tree at each phase:

```
CONFIRMED + fix works  → halt with PASS, write OUTCOME.md
CONFIRMED + fix fails  → move to next hypothesis (the verification stands, but fix is elsewhere)
DISPROVEN              → move to next hypothesis
AMBIGUOUS after budget → move to next hypothesis with note
```

### Unit-test-first principle

For each hypothesis where it's practical, write a focused unit test BEFORE the integration test attempt:

- **Why first:** unit tests run in <1 second; integration tests take ~30-40s plus setup overhead (~3-5 min per iter). A
  unit-level reproducer collapses the fix-design feedback loop from minutes to seconds.
- **Why not always:** some hypotheses (H1, H2) involve runtime lifecycle behavior that can't be simulated in a unit test
  without effectively reimplementing the test cluster. For those, integration is the actual verifier.
- **Outcome interpretation:** if the unit test reproduces the bug, the fix iterates against the unit test until it
  passes, then validates with integration. If the unit test does NOT reproduce, the hypothesis isn't fully refuted — the
  bug may live in machinery the unit test doesn't exercise — but the unit test still serves as a regression guard for
  whatever fix lands.

Unit-test applicability per hypothesis:

| Hypothesis                         | Unit test viable? | Reason                                                                                            |
| ---------------------------------- | ----------------- | ------------------------------------------------------------------------------------------------- |
| **H1** (Enable WAL)                | Sanity-only       | Race is integration-level; unit-test only the config wiring                                       |
| **H2** (iter-11 revert)            | No                | Race depends on lifecycle timing not exercisable in unit context                                  |
| **H4** (Local vs remote DC)        | Partial           | Can verify framing parity at partition level, but routing splitter is upstream of unit test scope |
| **H3** (FoldContextRegistry reset) | **Yes**           | Directly reproducible: register ctx → close → merge → readback. The unit test IS the experiment.  |

In each phase below, "Unit-test approach" is the first sub-step (where applicable). "Integration experiment" is the
primary verifier OR the fallback if the unit-test approach can't reproduce.

### Phase 1 — H1: Enable WAL on data partitions (iter 1-3)

**Hypothesis:** the race is the close+reopen-without-flush surface; enabling WAL forces durability before close,
eliminating the loss.

**Unit-test approach (sanity check only — actual race is integration-level):**

A focused unit test can verify the **config wiring is correct** but cannot reproduce the partition-lifecycle race itself
(the race requires a real ingestion task triggering `adjustStoragePartition` mid-stream). Add a small JUnit/TestNG test
in `clients/da-vinci-client/src/test/java/.../store/rocksdb/RocksDBStoragePartitionTest.java` that verifies:

```java
@Test
public void writeOptionsDisableWALIsFlagAware() {
  // Construct partition with vt-update-operand flag ON + data partition id → setDisableWAL(false)
  // Construct partition with flag OFF + data partition id → setDisableWAL(true)
  // Construct partition with flag ON + METADATA_PARTITION_ID → setDisableWAL(false)
  // Construct partition with flag OFF + METADATA_PARTITION_ID → setDisableWAL(false)
}
```

This catches typo-class bugs in the conditional and runs in <1 second. It does NOT verify the race is fixed — that
requires the integration test.

**Integration experiment (primary verifier):**

1. Modify `RocksDBStoragePartition.java:194` to:
   ```java
   this.writeOptions = new WriteOptions().setDisableWAL(
       this.partitionId != METADATA_PARTITION_ID && !serverConfig.isVtUpdateOperandEnabled());
   ```
   This enables WAL on data partitions ONLY when flag-on. Flag-off behavior is unchanged.
2. Build, run `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on (isolated)
3. If PASS: run the full 7 flag-on invocations to verify across compression strategies
4. If 7/7 PASS: run flag-OFF baseline + sister test for regression — declare success

**Decision:**

- All 7 flag-on PASS + regression checks clean → **CONFIRMED + fix works** → advance to Phase 5 (final validation +
  cleanup)
- Test still fails → **H1 DISPROVEN** (or fix is incomplete — try also adding `partition.sync()` before close as
  belt-and-suspenders; if that also fails, definitively disproven). Revert the change, advance to Phase 2.

### Phase 2 — H2: Revert iter-11's `processActiveActiveMessage` early-return (iter 4-5)

**Hypothesis:** the iter-11 skip removed implicit protection (lock, partition stabilization) that flag-OFF still
benefits from.

**Unit-test approach (not practical — see why):**

H2 is fundamentally about a side-effect of the iter-11 skip on partition-lifecycle timing. The iter-11 early-return is
trivially unit-testable (does it fire under flag-on + UPDATE? yes), but **what the skipped code was doing that
incidentally protected against the race** can only be observed when the lifecycle code paths are active — i.e., when the
test cluster is actually running. A unit test cannot simulate the close+reopen window that the iter-11 skip interacts
with.

Skip directly to integration experiment.

**Integration experiment:**

1. Temporarily revert the iter-11 early-return for UPDATE messages in
   `ActiveActiveStoreIngestionTask.processActiveActiveMessage` (the
   `if (serverConfig.isVtUpdateOperandEnabled() && msgType == UPDATE) return null;` early-return)
2. Run `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on
3. Expected: the iter-5 operand-buffer-position-advancement bug re-appears in some form — the test may fail differently
   OR pass differently

**Decision:**

- If test PASSES with iter-11 reverted: the path-skip IS the trigger. **Fix design:** preserve the iter-11 fix BUT add
  the protection back. Most likely: re-acquire the partition via `engine.getPartitionOrThrow` right before the merge
  call OR add a synchronization wrapper. Iterate within budget.
- If test FAILS with a different symptom (e.g., the iter-5 prefix-corruption symptom returns): **H2 DISPROVEN**. The
  iter-11 skip isn't the lifecycle trigger. Restore iter-11. Advance to Phase 3.
- If test fails with the SAME symptom (operand-only readbacks): **H2 DISPROVEN**. Restore iter-11. Advance to Phase 3.

### Phase 3 — H4: Per-key source-DC tracking (iter 6-8)

**Hypothesis:** the bug is a structural divider — local-DC writes go through one framing path, remote-DC writes go
through another. Half the keys come from each DC under AA, matching the half-affected pattern.

**Unit-test approach (try first):**

H4 has a unit-testable angle: verify that **both consumer paths invoke the same framing override**. Write a test that
constructs a `MaterializingReplicationMetadataRocksDBStoragePartition`, simulates UPDATEs arriving from two distinct
sources (local-DC topic name vs remote-DC topic name) by calling whatever method the consumer threads ultimately call,
and asserts that both calls produce identically-framed on-disk bytes.

Concrete sketch:

```java
@Test
public void mergeFramingIsIdenticalRegardlessOfSourceDc() {
  MaterializingReplicationMetadataRocksDBStoragePartition partition = ...;
  // Register fold context (same as production wiring)
  // Simulate one UPDATE from local-DC topic
  partition.merge(keyLocal, operandBytes);
  byte[] localRead = partition.getRaw(keyLocal);
  // Simulate one UPDATE from remote-DC topic (different topic, same kind of operand)
  partition.merge(keyRemote, operandBytes);
  byte[] remoteRead = partition.getRaw(keyRemote);
  // Both reads should show identical kind-byte framing
  assertThat(localRead[0]).isEqualTo(KIND_OPERAND);
  assertThat(remoteRead[0]).isEqualTo(KIND_OPERAND);
  // Or, if a previous PUT supplied the base, both should resolve to BASE+OPERAND concat shape
}
```

If both reads show identical framing, H4's "structural splitter at the framing layer" is refuted at the partition level
— the divider must be upstream of `partition.merge`. If they differ, hypothesis confirmed AT the partition layer (very
actionable).

**Caveat:** the unit test exercises only the framing code path, not the consumer-thread routing. H4 could still hold
even if this unit test passes — the splitter could be upstream (e.g., consumer routing skips the merge override for one
DC). If unit test passes but integration still fails, fall back to the integration approach below.

**Integration experiment (fallback if unit test passes):**

1. Instrument the follower-side `case UPDATE` handler in `StoreIngestionTask`/`ActiveActiveStoreIngestionTask` to log
   the source topic/DC for each UPDATE. Suggested:
   ```java
   LOGGER.info("[UPDATE-SOURCE] storeVersion={} partition={} key.first8={} sourceTopic={} sourceDc={}",
       storeName, partitionId, hexBytes(key, 8), topicName, dcInferenceFromTopic(topicName));
   ```
2. Run `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on
3. Grep for `[UPDATE-SOURCE]` entries
4. Cross-reference with the keys showing operand-only readbacks (the `[VT-MERGE-READBACK]` evidence from iter-1)
5. If keys from one DC are systematically affected and keys from the other are not, hypothesis CONFIRMED

**Decision:**

- CONFIRMED: design fix that ensures both DC paths go through the materializing framing. The most likely surface is in
  the partition routing or in `MaterializingReplicationMetadataRocksDBStoragePartition`'s override coverage. Iterate
  within budget.
- DISPROVEN: keys from both DCs equally affected. Advance to Phase 4.

### Phase 4 — H3: Comprehensive `put`/`merge`/`registry` instrumentation (iter 9-11)

**Hypothesis:** `MaterializingFoldContextRegistry` state is reset/missing at merge time for some calls, causing framing
to skip.

**Unit-test approach (try first — this is the most unit-testable hypothesis):**

H3 is directly reproducible at the unit level: construct a partition + register a fold context, simulate
close-and-reopen, then call merge and verify framing behavior.

```java
@Test
public void foldContextRegistrySurvivesPartitionCloseAndReopen() {
  String storeVersion = "test-store_v1";
  MaterializingFoldContext ctx = mock or build a real one;
  MaterializingFoldContextRegistry.register(storeVersion, ctx);

  MaterializingReplicationMetadataRocksDBStoragePartition partition = constructWithStoreVersion(storeVersion);
  // Put a base
  partition.put(key, baseBytes);

  // Simulate close-and-reopen (the lifecycle event from adjustStoragePartition)
  partition.close();
  partition = reopenSameStoreVersion(storeVersion);

  // Merge an operand
  partition.merge(key, operandBytes);

  // Read back via getRaw to see on-disk shape
  byte[] readback = partition.getRaw(key);

  // Expectation: the framed-base + framed-operand shape
  assertThat(readback[0]).isEqualTo(KIND_BASE);
  // ... assert presence of operand suffix
}
```

This test will either:

- **Reproduce the bug** (readback is operand-only, no base) → H3 CONFIRMED at unit level. The fix is then designed
  against this reproducer (fast feedback loop: edit → test in seconds), then validated at integration.
- **NOT reproduce the bug** (readback is correctly framed) → H3 DISPROVEN at the partition level. The bug is in whatever
  the integration test does that the unit test does not (e.g., concurrent threads, partition map manipulation in
  AbstractStorageEngine, etc.). Advance to integration diagnostic.

**Integration experiment (fallback if unit test does not reproduce):**

1. Add comprehensive logging to:
   - `MaterializingReplicationMetadataRocksDBStoragePartition.put` — log partition.identityHash, FoldContextRegistry.get
     result, FRAMING_IN_PROGRESS state
   - `MaterializingReplicationMetadataRocksDBStoragePartition.merge` — same logging
   - `MaterializingFoldContextRegistry.register` and `unregister` — log call, store-version, thread
   - `RocksDBStoragePartition.close` — log close events with partition.identityHash
2. Run `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on
3. Cross-reference: for each operand-only readback key, check whether the merge's FoldContextRegistry.get was null at
   call time, or the FRAMING_IN_PROGRESS state was unexpected
4. If a clear pattern emerges (e.g., registry was unregistered during a close that happened between PUT and MERGE for
   those keys), hypothesis CONFIRMED

**Decision:**

- CONFIRMED: design fix that prevents the registry reset during partition adjustments OR ensures merges fail-loud rather
  than silently skip framing. Iterate within budget.
- DISPROVEN: no clear pattern; registry state was always correct. Write `BLOCKED-NOTES.md`, halt.

### Phase 5 — Final validation + cleanup (only after a CONFIRMED + fix exit)

**Build:** none (cleanup only).

**Verify:**

- Remove diagnostic log lines added during the walkdown (or downgrade to DEBUG)
- Verify `log4j2.properties` is at branch-baseline (no diagnostic level changes)
- `git diff --stat` should show focused, minimal production code changes (preferably < 30 LOC outside autoresearch/)
- Run full 15-invocation gate (7 flag-on + 7 flag-off PartialUpdateTest + 1 sister test):
  ```
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateWithCompression" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast
  ```
  And:
  ```
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast
  ```
- All 15 PASS → write `OUTCOME.md` documenting the confirmed hypothesis + fix mechanism, commit

## 4. Iteration policy

**11-attempt total budget across phases.** Per-phase budgets are guidelines, not hard caps — the agent can borrow
iterations if one phase finishes early.

| Phase | Hypothesis                            |               Suggested iters | Exit condition                                               |
| ----- | ------------------------------------- | ----------------------------: | ------------------------------------------------------------ |
| 1     | H1 (WAL)                              |                           1-3 | If H1 confirmed + 7/7 PASS → halt with success; else move on |
| 2     | H2 (iter-11 revert)                   |                           4-5 | If confirmed → fix; else move on                             |
| 3     | H4 (source-DC structural)             |                           6-8 | If confirmed → fix; else move on                             |
| 4     | H3 (registry/framing instrumentation) |                          9-11 | If confirmed → fix; else halt with BLOCKED-NOTES             |
| 5     | Cleanup + final validation            | 1-2 (within remaining budget) | All 15 PASS                                                  |

Each iteration writes `walk-iter-N-NOTES.md` (use the `walk-` prefix to distinguish from prior fix-attempt
iter-N-NOTES.md). Include:

- Hypothesis being tested
- Code change made (file paths, line counts)
- Test result (PASS / FAIL with key log excerpts)
- Decision: confirmed/disproven/ambiguous → which hypothesis next

## 5. Test gates

Same as the prior fix work-stream — use the disable-retry init script + `--fail-fast`:

```bash
# Init script (one-time)
cat > /tmp/disable-test-retry.gradle <<'EOF'
allprojects {
  tasks.withType(Test).configureEach {
    retry {
      maxRetries = 0
    }
  }
}
EOF

# Single-test debug
cd /home/coder/Projects/venice && \
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast
```

## 6. Reference files (must-read before debugging)

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/RocksDBStoragePartition.java` (H1
  modification site, line ~194)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java` (H2
  iter-11 early-return; H3 case UPDATE handler instrumentation site)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/MaterializingReplicationMetadataRocksDBStoragePartition.java`
  (H3 instrumentation site; merge override)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/MaterializingFoldContextRegistry.java`
  (H3 register/unregister logging site)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/AbstractStorageEngine.java` (H3 close + partition
  map manipulation)

## 7. Decision criteria

**YES (success):** all 15 invocations PASS + diagnostic logs cleaned up + `OUTCOME.md` written documenting which
hypothesis was confirmed + the fix mechanism + git diff stat showing focused production code change.

**PARTIAL:** at least one hypothesis confirmed but no working fix within budget. Write `BLOCKED-NOTES.md` with the
confirmed hypothesis + attempted fixes + recommended next steps for human follow-up.

**FAILED:** all 4 hypotheses disproven OR budget exhausted before any confirmation. Write `BLOCKED-NOTES.md` documenting
all 4 verification results + remaining open hypotheses (e.g., late-replica bootstrap path, which we deferred from the
prior work-stream).

## 8. Risks

| Risk                                                                                         | How we'd see it                                         | Response                                                                                                                                         |
| -------------------------------------------------------------------------------------------- | ------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------ |
| H1 enables WAL but introduces a new failure mode (e.g., test flakiness due to slower writes) | flag-OFF baseline regresses, or test wall time balloons | Revert; H1 disproven; continue to H2                                                                                                             |
| H2 revert breaks flag-off path                                                               | flag-OFF regression                                     | Restore iter-11 immediately; advance to H4                                                                                                       |
| H4 instrumentation reveals no clear pattern (split-half is NOT by DC)                        | Keys from both DCs equally affected                     | Disprove H4; advance to H3                                                                                                                       |
| All 4 disproven                                                                              | Phase 4 finds nothing                                   | Write BLOCKED-NOTES.md with full diagnostic data; suggest the unaddressed hypothesis from BLOCKED-NOTES (late-replica bootstrap) for future work |
| Fix breaks sister test                                                                       | Phase 5 regression                                      | Iterate within Phase 5 budget; if can't reconcile, halt                                                                                          |
| Pre-commit hook fails on prettier `npm install`                                              | Spotless gradle task fails                              | Workaround: `npm install --registry=https://registry.npmjs.org/` in `build/spotless-node-modules-prettier-format/`                               |

## 9. Non-goals

- This is **not** about redesigning partition lifecycle broadly
- This is **not** about exhaustively investigating every hypothesis — fail-fast on first CONFIRMED + working fix
- This is **not** about performance optimization (H1's WAL enable IS a perf trade — document, don't optimize)
- This is **not** about re-running the same fixes the prior agent disproved
- This is **not** about resolving the unaddressed late-replica-bootstrap hypothesis from BLOCKED-NOTES (that's a
  separate work-stream if needed)

## 10. Final report shape

When done (success OR halt), write a single message back with:

- One-paragraph verdict (PASS / PARTIAL / FAILED with headline mechanism)
- For PASS: confirmed hypothesis + fix mechanism in 1-2 sentences + commit hash
- Per-hypothesis verification result table (which were tested, which were confirmed/disproven, key evidence)
- 15-row test result table (7 flag-on + 7 flag-off + 1 sister test) with wall times
- Files modified + line counts (`git diff --stat` for the final commit)
- Any follow-up risks the fix introduces (especially if H1 — document the WAL perf trade)
- `OUTCOME.md` location

If `BLOCKED-NOTES.md` was written, include its contents verbatim in the final report.

## 11. Why this matters

Three consecutive halts (original Phase C, validation work-stream, prior fix work-stream) have surfaced increasingly
precise diagnostic information but no fix. This walkdown takes the structured hypothesis space from `EXTRA-NOTES.md` and
tests each one explicitly. Either:

- **A hypothesis is confirmed and a fix lands** → the design is read-correct for the full advertised feature surface;
  production-readiness milestone met
- **All hypotheses disproven** → the bug is in territory we've explicitly checked. The remaining unaddressed hypothesis
  (late-replica bootstrap bypass from BLOCKED-NOTES) becomes the focus of a future work-stream
- **Hypothesis confirmed but fix elusive** → we have precisely-characterized scope for the actual fix work (likely needs
  LinkedIn Venice-internals expertise)

Either way, this is the cleanest remaining path before handing off to a human expert.
