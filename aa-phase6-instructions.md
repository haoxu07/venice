# AA Ingestion Bottleneck — PHASE 6 Subagent Instructions (Iteration 1)

You are the worker subagent for Phase 6. **READ this entire file before starting work.** Then read `/home/coder/AA_LEADER_OTHER_AND_KAFKA_PIPELINE_PHASE6_PROMPT.md` for the full goal context.

## Your goal in one sentence

Add ≥5 new leader sub-stage timer brackets to cover the leader OTHER bucket; add Kafka pipeline (consumer poll-wait/fetch decomposition + producer JMX) instrumentation; run 3 measurement runs + 1 OFF run + integration tests; identify whether the constraint is (a) leader OTHER, (b) Kafka pipeline, or (c) hybrid; produce `aa-phase6-result.json` and `AA_LEADER_OTHER_AND_KAFKA_PHASE6_REPORT.md`.

## Repo state when you start

- Working dir: `/home/coder/Projects/venice`
- Branch: `haoxu07/aa-ingestion-benchmark`
- HEAD: `12196b28a` (Phase 4); uncommitted Phase 5/5.5 changes are in working tree.
- **CRITICAL**: `internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java` line ~246 currently has `.setWriteComputationEnabled(true)` (Phase 5.5 leftover). **Your FIRST action** is to revert it to `.setWriteComputationEnabled(workloadType != WorkloadType.PUT)`. Verify with `git diff` before doing anything else.
- `PUT_KEY_POOL_SIZE = 100_000` is required (already set — keep as is).
- `AaLeaderBottleneckReporter.java` exists with the `[BOTTLENECK]` stderr format. Extend its `Stage` enum to add the Phase 6 stages.
- `AaDcrMergeReporter.java` exists. Don't break it.

## File-based protocol with the master

| File | Direction | Purpose |
|---|---|---|
| `aa-phase6-status.txt` | Subagent → Master | One word: `RUNNING_iter_1` (already set) → write `WAITING_VERIFY_iter_1` when done |
| `aa-phase6-progress.md` | Subagent → Master | Append progress every ~5–10 min so master sees liveness |
| `aa-phase6-result.json` | Subagent → Master | Final structured numbers per criterion 8 |
| `aa-phase6-instructions.md` | Master → Subagent | THIS file. Read once at start. |

**Heartbeat rule**: when running long detached jobs (build/runs/itest), poll-loop with 30-sec intervals and emit one short tool call per cycle. Append a progress line every ~5–10 min.

## Hard rules (DO NOT VIOLATE — verified by master at end)

1. DO NOT modify the merge LOGIC or any AA leader business code. Only add timer brackets and metric extraction.
2. DO NOT remove or disable Phase 0–5 instrumentation classes (`AaLeaderBottleneckReporter`, `AaDcrMergeReporter`, `TransientRecordCacheDiagnosticReporter`).
3. DO NOT enable WC for PUT in benchmark. Revert to `setWriteComputationEnabled(workloadType != WorkloadType.PUT)`.
4. DO NOT lower the PUT key pool below 100,000.
5. RMD cache stays ON (`-Dvenice.server.aa.rmd.timestamp.cache.enabled=true`).
6. Java 17 ONLY: `JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft`.
7. `git commit --no-verify` (npm pre-commit hook is broken; pre-authorized).
8. Branch: `haoxu07/aa-ingestion-benchmark`. Commit prefix: `[study][server]` or `[study][dvc]`.
9. Use `nohup setsid bash -c '...' > /path/to.log 2>&1 &` + `.pid` + `disown` for benchmark/test runs.
10. **Poll detached runs every 30 sec, NOT every 5 min**, with one short tool call per cycle (Phase 3 first agent was killed for silent waiting).

## Sub-task plan (recommended order)

### Step 0: Revert Phase 5.5 WC change + verify clean state
- Edit `ActiveActiveIngestionBenchmark.java`: change `.setWriteComputationEnabled(true)` back to `.setWriteComputationEnabled(workloadType != WorkloadType.PUT)`. Update the comment to reference Phase 6 if you like.
- Confirm `PUT_KEY_POOL_SIZE = 100_000`.
- Run `git diff internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java` and verify the only changes are: (a) PUT_KEY_POOL_SIZE 10_000→100_000, (b) the comment lines, (c) WC line restored to `workloadType != WorkloadType.PUT`.
- Append "Step 0 done: WC reverted" to `aa-phase6-progress.md`.

### Step 1: PART A — Map the leader OTHER bucket
- Read `aa-bottleneck-hotloop-map.md` if present; otherwise read `ActiveActiveStoreIngestionTask.processActiveActiveMessage` (~lines 540-720) to see the AA leader PUT hot loop.
- Identify ≥5 currently-uninstrumented code paths inside the per-record wall. Likely candidates (subagent picks the actual cuts):
  - `sensor_record_calls` — cumulative time inside `getHostLevelIngestionStats().recordX(...)` + `versionedIngestionStats.recordX(...)` calls per record
  - `lazy_oldvalue_init` — `Lazy.of(() -> ...)` construction (even when supplier not invoked)
  - `merge_result_wrapper` — `MergeConflictResultWrapper` allocation
  - `per_record_wrapper` — try/finally + exception handling overhead
  - `transient_map_value_check` — pre-merge transient cache check (separate from RMD cache)
  - `getReplicationMetadataAndSchemaId_entry` — entry overhead before transient lookup
  - `chunked_value_manifest_alloc` — `ChunkedValueManifestContainer` / `valueManifestContainer` allocations
- Write a scratch doc `/home/coder/Projects/venice/aa-phase6-other-map.md` listing each chosen sub-stage with file:line references and rationale.

### Step 2: PART A — Extend AaLeaderBottleneckReporter
- Add ≥5 new enum constants to `AaLeaderBottleneckReporter.Stage`. They should be **on-leader-wall** (set `offLeaderWall=false`) so they're included in coverage.
- Suggested names: `SENSOR_RECORD_CALLS`, `LAZY_OLDVALUE_INIT`, `MERGE_RESULT_WRAPPER`, `TRANSIENT_VALUE_CHECK`, `PER_RECORD_WRAPPER`. Pick whatever the code actually warrants.
- Gate with a NEW system property: `venice.server.aa.leader.other.instrumentation.enabled` (separate flag from the existing bottleneck flag), so OFF-run can disable just the new timers.
- The EASIEST way to add the new flag: add a `LEADER_OTHER_ENABLED` static boolean to `AaLeaderBottleneckReporter` reading `Boolean.getBoolean("venice.server.aa.leader.other.instrumentation.enabled")`. Then in the call sites, gate with `AaLeaderBottleneckReporter.LEADER_OTHER_ENABLED && AaLeaderBottleneckReporter.ENABLED`.
- Wire the timer brackets at the call sites identified in Step 1. Use the same `start = System.nanoTime(); ...; AaLeaderBottleneckReporter.record(stage, System.nanoTime() - start);` pattern.

### Step 3: PART B.1 — Consumer poll-wait/fetch decomposition + producer JMX
- Find the `ConsumptionTask` (or whichever class wraps the `consumer.poll()` for the leader RT consumer). The existing `RT_POLL_BLOCK_NS` measures total poll time. Split it:
  - `poll_wait_for_records` — when `records.isEmpty()` after the poll, the entire poll time is "waiting for records that didn't arrive"
  - `poll_fetch_records` — when records returned, time spent receiving + deserializing them
- Also extract Kafka client JMX metrics. The cleanest way: at the end of each 20-sec reporter tick, scan the JMX MBean server for `kafka.producer:type=producer-metrics,*` and `kafka.consumer:type=consumer-fetch-manager-metrics,*`. Print:
  - `[KAFKA-PIPELINE] producer_send_rate=... request_latency_avg=... batch_size_avg=... records_per_request_avg=... record_queue_time_avg=...`
  - `[KAFKA-PIPELINE] consumer_records_consumed_rate=... fetch_latency_avg=... fetch_size_avg=... records_per_request_avg=...`
- Use a NEW system property: `venice.server.aa.kafka.pipeline.instrumentation.enabled` (separate flag).
- Create a new reporter class `AaKafkaPipelineReporter.java` in `clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/`. It can use a daemon scheduler that prints `[KAFKA-PIPELINE]` lines every 20 sec. JMX scan via `ManagementFactory.getPlatformMBeanServer().queryNames(...)` with the kafka object name patterns.

### Step 4: PART B.2 — VT producer ack-callback decomposition
- Find `LeaderProducerCallback` (or the equivalent class in `kafka/consumer/`). The existing `VT_PRODUCE_ACK_WAIT` covers `producer.send → callback complete`. Split it:
  - `vt_send_to_request_complete` — time from `producer.send` (RecordMetadata produced timestamp) to `onCompletion` ENTRY. This is roughly broker-ack roundtrip.
  - `vt_callback_dispatch_delay` — the delay between when the broker acknowledged (record-end-offset is committed) and when our callback actually ran. If the producer's `RecordMetadata.timestamp` is broker-side, the gap from there to System.currentTimeMillis() at callback start is the dispatch delay.
- Or, if RecordMetadata broker-time is unreliable, capture two timestamps inside the callback: callback ENTRY (`onCompletion` start) and callback EXIT (after we've enqueued to drainer/transient_map). Difference = `vt_callback_body_ns`. Then total `vt_produce_ack_wait` minus body = "dispatch + queue latency". You can call this `vt_callback_queue_delay`.
- Add these as new sub-stages to `AaLeaderBottleneckReporter.Stage` (or a sibling enum in a new reporter `AaVtProducerReporter`). Mark them `offLeaderWall=true` (since they run on producer IO thread, not leader thread).

### Step 5: Build the JMH jar
```
JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft \
PATH=/export/apps/jdk/JDK-17_0_5-msft/bin:$PATH \
nohup setsid bash -c '
  cd /home/coder/Projects/venice && \
  JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft \
  ./gradlew :internal:venice-test-common:jmhJar -x test
' > aa-phase6-build.log 2>&1 &
echo $! > aa-phase6-build.pid
disown
```
Poll every 30 sec with `tail -3 aa-phase6-build.log` until you see `BUILD SUCCESSFUL`. If `BUILD FAILED`, fix the compile errors and retry.

### Step 6: 3 measurement runs (Phase 6 ON)
Canonical command (do NOT change the JMH params):
```
nohup setsid bash -c '
  cd /home/coder/Projects/venice && \
  /export/apps/jdk/JDK-17_0_5-msft/bin/java \
    --add-opens=java.base/java.nio=ALL-UNNAMED \
    --add-opens=java.base/sun.nio.ch=ALL-UNNAMED \
    --add-opens=java.base/java.lang=ALL-UNNAMED \
    --add-opens=java.base/java.lang.invoke=ALL-UNNAMED \
    --add-opens=java.base/jdk.internal.misc=ALL-UNNAMED \
    -Xms2G -Xmx4G \
    -jar internal/venice-test-common/build/libs/venice-test-common-jmh.jar \
    ActiveActiveIngestionBenchmark \
    -p workloadType=PUT -wi 2 -w 20s -i 2 -r 20s -f 1 \
    -jvmArgs "-Xms32G -Xmx32G \
              -Dvenice.server.aa.bottleneck.instrumentation.enabled=true \
              -Dvenice.server.aa.dcr.merge.instrumentation.enabled=true \
              -Dvenice.server.aa.rmd.timestamp.cache.enabled=true \
              -Dvenice.server.aa.rmd.timestamp.cache.bloom.authoritative=false \
              -Dvenice.server.aa.leader.other.instrumentation.enabled=true \
              -Dvenice.server.aa.kafka.pipeline.instrumentation.enabled=true \
              -Dphase3.producers.per.region=2" \
    -prof gc
' > aa-phase6-runN.log 2>&1 &
echo $! > aa-phase6-runN.pid
disown
```
Run with N=1, 2, 3 sequentially (not in parallel — they share a port and a JVM-internal broker). Each run takes ~7 min. Poll-loop with 30s intervals and tail -3 aa-phase6-runN.log. Wait for `Run complete` line. Confirm `[E2E]` lines appear and `[BOTTLENECK]`, `[LEADER-OTHER]` (if you used a separate prefix), `[KAFKA-PIPELINE]` lines appear in the log.

### Step 7: 1 OFF run (criterion 6)
Same command but with both Phase 6 flags **OFF**:
```
... -Dvenice.server.aa.leader.other.instrumentation.enabled=false \
    -Dvenice.server.aa.kafka.pipeline.instrumentation.enabled=false \
... > aa-phase6-off.log 2>&1 &
```
Other instrumentation flags stay ON.

### Step 8: Integration tests (criterion 7)
```
nohup setsid bash -c '
  cd /home/coder/Projects/venice && \
  JAVA_HOME=/export/apps/jdk/JDK-17_0_5-msft \
  ./gradlew :internal:venice-test-common:integrationTest \
    --tests "com.linkedin.venice.endToEnd.ActiveActiveReplicationForHybridTest" \
    --tests "com.linkedin.venice.endToEnd.TestActiveActiveIngestion" \
    --rerun-tasks
' > aa-phase6-itest.log 2>&1 &
echo $! > aa-phase6-itest.pid
disown
```
Wait ~12 min. Confirm `BUILD SUCCESSFUL`. Verify `internal/venice-test-common/build/test-results/integrationTest/TEST-com.linkedin.venice.endToEnd.ActiveActiveReplicationForHybridTest.xml` and `...TestActiveActiveIngestion.xml` exist with 0 `<failure>` and 0 `<error>` tags.

### Step 9: Parse logs, decide named answer, write artifacts
- Parse `[BOTTLENECK]` and any `[LEADER-OTHER]` lines from each `aa-phase6-runN.log`.
- For each run, average across the steady-state ticks (coverage ≥85% and outer_calls ≥1M) and compute total named-coverage = sum(named on-leader-wall stages including new ones) / leader_record_wall_ns. **This must exceed 80%**, averaged across the 3 runs.
- Identify the dominant unnamed-or-newly-named stage.
- Parse `[KAFKA-PIPELINE]` lines to characterize: producer send rate vs consumer consume rate; broker ack roundtrip vs callback dispatch delay.
- Parse `[E2E]` from each ON run + the OFF run. Compute median throughput per side. Confirm ON-OFF |delta| < 5% (criterion 6).
- Determine answer (a/b/c):
  - (a) leader OTHER dominated: if the new sub-stages collectively account for >40% of leader_record_wall and one named new stage is >25% of wall.
  - (b) Kafka pipeline: if [KAFKA-PIPELINE] producer_send_rate ≪ leader consumer_records_consumed_rate, OR if vt_callback_queue_delay is the largest fraction of vt_produce_ack_wait.
  - (c) hybrid: if leader OTHER and Kafka are both meaningful.

### Step 10: Write `aa-phase6-result.json` (machine-readable)
Schema (mirror Phase 4 result JSON style):
```json
{
  "phase": 6,
  "study_goal": "Identify constraint at WC-off + RMD-cache-on combined-fix config: leader OTHER, Kafka pipeline, or hybrid.",
  "run_date_utc": "2026-04-25",
  "runs": {
    "aa-phase6-run1": {
      "e2e_ops_per_sec": [...],
      "e2e_median": ...,
      "leader_substages": { "<stage>": { "total_ns":..., "calls":..., "avg_ns":..., "pct_of_wall":... }, ... },
      "kafka_pipeline": {
        "producer_send_rate": ...,
        "request_latency_avg_ms": ...,
        "batch_size_avg_bytes": ...,
        "consumer_records_consumed_rate": ...,
        "vt_send_to_request_complete_ns_avg": ...,
        "vt_callback_queue_delay_ns_avg": ...
      },
      "named_coverage_pct": ...
    },
    "aa-phase6-run2": { ... },
    "aa-phase6-run3": { ... }
  },
  "cross_run_summary": {
    "named_coverage_pct_avg": ...,
    "top_new_substage": { "name": "...", "pct_of_wall": ... },
    "kafka_pipeline_summary": { ... }
  },
  "criterion_6_off_run": {
    "on_e2e_median": ...,
    "off_e2e_median": ...,
    "delta_pct": ...,
    "tolerance_pct": 5.0,
    "status": "PASS|MARGINAL|FAIL"
  },
  "criterion_7_itests": {
    "build_status": "SUCCESS|FAILED",
    "failures": 0,
    "errors": 0
  },
  "named_answer": "a|b|c",
  "named_answer_justification": "..."
}
```

### Step 11: Write `AA_LEADER_OTHER_AND_KAFKA_PHASE6_REPORT.md`
Sections: Headline finding (named answer + 1-line summary), Per-criterion status table, Sub-stage table (new + old, sorted by % of wall), Kafka pipeline numbers table, Reproducibility, OFF/ON measurement integrity, Existing tests pass, Plausible interventions, Files added/modified.

### Step 12: Commit and signal master
```
git add -- clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaLeaderBottleneckReporter.java \
            clients/da-vinci-client/src/main/java/com/linkedin/davinci/stats/AaKafkaPipelineReporter.java \
            clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ActiveActiveStoreIngestionTask.java \
            clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/LeaderProducerCallback.java \
            clients/da-vinci-client/src/main/java/com/linkedin/davinci/kafka/consumer/ConsumptionTask.java \
            internal/venice-test-common/src/jmh/java/com/linkedin/venice/benchmark/ActiveActiveIngestionBenchmark.java \
            aa-phase6-result.json AA_LEADER_OTHER_AND_KAFKA_PHASE6_REPORT.md aa-phase6-other-map.md
git commit --no-verify -m "[study][server] Phase 6: leader OTHER + Kafka pipeline decomposition; named answer is X"
```
(Adjust the file list to what you actually modified.)

Then write to `aa-phase6-status.txt`: `WAITING_VERIFY_iter_1` (one word, overwrite the file).

Append to `aa-phase6-progress.md`: a final summary line "Subagent complete. Named answer: X. Coverage: Y%. ON median: Z. OFF median: W. Itest: SUCCESS."

## Master verification (so you know what passes)

The master will independently verify these 8 criteria from disk:

1. ≥5 new stage labels in `AaLeaderBottleneckReporter.java` (or sibling reporter file).
2. Sum of named on-leader-wall stages > 80% of `leader_record_wall_ns`, averaged across 3 runs' steady-state ticks.
3. `[KAFKA-PIPELINE]` lines exist in each run log with both consumer (poll_wait/fetch decomp) and producer (record-send-rate, request-latency-avg, batch-size-avg) metrics.
4. `[E2E]` line exists in each of the 3 ON run logs.
5. The named answer (a/b/c) is supported by quantitative data in the report.
6. OFF-run E2E median within ±5% of 3-run-ON median.
7. Both integration test XMLs exist with 0 `<failure>` and 0 `<error>`.
8. Both `aa-phase6-result.json` AND `AA_LEADER_OTHER_AND_KAFKA_PHASE6_REPORT.md` exist and are non-trivial.

## Final note

If any step has issues, write a clear note to `aa-phase6-progress.md` describing the problem; do NOT silently skip. The master will spawn a follow-up subagent if some criteria fail.

Begin now. First action: revert the WC change in `ActiveActiveIngestionBenchmark.java`.
