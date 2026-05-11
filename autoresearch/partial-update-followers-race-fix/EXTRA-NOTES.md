# EXTRA-NOTES — Follow-up analysis of why flag-OFF doesn't manifest the race

**Date:** 2026-05-11 **Status:** Analysis/hypothesis document (not measured) **Companion to:** `BLOCKED-NOTES.md` (the
agent's blocked outcome from this work-stream)

---

## The sharper question post-BLOCKED-NOTES

Phase 1 confirmed the race exists for flag-ON. Phase 2 disproved three plausible fixes. BLOCKED-NOTES surfaced two
surviving hypotheses (late-joining replica bootstrap bypass; cross-consumer-thread interleaving).

But the more fundamental question, **not directly answered by the agent's investigation**, is:

> The partition lifecycle, WAL-disabled config, and close+reopen events are IDENTICAL between flag-OFF and flag-ON. Yet
> flag-OFF doesn't manifest the bug. **What does flag-ON do that flag-OFF doesn't?**

If we answer this, the fix design becomes obvious. Below are four hypotheses, each testable with concrete experiments.

---

## H1: Self-healing semantics (probably the biggest contributor — design property, not a fix-target)

**BASELINE PUTs are self-contained; MERGE operands aren't.**

```
BASELINE flag-OFF — what happens if a PUT is lost in close+reopen:
   t=1   Leader RMW + produces full PUT "(new_name_3, last_name_3)" to VT
   t=2   Follower partition.put → memtable
   t=3   Close+reopen → memtable dropped → PUT lost
   t=4   VT replay or next iter → same PUT reissued → value lands on disk
   t=5   Read returns "new_name_3" ✅

MERGE flag-ON — what happens if the same race fires:
   t=0   Batch push → base "first_name_3" written via SST ingestion (durable; survives close)
   t=1   Leader produces operand "setName=new_name_3" to VT
   t=2   Follower partition.merge → memtable
   t=3   Close+reopen → memtable dropped → operand lost
   t=4   No replay — metadata WAL says offset is past this point
   t=5   Read returns "first_name_3" ❌
```

**Verification experiment (H1):** enable `setDisableWAL(false)` for data partitions when
`vt.update.operand.enabled=true`. If the test passes, the bug is the close+reopen-without-flush surface and the fix is
"enable WAL for flag-on data partitions" (with a per-record perf cost trade).

**Probable outcome:** test passes → fix is mechanical (one config change), perf trade explicit.

---

## H2: Path divergence — flag-OFF goes through `processActiveActiveMessage`; flag-ON skips it

The iter-11 fix made flag-ON UPDATEs early-return:

```java
if (serverConfig.isVtUpdateOperandEnabled() && msgType == UPDATE) {
  return null;  // ← iter-11 fix
}
```

This skip avoided the operand-buffer-position-advancement bug. But `processActiveActiveMessage` may have been doing
something that **incidentally** protected against the partition race:

- Holding a different lock that serialized with `adjustStoragePartition`
- Calling `getValueBytesForKey` which forced partition-state stabilization
- Synchronously waiting for some flush state

Flag-OFF still benefits from this implicit protection; flag-ON now forgoes it. This would explain why the iter-11 fix
unblocked the sister test (empty push, no lifecycle adjusts) but doesn't unblock these 7 (which do have lifecycle
adjusts).

**Verification experiment (H2):** temporarily revert the iter-11 early-return; re-run the failing test. Expected: the
iter-5 operand-buffer-advancement bug re-appears, the test fails differently (or differently passes, if the
operand-advance and the race net out). If the test passes with iter-11 reverted, the path-skip is the trigger. If the
test fails with a different symptom, the iter-11 skip is orthogonal.

**Probable outcome:** ambiguous — iter-11's skip and iter-5's bug interact in complex ways; need careful read of the
failure symptom.

---

## H3: Override asymmetry — `partition.put` and `partition.merge` interact with lifecycle differently

`MaterializingReplicationMetadataRocksDBStoragePartition` overrides both `put` and `merge` for framing/fold. But:

- `put` / `putWithReplicationMetadata` apply base framing via iter-1/iter-2 fixes
- `merge` applies operand framing (operand kind-byte + len prefix)
- The two overrides may interact differently with `MaterializingFoldContextRegistry` and `FRAMING_IN_PROGRESS`
  thread-local

If `merge` depends on per-store state in `MaterializingFoldContextRegistry` that gets reset on close:

- Merges in-flight during close+reopen could land on a partition whose `foldContext` was unregistered
- The framing path would skip (no fold context) → operand bytes persist without proper kind-byte prefix
- Read can't parse → "operand only, no base" symptom

**Verification experiment (H3):** add comprehensive logging to both override paths capturing:

- partition `System.identityHashCode(this)` at call time
- `MaterializingFoldContextRegistry.get(storeNameAndVersion)` result at call time
- `FRAMING_IN_PROGRESS.get()` state at call time
- Thread name

Cross-reference with the operand-only readbacks. If a merge's foldContext was null at merge time but the operand bytes
were still persisted, that's the bug.

**Probable outcome:** moderate-likely to find a smoking gun if H1 doesn't pass; the registry-reset-during-close pattern
is a known footgun shape.

---

## H4: The "split-half" structural divider — local-DC vs remote-DC writes

The agent's observation: **exactly half the keys on the same partition object show the bug within the same second.** Not
random timing — deterministic structure.

The most likely structural divider at flag-ON, with AA replication:

| Path                 | Where the UPDATE comes from                                                                           | Code path on follower              | Framing?                   |
| -------------------- | ----------------------------------------------------------------------------------------------------- | ---------------------------------- | -------------------------- |
| **Local-DC writes**  | This DC's leader fast-path (iter-11 codepath) → local VT → local follower's `processKafkaDataMessage` | Skips `processActiveActiveMessage` | Through merge override     |
| **Remote-DC writes** | Remote DC's leader → remote VT → this DC's _remote-VT consumer_ → `processKafkaDataMessage`           | Possibly different path            | Possibly different framing |

With AA, roughly half the writes for each partition come from each DC's leader (under symmetric workload). If one path
bypasses framing/fold-context registration, the structural ~half-affected pattern matches.

BASELINE flag-OFF doesn't have this asymmetry: **both DCs' leaders produce full PUTs**, both go through the same
partition.put override, both apply framing identically.

**Verification experiment (H4):** instrument follower-side `case UPDATE` handler to log the source DC/topic for each
UPDATE. Per-key, capture which DC's VT this UPDATE arrived from. Then for each operand-only readback key, look up the
source DC. If keys from one DC are systematically affected, hypothesis confirmed.

**Probable outcome:** likely to confirm or refute cleanly. If confirmed, the fix is to ensure both DC paths go through
the materializing framing.

---

## Recommended investigation order

| Order | Hypothesis | Experiment                                             | Cost                                        | Information value                                                         |
| ----- | ---------- | ------------------------------------------------------ | ------------------------------------------- | ------------------------------------------------------------------------- |
| 1     | **H1**     | Enable WAL on data partitions flag-on                  | 1 config change + 1 test run (~5 min)       | If passes → mechanical fix found; if fails → all other hypotheses survive |
| 2     | **H2**     | Revert iter-11's early-return                          | 1 line change + 1 test run                  | Likely ambiguous but cheap to try                                         |
| 3     | **H4**     | Per-key source-DC tracking                             | Add 1 log line + 1 test run + grep analysis | Reveals structural divider IF one exists                                  |
| 4     | **H3**     | Comprehensive `put`/`merge`/`registry` instrumentation | Larger diagnostic patch + 1-2 runs          | Last resort; broadest but most invasive                                   |

If H1 passes (which is probable for a memtable-loss bug), we don't need to test H2/H3/H4 at all. If H1 fails, the order
above is cheapest-to-most-expensive.

---

## What the agent's 4 phase-2 fix attempts already tell us

The agent disproved that the bug is in `closePartition`'s flush behavior alone:

- `partition.sync()` before close — no change
- `rocksDB.flush()` inside close() — no change
- put-preserves-existing-operand-suffix — no change
- All combined — no change

This argues **against** H1 being a simple memtable-flush issue. It might be that the close+reopen window is narrow
enough that flush doesn't help, OR the flush isn't reaching the right data. Or H1 isn't the primary mechanism.

**If H1's WAL experiment also fails to fix the test**, we definitively rule out the close+reopen memtable surface and
the bug is elsewhere — most likely H3 (registry reset during close → framing bypass) or H4 (DC-asymmetric paths).

---

## In one sentence

> **The agent confirmed the race exists and disproved the simple memtable-loss hypothesis; the four follow-up hypotheses
> each have concrete experiments that should converge on the actual mechanism in 4-8 additional iterations.**
