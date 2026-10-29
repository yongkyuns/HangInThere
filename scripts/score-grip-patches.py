#!/usr/bin/env python3
"""Score an already-trained pixel model on the same frozen patch population.

No fitting or model selection. Counts missing/visually excluded hand patches as
unknown, and records raw argmax separately from fixed-threshold acceptance.
"""
import argparse
from collections import Counter,defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import torch
from torchvision import transforms
from PIL import Image

ROOT=Path(__file__).absolute().parents[1]
spec=importlib.util.spec_from_file_location("patch_training",ROOT/"scripts/train-grip-patches.py")
training=importlib.util.module_from_spec(spec);spec.loader.exec_module(training)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model",type=Path);parser.add_argument("patches",type=Path);parser.add_argument("output",type=Path)
    parser.add_argument("--patch-review",type=Path);args=parser.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    rows=training.load_rows(args.patches)
    reviews=json.loads(args.patch_review.read_text())["exclusions"] if args.patch_review else []
    training.apply_reviews(rows,reviews)
    model=torch.jit.load(str(args.model),map_location="cpu").eval();tensor=transforms.ToTensor()
    valid=[(r,p) for r in rows if training.in_window(r) for p in r["patches"] if p["status"]=="patchReady"]
    scores={}
    with torch.inference_mode():
        for i in range(0,len(valid),32):
            batch=valid[i:i+32];rgb=torch.stack([tensor(Image.open(p["patch"]).convert("RGB")) for _,p in batch])
            for (_,p),score in zip(batch,model(rgb).tolist()):scores[p["patch"]]=score
    summary=defaultdict(Counter);cases=defaultdict(Counter);predictions=[]
    for row in rows:
        if not training.in_window(row):continue
        a=row["annotation"];split=a["split"];expected=a["label"]
        for patch in row["patches"]:
            record=dict(caseId=row["caseId"],recordingId=row["recordingId"],timestamp=row["pose"]["timestamp"],
                split=split,expected=expected,side=patch["side"],patchStatus=patch["status"])
            metrics=[summary[split],summary[split+":"+expected],cases[row["caseId"]]]
            for m in metrics:m["expectedHands"]+=1
            if patch["status"]!="patchReady":
                record.update(predicted="unknown",reason=patch["status"])
                for m in metrics:m["unknown"]+=1;m["missingPatch"]+=1
            else:
                score=scores[patch["patch"]];best=int(score[1]>score[0]);predicted=training.LABELS[best];accepted=max(score)>=.8
                record.update(probabilities=score,argmax=predicted,patch=patch["patch"],patchSHA256=patch["patchSHA256"],
                    predicted=predicted if accepted else "unknown",reason="accepted" if accepted else "lowScore")
                for m in metrics:
                    m["availablePatches"]+=1;m["argmaxCorrect" if predicted==expected else "argmaxWrong"]+=1
                    m[("correct" if predicted==expected else "wrong") if accepted else "unknown"]+=1
                    if not accepted:m["lowScore"]+=1
            predictions.append(record)
    (args.output/"predictions.jsonl").write_text("".join(json.dumps(p)+"\n" for p in predictions))
    receipt=dict(modelSHA256=hashlib.sha256(args.model.read_bytes()).hexdigest(),inputSHA256=hashlib.sha256(args.patches.read_bytes()).hexdigest(),
        reviewSHA256=hashlib.sha256(args.patch_review.read_bytes()).hexdigest() if args.patch_review else None,
        threshold=.8,splits=summary,cases=cases)
    (args.output/"report.json").write_text(json.dumps(receipt,indent=2)+"\n");print(json.dumps(dict(splits=summary),indent=2))


if __name__=="__main__":main()
