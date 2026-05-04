# VT-Merge Compaction-Time Fold via Native JNI Bridge (Path A2) — Final Results

**Branch:** `haoxu07/vt-rocksdb-merge-design` **Date:** 2026-05-04 **Outcome:** **PARTIAL PASS** — native filter fully
wired and correct; Phase C-1 throughput gate missed by ~28%; Phase C-2 conclusively shows the backstop is NOT redundant.

## Summary

The native JNI bridge path (Path A2) is **buildable, ABI-compatible with rocksdbjni 9.11.2 unchanged, correctness-clean
across all gates, and committed to source as a feature-flagged opt-in.** The performance gate (Phase C-1 iter-1 ≥ 100K
ops/s) was not met; the native filter delivered 72,848 ops/s iter-1, essentially unchanged from Phase D's 71,615 ops/s.
Phase C-2 (backstop disabled) showed the native filter alone delivers 144K ops/s iter-1 BUT chains grow unboundedly (p99
39 → 299 → 551 → 807), confirming the synchronous `ChainLengthBackstop` is essential.

## Phase outcomes

| Phase | Outcome | Headline                                                                                                                            |
| ----- | ------- | ----------------------------------------------------------------------------------------------------------------------------------- |
| A     | PASS    | JNI per-call cost 309–467 ns (4–6× under 2 µs gate). 1M-iteration stress test, no JVM crashes.                                      |
| B     | PASS    | Native CompactionFilter folds operand chains during real RocksDB compaction. Byte-equivalent to Java path across n ∈ {1..64}.       |
| C-1   | PARTIAL | Iter-1 throughput 72,848 ops/s vs 100K target. Chain p99 ≤ 47 (under 50 gate). VT-CHECK / READ-VERIFY clean. CV 4.9%.               |
| C-2   | INFORM. | Filter-only run hits 144K iter-1 BUT chain p99 grows monotonically. Backstop is NOT redundant; do not delete `ChainLengthBackstop`. |

## Headline numbers

| Metric                             | Phase D (no filter, prior work) | Phase A2 C-1 (filter ON, backstop ON) | Phase A2 C-2 (filter ON, backstop OFF) | GOAL §1 target |
| ---------------------------------- | ------------------------------: | ------------------------------------: | -------------------------------------: | -------------: |
| Iter-1 throughput (ops/s)          |                          71,615 |                                72,848 |                                144,047 |      ≥ 100,000 |
| Sustained throughput (3-iter mean) |                          67,338 |                                74,003 |                                142,194 |       ≥ 90,000 |
| Per-iter CV                        |                          10.49% |                                  4.9% |                                   3.7% |          < 15% |
| Chain p99 max across iters         |                              35 |                                    47 |                                    807 |           ≤ 50 |
| VT-CHECK                           |                           0/0/0 |                                 0/0/0 |                                  0/0/0 |          0/0/0 |
| READ-VERIFY                        |                       1000/1000 |                             1000/1000 |                              1000/1000 |      1000/1000 |
| Native binary size                 |                              NA |                                 25 KB |                                  25 KB |         ≤ 2 MB |

## Recommendation: keep ChainLengthBackstop

Phase C-2 was the experiment "is the backstop redundant when the native filter is in place?" The answer is a clear
**NO**:

- With the backstop disabled, chain p99 grew **monotonically** within a single 3-iter run (39 → 299 → 551 → 807).
- The native compaction filter runs on RocksDB's natural compaction schedule, which falls behind the merge rate under
  sustained load.
- Only the synchronous `ChainLengthBackstop` fires deterministically at MAX_CHAIN, providing the hard chain bound.

**Decision:** Do **NOT** delete `ChainLengthBackstop`. The native filter is a complementary mechanism, not a
replacement.

## Why the C-1 throughput target wasn't met

The pre-fix iter-1 baseline of 110K ops/s was measured with **unbounded chain growth** — chains hit p99 238 → 369 → 430
across iters. That throughput came at the cost of unbounded chain depth, which Phase B / D fixed via the synchronous
backstop.

With chains bounded to 64, the iter-1 throughput is structurally constrained by the per-MAX*CHAIN-th-merge fold-and-PUT
cost. The native filter relocates \_some* of that fold work to compaction threads, but it cannot eliminate the
synchronous backstop because the backstop is what prevents chain blow-up in the first place.

C-2 confirms this: when we disable the backstop and rely on the filter alone, throughput jumps to 144K (matching the
pre-fix unbounded baseline) but chain depth grows monotonically. There is no operating point with both bounded chains
AND > 100K throughput in this benchmark.

## Code / artifact summary

| Artifact                                                                                               | Phase | Lines / Bytes  |
| ------------------------------------------------------------------------------------------------------ | ----- | -------------- |
| `autoresearch/vt-merge-compaction-fold-native/native-src/venice_jni_bridge.cpp`                        | A     | ~150 LoC       |
| `autoresearch/vt-merge-compaction-fold-native/native-src/venice_rocksdb_fold.cpp`                      | B     | ~200 LoC       |
| `autoresearch/vt-merge-compaction-fold-native/native-src/CMakeLists.txt`                               | A+B   | ~70 LoC        |
| `autoresearch/vt-merge-compaction-fold-native/native-bin/linux-x86_64/libvenice_jni_bridge.so`         | A     | 17,784 bytes   |
| `autoresearch/vt-merge-compaction-fold-native/native-bin/linux-x86_64/libvenice_rocksdb_fold.so`       | B     | 25,328 bytes   |
| `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/jnibridge/*.java`      | A+B+C | ~600 LoC       |
| `clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/merge/jnibridge/*.java`      | A+B   | ~400 LoC       |
| `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/VeniceJniBridgeBenchmark.java` | A     | ~80 LoC        |
| `RocksDBStoragePartition.java` (wire site additions)                                                   | B     | +9 LoC         |
| `MaterializingFoldContextRegistry.java` (`firstRegistered`)                                            | C     | +13 LoC        |
| `build.gradle` (system-property pass-throughs for tests)                                               | A+B   | +9 LoC         |
| `phase-{A,B,C}-NOTES.md`                                                                               | doc   | ~250 LoC each  |
| `data/phase-{A,B,C}.tsv`                                                                               | data  | ~30 lines each |

The native binary footprint (43 KB combined) is **0.0022%** of the GOAL §1 budget of 2 MB per platform.

## ABI compatibility findings (key reference)

For future maintainers — the rocksdbjni 9.11.2 ABI traps we hit, in order:

1. **Missing `.h` files in rocksdbjni jar.** Verified via `unzip -l`. Fix: download from the rocksdb GitHub tag at
   `v9.11.2`. Cmake fetches at configure time.
2. **Undefined typeinfo `_ZTIN7rocksdb12CustomizableE`.** Fix: `-fno-rtti`. Vtable layout unaffected; virtual dispatch
   on `FilterV2` still works through the vptr.
3. **Pre-CXX11 libstdc++ ABI.** rocksdbjni's exported `Customizable::GetOption` uses legacy `RKSs` (`std::string`)
   mangling, not `NSt7__cxx11...`. Fix: `-D_GLIBCXX_USE_CXX11_ABI=0`.
4. **JVM `RTLD_LOCAL` symbol scope.** Even with all symbols correctly exported, the JVM's `System.load` hides them. Fix:
   `nativePromoteLibraryToGlobal(path)` JNI helper calls `dlopen(path, RTLD_NOLOAD | RTLD_GLOBAL)` to upgrade the
   existing rocksdbjni handle.
5. **`setCompactionFilterFactory` SIGABRT.** rocksdbjni wraps the factory in `std::shared_ptr<CompactionFilterFactory>`;
   the deleter crosses the library boundary unsafely. Fix: use `setCompactionFilter` directly with a raw
   `CompactionFilter*` we own from Java.

## Commit hashes

- `0933e3a74` — `[chore][bench][dvc] Native A2 Phase A: JNI bridge round-trip <500 ns`
- `bd50452f6` — `[chore][bench][dvc] Native A2 Phase B: native CompactionFilter folds operand chains`
- (Phase C: see commit at end of this work-stream)

## Follow-up work (out of scope for this work-stream)

1. **MAX_CHAIN tuning.** With the native filter complementing the backstop, raising MAX_CHAIN from 64 to e.g. 128 or 200
   may let the filter pre-empt the backstop more often, recovering some iter-1 throughput. Phase C used the default 64.
2. **Periodic `compactRange` trigger.** A bounded async `compactRange()` every N merges might let the native filter
   clean up chains before the backstop fires. Could close the C-1 throughput gap.
3. **Backstop optimization.** Profile whether the synchronous fold-and-PUT cost is dominated by the read-back, the Avro
   deserialize, or the put. The read-back could be elided when the filter has recently folded the same key.
4. **Multi-store dispatch.** The current native callback uses `MaterializingFoldContextRegistry.firstRegistered()` —
   fine for single-store-per-JVM (benchmarks, da-vinci-client embedded) but needs a per-key dispatch layer for
   multi-store production.
5. **Phase C-1 retry with raised MAX_CHAIN.** The simplest experiment: rerun Phase C-1 with
   `-Dvenice.server.vt.merge.max.chain.length=200`. Expected outcome is iter-1 in the 100K–120K range.

## Execution notes for future agents

- The native build needs `cmake`, `g++` 11.x (or compatible), and network access to GitHub for the rocksdb v9.11.2
  source archive. The headers (1.8 MB) are fetched at cmake configure time and not committed to git.
- The pre-commit hook (spotlessApply) ran cleanly throughout. No artifactory issues.
- The autoresearch directory is the source of truth: GOAL.md, phase-{A,B,C}-NOTES.md, RESULTS.md,
  data/phase-{A,B,C}.tsv. Native binaries committed to `native-bin/linux-x86_64/` for reproducibility.
- The forensics files at the repo root (`docs/contributing/proposals/per-request-forensics.md`, etc.) were left
  untouched per the task brief.
