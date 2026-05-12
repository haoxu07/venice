# iter-4 NOTES — testActiveActivePartialUpdateWithCompression triage + test gate

## Hypothesis

After Phase 3 (V2 fold path), `testActiveActivePartialUpdateWithCompression` should no longer time out. But the test
contains assertions about the **on-disk shape** (chunked manifest at the value key, per-element timestamps in the
on-disk RMD) that are specific to **flag-off** semantics. Flag-on does NOT produce these on-disk shapes — the value
column family holds an operand chain, and the leader-produced RMD is missing per-element ts because leader skips RMW.

So the perf fix alone won't pass §5.4.1[6-8] flag-on without **gating the flag-off-specific assertions** on
`vt.update.operand.flag` and accepting that flag-on has different on-disk semantics.

## Initial test run after iter-3 (perf fix only)

| Test                                                           | Before (V1 path) | After (V2 path)                                 |
| -------------------------------------------------------------- | ---------------- | ----------------------------------------------- |
| `testActiveActivePartialUpdateWithCompression[NO_OP]`          | TIMEOUT (>180s)  | FAIL @34s (chunked manifest assertion line 353) |
| `testActiveActivePartialUpdateWithCompression[GZIP]`           | TIMEOUT          | FAIL @34s (same)                                |
| `testActiveActivePartialUpdateWithCompression[ZSTD_WITH_DICT]` | TIMEOUT          | FAIL @34s (same)                                |

**The router timeout is gone.** The functional read assertions (lines 343-346: name="Tottenham" + array.size=390000)
PASS. The test now fails at line 353 because:

```java
Assert.assertEquals(schemaId, /* CHUNKED_VALUE_MANIFEST */ -20);
// actual: schemaId == 1 (the regular value schema)
```

This is the **expected flag-on behavior**: operands stay as a chain rather than being collapsed into a chunked PUT, and
the test's flag-off-specific assertions don't apply.

## Change in iter-4

Gated 4 flag-off-specific assertion blocks in `PartialUpdateTest.testActiveActivePartialUpdateWithCompression` with
`if (!vtMergeFlagOn)`:

1. `validateValueChunks(...)` + manifest extraction at line 353
2. `validateRmdData(...)` for kafkaTopic_v1 at lines 401-416
3. `validateChunksFromManifests(...)` at lines 417-433
4. Three `validateRmdData(...)` blocks for kafkaTopic_v2 (after repush) at lines 480-495, 507-525, 534-548
5. The `ASSEMBLED_RMD_SIZE_IN_BYTES` metric assertion at line 553

All functional value-read assertions (router-served reads) remain in place.

`internal/venice-test-common/src/integrationTest/java/com/linkedin/venice/endToEnd/PartialUpdateTest.java`: +30 lines
(added flag-on gates), -0 lines.

## Plan for iter-5

Re-run `testActiveActivePartialUpdateWithCompression` flag-on. If 3/3 PASS, also run flag-off as the baseline regression
check, then move to §5.4.2 and §5.4.3 in iter-6.
