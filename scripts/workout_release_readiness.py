#!/usr/bin/env python3
"""Assess predeclared workout evidence gates without converting smoke data into field qualification.

Reads local aggregate score reports and explicit device-matrix rows only. Never
opens or uploads media. Missing measurements fail a gate rather than becoming zero.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import math
import re
from pathlib import Path
try:
    from . import workout_benchmark as benchmark
except ImportError:
    import workout_benchmark as benchmark


class ReadinessError(ValueError):
    pass


def require(condition, message):
    if not condition:
        raise ReadinessError(message)


def finite(value):
    return type(value) in (int, float) and math.isfinite(value)


def fingerprint(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()).hexdigest()


def freeze(policy):
    require(policy.get("schema_version") == 1 and policy.get("status") == "predeclared", "A predeclared policy is required")
    require(isinstance(policy.get("benchmark_sha256"), str) and re.fullmatch(r"[0-9a-f]{64}", policy["benchmark_sha256"]), "Bind the exact frozen benchmark")
    require(all(type(policy.get(k)) is int and policy[k] >= 2 for k in ["minimum_participants", "minimum_sessions"]), "Declare independent cohort minimums")
    limits = policy.get("limits", {})
    required = {"rep_precision", "rep_recall", "exact_set_count_fraction", "observation_coverage", "phase_agreement_all_labelled",
                "false_starts", "false_ends", "phase_boundary_p95_seconds", "hold_error_p95_seconds"}
    require(set(limits) == required and all(finite(v) and v >= 0 for v in limits.values()), "Declare every measurement gate")
    require(all(limits[k] <= 1 for k in required if k in {"rep_precision", "rep_recall", "exact_set_count_fraction", "observation_coverage", "phase_agreement_all_labelled"}), "Fraction limits must be within 0..1")
    contexts = policy.get("supported_contexts")
    require(isinstance(contexts, list) and contexts and all(isinstance(row, dict) and row and
        set(row) <= {"device_family", "camera_view", "lighting", "assistance", "apparatus"} and
        all(isinstance(v, str) and 0 < len(v) <= 100 for v in row.values()) for row in contexts), "Predeclare supported recording conditions")
    require(len({fingerprint(row) for row in contexts}) == len(contexts), "Duplicate supported conditions")
    performance = policy.get("performance", {})
    require(set(performance) == {"minimum_duration_seconds", "minimum_device_families_per_platform", "maximum_pipeline_p95_ms", "maximum_drop_fraction", "maximum_battery_fraction_per_hour", "maximum_thermal_level"}, "Declare physical-device gates")
    require(all(finite(v) and v >= 0 for k, v in performance.items() if k != "maximum_thermal_level"), "Invalid device thresholds")
    require(performance["minimum_duration_seconds"] > 0 and type(performance["minimum_device_families_per_platform"]) is int and performance["minimum_device_families_per_platform"] >= 1, "Sustained representative device evidence is required")
    require(performance["maximum_thermal_level"] in ["nominal", "fair", "serious", "critical"], "Invalid thermal gate")
    require(performance["maximum_drop_fraction"] <= 1 and performance["maximum_battery_fraction_per_hour"] <= 1, "Fraction budgets must be within 0..1")
    return {"schema_version": 1, "policy_sha256": fingerprint(policy), "policy": policy}


def device_matrix(rows, limits):
    severity = {"nominal": 0, "fair": 1, "serious": 2, "critical": 3}
    seen = set()
    results = []
    families = {"ios": set(), "android": set()}
    required = {"report_sha256", "platform", "device_family", "model_family", "scope", "duration_seconds", "pipeline_p95_ms", "drop_fraction", "battery_fraction_per_hour", "maximum_thermal_level", "camera_sensor_time_aligned", "consent_recorded"}
    for row in rows:
        require(set(row) == required, "Device matrix must be content-free with explicit scope")
        require(isinstance(row["report_sha256"], str) and re.fullmatch(r"[0-9a-f]{64}", row["report_sha256"]) and row["report_sha256"] not in seen, "Duplicate or invalid report pin")
        seen.add(row["report_sha256"])
        require(row["platform"] in families and row["scope"] in ["host", "emulator", "physical_live"], "Unknown device scope")
        require(all(isinstance(row[key], str) and 0 < len(row[key]) <= 64 for key in ["device_family", "model_family"]), "Use public family labels, never device identifiers")
        require(type(row["consent_recorded"]) is bool and type(row["camera_sensor_time_aligned"]) is bool, "Explicit consent/timing declarations required")
        for key in ["duration_seconds", "pipeline_p95_ms", "drop_fraction", "battery_fraction_per_hour"]:
            require(row[key] is None or finite(row[key]) and row[key] >= 0, "Invalid measured device value")
        require(row["maximum_thermal_level"] is None or row["maximum_thermal_level"] in severity, "Unknown measured thermal state")
        require(all(row[k] is None or row[k] <= 1 for k in ["drop_fraction", "battery_fraction_per_hour"]), "Invalid measured fraction")
        reasons = []
        if row["scope"] != "physical_live": reasons.append("physical_capture_required")
        if not row["consent_recorded"]: reasons.append("consent_missing")
        if not row["camera_sensor_time_aligned"]: reasons.append("camera_sensor_timing_unqualified")
        for key, threshold, lower in [("duration_seconds", "minimum_duration_seconds", True), ("pipeline_p95_ms", "maximum_pipeline_p95_ms", False),
                                      ("drop_fraction", "maximum_drop_fraction", False), ("battery_fraction_per_hour", "maximum_battery_fraction_per_hour", False)]:
            if row[key] is None or (row[key] < limits[threshold] if lower else row[key] > limits[threshold]): reasons.append(key + "_unqualified")
        thermal = row["maximum_thermal_level"]
        if thermal is None or severity[thermal] > severity[limits["maximum_thermal_level"]]: reasons.append("thermal_unqualified")
        if not reasons: families[row["platform"]].add(row["device_family"])
        results.append({"platform": row["platform"], "device_family": row["device_family"], "model_family": row["model_family"], "scope": row["scope"], "qualified": not reasons, "reasons": reasons})
    covered = all(len(values) >= limits["minimum_device_families_per_platform"] for values in families.values())
    return {"qualified": covered and bool(results) and all(row["qualified"] for row in results if row["scope"] == "physical_live"),
            "qualified_families": {k: sorted(v) for k, v in families.items()}, "rows": results}


def measurement_gates(summary, limits):
    failures = []
    metrics = {"rep_precision": summary.get("completed_cycles", {}).get("precision"), "rep_recall": summary.get("completed_cycles", {}).get("recall"),
        "exact_set_count_fraction": summary.get("exact_set_count_fraction"), "observation_coverage": summary.get("observation_coverage"),
        "phase_agreement_all_labelled": summary.get("phase_agreement_all_labelled"),
        "false_starts": summary.get("automatic_starts", {}).get("false_positives"), "false_ends": summary.get("automatic_ends", {}).get("false_positives"),
        "phase_boundary_p95_seconds": summary.get("phase_boundaries", {}).get("matched_error_seconds", {}).get("p95")}
    holds = [row.get("absolute_error_seconds", {}).get("p95") for row in summary.get("hold_duration", {}).values() if row.get("scored_sessions", 0) > 0]
    metrics["hold_error_p95_seconds"] = max(holds) if holds and all(finite(v) for v in holds) else None
    lower = {"rep_precision", "rep_recall", "exact_set_count_fraction", "observation_coverage", "phase_agreement_all_labelled"}
    for name, value in metrics.items():
        if not finite(value) or (value < limits[name] if name in lower else value > limits[name]): failures.append(name)
    if summary.get("failed_sessions") != 0: failures.append("failed_sessions")
    return metrics, failures


def assess(lock, score, devices):
    require(lock == freeze(lock["policy"]), "Policy lock was modified")
    policy = lock["policy"]
    require(score.get("benchmark_sha256") == policy["benchmark_sha256"], "Benchmark binding mismatch")
    failures = []
    if score.get("evidence_class") != "independently_reviewed_sessions" or score.get("split") != "test": failures.append("fresh_independent_test_split_required")
    summary = score.get("summary", {})
    rows = score.get("sessions", [])
    require(isinstance(rows, list) and summary.get("sessions") == len(rows), "Session aggregate disagrees with scored evidence")
    require(all(isinstance(row, dict) and isinstance(row.get("participant_group"), str) and row["participant_group"] and
        isinstance(row.get("source_group"), str) and row["source_group"] for row in rows), "Per-session anonymous cohort bindings required")
    require(type(score.get("participant_groups")) is int and type(score.get("source_groups")) is int and
            score["participant_groups"] == len({r["participant_group"] for r in rows}) and
            score["source_groups"] == len({r["source_group"] for r in rows}), "Invalid independent cohort counts")
    try:
        recomputed = benchmark.aggregate(rows)
        require(fingerprint(summary) == fingerprint(recomputed), "Aggregate does not match scored sessions")
    except (KeyError, TypeError, ValueError) as error:
        raise ReadinessError("Invalid scored-session evidence") from error
    if score["participant_groups"] < policy["minimum_participants"]: failures.append("participant_coverage")
    if score["source_groups"] < policy["minimum_sessions"]: failures.append("independent_session_coverage")
    metrics, measurement_failures = measurement_gates(recomputed, policy["limits"])
    failures.extend(measurement_failures)
    context_results = []
    for required_context in policy["supported_contexts"]:
        matching = [row for row in rows if all(row.get("context", {}).get(k) == v for k, v in required_context.items())]
        context_failures = []
        if len({r["participant_group"] for r in matching}) < policy["minimum_participants"] or len({r["source_group"] for r in matching}) < policy["minimum_sessions"]:
            context_failures.append("supported_context_coverage")
        context_metrics, subgroup_failures = measurement_gates(benchmark.aggregate(matching), policy["limits"])
        context_failures.extend(subgroup_failures)
        if context_failures: failures.append("supported_context_accuracy")
        if "supported_context_coverage" in context_failures: failures.append("supported_context_coverage")
        context_results.append({"context": required_context, "sessions": len(matching), "metrics": context_metrics, "failures": context_failures, "qualified": not context_failures})
    matrix = device_matrix(devices, policy["performance"])
    if not matrix["qualified"]: failures.append("physical_device_matrix")
    return {"schema_version": 1, "scope": "Predeclared evidence readiness; never waives existing CI or form/coaching qualification",
            "policy_sha256": lock["policy_sha256"], "benchmark_sha256": policy["benchmark_sha256"], "measurement_and_device_gates_pass": not failures,
            "failures": list(dict.fromkeys(failures)), "metrics": metrics, "supported_contexts": context_results, "device_matrix": matrix,
            "remaining_external_reviews": ["qualified_coach_review", "equipment_distance_and_gym_noise_usability", "consent_and_exposure_audit"]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["freeze", "assess"]); parser.add_argument("policy", type=Path)
    parser.add_argument("--score", type=Path); parser.add_argument("--devices", type=Path); parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    policy = json.loads(args.policy.read_text())
    if args.action == "freeze": result = freeze(policy)
    else:
        require(args.score and args.devices, "Score and device matrix are required")
        result = assess(policy, json.loads(args.score.read_text()), json.loads(args.devices.read_text()))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    if args.action == "assess" and not result["measurement_and_device_gates_pass"]: raise SystemExit(1)


if __name__ == "__main__": main()
