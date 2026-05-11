# H5: Late-Replica Bootstrap Bypass — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-11 **Branch:** `haoxu07/vt-rocksdb-merge-design` (5th-hypothesis
investigation; the partition-layer surface has been exhausted) **Execution model:** Autonomous agent. Verify hypothesis
with diagnostics → apply fix → validate. 10-iter budget. Production code modifications authorized. **Estimated effort:**
Best case (RCA confirmed iter 1-2, fix small): ~2-3 hours. Worst case (RCA confirmed but fix architecturally hard): ~5-6
hours then halt with structured evidence.

---

## 0. Background — 4 prior work-streams have narrowed the bug surface

This is the **5th** investigation in the chain. Each prior halt has structurally narrowed the bug location:

| Work-stream                                    | Outcome                                                                                  | What it ruled out                                   |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------- | --------------------------------------------------- |
| 1. Original Phase C                            | "Tier 3 design issue"; iter-11 found operand-snapshot bug                                | Most Tier-1/2 patches                               |
| 2. partial-update-batch-pushed-keys-validation | Bug #6 discovered: follower partition lifecycle race                                     | iter-11 fix doesn't generalize beyond MapField test |
| 3. partial-update-followers-race-fix           | 3 plausible fixes disproven (sync, flush, put-preserve)                                  | Simple memtable-loss-on-close hypothesis            |
| 4. partial-update-followers-race-fix walkdown  | All 4 hypotheses (WAL, iter-11 skip, registry reset, partition-layer DC split) disproven | Everything **inside** the partition layer           |

**Current state:** the bug is confirmed UPSTREAM of `partition.merge`. The partition-layer code is correct (verified by
5 unit tests in `MaterializingPartitionCloseReopenRaceTest.java`).

**Required reading** before the agent starts:

- `autoresearch/partial-update-followers-race-fix/BLOCKED-NOTES.md` — bug surface narrowed by Phase 2 disproofs
- `autoresearch/partial-update-followers-race-fix/BLOCKED-NOTES-WALKDOWN.md` — 4 hypotheses disproven; recommended next
  steps (this GOAL targets #1)
- `autoresearch/partial-update-followers-race-fix/EXTRA-NOTES.md` — H5 hypothesis described
- `autoresearch/partial-update-followers-race-fix/iter-1-NOTES.md` — Phase 1 RCA evidence (134/368 operand-only
  readbacks)

## 1. The H5 hypothesis (precise statement)

The late-joining follower replica (e.g., `dc-0:37157` in iter-1's evidence) **had NO `BEGIN_BATCH_PUSH` adjust on
user-data partitions** — only `PREPARE_FOR_READ`. This means its data partitions were initialized through a different
code path than a normal-startup replica.

**Suspected mechanism:**

1. Normal-startup replica: consumes batch from VT → calls `partition.put` → materializing override applies kind-byte
   framing → bases land on disk as `[schemaId][KIND_BASE][len][avro]`
2. **Late-joining replica:** acquires state via **blob transfer**, **snapshot restore**, or **direct SST file
   ingestion** from another replica → the framing override is bypassed → bases land on disk as raw `[schemaId][avro]`
   (no kind-byte)
3. When a partial-update operand arrives later, `partition.merge` correctly applies the operand-kind-byte framing
4. RocksDB FullMerge produces concat blob: `[raw_base_bytes][delim_0x01][framed_operand_bytes]`
5. `ConcatBlobParser.parse` sees `blob[0] != KIND_BASE` → returns base=null + operands=[whole blob]
6. **Read returns "operand only, no base"** — matching the 134/368 evidence exactly

**Why this matches the split-half pattern:** roughly half the data partitions land on the late-joining replica (via
bootstrap), half on a normal-startup replica (via normal consumption). The half on the late replica all manifest the
bug; the half on the normal replica are correctly framed.

**Why flag-OFF doesn't manifest:** BASELINE doesn't apply any framing at all. Reads expect raw bytes and find them. The
kind-byte framing is only required when flag-on.

## 2. Goal

Verify the H5 hypothesis with targeted diagnostics, then apply a minimal fix that ensures **all paths** that write a
base PUT for a flag-on store invoke the materializing framing. Validate against the 15-invocation gate.

**Success criterion:** all 7 flag-on PartialUpdateTest invocations PASS + 7 flag-off PASS + 1 sister test PASS = 15/15.

## 3. Scope

### In scope

- **Production code modifications authorized** for diagnostics and fixes
- Adding `[BOOTSTRAP-PUT]`, `[BLOB-TRANSFER-PUT]`, `[SST-INGEST]` etc. log lines on every non-`partition.put` write path
- Inspecting on-disk SST bytes via `sst_dump` for one operand-only key (the agent should set up the tooling or use
  `rocksdb.cfstats`-style introspection)
- Modifying the bootstrap path so that base bytes are correctly framed before landing on disk (the actual fix)
- Adding new unit tests as regression guards for the fix

### Out of scope

- Modifying the on-disk wire format (`ConcatBlobParser` framing stays)
- Changing leader-side write contract
- Changing flag-OFF behavior
- Modifying integration test code (no fixture mutations)
- Replacing `StringAppendOperator`
- Touching the 5 unit tests in `MaterializingPartitionCloseReopenRaceTest` (those are regression guards)

## 4. Phased plan

### Phase 1 — Verify the bootstrap-bypass hypothesis (iter 1-3)

**Sub-step 1a: Identify all non-`partition.put` paths that write base bytes**

Read code paths:

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/AbstractStorageEngine.java` (and
  `RocksDBStorageEngine.java`) — look for `ingestSstFile`, `addPartition`, `restoreFromBackup`, or similar
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/blobtransfer/...` (if present) — blob transfer
  client/server
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/storage/...` — bootstrap-related controllers
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreIngestionTask.java` — search for
  `addStoragePartition`, batch-push hooks
- Search for `BEGIN_BATCH_PUSH`, `END_BATCH_PUSH`, `PREPARE_FOR_READ` callers to map the partition state-machine

Enumerate every entry point that writes base bytes. Verify whether each entry point invokes the materializing-put
framing override (i.e., goes through `MaterializingRocksDBStoragePartition.put` / `putWithReplicationMetadata`) or
bypasses it.

**Sub-step 1b: Add diagnostic log lines at every base-write entry point**

For each entry point found in 1a, add a `[BASE-WRITE-PATH]` log line capturing:

- The entry point name (e.g., `partition.put`, `partition.ingestSstFile`, `blobTransferClient.applySnapshot`)
- Whether framing is applied (by checking thread-local `FRAMING_IN_PROGRESS` or equivalent indicator)
- Partition.identityHash, key.first8 bytes, value first 4-8 bytes (to see if kind-byte is present)
- Thread name

Run `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on. Server logs should show every base-write path.

**Sub-step 1c: Cross-reference with operand-only readback evidence**

For each operand-only readback key (from the prior `[VT-MERGE-READBACK]` evidence), look up:

- Where its base was written
- Through which entry point
- Was framing applied?

**Decision after Phase 1:**

- CONFIRMED — operand-only keys' bases all come from a non-framed entry point → advance to Phase 2
- DISPROVEN — operand-only keys' bases come from `partition.put` with framing applied → H5 is wrong; halt with
  `BLOCKED-NOTES-H5.md`
- AMBIGUOUS after 3 iters → halt with `BLOCKED-NOTES-H5.md`

### Phase 2 — Apply the fix (iter 4-7)

**Sub-step 2a: Add a unit test reproducer for the identified bypass**

Write a unit test in `clients/da-vinci-client/src/test/java/.../store/rocksdb/MaterializingPartitionBootstrapTest.java`:

```java
@Test
public void bootstrapPathPreservesFramingOnBasePut() {
  // 1. Open a flag-on MaterializingRocksDBStoragePartition
  // 2. Invoke the suspect bootstrap entry point (e.g., ingestSstFile) with raw Avro bytes
  // 3. Then call partition.merge(key, operandBytes)
  // 4. Read back via partition.get
  // 5. Expectation: the read returns the materialized record (base + operand applied)
  // 6. If the bypass is real, this test fails — confirming the reproducer
}
```

If the bypass is real, this unit test fails immediately. The fix iterates against this unit test (sub-seconds per
attempt) until it passes.

**Sub-step 2b: Apply the fix**

The fix shape depends on the identified bypass — most likely candidates:

- **If `ingestSstFile` bypasses framing:** wrap the SST ingestion to pre-process bytes through the framing logic, or
  write a stream-rewriting SST-to-SST transformer that applies kind-byte prefix
- **If blob transfer bypasses framing:** intercept blob bytes at the transfer endpoint and re-frame before writing
- **If the bootstrap path calls a sibling method that doesn't go through `partition.put`:** route it through
  `partition.put` instead, or add framing to the sibling method

Each fix should:

- Be guarded by the `vt.update.operand.enabled` flag (no impact on flag-off)
- Be minimal (< 30 LOC ideally; document why if larger)
- Preserve the existing semantics for flag-off

**Sub-step 2c: Verify the fix passes the unit test reproducer + integration test**

1. Unit test passes
2. Integration: `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on PASSES
3. Full 7-test flag-on gate PASSES
4. Flag-off regression: 7-test flag-off PASSES
5. Sister test (`testActiveActivePartialUpdateWithRecordMapField`) flag-on PASSES

If 1-5 all pass, advance to Phase 3. If any fails, iterate within budget.

### Phase 3 — Validate + cleanup (iter 8-10)

**Build:** none (cleanup only).

**Verify:**

- Remove diagnostic `[BASE-WRITE-PATH]` logging (or downgrade to DEBUG)
- `git diff --stat` minimal (production code: ideally < 30 LOC; tests: 1 new unit test class)
- Run the full 15-invocation gate:
  - 7 × flag-on PartialUpdateTest invocations
  - 7 × flag-off PartialUpdateTest invocations
  - 1 × sister test (TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField)
    flag-on
- Write `OUTCOME.md` documenting the bypass mechanism + fix

## 5. Iteration policy

**10-iter total budget** distributed across the 3 phases:

| Phase                        | Suggested iters | Halt condition                                                                                       |
| ---------------------------- | --------------- | ---------------------------------------------------------------------------------------------------- |
| Phase 1 (verify)             | 1-3             | If RCA not confirmed/disproven by iter 3 → BLOCKED-NOTES.md, halt                                    |
| Phase 2 (fix)                | 4-7             | If unit test or 7/7 not passing by iter 7 → BLOCKED-NOTES.md with confirmed bypass + attempted fixes |
| Phase 3 (validate + cleanup) | 8-10            | If regression on any test → BLOCKED-NOTES.md                                                         |

Each iteration writes `h5-iter-N-NOTES.md` in `autoresearch/late-replica-bootstrap-bypass/` with:

- Hypothesis being tested (or fix being applied)
- Code change (file paths, line counts)
- Test result
- Decision

## 6. Run commands

Same gradle init script + fail-fast as prior work-streams:

```bash
# Init script
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

# Single integration test
cd /home/coder/Projects/venice && \
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast \
    > /tmp/h5-iter-N.log 2>&1

# Full 7-test gate (Phase 2 exit, Phase 3)
cd /home/coder/Projects/venice && \
  ./gradlew --init-script /tmp/disable-test-retry.gradle \
    :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testPartialUpdateOnBatchPushedKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateOnBatchPushedChunkKeys" \
    --tests "com.linkedin.venice.endToEnd.PartialUpdateTest.testActiveActivePartialUpdateWithCompression" \
    -Dvt.update.operand.flag=true \
    --rerun-tasks --fail-fast

# Sister regression check
./gradlew --init-script /tmp/disable-test-retry.gradle \
  :internal:venice-test-common:integrationTest \
  --tests "com.linkedin.venice.endToEnd.TestPartialUpdateWithActiveActiveReplication.testActiveActivePartialUpdateWithRecordMapField" \
  -Dvt.update.operand.flag=true \
  --rerun-tasks --fail-fast
```

## 7. Test gates

| Gate                       | Threshold                                                                                           |
| -------------------------- | --------------------------------------------------------------------------------------------------- |
| Phase 1 exit               | Diagnostic logs identify the bypass path (or conclusively rule out the bootstrap-bypass hypothesis) |
| Phase 2 exit (unit)        | New unit test reproduces and then passes with the fix                                               |
| Phase 2 exit (integration) | Full 7-test flag-on gate PASSES                                                                     |
| Phase 3 exit               | All 15 invocations PASS; diagnostic logs removed                                                    |

## 8. Decision criteria

**YES (success):** all 15 invocations PASS + diagnostic logs cleaned up + `OUTCOME.md` written + commit hash captured.
Optionally, the new unit test serves as a permanent regression guard.

**PARTIAL:** bypass confirmed but fix elusive within budget. Write `BLOCKED-NOTES-H5.md` with the confirmed bypass +
attempted fixes + recommended next steps.

**FAILED / BLOCKED:** Phase 1 disproves H5 (bypass not found OR operand-only keys' bases all came from `partition.put`
with framing) → write `BLOCKED-NOTES-H5.md` listing the remaining hypotheses (H6 consumer routing; H7 SST checksum
framing).

## 9. Risks

| Risk                                            | How we'd see it                                                                      | Response                                                                                                                      |
| ----------------------------------------------- | ------------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| Bootstrap path is hard to identify              | Phase 1 iter 1 doesn't find a clear non-`partition.put` write path                   | Grep harder; look for direct RocksDB API usage; check `RocksDBStoragePartition.beginBatchWrite/endBatchWrite`/`ingestSstFile` |
| Bypass exists but fix is architecturally large  | Phase 2 iter 5+ involves > 100 LOC of refactoring                                    | Halt; document the design tension; this becomes a larger work-stream than this GOAL covers                                    |
| Fix breaks flag-off path                        | Flag-off regression in Phase 2                                                       | Iterate within budget; if can't reconcile, halt                                                                               |
| Fix breaks sister test                          | Sister test regresses in Phase 3                                                     | Iterate; if can't reconcile, halt                                                                                             |
| Hypothesis disproven                            | Phase 1 evidence shows all operand-only keys' bases came from framed `partition.put` | H5 ruled out; halt with structured evidence; the bug is in consumer-thread routing or SST checksum framing                    |
| Pre-commit hook fails on prettier `npm install` | Spotless gradle task fails                                                           | Workaround: `npm install --registry=https://registry.npmjs.org/` in `build/spotless-node-modules-prettier-format/`            |

## 10. Non-goals

- This is **not** about refactoring the bootstrap path broadly — only fix the framing-bypass for flag-on
- This is **not** about implementing a new bootstrap mechanism — only fix the existing one's framing
- This is **not** about validating beyond the 15 in-scope invocations
- This is **not** about retrofitting earlier-disproven hypotheses

## 11. Final report shape

When done (success OR halt), write a single message back with:

- One-paragraph verdict (PASS / PARTIAL / FAILED)
- For PASS: the identified bypass mechanism + fix mechanism in 1-2 sentences + commit hash
- 15-row test result table (7 flag-on + 7 flag-off + 1 sister) with wall times
- Files modified + line counts (`git diff --stat`)
- New unit test added (file path + line count)
- Any follow-up risks
- `OUTCOME.md` location

If `BLOCKED-NOTES-H5.md` was written, include verbatim.

## 12. Why this is the most-leveraged remaining hypothesis

The "split-half" pattern observed by the prior agent (exactly half the keys on the same partition affected within the
same second) is structurally consistent with **a half-of-replicas bootstrap divergence**:

- Two replicas per partition (under AA with 2 regions)
- One is normally-started (framing applied)
- One is late-joining (framing bypassed if H5)
- Reads round-robin across replicas
- ~50% of reads hit the bug

Other hypotheses (consumer routing, SST checksum framing) are also possible explanations of the split-half pattern, but
H5 is the simplest and matches the **observed evidence** about the late replica's missing `BEGIN_BATCH_PUSH` adjust. If
H5 turns out to be the bug, the fix is mechanical (route the bootstrap path through the framing override). If H5 is
disproven, we've still eliminated a major class of explanations and the BLOCKED-NOTES gives a structured starting point
for the next investigation.
