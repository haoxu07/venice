#!/usr/bin/env python3
"""
Phase 7 log parser. Reads aa-phase7-<exp>.log and emits a per-experiment
summary block usable by aa-phase7-finalize.py.

Captures:
  - E2E iterations (warmup + measurement)
  - [KAFKA-BROKER] tick lines (averages last 3 ticks per metric)
  - [KAFKA-PIPELINE] producer + consumer lines (averages of last 3 SUMMARY ticks)
  - [BOTTLENECK] poll counts (rt_poll_empty_count, rt_poll_full_count last)

Outputs a JSON dict on stdout.
"""
import json
import os
import re
import sys
from statistics import median


E2E_RE = re.compile(
    r"\[E2E\] workload=PUT records=(\d+) elapsed_ms=(\d+) e2e_throughput_ops_per_sec=([\d.]+)"
)
BROKER_LINE_RE = re.compile(r"\[KAFKA-BROKER\] tick=(\d+) (.*)$")
KV_RE = re.compile(r"(\w+)=([\-\w.+e]+)")
PIPELINE_PRODUCER_RE = re.compile(
    r"\[KAFKA-PIPELINE\] producer client_id=(\S+) record_send_rate=(\S+) request_latency_avg_ms=(\S+) "
    r"batch_size_avg_bytes=(\S+) records_per_request_avg=(\S+) record_queue_time_avg_ms=(\S+)"
)
PIPELINE_CONSUMER_RE = re.compile(
    r"\[KAFKA-PIPELINE\] consumer client_id=(\S+) records_consumed_rate=(\S+) "
    r"fetch_latency_avg_ms=(\S+) fetch_size_avg_bytes=(\S+) records_per_request_avg=(\S+)"
)
PIPELINE_SUMMARY_RE = re.compile(
    r"\[KAFKA-PIPELINE-SUMMARY\] tick=(\d+) producer_count=(\d+) consumer_count=(\d+)"
)

# Threshold for "steady" pipeline ticks (the workload phase). Each Phase 6/7 run has
# ~17 producer-metric MBeans during steady state and drops to 2-3 after teardown.
PIPELINE_STEADY_PRODUCER_COUNT = 10
# [BOTTLENECK] line example we want: "rt_poll_empty_count" and "rt_poll_full_count"
BN_STAGE_RE = re.compile(r"\[BOTTLENECK\] stage=(\S+) calls=(\d+)")


def num(s):
    if s in (None, "", "-1"):
        return -1.0
    try:
        return float(s)
    except ValueError:
        return -1.0


def parse_log(path):
    e2e = []  # list of dicts with records, elapsed_ms, ops_per_sec
    broker_ticks = []  # list of dicts of broker metrics keyed by name
    # Each pipeline tick stores both producer/consumer lists and the SUMMARY meta so we
    # can later filter to "steady" ticks where producer_count >= threshold.
    pipeline_ticks = []
    cur_prod = []
    cur_cons = []
    poll_empty = 0
    poll_full = 0

    with open(path, "r", errors="replace") as f:
        for line in f:
            m = E2E_RE.search(line)
            if m:
                e2e.append(
                    {
                        "records": int(m.group(1)),
                        "elapsed_ms": int(m.group(2)),
                        "ops_per_sec": float(m.group(3)),
                    }
                )
                continue
            m = BROKER_LINE_RE.search(line)
            if m:
                kv = dict(KV_RE.findall(m.group(2)))
                kv = {k: num(v) for k, v in kv.items()}
                kv["tick"] = int(m.group(1))
                broker_ticks.append(kv)
                continue
            m = PIPELINE_PRODUCER_RE.search(line)
            if m:
                cur_prod.append(
                    {
                        "client_id": m.group(1),
                        "record_send_rate": num(m.group(2)),
                        "request_latency_avg_ms": num(m.group(3)),
                        "batch_size_avg_bytes": num(m.group(4)),
                        "records_per_request_avg": num(m.group(5)),
                        "record_queue_time_avg_ms": num(m.group(6)),
                    }
                )
                continue
            m = PIPELINE_CONSUMER_RE.search(line)
            if m:
                cur_cons.append(
                    {
                        "client_id": m.group(1),
                        "records_consumed_rate": num(m.group(2)),
                        "fetch_latency_avg_ms": num(m.group(3)),
                        "fetch_size_avg_bytes": num(m.group(4)),
                        "records_per_request_avg": num(m.group(5)),
                    }
                )
                continue
            m = PIPELINE_SUMMARY_RE.search(line)
            if m:
                pipeline_ticks.append(
                    {
                        "tick": int(m.group(1)),
                        "producer_count": int(m.group(2)),
                        "consumer_count": int(m.group(3)),
                        "producers": cur_prod,
                        "consumers": cur_cons,
                    }
                )
                cur_prod, cur_cons = [], []
                continue
            m = BN_STAGE_RE.search(line)
            if m:
                if m.group(1) == "rt_poll_empty_count":
                    poll_empty = int(m.group(2))
                elif m.group(1) == "rt_poll_full_count":
                    poll_full = int(m.group(2))

    # Compute summary numbers
    e2e_warmup = [e["ops_per_sec"] for e in e2e[:2]]
    e2e_measure = [e["ops_per_sec"] for e in e2e[2:4]]
    e2e_median = median(e2e_measure) if e2e_measure else None
    e2e_all_pooled_median = median([e["ops_per_sec"] for e in e2e]) if e2e else None

    # Broker JMX: average last 3 ticks
    broker_summary = {}
    if broker_ticks:
        last3 = broker_ticks[-3:]
        keys = set()
        for t in last3:
            keys |= set(t.keys())
        keys.discard("tick")
        for k in keys:
            vals = [t[k] for t in last3 if t.get(k, -1.0) != -1.0]
            if vals:
                broker_summary[k] = sum(vals) / len(vals)
            else:
                broker_summary[k] = -1.0

    # Pipeline: total producer send rate / consumer consume rate, averaged over last 3
    # STEADY SUMMARY ticks (producer_count >= threshold), summing across all client_ids per tick.
    steady_ticks = [t for t in pipeline_ticks if t["producer_count"] >= PIPELINE_STEADY_PRODUCER_COUNT]
    last3 = steady_ticks[-3:]
    last3_prod = [t["producers"] for t in last3]
    last3_cons = [t["consumers"] for t in last3]

    def safe_sum(lst, key):
        return sum(d[key] for d in lst if d.get(key, -1.0) != -1.0)

    def safe_avg(lst, key):
        vals = [d[key] for d in lst if d.get(key, -1.0) != -1.0]
        return sum(vals) / len(vals) if vals else -1.0

    producer_total_send_rate_avg = (
        sum(safe_sum(t, "record_send_rate") for t in last3_prod) / len(last3_prod)
        if last3_prod
        else -1.0
    )
    consumer_total_consume_rate_avg = (
        sum(safe_sum(t, "records_consumed_rate") for t in last3_cons) / len(last3_cons)
        if last3_cons
        else -1.0
    )
    # Pick the producer with largest send rate from the last tick (the dominant one, likely the
    # leader's VT producer) as the representative for batch/queue time.
    rep_producer = None
    if last3_prod:
        flat = [d for t in last3_prod for d in t]
        if flat:
            rep_producer = max(flat, key=lambda d: d.get("record_send_rate", -1.0))
    record_queue_time_avg_ms = rep_producer.get("record_queue_time_avg_ms", -1.0) if rep_producer else -1.0
    request_latency_avg_ms = rep_producer.get("request_latency_avg_ms", -1.0) if rep_producer else -1.0
    batch_size_avg_bytes = rep_producer.get("batch_size_avg_bytes", -1.0) if rep_producer else -1.0
    producer_request_total_time_ms = -1.0
    if request_latency_avg_ms != -1.0:
        producer_request_total_time_ms = request_latency_avg_ms

    rep_consumer_fetch_latency = -1.0
    if last3_cons:
        flat = [d for t in last3_cons for d in t]
        if flat:
            rep_consumer_fetch_latency = max(flat, key=lambda d: d.get("records_consumed_rate", -1.0)).get(
                "fetch_latency_avg_ms", -1.0
            )

    poll_total = poll_empty + poll_full
    poll_empty_pct = (poll_empty / poll_total * 100.0) if poll_total else -1.0
    poll_full_pct = (poll_full / poll_total * 100.0) if poll_total else -1.0

    return {
        "log_path": path,
        "e2e_iters_all": [e["ops_per_sec"] for e in e2e],
        "e2e_warmup": e2e_warmup,
        "e2e_measurement": e2e_measure,
        "e2e_median": e2e_median,
        "e2e_all_pooled_median": e2e_all_pooled_median,
        "broker_jmx_last3_avg": broker_summary,
        "kafka_pipeline": {
            "ticks_used": len(last3_prod),
            "producer_total_send_rate_avg": producer_total_send_rate_avg,
            "consumer_total_consume_rate_avg": consumer_total_consume_rate_avg,
            "rep_producer_request_latency_avg_ms": request_latency_avg_ms,
            "rep_producer_batch_size_avg_bytes": batch_size_avg_bytes,
            "rep_producer_record_queue_time_avg_ms": record_queue_time_avg_ms,
            "rep_consumer_fetch_latency_avg_ms": rep_consumer_fetch_latency,
        },
        "poll_counts": {
            "rt_poll_empty_count": poll_empty,
            "rt_poll_full_count": poll_full,
            "poll_empty_pct": poll_empty_pct,
            "poll_full_pct": poll_full_pct,
        },
    }


if __name__ == "__main__":
    out = {}
    for p in sys.argv[1:]:
        if not os.path.exists(p):
            sys.stderr.write(f"missing: {p}\n")
            continue
        out[os.path.basename(p)] = parse_log(p)
    print(json.dumps(out, indent=2))
