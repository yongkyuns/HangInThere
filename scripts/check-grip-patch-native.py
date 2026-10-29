#!/usr/bin/env python3
"""Verify native pixel inference parity, retaining failures in an audit receipt."""
import argparse
import hashlib
import json
from pathlib import Path
import statistics


def compare(expected, actual, threshold=.8):
    assert len(actual)==len(expected)==len({r["patch"] for r in actual}),"Changed patch population"
    assert set(expected)=={r["patch"] for r in actual},"Changed patches"
    maximum=0; argmax=0; acceptance=0
    for row in actual:
        reference=expected[row["patch"]]; scores=row["scores"]
        assert len(scores)==2 and all(0<=s<=1 for s in scores)
        maximum=max(maximum,max(abs(a-b) for a,b in zip(scores,reference["probabilities"])))
        argmax+=(scores[1]>scores[0])!=(reference["argmax"]=="underhand")
        acceptance+=(max(scores)>=threshold)!=(reference["predicted"]!="unknown")
    return dict(patches=len(actual),maxAbsoluteScoreDifference=maximum,argmaxMismatches=argmax,
        thresholdMismatches=acceptance,passParity=maximum<.01 and argmax==0 and acceptance==0,
        hostCPUWarmMeanMs=1000*statistics.mean(r["hostCpuSeconds"] for r in actual[1:]),
        hostCPUWarmMedianMs=1000*statistics.median(r["hostCpuSeconds"] for r in actual[1:]))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("reference",type=Path);parser.add_argument("native",type=Path);parser.add_argument("output",type=Path)
    args=parser.parse_args()
    expected={r["patch"]:r for l in args.reference.read_text().splitlines() if (r:=json.loads(l)).get("patch")}
    report=compare(expected,[json.loads(l) for l in args.native.read_text().splitlines()])
    report.update(referenceSHA256=hashlib.sha256(args.reference.read_bytes()).hexdigest(),nativeSHA256=hashlib.sha256(args.native.read_bytes()).hexdigest())
    args.output.write_text(json.dumps(report,indent=2)+"\n");print(json.dumps(report,indent=2))
    if not report["passParity"]:raise SystemExit("Native predictions differ; artifact is not qualified")


if __name__=="__main__":main()
