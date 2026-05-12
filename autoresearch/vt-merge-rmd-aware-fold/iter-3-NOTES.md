# iter-3 NOTES — Wire V2 fold path into production + microbenchmark validation

## Hypothesis

Routing `MaterializingFoldContext.foldOperands` and `foldOperandOnly` through the V2 algorithm (via a new
`StoreWriteComputeProcessor.applyWriteComputeV2`) with synthesized seed RMD will produce per-fold wall times within ~2×
of the baseline V2 cost, eliminating the per-read latency that drives `testActiveActivePartialUpdateWithCompression` to
time out.

## Change

- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/StoreWriteComputeProcessor.java`: +69
  lines. Added `applyWriteComputeV2(...)` method and a lazy-init `WriteComputeSeedRmd` helper field.
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/MaterializingFoldContext.java`: +16 −6
  lines. Replaced V1 `applyWriteCompute` call in `foldOperands` and `foldOperandOnly` with V2 path; added monotonically
  increasing `modifyTs` counter per operand.

## Result

**Microbenchmark `WriteComputeArrayApplyMicrobenchmark` (PASS):**

| baseSize | V1 path (ms) | V2 path (ms) | ratio     |
| -------- | ------------ | ------------ | --------- |
| 10K      | 160.83       | 16.37        | 9.8×      |
| 50K      | 589.18       | 11.50        | 51.2×     |
| 100K     | 1128.56      | 18.60        | 60.7×     |
| 200K     | 2216.07      | 53.70        | 41.3×     |
| 380K     | 4128.73      | 93.38        | **44.2×** |

The 380K-float setUnion (the workload that times out the integration test) drops from ~4 seconds to ~93 ms. Against the
39-operand chain length in the test, server-side fold cost goes from ~160 s (timeout) to ~3.6 s — well under the
router's 10 s HTTP timeout.

**Unit tests:** `TestWriteComputeSeedRmd` 8/8 PASS. `TestWriteComputeProcessorV2` 4/4 PASS (V2 handler
regression-guard).

**Halt-trigger check (Phase 3):** Microbenchmark shows >40× speedup at 380K. Material improvement confirmed. Proceed to
Phase 4 (integration tests).

## Next step (iter-4)

Run §5.4.1 group integration tests — primarily `testActiveActivePartialUpdateWithCompression[NO_OP/GZIP/ZSTD_WITH_DICT]`
(the timeout target) plus baseline regression check on the 8 tests that previously passed post-H5.

Goal: 8/8 flag-on tests in §5.4.1 pass without regression to the 8 flag-off baseline runs.
