# iter-6 NOTES — synthesize RMD from operand chain on getReplicationMetadata

## Hypothesis

After iter-5, 2/3 cross-DC tests pass. OnMapField still fails because flag-on UPDATE operands don't write RMD
column-family entries. When the AA leader processes a subsequent PUT, it reads `existingRmd = null` and merges the PUT
as if it were a first PUT — losing the operand chain's per-element-ts state.

## Fix

Override `MaterializingReplicationMetadataRocksDBStoragePartition.getReplicationMetadata` to synthesize an RMD via fold
when:

- on-disk RMD is null, AND
- the on-disk VALUE has an operand chain (KIND_BASE+operands or KIND_OPERAND chain)

The synthesized RMD reflects the chain's cumulative per-field-ts state via V2 fold. Returned in the on-disk RMD format
([4B schemaId][rmd avro bytes]) so callers can use it interchangeably.

Implementation:

1. Add `MaterializingFoldContext.computeSynthesizedRmd(baseSchemaId, baseValueBytes, operands)`. Folds the chain through
   V2 (using on-disk-null seed RMD), serializes the final RMD record with the resolved valueSchemaId.
2. Add `StoreWriteComputeProcessor.serializeRmdRecord` helper.
3. Add `MaterializingFraming.maybeSynthesizeRmdFromChain` that parses the raw value, detects chain type
   (KIND_OPERAND-only vs KIND_BASE+operands), and delegates to fold context.
4. Override `getReplicationMetadata` in `MaterializingReplicationMetadataRocksDBStoragePartition` to call the synthesis
   when on-disk RMD is null and value bytes have a chain.

## Result

(filled in after test runs)
