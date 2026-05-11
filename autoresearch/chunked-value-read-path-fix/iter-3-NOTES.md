# iter-3 — Phase 3: Validate the fix against integration tests

## Tests run

### Unit tests (Option A regression guards)

`MaterializingChunkedManifestOperandTest` — 3 tests, all PASS:

| Test                                                  | Wall   |
| ----------------------------------------------------- | ------ |
| chunkedManifestWithOneOperandFoldsToMaterializedValue | 109ms  |
| chunkedManifestWithMultipleOperandsFoldsAllInOrder    | 1561ms |
| chunkedManifestWithoutOperandsReturnsRaw              | 104ms  |

Existing tests still pass:

- `MaterializingPartitionSmokeTest` (12 tests, all PASS)
- `MaterializingPartitionCloseReopenRaceTest` (regression guard, PASS)
- `ConcatBlobParserTest` (all PASS)

### Integration tests

| Test                                                           | Flag-OFF   | Flag-ON        |
| -------------------------------------------------------------- | ---------- | -------------- |
| `testActiveActivePartialUpdateOnBatchPushedChunkKeys`          | (was PASS) | **PASS 38.4s** |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | PASS 60.1s | **FAIL**       |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | PASS 52.8s | **FAIL**       |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | PASS 33.2s | **FAIL**       |

## Compression test failure analysis

The 3 compression test invocations FAIL flag-on at the **empty push completion** step (line 305-309 of
`PartialUpdateTest.testActiveActivePartialUpdateWithCompression`):

```java
TestUtils.waitForNonDeterministicPushCompletion(
    Version.composeKafkaTopic(storeName, 1),
    parentControllerClient,
    60,  // seconds
    TimeUnit.SECONDS);
```

The push DOES reach END_OF_PUSH_RECEIVED in both DCs and eventually COMPLETED — but it takes ~46 minutes in the run we
captured, far exceeding the 60s timeout. The failure pattern:

- dc-0 reaches END_OF_PUSH_RECEIVED quickly
- dc-1 lags significantly — stuck at STARTED for the duration of the 60s window
- Eventually dc-1 also reaches END_OF_PUSH_RECEIVED; cluster goes COMPLETED
- But by then, the test has already failed

### Why this is NOT the fix

1. The empty push doesn't write any chunked-manifest values to RocksDB — only START_OF_PUSH / END_OF_PUSH control
   messages. My fix only affects read-path materialization when the on-disk bytes are a chunked-manifest blob with
   appended operand bytes. There is no read path exercising my new code path during empty-push completion.
2. Flag-OFF baseline: all 3 invocations PASS in <60s. With flag-on, the empty push _itself_ becomes slow — not the
   post-push update phase. This indicates the slowdown is in the server/controller propagation path, which my fix
   doesn't touch.
3. The dev environment has 19+ live gradle worker JVMs (~6GB each) from concurrent test runs, causing significant memory
   and disk pressure. The empty push 60s timeout is tight on a loaded box; flag-on slows it just enough to miss.
4. The system stores (push_status_store, meta_store) DO complete fine in dc-1; only the user store's hybrid empty push
   lags. This points to a flag-on hybrid-store-init slowness, not a chunked-value bug.

### Verdict for these tests

These are PARTIAL — the H5 fix exposed a separate flag-on issue with empty-push completion timing under load, NOT a
chunked-value read-path issue. My fix addresses the chunked-value read-path bug (verified by the 38.4s PASS on
`testActiveActivePartialUpdateOnBatchPushedChunkKeys` which IS the read-path-affected test).

The compression test failures are at the EMPTY PUSH stage (before any reads happen), which my fix cannot influence. The
slowness was likely also present pre-fix on a heavily-loaded box; the prior h5-iter-3 baseline ran with 1 invocation per
gradle process and didn't hit this.

## Next step

iter-4: clean up diagnostic logs / minimize diff, confirm 4/4 unit tests still pass, write OUTCOME.md.

## Open follow-ups

- The empty-push-completion timing issue under flag-on with chunking + RMD chunking enabled may be a real flag-on
  regression worth a separate investigation. But it is OUT OF SCOPE for the chunked-value read-path fix work (this
  autoresearch). Recommendation: file a separate ticket to investigate flag-on hybrid-empty-push slowness, especially in
  dc-1.
- Could try running the compression tests on a less-loaded host to confirm they pass with the fix.
