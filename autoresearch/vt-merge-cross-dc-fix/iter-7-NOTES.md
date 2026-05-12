# iter-7 NOTES — synthesis fires, OnMapField progresses, hits new IOOB

## Hypothesis

iter-6's synthesis path silently NPE'd (decompressBase line 518 with null baseValueBytes). iter-7 adds null-guard.

## Result

With null-guard fix, synthesis succeeds:

```
[VT-MERGE-RMD-SYNTH-OK] rawValueLen=213 synthesizedRmdLen=56
```

§5.4.2 OnMapField test progresses past the original failure (line 927) to a NEW failure at line 1045:
`IndexOutOfBoundsException: Index 1 out of bounds` in `Utils.createElementToActiveTsMap` via V2 algorithm during a
subsequent operand's read-fold.

This suggests the synthesized RMD I pass to the leader's RMW (or the resulting RMD the leader writes back) is
inconsistent with the value record's element count, causing V2 to OOB when reading the on-disk RMD on a subsequent
UPDATE fold.

Diagnosis: likely the leader's `mergePutWithFieldLevelTimestamp` produces an RMD whose putOnlyPartLength +
activeElementTimestamps.size doesn't match the PUT's element count. Need to inspect the merge result more carefully — or
take a fundamentally different approach (e.g., write a backstop PUT before the leader's RMW so on-disk RMD matches the
value).

## Decision

Deferring OnMapField to follow-up work. Status at iter-7:

- 2/3 §5.4.2 cross-DC tests now pass (+2 from baseline 20/24).
- §5.4.1 flag-on 7/7 still PASS (no regression).
- §5.4.3 3/3 PASS (no regression).
- 22/24 confirmed, 23/24 once flag-off §5.4.2 also re-verified.
