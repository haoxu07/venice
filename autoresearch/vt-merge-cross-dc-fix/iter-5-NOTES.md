# iter-5 NOTES — Progress: 2/3 §5.4.2 cross-DC tests now PASS

## Result after iter-5 (superset schema for fold reader)

- §5.4.2 OnFields flag-on: **PASS** (was FAIL)
- §5.4.2 OnListField flag-on: **PASS** (was FAIL)
- §5.4.2 OnMapField flag-on: **FAIL** still
- §5.4.1 flag-on regression: 7/7 PASS (no regression)

## OnMapField analysis

Failure at line 927: `expected [4] but found [3]`.

Sequence:

1. Chain has 3 operands at ts=1, 2, 3 adding map entries {OneZero@1, TwoZero@2, ThreeZero@3}.
2. PUT@ts=2 with map={one, two, three}.
3. Expected: PUT@ts=2 wins on elements at ts <= 2 (OneZero, TwoZero removed), retains ThreeZero@3. Final map: {one, two,
   three, ThreeZero} = 4 entries.
4. Actual: 3 entries (no ThreeZero).

Root cause: under flag-on, the operand chain doesn't update the RMD column family. When the AA leader processes PUT@ts=2
via RMW, it reads:

- oldValue (materialized via fold): {OneZero, TwoZero, ThreeZero}
- existingRmd: NULL (chain never wrote RMD)

With null RMD, `mergeConflictResolver.put` treats the PUT as a first-time PUT → new wins fully, overwrites everything
including ThreeZero. The chain's per-element-ts is lost.

## Next-step options

1. **Compute synthesized RMD at AA-leader RMW time** by folding the chain via V2. Requires plumbing the RMD-fold path
   into `getReplicationMetadataAndSchemaId`. Heaviest weight but most architecturally clean.

2. **Pre-fold the chain into a single PUT BEFORE the leader's RMW.** Use the chain-length backstop's mechanism at
   PUT-RMW entry to write the existing folded value+RMD back to disk FIRST, then run RMW with the now-updated RMD.

3. **Bypass the leader's RMW for cross-DC PUT-after-chain and let the follower's fold handle it.** This would require
   the leader to forward the PUT as-is without DCR; the follower's chain would then have PUT+UPDATE mixed which the fold
   handles.

Going with option 2 for the next iter: pre-fold the chain via the existing chain-length backstop mechanism, triggered at
PUT RMW entry. This reuses existing infrastructure.

Actually, looking deeper: the chain-length backstop in the storage partition is on the FOLLOWER side. The leader's RMW
reads via `getValueBytesForKey` which goes through the partition's `get(byte[])` which already folds. So the leader sees
folded VALUE. The issue is the leader's RMD column-family read returns null because the chain never wrote RMD.

The fix needs to either:

- Compute RMD at fold time and persist it to RMD column-family.
- Or compute RMD at leader read time and pass it to mergeConflictResolver.

I'll try the simpler version of option 1: at the AA leader's `getReplicationMetadataAndSchemaId` call, if the on-disk
RMD is null AND the storage partition has a fold context registered AND the value bytes are non-null (meaning the key
has data), compute the RMD via fold. Save in PartitionConsumptionState transient cache so subsequent reads don't
re-fold.
