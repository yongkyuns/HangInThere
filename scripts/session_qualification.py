#!/usr/bin/env python3
"""Validate and summarize whole-workout session qualification evidence.

Stdlib-only. This tool is descriptive: it reports session-level product metrics
without changing runtime thresholds or producing an automatic release verdict.
"""

from __future__ import annotations

import argparse
import json
import math
import pathlib
import re
import statistics
from collections import Counter
from typing import Any, Iterable, Optional, Sequence

EVIDENCE_CLASSES = {"development", "heldout_consumed", "field"}
EXERCISES = {"pull_up", "dip"}


class SessionError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SessionError(message)


def integer(value: Any, minimum: int = 0) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= minimum


def finite_number(value: Any) -> Optional[float]:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        return None
    value = float(value)
    return value if math.isfinite(value) else None


def fraction(numerator: int, denominator: int) -> Optional[float]:
    return numerator / denominator if denominator else None


def load(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise SessionError(f"{path}: {exc}") from exc
    require(isinstance(value, dict), "top-level JSON value must be an object")
    return value


def validate(manifest: dict[str, Any]) -> list[dict[str, Any]]:
    require(manifest.get("schema_version") == 1, "unsupported schema_version")
    sessions = manifest.get("sessions")
    require(isinstance(sessions, list) and sessions, "sessions must be a nonempty array")

    seen: set[str] = set()
    for index, session in enumerate(sessions):
        prefix = f"sessions[{index}]"
        require(isinstance(session, dict), f"{prefix} must be an object")
        identifier = session.get("id")
        require(
            isinstance(identifier, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,100}", identifier),
            f"{prefix}.id is invalid",
        )
        require(identifier not in seen, f"duplicate session id {identifier}")
        seen.add(identifier)

        require(session.get("exercise") in EXERCISES, f"{identifier}: invalid exercise")
        evidence = session.get("evidence_class")
        require(evidence in EVIDENCE_CLASSES, f"{identifier}: invalid evidence_class")
        population_eligible = session.get("population_eligible")
        require(type(population_eligible) is bool, f"{identifier}: population_eligible must be boolean")
        require(
            not population_eligible or evidence == "field",
            f"{identifier}: only field sessions may be population_eligible",
        )

        for key in ("source_group", "participant_group"):
            value = session.get(key)
            require(
                value is None or isinstance(value, str) and value.strip(),
                f"{identifier}: invalid {key}",
            )

        expected = session.get("expected_movements")
        require(
            expected is None or integer(expected),
            f"{identifier}: expected_movements must be null or a nonnegative integer",
        )
        for key in ("observed_movements", "partial_attempts", "interrupted_attempts"):
            require(integer(session.get(key)), f"{identifier}: invalid {key}")

        analyzed = session.get("analyzed_frames")
        usable = session.get("usable_tracking_frames")
        require(integer(analyzed, 1), f"{identifier}: analyzed_frames must be positive")
        require(
            integer(usable) and usable <= analyzed,
            f"{identifier}: usable_tracking_frames must be within analyzed_frames",
        )

        setup = session.get("bar_setup")
        require(isinstance(setup, dict), f"{identifier}: bar_setup must be an object")
        require(type(setup.get("required")) is bool, f"{identifier}: bar_setup.required must be boolean")
        require(type(setup.get("succeeded")) is bool, f"{identifier}: bar_setup.succeeded must be boolean")
        require(integer(setup.get("attempts")), f"{identifier}: invalid bar_setup.attempts")
        if setup["required"]:
            require(setup["attempts"] >= 1, f"{identifier}: required bar setup needs at least one attempt")
        else:
            require(setup["attempts"] == 0, f"{identifier}: non-required bar setup must have zero attempts")
            require(not setup["succeeded"], f"{identifier}: non-required bar setup cannot be marked succeeded")

        camera = session.get("camera_stability")
        require(isinstance(camera, dict), f"{identifier}: camera_stability must be an object")
        for key in ("false_interruptions", "deliberate_events", "detected_deliberate_events"):
            require(integer(camera.get(key)), f"{identifier}: invalid camera_stability.{key}")
        require(
            camera["detected_deliberate_events"] <= camera["deliberate_events"],
            f"{identifier}: detected deliberate camera events exceed deliberate events",
        )

        tags = session.get("tags", [])
        require(
            isinstance(tags, list)
            and all(isinstance(tag, str) and tag.strip() for tag in tags)
            and len(tags) == len(set(tags)),
            f"{identifier}: tags must be unique nonempty strings",
        )
    return sessions


def percentile(values: Iterable[float], q: float) -> Optional[float]:
    ordered = sorted(v for v in values if math.isfinite(v))
    if not ordered:
        return None
    index = round((len(ordered) - 1) * min(1.0, max(0.0, q)))
    return ordered[index]


def summarize(sessions: Sequence[dict[str, Any]]) -> dict[str, Any]:
    count_labeled = [s for s in sessions if s.get("expected_movements") is not None]
    exact = [s for s in count_labeled if s["observed_movements"] == s["expected_movements"]]
    expected = sum(int(s["expected_movements"]) for s in count_labeled)
    observed = sum(int(s["observed_movements"]) for s in count_labeled)
    matched = sum(min(int(s["expected_movements"]), int(s["observed_movements"])) for s in count_labeled)
    missed = sum(max(int(s["expected_movements"]) - int(s["observed_movements"]), 0) for s in count_labeled)
    extra = sum(max(int(s["observed_movements"]) - int(s["expected_movements"]), 0) for s in count_labeled)

    analyzed = sum(int(s["analyzed_frames"]) for s in sessions)
    usable = sum(int(s["usable_tracking_frames"]) for s in sessions)
    tracking = [s["usable_tracking_frames"] / s["analyzed_frames"] for s in sessions]

    interrupted_sets = sum(int(s["interrupted_attempts"] > 0) for s in sessions)
    setup_required = [s for s in sessions if s["bar_setup"]["required"]]
    setup_success = sum(int(s["bar_setup"]["succeeded"]) for s in setup_required)
    setup_attempts = [int(s["bar_setup"]["attempts"]) for s in setup_required]

    false_camera = sum(int(s["camera_stability"]["false_interruptions"]) for s in sessions)
    deliberate = sum(int(s["camera_stability"]["deliberate_events"]) for s in sessions)
    deliberate_detected = sum(int(s["camera_stability"]["detected_deliberate_events"]) for s in sessions)

    return {
        "session_count": len(sessions),
        "count_labeled_sessions": len(count_labeled),
        "exact_count_sessions": len(exact),
        "exact_count_fraction": fraction(len(exact), len(count_labeled)),
        "expected_movements": expected,
        "observed_movements": observed,
        "matched_movements": matched,
        "missed_movements": missed,
        "extra_movements": extra,
        "rep_recall": fraction(matched, expected),
        "extra_movements_per_100_expected": (100 * extra / expected) if expected else None,
        "sets_with_interruptions": interrupted_sets,
        "interruption_set_fraction": fraction(interrupted_sets, len(sessions)),
        "partial_attempts": sum(int(s["partial_attempts"]) for s in sessions),
        "tracking_coverage_weighted": fraction(usable, analyzed),
        "tracking_coverage_median": statistics.median(tracking) if tracking else None,
        "tracking_coverage_p05": percentile(tracking, 0.05),
        "tracking_coverage_minimum": min(tracking) if tracking else None,
        "bar_setup_required_sessions": len(setup_required),
        "bar_setup_successes": setup_success,
        "bar_setup_success_fraction": fraction(setup_success, len(setup_required)),
        "bar_setup_mean_attempts": statistics.mean(setup_attempts) if setup_attempts else None,
        "false_camera_interruptions": false_camera,
        "false_camera_interruptions_per_session": fraction(false_camera, len(sessions)),
        "deliberate_camera_events": deliberate,
        "detected_deliberate_camera_events": deliberate_detected,
        "camera_event_detection_fraction": fraction(deliberate_detected, deliberate),
    }


def analyze(manifest: dict[str, Any]) -> dict[str, Any]:
    sessions = validate(manifest)
    field = [s for s in sessions if s["population_eligible"]]
    by_exercise = {
        exercise: summarize([s for s in sessions if s["exercise"] == exercise])
        for exercise in sorted({s["exercise"] for s in sessions})
    }
    tags = sorted({tag for s in sessions for tag in s.get("tags", [])})
    by_tag = {tag: summarize([s for s in sessions if tag in s.get("tags", [])]) for tag in tags}
    return {
        "schema_version": 1,
        "scope": "whole-session descriptive qualification; no automatic release verdict",
        "evidence_class_counts": dict(Counter(s["evidence_class"] for s in sessions)),
        "all_sessions": summarize(sessions),
        "population_eligible_sessions": summarize(field),
        "by_exercise": by_exercise,
        "by_tag": by_tag,
    }


def fmt_fraction(value: Optional[float]) -> str:
    return "—" if value is None else f"{100 * value:.1f}%"


def render_markdown(report: dict[str, Any]) -> str:
    def row(label: str, metrics: dict[str, Any]) -> str:
        exact = fmt_fraction(metrics["exact_count_fraction"])
        recall = fmt_fraction(metrics["rep_recall"])
        tracking = fmt_fraction(metrics["tracking_coverage_weighted"])
        setup = fmt_fraction(metrics["bar_setup_success_fraction"])
        return (
            f"| {label} | {metrics['session_count']} | {exact} | {recall} | "
            f"{metrics['extra_movements']} | {tracking} | "
            f"{metrics['sets_with_interruptions']} | {setup} | "
            f"{metrics['false_camera_interruptions']} |"
        )

    lines = [
        "# Session qualification summary",
        "",
        "| Cohort | Sessions | Exact-count sets | Rep recall | Extra reps | Tracking | Interrupted sets | Bar setup | False camera interrupts |",
        "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |",
        row("All evidence", report["all_sessions"]),
        row("Population-eligible field", report["population_eligible_sessions"]),
        "",
        "Population-eligible metrics intentionally exclude development and consumed held-out evidence.",
        "This report is descriptive evidence, not an automatic release verdict.",
    ]
    return "\n".join(lines) + "\n"


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=pathlib.Path)
    parser.add_argument("--output", type=pathlib.Path, required=True)
    parser.add_argument("--markdown", type=pathlib.Path)
    args = parser.parse_args(argv)
    try:
        report = analyze(load(args.manifest))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        if args.markdown:
            args.markdown.parent.mkdir(parents=True, exist_ok=True)
            args.markdown.write_text(render_markdown(report), encoding="utf-8")
        return 0
    except (SessionError, OSError, ValueError, TypeError) as exc:
        print(f"Session qualification rejected: {exc}", file=__import__("sys").stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
