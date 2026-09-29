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
import math
import pathlib
import re
import subprocess
import sys
from typing import Any, Optional, Sequence

REQUIRED_FILES = ("video.mov", "session.json", "qualification.json", "hashes.json")
ROOT = pathlib.Path(__file__).resolve().parents[1]
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
    session_schema = session.get("schemaVersion")
    require(session_schema in {1, 2}, "unsupported session schemaVersion")
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

    capture = session.get("capture")
    require(isinstance(capture, dict), "session capture must be an object")
    require(
        capture.get("backend") == "AVCaptureMovieFileOutput",
        "session capture backend is unsupported",
    )
    require(capture.get("includesSetup") is True, "debug capture must include setup")
    require(capture.get("audioRecorded") is False, "debug capture must not contain audio")
    duration = capture.get("durationSeconds")
    require(
        type(duration) in (int, float) and math.isfinite(duration) and duration >= 0,
        "capture durationSeconds is invalid",
    )
    capture_first = capture.get("firstAnalyzedSourceSeconds")
    capture_last = capture.get("lastAnalyzedSourceSeconds")
    if capture_first is not None or capture_last is not None:
        require(
            type(capture_first) in (int, float)
            and math.isfinite(capture_first)
            and type(capture_last) in (int, float)
            and math.isfinite(capture_last)
            and capture_first <= capture_last,
            "capture source timestamp bounds are invalid",
        )

    capture_movie_first = capture.get("firstAnalyzedMovieSeconds")
    capture_movie_last = capture.get("lastAnalyzedMovieSeconds")
    if session_schema >= 2:
        require(
            type(capture_movie_first) in (int, float)
            and math.isfinite(capture_movie_first)
            and capture_movie_first >= 0
            and type(capture_movie_last) in (int, float)
            and math.isfinite(capture_movie_last)
            and capture_movie_first <= capture_movie_last,
            "capture movie timestamp anchors are invalid",
        )
        require(
            type(capture_first) in (int, float)
            and type(capture_last) in (int, float),
            "schema v2 requires paired source/movie anchors",
        )

    set_first = set_report.get("firstSourceSeconds")
    set_last = set_report.get("lastSourceSeconds")
    if set_first is not None or set_last is not None:
        require(
            type(set_first) in (int, float)
            and math.isfinite(set_first)
            and type(set_last) in (int, float)
            and math.isfinite(set_last)
            and set_first <= set_last,
            "set source timestamp bounds are invalid",
        )
        require(
            type(capture_first) in (int, float)
            and type(capture_last) in (int, float)
            and capture_first <= set_first <= set_last <= capture_last,
            "set source timestamps fall outside recorded capture anchors",
        )

    bar_reference = session.get("barReference")
    if isinstance(bar_reference, dict):
        bar_source = bar_reference.get("sourceSeconds")
        require(
            type(bar_source) in (int, float) and math.isfinite(bar_source),
            "bar reference source timestamp is invalid",
        )
        if capture_first is not None or capture_last is not None:
            require(
                capture_first <= bar_source <= capture_last,
                "bar reference falls outside recorded capture anchors",
            )
        if set_first is not None:
            require(
                bar_source <= set_first,
                "bar reference must precede the live set source window",
            )

    return {
        "schema_version": 1,
        "package": package.name,
        "files": verified,
        "session": {
            "schema_version": session_schema,
            "counter_policy_version": session["counterPolicyVersion"],
            "exercise": exercise,
            "side": session["side"],
            "bar_reference_present": isinstance(bar_reference, dict),
            "bar_reference": bar_reference if isinstance(bar_reference, dict) else None,
            "set": set_report,
            "capture": capture,
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
                    "session_schema_version": report["session"]["schema_version"],
                    "counter_policy_version": report["session"]["counter_policy_version"],
                    "capture_exercise": exercise,
                    "tracking_side": report["session"]["side"],
                    "bar_reference_present": report["session"]["bar_reference_present"],
                    "bar_reference": report["session"]["bar_reference"],
                    "capture": report["session"]["capture"],
                    "set": report["session"]["set"],
                },
            }
        ],
    }



def _finite_number(value: Any) -> bool:
    return type(value) in (int, float) and math.isfinite(value)


def _timestamp_seconds(row: dict[str, Any]) -> float:
    stamp = row.get("timestamp")
    require(isinstance(stamp, dict), "observation timestamp is missing")
    value = stamp.get("value")
    timescale = stamp.get("timescale")
    require(
        isinstance(value, int)
        and not isinstance(value, bool)
        and isinstance(timescale, int)
        and not isinstance(timescale, bool)
        and timescale > 0,
        "observation source timestamp is invalid",
    )
    return value / timescale


def read_observations(path: pathlib.Path) -> tuple[list[dict[str, Any]], list[float]]:
    rows: list[dict[str, Any]] = []
    seconds: list[float] = []
    try:
        with path.open(encoding="utf-8") as stream:
            for index, line in enumerate(stream):
                value = json.loads(line)
                require(isinstance(value, dict), "observation row must be an object")
                require(value.get("frameIndex") == index, "observation frames are reordered")
                require(value.get("timebase") == "source_pts", "actual source PTS are required")
                now = _timestamp_seconds(value)
                require(
                    not seconds or now > seconds[-1],
                    "observation source timestamps are not strictly increasing",
                )
                rows.append(value)
                seconds.append(now)
    except (OSError, json.JSONDecodeError) as exc:
        raise DebugSessionError(f"{path}: {exc}") from exc
    require(rows, "offline evaluation produced no observations")
    return rows, seconds


def replay_clock(report: dict[str, Any]) -> dict[str, float]:
    session = report["session"]
    require(
        session["schema_version"] >= 2,
        "live-vs-replay comparison requires debug session schema v2 clock anchors",
    )
    capture = session["capture"]
    source_first = capture.get("firstAnalyzedSourceSeconds")
    source_last = capture.get("lastAnalyzedSourceSeconds")
    movie_first = capture.get("firstAnalyzedMovieSeconds")
    movie_last = capture.get("lastAnalyzedMovieSeconds")
    require(
        all(_finite_number(x) for x in (source_first, source_last, movie_first, movie_last)),
        "debug capture clock anchors are incomplete",
    )
    source_span = source_last - source_first
    movie_span = movie_last - movie_first
    require(source_span > 0 and movie_span > 0, "debug capture clock anchors need a positive span")
    slope = movie_span / source_span
    require(_finite_number(slope) and slope > 0, "debug capture clock mapping is invalid")
    return {
        "source_first_seconds": source_first,
        "source_last_seconds": source_last,
        "movie_first_seconds": movie_first,
        "movie_last_seconds": movie_last,
        "movie_seconds_per_source_second": slope,
        "movie_intercept_seconds": movie_first - slope * source_first,
    }


def source_to_movie_elapsed(clock: dict[str, float], source_seconds: float) -> float:
    require(_finite_number(source_seconds), "source timestamp is invalid")
    return (
        clock["movie_intercept_seconds"]
        + clock["movie_seconds_per_source_second"] * source_seconds
    )


def select_live_set_observations(
    report: dict[str, Any],
    rows: list[dict[str, Any]],
    seconds: list[float],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    require(len(rows) == len(seconds) and rows, "observation stream is invalid")
    set_report = report["session"]["set"]
    set_first = set_report.get("firstSourceSeconds")
    set_last = set_report.get("lastSourceSeconds")
    require(
        _finite_number(set_first)
        and _finite_number(set_last)
        and set_first <= set_last,
        "captured set has no usable source-time window",
    )
    clock = replay_clock(report)
    asset_origin = seconds[0]
    target_first = asset_origin + source_to_movie_elapsed(clock, set_first)
    target_last = asset_origin + source_to_movie_elapsed(clock, set_last)
    require(target_first <= target_last, "mapped set window is invalid")

    start_index = min(range(len(seconds)), key=lambda i: abs(seconds[i] - target_first))
    end_index = min(range(start_index, len(seconds)), key=lambda i: abs(seconds[i] - target_last))
    selected: list[dict[str, Any]] = []
    for new_index, row in enumerate(rows[start_index : end_index + 1]):
        copy = dict(row)
        copy["frameIndex"] = new_index
        selected.append(copy)
    require(selected, "mapped set window contains no observations")

    return selected, {
        "clock": clock,
        "asset_origin_source_pts_seconds": asset_origin,
        "target_first_source_pts_seconds": target_first,
        "selected_first_source_pts_seconds": seconds[start_index],
        "first_alignment_error_seconds": seconds[start_index] - target_first,
        "target_last_source_pts_seconds": target_last,
        "selected_last_source_pts_seconds": seconds[end_index],
        "last_alignment_error_seconds": seconds[end_index] - target_last,
        "first_frame_index": start_index,
        "last_frame_index": end_index,
        "selected_frames": len(selected),
    }


def replay_bar_edge(
    report: dict[str, Any],
    rows: list[dict[str, Any]],
) -> tuple[list[float], dict[str, Any]]:
    bar = report["session"].get("bar_reference")
    require(isinstance(bar, dict), "live-vs-replay comparison requires a bar reference")
    live_size = bar.get("imageSize")
    require(isinstance(live_size, dict), "bar reference image size is missing")
    live_width = live_size.get("width")
    live_height = live_size.get("height")
    require(
        _finite_number(live_width)
        and live_width > 0
        and _finite_number(live_height)
        and live_height > 0,
        "bar reference image size is invalid",
    )
    first_size = rows[0].get("imageSize")
    require(isinstance(first_size, dict), "offline pose image size is missing")
    replay_width = first_size.get("width")
    replay_height = first_size.get("height")
    require(
        _finite_number(replay_width)
        and replay_width > 0
        and _finite_number(replay_height)
        and replay_height > 0,
        "offline pose image size is invalid",
    )
    for row in rows[1:]:
        require(row.get("imageSize") == first_size, "offline pose image geometry changed")

    a = bar.get("a")
    b = bar.get("b")
    require(isinstance(a, dict) and isinstance(b, dict), "bar reference endpoints are missing")
    values = [a.get("x"), a.get("y"), b.get("x"), b.get("y")]
    require(all(_finite_number(x) for x in values), "bar reference endpoints are invalid")
    sx = replay_width / live_width
    sy = replay_height / live_height
    edge = [values[0] * sx, values[1] * sy, values[2] * sx, values[3] * sy]
    require(
        math.hypot(edge[2] - edge[0], edge[3] - edge[1]) > 0,
        "scaled bar reference is degenerate",
    )
    return edge, {
        "live_image_size": {"width": live_width, "height": live_height},
        "replay_image_size": {"width": replay_width, "height": replay_height},
        "scale_x": sx,
        "scale_y": sy,
        "edge": edge,
    }


def write_observations(path: pathlib.Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    try:
        with path.open("x", encoding="utf-8") as stream:
            for row in rows:
                stream.write(json.dumps(row, sort_keys=True, allow_nan=False) + "\n")
    except (OSError, ValueError, TypeError) as exc:
        raise DebugSessionError(f"{path}: {exc}") from exc


def _delta_summary(values: list[float]) -> dict[str, Any]:
    if not values:
        return {"count": 0, "mean_seconds": None, "max_absolute_seconds": None}
    return {
        "count": len(values),
        "mean_seconds": sum(values) / len(values),
        "max_absolute_seconds": max(abs(x) for x in values),
    }


def compare_live_and_replay(
    report: dict[str, Any],
    counter: dict[str, Any],
    window: dict[str, Any],
) -> dict[str, Any]:
    session = report["session"]
    live = session["set"]
    replay = counter.get("summary")
    require(isinstance(replay, dict), "counter replay summary is missing")
    for key in ("policyVersion", "observedMovements", "partialAttempts", "interruptedAttempts"):
        require(isinstance(replay.get(key), int), f"counter replay {key} is invalid")

    live_first = live.get("firstSourceSeconds")
    movement_times = live.get("movementTimes")
    require(
        _finite_number(live_first)
        and isinstance(movement_times, list)
        and all(_finite_number(x) and x >= 0 for x in movement_times),
        "live movement timeline is invalid",
    )
    clock = window["clock"]
    asset_origin = window["asset_origin_source_pts_seconds"]
    mapped_live_events = [
        asset_origin + source_to_movie_elapsed(clock, live_first + relative)
        for relative in movement_times
    ]
    replay_events = [
        event.get("sourceSeconds")
        for event in counter.get("events", [])
        if event.get("outcome") == "movement"
    ]
    require(all(_finite_number(x) for x in replay_events), "replay movement timeline is invalid")
    pairs = []
    deltas = []
    for index, (live_time, replay_time) in enumerate(zip(mapped_live_events, replay_events)):
        delta = replay_time - live_time
        deltas.append(delta)
        pairs.append(
            {
                "index": index,
                "mapped_live_movie_seconds": live_time,
                "replay_movie_seconds": replay_time,
                "replay_minus_live_seconds": delta,
            }
        )

    live_tracking = live.get("trackingCoverage")
    replay_tracking = counter.get("trackingCoverage")
    tracking_delta = (
        replay_tracking - live_tracking
        if _finite_number(live_tracking) and _finite_number(replay_tracking)
        else None
    )
    return {
        "schema_version": 1,
        "scope": (
            "live-vs-current-replay movement diagnostic; clock-aligned set window; "
            "not strict-form qualification"
        ),
        "captured_policy_version": session["counter_policy_version"],
        "replay_policy_version": replay["policyVersion"],
        "policy_changed": replay["policyVersion"] != session["counter_policy_version"],
        "live": {
            "phase": live.get("phase"),
            "end_reason": live.get("endReason"),
            "observed_movements": live.get("observedMovements"),
            "partial_attempts": live.get("partialAttempts"),
            "interrupted_attempts": live.get("interruptedAttempts"),
            "tracking_coverage": live_tracking,
            "movement_times_from_set_start_seconds": movement_times,
        },
        "replay": {
            "frames": counter.get("frames"),
            "usable_tracking_frames": counter.get("usableTrackingFrames"),
            "tracking_coverage": replay_tracking,
            "observed_movements": replay["observedMovements"],
            "partial_attempts": replay["partialAttempts"],
            "interrupted_attempts": replay["interruptedAttempts"],
        },
        "deltas": {
            "observed_movements": replay["observedMovements"] - live.get("observedMovements", 0),
            "partial_attempts": replay["partialAttempts"] - live.get("partialAttempts", 0),
            "interrupted_attempts": replay["interruptedAttempts"] - live.get("interruptedAttempts", 0),
            "tracking_coverage": tracking_delta,
        },
        "timing": {
            "pairing": "chronological_index_diagnostic_only",
            "mapped_live_events": len(mapped_live_events),
            "replay_events": len(replay_events),
            "pairs": pairs,
            "delta_summary": _delta_summary(deltas),
        },
        "window_alignment": window,
    }


def run_replay_comparison(
    package: pathlib.Path,
    output: pathlib.Path,
    rights_evidence: str,
) -> dict[str, Any]:
    package = package.resolve()
    output = output.resolve()
    require(sys.platform == "darwin", "replay comparison requires macOS Apple Vision")
    require(not output.exists(), "replay comparison output already exists")
    report = verify(package)
    require(report["session"]["schema_version"] >= 2, "replay comparison requires a schema v2 package")
    require(report["session"]["bar_reference_present"], "replay comparison requires a captured bar reference")

    output.mkdir(parents=True)
    manifest = evaluation_manifest(package, report, rights_evidence)
    manifest_path = output / "manifest.json"
    write_json(manifest_path, manifest)
    pose_output = output / "poses"
    subprocess.run(
        [
            str(ROOT / "scripts" / "evaluate.sh"),
            str(manifest_path),
            "--root",
            str(package),
            "--output",
            str(pose_output),
        ],
        check=True,
    )

    pose_report = read_json(pose_output / "report.json")
    clip = manifest["clips"][0]
    pose_rows = pose_report.get("clips")
    require(isinstance(pose_rows, list) and len(pose_rows) == 1, "unexpected pose report")
    require(pose_rows[0].get("id") == clip["id"], "pose report clip identity differs")
    require(pose_rows[0].get("status") == "processed", "offline pose replay did not complete")
    observation_path = pose_output / clip["id"] / "observations.jsonl"
    rows, seconds = read_observations(observation_path)
    selected, window = select_live_set_observations(report, rows, seconds)
    set_observations = output / "set-observations.jsonl"
    write_observations(set_observations, selected)
    edge, bar_mapping = replay_bar_edge(report, selected)

    counter_path = output / "counter.json"
    subprocess.run(
        [
            str(ROOT / "scripts" / "count-replay.sh"),
            str(set_observations),
            report["session"]["exercise"],
            report["session"]["side"],
            str(counter_path),
            *(str(x) for x in edge),
        ],
        check=True,
    )
    counter = read_json(counter_path)
    set_sha, _ = digest(set_observations)
    require(counter.get("input_sha256") == set_sha, "counter replay input pin differs")
    require(
        counter.get("source_revision") == pose_report.get("source_commit"),
        "pose and counter replay used different source revisions",
    )

    comparison = compare_live_and_replay(report, counter, window)
    comparison["package"] = report["package"]
    comparison["video_sha256"] = report["files"]["video.mov"]["sha256"]
    comparison["pose_report_sha256"] = digest(pose_output / "report.json")[0]
    comparison["set_observations_sha256"] = set_sha
    comparison["counter_report_sha256"] = digest(counter_path)[0]
    comparison["bar_mapping"] = bar_mapping
    comparison["source_revision"] = counter.get("source_revision")
    write_json(output / "comparison.json", comparison)
    return comparison


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

    compare_parser = commands.add_parser("compare")
    compare_parser.add_argument("package", type=pathlib.Path)
    compare_parser.add_argument("--output", type=pathlib.Path, required=True)
    compare_parser.add_argument("--rights-evidence", required=True)

    args = parser.parse_args(argv)
    try:
        report = verify(args.package)
        if args.command == "verify":
            write_json(args.output, report)
        elif args.command == "manifest":
            write_json(
                args.output,
                evaluation_manifest(args.package, report, args.rights_evidence),
            )
        else:
            comparison = run_replay_comparison(
                args.package,
                args.output,
                args.rights_evidence,
            )
            print(
                "Replay comparison: "
                f"live={comparison['live']['observed_movements']} "
                f"replay={comparison['replay']['observed_movements']} "
                f"policy={comparison['captured_policy_version']}->"
                f"{comparison['replay_policy_version']}"
            )
        return 0
    except (
        DebugSessionError,
        OSError,
        ValueError,
        TypeError,
        subprocess.CalledProcessError,
    ) as exc:
        print(f"Debug session rejected: {exc}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
