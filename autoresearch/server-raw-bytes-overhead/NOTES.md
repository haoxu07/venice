# Server-Side Fold Cost: `engine.get` vs `getRaw` Comparison

**Date:** 2026-05-09 **Branch:** `haoxu07/vt-rocksdb-merge-design` **Bench change:** Added
`captureRawReadLatencyMetrics(String context)` companion to the existing read-latency probe — times
`partition.getRaw(byte[])` instead of `engine.get(partition, key)`. The delta is the engine-side fold cost (Avro
decode + WC apply + Avro encode) that the server pays today and would shift to the client/router under the proposed
read-path change.

---

## Headline

At production-scale records (100 KB record / 1 KB operand / chain depth ~50), the server-side fold accounts for **~91%
of MERGE's read latency**. Returning raw bytes from RocksDB (no fold) is **~10× faster** on the server.

|       Iter | Chain p99 | `engine.get` p50 (with fold) | `getRaw` p50 (no fold) | Server fold cost (Δ) | % of latency from fold |
| ---------: | --------: | ---------------------------: | ---------------------: | -------------------: | ---------------------: |
| 1 (warmup) |         3 |                       164 µs |                  35 µs |               129 µs |                    79% |
| 2 (meas 1) |        22 |                       597 µs |                 110 µs |               487 µs |                    82% |
| 3 (meas 2) |        36 |                       897 µs |                 172 µs |               725 µs |                    81% |
| 4 (meas 3) |        53 |                     1,129 µs |                 101 µs |             1,028 µs |                **91%** |
|  trial-end |     (~53) |                     1,116 µs |                 101 µs |             1,015 µs |                **91%** |

VT-CHECK 0/0/0; READ-VERIFY 1000/1000.

## Two distinct scaling regimes

| Path                     | Scales with chain depth? | Approximate model                                                                    |
| ------------------------ | ------------------------ | ------------------------------------------------------------------------------------ |
| `engine.get` (with fold) | **Yes, linearly**        | ~70 µs + 16 µs × chain_depth                                                         |
| `getRaw` (no fold)       | **Roughly flat**         | ~100 µs at 100 KB record (mostly RocksDB get + StringAppendOperator concat + memcpy) |

The `engine.get` cost grows ~16× faster with chain depth than `getRaw`. At chain depth 53, **fold work is ~10× the
entire raw-bytes-return cost**.

## What the server actually does in each path

| Step                                           | `engine.get` | `getRaw` |
| ---------------------------------------------- | ------------ | -------- |
| RocksDB LSM walk                               | ✓            | ✓        |
| `StringAppendOperator.FullMerge` (byte concat) | ✓            | ✓        |
| `ConcatBlobParser.parse` (varint walk)         | ✓            | ✗        |
| Avro decode of base                            | ✓            | ✗        |
| Avro decode of N operands                      | ✓            | ✗        |
| `WriteComputeProcessor.applyWriteCompute` × N  | ✓            | ✗        |
| Avro encode of materialized record             | ✓            | ✗        |
| `ConcatBlobParser.frameBase`                   | ✓            | ✗        |

`getRaw` is everything RocksDB does + memcpy bytes out. Everything else (the fold pipeline) is what shifts to the
client/router under the proposal.

## Cross-record-size summary

| Workload                   | engine.get p50 | getRaw p50 | Server fold cost saved |
| -------------------------- | -------------: | ---------: | ---------------------: |
| 1.6 KB / chain ~30 (smoke) |          78 µs |       8 µs |           ~70 µs (90%) |
| 100 KB / chain ~22         |         597 µs |     110 µs |           487 µs (82%) |
| 100 KB / chain ~53         |       1,129 µs |     101 µs |         1,028 µs (91%) |

**The bigger the record AND the deeper the chain, the more dramatic the server-side saving.** This is exactly the regime
where MERGE's value matters most for production.

## Implication for the read-path proposal

If MERGE adopts server-side raw-bytes return (server skips fold; client/router does it):

- **Server read CPU drops to BASELINE-equivalent** (~100 µs vs BASELINE's ~70 µs at 100 KB)
- **Per-server read throughput could increase ~10×** at deep chains (Little's Law: throughput = concurrency / latency,
  with latency dropping ~10×)
- **The fold work doesn't disappear** — it lands on whichever layer (router or end client) does the fold next
- **MERGE's other wins survive** (40× write throughput, 100× VT bandwidth, ~30% storage savings)
- **MERGE's read-CPU cost equalizes with BASELINE on the server** while preserving all write-side benefits

This is the empirical foundation for the proposed design study (`autoresearch/server-raw-bytes-overhead/` is the data;
the study itself is a separate GOAL doc TBD).

## Run logs

- Smoke (small record): `/tmp/jmh-rawread-smoke.log`
- 100 KB measurement: `/tmp/jmh-rawread-100kb.log`

(Not committed; reproducible from current branch HEAD with the `[RAW-READ-LAT]` log lines emitted by the bench.)

## Bench change

Single addition to `LeanActiveActiveIngestionBenchmark.java`:

- New private method `captureRawReadLatencyMetrics(String context)` mirroring `captureReadLatencyMetrics` but invoking
  `partition.getRaw(byte[])` via reflection (same pattern as the operand-count sampler).
- Wired into the existing iter-end and trial-end probe sites — both `[READ-LAT]` (with fold) and `[RAW-READ-LAT]` (no
  fold) emit per-iter and at trial-end.
- Skipped for `BASELINE` mode (no fold to bypass; comparison would be a no-op).
- No production code change. Workload + correctness behavior unchanged.

## Why this isn't a sufficient answer on its own

The numbers are server-side measurements only. They tell us how much CPU the server saves; they don't tell us:

1. **What it costs to do the fold elsewhere** — same fold work, different machine
2. **Wire bandwidth impact** — server→router→client carries `base + N operands` instead of materialized record (~10-50%
   larger)
3. **Multi-language client library cost** — Java client has WriteCompute already; Python/Go/etc. do not
4. **Whether router is the right fold-host** vs. end-client

A full GOAL doc would be needed to design the production change. This NOTES file is the foundational measurement data
that justifies pursuing such a study.
