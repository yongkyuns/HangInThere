#!/usr/bin/env python3
"""Join frozen visual grip labels to existing native frame exports, by source SHA.

No media download, label inference or split reassignment. Private owner sources
are included only when --private-annotations is explicitly supplied.
"""
import argparse
import json
from pathlib import Path

ROOT=Path(__file__).absolute().parents[1]
FIELDS=["recordingId","label","split","start","end","subjectId","sourceGroup","evidence"]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output",type=Path)
    parser.add_argument("--annotations",type=Path,default=ROOT/"Evaluation/fixtures/public-grip-review-v1.json")
    parser.add_argument("--public-frames",type=Path,default=ROOT/"build/public-grip-training")
    parser.add_argument("--private-annotations",type=Path)
    parser.add_argument("--private-frames",type=Path,default=ROOT/"build/grip-training")
    args=parser.parse_args()
    inputs=[]
    for annotation in json.loads(args.annotations.read_text())["cases"]:
        if annotation.get("label"):
            inputs.append((annotation,args.public_frames/annotation["id"]/"frames",annotation["id"]))
    if args.private_annotations:
        for annotation in json.loads(args.private_annotations.read_text()):
            # These development exports have fixed source names; reject an
            # unexpected schema instead of silently assigning another source.
            name={"pullup-features.jsonl":"pullup", "chinup-features.jsonl":"chinup"}[annotation["featuresFile"]]
            inputs.append((annotation,args.private_frames/(name+"-frames"),"owner-"+name))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    count=0
    with args.output.open("x") as output:
        for annotation,directory,case in inputs:
            for line in (directory/"frames.jsonl").read_text().splitlines():
                row=json.loads(line)
                assert row["recordingId"]==annotation["recordingId"],"Foreign frame export"
                assert annotation["split"] in ["train","validation","test"]
                row.update(caseId=case,framesDirectory=str(directory.absolute()),annotation={k:annotation.get(k) for k in FIELDS})
                output.write(json.dumps(row,allow_nan=False)+"\n");count+=1
    print(json.dumps(dict(recordings=len(inputs),frames=count,output=str(args.output))))


if __name__=="__main__":main()
