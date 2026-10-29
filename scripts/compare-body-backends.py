#!/usr/bin/env python3
"""Bounded Mac body SDK diagnostic on exact production-decoded RGB and PTS.

No counter, labels, ROI, grip inference or production-source mutation. Each frame
retains both fresh SDK outputs and all MediaPipe raw image/world candidates.
"""
import argparse
import dataclasses
import hashlib
import importlib.metadata
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import time


JOINTS = [("nose", 0), ("leftEye", 2), ("rightEye", 5), ("leftEar", 7), ("rightEar", 8),
          ("leftShoulder", 11), ("rightShoulder", 12), ("leftElbow", 13), ("rightElbow", 14),
          ("leftWrist", 15), ("rightWrist", 16), ("leftHip", 23), ("rightHip", 24),
          ("leftKnee", 25), ("rightKnee", 26), ("leftAnkle", 27), ("rightAnkle", 28)]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_exact(stream, size):
    chunks = bytearray()
    while len(chunks) < size:
        block = stream.read(size-len(chunks))
        if not block:
            raise EOFError("Truncated native RGBA frame")
        chunks.extend(block)
    return bytes(chunks)


def convert(raw, timestamp, width, height, backend):
    people = []
    for person in raw:
        assert len(person) == 33
        landmarks = []
        for name, index in JOINTS:
            point = person[index]
            score = min(point["visibility"] or 0.0, point["presence"] or 0.0)
            x, y = point["x"], point["y"]
            # Match the app's 2D source-pixel contract. Retain raw out-of-frame
            # SDK points separately; never clamp them into the original image.
            if all(math.isfinite(v) for v in (x, y, score)) and 0 <= x <= 1 and 0 <= y <= 1 and 0 < score <= 1:
                landmarks.append(dict(joint=name, position=dict(x=x*width, y=y*height), confidence=score))
        people.append(dict(landmarks=landmarks))
    return dict(timestamp=timestamp, imageSize=dict(width=width, height=height), people=people,
                backend=backend, requestRevision=1)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("config", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    config_bytes = args.config.read_bytes()
    config = json.loads(config_bytes)
    args.output.mkdir(parents=True, exist_ok=False)
    os.environ["MPLCONFIGDIR"] = str(args.output/"matplotlib-cache")
    os.environ["XDG_CACHE_HOME"] = str(args.output/"runtime-cache")
    import mediapipe as mp
    import numpy as np
    from PIL import Image
    from mediapipe.tasks.python import BaseOptions
    from mediapipe.tasks.python.vision import PoseLandmarker, PoseLandmarkerOptions, RunningMode

    assert importlib.metadata.version("mediapipe") == "0.10.32"
    binary = args.config.parent/"pixel-stream"
    model = Path("models/grip/pose_landmarker_lite.task")
    assert sha(model) == "59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a"
    backend = "MediaPipePoseLite_Python0.10.32_CPU_IMAGE"
    options = PoseLandmarkerOptions(base_options=BaseOptions(model_asset_path=str(model), delegate=BaseOptions.Delegate.CPU),
        running_mode=RunningMode.IMAGE, num_poses=2, min_pose_detection_confidence=0.5,
        min_pose_presence_confidence=0.5, min_tracking_confidence=0.5, output_segmentation_masks=False)
    for case in config["cases"]:
        directory = args.output/case["id"]
        directory.mkdir()
        expected = [json.loads(line) for line in Path(case["expectedFrameProof"]).open()]
        assert sha(case["path"]) == case["sha256"]
        matched_provider = compared_provider = matched_cached = 0
        seen = 0
        with PoseLandmarker.create_from_options(options) as model_instance, \
                (directory/"raw-models.jsonl").open("x") as output, \
                (directory/"native-transport.log").open("x") as log:
            process = subprocess.Popen([str(binary), str(args.config), case["id"]], stdout=subprocess.PIPE, stderr=log)
            try:
                begin = json.loads(process.stdout.readline())
                assert begin["kind"] == "source" and begin["sourceSHA256"] == case["sha256"]
                assert begin["selectedFrames"] == len(expected)
                while True:
                    frame = json.loads(process.stdout.readline())
                    if frame["kind"] == "end":
                        end = frame
                        break
                    assert frame["kind"] == "frame" and frame["ordinal"] == seen
                    proof = expected[seen]
                    assert frame["timestamp"] == proof["timestamp"]
                    assert dict(width=frame["width"], height=frame["height"]) == proof["imageSize"]
                    assert frame["payloadBytes"] == frame["width"]*frame["height"]*4
                    payload = read_exact(process.stdout, frame["payloadBytes"])
                    assert hashlib.sha256(payload).hexdigest() == frame["rgbaSHA256"]
                    if proof.get("rgbaSHA256") is not None:
                        assert proof["rgbaSHA256"] == frame["rgbaSHA256"]
                    if proof.get("providerSHA256") is not None:
                        compared_provider += 1
                        matched_provider += proof["providerSHA256"] == frame["providerSHA256"]
                    assert frame["visionPose"]["timestamp"] == frame["timestamp"]
                    matched_cached += frame["visionPose"] == proof["visionPose"]
                    # No resampling, crop, rotation, mirror or learned quality
                    # gate. Fresh Vision received the exact source CGImage;
                    # MediaPipe receives its opaque sRGB top-left RGB pixels.
                    rgba = np.frombuffer(payload, dtype=np.uint8).reshape(frame["height"], frame["width"], 4)
                    assert np.all(rgba[:, :, 3] == 255)
                    rgb = np.ascontiguousarray(rgba[:, :, :3])
                    began = time.perf_counter()
                    result = model_instance.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=rgb))
                    elapsed = (time.perf_counter()-began)*1000
                    raw = [[dataclasses.asdict(point) for point in person] for person in result.pose_landmarks]
                    world = [[dataclasses.asdict(point) for point in person] for person in result.pose_world_landmarks]
                    assert len(raw) == len(world)
                    converted = convert(raw, frame["timestamp"], frame["width"], frame["height"], backend)
                    row = dict(ordinal=seen, timestamp=frame["timestamp"], providerSHA256=frame["providerSHA256"],
                        rgbaSHA256=frame["rgbaSHA256"], visionPose=frame["visionPose"], pose=converted,
                        rawMediaPipe=raw, rawMediaPipeWorld=world, mediaPipeMilliseconds=elapsed,
                        referenceProviderMatches=proof.get("providerSHA256") == frame["providerSHA256"] if proof.get("providerSHA256") else None,
                        freshVisionMatchesCache=frame["visionPose"] == proof["visionPose"], sourceWindowId=proof.get("sourceWindowId"))
                    output.write(json.dumps(row, separators=(",", ":"), allow_nan=False)+"\n")
                    seconds = frame["timestamp"]["value"]/frame["timestamp"]["timescale"]
                    if case["id"] == "new-rear-dip-full-source" and any(abs(seconds-t) < .000001 for t in [11.44, 17.8, 28.0, 41.8, 44.2]):
                        Image.fromarray(rgb).save(directory/f"source-{seconds:.2f}.png")
                    seen += 1
                    if seen % 300 == 0:
                        output.flush()
                        print(case["id"], seen, "verified original-time frames", flush=True)
                assert process.stdout.read() == b"" and process.wait(timeout=60) == 0
            finally:
                # Drain a producer after a consumer-side failure so its normal
                # close removes only its own private import copy. No global tmp
                # deletion or user recording cleanup is performed.
                if process.poll() is None:
                    shutil.copyfileobj(process.stdout, open(os.devnull, "wb"))
                    process.wait(timeout=60)
            assert seen == len(expected) == end["selectedFrames"] and end["sourceClockVerified"]
            assert args.config.read_bytes() == config_bytes and sha(case["path"]) == case["sha256"]
        summary = dict(id=case["id"], frames=seen, sourceSHA256=case["sha256"], nativeBegin=begin, nativeEnd=end,
            sourceClockVerified=True, receivedRGBAPayloadHashVerified=True,
            providerHashesCompared=compared_provider, providerHashesMatching=matched_provider,
            freshVisionRawPosesMatchingCached=matched_cached, rawModelsSHA256=sha(directory/"raw-models.jsonl"),
            backend=backend, modelSHA256=sha(model), options=dict(runningMode="IMAGE", numPoses=2,
                minDetection=.5, minPresence=.5, minTracking=.5, delegate="CPU"),
            scope="Fresh Mac SDK component outputs on identical production-decoded frames; no controller, counters, physical iPhone inference, mobile parity, throughput or qualification.")
        (directory/"export.json").write_text(json.dumps(summary, indent=2)+"\n")
        print("Completed", case["id"], seen, "frames; prior provider hashes", matched_provider, "/", compared_provider,
              "fresh Vision cache matches", matched_cached, "/", seen, flush=True)


if __name__ == "__main__":
    main()
