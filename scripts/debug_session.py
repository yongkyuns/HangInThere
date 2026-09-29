#!/usr/bin/env python3
"""Verify HangInThere debug-session packages and prepare offline evaluation.

Debug packages are local directory packages exported by the app. This tool never
uploads media. Manifest generation requires explicit rights evidence rather than
inventing permission for a user recording.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import sys
from typing import Any, Optional, Sequence

REQUIRED_FILES = ("video.mov", "session.json", "qualification.json", "hashes.json")
EXERCISE_MAP = {
    "pullUp": "pull_up",
    "dip": "parallel_bar_dip",
}


class DebugSessionError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise DebugSessionError(message)


def digest(path: pathlib.Path) -> tuple[str, int]:
    hasher = hashlib.sha256()
    count = 0
    try:
        with path.open("rb") as stream:
            for block in iter(lambda: stream.read(1024 * 1024), b""):
                count += len(block)
                hasher.update(block)
    except OSError as exc:
        raise DebugSessionError(f"{path}: {exc}") from exc
    return hasher.hexdigest(), count


def read_json(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DebugSessionError(f"{path}: {exc}") from exc
    require(isinstance(value, dict), f"{path}: top-level JSON must be an object")
    return value


def verify(package: pathlib.Path) -> dict[str, Any]:
    package = package.resolve()
    require(package.is_dir(), "debug session must be an exported package directory")
    for name in REQUIRED_FILES:
        require((package / name).is_file(), f"missing {name}")

    hashes = read_json(package / "hashes.json")
    require(hashes.get("schemaVersion") == 1, "unsupported hashes schemaVersion")
    files = hashes.get("files")
    require(isinstance(files, dict), "hashes.json files must be an object")

    verified: dict[str, dict[str, Any]] = {}
    for name in ("video.mov", "session.json", "qualification.json"):
        expected = files.get(name)
        require(isinstance(expected, dict), f"hashes.json missing {name}")
        sha, size = digest(package / name)
        require(expected.get("sha256") == sha, f"{name}: SHA-256 mismatch")
        require(expected.get("bytes") == size, f"{name}: byte count mismatch")
        verified[name] = {"sha256": sha, "bytes": size}

    session = read_json(package / "session.json")
    qualification = read_json(package / "qualification.json")
    require(session.get("schemaVersion") == 1, "unsupported session schemaVersion")
    require(
        qualification.get("schemaVersion") == 1,
        "unsupported qualification schemaVersion",
    )
    exercise = session.get("exercise")
    require(exercise in EXERCISE_MAP, "session exercise is unsupported")
    require(
        isinstance(session.get("counterPolicyVersion"), int)
        and session["counterPolicyVersion"] >= 1,
        "session counterPolicyVersion is invalid",
    )
    require(session.get("side") in {"left", "right"}, "session side is invalid")
    set_report = session.get("set")
    require(isinstance(set_report, dict), "session set must be an object")

    consistency_checks = (
        (
            "counterPolicyVersion",
            qualification.get("counterPolicyVersion"),
            session["counterPolicyVersion"],
        ),
        ("exercise", qualification.get("exercise"), exercise),
        ("side", qualification.get("side"), session["side"]),
        (
            "observedMovements",
            qualification.get("observedMovements"),
            set_report.get("observedMovements"),
        ),
        (
            "partialAttempts",
            qualification.get("partialAttempts"),
            set_report.get("partialAttempts"),
        ),
        (
            "interruptedAttempts",
            qualification.get("interruptedAttempts"),
            set_report.get("interruptedAttempts"),
        ),
        (
            "setAnalyzedFrames",
            qualification.get("setAnalyzedFrames"),
            set_report.get("analyzedFrames"),
        ),
        (
            "setUsableTrackingFrames",
            qualification.get("setUsableTrackingFrames"),
            set_report.get("usableTrackingFrames"),
        ),
        (
            "trackingCoverage",
            qualification.get("trackingCoverage"),
            set_report.get("trackingCoverage"),
        ),
        ("setPhase", qualification.get("setPhase"), set_report.get("phase")),
        (
            "setEndReason",
            qualification.get("setEndReason"),
            set_report.get("endReason"),
        ),
    )
    for field, qualification_value, session_value in consistency_checks:
        require(
            qualification_value == session_value,
            f"qualification {field} disagrees with session.json",
        )

    return {
        "schema_version": 1,
        "package": package.name,
        "files": verified,
        "session": {
            "counter_policy_version": session["counterPolicyVersion"],
            "exercise": exercise,
            "side": session["side"],
            "bar_reference_present": isinstance(session.get("barReference"), dict),
            "set": set_report,
            "capture": session.get("capture"),
        },
        "qualification": {
            "set_phase": qualification.get("setPhase"),
            "set_end_reason": qualification.get("setEndReason"),
            "observed_movements": qualification.get("observedMovements"),
        },
    }


def evaluation_manifest(
    package: pathlib.Path,
    report: dict[str, Any],
    rights_evidence: str,
) -> dict[str, Any]:
    require(rights_evidence.strip(), "private evaluation requires rights evidence")
    video = report["files"]["video.mov"]
    exercise = report["session"]["exercise"]
    identifier = "debug_" + video["sha256"][:16]
    require(re.fullmatch(r"[A-Za-z0-9_-]+", identifier) is not None, "invalid ID")
    return {
        "schema_version": 1,
        "clips": [
            {
                "id": identifier,
                "dataset": "local-live-debug-session",
                "exercise": EXERCISE_MAP[exercise],
                "split": "unassigned",
                "source_group": "debug-video-" + video["sha256"],
                "subject_group": None,
                "rights": {
                    "status": "approved",
                    "evidence": rights_evidence,
                    "public_outputs": False,
                },
                "media": {
                    "kind": "video",
                    "files": [
                        {
                            "path": "video.mov",
                            "sha256": video["sha256"],
                        }
                    ],
                },
                "debug_session": {
                    "package": report["package"],
                    "counter_policy_version": report["session"]["counter_policy_version"],
                    "capture_exercise": exercise,
                    "tracking_side": report["session"]["side"],
                    "bar_reference_present": report["session"]["bar_reference_present"],
                    "capture": report["session"]["capture"],
                    "set": report["session"]["set"],
                },
            }
        ],
    }


def write_json(path: pathlib.Path, value: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(value, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
    )


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)

    verify_parser = commands.add_parser("verify")
    verify_parser.add_argument("package", type=pathlib.Path)
    verify_parser.add_argument("--output", type=pathlib.Path, required=True)

    manifest_parser = commands.add_parser("manifest")
    manifest_parser.add_argument("package", type=pathlib.Path)
    manifest_parser.add_argument("--output", type=pathlib.Path, required=True)
    manifest_parser.add_argument("--rights-evidence", required=True)

    args = parser.parse_args(argv)
    try:
        report = verify(args.package)
        if args.command == "verify":
            write_json(args.output, report)
        else:
            write_json(
                args.output,
                evaluation_manifest(args.package, report, args.rights_evidence),
            )
        return 0
    except (DebugSessionError, OSError, ValueError, TypeError) as exc:
        print(f"Debug session rejected: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
