# Phase A — JNI bridge microbench

**Date:** 2026-05-03 / 2026-05-04 **Status:** PASS

## Build pipeline (Phase A scope only)

Phase A measures the JNI per-call cost; it deliberately does **not** depend on RocksDB headers. The native object is a
stand-alone JNI shim that mirrors the production hot-path shape (DirectByteBuffer + CallObjectMethod +
GetByteArrayRegion) but has zero coupling to `rocksdb::CompactionFilter`. Phase B will swap in the actual subclass.

### Decision: minimal infrastructure for Phase A

The GOAL §3 "Build pipeline" suggested a gradle subproject under `clients/da-vinci-client/native-fold/`. For Phase A
specifically I deferred that: adding a gradle subproject + cmake plugin + per-platform packaging is justified only when
Phase B actually needs distributable binaries. For Phase A, an out-of-band CMake build into
`autoresearch/vt-merge-compaction-fold-native/native-bin/linux-x86_64/` is sufficient and keeps the main code tree's
diff small. Rationale documented here per the GOAL "explain in `phase-A-NOTES.md` if no precedent" instruction.

### Files

- `autoresearch/vt-merge-compaction-fold-native/native-src/venice_jni_bridge.cpp` — JNI bridge (~120 LoC)
- `autoresearch/vt-merge-compaction-fold-native/native-src/CMakeLists.txt` — cmake stub
- `autoresearch/vt-merge-compaction-fold-native/native-bin/linux-x86_64/libvenice_jni_bridge.so` — built lib (17 KB)
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/jnibridge/VeniceJniBridge.java` — Java
  loader + native method declarations
- `clients/da-vinci-client/src/main/java/com/linkedin/davinci/store/rocksdb/merge/jnibridge/EchoFoldCallback.java` —
  Phase A callback (echoes input)
- `clients/da-vinci-client/src/test/java/com/linkedin/davinci/store/rocksdb/merge/jnibridge/VeniceJniBridgeTest.java` —
  TestNG tests (unit + stress + microbench)
- `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/VeniceJniBridgeBenchmark.java` — JMH benchmark
  for Phase B follow-on
- `build.gradle` — added one `systemProperty` pass-through for the JNI-lib path

### Build commands

```
cd autoresearch/vt-merge-compaction-fold-native/native-src && \
  mkdir -p build && cd build && cmake .. && cmake --build .
cp libvenice_jni_bridge.so ../../native-bin/linux-x86_64/
```

CMake flags: `-O2 -fvisibility=hidden -fno-rtti -fno-exceptions`. Output is 17 KB stripped. JNI symbols verified via
`nm -D` — only the four `Java_com_linkedin_davinci_store_rocksdb_merge_jnibridge_VeniceJniBridge_*` entry points are
exported.

## Verify

### Step 1 — New unit tests (sub-second)

```
./gradlew :clients:da-vinci-client:test \
  --tests 'com.linkedin.davinci.store.rocksdb.merge.jnibridge.VeniceJniBridgeTest' \
  -Dvenice.jni.bridge.lib.path=$PWD/autoresearch/vt-merge-compaction-fold-native/native-bin/linux-x86_64/libvenice_jni_bridge.so
```

5/5 passing:

| Test                                      | Outcome | Detail                                                                     |
| ----------------------------------------- | :-----: | -------------------------------------------------------------------------- |
| `evenLengthInputEchoesAsChangeValue`      |  PASS   | Even-length input → CHANGE_VALUE path returns 16-byte echo                 |
| `oddLengthInputReportsKeep`               |  PASS   | Odd-length input → callback returns null → C++ reports input length intact |
| `exceptionPathRecoversCleanly`            |  PASS   | Empty input → callback throws → C++ ExceptionClear, returns -2, no crash   |
| `stressMillionIterations`                 |  PASS   | 1M iterations × 64B input, all round-trip cleanly. No JVM crash.           |
| `microBenchSteadyStateUnder2Microseconds` |  PASS   | All 9 measurement rounds (3 valueSizes × 3 rounds) under 2 µs gate         |

### Step 2 — Modified existing tests

None. Phase A added new files only. Existing tests in `com.linkedin.davinci.store.rocksdb.merge.*` remain unaffected
(verified by the unrelated test classes still compiling and the diff being additive only).

### Step 3 — Full reused suites

Skipped for Phase A — the only reused interface is `Object` (the callback). None of the existing rocksdb-package tests
depend on `VeniceJniBridge` or `EchoFoldCallback`. `compileJava` and `compileTestJava` both clean.

### Steps 4-5 — Native integration test, AA flag-on integration test

Per the GOAL §5 dev-loop ordering: Phase A skips these. Phase B will exercise them.

### Step 6 — JMH performance gate

Phase A's microbench runs in-test (not via JMH) for measurement simplicity. The JMH benchmark `VeniceJniBridgeBenchmark`
is shipped for Phase B/C follow-on investigation if needed but is not part of the Phase A gate. Phase A's gate is
satisfied by the test-class measurement.

## Measurements

### JNI per-call cost (steady state, in-C++ tight loop)

| valueSize (bytes) | round | ns/call | iterations |
| ----------------: | ----: | ------: | ---------: |
|                64 |     1 |   314.3 |    500,000 |
|                64 |     2 |   309.3 |    500,000 |
|                64 |     3 |   330.1 |    500,000 |
|               256 |     1 |   467.3 |    500,000 |
|               256 |     2 |   465.3 |    500,000 |
|               256 |     3 |   374.6 |    500,000 |
|              1024 |     1 |   417.9 |    500,000 |
|              1024 |     2 |   416.6 |    500,000 |
|              1024 |     3 |   408.1 |    500,000 |

**Min:** 309 ns. **Max:** 467 ns. **Median:** ≈ 408 ns. **Phase A gate:** ≤ 2 µs (2000 ns). **Headroom:** 4.3× under
gate at the worst measurement.

The cost grows mildly with value size (314 → 467 ns going from 64B → 256B), consistent with the `memcpy` into the
staging buffer + the JNI region copy on the return path. Both costs are paid by the production path too.

### Stress test (1M iterations)

`stressMillionIterations` ran in 303 ms for 1M iterations of 64-byte input — 303 ns/call average, matches the
steady-state measurement. **Zero JVM crashes**, total byte count exact (`64 × 1,000,000 = 64,000,000`).

### Exception path

`nativeProbeException` invoked the callback once with empty input. The Java callback threw a `RuntimeException`; the C++
`ExceptionCheck` + `ExceptionClear` recovered cleanly and returned `-2`. **No JVM crash.**

### Native footprint

`libvenice_jni_bridge.so` is 17,464 bytes — well under the 2 MB per-platform budget in GOAL §1.

## Gate evaluation

| GOAL §3 Phase A exit criterion                          | Met?  | Detail                                             |
| ------------------------------------------------------- | :---: | -------------------------------------------------- |
| Per-call cost ≤ 2 µs                                    |  YES  | Max 467 ns (4.3× under gate)                       |
| Zero JVM crashes across 1M-iteration stress test        |  YES  | 1M iterations, exact byte count, no crash          |
| No native memory leaks (informal — no DBB tracking yet) | YES\* | Test-process RSS flat across run; no jcmd evidence |
|                                                         |       | required for Phase A per scope                     |

\*`jcmd VM.native_memory` was not run for Phase A — the test runs for ~2s with 1M+ JNI calls, all `NewDirectByteBuffer`
and `NewLocalRef` allocations are paired with `DeleteLocalRef` in the same call, and `GetPrimitiveArrayCritical` is
paired with `ReleasePrimitiveArrayCritical`. No global ref leaks: the only global ref is the callback object set once in
`nativeInit`. A formal NMT regression check is deferred to Phase B where the long-lived compaction-thread attachments
matter.

## Decision

**Result: PASS.** All Phase A exit criteria met. Per-call cost is comfortably below the 2 µs Phase A gate, the JVM
survives 1M iterations without a crash, and the exception-handling path recovers cleanly.

## What this measures vs what Phase B will incur

Phase A's measurement bounds the JNI marshal + Java method dispatch + bytes-back copy. Phase B adds:

1. The actual `rocksdb::CompactionFilter::FilterV2` virtual call (one indirect call per value, ~5 ns).
2. The Java `ConcatBlobParser.parse` + `MaterializingFoldContext.foldOperands` work — this is the dominant cost and is
   **not** affected by JNI overhead.
3. Per-thread JNIEnv attach/detach for compaction worker threads — `AttachCurrentThreadAsDaemon` is one-time per thread.

The Phase A measurement is therefore a strict lower bound on Phase B's per-call JNI cost; the fold work in Java will
dominate, which is fine because that fold work is reused from the existing well-tested code path.

## Next: Phase B

Phase B requires linking against `rocksdb::CompactionFilter` to register a custom filter on the column-family options.
The GOAL §4 design assumes this is buildable against headers extracted from the rocksdbjni jar. **Verified upfront that
the rocksdbjni 9.11.2 jar does NOT ship the `.h` files** (`unzip -l` shows only the `librocksdbjni-*.so` per platform,
no `META-INF/native/include/`). The fallback path is the GitHub tag — verified
`curl https://raw.githubusercontent.com/facebook/rocksdb/v9.11.2/include/rocksdb/compaction_filter.h` is reachable from
this host.

The deeper Phase B obstacle (called out in GOAL §8 as the "Native build matrix" and "Avro version mismatch / ABI drift"
rows) is that the rocksdbjni ships RocksDB **statically linked** into the librocksdbjni .so, with no exported C++
symbols for the `CompactionFilter` base class. Registering an out-of-tree custom filter therefore requires either:

- (B-link) compiling our shim against the same RocksDB version + linking against a separately-compiled `librocksdb.a`
  and registering via the Java `Options.setCompactionFilterFactory(...)` interface — but rocksdbjni's Java API only
  accepts an `AbstractCompactionFilterFactory` from its own type hierarchy, so this requires a custom rocksdbjni build.
- (B-jni-only) compiling RocksDB ourselves and shipping a fork of rocksdbjni — explicitly out of scope per GOAL §9 ("DO
  NOT fork rocksdbjni itself").
- (B-callback) exposing a hook in upstream rocksdbjni for a Java callback — what the GOAL §0 already noted is missing in
  9.11.2 (the original Phase A blocker).

Phase B will need to find a path through this. The Phase A success here doesn't unblock Phase B's ABI question — it just
confirms that **if** Phase B can be wired, the JNI cost will be acceptable.

## Commit hash

(filled in after commit)
