#!/usr/bin/env python3
"""Offline manifest preflight, production-pipeline batch execution and 2D scoring.

No downloads, training, substitute landmarks, implicit FPS, or form verdicts.
"""
from __future__ import annotations
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JOINTS = set("nose neck root leftEye rightEye leftEar rightEar leftShoulder rightShoulder leftElbow rightElbow leftWrist rightWrist leftHip rightHip leftKnee rightKnee leftAnkle rightAnkle".split())
SPLITS = {"unassigned", "development", "validation", "test", "smoke"}
EXERCISES = {"pull_up", "parallel_bar_dip", "bench_dip", "other", "dip_unverified"}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    def invalid(value):
        raise ValueError(f"Non-finite JSON number: {value}")
    def unique(items):
        result = {}
        for key, value in items:
            require(key not in result, f"Duplicate JSON key: {key}")
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), parse_constant=invalid, object_pairs_hook=unique)


def write_json(path, value):
    Path(path).write_text(json.dumps(value, indent=2, sort_keys=True, allow_nan=False) + "\n")


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def integer(value, minimum=0):
    return type(value) is int and value >= minimum


def number(value):
    return type(value) in (int, float) and math.isfinite(value)


def asset_path(root, spec):
    require(isinstance(spec, dict), "Asset must be an object")
    name = spec.get("path")
    require(isinstance(name, str) and name and not Path(name).is_absolute(), "Asset path must be relative")
    require(".." not in Path(name).parts, "Parent traversal is not allowed")
    require(re.fullmatch(r"[a-f0-9]{64}", spec.get("sha256", "")) is not None, "Asset needs a SHA-256 pin")
    path = (root / name).resolve()
    require(path.is_relative_to(root.resolve()), "Asset symlink escapes data root")
    return path


def validate_manifest(manifest, root):
    require(manifest.get("schema_version") == 1, "Unsupported manifest version")
    confidence = manifest.get("confidence_threshold", 0.3)
    require(number(confidence) and 0 < confidence <= 1, "Invalid confidence threshold")
    clips = manifest.get("clips")
    require(isinstance(clips, list) and clips, "Empty corpus is not an evaluation")
    seen_ids, grouping = set(), {}
    for clip in clips:
        identifier = clip.get("id", "")
        require(isinstance(identifier, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,100}", identifier), "Invalid clip ID")
        require(identifier not in seen_ids, "Duplicate clip ID")
        seen_ids.add(identifier)
        require(clip.get("exercise") in EXERCISES, f"{identifier}: unknown exercise")
        split = clip.get("split")
        require(split in SPLITS, f"{identifier}: invalid split")
        for field in ("dataset", "source_group"):
            require(isinstance(clip.get(field), str) and clip[field].strip(), f"{identifier}: missing {field}")
        require(clip.get("subject_group") is None or isinstance(clip["subject_group"], str) and clip["subject_group"].strip(), "Invalid subject group")
        require(split in {"smoke", "unassigned"} or clip.get("subject_group"), "Assign subject groups before claiming held-out splits")
        rights = clip.get("rights", {})
        require(rights.get("status") in {"approved", "pending", "denied"}, "Invalid rights status")
        require(type(rights.get("public_outputs", False)) is bool, "public_outputs must be boolean")
        if rights["status"] == "approved":
            require(isinstance(rights.get("evidence"), str) and rights["evidence"].strip(), "Approval requires evidence")
        media = clip.get("media", {})
        require(media.get("kind") in {"video", "images"}, "Unsupported media kind")
        files = media.get("files")
        require(isinstance(files, list) and files, "Media files must be nonempty")
        require(media["kind"] != "video" or len(files) == 1, "Video needs exactly one file")
        if "expected_frames" in media:
            require(integer(media["expected_frames"], 1), "Invalid expected frame count")
            require(media["kind"] != "images" or len(files) == media["expected_frames"], "Image count mismatch")
        groups = [("source", clip["source_group"])]
        if clip.get("subject_group"):
            groups.append(("subject", clip["subject_group"]))
        for spec in files:
            asset_path(root, spec)
            groups.append(("media", spec["sha256"]))
        if clip.get("annotations"):
            asset_path(root, clip["annotations"])
        for group in groups:
            require(group not in grouping or grouping[group] == split, "Source, subject or media crosses splits")
            grouping[group] = split
    return clips


def preflight(clip, root, public_output=False):
    if clip["rights"]["status"] != "approved":
        return "not_approved"
    if public_output and not clip["rights"].get("public_outputs", False):
        return "outputs_not_approved"
    for spec in clip["media"]["files"] + ([clip["annotations"]] if clip.get("annotations") else []):
        path = asset_path(root, spec)
        if not path.is_file():
            return "missing_input"
        if digest(path) != spec["sha256"]:
            return "integrity_failure"
    return "ready"


def validate_reference(reference, clip):
    require(reference.get("schema_version") == 1, "Unsupported annotation version")
    require(reference.get("coordinates") == "upright_pixels_top_left", "Annotation coordinate convention must be explicit")
    require(reference.get("independently_reviewed") is True, "Model predictions are not ground truth")
    require(isinstance(reference.get("provenance"), str) and reference["provenance"].strip(), "Annotation provenance is required")
    require(reference.get("media_sha256") == [x["sha256"] for x in clip["media"]["files"]], "Labels belong to different media")
    frames = reference.get("frames")
    require(isinstance(frames, list) and frames, "No reviewed joint labels")
    previous = -1
    for frame in frames:
        index = frame.get("frame_index")
        require(integer(index) and index > previous, "Reference indices must be strictly increasing and zero-based")
        previous = index
        width, height = frame.get("width"), frame.get("height")
        require(number(width) and number(height) and width > 0 and height > 0, "Invalid label dimensions")
        if "scale_pixels" in frame:
            require(number(frame["scale_pixels"]) and frame["scale_pixels"] > 0, "Invalid reference scale")
        require(type(frame.get("endpoint", False)) is bool, "endpoint must be boolean")
        points = frame.get("points")
        require(isinstance(points, dict) and points, "Only visible, reviewed points belong in points")
        for joint, point in points.items():
            require(joint in JOINTS and isinstance(point, list) and len(point) == 2, "Unknown or malformed joint")
            require(all(number(x) for x in point) and 0 <= point[0] <= width and 0 <= point[1] <= height, "Joint outside labelled image")
        if "timestamp_seconds" in frame:
            require(clip["media"]["kind"] == "video" and number(frame["timestamp_seconds"]) and frame["timestamp_seconds"] >= 0, "Image sequences do not establish timestamps")
    return reference


def statistics(values, total):
    ordered = sorted(values)
    return {"reference_count": total, "measured_count": len(values),
            "coverage": len(values) / total if total else None,
            "mean": sum(values) / len(values) if values else None,
            "p95": ordered[max(0, math.ceil(0.95 * len(ordered)) - 1)] if ordered else None}


def angle(points):
    a, b, c = points
    u, v = (a[0] - b[0], a[1] - b[1]), (c[0] - b[0], c[1] - b[1])
    denominator = math.hypot(*u) * math.hypot(*v)
    if denominator < 1e-8:
        return None
    return math.degrees(math.acos(max(-1, min(1, (u[0] * v[0] + u[1] * v[1]) / denominator))))


def score_pose(reference, observations, confidence=0.3):
    labels = {x["frame_index"]: x for x in reference["frames"]}
    errors = {j: [] for j in JOINTS}
    totals = Counter(j for frame in labels.values() for j in frame["points"])
    normalized, endpoint_errors, angle_errors = [], [], {"left": [], "right": []}
    normalized_total = sum(len(f["points"]) for f in labels.values() if "scale_pixels" in f)
    endpoint_total = sum(len(f["points"]) for f in labels.values() if f.get("endpoint"))
    angle_total = {side: 0 for side in angle_errors}
    # Count every eligible reference angle, including frames with no predicted person.
    for ref in labels.values():
        for side in angle_total:
            chain = [side + suffix for suffix in ("Shoulder", "Elbow", "Wrist")]
            if all(j in ref["points"] for j in chain) and angle([ref["points"][j] for j in chain]) is not None:
                angle_total[side] += 1
    seen, ambiguous, missing = set(), 0, 0
    for obs in observations:
        index = obs["frameIndex"]
        if index not in labels:
            continue
        require(index not in seen, "Duplicate prediction frame")
        seen.add(index)
        ref = labels[index]
        if "timestamp_seconds" in ref:
            ts = obs.get("timestamp")
            require(obs["timebase"] == "source_pts" and ts and ts["timescale"] > 0, "Reference timestamp is unavailable")
            require(abs(ts["value"] / ts["timescale"] - ref["timestamp_seconds"]) <= 1e-4, "Reference timestamp mismatch")
        people = obs["people"]
        if len(people) != 1:
            ambiguous += len(people) > 1
            missing += len(people) == 0
            continue
        size = obs["imageSize"]
        width, height = size["width"], size["height"]
        require(number(width) and number(height) and width > 0 and height > 0, "Invalid prediction dimensions")
        require(abs(width / ref["width"] - height / ref["height"]) <= 2 / min(ref["width"], ref["height"]), "Image aspect ratio differs from labels")
        predicted = {}
        for point in people[0]["landmarks"]:
            joint = point["joint"]
            require(joint in JOINTS and joint not in predicted, "Invalid/duplicate predicted joint")
            value, location = point["confidence"], point["position"]
            if number(value) and confidence <= value <= 1 and all(number(location[k]) for k in ("x", "y")):
                if 0 <= location["x"] <= width and 0 <= location["y"] <= height:
                    predicted[joint] = [location["x"] * ref["width"] / width, location["y"] * ref["height"] / height]
        for joint, actual in ref["points"].items():
            if joint not in predicted:
                continue
            distance = math.dist(predicted[joint], actual)
            errors[joint].append(distance)
            if "scale_pixels" in ref:
                normalized.append(distance / ref["scale_pixels"])
            if ref.get("endpoint"):
                endpoint_errors.append(distance)
        for side in angle_errors:
            chain = [side + suffix for suffix in ("Shoulder", "Elbow", "Wrist")]
            if all(j in ref["points"] and j in predicted for j in chain):
                actual, estimate = angle([ref["points"][j] for j in chain]), angle([predicted[j] for j in chain])
                if actual is not None and estimate is not None:
                    angle_errors[side].append(abs(actual - estimate))
    require(seen == set(labels), "Some labelled frames were not processed")
    return {"status": "measured_2d_only", "joint_pixels": {j: statistics(errors[j], totals[j]) for j in sorted(totals)},
            "normalized_joint_error": statistics(normalized, normalized_total),
            "endpoint_joint_pixels": statistics(endpoint_errors, endpoint_total),
            "elbow_degrees": {side: statistics(angle_errors[side], angle_total[side]) for side in angle_errors},
            "ambiguous_person_frames": ambiguous, "missing_person_frames": missing,
            "person_policy": "exactly_one_prediction_no_reference_based_selection"}


def observations(path, expected_kind):
    previous_time = None
    with Path(path).open() as stream:
        for index, line in enumerate(stream):
            obs = json.loads(line, parse_constant=lambda _: (_ for _ in ()).throw(ValueError("Nonfinite prediction")))
            require(obs.get("frameIndex") == index, "Prediction stream is truncated or reordered")
            require(obs.get("backend") == "Apple Vision 2D" and integer(obs.get("requestRevision"), 1), "Unexpected backend identity")
            require(number(obs.get("processingMilliseconds")) and obs["processingMilliseconds"] >= 0, "Invalid timing")
            if expected_kind == "video":
                timestamp = obs.get("timestamp", {})
                require(obs.get("timebase") == "source_pts" and integer(timestamp.get("timescale"), 1) and integer(timestamp.get("value")), "Missing actual source PTS")
                now = timestamp["value"] / timestamp["timescale"]
                require(previous_time is None or now > previous_time, "Nonmonotonic source PTS")
                previous_time = now
            else:
                require(obs.get("timebase") == "frame_index" and obs.get("timestamp") is None, "Invented image timestamp")
            yield obs


def run_batch(manifest_path, root, output, engine, public_output=False, timeout=1800):
    manifest = read_json(manifest_path)
    clips = validate_manifest(manifest, root)
    output.mkdir(parents=True, exist_ok=False)  # Never mix old and new results.
    records, jobs, references = [], [], {}
    for clip in clips:
        status = preflight(clip, root, public_output)
        record = {"id": clip["id"], "dataset": clip["dataset"], "exercise": clip["exercise"],
                  "split": clip["split"], "source_group": clip["source_group"], "subject_group": clip.get("subject_group"),
                  "status": status, "media_sha256": [x["sha256"] for x in clip["media"]["files"]],
                  "pose_metrics": {"status": "not_evaluated"}, "rep_metrics": {"status": "not_implemented"}}
        if status == "ready" and clip.get("annotations"):
            try:
                references[clip["id"]] = validate_reference(read_json(asset_path(root, clip["annotations"])), clip)
                record["annotation_sha256"] = clip["annotations"]["sha256"]
            except (ValueError, KeyError, TypeError):
                record["status"] = "invalid_annotations"
        if record["status"] == "ready":
            jobs.append({"id": clip["id"], "kind": clip["media"]["kind"],
                         "paths": [str(asset_path(root, x)) for x in clip["media"]["files"]]})
        records.append(record)
    engine_status = "not_run"
    if jobs:
        with tempfile.TemporaryDirectory(prefix="hanginthere-jobs-") as temporary:
            path = Path(temporary) / "jobs.json"
            write_json(path, jobs)
            # Filesystem paths remain in this temporary job description, not reports.
            try:
                with (output / "engine.log").open("w") as log:
                    process = subprocess.run([str(engine), str(path), str(output.resolve())], stdout=log,
                                             stderr=log, timeout=timeout, check=False)
                engine_status = "completed" if process.returncode == 0 else "failed"
            except (OSError, subprocess.TimeoutExpired):
                engine_status = "failed"
    for clip, record in zip(clips, records):
        if record["status"] != "ready":
            continue
        try:
            directory = output / clip["id"]
            completion = read_json(directory / "completion.json")
            require(engine_status == "completed" and completion["status"] == "processed", "Incomplete engine run")
            require(preflight(clip, root, public_output) == "ready", "Input changed during inference")
            count = 0
            backend = None
            timings = []
            for obs in observations(directory / "observations.jsonl", clip["media"]["kind"]):
                count += 1
                identity = (obs["backend"], obs["requestRevision"])
                require(backend is None or backend == identity, "Backend changed within clip")
                backend = identity
                timings.append(obs["processingMilliseconds"])
            require(count > 0 and count == completion["frames"], "Incomplete frame stream")
            require(count == clip["media"].get("expected_frames", count), "Frame count differs from manifest")
            record.update(status="processed", frames=count, backend=backend, target=completion["target"],
                          operating_system=completion["operatingSystem"], observations_sha256=digest(directory / "observations.jsonl"),
                          processing_ms=statistics(timings, count))
            if clip["id"] in references:
                record["pose_metrics"] = score_pose(references[clip["id"]],
                    observations(directory / "observations.jsonl", clip["media"]["kind"]), manifest.get("confidence_threshold", 0.3))
            else:
                record["pose_metrics"] = {"status": "no_independent_labels"}
        except (OSError, ValueError, KeyError, TypeError):
            record.update(status="evaluation_failure", pose_metrics={"status": "not_evaluated"})
    sources = sorted(list((ROOT / "HangInThere/Analysis").glob("*.swift")) +
                     [ROOT / "HangInThere/Capture/VideoReplayReader.swift", ROOT / "HangInThere/Pose/VisionPoseEstimator.swift",
                      ROOT / "Evaluation/PoseBatch.swift", Path(__file__)])
    fingerprint = {str(p.relative_to(ROOT)): digest(p) for p in sources}
    commit = subprocess.run(["git", "-C", str(ROOT), "rev-parse", "HEAD"], text=True, capture_output=True, check=False).stdout.strip() or None
    report = {"schema_version": 1, "manifest_sha256": digest(manifest_path), "source_commit": commit,
              "source_files_sha256": fingerprint, "engine_sha256": digest(engine), "confidence_threshold": manifest.get("confidence_threshold", 0.3),
              "scope": "offline pose evaluation; no phone throughput, rep, form or 3D accuracy claim",
              "counts_by_status": dict(Counter(x["status"] for x in records)),
              "by_exercise": {e: dict(Counter(x["status"] for x in records if x["exercise"] == e)) for e in sorted({x["exercise"] for x in records})},
              "clips": records}
    write_json(output / "report.json", report)
    return 0 if all(x["status"] == "processed" for x in records) else 2


def smoke_manifest(root):
    fixture = root / "HangInThereTests/Fixtures/generated"
    meta = read_json(fixture / "prepared.json")
    video = fixture / "pullup-smoke.mp4"
    require(digest(video) == meta["video_sha256"], "Fixture integrity mismatch")
    return {"schema_version": 1, "clips": [{"id": "pullup_smoke", "dataset": "p0_smoke", "exercise": "pull_up",
        "split": "smoke", "source_group": meta["source_id"], "subject_group": None,
        "rights": {"status": "approved", "evidence": "HangInThereTests/Fixtures/source.json; existing reviewed test-only use", "public_outputs": True},
        "media": {"kind": "video", "expected_frames": len(meta["frame_pts_seconds"]), "files": [
            {"path": str(video.relative_to(root)), "sha256": meta["video_sha256"]}]}}]}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    for command in ("inspect", "run"):
        p = commands.add_parser(command)
        p.add_argument("manifest", type=Path)
        p.add_argument("--root", type=Path, required=True)
        p.add_argument("--output", type=Path, required=True)
        p.add_argument("--public-output", action="store_true")
        if command == "run":
            p.add_argument("--engine", type=Path, required=True)
            p.add_argument("--timeout", type=int, default=1800)
    p = commands.add_parser("smoke-manifest")
    p.add_argument("--root", type=Path, required=True)
    p.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        root = args.root.resolve()
        if args.command == "smoke-manifest":
            write_json(args.output, smoke_manifest(root))
            return 0
        if args.command == "inspect":
            clips = validate_manifest(read_json(args.manifest), root)
            rows = [{"id": c["id"], "status": preflight(c, root, args.public_output)} for c in clips]
            write_json(args.output, {"clips": rows, "counts": dict(Counter(x["status"] for x in rows))})
            return 0 if all(x["status"] == "ready" for x in rows) else 2
        return run_batch(args.manifest, root, args.output, args.engine.resolve(), args.public_output, args.timeout)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Evaluation rejected: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
