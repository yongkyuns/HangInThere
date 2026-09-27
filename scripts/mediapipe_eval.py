#!/usr/bin/env python3
"""Run MediaPipe Pose Landmarker Heavy on an existing HangInThere image manifest.

Host-only comparison tool. It deliberately does not modify the iOS app target.
Only ordered image sequences are supported in this first comparison so both
backends see the exact same pre-oriented pixels used by the independent labels.
"""
from __future__ import annotations

import argparse
import json
import math
from pathlib import Path
import platform
import sys
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


def finite(value):
    return isinstance(value, (int, float)) and math.isfinite(value)


def usable_confidence(landmark):
    """Conservative common confidence for the shared scorer.

    MediaPipe exposes visibility and presence separately. HangInThere has one
    generic confidence field, so use the minimum when both are valid. This
    prevents a highly visible-but-low-presence or present-but-occluded point
    from looking stronger than either signal supports.
    """
    values = []
    for name in ("visibility", "presence"):
        value = getattr(landmark, name, None)
        if finite(value):
            values.append(max(0.0, min(1.0, float(value))))
    return min(values) if values else 0.0


def person_from_landmarks(landmarks, width, height):
    points = []
    for index, joint in INDEX_TO_JOINT.items():
        if index >= len(landmarks):
            continue
        point = landmarks[index]
        x, y = getattr(point, "x", None), getattr(point, "y", None)
        if not finite(x) or not finite(y):
            continue
        points.append({
            "joint": joint,
            "position": {"x": float(x) * width, "y": float(y) * height},
            "confidence": usable_confidence(point),
        })
    return {"landmarks": points}


def load_image_dimensions(mp, path):
    image = mp.Image.create_from_file(str(path))
    # Current MediaPipe Python Image exposes numpy_view(). Keep this explicit so
    # dimensions come from the actual decoded pixels rather than manifest text.
    pixels = image.numpy_view()
    if pixels.ndim < 2 or pixels.shape[0] <= 0 or pixels.shape[1] <= 0:
        raise ValueError("Invalid decoded image")
    return image, int(pixels.shape[1]), int(pixels.shape[0])


def run(manifest_path, data_root, output, model_path):
    import mediapipe as mp

    manifest = ev.read_json(manifest_path)
    clips = ev.validate_manifest(manifest, data_root)
    output.mkdir(parents=True, exist_ok=False)

    BaseOptions = mp.tasks.BaseOptions
    PoseLandmarker = mp.tasks.vision.PoseLandmarker
    PoseLandmarkerOptions = mp.tasks.vision.PoseLandmarkerOptions
    RunningMode = mp.tasks.vision.RunningMode
    options = PoseLandmarkerOptions(
        base_options=BaseOptions(model_asset_path=str(model_path)),
        running_mode=RunningMode.IMAGE,
        num_poses=1,
        min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5,
        output_segmentation_masks=False,
    )

    records = []
    with PoseLandmarker.create_from_options(options) as landmarker:
        for clip in clips:
            record = {
                "id": clip["id"], "dataset": clip["dataset"], "exercise": clip["exercise"],
                "split": clip["split"], "source_group": clip["source_group"],
                "subject_group": clip.get("subject_group"), "status": "not_evaluated",
                "pose_metrics": {"status": "not_evaluated"},
            }
            status = ev.preflight(clip, data_root, public_output=True)
            if status != "ready":
                record["status"] = status
                records.append(record)
                continue
            if clip["media"]["kind"] != "images":
                record["status"] = "unsupported_media_kind"
                records.append(record)
                continue
            reference = None
            if clip.get("annotations"):
                reference = ev.validate_reference(
                    ev.read_json(ev.asset_path(data_root, clip["annotations"])), clip
                )
            observations = []
            timings = []
            for frame_index, spec in enumerate(clip["media"]["files"]):
                path = ev.asset_path(data_root, spec)
                image, width, height = load_image_dimensions(mp, path)
                started = time.perf_counter_ns()
                result = landmarker.detect(image)
                elapsed_ms = (time.perf_counter_ns() - started) / 1_000_000.0
                timings.append(elapsed_ms)
                people = []
                if result.pose_landmarks:
                    # num_poses=1, but preserve zero/one semantics explicitly.
                    people = [person_from_landmarks(result.pose_landmarks[0], width, height)]
                observations.append({
                    "frameIndex": frame_index,
                    "timebase": "frame_index",
                    "timestamp": None,
                    "imageSize": {"width": width, "height": height},
                    "people": people,
                    "backend": BACKEND,
                    "backendVersion": MODEL_ID,
                    "processingMilliseconds": elapsed_ms,
                })
            directory = output / clip["id"]
            directory.mkdir()
            obs_path = directory / "observations.jsonl"
            with obs_path.open("w") as stream:
                for observation in observations:
                    stream.write(json.dumps(observation, sort_keys=True, allow_nan=False) + "\n")
            record.update(
                status="processed",
                frames=len(observations),
                backend=[BACKEND, MODEL_ID],
                observations_sha256=ev.digest(obs_path),
                processing_ms=ev.statistics(timings, len(observations)),
            )
            if reference is not None:
                record["pose_metrics"] = ev.score_pose(
                    reference, iter(observations), manifest.get("confidence_threshold", 0.3)
                )
            else:
                record["pose_metrics"] = {"status": "no_independent_labels"}
            records.append(record)

    report = {
        "schema_version": 1,
        "manifest_sha256": ev.digest(manifest_path),
        "model_sha256": ev.digest(model_path),
        "backend": [BACKEND, MODEL_ID],
        "runtime": {"mediapipe": getattr(mp, "__version__", "unknown"), "python": platform.python_version()},
        "confidence_policy": "min(visibility,presence), clamped to [0,1]; scorer threshold from manifest",
        "scope": "host-only 2D pose comparison on identical labelled image pixels; no iOS performance claim",
        "counts_by_status": dict(__import__("collections").Counter(x["status"] for x in records)),
        "clips": records,
    }
    ev.write_json(output / "report.json", report)
    return 0 if all(x["status"] == "processed" for x in records) else 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--model", type=Path, required=True)
    args = parser.parse_args()
    raise SystemExit(run(args.manifest.resolve(), args.root.resolve(), args.output.resolve(), args.model.resolve()))


if __name__ == "__main__":
    main()