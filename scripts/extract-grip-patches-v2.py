#!/usr/bin/env python3
"""Extract per-hand RGB patches from source-bound research policy-v2 plans.

Labels are carried unchanged for a later training join. Body/hand landmarks only
locate/associate the patch. No landmark vector, depth, SDK chirality or scene
pixels outside the hand/short forearm crop are classifier inputs.
"""
import os
os.environ.setdefault("OMP_NUM_THREADS", "2")
import argparse
import hashlib
import json
import math
from collections import Counter
from pathlib import Path
import mediapipe as mp
import numpy as np
from PIL import Image

ROOT = Path(__file__).absolute().parents[1]


def distance(a, b):
    return math.hypot(a[0]-b[0], a[1]-b[1])


def hand_patch(points, toward, size):
    """Small square enclosing hand with a short segment toward the elbow."""
    width, height = size
    lo = np.min(points, axis=0); hi = np.max(points, axis=0)
    extent = float(max(hi-lo))
    if not np.isfinite(points).all() or extent < 16:
        return None
    side = int(math.ceil(extent*1.5))
    if side > min(width, height)*.45:
        return None
    center = (lo+hi)/2
    delta = np.asarray(toward)-points[0]
    norm = float(np.linalg.norm(delta))
    if norm > 0:
        center += delta/norm*extent*.12
    left = min(max(0, int(math.floor(center[0]-side/2))), width-side)
    top = min(max(0, int(math.floor(center[1]-side/2))), height-side)
    return [left, top, left+side, top+side]


def iou(a, b):
    intersection = max(0, min(a[2], b[2])-max(a[0], b[0])) * max(0, min(a[3], b[3])-max(a[1], b[1]))
    return intersection/((a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("plans", type=Path); parser.add_argument("output", type=Path)
    parser.add_argument("--manual-training-regions",type=Path,
                        help="Reviewed source-bound training search regions; never permitted for validation or test")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    model = ROOT/"models/grip/hand_landmarker.task"
    manifest = json.loads((ROOT/"models/grip/manifest.json").read_text())
    expected = next(m["sha256"] for m in manifest["models"] if m["file"] == model.name)
    assert hashlib.sha256(model.read_bytes()).hexdigest() == expected
    options = mp.tasks.vision.HandLandmarkerOptions(base_options=mp.tasks.BaseOptions(
        model_asset_path=str(model), delegate=mp.tasks.BaseOptions.Delegate.CPU), num_hands=2,
        min_hand_detection_confidence=.5, min_hand_presence_confidence=.5)
    counts = Counter()
    manual = json.loads(args.manual_training_regions.read_text()) if args.manual_training_regions else None
    manual_sha = hashlib.sha256(args.manual_training_regions.read_bytes()).hexdigest() if manual else None
    with mp.tasks.vision.HandLandmarker.create_from_options(options) as sdk, (args.output/"patches.jsonl").open("x") as stream:
        for line in args.plans.read_text().splitlines():
            row = json.loads(line); plan = row["patchPlan"]; pose = row["pose"]
            assert plan["sourceId"] == row["recordingId"] and plan["timestamp"] == pose["timestamp"]
            assert plan["imageSize"] == pose["imageSize"]
            if manual:
                assert row["annotation"]["split"]=="train","Manual regions cannot alter held-out inputs"
                entry=next(e for e in manual["sources"] if e["recordingId"]==row["recordingId"])
                assert entry["imageSize"]==pose["imageSize"] and entry["trainingOnly"] is True
                t=pose["timestamp"]["value"]/pose["timestamp"]["timescale"]
                assert entry["start"]<=t<=entry["end"]
                row["manualPlan"]=dict(sourceId=row["recordingId"],timestamp=pose["timestamp"],imageSize=pose["imageSize"],
                    status="manualTrainingRegions",targets=entry["targets"],annotationSHA256=manual_sha)
                plan=row["manualPlan"]
            path = Path(row["framesDirectory"])/row["image"]
            assert hashlib.sha256(path.read_bytes()).hexdigest() == row["imageSHA256"]
            image = Image.open(path).convert("RGB")
            assert image.size == (pose["imageSize"]["width"], pose["imageSize"]["height"])
            short = min(image.size); pixels = np.asarray(image)
            results = []
            for side in ["left", "right"]:
                result = dict(side=side, status='wristUnavailable' if plan['status']=='ready' else plan["status"])
                targets = [t for t in plan.get("targets", []) if t["side"] == side]
                for target in targets:
                    wrist = [target["wrist"][k] for k in ["x", "y"]]
                    other = [target["otherWrist"][k] for k in ["x", "y"]] if target.get('otherWrist') else None
                    result["status"] = "handNotDetected"
                    for region in target["regions"]:
                        x,y,w,h = [region[k] for k in ["left", "top", "width", "height"]]
                        crop = np.ascontiguousarray(pixels[y:y+h,x:x+w])
                        detected = sdk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=crop))
                        valid = []
                        for hand in detected.hand_landmarks:
                            if len(hand) != 21: continue
                            points = np.array([[x+p.x*w, y+p.y*h] for p in hand])
                            if not np.isfinite(points).all(): continue
                            own_distance = distance(points[0], wrist)
                            if own_distance > short*.10 or (other is not None and own_distance+short*.025 >= distance(points[0], other)):
                                result["status"] = "handOwnershipAmbiguous"; continue
                            valid.append((own_distance, points))
                        if len(valid) > 1:
                            result["status"] = "multipleHandsAtTarget"; continue
                        if not valid: continue
                        points = valid[0][1]
                        box = hand_patch(points, [target["forearmToward"][k] for k in ["x", "y"]], image.size)
                        if box is None:
                            result["status"] = "handPatchTooSmallOrInvalid"; continue
                        result.update(status="patchReady", box=box, searchRegion=region,
                                      detectedWrist=points[0].tolist())
                        break
                results.append(result)
            if all(r["status"] == "patchReady" for r in results) and (
                    distance(results[0]["detectedWrist"], results[1]["detectedWrist"]) < short*.06 or
                    iou(results[0]["box"], results[1]["box"]) > .35):
                for r in results:
                    r["status"] = "overlappingHandPatches"
            for result in results:
                if result["status"] == "patchReady":
                    patch = image.crop(result["box"]).resize((128,128), Image.Resampling.BILINEAR)
                    relative = Path(row["caseId"])/(Path(row["image"]).stem+"-"+result["side"]+".png")
                    destination = args.output/relative; destination.parent.mkdir(parents=True,exist_ok=True)
                    patch.save(destination)
                    result.update(patch=str(destination.absolute()),patchSHA256=hashlib.sha256(destination.read_bytes()).hexdigest())
                    if manual:result.update(origin="manualTrainingRegion",manualAnnotationSHA256=manual_sha)
                counts[result["status"]] += 1
            row["patches"] = results
            stream.write(json.dumps(row, allow_nan=False)+"\n")
    (args.output/"extraction.json").write_text(json.dumps(dict(policyVersion=2, handModelSHA256=expected,
        patchSize=128, inputSHA256=hashlib.sha256(args.plans.read_bytes()).hexdigest(), counts=dict(counts)),indent=2)+"\n")
    print(json.dumps(dict(counts)))


if __name__ == "__main__":
    main()
