#!/usr/bin/env python3
"""Run MediaPipe Pose Landmarker Heavy on an existing HangInThere image manifest.

Host-only comparison tool. It deliberately does not modify the iOS app target.
Only ordered image sequences are supported in this first comparison so both
backends receive the same pre-oriented source files used by the reviewed labels.
"""
from __future__ import annotations

import argparse
from collections import Counter
import importlib.metadata
import importlib.util
import json
from pathlib import Path
import platform
import sys
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
import evaluation as ev  # noqa: E402

MODEL_ID = "pose_landmarker_heavy/float16/1"
BACKEND = "MediaPipe Pose Landmarker Heavy"

# MediaPipe indices from the official Pose Landmarker model card/guide.
INDEX_TO_JOINT = {
    0: "nose",
    2: "leftEye",
    5: "rightEye",
    7: "leftEar",
    8: "rightEar",
    11: "leftShoulder",
    12: "rightShoulder",
    13: "leftElbow",
    14: "rightElbow",
    15: "leftWrist",
    16: "rightWrist",
    23: "leftHip",
    24: "rightHip",
    25: "leftKnee",
    26: "rightKnee",
    27: "leftAnkle",
    28: "rightAnkle",
}


def usable_confidence(landmark):
    """Policy v2: require both Heavy scores to be finite probabilities.

    This is an evaluation gate, not calibration against Apple's confidence.
    Missing, nonfinite and out-of-range signals make the point unavailable.
    """
    values = [getattr(landmark, name, None) for name in ("visibility", "presence")]
    if not all(ev.number(x) and 0 <= x <= 1 for x in values):
        return 0.0
    return min(values)


def person_from_landmarks(landmarks, width, height):
    points = []
    for index, joint in INDEX_TO_JOINT.items():
        if index >= len(landmarks):
            continue
        point = landmarks[index]
        x, y = getattr(point, "x", None), getattr(point, "y", None)
        if not ev.number(x) or not ev.number(y):
            continue
        points.append({
            "joint": joint,
            "position": {"x": float(x) * width, "y": float(y) * height},
            "confidence": usable_confidence(point),
            "visibility": point.visibility if ev.number(getattr(point, "visibility", None)) else None,
            "presence": point.presence if ev.number(getattr(point, "presence", None)) else None,
        })
    return {"landmarks": points}


def load_image_dimensions(mp, path):
    # Do not assume MediaPipe applies EXIF like Vision. The fixed PNG pilot is
    # upright already; reject rotated input instead of scoring another frame.
    from PIL import Image
    with Image.open(path) as source:
        ev.require(source.getexif().get(274, 1) == 1, "Input must be pre-oriented upright")
        size = source.size
    image = mp.Image.create_from_file(str(path))
    pixels = image.numpy_view()
    ev.require(pixels.ndim == 3 and pixels.shape[2] == 3, "Expected decoded RGB image")
    ev.require((pixels.shape[1], pixels.shape[0]) == size, "Decoded dimensions changed")
    return image, int(pixels.shape[1]), int(pixels.shape[0])


def model_contract():
    spec = importlib.util.spec_from_file_location("heavy_model", ROOT / "scripts/prepare-mediapipe-model.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def check_model(path):
    contract = model_contract()
    ev.require(contract.MODEL_ID == MODEL_ID and ev.digest(path) == contract.SHA256,
               "Model does not match the pinned Heavy asset")


def read_observations(path):
    with path.open() as stream:
        for index, line in enumerate(stream):
            row = json.loads(line)
            ev.require(row["frameIndex"] == index and row["timebase"] == "frame_index"
                       and row.get("timestamp") is None, "Incomplete or reordered image output")
            ev.require([row["backend"], row["backendVersion"]] == [BACKEND, MODEL_ID],
                       "Backend changed within clip")
            yield row


def run(manifest_path, data_root, output, model_path, public_output=False):
    manifest = ev.read_json(manifest_path)
    clips = ev.validate_manifest(manifest, data_root)
    manifest_hash = ev.digest(manifest_path)
    check_model(model_path)  # Enforced even when caller bypasses the downloader.
    output.mkdir(parents=True, exist_ok=False)
    import mediapipe as mp

    options = mp.tasks.vision.PoseLandmarkerOptions(
        base_options=mp.tasks.BaseOptions(model_asset_path=str(model_path),
                                         delegate=mp.tasks.BaseOptions.Delegate.CPU),
        running_mode=mp.tasks.vision.RunningMode.IMAGE,
        # Two is sufficient to reject ambiguity; one hides second-person evidence.
        num_poses=2,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        output_segmentation_masks=False,
    )
    records = []
    with mp.tasks.vision.PoseLandmarker.create_from_options(options) as landmarker:
        for clip in clips:
            record = {key: clip.get(key) for key in
                      ("id", "dataset", "exercise", "split", "source_group", "subject_group")}
            record.update(status=ev.preflight(clip, data_root, public_output),
                          media_sha256=[x["sha256"] for x in clip["media"]["files"]],
                          pose_metrics={"status": "not_evaluated"},
                          rep_metrics={"status": "not_implemented"})
            records.append(record)
            if record["status"] != "ready":
                continue
            if clip["media"]["kind"] != "images":
                record["status"] = "unsupported_media_kind"
                continue
            directory = output / clip["id"]
            directory.mkdir()
            obs_path = directory / "observations.jsonl"
            count, timings, person_counts = 0, [], Counter()
            status = "engine_failure"
            try:
                reference = None
                if clip.get("annotations"):
                    reference = ev.validate_reference(
                        ev.read_json(ev.asset_path(data_root, clip["annotations"])), clip)
                    record["annotation_sha256"] = clip["annotations"]["sha256"]
                with obs_path.open("w") as stream:
                    for index, asset in enumerate(clip["media"]["files"]):
                        started = time.perf_counter_ns()
                        image, width, height = load_image_dimensions(mp, ev.asset_path(data_root, asset))
                        result = landmarker.detect(image)
                        elapsed = (time.perf_counter_ns() - started) / 1_000_000.0
                        people = [person_from_landmarks(points, width, height)
                                  for points in result.pose_landmarks]
                        row = {"frameIndex": index, "timebase": "frame_index", "timestamp": None,
                               "imageSize": {"width": width, "height": height}, "people": people,
                               "backend": BACKEND, "backendVersion": MODEL_ID,
                               "processingMilliseconds": elapsed}
                        stream.write(json.dumps(row, sort_keys=True, allow_nan=False) + "\n")
                        count += 1
                        timings.append(elapsed)
                        person_counts[len(people)] += 1
                ev.require(count == len(clip["media"]["files"]), "Incomplete image sequence")
                ev.require(ev.preflight(clip, data_root, public_output) == "ready"
                           and ev.digest(manifest_path) == manifest_hash, "Input changed during inference")
                check_model(model_path)
                metrics = (ev.score_pose(reference, read_observations(obs_path),
                                        manifest.get("confidence_threshold", 0.3))
                           if reference is not None else {"status": "no_independent_labels"})
                record.update(frames=count, backend=[BACKEND, MODEL_ID], pose_metrics=metrics,
                              observations_sha256=ev.digest(obs_path),
                              processing_ms=ev.statistics(timings, count),
                              people_per_frame=dict(sorted(person_counts.items())))
                status = "processed"
            except (OSError, ValueError, RuntimeError, KeyError, TypeError) as error:
                # Retain partial output, not a score. Never export raw exception text.
                record["failure_type"] = type(error).__name__
            record["status"] = status
            ev.write_json(directory / "completion.json", {"status": status, "frames": count})

    # A later clip must not change an earlier clip or its review after scoring.
    try:
        stable = ev.digest(manifest_path) == manifest_hash
        check_model(model_path)
    except (ValueError, OSError):
        stable = False
    for clip, record in zip(clips, records):
        if record["status"] == "processed" and (
                not stable or ev.preflight(clip, data_root, public_output) != "ready"):
            record.update(status="integrity_failure", pose_metrics={"status": "not_evaluated"})
            ev.write_json(output / clip["id"] / "completion.json",
                          {"status": "integrity_failure", "frames": record["frames"]})
    commit = subprocess.run(["git", "rev-parse", "HEAD"], cwd=ROOT, capture_output=True,
                            text=True, check=False).stdout.strip() or None
    sources = [Path(__file__), ROOT / "scripts/evaluation.py", ROOT / "scripts/prepare-mediapipe-model.py",
               ROOT / "Evaluation/mediapipe-requirements.txt"]
    report = {
        "schema_version": 1, "manifest_sha256": manifest_hash,
        "model_sha256": model_contract().SHA256, "backend": [BACKEND, MODEL_ID],
        "source_commit": commit,
        "source_files_sha256": {str(p.relative_to(ROOT)): ev.digest(p) for p in sources},
        "runtime": {"mediapipe": mp.__version__, "python": platform.python_version(),
                    "operating_system": platform.system() + " " + platform.release(),
                    "machine": platform.machine(),
                    "packages": {name: importlib.metadata.version(name) for name in ("numpy", "Pillow")}},
        "options": {"mode": "IMAGE", "delegate": "CPU", "num_poses": 2,
                    "min_pose_detection_confidence": 0.5, "min_pose_presence_confidence": 0.5},
        "confidence_threshold": manifest.get("confidence_threshold", 0.3),
        "confidence_policy": "v2: min(visibility,presence); both must be finite in [0,1], else zero",
        "timing_scope": "image metadata check, decode and inference; host diagnostic only",
        "scope": "same upright source files; no cross-decoder pixel-equality, iOS speed or accuracy qualification",
        "counts_by_status": dict(Counter(x["status"] for x in records)), "clips": records,
    }
    ev.write_json(output / "report.json", report)
    return 0 if all(x["status"] == "processed" for x in records) else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    parser.add_argument("--public-output", action="store_true")
    args = parser.parse_args()
    try:
        return run(args.manifest.resolve(), args.root.resolve(), args.output.resolve(),
                   args.model.resolve(), args.public_output)
    except (OSError, ValueError, RuntimeError) as error:
        print(f"MediaPipe evaluation failed ({type(error).__name__}); no complete score.", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
