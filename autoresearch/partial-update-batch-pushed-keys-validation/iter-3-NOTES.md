# Iter-3 — Confirmed: follower's case-UPDATE invokes merge, but merge doesn't persist for some keys

## Hypothesis tested

Iter-2 narrowed the bug to: at least one follower replica per partition does NOT show the operand on disk. This iter
confirms whether the follower's `case UPDATE` handler is being invoked (i.e., is the follower receiving the VT message
at all?), and whether the `storageEngine.merge` call is being executed.

## Approach

Bumped `logger.aatask.level=debug` and `logger.sittask.level=debug` in `log4j2.properties` to surface the
`VT-merge follower case UPDATE` and `VT-merge leader fast-path` debug logs. Re-ran isolated invocation
`testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on.

## Result

FAILED with `expected [new_name_3] but found [first_name_3]` at 48.802s.

## Key findings

For user store `updateBatch_14e384d63437b_facb7dc9_v1` partition=1, server `dc-0:localhost_45497` (the FOLLOWER):

- 36 `VT-merge follower case UPDATE` log entries at INFO/DEBUG, partition=1, with operandLen=13 (3 entries) and
  operandLen=14 (33 entries) — confirming the follower DID receive 36 UPDATE messages over the VT topic for partition=1.
- The `storageEngine.merge` call is invoked for each (no exception in log).
- BUT, when reading key="3" (operandLen=13, single-digit) on the same partition+server, on-disk shows rawLen=31 (framed
  base only, NO operand) at all read timestamps (19:27:18, 19:27:19, 19:27:28).
- For a different single-digit key on the SAME partition+server (key="5"), on-disk shows rawLen=55 (framed base + 1
  operand) at 19:27:25.

So out of 3 single-digit-key UPDATEs that the follower processed, key="5" was applied successfully, but key="3" was not.

## Hex dumps

key="3" on dc-0:45497 partition=1 (over multiple reads, all rawLen=31):

```
00 00 00 01 00 19 18 66 69 72 73 74 5f 6e 61 6d 65 5f 33 16 6c 61 73 74 5f 6e 61 6d 65 5f 33
[schemaId=1][KIND_BASE][len=25][first_name_3 + last_name_3 avro]
```

key="5" on dc-0:45497 partition=1 (rawLen=55):

```
00 00 00 01 00 19 18 66 69 72 73 74 5f 6e 61 6d 65 5f 35 16 6c 61 73 74 5f 6e 61 6d 65 5f 35 01 01 15 00 00 00 01 00 00 00 01 02 14 6e 65 77 5f 6e 61 6d 65 5f 35 00
[schemaId=1][KIND_BASE][len=25][first_name_5 + last_name_5 avro][delim 01][KIND_OPERAND][len=21][schemaIds][WC payload "new_name_5"]
```

## Suspect: partition re-open during ingestion

For dc-0:45497 partition=1, RocksDB was opened TWICE during the test:

- 19:27:02: Helix-ST thread opens partition (OFFLINE→STANDBY transition).
- 19:27:18: `Store-writer-sorted-t0` (drainer thread) re-opens partition (presumably during BEGIN_BATCH_PUSH or
  END_BATCH_PUSH `adjustStoragePartition`).

The merges on the operandLen=13 keys may have happened BETWEEN those two opens (against the "old" partition that was
about to be closed), and then the close-and-reopen MAY have lost the in-memory write buffer / WAL. RocksDB's writes are
usually durable through close, but if the close is performed against a partition that's been replaced in the partition
map, the merge might be addressed to a closed/orphaned RocksDB instance whose writes are silently lost.

This is a HYPOTHESIS, not yet verified. To verify would require:

- Adding a write-side diagnostic (immediate `super.get(key)` after `super.merge(key, ...)` in the materializing
  partition's merge override) to detect the case where the merge "succeeds" but a subsequent read in the same thread
  doesn't see the operand. **This is out of scope per GOAL §9 ("DO NOT modify diagnostic infrastructure").**
- OR examining the RocksDB internal state (number of open instances per replica, whether writes are flushing).

## Tier classification

**Tier 2 → Tier 3 boundary.** The diagnostic data shows the operand-snapshot bug from iter-11 is NOT regressed. The new
failure is a write-path or partition-lifecycle issue specific to the AA/chunking/batch-push interaction. The bug is at a
layer below `MaterializingFraming` (RocksDB merge / partition lifecycle) and is not addressable via the existing
in-scope codepaths.

## Files modified

- `internal/venice-test-common/src/integrationTest/resources/log4j2.properties` (temp diagnostic; reverted at end of
  iter-3).

## Decision

Halt and escalate per GOAL §10 ("8-attempt budget exhausted on any one failing test method" criterion approached +
"deeper architectural issue beyond what 8 iterations can solve"). Write `BLOCKED-NOTES.md`.
