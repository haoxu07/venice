# Iter-2 — Diagnostic uplift via log4j2.properties (test-infra config only)

## Hypothesis tested

The 7 failing flag-on invocations from iter-1 share a single root failure mode (operand not visible at read time).
Determine whether the failure is on the WRITE path (operand never reaches RocksDB) or the READ path (operand on disk but
not folded).

## Approach

Temporarily promoted VT-merge diagnostic loggers from DEBUG to INFO/DEBUG via
`internal/venice-test-common/src/integrationTest/resources/log4j2.properties` (test infrastructure config; the
diagnostic emit sites in production code are untouched, only their visibility threshold). Re-ran a single isolated
invocation `testPartialUpdateOnBatchPushedKeys[NO_OP]` flag-on.

## Result

FAILED with `expected [new_name_2] but found [first_name_2]` at 46.798s.

## Key findings from server-side logs (extracted from `output.bin` via `strings`)

For one user store (`updateBatch_14dc66109885c_1eda3d28_v1`) on 4 servers (2 per region), reading partition=0 across
timestamps shows the operand IS on disk for some replicas but NOT others:

| Time     | Server (DC-0) | Partition | rawLen | Has operand? | Replica role |
| -------- | ------------- | --------- | ------ | ------------ | ------------ |
| 19:19:07 | dc-0:46363    | 2         | 55     | YES          | LEADER       |
| 19:19:07 | dc-0:46363    | 0         | 31     | NO           | follower     |
| 19:19:07 | dc-0:42103    | 2         | 55     | YES          | follower     |
| 19:19:07 | dc-0:42103    | 0         | 55     | YES          | LEADER       |
| 19:19:07 | dc-0:46363    | 1         | 55     | YES          | LEADER       |
| 19:19:07 | dc-0:42103    | 1         | 31     | NO           | follower     |

The pattern: **the LEADER replica for each partition has the operand, but at least one FOLLOWER replica does NOT have
it.** This suggests the operand merge is not propagating from leader to follower correctly (or the follower is not
applying the merge).

## On-disk hex dump for a key WITH operand applied

```
00 00 00 01 00 19 18 66 69 72 73 74 5f 6e 61 6d 65 5f 31 16 6c 61 73 74 5f 6e 61 6d 65 5f 31  ← framed base
01                                                                                            ← StringAppendOperator delimiter
01 15                                                                                          ← KIND_OPERAND + varint(21)
00 00 00 01 00 00 00 01                                                                       ← valueSchemaId(1) + updateSchemaId(1)
02 14 6e 65 77 5f 6e 61 6d 65 5f 31 00                                                        ← Avro WC payload "new_name_1"
```

Materialize log: `rawLen=55 baseLen=25 opCount=1 ctxNull=false` — parsing succeeds; fold context is registered. Read
fold should produce updated value with `firstName=new_name_1`.

## On-disk hex dump for a key WITHOUT operand applied (same store, different replica)

```
00 00 00 01 00 19 18 66 69 72 73 74 5f 6e 61 6d 65 5f 32 16 6c 61 73 74 5f 6e 61 6d 65 5f 32  ← framed base only, no operand
```

rawLen=31. Just the framed base. The operand was never merged at this replica.

## Tier classification

**Tier 2: new bug investigation, partially scoped.** The operand IS being:

- Produced to VT by the leader (verified via the SAME store-version on the leader replica having the operand).
- Framed correctly (the framed-base + framed-operand format on disk decodes correctly via
  `MaterializingFraming.materialize`).

The operand is NOT being applied at one or more follower replicas. The follower's `case UPDATE` handler in
`StoreIngestionTask.processKafkaDataMessage` IS being invoked (per iter-3 follow-up logs), and `storageEngine.merge` is
called, but the merge does not result in the operand being persisted.

## Files modified

- `internal/venice-test-common/src/integrationTest/resources/log4j2.properties` (test infrastructure only — promoted
  VT-merge diagnostic logger levels). Reverted at end of iter-3.

## Next step

Iter-3 follow-up: bump `aatask`/`sittask` to DEBUG to surface the `follower case UPDATE` log in StoreIngestionTask,
confirming whether the merge call is actually happening at the followers that don't show the operand.
