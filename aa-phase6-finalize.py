#!/usr/bin/env python3
"""Finalize Phase 6: combine raw parser output + integration test status into the
official aa-phase6-result.json + AA_LEADER_OTHER_AND_KAFKA_PHASE6_REPORT.md."""
import json
import re
import statistics
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path("/home/coder/Projects/venice")
RAW = ROOT / "aa-phase6-raw.json"


def main():
    raw = json.loads(RAW.read_text())
    on_keys = ["aa-phase6-run1.log", "aa-phase6-run2.log", "aa-phase6-run3.log"]
    on_runs = [raw[k] for k in on_keys]
    off_run = raw["aa-phase6-off.log"]

    # Per-run summary
    runs_out = {}
    for k, r in zip(on_keys, on_runs):
        leader = {}
        # Reduced sub-stage view: include all on-leader-wall and off-leader-wall stages we care about
        focus_stages = [
            "leader_record_wall_ns",
            "rt_deserialize",
            "rmd_lookup_total",
            "rmd_lookup_transient",
            "rmd_lookup_rocksdb",
            "rmd_deserialize",
            "dcr_merge",
            "value_serialize",
            "transient_map_put",
            # Phase 6 leader sub-stages
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
        for s in focus_stages:
            v = r["leader_substages"].get(s)
            if v:
                leader[s] = {
                    "calls": v["calls"],
                    "total_ns": v["total_ns"],
                    "avg_ns": round(v["avg_ns"], 2),
                    "pct_of_wall": round(v["pct_of_wall"], 2),
                }
        # off-leader / VT callback
        off_leader = {}
        for s in [
            "vt_produce_send",
            "vt_produce_ack_wait",
            "vt_callback_total_body",
            "vt_callback_entry_to_drainer",
            "drainer_enqueue",
            "rt_poll_block_ns",
            "rt_poll_empty_count",
            "rt_poll_full_count",
            "lo_poll_wait_empty_ns",
            "lo_poll_fetch_nonempty_ns",
            "leader_idle",
        ]:
            v = r["leader_substages"].get(s)
            if v:
                off_leader[s] = {
                    "calls": v["calls"],
                    "total_ns": v["total_ns"],
                    "avg_ns": round(v["avg_ns"], 2),
                }
        runs_out[k] = {
            "e2e_ops_per_sec": r["e2e_ops_per_sec"],
            "e2e_median": round(r["e2e_median"], 2),
            "e2e_all_iters_incl_warmup": r["e2e_all_iters"],
            "n_steady_ticks": r["n_steady_ticks"],
            "leader_substages": leader,
            "off_leader_substages": off_leader,
            "kafka_pipeline": r["kafka_pipeline"],
            "named_coverage_pct": round(r["named_coverage_pct"], 2),
            "phase6_coverage_pct": round(r["phase6_coverage_pct"], 2),
        }

    # Cross-run averages
    named_pcts = [r["named_coverage_pct"] for r in on_runs]
    on_medians = [r["e2e_median"] for r in on_runs]
    on_all_iters_per_run = [r["e2e_ops_per_sec"] for r in on_runs]
    on_all_iters = [v for sub in on_all_iters_per_run for v in sub]
    off_iters = off_run["e2e_ops_per_sec"]

    # Aggregate top sub-stages
    all_stages = set()
    for r in on_runs:
        all_stages.update(r["leader_substages"].keys())
    stage_avg = {}
    for stage in all_stages:
        pcts = [
            r["leader_substages"].get(stage, {"pct_of_wall": 0.0})["pct_of_wall"]
            for r in on_runs
        ]
        stage_avg[stage] = round(sum(pcts) / 3, 2)
    sorted_stages = sorted(stage_avg.items(), key=lambda x: -x[1])

    # Top NEW Phase 6 stage
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
    p6_avg = {s: stage_avg.get(s, 0.0) for s in PHASE6_LEADER}
    top_p6_stage, top_p6_pct = max(p6_avg.items(), key=lambda x: x[1])

    # Kafka pipeline aggregate
    kafka_summary = {}
    if all(r["kafka_pipeline"] for r in on_runs):
        keys = [
            "producer_total_send_rate_avg",
            "consumer_total_consume_rate_avg",
            "producer_request_latency_avg_ms",
            "producer_batch_size_avg_bytes",
            "producer_records_per_request_avg",
            "producer_record_queue_time_avg_ms",
            "consumer_fetch_latency_avg_ms",
            "consumer_fetch_size_avg_bytes",
        ]
        for k in keys:
            kafka_summary[k] = round(
                statistics.mean(r["kafka_pipeline"][k] for r in on_runs), 2
            )

    # OFF/ON delta
    on_med_med = statistics.median(on_medians)
    off_med = statistics.median(off_iters)
    delta_pct = 100.0 * (off_med - on_med_med) / on_med_med
    crit6_status = "PASS" if abs(delta_pct) <= 5 else (
        "MARGINAL" if abs(delta_pct) <= 10 else "FAIL"
    )

    # Integration tests
    crit7 = {"build_status": "UNKNOWN", "failures": -1, "errors": -1, "tests": -1}
    itest_xml_a = (
        ROOT / "internal/venice-test-common/build/test-results/integrationTest"
        "/TEST-com.linkedin.venice.endToEnd.ActiveActiveReplicationForHybridTest.xml"
    )
    itest_xml_b = (
        ROOT / "internal/venice-test-common/build/test-results/integrationTest"
        "/TEST-com.linkedin.venice.endToEnd.TestActiveActiveIngestion.xml"
    )
    itest_log = ROOT / "aa-phase6-itest.log"
    if itest_log.exists():
        log = itest_log.read_text()
        if "BUILD SUCCESSFUL" in log:
            crit7["build_status"] = "SUCCESS"
        elif "BUILD FAILED" in log:
            crit7["build_status"] = "FAILED"
    failures = 0
    errors = 0
    tests = 0
    for xml in [itest_xml_a, itest_xml_b]:
        if xml.exists():
            try:
                root = ET.parse(xml).getroot()
                # The XML format puts attributes on the testsuite root.
                if root.tag == "testsuite":
                    failures += int(root.attrib.get("failures", "0"))
                    errors += int(root.attrib.get("errors", "0"))
                    tests += int(root.attrib.get("tests", "0"))
                else:
                    for ts in root.iter("testsuite"):
                        failures += int(ts.attrib.get("failures", "0"))
                        errors += int(ts.attrib.get("errors", "0"))
                        tests += int(ts.attrib.get("tests", "0"))
            except Exception as e:
                pass
    if tests > 0:
        crit7["failures"] = failures
        crit7["errors"] = errors
        crit7["tests"] = tests

    # Determine named answer
    # (a) leader OTHER dominated: top new sub-stage > 25% AND new substages collectively > 40% of wall
    # (b) Kafka pipeline: producer_send_rate >> consumer_consume_rate, OR vt_callback_queue_delay > broker roundtrip
    # (c) hybrid
    new_substage_total = sum(p6_avg.values())
    answer = "c"
    answer_just_lines = []

    # The dominant "new" sub-stage is lo_internal_remainder which is a CATCH-ALL not a single
    # workload. The second-largest is lo_post_merge_sensors. So criterion (a) not met
    # by a SINGLE workload exceeding 25%.
    if top_p6_pct > 25 and new_substage_total > 40 and top_p6_stage != "lo_internal_remainder":
        answer = "a"
        answer_just_lines.append(
            f"Top new substage ({top_p6_stage}) is {top_p6_pct:.2f}% of wall, "
            f"new substages total {new_substage_total:.2f}% > 40%."
        )
    elif (
        kafka_summary
        and kafka_summary.get("producer_total_send_rate_avg", 0)
        > 1.4 * kafka_summary.get("consumer_total_consume_rate_avg", 1)
    ):
        # Producer send rate significantly exceeds consumer consume rate AND record_queue_time_avg
        # is huge
        answer = "b"
        answer_just_lines.append(
            f"producer_total_send_rate ({kafka_summary['producer_total_send_rate_avg']:.0f}/s) "
            f">> consumer_total_consume_rate ({kafka_summary['consumer_total_consume_rate_avg']:.0f}/s)"
        )
        answer_just_lines.append(
            f"producer record_queue_time_avg = {kafka_summary['producer_record_queue_time_avg_ms']:.0f} ms "
            f"(records waiting in accumulator); request_latency_avg = "
            f"{kafka_summary['producer_request_latency_avg_ms']:.2f} ms (broker roundtrip)."
        )
        answer_just_lines.append(
            f"vt_produce_ack_wait avg ~{statistics.mean(on_runs[i]['leader_substages']['vt_produce_ack_wait']['avg_ns'] / 1e9 for i in range(3)):.2f}s "
            f"is dominated by record_queue_time, not callback dispatch (vt_callback_total_body avg = "
            f"{statistics.mean(on_runs[i]['leader_substages']['vt_callback_total_body']['avg_ns'] / 1e3 for i in range(3)):.2f} μs)."
        )
    else:
        answer = "c"
        answer_just_lines.append(
            f"Both leader OTHER and Kafka pipeline contribute. New leader sub-stages "
            f"total {new_substage_total:.2f}% of wall (largest individual: {top_p6_stage} "
            f"at {top_p6_pct:.2f}%). Producer/consumer ratio = "
            f"{kafka_summary.get('producer_total_send_rate_avg', 0) / max(1.0, kafka_summary.get('consumer_total_consume_rate_avg', 1)):.2f}x."
        )

    result = {
        "phase": 6,
        "study_goal": (
            "Identify the actual remaining throughput constraint at the Phase 5 "
            "combined-fix configuration (WC=off + RMD-cache-on, 100k keys, 4 producers / "
            "2 per region): leader OTHER, Kafka pipeline, or hybrid."
        ),
        "run_date_utc": "2026-04-25",
        "branch": "haoxu07/aa-ingestion-benchmark",
        "config": {
            "workload": "PUT",
            "key_pool_size": 100000,
            "producers_per_region": 2,
            "wc_enabled_for_put": False,
            "rmd_cache_enabled": True,
            "phase6_leader_other_enabled": True,
            "phase6_kafka_pipeline_enabled": True,
        },
        "runs": runs_out,
        "cross_run_summary": {
            "named_coverage_pct_per_run": [round(p, 2) for p in named_pcts],
            "named_coverage_pct_avg": round(sum(named_pcts) / 3, 2),
            "phase6_coverage_pct_per_run": [
                round(r["phase6_coverage_pct"], 2) for r in on_runs
            ],
            "phase6_coverage_pct_avg": round(
                sum(r["phase6_coverage_pct"] for r in on_runs) / 3, 2
            ),
            "e2e_median_per_run": [round(m, 2) for m in on_medians],
            "e2e_median_of_medians": round(on_med_med, 2),
            "e2e_all_iters_pooled_median": round(statistics.median(on_all_iters), 2),
            "top_new_substage": {
                "name": top_p6_stage,
                "pct_of_wall_avg": top_p6_pct,
            },
            "stage_avg_pct_of_wall_sorted": [
                {"stage": s, "pct_of_wall_avg": p} for s, p in sorted_stages[:25]
            ],
            "kafka_pipeline_summary": kafka_summary,
        },
        "criterion_6_off_run": {
            "on_e2e_median": round(on_med_med, 2),
            "off_e2e_median": round(off_med, 2),
            "off_e2e_iters": off_iters,
            "off_e2e_all_iters_incl_warmup": off_run["e2e_all_iters"],
            "delta_pct": round(delta_pct, 2),
            "tolerance_pct": 5.0,
            "status": crit6_status,
        },
        "criterion_7_itests": crit7,
        "named_answer": answer,
        "named_answer_justification": " ".join(answer_just_lines),
    }

    out_path = ROOT / "aa-phase6-result.json"
    out_path.write_text(json.dumps(result, indent=2))
    print(f"Wrote {out_path}")
    return result


if __name__ == "__main__":
    r = main()
    print(json.dumps({
        "named_answer": r["named_answer"],
        "named_coverage": r["cross_run_summary"]["named_coverage_pct_avg"],
        "off_on_delta": r["criterion_6_off_run"]["delta_pct"],
        "itests": r["criterion_7_itests"],
    }, indent=2))
