#!/usr/bin/env python3
"""Join frozen visual labels to real patches; manual localization is train-only.

Retain missing patches in labelled-window denominators. Existing public/private
training sources stay training; no old validation/test source is repurposed.
"""
import argparse
import json
from pathlib import Path

ROOT=Path(__file__).absolute().parents[1]
FIELDS=["recordingId","label","split","start","end","subjectId","sourceGroup","evidence","viewpoint"]


def assemble(original,new,annotations,manual=()):
    labels={a["id"]:a for a in annotations["cases"]};rows=[]
    for row in original:
        if row["annotation"]["split"]!="train":continue
        row["annotation"]["cohort"]="original-training";rows.append(row)
    for row in new:
        a=labels[row["caseId"]]
        if not a["label"]:continue
        assert row["recordingId"]==a["recordingId"]==a["originalVideoSHA256"]
        row["annotation"]={k:a.get(k) for k in FIELDS};row["annotation"]["cohort"]="kinetics-expansion";rows.append(row)
    supplements={(r["recordingId"],r["imageSHA256"]):r for r in manual}
    assert len(supplements)==len(manual),"Duplicate manual annotation"
    for row in rows:
        candidate=supplements.get((row["recordingId"],row["imageSHA256"]))
        if not candidate:continue
        assert row["annotation"]["split"]=="train","Manual patches cannot alter held-out pixels"
        assert candidate["pose"]==row["pose"] and candidate["patchPlan"]==row["patchPlan"],"Foreign annotation context"
        for index,patch in enumerate(row["patches"]):
            replacement=candidate["patches"][index]
            assert replacement["side"]==patch["side"]
            if patch["status"]!="patchReady" and replacement["status"]=="patchReady":
                assert replacement["origin"]=="manualTrainingRegion"
                row["patches"][index]=replacement;row["manualPlan"]=candidate["manualPlan"]
    return rows


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("new_patches",type=Path);parser.add_argument("output",type=Path)
    parser.add_argument("--annotations",type=Path,default=ROOT/"Evaluation/fixtures/public-grip-expansion-v1.json")
    parser.add_argument("--original-patches",type=Path,default=ROOT/"build/grip-patches/images/patches.jsonl")
    parser.add_argument("--manual-patches",type=Path)
    parser.add_argument("--manual-input-regions",type=Path,help="Emit only train frames inside these reviewed search-region windows")
    args=parser.parse_args()
    def read(path):return [json.loads(l) for l in path.read_text().splitlines()]
    rows=assemble(read(args.original_patches),read(args.new_patches),json.loads(args.annotations.read_text()),
        read(args.manual_patches) if args.manual_patches else [])
    if args.manual_input_regions:
        entries={r["recordingId"]:r for r in json.loads(args.manual_input_regions.read_text())["sources"]}
        def selected(row):
            entry=entries.get(row["recordingId"])
            if entry is None:return False
            assert row["annotation"]["split"]=="train" and entry["trainingOnly"] is True
            t=row["pose"]["timestamp"];seconds=t["value"]/t["timescale"]
            return entry["start"]<=seconds<=entry["end"]
        rows=[r for r in rows if selected(r)]
    with args.output.open("x") as output:
        for row in rows:output.write(json.dumps(row,allow_nan=False)+"\n")
    print(json.dumps(dict(frames=len(rows),output=str(args.output))))


if __name__=="__main__":main()
