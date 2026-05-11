# Chunked-Value Read-Path Fix — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-11 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation; 6th
investigation in the chain) **Execution model:** Autonomous agent. Three phases (characterize → fix → validate). 10-iter
budget. Production code modifications authorized. **Estimated effort:** Best case (fix is straightforward
read-path-only): ~2-3 hours. Worst case (requires leader-side or partition-lifecycle change): ~5-6 hours then halt with
structured evidence.

---

## 0. Background — H5 fix unlocked 3 tests; 4 still fail with this separate bug

The prior `late-replica-bootstrap-bypass` work-stream (commit `3bbce2ce0`) found and fixed a partition-routing bug in
`VeniceWriter`. That fix unblocked **3 of 7** in-scope flag-on `PartialUpdateTest` invocations:

| Test                                                                   | Status                      |
| ---------------------------------------------------------------------- | --------------------------- |
| `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on                    | ✅ PASS (was FAIL)          |
| `testPartialUpdateOnBatchPushedKeys[GZIP]` flag-on                     | ✅ PASS (was FAIL)          |
| `testPartialUpdateOnBatchPushedKeys[ZSTD_WITH_DICT]` flag-on           | ✅ PASS (was FAIL)          |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys` flag-on          | ❌ FAIL (this work targets) |
| `testActiveActivePartialUpdateWithCompression[NO_OP]` flag-on          | ❌ FAIL (this work targets) |
| `testActiveActivePartialUpdateWithCompression[GZIP]` flag-on           | ❌ FAIL (this work targets) |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` flag-on | ❌ FAIL (this work targets) |

The 4 remaining failures all involve **chunked values** — values large enough that Venice's chunking layer splits them
across multiple RocksDB entries with a top-level manifest pointing at the chunks.

**Required reading** before the agent starts:

- `autoresearch/late-replica-bootstrap-bypass/OUTCOME.md` — H5 fix outcome
- `autoresearch/late-replica-bootstrap-bypass/h5-iter-3-NOTES.md` — fix + initial test results; characterizes the
  chunked-value bug at the end
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/MaterializingFraming.java` (lines
  186-188 specifically)

## 1. The chunked-value bug (precise statement)

From `MaterializingFraming.materialize` at lines 186-188:

```java
int schemaId = ByteUtils.readInt(raw, 0);
if (schemaId < 0) {
  // Chunk or manifest — never framed.
  return raw;
}
```

**The intent:** when the on-disk bytes begin with a negative schemaId (Venice's marker for chunk or chunk-manifest
entries), the materializer returns the bytes verbatim so downstream chunking reassembly can handle them normally.

**The bug:** when partial-update operands are merged onto a chunked-manifest entry, the on-disk bytes are NOT just the
manifest — they are `[manifest_with_negative_schemaId][delim 0x01][framed_operand][delim 0x01][framed_operand]...` after
RocksDB's `StringAppendOperator.FullMerge` runs. Returning these bytes verbatim breaks downstream chunking reassembly
because the appended operand bytes corrupt the manifest format.

**Why it's a NEW bug surfaced by H5:** before the H5 fix, operands for chunked records were routed to the wrong
partition and never landed on the same partition as the manifest. So the bug existed but was masked by the routing bug.
H5 fixed the routing; now operands DO arrive at the chunked manifest's partition, exposing this read-path bug.

## 2. Goal

Fix the chunked-value read-path so that **all 4 remaining flag-on tests pass**, with the existing 3 flag-on tests +
flag-OFF baseline + sister test still passing. The H5 fix is a strict prerequisite (no regression on it).

**Success criterion:** all 7 flag-on PartialUpdateTest invocations PASS + 7 flag-off PASS + 1 sister test PASS = 15/15.

## 3. Scope

### In scope

- **Production code modifications authorized** for diagnostics and fixes
- Investigation in `MaterializingFraming.materialize` and surrounding code paths
- Possibly extending into the leader-side `ActiveActiveStoreIngestionTask` if the cleanest fix is at the leader (e.g.,
  fall back to RMW for chunked records under flag-on)
- Investigation in `RawBytesChunkingAdapter`, `ChunkingUtils`, or similar for the chunking reassembly path
- Adding new unit tests as regression guards for the fix
- Reading the existing chunked-value code paths

### Out of scope

- Modifying the H5 fix (commit `3bbce2ce0`); it's a prerequisite, not a target
- Changing the on-disk wire format (`ConcatBlobParser` framing stays; `KIND_BASE`/`KIND_OPERAND` framing stays)
- Changing flag-OFF behavior (every change must be gated by flag-on)
- Modifying integration test code (no fixture mutations)
- Touching the 8 regression-guard unit tests added by prior work-streams (`MaterializingPartitionCloseReopenRaceTest` +
  `VeniceWriterVtMergeOperandRoutingTest`)
- Modifying the chunking layer itself (chunking semantics are not the bug — the issue is operand interaction with
  chunking)

## 4. Three fix candidates (agent picks one based on investigation)

### Option A — Read-path fold-and-rewrite for chunked manifest + operands

When `MaterializingFraming.materialize` sees a chunked-manifest blob WITH appended operand bytes:

1. Parse the concat blob via `ConcatBlobParser.parse` (it already knows how to find operand suffixes)
2. Strip operands from the manifest bytes
3. Use the existing chunked-read path to reassemble the chunks into a materialized value
4. Apply the operands to the materialized value via `MaterializingFoldContext.foldOperands`
5. Re-frame and return (possibly without chunking if result fits inline; possibly re-chunking if too large)

**Pros:** localized to the read path; no leader-side change; no extra writes. **Cons:** read latency cost on first read
of a chunked record that's been partially updated.

### Option B — Follower-side RMW fallback for chunked records

In the follower's `partition.merge` override (`MaterializingReplicationMetadataRocksDBStoragePartition.merge`):

1. Detect if the existing on-disk value for this key is a chunked manifest (negative schemaId)
2. If yes: read + reassemble + apply operand + write back as a fresh chunked PUT
3. If no: existing merge path

**Pros:** read path stays simple; write-once cost. **Cons:** RMW cost on the follower for every
operand-on-chunked-record write; defeats some of the design's benefit for large records.

### Option C — Leader-side RMW fallback for chunked records

In the leader's `processMessageAndMaybeProduceToKafka` (the fast-path that skips RMW under flag-on):

1. Before deciding to produce an operand UPDATE, check if the existing value is chunked
2. If chunked: fall back to RMW path — read + materialize + apply + produce full PUT (re-chunked)
3. If not chunked: existing fast-path

**Pros:** simplest semantically; preserves the read path's chunking invariants; never produces operands for chunked
records. **Cons:** leader has to detect chunking (which requires reading the manifest); reduces design's write-side
benefit for large records.

### Investigation guidance

The agent should:

1. Phase 1: characterize the actual on-disk bytes for the failing chunked tests via diagnostics. Add
   `[CHUNKED-MANIFEST-OPERAND]` log line in `MaterializingFraming.materialize` capturing what the bytes look like when
   `schemaId < 0`.
2. Confirm the bug shape: are the operand bytes really being appended to a chunked manifest? If so, which option (A/B/C)
   is appropriate?
3. Phase 2: implement the chosen option. Write a unit-test reproducer first (chunked base + operand merge → reassembled
   materialized value).
4. Phase 3: validate against the 15-invocation gate.

## 5. Phased plan

### Phase 1 — Characterize the bug (iter 1-3)

**Build:**

- Add `[CHUNKED-MANIFEST-OPERAND]` diagnostic in `MaterializingFraming.materialize` when `schemaId < 0`:
  ```java
  LOGGER.info("[CHUNKED-MANIFEST-OPERAND] schemaId={} rawLen={} firstBytes={} containsOperandDelim={}",
      schemaId, raw.length, hexBytes(raw, 16), containsKindByte(raw, KIND_OPERAND));
  ```

**Verify:**

- Run `testActiveActivePartialUpdateOnBatchPushedChunkKeys` flag-on (~50s)
- Grep logs for `[CHUNKED-MANIFEST-OPERAND]` events
- Look for blobs where `rawLen` > expected manifest size AND `containsOperandDelim=true`

**Exit:**

- Confirmed: chunked-manifest bytes with appended operand bytes observed → advance to Phase 2 with option selection
- Refuted: no operand bytes appended; the bug is elsewhere → halt with `BLOCKED-NOTES-CHUNKED.md`

### Phase 2 — Apply fix (iter 4-7)

**Build:**

1. Write a unit-test reproducer in `clients/da-vinci-client/src/test/java/...` that:
   - Constructs a chunked-manifest blob
   - Appends operand bytes (simulating post-merge state)
   - Calls `MaterializingFraming.materialize`
   - Asserts the materialized result is correct
2. The test should FAIL initially. Iterate fix until it passes.
3. Verify single integration test `testActiveActivePartialUpdateOnBatchPushedChunkKeys` flag-on PASSES.
4. If pass: run full 7-test flag-on gate.

**Decision:**

- All 7 flag-on PASS → advance to Phase 3
- Some still fail with different symptom → iterate within Phase 2 budget
- Same symptom or new regression → revert and re-investigate

### Phase 3 — Validate + cleanup (iter 8-10)

**Build:** none (cleanup only).

**Verify:**

- Remove `[CHUNKED-MANIFEST-OPERAND]` diagnostic (or downgrade to DEBUG)
- `git diff --stat` minimal (production code ideally < 100 LOC; tests: 1 new unit test class)
- Run full 15-invocation gate (7 flag-on + 7 flag-off PartialUpdateTest + 1 sister test)
- Write `OUTCOME.md` documenting the fix mechanism

## 6. Iteration policy

10-iter total budget. Each iter writes `chunked-iter-N-NOTES.md` in `autoresearch/chunked-value-read-path-fix/`.

Per-phase guidance: 3 (verify) + 4 (fix) + 3 (validate).

Bug-clustering principle: if a single fix unlocks all 4 chunked invocations, it's one iteration. Iterate within budget
if some tests still fail with a different symptom after the fix.

## 7. Run commands

Standard pattern with init script + fail-fast:

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

# Unit tests (<1 sec)
./gradlew :clients:da-vinci-client:test --tests "<FQN>" --rerun-tasks

# Single chunked integration test (Phase 1 + 2)
cd /home/coder/Projects/venice && \
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast \
    > /tmp/chunked-iter-N.log 2>&1

# Full 7-test flag-on gate (Phase 2 exit, Phase 3)
cd /home/coder/Projects/venice && \
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateWithCompression" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast

# Sister regression check (Phase 3)
./gradlew --init-script /tmp/disable-test-retry.gradle \
  :internal:venice-test-common:integrationTest \
  --tests "com.linkedin.venice.endToEnd.TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField" \
  -Dvt.update.operand.flag=true \
  --rerun-tasks --fail-fast
```

## 8. Reference files

### Suspected bug surface (highest priority reading)

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/MaterializingFraming.java` (lines
  186-188; the bug site)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/ConcatBlobParser.java` (parser;
  KIND_BASE/KIND_OPERAND constants)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/MaterializingFoldContext.java` (fold
  logic that may need to be invoked even for chunked manifests)

### Chunking layer (read to understand the chunked-read path)

- `internal/venice-common/src/main/java/com/linkedin/venice/chunking/ChunkingUtils.java` (likely)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/storage/chunking/RawBytesChunkingAdapter.java` (likely)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/storage/chunking/ChunkingAdapter.java` (likely)

### Leader fast-path (read if considering Option C — leader-side RMW fallback)

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java`
  (`processMessageAndMaybeProduceToKafka`, `produceUpdateOperandToVT`)

### Tests (regression-guard layer)

- `clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/MaterializingPartitionCloseReopenRaceTest.java`
  (DO NOT modify; regression guard)
- `internal/venice-common/src/test/java/com/linkedin/venice/writer/VeniceWriterVtMergeOperandRoutingTest.java` (DO NOT
  modify; regression guard for H5)
- `internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/endToEnd/PartialUpdateTest.java` (the
  integration tests under validation)

## 9. Decision criteria

**YES (success):** all 15 invocations PASS + diagnostic logs cleaned up + `OUTCOME.md` written + commit hash captured +
new unit test as regression guard.

**PARTIAL:** ≥ some of the 4 chunked tests now PASS but not all. Document and halt with `BLOCKED-NOTES-CHUNKED.md`;
classify each remaining failure.

**FAILED / BLOCKED:** budget exhausted with chunked tests still failing. Write `BLOCKED-NOTES-CHUNKED.md` with confirmed
bug shape, attempted fixes, and recommended next steps.

## 10. Risks

| Risk                                                                             | How we'd see it                                                                      | Response                                                                                               |
| -------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------ | ------------------------------------------------------------------------------------------------------ |
| Fix requires changes in chunking layer (out of scope)                            | Phase 2 attempts fail because the chunking layer can't accept operand-suffixed bytes | Halt with `BLOCKED-NOTES-CHUNKED.md`; this becomes a bigger design conversation                        |
| Option A reassembly is too slow → integration test timeout                       | Test runs >2 min but eventually fails                                                | Revert; try Option B or C                                                                              |
| Option B/C requires reading current value before merging → reintroduces RMW cost | Trade-off explicit                                                                   | Document; this may be the right answer if chunked-value frequency in production is low                 |
| Fix breaks the 3 H5-unblocked tests                                              | H5 regression                                                                        | Iterate; if can't reconcile, halt                                                                      |
| Fix breaks flag-off behavior                                                     | Flag-OFF regression                                                                  | Iterate; if can't reconcile, halt                                                                      |
| Pre-commit hook fails on prettier                                                | Spotless gradle task fails                                                           | `npm install --registry=https://registry.npmjs.org/` in `build/spotless-node-modules-prettier-format/` |

## 11. Non-goals

- This is **not** about redesigning the chunking layer broadly
- This is **not** about optimizing chunked-value read performance (correctness only)
- This is **not** about modifying the iter-11 fix or the H5 fix (both are prerequisites)
- This is **not** about validating beyond the 15 in-scope invocations

## 12. Final report shape

When done (success OR halt), write a single message back with:

- One-paragraph verdict (PASS / PARTIAL / FAILED)
- For PASS: chosen fix option (A/B/C) + mechanism in 1-2 sentences + commit hash
- 15-row test result table (7 flag-on + 7 flag-off + 1 sister) with wall times
- Files modified, line counts (`git diff --stat`)
- New unit test added (file path + LOC)
- Follow-up risks
- `OUTCOME.md` location

If `BLOCKED-NOTES-CHUNKED.md` was written, include verbatim.

## 13. Why this matters

The H5 fix unblocked 3 tests; this work, if successful, would unblock the remaining 4 and complete the original Phase C
goal across the full advertised feature surface (scalar SET + map merge + list merge + chunked values + all compression
strategies + AA replication). That would be the **first time** all 7 in-scope `PartialUpdateTest` invocations have
passed flag-on since the experiment began — a true production-readiness milestone for the design's correctness.
