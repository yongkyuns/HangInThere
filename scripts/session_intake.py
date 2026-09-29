#!/usr/bin/env python3
"""Build sanitized whole-session qualification records from device reports.

The runtime report supplies measured counters only. A separate reviewer record
supplies expected movement count and field-context labels. This keeps ground truth
independent from model output and avoids copying media or private identifiers into
qualification summaries.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import math
import pathlib
import re
import sys
from typing import Any, Optional, Sequence

SCRIPTS = pathlib.Path(__file__).resolve().parent
_SPEC = importlib.util.spec_from_file_location(
    "device_qualification_analysis",
    SCRIPTS / "analyze_device_qualification.py",
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("could not load device qualification validator")
device_analysis = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(device_analysis)

EVIDENCE_CLASSES = {"development", "heldout_consumed", "field"}
EXERCISES = {"pull_up", "dip"}
RUNTIME_END_REASONS = {
    "manual",
    "appInactive",
    "cameraInterrupted",
    "cameraFailure",
    "setupInvalidated",
    "phoneMoved",
    "sceneShifted",
    "sceneScaled",
}


class IntakeError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise IntakeError(message)


def integer(value: Any, minimum: int = 0) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value >= minimum


def load(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise IntakeError(f"{path}: {exc}") from exc
    require(isinstance(value, dict), f"{path}: top-level JSON value must be an object")
    return value


def validate_review(review: dict[str, Any]) -> None:
    require(review.get("schema_version") == 1, "unsupported review schema_version")
    identifier = review.get("id")
    require(
        isinstance(identifier, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,100}", identifier),
        "invalid session id",
    )
    require(review.get("exercise") in EXERCISES, "invalid exercise")
    evidence = review.get("evidence_class")
    require(evidence in EVIDENCE_CLASSES, "invalid evidence_class")
    population = review.get("population_eligible")
    require(type(population) is bool, "population_eligible must be boolean")
    require(not population or evidence == "field", "only field evidence may be population eligible")

    independent = review.get("reviewed_without_runtime_output")
    require(type(independent) is bool, "reviewed_without_runtime_output must be boolean")
    if population:
        require(
            independent,
            "population-eligible ground truth must be reviewed without runtime output",
        )

    for key in ("source_group", "participant_group"):
        value = review.get(key)
        require(
            value is None or isinstance(value, str) and value.strip(),
            f"invalid {key}",
        )
    if population:
        require(
            isinstance(review.get("source_group"), str) and review["source_group"].strip(),
            "population-eligible session requires source_group",
        )
        require(
            isinstance(review.get("participant_group"), str)
            and review["participant_group"].strip(),
            "population-eligible session requires participant_group",
        )

    require(integer(review.get("expected_movements")), "expected_movements must be nonnegative")

    bar = review.get("bar_setup")
    require(isinstance(bar, dict), "bar_setup must be an object")
    require(type(bar.get("required")) is bool, "bar_setup.required must be boolean")
    require(type(bar.get("succeeded")) is bool, "bar_setup.succeeded must be boolean")
    require(integer(bar.get("attempts")), "bar_setup.attempts must be nonnegative")
    if bar["required"]:
        require(bar["attempts"] >= 1, "required bar setup needs at least one attempt")
    else:
        require(bar["attempts"] == 0, "non-required bar setup must have zero attempts")
        require(not bar["succeeded"], "non-required bar setup cannot be marked succeeded")

    camera = review.get("camera_stability")
    require(isinstance(camera, dict), "camera_stability must be an object")
    for key in ("false_interruptions", "deliberate_events", "detected_deliberate_events"):
        require(integer(camera.get(key)), f"camera_stability.{key} must be nonnegative")
    require(
        camera["detected_deliberate_events"] <= camera["deliberate_events"],
        "detected deliberate camera events exceed deliberate events",
    )

    tags = review.get("tags", [])
    require(
        isinstance(tags, list)
        and all(isinstance(tag, str) and tag.strip() for tag in tags)
        and len(tags) == len(set(tags)),
        "tags must be unique nonempty strings",
    )


def validate_runtime_report(report: dict[str, Any]) -> None:
    try:
        device_analysis.validate_report(report)
    except Exception as exc:
        raise IntakeError(f"invalid device qualification report: {exc}") from exc

    for key in (
        "partialAttempts",
        "interruptedAttempts",
        "setAnalyzedFrames",
        "setUsableTrackingFrames",
    ):
        require(integer(report.get(key)), f"device report missing/invalid {key}")

    analyzed = report["setAnalyzedFrames"]
    usable = report["setUsableTrackingFrames"]
    require(usable <= analyzed, "setUsableTrackingFrames exceeds setAnalyzedFrames")
    require(integer(report.get("observedMovements")), "device report observedMovements is invalid")

    coverage = report.get("trackingCoverage")
    if analyzed == 0:
        require(coverage is None, "zero-frame set must have null trackingCoverage")
    else:
        require(
            isinstance(coverage, (int, float))
            and not isinstance(coverage, bool)
            and math.isfinite(float(coverage))
            and 0 <= float(coverage) <= 1,
            "trackingCoverage must be a finite unit-interval number",
        )
        expected = usable / analyzed
        require(
            abs(float(coverage) - expected) <= 1e-9,
            "trackingCoverage disagrees with set-specific frame counts",
        )

    require(report.get("setPhase") == "finished", "device report must represent a finished set")
    require(
        report.get("setEndReason") in RUNTIME_END_REASONS,
        "finished device report needs an explicit recognized setEndReason",
    )


def build_session(review: dict[str, Any], report: dict[str, Any]) -> dict[str, Any]:
    validate_review(review)
    validate_runtime_report(report)
    return {
        "id": review["id"],
        "exercise": review["exercise"],
        "evidence_class": review["evidence_class"],
        "population_eligible": review["population_eligible"],
        "reviewed_without_runtime_output": review["reviewed_without_runtime_output"],
        "source_group": review.get("source_group"),
        "participant_group": review.get("participant_group"),
        "expected_movements": review["expected_movements"],
        "observed_movements": report["observedMovements"],
        "partial_attempts": report["partialAttempts"],
        "interrupted_attempts": report["interruptedAttempts"],
        "end_reason": report["setEndReason"],
        "analyzed_frames": report["setAnalyzedFrames"],
        "usable_tracking_frames": report["setUsableTrackingFrames"],
        "bar_setup": review["bar_setup"],
        "camera_stability": review["camera_stability"],
        "tags": list(review.get("tags", [])),
    }


def build_manifest(pairs: Sequence[tuple[pathlib.Path, pathlib.Path]]) -> dict[str, Any]:
    require(bool(pairs), "at least one --session pair is required")
    sessions = [build_session(load(review), load(report)) for review, report in pairs]
    identifiers = [session["id"] for session in sessions]
    require(len(identifiers) == len(set(identifiers)), "duplicate session id across intake pairs")
    return {
        "schema_version": 1,
        "scope": "sanitized whole-session qualification records; reviewer truth remains separate from runtime output",
        "sessions": sessions,
    }


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--session",
        nargs=2,
        action="append",
        metavar=("REVIEW_JSON", "DEVICE_REPORT_JSON"),
        required=True,
        help="pair an independently reviewed session label with its exported device report",
    )
    parser.add_argument("--output", type=pathlib.Path, required=True)
    args = parser.parse_args(argv)
    try:
        pairs = [(pathlib.Path(a), pathlib.Path(b)) for a, b in args.session]
        manifest = build_manifest(pairs)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        return 0
    except (IntakeError, OSError, ValueError, TypeError) as exc:
        print(f"Session intake rejected: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
