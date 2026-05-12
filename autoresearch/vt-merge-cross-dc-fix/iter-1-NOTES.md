# iter-1 NOTES — Phase 1 cache infrastructure

## Hypothesis

Build the FieldLevelRmdCache infrastructure per the GOAL §4 design. The cache must support:

- Per-(key, fieldOrdinal) entries with: topLevelFieldTs, rmdSubtree (collection), populatedByPut bit
- Per-key mode (0 = whole-record-ts, 1 = per-field-ts) via sentinel slot at ordinal=-1
- Mode 0 → 1 transitions with proper synthesis honoring the two gotchas
- Mode 1 → 0 transitions with cross-field invalidation
- 4-branch decision logic from the design

The two gotchas to honor in Phase 1 synthesis:

- **Gotcha #1:** empty-at-PUT collection field → synthesized topLevelFieldTs = Long.MIN_VALUE
- **Gotcha #2:** scalar field at schema default at PUT time → topLevelFieldTs = Long.MIN_VALUE, populatedByPut = false

## Plan for iter-1

1. Reverted the uncommitted diagnostic logs from prior session (TIMING-RMW, TIMING-AA-RMW).
2. Create the package `com.linkedin.davinci.replication.rmdcache.field`.
3. Implement `FieldRmdEntry` POJO.
4. Implement `FieldLevelRmdCache` with simpler in-memory hash-map (ConcurrentHashMap-based) — the lock-free primitive
   map is a performance optimization for later; correctness comes first. We can revisit if Phase 3 microbenchmark shows
   it dominates.
5. Implement Mode 0 → 1 synthesis that consults the base value record to determine populatedByPut per field.
6. Write unit tests demonstrating each behavior including both gotchas.

## Decision points

- **Use ConcurrentHashMap (simpler) vs LockFreeLongLongMap (faster):** Start with ConcurrentHashMap. For the 24/24
  target, correctness >> performance. The Phase 3 microbenchmark will tell us if we need to switch.
- **Cache lifetime:** in-memory per partition, rebuilds on partition reopen. Out of scope: persistence.

## Result

(filled in after iter-1 lands)
