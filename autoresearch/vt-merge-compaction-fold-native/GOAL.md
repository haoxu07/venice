# VT-Merge Compaction-Time Fold via Native JNI Bridge (Path A2) — Goal Document

**Owner:** xhao@linkedin.com **Drafted:** 2026-05-04 **Branch:** `haoxu07/vt-rocksdb-merge-design` (continuation;
consider branching to `haoxu07/vt-merge-fold-native` if the native-build pipeline forces a separate review cycle)
**Execution model:** Phased, autonomous agent. Three phases. Each phase ends with measurable gate. **Estimated effort:**
3–4 weeks engineering + 1–2 days benchmark cycles. Larger surface than prior phased work because the build pipeline
includes native binaries.

---

## 0. Problem statement

The completed `vt-merge-compaction-fold` project (commits `cfc112293..1f6a4a74a`) hit Phase A's intended chain-bound
goal via the synchronous Phase B backstop, but at the cost of iter-1 throughput dropping from pre-fix 110K ops/s to 72K
ops/s. The headline metrics:

| Metric                                    | Pre-fix iter-1 (unbounded chain) | Phase D today | Original Phase A target |
| ----------------------------------------- | -------------------------------: | ------------: | ----------------------: |
| Iter-1 throughput                         |                       110K ops/s |     72K ops/s |                  ≥ 100K |
| Sustained throughput (180s × 3 iter mean) |                collapsed to ~70K |           67K |                   ≥ 90K |
| Chain p99 across iters                    |      unbounded (236 → 369 → 430) | strictly ≤ 64 |                   ≤ 100 |

Phase A as originally specified — a Java `AbstractCompactionFilter<Slice>` that folds operand chains during RocksDB
compaction — was blocked because `rocksdbjni 9.11.2` exposes `AbstractCompactionFilter` as a thin native-handle wrapper
with no overridable Java callback. The no-C++ constraint excluded the only available path.

This work-stream relaxes the no-C++ constraint to the **minimum extent needed** — a small C++ shim (Path A2 from the
design discussion) — to recover the throughput gap left by Phase B's synchronous backstop.

## 1. Goal

Implement a compaction-time fold using a thin C++ filter that bridges back into Java via JNI for the actual fold logic.
Reuse the existing well-tested `MaterializingFoldContext.foldOperands()` Java code; do **not** port Avro write-compute
to C++. Verify with the same correctness + performance gates that Phase D met:

1. **Iter-1 throughput ≥ 100K ops/s** (within 10% of pre-fix's 110K — recovers the gap from Phase D's 72K)
2. **Sustained throughput ≥ 90K ops/s** across 3 × 180s iters (CV < 15%)
3. **Chain-length p99 ≤ 50** (tighter than Phase D's 64 because the filter folds in background, not just at MAX_CHAIN)
4. **Correctness preserved**: VT-CHECK 0/0/0, READ-VERIFY 1000/1000, byte-equivalence between Phase D and Phase A2
   stored values (modulo legitimate fold differences)
5. **Net code reduction or no growth in JNI surface**: native binary should be ≤ 2 MB per platform; Java surface ≤ 200
   LOC

## 2. Scope

### In scope

- New native library `libvenice_rocksdb_fold.so` (linux-x86_64, linux-aarch64) and `.dylib` (macos-arm64). Other
  platforms only if LinkedIn CI requires.
- Custom C++ `CompactionFilterFactory` and `CompactionFilter` implementations bridging to Java via JNI.
- Java callback class `VeniceConcatFoldNativeCallback` with `byte[] foldConcatBlob(byte[] input)` (or DirectByteBuffer
  equivalent).
- Build pipeline: gradle native-build subproject; per-platform binary inclusion in the published artifact.
- Wiring in `RocksDBStoragePartition` to register the native filter factory when the existing MERGE_OPERAND_SWEPT flag
  is enabled.
- Decision: keep or remove the synchronous Phase B `ChainLengthBackstop` — see §3 Phase C.
- New unit tests for the JNI bridge; reuse all Phase D / Phase B integration tests.

### Out of scope

- Porting `WriteComputeProcessor` or `MaterializingFoldContext` to C++ (Path A1 — explicitly rejected).
- Forking `rocksdbjni` itself (Path A3 — viable but out of scope here).
- Replacing `StringAppendOperator` (filter runs AFTER concat; concat is preserved).
- Changing the on-disk wire format. `ConcatBlobParser`'s `KIND_BASE`/`KIND_OPERAND` framing is unchanged.
- Changing the leader-side write contract.
- Read-side optimizations (caching, write-back).

## 3. Phased plan

Three phases, each ending with a measurable gate. Halt on the first failed exit; agent writes `BLOCKED-NOTES.md` and
stops.

### Phase A — JNI bridge microbenchmark (risk reduction)

**Why first:** the JNI per-call cost is the single most uncertain number in the project. Resolve it before committing to
the larger build.

**Build:**

- Minimal C++ filter that does no fold work: receives a value, calls back into Java via `CallObjectMethod`, Java echoes
  the input bytes, C++ writes output unchanged.
- Use DirectByteBuffer for argument passing (zero-copy path).
- Pre-allocate per-thread buffers in Java to avoid per-call allocation.
- Build for one platform only (linux-x86_64) to bound build-pipeline scope here.

**Verify:**

- JMH microbench: invoke the bridge in a tight loop, measure per-call ns.
- Profile with JFR; verify GC churn from the bridge is below 1% young-gen.
- Check exception path: trigger a Java exception, verify C++ side handles it without JVM crash.

**Exit:**

- Per-call cost ≤ 2 µs (provides margin over 1 µs estimate).
- Zero JVM crashes across 1M-iteration stress test.
- No native memory leaks (`jcmd <pid> VM.native_memory` flat across the test).

### Phase B — Working filter with correctness gates

**Build:**

- Real C++ filter that, on each compaction-output value:
  1. Pass value to Java via DirectByteBuffer
  2. Java callback: `ConcatBlobParser.parse` → if no operands return `KEEP`; else
     `MaterializingFoldContext.foldOperands` → `frameBase` → return reframed bytes (or sentinel for `KEEP`)
  3. C++ writes the result via `CHANGE_VALUE` or `KEEP`
- Java callback class `VeniceConcatFoldNativeCallback` registered per store-version in a registry mirroring
  `MaterializingFoldContextRegistry`.
- Build pipeline: linux-x86_64 + linux-aarch64 + macos-arm64 (the LinkedIn CI matrix). Skip windows unless requested.
- Wire into `RocksDBStoragePartition` at the same merge-operator setup site (lines ~274 and ~473 in current HEAD),
  guarded by a new flag `vt.merge.native.compaction.filter.enabled` (default false; Phase B exit flips to true).

**Verify:**

- New unit tests on the Java callback directly (handles already-folded blobs, base+operands, operand-only, malformed,
  fold failure).
- Reuse all Phase B/D unit tests (`ChainLengthBackstopTest`, `MaterializingPartitionSmokeTest`, `ConcatBlobParserTest`,
  `RocksDbMergeReproTest`).
- Reuse `TestPartialUpdateWithActiveActiveReplication` integration test with the existing flag.
- New integration test: `NativeCompactionFoldRoundTripTest` — write base + N operands, force `compactRange()`, read raw
  blob, assert `KIND_BASE` framed with no operand suffix.
- New byte-equivalence test: same workload, run with and without native filter; assert that materialized read-path
  output is byte-identical (operand chains differ in length, but `MaterializingFraming.materialize()` results must
  match).
- Smoke run: JMH MERGE_OPERAND_SWEPT `-i 1 -r 30s -wi 1 -w 10s`. Native filter ON. `READ-VERIFY` 1000/1000.

**Exit:**

- All unit + integration tests green.
- Byte-equivalence test green.
- Smoke run completes; `READ-VERIFY` 1000/1000.
- Zero JVM crashes; native memory flat.

### Phase C — Performance tuning + backstop decision

**Build:** none initially. Configuration / tuning experiments only.

**Verify:**

- Run JMH MERGE_OPERAND_SWEPT `-i 3 -r 180s -wi 1 -w 30s` with native filter ON.
- Capture: JMH score per iter, chain-length p99 per iter, JNI overhead via JFR profile, RocksDB cfstats per iter.
- If chain p99 stays ≤ 25 across all iters with the synchronous backstop _also_ enabled, repeat with backstop disabled
  (set `vt.merge.max.chain.length=0` or new disable flag).
- If chain p99 stays bounded with backstop disabled, the backstop is redundant — Phase D revisited follow-up: delete
  `ChainLengthBackstop` similarly to how Phase D deleted `PartitionSweeper`.

**Exit:**

- **Phase C-1 (filter on, backstop on):** iter-1 ≥ 100K, sustained ≥ 90K, CV < 15%, chain p99 ≤ 50.
- **Phase C-2 (filter on, backstop off; only if C-1 passes):** iter-1 ≥ 105K, sustained ≥ 95K, chain p99 ≤ 50. If passes
  → recommend backstop deletion in `RESULTS.md`.

If either C-1 or C-2 fails, halt and write `BLOCKED-NOTES.md`. PARTIAL is acceptable; FAILED requires escalation.

## 4. Concrete design — sketches

### C++ shim (header)

```cpp
// venice_rocksdb_fold.h
#pragma once
#include <jni.h>
#include "rocksdb/compaction_filter.h"

namespace venice {

class VeniceConcatFoldFilter : public rocksdb::CompactionFilter {
 public:
  VeniceConcatFoldFilter(JavaVM* jvm, jobject javaCallback, jmethodID foldMethodId);
  ~VeniceConcatFoldFilter() override;

  Decision FilterV2(int level, const rocksdb::Slice& key,
                    ValueType value_type, const rocksdb::Slice& existing_value,
                    std::string* new_value, std::string* skip_until) const override;

  const char* Name() const override { return "VeniceConcatFoldFilter"; }

 private:
  JavaVM* jvm_;
  jobject java_callback_;        // global ref to the Java VeniceConcatFoldNativeCallback
  jmethodID fold_method_id_;     // cached method ID
};

class VeniceConcatFoldFilterFactory : public rocksdb::CompactionFilterFactory {
 public:
  VeniceConcatFoldFilterFactory(JavaVM* jvm, jobject javaCallback);
  std::unique_ptr<rocksdb::CompactionFilter> CreateCompactionFilter(
      const rocksdb::CompactionFilter::Context& context) override;
  const char* Name() const override { return "VeniceConcatFoldFilterFactory"; }

 private:
  JavaVM* jvm_;
  jobject java_callback_;
  jmethodID fold_method_id_;
};

} // namespace venice
```

### Java callback (sketch)

```java
public final class VeniceConcatFoldNativeCallback {

  private final MaterializingFoldContext foldContext;

  public VeniceConcatFoldNativeCallback(MaterializingFoldContext foldContext) {
    this.foldContext = foldContext;
  }

  /**
   * Called from native code via JNI. Input: a concat blob from a compaction-output value.
   * Returns: folded base bytes, OR null to signal KEEP (no change).
   */
  public byte[] foldConcatBlob(ByteBuffer inputView) {
    // Read inputView into a byte[] (or work zero-copy via inputView)
    byte[] input = readToArray(inputView);
    ConcatBlobParser.Parsed parsed = ConcatBlobParser.parse(input);
    if (parsed.getOperands().isEmpty()) {
      return null; // KEEP
    }
    if (parsed.hasBase()) {
      byte[] foldedAvro = foldContext.foldOperands(parsed.getSchemaId(), parsed.getBase(), parsed.getOperands());
      if (foldedAvro == null) return null; // tombstone — KEEP for now
      return ConcatBlobParser.frameBase(parsed.getSchemaId(), foldedAvro);
    } else {
      MaterializingFoldContext.FoldOnlyResult result = foldContext.foldOperandOnly(parsed.getOperands());
      if (result.getBytes() == null) return null;
      return ConcatBlobParser.frameBase(result.getSchemaId(), result.getBytes());
    }
  }
}

```

### C++ → Java bridge in `FilterV2`

```cpp
Decision VeniceConcatFoldFilter::FilterV2(
    int level, const rocksdb::Slice& key,
    ValueType value_type, const rocksdb::Slice& existing_value,
    std::string* new_value, std::string* skip_until) const {

  JNIEnv* env = nullptr;
  if (jvm_->AttachCurrentThreadAsDaemon((void**)&env, nullptr) != JNI_OK) {
    return Decision::kKeep;
  }

  // Wrap existing_value as a DirectByteBuffer (zero-copy)
  jobject input_buf = env->NewDirectByteBuffer(
      const_cast<char*>(existing_value.data()), existing_value.size());

  jbyteArray result = (jbyteArray) env->CallObjectMethod(
      java_callback_, fold_method_id_, input_buf);
  env->DeleteLocalRef(input_buf);

  if (env->ExceptionCheck()) {
    env->ExceptionDescribe();
    env->ExceptionClear();
    return Decision::kKeep;
  }

  if (result == nullptr) {
    return Decision::kKeep;
  }

  jsize len = env->GetArrayLength(result);
  new_value->assign(len, 0);
  env->GetByteArrayRegion(result, 0, len, reinterpret_cast<jbyte*>(&(*new_value)[0]));
  env->DeleteLocalRef(result);
  return Decision::kChangeValue;
}
```

The hot-path optimization (Phase A microbenchmark target) is to switch the result from `byte[]` to a pre-allocated
DirectByteBuffer that Java writes into in place — saves the `GetByteArrayRegion` copy. Defer this until the basic
version works.

### Build pipeline

- New gradle subproject `clients/da-vinci-client/native-fold/` with `CMakeLists.txt`.
- Per-platform builds via `nativeBuild` task in the subproject.
- Output: `libvenice_rocksdb_fold-{platform}.so` files bundled into a separate jar
  `venice-rocksdb-fold-native-{version}.jar`.
- Java side loads via `System.loadLibrary("venice_rocksdb_fold")` — extracted from jar to temp dir at startup, same
  pattern rocksdbjni uses.
- Header dependency: RocksDB headers from the same `org.rocksdb:rocksdbjni:9.11.2` Maven artifact (extract `.h` files at
  build time) so we link against the same C++ ABI as the Maven-shipped binary.

## 5. Test gates per phase

### Reused test assets (do not rewrite)

Same as `vt-merge-compaction-fold/GOAL.md` §5 reused-assets table — `ConcatBlobParserTest`,
`MaterializingPartitionSmokeTest`, `RocksDbMergeReproTest`, partition/engine tests,
`TestPartialUpdateWithActiveActiveReplication`, lean smoke tests, `LeanActiveActiveIngestionBenchmark`. Plus the Phase B
unit tests that landed in `vt-merge-compaction-fold` (`ChainLengthBackstopTest` etc.) — these must continue passing with
the native filter installed.

### New tests per phase

| Phase | New test                                                                              | What it covers                                                                                                                   | Gate                                         |
| ----- | ------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------- | -------------------------------------------- |
| A     | JNI bridge microbench (`VeniceJniBridgeBenchTest`)                                    | Per-call ns; allocation rate; exception handling                                                                                 | ≤ 2 µs/call; no JVM crashes in 1M iterations |
| B     | `VeniceConcatFoldNativeCallbackTest` (Java unit)                                      | Java fold logic exercised directly via the callback class (5 cases mirroring `MaterializingPartitionSmokeTest` integration test) | All 5 cases pass                             |
| B     | `NativeCompactionFoldRoundTripTest` (Java integration with real RocksDB + native lib) | Open RocksDB with native filter; write base + operands; `compactRange()`; assert `KIND_BASE` blob with no operand suffix         | Round-trip works                             |
| B     | `NativeFilterByteEquivalenceTest`                                                     | Same workload run with native filter ON vs OFF (Phase D); compare `MaterializingFraming.materialize()` output across regions     | Byte-equivalent reads                        |
| C     | (none)                                                                                | JMH performance gate                                                                                                             | C-1 / C-2 thresholds in §3                   |

### Dev-loop ordering

1. New unit tests (sub-second)
2. Modified existing unit tests
3. Full reused unit suites (`./gradlew :clients:da-vinci-client:test`)
4. Native integration tests (`./gradlew :clients:da-vinci-client:integrationTest --tests *Native*`)
5. `TestPartialUpdateWithActiveActiveReplication` (full AA flag-on)
6. JMH performance gate

## 6. Verification logistics

- Per-phase TSV at `autoresearch/vt-merge-compaction-fold-native/data/phase-{A,B,C}.tsv`.
- Per-phase NOTES at `autoresearch/vt-merge-compaction-fold-native/phase-{A,B,C}-NOTES.md`.
- Final RESULTS.md at end of Phase C.
- Native binaries committed to a separate `native-bin/` subdirectory, NOT in main code tree (to keep the Java diff
  reviewable).

## 7. Decision criteria

A YES on overall goal requires all of:

- ✅ JNI bridge per-call cost ≤ 2 µs (Phase A measured)
- ✅ All correctness gates pass: VT-CHECK 0/0/0, READ-VERIFY 1000/1000, byte-equivalence with Phase D
- ✅ Iter-1 throughput ≥ 100K ops/s (Phase C-1)
- ✅ Sustained throughput ≥ 90K ops/s with CV < 15% (Phase C-1)
- ✅ Chain-length p99 ≤ 50 across all iters

Phase C-2 (backstop removal) is a NICE-TO-HAVE; not required for the overall YES.

## 8. Risks

| Risk                                                                                                       | How we'd see it                                                   | Response                                                                                                                                                               |
| ---------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| JNI per-call cost > 2 µs                                                                                   | Phase A microbench fails the 2 µs gate                            | Profile to find dominant cost; optimize buffer reuse; if still > 2 µs, reconsider going to A1 (pure C++) — halt and escalate                                           |
| Native build matrix complex                                                                                | LinkedIn CI doesn't have aarch64 builders, or build times balloon | Start with linux-x86_64 only for Phase A/B; add platforms in Phase C if not blocked                                                                                    |
| Avro version mismatch / ABI drift                                                                          | Native lib breaks when rocksdbjni upgrades minor versions         | Pin rocksdbjni version in build; add CI check on rocksdbjni bumps                                                                                                      |
| JNI thread attachment overhead per compaction thread                                                       | First-call latency spikes; throughput jitter                      | Use `AttachCurrentThreadAsDaemon`; verify subsequent calls are free via JFR                                                                                            |
| Native memory leak (DirectByteBuffer or global refs)                                                       | Heap stable but RSS grows unboundedly                             | Use `jcmd VM.native_memory` regression test in Phase A; release `jobject` refs explicitly                                                                              |
| JVM crash in C++ on malformed blob                                                                         | Crash log; SEGV                                                   | Defensive checks before JNI calls; wrap entire filter body in try/catch in Java; C++ catches `std::exception`                                                          |
| Byte-equivalence fails because compaction filter rewrites differently than Phase D's read-path materialize | `NativeFilterByteEquivalenceTest` red                             | Investigate divergence; could be schema-evolution edge case or operand-fold ordering — likely an MaterializingFoldContext bug shared with Phase D, not native-specific |
| Phase C iter-1 throughput stays below 100K                                                                 | Performance gate red                                              | Profile to find new bottleneck; possibly compaction IO-bound rather than CPU-bound; document in RESULTS.md and accept lower target                                     |
| Decision gate too strict (90K sustained)                                                                   | Phase C-1 marginal pass                                           | Document and propose follow-up (compaction tuning, bigger memtable)                                                                                                    |

## 9. Non-goals

- **Not** porting WriteCompute to C++ — that's path A1, explicitly rejected here.
- **Not** forking rocksdbjni — that's path A3, deferred.
- **Not** changing on-disk wire format.
- **Not** changing the leader-side write contract.
- **Not** introducing native code beyond the compaction filter (no native C++ for read path, no native partition logic).
- **Not** retiring the synchronous Phase B backstop in this work-stream unless Phase C-2 explicitly proves it's
  redundant.
- **Not** windows-platform support unless LinkedIn CI explicitly requires it.
