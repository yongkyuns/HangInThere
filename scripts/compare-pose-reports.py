#!/usr/bin/env python3
"""Create a factual, non-ranked Vision/MediaPipe metric comparison."""
from __future__ import annotations
import argparse, json
from pathlib import Path


def load(path):
    return json.loads(Path(path).read_text())


def metric(summary):
    return {k: summary.get(k) for k in ("reference_count", "measured_count", "coverage", "mean", "p95")}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("vision", type=Path)
    parser.add_argument("mediapipe", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    vision, mp = load(args.vision), load(args.mediapipe)
    if vision["manifest_sha256"] != mp["manifest_sha256"]:
        raise SystemExit("Reports did not use the same manifest")
    va = {x["id"]: x for x in vision["clips"]}
    mb = {x["id"]: x for x in mp["clips"]}
    if set(va) != set(mb):
        raise SystemExit("Reports contain different clips")
    clips = []
    for cid in sorted(va):
        v, m = va[cid], mb[cid]
        if v["pose_metrics"].get("status") != "measured_2d_only" or m["pose_metrics"].get("status") != "measured_2d_only":
            raise SystemExit(f"{cid}: both backends need measured labels")
        joints = sorted(set(v["pose_metrics"]["joint_pixels"]) | set(m["pose_metrics"]["joint_pixels"]))
        clips.append({
            "id": cid,
            "reference_points": sum(x["reference_count"] for x in v["pose_metrics"]["joint_pixels"].values()),
            "vision": {
                "backend": v["backend"],
                "processing_ms": v["processing_ms"],
                "joint_pixels": {j: metric(v["pose_metrics"]["joint_pixels"].get(j, {})) for j in joints},
                "elbow_degrees": {s: metric(v["pose_metrics"]["elbow_degrees"][s]) for s in ("left", "right")},
            },
            "mediapipe": {
                "backend": m["backend"],
                "processing_ms": m["processing_ms"],
                "joint_pixels": {j: metric(m["pose_metrics"]["joint_pixels"].get(j, {})) for j in joints},
                "elbow_degrees": {s: metric(m["pose_metrics"]["elbow_degrees"][s]) for s in ("left", "right")},
            },
        })
    output = {
        "schema_version": 1,
        "manifest_sha256": vision["manifest_sha256"],
        "scope": "descriptive same-label comparison; no winner, iOS throughput, rep, form or 3D claim",
        "clips": clips,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(output, indent=2, sort_keys=True, allow_nan=False) + "\n")