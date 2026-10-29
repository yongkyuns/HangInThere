#!/usr/bin/env python3
"""Compare binary grip methods on every hand in frozen labelled source windows.

Missing hand features count as unknown, never disappear from the denominator.
Public subjects are unknown; this reports source-recording separation only.
No labels, model weights, thresholds or split assignments are changed here.
"""
import argparse
import hashlib
import json
from pathlib import Path

LABELS = ["overhand", "underhand"]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read_rows(path):
    return [json.loads(s) for s in Path(path).read_text().splitlines() if s.strip()]


def indexed(rows):
    result = {}
    for row in rows:
        key = row["recordingId"], row["observation"]["seconds"]
        if key in result:
            raise ValueError("Duplicate source-frame decision")
        result[key] = row
    return result


def metrics(items):
    matrix = {label: {prediction: 0 for prediction in LABELS + ["neutral", "unknown"]} for label in LABELS}
    complete = 0
    for label, predictions in items:
        complete += predictions == [label, label]
        for prediction in predictions:
            if prediction not in matrix[label]:
                raise ValueError("Unexpected per-hand class")
            matrix[label][prediction] += 1
    expected = 2 * len(items)
    correct = sum(matrix[label][label] for label in LABELS)
    unknown = sum(matrix[label]["unknown"] for label in LABELS)
    wrong = expected - correct - unknown
    return dict(frames=len(items), expectedHands=expected, correct=correct, wrong=wrong, unknown=unknown,
        coverage=(correct+wrong)/expected if expected else None,
        accuracyAmongPredictions=correct/(correct+wrong) if correct+wrong else None,
        correctAmongAllHands=correct/expected if expected else None,
        fullyCorrectFrames=complete, confusion=matrix)


def check_separation(cases, train_rows):
    by_source, by_recording = {}, {}
    for case in cases:
        if case["status"] != "binaryLabel":
            continue
        for groups, key in [(by_source, case["sourceGroup"]), (by_recording, case["recordingId"])]:
            if key in groups and groups[key] != case["split"]:
                raise ValueError("Public source leaks across grip splits")
            groups[key] = case["split"]
    training = {r["recordingId"] for r in train_rows}
    if any(c["recordingId"] in training for c in cases if c["split"] in ["validation", "test"]):
        raise ValueError("Held-out recording was used for classifier training")


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ["annotations", "features_root", "training_data", "candidate_model", "old_scores", "new_scores", "output"]:
        p.add_argument(name, type=Path)
    args = p.parse_args()
    review = json.loads(args.annotations.read_text())
    training = read_rows(args.training_data)
    check_separation(review["cases"], training)
    candidate = json.loads(args.candidate_model.read_text())
    assert candidate["trainingSHA256"] == digest(args.training_data), "Different trained data"
    old, new = indexed(read_rows(args.old_scores)), indexed(read_rows(args.new_scores))
    assert old.keys() == new.keys(), "Methods did not process the same source observations"
    groups = {split: {method: [] for method in ["geometry", "privatePrototype", "publicExpanded"]}
              for split in ["train", "validation", "test"]}
    sources = []
    for c in review["cases"]:
        if c["status"] != "binaryLabel":
            continue
        rows = read_rows(args.features_root / c["id"] / "features.jsonl")
        assert rows and all(r["recordingId"] == c["recordingId"] for r in rows)
        eligible = [r for r in rows if c["start"] <= r["observation"]["seconds"] <= c["end"]]
        assert eligible, "Labelled source window contains no observations"
        local = {method: [] for method in groups[c["split"]]}
        for row in eligible:
            key = row["recordingId"], row["observation"]["seconds"]
            assert key in old and key in new, "Missing source-frame decisions"
            predictions = dict(geometry=[row["legacy"][side] for side in ["left", "right"]],
                privatePrototype=[old[key]["decisions"][side]["grip"] for side in ["left", "right"]],
                publicExpanded=[new[key]["decisions"][side]["grip"] for side in ["left", "right"]])
            for method, values in predictions.items():
                item = c["label"], values
                local[method].append(item); groups[c["split"]][method].append(item)
        sources.append(dict(id=c["id"], label=c["label"], split=c["split"], subjectId=c["subjectId"],
            labelledFrames=len(eligible), handsWithFeatures=sum(len(r["features"]) for r in eligible),
            methods={method: metrics(items) for method, items in local.items()}))
    report = dict(scope="Provisional visual development labels; source-recording-disjoint raw hand orientation, not athlete-independent, rep-summary or phone-performance qualification",
        reviewedClips=len(review["cases"]), binaryLabelClips=len(sources),
        unresolvedOrUnsupportedClips=sum(c["status"] != "binaryLabel" for c in review["cases"]),
        unknownSubjectIdentity=True, recordingSeparationVerified=True, shippingEnabled=False, accuracyQualified=False,
        annotationsSHA256=digest(args.annotations), trainingSHA256=digest(args.training_data),
        candidateModelSHA256=digest(args.candidate_model),
        splits={split: {method: metrics(items) for method, items in methods.items()} for split, methods in groups.items()},
        sources=sources)
    with args.output.open("x") as stream:
        json.dump(report, stream, indent=2); stream.write("\n")
    print(json.dumps(report["splits"]["test"], indent=2))


if __name__ == "__main__":
    main()
