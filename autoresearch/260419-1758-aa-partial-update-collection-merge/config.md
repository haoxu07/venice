# Autoresearch run: AA partial-update collection-merge optimization

## Configuration

- **Goal:** maximize E2E throughput (ops/s) of `ActiveActiveIngestionBenchmark` PARTIAL_UPDATE workload
- **Metric:** `e2e_throughput_ops_per_sec` from benchmark stderr (measurement iteration only)
- **Direction:** higher is better
- **Iterations:** 5 bounded
- **Scope (modifiable):**
  - `clients/da-vinci-client/src/main/java/com/linkedin/davinci/schema/merge/SortBasedCollectionFieldOpHandler.java`
  - `clients/da-vinci-client/src/main/java/com/linkedin/davinci/schema/merge/CollectionTimestampMergeRecordHelper.java`
  - `clients/da-vinci-client/src/main/java/com/linkedin/davinci/schema/merge/PerFieldTimestampMergeRecordHelper.java`
- **Verify:** benchmark run with PARTIAL_UPDATE + 10K pool + 100-entry tags
- **Guard:** all tests in `TestPartialUpdateWithActiveActiveReplication`

## Hot path identified

Per AddToMap call on a 100-entry tags map, `handleModifyCollectionMergeMap` does:
1. Clone `currMap` to `ArrayList<KeyValPair>` (100 new objects)
2. Build `IndexedHashMap<KeyValPair, Long>` from that list (another 100-slot map)
3. Build `IndexedHashMap<String, Long>` for deleted keys
4. Iterate new entries — O(incoming) lookups in step 1's map
5. On update: `remove() + put()` for IndexedHashMap insertion-order reordering
6. Rebuild `ArrayList<ElementAndTimestamp>` (100 entries)
7. `sortElementAndTimestampListInMap` (O(N log N))
8. Allocate new `IndexedHashMap` result, copy all 100 entries back
9. Call `currValueRecord.put()` to replace

Allocation budget per 1-entry AddToMap ≈ 5 collections × 100 slots = 500 object slots + sort.
