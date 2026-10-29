#!/usr/bin/env python3
"""Join explicit human/visual source-window labels with shared hand features.

Usage: prepare-binary-grip-training.py ANNOTATIONS_JSON NEW_OUTPUT_JSONL
Each annotation has featuresFile (relative to this JSON), recordingId (SHA256),
label, subjectId, split, start, end and evidence. Never label from predictions.
Missing hands remain in the coverage receipt; they are not training features.
"""
import hashlib
import json
from pathlib import Path
import sys


def main():
    manifest, output = map(Path, sys.argv[1:])
    annotations = json.loads(manifest.read_text())
    dataset, receipt = [], []
    for annotation in annotations:
        assert annotation["label"] in ["overhand", "underhand"] and annotation["evidence"], "Explicit labels/evidence required"
        assert annotation["start"] < annotation["end"], "Invalid labelled source window"
        source = manifest.parent / annotation["featuresFile"]
        rows = [json.loads(s) for s in source.read_text().splitlines() if s.strip()]
        assert rows and all(r["recordingId"] == annotation["recordingId"] for r in rows), "Different recording"
        selected = [r for r in rows if annotation["start"] <= r["observation"]["seconds"] <= annotation["end"]]
        assert selected, "No original source frames in labelled window"
        for row in selected:
            for side, features in row["features"].items():
                dataset.append(dict(featureVersion=1, features=features, label=annotation["label"],
                    recordingId=annotation["recordingId"], subjectId=annotation["subjectId"], split=annotation["split"],
                    seconds=row["observation"]["seconds"], side=side, labelEvidence=annotation["evidence"]))
        receipt.append(dict(recordingId=annotation["recordingId"], label=annotation["label"],
            sourceFeatureSHA256=hashlib.sha256(source.read_bytes()).hexdigest(),
            labelledFrames=len(selected), framesWithNoEligibleHand=sum(not r["features"] for r in selected),
            eligibleHands=sum(len(r["features"]) for r in selected)))
    with output.open("x") as stream:
        for row in dataset:
            stream.write(json.dumps(row, sort_keys=True, allow_nan=False)+"\n")
    Path(str(output)+".receipt.json").write_text(json.dumps(dict(
        annotationsSHA256=hashlib.sha256(manifest.read_bytes()).hexdigest(),
        trainingSHA256=hashlib.sha256(output.read_bytes()).hexdigest(), recordings=receipt), indent=2)+"\n")
    print(f"Prepared {len(dataset)} labelled real-hand samples")


if __name__ == "__main__":
    main()
