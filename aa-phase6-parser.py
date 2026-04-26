#!/usr/bin/env python3
"""Phase 6 log parser. Reads aa-phase6-runN.log files and computes:
 - per-run sub-stage tables (named coverage averaged across steady-state ticks)
 - kafka pipeline summary (producer send rate, consumer consumed rate, etc.)
 - E2E throughput summary
"""
import json
import re
import statistics
import sys
from collections import defaultdict

E2E_RE = re.compile(r"\[E2E\] workload=PUT records=(\d+) elapsed_ms=(\d+) e2e_throughput_ops_per_sec=([\d.]+)")
BN_RE = re.compile(
    r"\[BOTTLENECK\] stage=([\w_]+) calls=(\d+) total_ns=(\d+) avg_ns=([\d.]+)"
)
BN_SUMMARY_RE = re.compile(
    r"\[BOTTLENECK-SUMMARY\] tick=(\d+) total_records=(\d+) wall_ns=(\d+) top3=(.+)"
)
# Phase 6 KAFKA-PIPELINE producer/consumer
KAFKA_PRODUCER_RE = re.compile(
    r"\[KAFKA-PIPELINE\] producer client_id=(\S+) record_send_rate=([-\d.e+]+) "
    r"request_latency_avg_ms=([-\d.e+]+) batch_size_avg_bytes=([-\d.e+]+) "
    r"records_per_request_avg=([-\d.e+]+) record_queue_time_avg_ms=([-\d.e+]+)"
)
KAFKA_CONSUMER_RE = re.compile(
    r"\[KAFKA-PIPELINE\] consumer client_id=(\S+) records_consumed_rate=([-\d.e+]+) "
    r"fetch_latency_avg_ms=([-\d.e+]+) fetch_size_avg_bytes=([-\d.e+]+) "
    r"records_per_request_avg=([-\d.e+]+)"
)

# On-leader-wall stages we treat as "named coverage". Existing + Phase 6.
ON_LEADER_WALL = [
    "rt_deserialize",
    "rmd_lookup_total",
    "dcr_merge",
    "value_serialize",
    "value_chunk",
    "transient_map_put",
    # Phase 6 newcomers (on-leader-wall):
    "lo_lazy_oldvalue_init",
    "lo_rmd_cache_decide",
    "lo_write_timestamp",
    "lo_put_dispatch",
    "lo_rmd_cache_remember",
    "lo_result_wrapper_alloc",
    "lo_sensor_calls",
    "lo_post_merge_sensors",
    "lo_internal_remainder",
]
PHASE6_LEADER = [
    "lo_lazy_oldvalue_init",
    "lo_rmd_cache_decide",
    "lo_write_timestamp",
    "lo_put_dispatch",
    "lo_rmd_cache_remember",
    "lo_result_wrapper_alloc",
    "lo_sensor_calls",
    "lo_post_merge_sensors",
    "lo_internal_remainder",
]


def parse_run(path):
    """Parse one runN.log file. Return dict with e2e_iters, ticks, kafka."""
    e2e = []
    # State for current tick: stage -> total_ns, calls
    cur = {}
    ticks = []
    kafka_producer_lines = []
    kafka_consumer_lines = []
    last_kafka_tick = {"producer": [], "consumer": []}
    with open(path, errors="ignore") as f:
        for line in f:
            m = E2E_RE.search(line)
            if m:
                e2e.append({
                    "records": int(m.group(1)),
                    "elapsed_ms": int(m.group(2)),
                    "ops_per_sec": float(m.group(3)),
                })
                continue
            m = BN_RE.search(line)
            if m:
                stage = m.group(1)
                calls = int(m.group(2))
                total_ns = int(m.group(3))
                avg_ns = float(m.group(4))
                cur[stage] = {"calls": calls, "total_ns": total_ns, "avg_ns": avg_ns}
                continue
            m = BN_SUMMARY_RE.search(line)
            if m:
                tick = int(m.group(1))
                total_records = int(m.group(2))
                wall_ns = int(m.group(3))
                top3 = m.group(4)
                ticks.append({
                    "tick": tick,
                    "total_records": total_records,
                    "wall_ns": wall_ns,
                    "top3": top3,
                    "stages": dict(cur),
                })
                cur = {}
                continue
            m = KAFKA_PRODUCER_RE.search(line)
            if m:
                last_kafka_tick["producer"].append({
                    "client_id": m.group(1),
                    "record_send_rate": float(m.group(2)),
                    "request_latency_avg_ms": float(m.group(3)),
                    "batch_size_avg_bytes": float(m.group(4)),
                    "records_per_request_avg": float(m.group(5)),
                    "record_queue_time_avg_ms": float(m.group(6)),
                })
                continue
            m = KAFKA_CONSUMER_RE.search(line)
            if m:
                last_kafka_tick["consumer"].append({
                    "client_id": m.group(1),
                    "records_consumed_rate": float(m.group(2)),
                    "fetch_latency_avg_ms": float(m.group(3)),
                    "fetch_size_avg_bytes": float(m.group(4)),
                    "records_per_request_avg": float(m.group(5)),
                })
                continue
            if "[KAFKA-PIPELINE-SUMMARY]" in line:
                # snapshot last_kafka_tick into kafka_xxx_lines
                kafka_producer_lines.append(last_kafka_tick["producer"])
                kafka_consumer_lines.append(last_kafka_tick["consumer"])
                last_kafka_tick = {"producer": [], "consumer": []}
    return {
        "path": path,
        "e2e": e2e,
        "ticks": ticks,
        "kafka_producer_ticks": kafka_producer_lines,
        "kafka_consumer_ticks": kafka_consumer_lines,
    }


def steady_ticks(run):
    """Filter to steady-state ticks: leader_record_wall_ns calls >= 1M and not first 1.
    Mirror Phase 4: coverage_pct >= 85% and outer_calls >= 1M.
    For Phase 6 we use leader_record_wall_ns calls >= 1M as the steady gate.
    """
    out = []
    for t in run["ticks"]:
        wall = t["stages"].get("leader_record_wall_ns")
        if wall is None:
            continue
        if wall["calls"] < 1_000_000:
            continue
        out.append(t)
    return out


def aggregate_run(run):
    steady = steady_ticks(run)
    if not steady:
        return None
    # Sum total_ns across steady ticks per stage, sum wall, sum calls
    totals = defaultdict(lambda: {"calls": 0, "total_ns": 0})
    wall_sum = 0
    for t in steady:
        wall_sum += t["stages"]["leader_record_wall_ns"]["total_ns"]
        for stage, s in t["stages"].items():
            totals[stage]["calls"] += s["calls"]
            totals[stage]["total_ns"] += s["total_ns"]
    leader = {}
    for stage, v in totals.items():
        avg = v["total_ns"] / v["calls"] if v["calls"] > 0 else 0.0
        pct = 100.0 * v["total_ns"] / wall_sum if wall_sum > 0 else 0.0
        leader[stage] = {
            "calls": v["calls"],
            "total_ns": v["total_ns"],
            "avg_ns": avg,
            "pct_of_wall": pct,
        }
    # Named coverage
    named_coverage = sum(
        leader.get(s, {"total_ns": 0})["total_ns"] for s in ON_LEADER_WALL
    )
    named_pct = 100.0 * named_coverage / wall_sum if wall_sum > 0 else 0.0
    phase6_total = sum(
        leader.get(s, {"total_ns": 0})["total_ns"] for s in PHASE6_LEADER
    )
    phase6_pct = 100.0 * phase6_total / wall_sum if wall_sum > 0 else 0.0
    return {
        "n_steady_ticks": len(steady),
        "wall_ns_sum": wall_sum,
        "leader": leader,
        "named_coverage_pct": named_pct,
        "phase6_coverage_pct": phase6_pct,
    }


def aggregate_kafka(run):
    """For each kafka tick (taken from the LAST steady tick batch — the most
    recent one with non-zero rates), pick the highest-rate producer / consumer
    client and report. Then average across the last 3 ticks for stability."""
    # Use only ticks where any producer record_send_rate > 100 (i.e., steady-state).
    candidate = []
    for prod_tick, cons_tick in zip(run["kafka_producer_ticks"], run["kafka_consumer_ticks"]):
        max_rate = max((p["record_send_rate"] for p in prod_tick), default=0.0)
        if max_rate > 100.0:
            candidate.append((prod_tick, cons_tick))
    if not candidate:
        return None
    # Use the last 3 ticks
    candidate = candidate[-3:]
    prod_aggregates = []
    cons_aggregates = []
    for prod_tick, cons_tick in candidate:
        # Sum producer record_send_rate across all clients (aggregate cluster send rate)
        total_send_rate = sum(p["record_send_rate"] for p in prod_tick if p["record_send_rate"] >= 0)
        # Pick the dominant producer (highest rate) for representative latency etc.
        if prod_tick:
            top_p = max(prod_tick, key=lambda x: x["record_send_rate"])
        else:
            top_p = None
        total_consume_rate = sum(c["records_consumed_rate"] for c in cons_tick if c["records_consumed_rate"] >= 0)
        # Pick the leader-AA-wc consumer (the one with highest rate, typically RT consumer)
        if cons_tick:
            top_c = max(cons_tick, key=lambda x: x["records_consumed_rate"])
        else:
            top_c = None
        prod_aggregates.append({
            "total_send_rate": total_send_rate,
            "top_producer": top_p,
        })
        cons_aggregates.append({
            "total_consume_rate": total_consume_rate,
            "top_consumer": top_c,
        })
    return {
        "ticks_used": len(candidate),
        "producer_total_send_rate_avg": statistics.mean(p["total_send_rate"] for p in prod_aggregates),
        "consumer_total_consume_rate_avg": statistics.mean(c["total_consume_rate"] for c in cons_aggregates),
        "producer_request_latency_avg_ms": statistics.mean(
            p["top_producer"]["request_latency_avg_ms"] for p in prod_aggregates if p["top_producer"]
        ),
        "producer_batch_size_avg_bytes": statistics.mean(
            p["top_producer"]["batch_size_avg_bytes"] for p in prod_aggregates if p["top_producer"]
        ),
        "producer_records_per_request_avg": statistics.mean(
            p["top_producer"]["records_per_request_avg"] for p in prod_aggregates if p["top_producer"]
        ),
        "producer_record_queue_time_avg_ms": statistics.mean(
            p["top_producer"]["record_queue_time_avg_ms"] for p in prod_aggregates if p["top_producer"]
        ),
        "consumer_fetch_latency_avg_ms": statistics.mean(
            c["top_consumer"]["fetch_latency_avg_ms"] for c in cons_aggregates if c["top_consumer"]
        ),
        "consumer_fetch_size_avg_bytes": statistics.mean(
            c["top_consumer"]["fetch_size_avg_bytes"] for c in cons_aggregates if c["top_consumer"]
        ),
    }


def main():
    runs = sys.argv[1:]
    results = {}
    for r in runs:
        run = parse_run(r)
        agg = aggregate_run(run)
        kafka = aggregate_kafka(run)
        # E2E iters: drop the warmup if benchmark printed exactly 4 — last 2 are measurement.
        # JMH prints 1 [E2E] per warmup iter (2) + 1 per measurement iter (2) = 4.
        if len(run["e2e"]) >= 4:
            measurement = run["e2e"][-2:]
        elif len(run["e2e"]) >= 2:
            measurement = run["e2e"][-2:]
        else:
            measurement = run["e2e"]
        e2e_ops = [e["ops_per_sec"] for e in measurement]
        e2e_median = statistics.median(e2e_ops) if e2e_ops else 0.0
        results[r] = {
            "e2e_ops_per_sec": e2e_ops,
            "e2e_median": e2e_median,
            "e2e_all_iters": [e["ops_per_sec"] for e in run["e2e"]],
            "n_steady_ticks": agg["n_steady_ticks"] if agg else 0,
            "leader_substages": (agg["leader"] if agg else {}),
            "named_coverage_pct": agg["named_coverage_pct"] if agg else 0.0,
            "phase6_coverage_pct": agg["phase6_coverage_pct"] if agg else 0.0,
            "kafka_pipeline": kafka,
        }
    print(json.dumps(results, indent=2, default=str))


if __name__ == "__main__":
    main()
