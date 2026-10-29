#!/usr/bin/env python3
"""Local SDK stages for learned grip data; no labels and no policy decisions.

body FRAMES_DIRECTORY OUTPUT_JSONL
hands PLANNED_JSONL FRAMES_DIRECTORY OUTPUT_JSONL
Between stages, BinaryGripLearningPipelineTests emits the real shared crop plan.
"""
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import sys

os.environ.setdefault("OMP_NUM_THREADS", "2")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
import mediapipe as mp
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
BODY = [("leftShoulder", 11), ("rightShoulder", 12), ("leftHip", 23), ("rightHip", 24), ("leftWrist", 15), ("rightWrist", 16)]
HAND = [("wrist", 0), ("indexMCP", 5), ("middleMCP", 9), ("pinkyMCP", 17), ("thumbCMC", 1), ("thumbTip", 4)]


def point(p):
    return dict(x=float(p.x), y=float(p.y), z=float(p.z))


def pixels(row, directory):
    path = directory / row["image"]
    assert hashlib.sha256(path.read_bytes()).hexdigest() == row["imageSHA256"], "Changed image"
    with Image.open(path) as image:
        return np.asarray(image.convert("RGB"))


def main():
    mode = sys.argv[1]
    manifest = json.loads((ROOT / "models/grip/manifest.json").read_text())
    for model in manifest["models"]:
        assert hashlib.sha256((ROOT / "models/grip" / model["file"]).read_bytes()).hexdigest() == model["sha256"]
    if mode == "body":
        directory, output = map(Path, sys.argv[2:])
        source = directory / "frames.jsonl"
        options = mp.tasks.vision.PoseLandmarkerOptions(base_options=mp.tasks.BaseOptions(
            model_asset_path=str(ROOT / "models/grip/pose_landmarker_lite.task"), delegate=mp.tasks.BaseOptions.Delegate.CPU), num_poses=2,
            min_pose_detection_confidence=.5, min_pose_presence_confidence=.5)
        with mp.tasks.vision.PoseLandmarker.create_from_options(options) as sdk, output.open("x") as stream:
            for line in source.read_text().splitlines():
                row = json.loads(line); pose = row["pose"]
                tracked = [dict(joint=p["joint"], position=p["position"], score=p["confidence"])
                           for p in (pose["people"][0]["landmarks"] if len(pose["people"]) == 1 else []) if p["joint"] in dict(BODY)]
                body = []
                result = sdk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=pixels(row, directory)))
                if len(result.pose_landmarks) == len(result.pose_world_landmarks) == 1:
                    size = pose["imageSize"]
                    for name, i in BODY:
                        p = result.pose_landmarks[0][i]; world = result.pose_world_landmarks[0][i]
                        body.append(dict(joint=name, position=dict(x=float(p.x)*size["width"], y=float(p.y)*size["height"]),
                                         world=point(world), score=float(min(p.visibility, p.presence))))
                row["observation"] = dict(seconds=pose["timestamp"]["value"]/pose["timestamp"]["timescale"],
                    imageSize=pose["imageSize"], trackedBody=tracked, body=body, hands=[],
                    modelId="mediapipe_hand_full_v1_pose_lite_v1", backend="MediaPipe Python " + importlib.metadata.version("mediapipe") + " local training")
                stream.write(json.dumps(row, allow_nan=False) + "\n")
    elif mode == "hands":
        source, directory, output = map(Path, sys.argv[2:])
        options = mp.tasks.vision.HandLandmarkerOptions(base_options=mp.tasks.BaseOptions(
            model_asset_path=str(ROOT / "models/grip/hand_landmarker.task"), delegate=mp.tasks.BaseOptions.Delegate.CPU), num_hands=1,
            min_hand_detection_confidence=.5, min_hand_presence_confidence=.5)
        with mp.tasks.vision.HandLandmarker.create_from_options(options) as sdk, output.open("x") as stream:
            for line in source.read_text().splitlines():
                row = json.loads(line); image = pixels(row, directory); candidates = []
                for request in row["requests"]:
                    for region in request["regions"]:
                        x, y, w, h = [region[k] for k in ["left", "top", "width", "height"]]
                        crop = np.ascontiguousarray(image[y:y+h, x:x+w])
                        result = sdk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=crop))
                        hands = []
                        for points, world, categories in zip(result.hand_landmarks, result.hand_world_landmarks, result.handedness):
                            if len(points) != 21 or len(world) != 21:
                                continue
                            hand = dict(points=[dict(joint=name, position=dict(x=x+float(points[i].x)*w, y=y+float(points[i].y)*h), world=point(world[i])) for name, i in HAND], sourceRegion=region)
                            if categories:
                                hand.update(sdkHandedness=categories[0].category_name, sdkHandednessScore=float(categories[0].score))
                            hands.append(hand)
                        candidates.append(dict(side=request["side"], hands=hands))
                # Preserve all ordered candidates. Shared code, not a desired
                # grip label, picks the first anatomically valid hand per side.
                row["candidates"] = candidates
                stream.write(json.dumps(row, allow_nan=False) + "\n")
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main()
