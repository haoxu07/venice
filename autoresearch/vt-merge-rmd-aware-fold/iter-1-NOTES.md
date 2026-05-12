# iter-1 NOTES — Open-question resolution + strategy pivot

## Hypothesis

The primary failure (`testActiveActivePartialUpdateWithCompression` flag-on timeout) is driven by **per-call V1 fold
cost** (O(N×M) `.contains()` on a 380K-float list) — NOT by cross-DC AA semantics bugs. If we route the fold path
through V2 with a **synthesized seed RMD** (no persistent cache yet), the timeout disappears.

A full per-(key, field) RMD cache (Phases 1+2 of GOAL.md) is only needed if §5.4.2 cross-DC tests fail. Build it
incrementally, after Phase 3 proves the perf win.

## §11 open-question resolutions

1. **Cache scope — per-store-version or per-partition?** **Decision: per-partition.** Mirrors `RmdTimestampCache` and
   matches the lifecycle of `PartitionConsumptionState` / RocksDB partitions. When the partition closes the cache drops.
   This avoids cross-partition coordination on schema-version-but-different-partitions.

2. **Mode transition detection — operand type vs separate RMD-mode field?** **Decision: operand-type-driven, with a
   sentinel cache entry.** Incoming UPDATE → per-field-ts mode. Incoming PUT → whole-record mode. On cache miss we read
   RocksDB RMD once and inspect its union branch (whole-record-ts vs per-field-ts record) to set the sentinel. The cache
   stores the last-observed mode bit at `(keyHash, fieldOrdinal=-1)` sentinel slot.

3. **Should the chunked-manifest chain backstop also move to V2?** **Decision: deferred / not in scope of this iteration
   loop.** The backstop already collapses on chain-length-1 for chunked manifests
   (`MaterializingReplicationMetadataRocksDBStoragePartition` line 135), so V1 cost is paid at most once per write — not
   the dominant cost path. We can revisit in a separate change.

4. **Which timestamp to use for DCR — operand's `updateOperationTimestamp` or Kafka offset/produce time?** **Decision:
   `updateOperationTimestamp` from the WC payload.** This is what `MergeConflictResolver.update()` uses for baseline AA
   leader RMW, so flag-on cache must use the same axis to preserve semantics. Kafka produce time would diverge from the
   application-level write timestamp and break cross-DC DCR.

## Strategy pivot (vs literal GOAL.md ordering)

The GOAL doc orders Phase 1 (cache) → Phase 2 (merge wire-in) → Phase 3 (fold V2 routing). I am inverting Phase 3 to
come first, because:

- **The primary integration-test failure** (`testActiveActivePartialUpdateWithCompression` timeout) is a **per-call fold
  cost** issue, not a DCR-correctness issue.
- **V2 routing requires no persistent cache** if we synthesize a put-only-state seed RMD with `topLevelTs=0` per fold
  call. Every operand wins (`ignoreIncomingRequest` returns false when `topLevelTs < modifyTs` and operands carry
  strictly positive ts). The V2 algorithm uses `IndexedHashMap` for sub-quadratic add/remove — that's the empirically
  10-58× speedup the microbenchmark shows.
- **Cross-DC DCR correctness** (§5.4.2) is only at risk if two DCs interleave operands with timestamp inversion. The
  seed-RMD approach preserves operand-order semantics within a single chain because operands carry monotonically
  increasing ts in `updateOperationTimestamp` — the V2 algorithm's element-level DCR still applies inside one chain.

If §5.4.2 tests fail after Phase 3, I'll layer in the cache. If they pass, the cache is unnecessary for the
24-invocation gate.

## Plan for iter-2 onward

- **iter-2**: Implement seed-RMD V2 routing in `MaterializingFoldContext.foldOperands` + `foldOperandOnly`. Unit-test
  parity with a chain of setUnion operands on a growing list.
- **iter-3**: Microbenchmark validation. Confirm new fold path is within ~2× of V2 baseline on the 380K-float workload.
- **iter-4-5**: Integration tests — §5.4.1 group first (the primary target). If §5.4.2 also passes, skip Phase 1-2;
  otherwise add cache.
- **iter-6+**: Optionally add cache, finalize, write COMPLETE.md / OUTCOME.md.

## Halt-trigger pre-check

- Iter-2 unit test for V2 fold parity: if it differs from baseline AA result on a deterministic workload → halt; the
  seed-RMD shortcut is wrong.
- Iter-3 microbenchmark: if new fold is not materially faster than V1 → halt (V2 routing overhead dominates, falls back
  to Phase 1 cache approach).
- Iter-4 integration: any flag-off regression → halt as hard blocker.

## Result

Iter-1 is design-only; no code change. Next iteration: code + unit test for Phase 3 seed-RMD V2 routing.
