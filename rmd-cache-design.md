# RMD Lookup Cache Design (scratch, sub-task a)

## Four-branch decision logic (verbatim from PDF, applied per partition)

For every new PUT record R consumed from an RT topic on an AA leader:

- (A) Bloom filter says R.key is DEFINITELY NOT IN DB:
      -> R wins DCR. Insert (R.key_hash, R.timestamp) into the cache.
      -> No RMD RocksDB read.

- (B) Bloom filter says R.key is POSSIBLY IN DB:
  - (B.1) R.key_hash is in the cache:
      -> Compare R.timestamp with cached timestamp.
      -> If R wins, update cache entry; otherwise drop R.
      -> No RMD RocksDB read.
  - (B.2) R.key_hash is NOT in the cache:
    - (B.2.a) R.timestamp > highestTimestampPersistedInDbButNotInCache:
      -> R wins. Persist it, add (R.key_hash, R.timestamp) to cache.
      -> No RMD RocksDB read.
    - (B.2.b) else:
      -> Fall back to an RMD RocksDB read to obtain the old timestamp,
         run the normal DCR comparison.

## Eviction invariant

- Cache evicts entries whose timestamp < (CurrentTime - T).
- On eviction, highestTimestampPersistedInDbButNotInCache must be raised to
  max(existing, evicted.timestamp).
- Invariant preserved: every key present in DB but absent from cache has
  timestamp <= highestTimestampPersistedInDbButNotInCache.
- Max-size cap: when the cache reaches cap and a new insert arrives, evict
  the entry with the oldest timestamp (also raises highestTs).

## Thread-safety

- Per-partition instance (not shared across partitions).
- Multiple AA/WC parallel workers call into the same partition's cache.
- highestTimestampPersistedInDbButNotInCache: AtomicLong.
- Main map: ConcurrentHashMap<Long, Long> (hashed key -> timestamp).
- Bloom filter: Guava BloomFilter is thread-safe for puts + queries.

## Key hashing

- hash64(key bytes) via a fast 64-bit mixing hash (e.g. Guava Hashing.murmur3_128()
  truncated, or a simpler xxhash-style local implementation to avoid Guava dep).

## Cache wiring location

- Server-side: clients/da-vinci-client/src/main/java/com/linkedin/davinci/replication/
  -> New package `rmdcache` with `RmdTimestampCache` class (per-partition).
- Invoked from ActiveActiveStoreIngestionTask.processActiveActiveMessage BEFORE
  the call to getReplicationMetadataAndSchemaId:
    * For PUT only, run cache decision:
       - (A), (B.1)-win, (B.2.a)  -> build a synthetic RmdWithValueSchemaId
         that signals "no need to read RMD; caller should use cached path"
         OR simply skip the RMD read and call a new MergeConflictResolver.putWithTimestampOnly(...)
         that records the merge decision using (newValue wins) logic.
       - (B.1)-lose               -> ignore the record; skip RMD read, skip merge.
       - (B.2.b)                  -> fall back to existing RMD read + merge path.

## Integration strategy for PUT fast-path

The existing putWithoutRmd() method in MergeConflictResolver already does
exactly what we need when "new wins without comparison": it creates a new
RMD and returns a MergeConflictResult with the incoming value. This is the
existing code path used when RMD is null (i.e. first write of a key). We can
reuse it to implement branches A, B.1-win, and B.2.a as "synthetic first-write"
where the cache already proved the incoming R wins.

For branch B.1-lose, we can return MergeConflictResult.getIgnoredResult()
directly.

For branch B.2.b, we fall through to the original RMD lookup path.

## Config keys

- server.aa.rmd.timestamp.cache.enabled (boolean, default false)
  The feature flag. Default off to preserve existing behavior in production.
  The benchmark sets it to true.
- server.aa.rmd.timestamp.cache.time.window.ms (long, default 60000)
  T = 60s by default.
- server.aa.rmd.timestamp.cache.max.size.per.partition (int, default 500000)
  Hard cap on entries to bound memory.
- server.aa.rmd.timestamp.cache.bloom.expected.insertions (int, default 1000000)
- server.aa.rmd.timestamp.cache.bloom.fpp (double, default 0.01)

## Metrics (criterion 3)

Per-partition AtomicLong counters exposed on the cache object:
- skippedRmdLookupCount    (branches A + B.1 + B.2.a)
- fallbackRmdLookupCount   (branch B.2.b)

Aggregation + stderr print via ActiveActiveStoreIngestionTask's periodic
logger or a JMH `@TearDown(Level.Iteration)` hook is NOT allowed (benchmark
source is frozen). Instead, we aggregate across all partitions via a static
registry in the cache class, and print via a JMH IterationListener registered
at JAR bootstrap (or via a stats object hooked from the ingestion task's
existing iteration tear-down in HostLevelIngestionStats).

Simpler alternative: add a static ConcurrentHashMap<String, long[]> in the
RmdTimestampCache class (key = storeVersionTopic), keyed by VT, and iterate
from a JMH AuxCounters or a benchmark-side listener. But the benchmark file
is frozen.

Final approach: register a JMH IterationListener via ServiceLoader. JMH
supports custom listeners. The JMH CLI does not expose a flag, but we can
register via the `META-INF/services/org.openjdk.jmh.runner.NotificationListener`
— wait, JMH does not have such a listener SPI.

Actual simplest solution:
  - Add a static AtomicLong totalSkipped and totalFallback in RmdTimestampCache.
  - Make them reset-to-zero on any new partition warmup (safe because we only
    care about the per-iteration delta printed at iteration TearDown).
  - Read + print them from HostLevelIngestionStats or from a new
    AAIngestionIterationReporter that uses a background thread that detects
    iteration boundaries via a sentinel... but that's complex.
  - CLEANEST: add a static ScheduledExecutorService that every 20s prints the
    aggregate counters to stderr in the form "[RMD-CACHE] workload=PUT skipped=S
    fallback=F ratio=R". This is decoupled from JMH iterations but per the
    criterion, the master will parse `[RMD-CACHE] workload=PUT` lines from the
    log and average across the 2 measurement iterations.

Actually even simpler: a daemon thread in RmdTimestampCache that snapshots
the counters every 20s and prints the delta. Since the baseline benchmark uses
2x20s measurement iterations, the report periods will roughly align with
iteration boundaries.

Best approach (final): hook it into the benchmark harness via the existing
`finishIterationAndReportE2E` TearDown. Oh wait, we can't modify the benchmark.

Decision: Use a daemon thread that prints every 20s, so each measurement
iteration gets ~1 stderr line. Emit the workload type as "PUT" (the only
workload instrumented). The criterion verification parses these lines and
averages the ratio across them.

## Unit tests

- RmdTimestampCacheTest: direct cache unit test for each branch.
- ActiveActiveDcrCacheTest: integration-style test that drives PUT records
  through the ingestion task with mocked storage engine, and asserts:
  * For every branch (A, B.1, B.2.a, B.2.b) the DCR decision matches the
    non-cached reference path.
  * A batch fully routed through B.2.b (timestamps < highestTs) hits the
    RMD lookup.
  * Two concurrent writers on the same key produce the same outcome as the
    serial reference.
