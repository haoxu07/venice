# h5-iter-4 — Phase 3: cleanup + final validation

## Cleanup

Removed all `[BASE-WRITE-PATH]`, `[VT-MERGE-READBACK]`, `[ADJUST-PARTITION]` diagnostic log lines that were added in
iter-1. Production diff is now minimal — 2 files, 87 insertions, 8 deletions:

```
clients/da-vinci-client/.../ActiveActiveStoreIngestionTask.java | 28 +++++++++--------
internal/venice-common/.../VeniceWriter.java                    | 67 +++++++++++++++++++++++++++++++++++
```

The actual fix delta is even smaller — the bulk of the +67 in VeniceWriter is Javadoc and comments. Net code change is:

- ~25 lines of new method body in `VeniceWriter.updateForVtMergeOperand`
- 6-line callsite swap in `ActiveActiveStoreIngestionTask.produceUpdateOperandToVT` (from `.update(keyBytes, ...)` to
  `.updateForVtMergeOperand(rawKeyBytes, ...)`)

## Final validation

Post-cleanup:

| Gate                                                                  | Result   | Wall        |
| --------------------------------------------------------------------- | -------- | ----------- |
| `testPartialUpdateOnBatchPushedKeys` flag-on (3 invocations)          | 3/3 PASS | 95.9s total |
| `testPartialUpdateOnBatchPushedKeys` flag-off (3 invocations)         | 3/3 PASS | 98.4s total |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys` flag-off        | PASS     | 38.5s       |
| Sister test `testActiveActivePartialUpdateWithRecordMapField` flag-on | PASS     | 37.0s       |
| Regression guards `MaterializingPartitionCloseReopenRaceTest`         | 5/5 PASS | <2s         |
| New `VeniceWriterVtMergeOperandRoutingTest`                           | 3/3 PASS | <1s         |

## Out-of-scope (acknowledged failures)

The 4 chunked-value flag-on invocations still fail. These are pre-existing bugs unrelated to the H5 partition-routing
mechanism — see `OUTCOME.md` §"Follow-up risks" for the proposed next investigation.

## Decision

**SUCCESS for the H5 partition-routing mechanism.** PARTIAL outcome on the full 15-invocation gate because of unrelated
pre-existing chunked-value bugs.
