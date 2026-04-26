# Phase 7 progress log

Master = top-level Claude Code session (Option B verify-and-respawn loop).
Goal prompt: /home/coder/AA_KAFKA_TUNING_PHASE7_PROMPT.md

## Iteration 1 (started)
- Repo HEAD: 0c5ea4930 (Phase 6 final)
- Worker subagent spawned by master to run Phase 7 Kafka config tuning study
- Required: baseline + 5+ experiments + broker JMX + named conclusion

### 2026-04-25 14:00 UTC — Plan
1. Add `AaKafkaBrokerReporter.java` (sibling of AaKafkaPipelineReporter) to scrape
   broker JMX MBeans every 20s, gated by `venice.server.aa.kafka.broker.jmx.enabled`.
2. Wire per-experiment sysprops in `ActiveActiveIngestionBenchmark.setUp()`:
   - `venice.kafka.linger.ms` -> producer prop `kafka.linger.ms` via Samza optionalConfigs
   - `venice.kafka.batch.size` -> `kafka.batch.size`
   - `venice.kafka.compression.type` -> `kafka.compression.type`
   - reuse `phase3.server.max.poll.records` -> SERVER_KAFKA_MAX_POLL_RECORDS
3. Inject broker-side num.io.threads / num.network.threads via sysprop in
   `KafkaBrokerFactory.generateService()`.
4. Build jmh jar, run 5+ canonical experiments.
5. Parse logs, build comparison table, name conclusion.

### 2026-04-25 14:50 UTC — Build + initial experiments
- jmh jar build: BUILD SUCCESSFUL in 1m 8s. Jar: 330MB at
  internal/venice-test-common/build/libs/venice-test-common-jmh.jar.
- baseline: median(measure)=median(144430.76, 140164.68) = **142297.72 ops/s**
  (warmup 129833.21, 130450.51). 9 [KAFKA-BROKER] ticks emitted, handler_idle
  steady around 2.91 (with broker_count=1 due to MBean naming, but the absolute
  value indicates **highly idle** request handler pool).
- expA (linger.ms=5): median(measure)=median(134755, 128975) = **131865 ops/s**
  (Δ vs baseline = -7.3%). Linger reduction did NOT lift the cap.
- expB (max.poll.records=5000): median(measure)=median(130432, 129132) =
  **129782 ops/s** (Δ vs baseline = -8.8%). Slightly worse — broker isn't
  the bottleneck on the consumer side either.
- Both expA and expB land within ±10% of baseline — broker JMX picture matches
  the (b) shape: client-side knobs do not move E2E. Continuing with C, D, E.

### 2026-04-25 16:10 UTC — All experiments complete + report written
- expC (linger=5 + max.poll=5000): median(measure)=130712 (-8.14%)
- expD (compression=lz4): median(measure)=126889 (-10.83%)
- expE (broker num.io.threads=16): median(measure)=141789 (-0.36%)
- Final ranking by E2E:
    baseline 142298 / Exp.E 141789 / Exp.A 131865 / Exp.C 130712 / Exp.B 129783 / Exp.D 126889
- Named answer = **(b)**: no knob moves E2E by >= +5%. Max upward delta = +0.00%.
- KEY broker JMX evidence: handler_idle averaged 2.60 across all experiments
  (with 3 brokers in JVM = ~0.87 idle/broker = 87% spare capacity).
- Wrote aa-phase7-result.json + AA_KAFKA_TUNING_PHASE7_REPORT.md.
- Integration tests running in background (aa-phase7-itest.log).

## 2026-04-26T06:20:00Z — Master DONE
All 8 criteria independently verified. aa-phase7-DONE.txt written.
