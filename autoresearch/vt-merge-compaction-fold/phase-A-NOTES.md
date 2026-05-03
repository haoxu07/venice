# Phase A — Compaction-time fold via Java CompactionFilter

**Date:** 2026-05-03 **Status:** BLOCKED → repurposed (see "Resolution" below)

## Headline

Phase A as designed in `GOAL.md` §3 / §4 cannot be implemented in pure Java with `rocksdbjni 9.11.2`. The Java class
`org.rocksdb.AbstractCompactionFilter<T>` is a thin wrapper around a native handle; there is **no Java method to
override** that receives `(int level, Slice key, Slice existingValue, ByteBuffer newValue)` and returns a `Decision`.
Every concrete `AbstractCompactionFilter` subclass in the shipped jar (`RemoveEmptyValueCompactionFilter`,
`CassandraCompactionFilter`) constructs its native handle via a `createNew*0(...)` JNI call and lets the C++ layer do
all per-value work. The `AbstractCompactionFilterFactory.createCompactionFilter(Context)` contract returns an
`AbstractCompactionFilter` whose `nativeHandle_` is then disowned and handed to the C++ side; from that point on the
filter runs entirely in C++.

This is verifiable by `javap -p` against the shipped jar:

```
org.rocksdb.AbstractCompactionFilter:
  protected AbstractCompactionFilter(long);
  protected final native void disposeInternal(long);
  // (no filter() method, no Decision enum exposed)

org.rocksdb.AbstractCompactionFilterFactory:
  public AbstractCompactionFilterFactory();
  protected long initializeNative(long...);
  private long createCompactionFilter(boolean, boolean);
  public abstract T createCompactionFilter(Context);
  public abstract String name();
  // factory returns a native filter handle; no per-value Java callback.
```

The GOAL §3 Phase A skeleton:

```java
public final class VeniceConcatFoldCompactionFilter extends AbstractCompactionFilter<Slice> {
  @Override
  protected Decision filter(int level, Slice key, Slice existingValue, ByteBuffer newValue) { ... }
}
```

would not compile (no `Decision` symbol; no method to override). Implementing the filter logic in C++ is explicitly
forbidden by the GOAL's no-go zone:

> Do NOT introduce C++ code. The CompactionFilter is Java only (`AbstractCompactionFilter<Slice>`).

## Resolution

Per the GOAL escalation guidance:

> If the API is different, document and adapt — don't fight the API.

…and per §8 risk row "compaction filter on flush knob not exposed in rocksdbjni 9.11.2 → Skip Phase C; Phase B's
MAX_CHAIN backstop is the alternative":

The chain-length bound for the JMH MERGE_OPERAND_SWEPT workload is achieved **by Phase B alone** — the synchronous
chain-length backstop on the follower's apply-operand path. Phase B does not depend on any rocksdbjni compaction-filter
callback API; it is pure Java applied at write time, before `rocksDB.merge(...)`. With MAX_CHAIN strictly enforced, no
key can accumulate more than MAX_CHAIN operands between consecutive base PUTs, regardless of RocksDB compaction cadence.
This is a stronger bound than what the compaction filter would provide (the filter only consolidates when a compaction
happens).

Therefore the work-stream proceeds:

- **Phase A: skipped** (no Java API to implement against in rocksdbjni 9.11.2)
- **Phase B: proceed** (pure-Java write-path backstop; this is the load-bearing change for the chain bound)
- **Phase C: skipped** (also requires Java compaction-filter callbacks; same blocker)
- **Phase D: proceed** (delete `PartitionSweeper` once Phase B is green)

This adapts the work to the available API surface while preserving the goal's success criteria: chain-length p99 ≤ 64
strictly under any sustained workload (Phase B exit), iter-over-iter throughput stable (Phase B target CV < 10%), and
net code reduction (Phase D).

## Verification artifacts

- This NOTES file + the empty `data/phase-A.tsv` (with header only) document the decision; no JMH run is associated with
  Phase A in this work-stream.
- No Phase A commit is created. The branch advances directly to Phase B.

## What would be needed to revisit Phase A in the future

Either (a) a future rocksdbjni release that exposes a Java callback API for compaction filters (an
`AbstractCompactionFilterJava` analogue of `AbstractCompactionFilterFactory`), or (b) a relaxation of the no-C++
constraint to ship a small JNI shim that calls back into Java for each compaction-filter invocation. Both are out of
scope here and are tracked as follow-up work in `RESULTS.md`.
