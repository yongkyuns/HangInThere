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
import sys
from datetime import datetime, timezone
from pathlib import Path
try:
    from . import workout_benchmark as benchmark
except ImportError:
    import workout_benchmark as benchmark


class ReadinessError(ValueError):
    pass


# Decisions are human attestations backed by separately retained reports. This
# parser checks their binding and completeness; it cannot authenticate credentials
# or establish that the claimed field work actually occurred.
EXTERNAL_REVIEW_CHECKS = {
    "qualified_coach_review": ("qualified_coach", (
        "credentials_verified", "progression_rules_reviewed", "recommendation_limits_reviewed",
        "beginner_hold_and_partial_practice_reviewed")),
    "equipment_distance_and_gym_noise_usability": ("usability_reviewer", (
        "ios_equipment_distance_trials_completed", "android_equipment_distance_trials_completed",
        "ios_gym_noise_trials_completed", "android_gym_noise_trials_completed",
        "beginner_unaided_task_trials_completed", "start_stop_and_tracking_loss_recognized",
        "review_correction_and_next_focus_understood")),
    "consent_and_exposure_audit": ("data_auditor", (
        "capture_and_review_consent_verified", "participant_and_source_holdout_verified",
        "annotation_independent_of_runtime_output", "adjudication_completed",
        "model_and_developer_exposure_documented", "supported_conditions_verified")),
}


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


def review_subject(lock, score, devices, app_revision, coaching_spec_sha256):
    require(isinstance(app_revision, str) and re.fullmatch(r"[0-9a-f]{40}", app_revision), "Bind the full evaluated app revision")
    require(isinstance(coaching_spec_sha256, str) and re.fullmatch(r"[0-9a-f]{64}", coaching_spec_sha256), "Bind the reviewed coaching specification")
    return {"schema_version": 1, "app_revision": app_revision, "coaching_spec_sha256": coaching_spec_sha256,
            "policy_sha256": lock["policy_sha256"], "benchmark_sha256": lock["policy"]["benchmark_sha256"],
            "score_sha256": fingerprint(score), "device_matrix_sha256": fingerprint(devices)}


def prepare_reviews(lock, score, devices, app_revision, coaching_spec_sha256):
    # Use the existing validators even when the measurements legitimately fail.
    # Preparing a packet never turns those failures into approval.
    assess(lock, score, devices)
    subject = review_subject(lock, score, devices, app_revision, coaching_spec_sha256)
    return {"schema_version": 1, "subject": subject, "subject_sha256": fingerprint(subject),
            "reviews": [{"kind": kind, "status": "pending", "reviewer_id": None, "reviewer_role": role,
                         "human_review_confirmed": False, "independent_review_confirmed": False,
                         "reviewed_at_utc": None, "evidence_sha256": None, "reviewed_context_sha256s": [],
                         "checks": dict.fromkeys(checks, False)}
                        for kind, (role, checks) in EXTERNAL_REVIEW_CHECKS.items()]}


def assess_reviews(packet, subject, contexts):
    require(isinstance(packet, dict) and set(packet) == {"schema_version", "subject", "subject_sha256", "reviews"}, "Use the content-free review packet schema")
    require(type(packet["schema_version"]) is int and packet["schema_version"] == 1 and packet["subject"] == subject
            and fingerprint(packet["subject"]) == packet["subject_sha256"] == fingerprint(subject), "Reviews must bind the exact evaluated artifacts")
    reviews = packet["reviews"]
    require(isinstance(reviews, list) and len(reviews) <= len(EXTERNAL_REVIEW_CHECKS), "Invalid review decisions")
    required_contexts = {fingerprint(context) for context in contexts}
    fields = {"kind", "status", "reviewer_id", "reviewer_role", "human_review_confirmed", "independent_review_confirmed",
              "reviewed_at_utc", "evidence_sha256", "reviewed_context_sha256s", "checks"}
    seen, accepted, decisions = set(), set(), []
    for row in reviews:
        require(isinstance(row, dict) and set(row) == fields, "Use content-free review decisions, not report contents")
        kind = row["kind"]
        require(isinstance(kind, str) and kind in EXTERNAL_REVIEW_CHECKS and kind not in seen, "Unknown or duplicate review kind")
        seen.add(kind)
        role, checks = EXTERNAL_REVIEW_CHECKS[kind]
        require(row["status"] in ["pending", "approved", "rejected"], "Unknown human review decision")
        require(row["reviewer_role"] == role, "Reviewer role does not match the required review")
        require(row["reviewer_id"] is None or isinstance(row["reviewer_id"], str) and re.fullmatch(r"[A-Za-z0-9_-]{1,64}", row["reviewer_id"]), "Use a pseudonymous reviewer identifier")
        require(all(type(row[key]) is bool for key in ["human_review_confirmed", "independent_review_confirmed"]), "Explicit human and independent review declarations required")
        require(isinstance(row["checks"], dict) and set(row["checks"]) == set(checks) and all(type(value) is bool for value in row["checks"].values()), "Declare every required field-review check")
        reviewed_contexts = row["reviewed_context_sha256s"]
        require(isinstance(reviewed_contexts, list) and all(isinstance(pin, str) and pin in required_contexts for pin in reviewed_contexts) and len(set(reviewed_contexts)) == len(reviewed_contexts), "Bind reviewed supported conditions without duplicates")
        evidence = row["evidence_sha256"]
        require(evidence is None or isinstance(evidence, str) and re.fullmatch(r"[0-9a-f]{64}", evidence), "Pin the separately retained human report")
        reviewed_at = row["reviewed_at_utc"]
        require(reviewed_at is None or isinstance(reviewed_at, str) and re.fullmatch(r"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z", reviewed_at), "Use a UTC review timestamp")
        if reviewed_at is not None:
            try:
                when = datetime.strptime(reviewed_at, "%Y-%m-%dT%H:%M:%SZ").replace(tzinfo=timezone.utc)
            except ValueError as error:
                raise ReadinessError("Invalid review date") from error
            require(when <= datetime.now(timezone.utc), "A future review cannot qualify")
        reasons = []
        if row["status"] != "approved": reasons.append(row["status"])
        if row["reviewer_id"] is None or not row["human_review_confirmed"] or not row["independent_review_confirmed"]: reasons.append("eligible_human_review_required")
        if evidence is None or reviewed_at is None: reasons.append("dated_report_required")
        if set(reviewed_contexts) != required_contexts: reasons.append("supported_conditions_not_reviewed")
        if not all(row["checks"].values()): reasons.append("review_checks_incomplete")
        if not reasons: accepted.add(kind)
        # Never copy reviewer identities or human report contents to the output.
        decisions.append({"kind": kind, "status": row["status"], "attestation_complete": not reasons, "reasons": reasons})
    return {"scope": "Artifact-bound human attestations; credentials and field observations require independent verification",
            "subject_sha256": fingerprint(subject), "reviews_sha256": fingerprint(packet), "decisions": decisions,
            "remaining_external_reviews": [kind for kind in EXTERNAL_REVIEW_CHECKS if kind not in accepted]}


def assess(lock, score, devices, reviews=None, app_revision=None, coaching_spec_sha256=None):
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
    field_reviews = assess_reviews(reviews, review_subject(lock, score, devices, app_revision, coaching_spec_sha256), policy["supported_contexts"]) if reviews is not None else None
    remaining_reviews = field_reviews["remaining_external_reviews"] if field_reviews is not None else list(EXTERNAL_REVIEW_CHECKS)
    return {"schema_version": 1, "scope": "Predeclared evidence readiness; never waives existing CI or form/coaching qualification",
            "policy_sha256": lock["policy_sha256"], "benchmark_sha256": policy["benchmark_sha256"], "measurement_and_device_gates_pass": not failures,
            "failures": list(dict.fromkeys(failures)), "metrics": metrics, "supported_contexts": context_results, "device_matrix": matrix,
            "field_reviews": field_reviews, "remaining_external_reviews": remaining_reviews,
            "reviewed_qualification_gates_pass": not failures and not remaining_reviews}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["freeze", "assess", "prepare-reviews"]); parser.add_argument("policy", type=Path)
    parser.add_argument("--score", type=Path); parser.add_argument("--devices", type=Path); parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--reviews", type=Path); parser.add_argument("--app-revision"); parser.add_argument("--coaching-spec", type=Path)
    parser.add_argument("--require-field-reviews", action="store_true", help="Fail if any human review is missing, incomplete or rejected")
    args = parser.parse_args(argv)
    try:
        require(args.action == "assess" or (args.reviews is None and not args.require_field_reviews), "Review-required flags apply only to assessment")
        require(args.action != "freeze" or all(value is None for value in [args.score, args.devices, args.app_revision, args.coaching_spec]), "Freeze accepts only a policy and a fresh output")
        inputs = [path for path in [args.policy, args.score, args.devices, args.reviews, args.coaching_spec] if path is not None]
        require(all(args.output.resolve() != path.resolve() and not (args.output.exists() and path.exists() and args.output.samefile(path)) for path in inputs), "Output would overwrite an immutable input")
        policy = benchmark.read(args.policy)
        if args.action == "freeze": result = freeze(policy)
        else:
            require(args.score and args.devices, "Score and device matrix are required")
            score, devices = benchmark.read(args.score), benchmark.read(args.devices)
            spec_pin = hashlib.sha256(args.coaching_spec.read_bytes()).hexdigest() if args.coaching_spec else None
            if args.action == "prepare-reviews": result = prepare_reviews(policy, score, devices, args.app_revision, spec_pin)
            else: result = assess(policy, score, devices, benchmark.read(args.reviews) if args.reviews else None, args.app_revision, spec_pin)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        # Archive every result; a different existing audit artifact must also
        # survive even when it was not named among this invocation's inputs.
        with args.output.open("x") as output:
            json.dump(result, output, indent=2, allow_nan=False); output.write("\n")
        if args.action == "assess" and not result["reviewed_qualification_gates_pass" if args.require_field_reviews or args.reviews else "measurement_and_device_gates_pass"]: return 1
        return 0
    except (ValueError, OSError, KeyError, TypeError, OverflowError) as error:
        print(f"Workout release assessment rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__": raise SystemExit(main())
