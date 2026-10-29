#!/usr/bin/env python3
"""Train a tiny, local two-grip classifier from real shared-code features.

Input JSONL rows: features[19], featureVersion=1, label=overhand|underhand,
recordingId=video SHA256, subjectId, split=train|validation|test, seconds, side.
Recordings AND identified people must be disjoint across splits. Unknown subject
IDs are allowed only in train-only --development-only mode, never as evidence of
person independence. That mode reports resubstitution, not test accuracy.
Model promotion/in-app enablement is deliberately outside this trainer.
"""
import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import re

os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
import numpy as np

LABELS = ["overhand", "underhand"]
FEATURE_COUNT = 19


def validate(rows, development):
    if not rows:
        raise ValueError("No labelled hand features")
    identities, records, subjects = set(), {}, {}
    for r in rows:
        if r.get("featureVersion") != 1 or r.get("label") not in LABELS or r.get("split") not in ["train", "validation", "test"]:
            raise ValueError("Unexpected feature version, label or split")
        features = r.get("features", [])
        if len(features) != FEATURE_COUNT or not all(type(x) in [int, float] and math.isfinite(x) and abs(x) <= 8 for x in features):
            raise ValueError("Invalid hand features")
        if not re.fullmatch("[0-9a-f]{64}", r.get("recordingId", "")) or (not development and not r.get("subjectId")):
            raise ValueError("Missing recording hash/subject group")
        if r.get("subjectId") is not None and (not isinstance(r["subjectId"], str) or not r["subjectId"]):
            raise ValueError("Invalid known subject group")
        if r.get("side") not in ["left", "right"] or not isinstance(r.get("seconds"), (int, float)) or not math.isfinite(r["seconds"]):
            raise ValueError("Invalid source time/anatomical side")
        identity = (r["recordingId"], r["seconds"], r["side"])
        if identity in identities:
            raise ValueError("Duplicate source sample")
        identities.add(identity)
        for groups, key in [(records, r["recordingId"])] + ([(subjects, r["subjectId"])] if r.get("subjectId") else []):
            if key in groups and groups[key] != r["split"]:
                raise ValueError("Recording or subject leaks across dataset splits")
            groups[key] = r["split"]
    splits = {r["split"] for r in rows}
    if development and splits != {"train"}:
        raise ValueError("Development-only mode requires train-only data; do not relabel training as testing")
    if not development and splits != {"train", "validation", "test"}:
        raise ValueError("Need independent train/validation/test recordings and people, or --development-only")
    for split in splits:
        if {r["label"] for r in rows if r["split"] == split} != set(LABELS):
            raise ValueError("Every split needs both real grip classes")


def scores(model, features):
    x = (np.asarray(features) - model["mean"]) / model["scale"]
    hidden = np.maximum(x @ np.array(model["hiddenWeights"]).T + model["hiddenBias"], 0)
    logits = hidden @ model["outputWeights"] + model["outputBias"]
    p = 1 / (1 + np.exp(-np.clip(logits, -700, 700)))
    valid = (np.abs(x) <= 8).all(axis=1)
    return p, valid


def train(rows, data_hash, epochs=600):
    selected = [r for r in rows if r["split"] == "train"]
    raw = np.asarray([r["features"] for r in selected], dtype=np.float64)
    y = np.array([LABELS.index(r["label"]) for r in selected], dtype=np.float64)
    mean = raw.mean(axis=0); scale = np.maximum(raw.std(axis=0), .1); x = (raw-mean)/scale
    rng = np.random.default_rng(31)
    w = rng.normal(0, .12, (FEATURE_COUNT, 12)); b = np.zeros(12)
    v = rng.normal(0, .12, 12); bias = 0.0
    balancing = np.where(y == 1, len(y)/(2*sum(y == 1)), len(y)/(2*sum(y == 0)))
    for _ in range(epochs):
        h0 = x @ w + b; h = np.maximum(h0, 0)
        p = 1/(1+np.exp(-np.clip(h @ v + bias, -40, 40)))
        error = (p-y)*balancing/len(y)
        dw = x.T @ ((error[:,None]*v)*(h0 > 0)) + .001*w
        db = ((error[:,None]*v)*(h0 > 0)).sum(axis=0)
        dv = h.T @ error + .001*v
        w -= .05*dw; b -= .05*db; v -= .05*dv; bias -= .05*error.sum()
    return dict(schemaVersion=1, featureVersion=1, labels=LABELS, mean=mean.tolist(), scale=scale.tolist(),
        hiddenWeights=w.T.tolist(), hiddenBias=b.tolist(), outputWeights=v.tolist(), outputBias=float(bias),
        acceptanceScore=.9, trainingSHA256=data_hash, qualification="developmentOnly")


def evaluate(model, rows):
    result = {}
    for split in sorted({r["split"] for r in rows}):
        group = [r for r in rows if r["split"] == split]
        p, valid = scores(model, [r["features"] for r in group])
        matrix = {label: {c: 0 for c in LABELS+["unknown"]} for label in LABELS}
        for r, score, available in zip(group, p, valid):
            predicted = "unknown" if not available or max(score, 1-score) < model["acceptanceScore"] else LABELS[int(score >= .5)]
            matrix[r["label"]][predicted] += 1
        result[split] = dict(samples=len(group), recordings=len({r["recordingId"] for r in group}),
            subjects=len({r["subjectId"] for r in group if r.get("subjectId")}),
            unknownSubjectRecordings=len({r["recordingId"] for r in group if not r.get("subjectId")}), confusion=matrix,
            scope="training resubstitution; not independent accuracy" if split == "train" else "source/subject-disjoint development evaluation; not physical device qualification")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("data", type=Path); parser.add_argument("output", type=Path)
    parser.add_argument("--development-only", action="store_true")
    args = parser.parse_args()
    contents = args.data.read_bytes(); data_hash = hashlib.sha256(contents).hexdigest()
    rows = [json.loads(line) for line in contents.splitlines() if line.strip()]
    validate(rows, args.development_only)
    model = train(rows, data_hash)
    report = dict(trainingSHA256=data_hash, featureVersion=1, architecture="19 inputs / 12 ReLU / 1 sigmoid",
        learnedParameters=253, normalizationValues=38, rawFloat32Bytes=(253+38)*4,
        decisionThreshold=.9, thresholdCalibrated=False, qualification="developmentOnly", shippingEnabled=False,
        metrics=evaluate(model, rows), note="Two output classes do not prove rejection of neutral/mixed/occluded hands. Independent unsupported-condition data and calibration remain necessary.")
    args.output.mkdir(parents=True, exist_ok=False)
    (args.output/"model.json").write_text(json.dumps(model, indent=2, allow_nan=False)+"\n")
    model_bytes = (args.output/"model.json").read_bytes()
    report.update(modelSHA256=hashlib.sha256(model_bytes).hexdigest(), modelJSONBytes=len(model_bytes))
    (args.output/"report.json").write_text(json.dumps(report, indent=2)+"\n")
    print(json.dumps(report))


if __name__ == "__main__":
    main()
