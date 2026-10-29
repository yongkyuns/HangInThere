#!/usr/bin/env python3
"""Train a small RGB grip classifier; fixed recording splits, no pose features.

ImageNet MobileNetV3-small features are frozen. Only a two-output image head is
trained, with class-balanced cross entropy and train-only image augmentation.
Development artifact only: private owner video contributes training pixels.
"""
import os
os.environ.setdefault("OMP_NUM_THREADS", "2")
os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path
import random
import time
import numpy as np
from PIL import Image
import torch
from torch import nn
from torchvision import models, transforms

LABELS = ["overhand", "underhand"]


class PatchClassifier(nn.Module):
    def __init__(self, backbone):
        super().__init__()
        self.features = backbone.features
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.head = nn.Linear(576, 2)
        self.register_buffer("mean", torch.tensor([.485,.456,.406]).reshape(1,3,1,1))
        self.register_buffer("std", torch.tensor([.229,.224,.225]).reshape(1,3,1,1))

    def embed(self, rgb):
        return self.pool(self.features((rgb-self.mean)/self.std)).flatten(1)

    def forward(self, rgb):
        return self.head(self.embed(rgb)).softmax(dim=1)


def load_rows(path):
    rows = [json.loads(line) for line in path.read_text().splitlines()]
    seen = {}; sources = defaultdict(set); people = defaultdict(set); groups = defaultdict(set); annotations = {}
    for row in rows:
        annotation = row["annotation"]
        assert annotation["recordingId"] == row["recordingId"]
        assert row["patchPlan"]["sourceId"] == row["recordingId"]
        assert row["patchPlan"]["timestamp"] == row["pose"]["timestamp"]
        key = (row["recordingId"], row["pose"]["timestamp"]["value"], row["pose"]["timestamp"]["timescale"])
        assert key not in seen, "Duplicate source observation"
        seen[key] = True
        split = annotation["split"]
        assert split in ["train", "validation", "test"] and annotation["label"] in LABELS
        if row["recordingId"] in annotations:
            assert annotations[row["recordingId"]] == annotation,"Inconsistent recording annotation"
        annotations[row["recordingId"]] = annotation
        sources[row["recordingId"]].add(split)
        if annotation.get("subjectId"): people[annotation["subjectId"]].add(split)
        if annotation.get("sourceGroup"): groups[annotation["sourceGroup"]].add(split)
        assert sorted(p["side"] for p in row["patches"]) == ["left", "right"]
        for patch in row["patches"]:
            if patch.get("origin")=="manualTrainingRegion":
                assert split=="train" and row["manualPlan"]["sourceId"]==row["recordingId"],"Manual crop entered holdout"
                assert row["manualPlan"]["timestamp"]==row["pose"]["timestamp"]
                assert patch["manualAnnotationSHA256"]==row["manualPlan"]["annotationSHA256"]
            if patch["status"] == "patchReady":
                assert hashlib.sha256(Path(patch["patch"]).read_bytes()).hexdigest() == patch["patchSHA256"]
    assert all(len(s) == 1 for s in list(sources.values())+list(people.values())+list(groups.values())), "Split leakage"
    return rows


def apply_reviews(rows, reviews):
    """Image-bound visual exclusions; never change a recording's grip or split."""
    pending = {(p["recordingId"],p["imageSHA256"],p["side"]):p for p in reviews}
    assert len(pending) == len(reviews), "Duplicate review"
    for row in rows:
        for patch in row["patches"]:
            key = (row["recordingId"],row["imageSHA256"],patch["side"])
            review = pending.pop(key,None)
            if review is None: continue
            assert patch["status"] == "patchReady" and review["patchSHA256"] == patch["patchSHA256"]
            assert review["reason"] in ["openHand", "occludedGrip", "overlap"]
            patch["status"] = "visualExclusion:"+review["reason"]
    assert not pending, "Review does not match source images"


def in_window(row):
    t = row["pose"]["timestamp"]; t = t["value"]/t["timescale"]
    a = row["annotation"]
    return a["start"] <= t <= a["end"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("patches", type=Path); parser.add_argument("output", type=Path)
    parser.add_argument("--patch-review", type=Path)
    args = parser.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2); torch.set_num_interop_threads(2)
    random.seed(31); np.random.seed(31); torch.manual_seed(31)
    rows = load_rows(args.patches)
    reviews = json.loads(args.patch_review.read_text())["exclusions"] if args.patch_review else []
    apply_reviews(rows,reviews)
    model = PatchClassifier(models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.IMAGENET1K_V1)).eval()
    for parameter in model.features.parameters(): parameter.requires_grad = False
    tensor = transforms.ToTensor()
    augment = transforms.Compose([transforms.RandomAffine(12,translate=(.04,.04),scale=(.92,1.08)),
                                  transforms.ColorJitter(.2,.2,.15,.03),transforms.ToTensor()])
    train_x = []; train_y = []; train_patches = []
    valid = [(r,p) for r in rows if in_window(r) for p in r["patches"] if p["status"] == "patchReady"]
    with torch.inference_mode():
        for row, patch in valid:
            if row["annotation"]["split"] != "train": continue
            image = Image.open(patch["patch"]).convert("RGB")
            images = torch.stack([tensor(image)]+[augment(image) for _ in range(4)])
            train_x.append(model.embed(images)); train_y.extend([LABELS.index(row["annotation"]["label"])]*5)
            train_patches.append(dict(recordingId=row["recordingId"], imageSHA256=row["imageSHA256"],
                                      patchSHA256=patch["patchSHA256"], label=row["annotation"]["label"]))
    # clone outside inference mode so autograd can save these constant features.
    x = torch.cat(train_x).clone(); y = torch.tensor(train_y)
    assert set(y.tolist()) == {0,1}, "Training requires both grip classes"
    frequencies = torch.bincount(y).float()
    weight = len(y)/(2*frequencies)
    optimizer = torch.optim.AdamW(model.head.parameters(), lr=.01, weight_decay=.1)
    loss_fn = nn.CrossEntropyLoss(weight=weight)
    for _ in range(300):
        optimizer.zero_grad(); loss = loss_fn(model.head(x),y); loss.backward(); optimizer.step()
    model.eval()
    threshold = .8  # fixed development abstention threshold, not tuned on held-out predictions
    scores = {}; timings = []
    with torch.inference_mode():
        for offset in range(0,len(valid),32):
            batch = valid[offset:offset+32]
            images = torch.stack([tensor(Image.open(p["patch"]).convert("RGB")) for _,p in batch])
            started = time.perf_counter(); probabilities = model(images).tolist()
            timings.append((time.perf_counter()-started,len(batch)))
            for (_,patch), score in zip(batch,probabilities): scores[patch["patch"]] = score
    summary = defaultdict(Counter); by_case = defaultdict(Counter); predictions = []
    for row in rows:
        if not in_window(row): continue
        annotation = row["annotation"]; split = annotation["split"]; expected = annotation["label"]
        for patch in row["patches"]:
            record = dict(caseId=row["caseId"],recordingId=row["recordingId"],timestamp=row["pose"]["timestamp"],
                          split=split,expected=expected,side=patch["side"],patchStatus=patch["status"],
                          personIndex=row["patchPlan"].get("personIndex"))
            metrics = [summary[split],summary[split+":"+expected],by_case[row["caseId"]]]
            for m in metrics: m["expectedHands"] += 1
            if patch["status"] != "patchReady":
                record.update(predicted="unknown",reason=patch["status"])
                for m in metrics: m["unknown"]+=1; m["missingPatch"]+=1
            else:
                score = scores[patch["patch"]]; best = int(np.argmax(score)); predicted = LABELS[best]
                record.update(probabilities=score,argmax=predicted,patch=patch["patch"],patchSHA256=patch["patchSHA256"])
                accepted = score[best] >= threshold
                record.update(predicted=predicted if accepted else "unknown",reason="accepted" if accepted else "lowScore")
                for m in metrics:
                    m["availablePatches"]+=1
                    m["argmaxCorrect" if predicted == expected else "argmaxWrong"]+=1
                    m[("correct" if predicted == expected else "wrong") if accepted else "unknown"]+=1
                    if not accepted: m["lowScore"]+=1
            predictions.append(record)
    identity = dict(inputSHA256=hashlib.sha256(args.patches.read_bytes()).hexdigest(), visualExclusions=reviews,training=train_patches)
    training_sha = hashlib.sha256(json.dumps(identity,sort_keys=True).encode()).hexdigest()
    torch.save(model.state_dict(),args.output/"weights.pt")
    traced = torch.jit.trace(model,torch.zeros(1,3,128,128))
    traced.save(str(args.output/"GripPatchClassifier.pt"))
    (args.output/"predictions.jsonl").write_text("".join(json.dumps(p)+"\n" for p in predictions))
    contributing_by_label={label:len({p["recordingId"] for p in train_patches if p["label"]==label}) for label in LABELS}
    origins=Counter(p.get("origin","automatic") for r in rows if in_window(r) and r["annotation"]["split"]=="train"
                    for p in r["patches"] if p["status"]=="patchReady")
    report = dict(qualification="development-only",labels=LABELS,input="128x128 RGB hand/short-forearm pixels only",
        architecture="MobileNetV3-small frozen ImageNet1K V1 features + trained 576-to-2 linear head",
        seed=31,epochs=300,augmentationCopies=4,threshold=threshold,
        trainingSHA256=training_sha,trainingPatches=len(train_patches),classCounts=dict(Counter(p["label"] for p in train_patches)),
        contributingTrainingSourcesByLabel=contributing_by_label,trainingPatchOrigins=dict(origins),
        parameters=sum(p.numel() for p in model.parameters()),trainableParameters=sum(p.numel() for p in model.parameters() if p.requires_grad),
        splits=summary,cases=by_case,hostBatchMeanMsPerPatch=1000*sum(t for t,_ in timings)/sum(n for _,n in timings),
        limitations=["Provisional visual labels, not independent human-reviewed patch labels",
            "Only source-recording separation; public athlete identities unknown",
            "Underhand training comes from %d recordings; frame counts are correlated samples" % contributing_by_label["underhand"],
            "No iPhone latency or release qualification; output scores are not calibrated probabilities"],
        sourceCounts={s:len({r["recordingId"] for r in rows if r["annotation"]["split"]==s}) for s in ["train","validation","test"]},
        contributingSourceCounts={s:len({r["recordingId"] for r,p in valid if r["annotation"]["split"]==s}) for s in ["train","validation","test"]})
    (args.output/"report.json").write_text(json.dumps(report,indent=2)+"\n")
    (args.output/"training-receipt.json").write_text(json.dumps(identity,indent=2)+"\n")
    print(json.dumps(report,indent=2))


if __name__ == "__main__":
    main()
