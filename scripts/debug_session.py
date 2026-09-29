#!/usr/bin/env python3
"""Validate and replay a local HangInThere live debug capture.

This tool never uploads media. prepare is cross-platform and produces the
standard evaluation manifest. run requires macOS because it invokes the exact
production Apple-Vision batch path, then replays the production movement counter
with the recorded exercise/side/bar reference.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import re
import subprocess
import sys
from typing import Any, Optional, Sequence

ROOT = pathlib.Path(__file__).resolve().parents[1]
EXERCISE_MAP = {"pullUp": "pull_up", "dip": "parallel_bar_dip"}
COUNT_EXERCISES = {"pullUp", "dip"}
SIDES = {"left", "right"}


class DebugSessionError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise DebugSessionError(message)


def digest(path: pathlib.Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def read_json(path: pathlib.Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise DebugSessionError(f"{path}: {exc}") from exc
    require(isinstance(value, dict), f"{path}: top-level JSON must be an object")
    return value


def validate_file_record(record: Any, root: pathlib.Path, label: str) -> pathlib.Path:
    require(isinstance(record, dict), f"{label} must be an object")
    name = record.get("name")
    require(
        isinstance(name, str)
        and name
        and pathlib.PurePath(name).name == name
        and "/" not in name
        and "\\" not in name,
        f"{label}.name must be a basename",
    )
    sha = record.get("sha256")
    require(
        isinstance(sha, str) and re.fullmatch(r"[0-9a-f]{64}", sha),
        f"{label}.sha256 is invalid",
    )
    size = record.get("bytes")
    require(isinstance(size, int) and not isinstance(size, bool) and size >= 0,
            f"{label}.bytes is invalid")
    path = root / name
    require(path.is_file(), f"{label} file is missing: {path}")
    require(path.stat().st_size == size, f"{label} byte count changed")
    require(digest(path) == sha, f"{label} SHA-256 changed")
    return path


def validate_session(manifest: dict[str, Any], root: pathlib.Path) -> dict[str, Any]:
    require(manifest.get("schemaVersion") == 1, "unsupported debug session schema")
    try:
        import uuid
        uuid.UUID(str(manifest.get("sessionID")))
    except (ValueError, TypeError):
        raise DebugSessionError("sessionID is not a UUID") from None

    video = validate_file_record(manifest.get("video"), root, "video")
    qualification = validate_file_record(
        manifest.get("qualification"), root, "qualification"
    )
    require(video != qualification, "video and qualification files must differ")

    capture = manifest.get("capture")
    require(isinstance(capture, dict), "capture must be an object")
    value = capture.get("durationSeconds")
    require(
        isinstance(value, (int, float))
        and not isinstance(value, bool)
        and value >= 0,
        "capture.durationSeconds is invalid",
    )
    for key in ("appendedSamples", "droppedQueueSamples", "droppedWriterSamples"):
        value = capture.get(key)
        require(
            isinstance(value, int) and not isinstance(value, bool) and value >= 0,
            f"capture.{key} is invalid",
        )
    require(capture["appendedSamples"] > 0, "debug video contains no appended samples")

    workout = manifest.get("workout")
    require(isinstance(workout, dict), "workout must be an object")
    require(
        isinstance(workout.get("counterPolicyVersion"), int)
        and workout["counterPolicyVersion"] > 0,
        "counterPolicyVersion is invalid",
    )
    require(workout.get("exercise") in COUNT_EXERCISES, "workout exercise is invalid")
    require(workout.get("side") in SIDES, "workout side is invalid")
    require(workout.get("phase") == "finished", "debug replay requires a finished set")

    bar = manifest.get("barReference")
    require(isinstance(bar, dict), "debug replay requires a confirmed bar reference")
    edge = bar.get("referenceEdge")
    require(isinstance(edge, dict), "barReference.referenceEdge is invalid")
    for key in ("ax", "ay", "bx", "by"):
        require(
            isinstance(edge.get(key), (int, float))
            and not isinstance(edge.get(key), bool),
            f"bar edge {key} is invalid",
        )
    for key in ("imageWidth", "imageHeight"):
        require(
            isinstance(bar.get(key), (int, float))
            and bar[key] > 0,
            f"barReference.{key} is invalid",
        )

    return {
        "manifest": manifest,
        "video_path": video,
        "qualification_path": qualification,
    }


def clip_id(session_id: str) -> str:
    return "debug_" + session_id.replace("-", "").lower()


def build_evaluation_manifest(manifest: dict[str, Any]) -> dict[str, Any]:
    identifier = clip_id(manifest["sessionID"])
    workout = manifest["workout"]
    return {
        "schema_version": 1,
        "clips": [
            {
                "id": identifier,
                "dataset": "local-live-debug-capture",
                "exercise": EXERCISE_MAP[workout["exercise"]],
                "split": "unassigned",
                "source_group": "live-debug-" + manifest["sessionID"],
                "subject_group": None,
                "rights": {
                    "status": "approved",
                    "evidence": (
                        "Locally exported opt-in debug capture; local evaluation only. "
                        "Do not publish without separate permission review."
                    ),
                    "public_outputs": False,
                },
                "media": {
                    "kind": "video",
                    "files": [
                        {
                            "path": manifest["video"]["name"],
                            "sha256": manifest["video"]["sha256"],
                        }
                    ],
                },
            }
        ],
    }


def write_json(path: pathlib.Path, value: Any) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def prepare(session_path: pathlib.Path, root: pathlib.Path, output: pathlib.Path) -> str:
    manifest = read_json(session_path)
    validate_session(manifest, root)
    write_json(output, build_evaluation_manifest(manifest))
    return clip_id(manifest["sessionID"])


def validate_observation_geometry(
    observations: pathlib.Path, manifest: dict[str, Any]
) -> None:
    try:
        with observations.open(encoding="utf-8") as stream:
            first = json.loads(stream.readline())
    except (OSError, json.JSONDecodeError) as exc:
        raise DebugSessionError(f"cannot read first observation: {exc}") from exc
    size = first.get("imageSize", {})
    bar = manifest["barReference"]
    require(
        abs(float(size.get("width", -1)) - float(bar["imageWidth"])) <= 1
        and abs(float(size.get("height", -1)) - float(bar["imageHeight"])) <= 1,
        "offline decoded geometry differs from live bar-reference geometry",
    )


def run_session(
    session_path: pathlib.Path,
    root: pathlib.Path,
    output: pathlib.Path,
    allow_policy_mismatch: bool,
) -> int:
    require(sys.platform == "darwin", "debug-session Apple Vision replay requires macOS")
    manifest = read_json(session_path)
    validate_session(manifest, root)
    identifier = clip_id(manifest["sessionID"])

    output.mkdir(parents=True, exist_ok=False)
    evaluation_manifest = output / "evaluation-manifest.json"
    write_json(evaluation_manifest, build_evaluation_manifest(manifest))
    poses = output / "poses"

    inference = subprocess.run(
        [
            str(ROOT / "scripts" / "evaluate.sh"),
            str(evaluation_manifest),
            "--root",
            str(root),
            "--output",
            str(poses),
        ],
        check=False,
    )
    require(inference.returncode == 0, "production Apple-Vision evaluation failed")

    observations = poses / identifier / "observations.jsonl"
    validate_observation_geometry(observations, manifest)
    counter_output = output / "counter.json"
    edge = manifest["barReference"]["referenceEdge"]
    workout = manifest["workout"]
    counting = subprocess.run(
        [
            str(ROOT / "scripts" / "count-replay.sh"),
            str(observations),
            workout["exercise"],
            workout["side"],
            str(counter_output),
            str(edge["ax"]),
            str(edge["ay"]),
            str(edge["bx"]),
            str(edge["by"]),
        ],
        check=False,
    )
    require(counting.returncode == 0, "production counter replay failed")

    counter = read_json(counter_output)
    evaluated_policy = counter.get("summary", {}).get("policyVersion")
    recorded_policy = workout["counterPolicyVersion"]
    policy_matches = evaluated_policy == recorded_policy
    if not policy_matches and not allow_policy_mismatch:
        raise DebugSessionError(
            f"counter policy mismatch: recording used v{recorded_policy}, "
            f"current source produced v{evaluated_policy}; "
            "use --allow-policy-mismatch only for an intentional new-policy comparison"
        )

    summary = {
        "schema_version": 1,
        "session_id": manifest["sessionID"],
        "video_sha256": manifest["video"]["sha256"],
        "recorded_counter_policy_version": recorded_policy,
        "evaluated_counter_policy_version": evaluated_policy,
        "counter_policy_matches_recording": policy_matches,
        "recorded_observed_movements": workout.get("observedMovements"),
        "offline_observed_movements": counter.get("summary", {}).get("observedMovements"),
        "recorded_partial_attempts": workout.get("partialAttempts"),
        "offline_partial_attempts": counter.get("summary", {}).get("partialAttempts"),
        "recorded_interrupted_attempts": workout.get("interruptedAttempts"),
        "offline_interrupted_attempts": counter.get("summary", {}).get("interruptedAttempts"),
        "recording_dropped_queue_samples": manifest["capture"]["droppedQueueSamples"],
        "recording_dropped_writer_samples": manifest["capture"]["droppedWriterSamples"],
        "observations_sha256": digest(observations),
        "counter_report_sha256": digest(counter_output),
        "scope": (
            "offline reproduction of an opt-in local debug capture; "
            "not ground-truth accuracy qualification"
        ),
    }
    write_json(output / "reproduction.json", summary)
    print(json.dumps(summary, indent=2, sort_keys=True))
    return 0


def main(argv: Optional[Sequence[str]] = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("prepare")
    p.add_argument("session", type=pathlib.Path)
    p.add_argument("--root", type=pathlib.Path, required=True)
    p.add_argument("--output", type=pathlib.Path, required=True)

    p = sub.add_parser("run")
    p.add_argument("session", type=pathlib.Path)
    p.add_argument("--root", type=pathlib.Path, required=True)
    p.add_argument("--output", type=pathlib.Path, required=True)
    p.add_argument("--allow-policy-mismatch", action="store_true")

    args = parser.parse_args(argv)
    try:
        root = args.root.resolve()
        if args.command == "prepare":
            identifier = prepare(args.session, root, args.output)
            print(identifier)
            return 0
        return run_session(
            args.session,
            root,
            args.output,
            args.allow_policy_mismatch,
        )
    except (DebugSessionError, OSError, ValueError, KeyError, TypeError) as exc:
        print(f"Debug session rejected: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
