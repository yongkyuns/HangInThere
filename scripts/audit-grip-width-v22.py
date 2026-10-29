#!/usr/bin/env python3
"""Audit width policy v2 against frozen exposed regressions without relabeling them."""
from collections import Counter
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-width-v22'
OLD=ROOT/'build/grip-width-v21'
def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def main():
    pins=0
    for file in ['baseline-freeze.json','candidate-revision3-freeze.json']:
        for path,digest in read(OUT/file)['sha256'].items():
            if file=='baseline-freeze.json' and sha(path)!=digest:
                backup=OUT/'baseline-code'/Path(path).relative_to(ROOT)
                assert backup.is_file() and sha(backup)==digest,path
            else:assert sha(path)==digest,path
            pins+=1
    old=lines(OLD/'test/native-output.jsonl');new=lines(OUT/'native-output-r3.jsonl')
    assert len(old)==len(new)==7579
    unchanged=['recordingId','imageSHA256','seconds','sourceEpoch','phase','trackingIssue','movements','released','reference','supportBySide','exercise']
    assert all(all(a[k]==b[k] for k in unchanged) for a,b in zip(old,new))
    assert all(b['width']=='unknown' for b in new if not b['widthBodyUsable'])
    sequence=lines(OUT/'alternating-output-r3.jsonl');before=lines(OLD/'test/alternating-output.jsonl')
    assert len(sequence)==len(before)==5112
    assert all(all(a[k]==b[k] for k in unchanged) for a,b in zip(before,sequence))
    plan=read(OLD/'alternating-plan.json')['order'];baselines={(r['caseId'],r['seconds']):r for r in new}
    baselines.update({('owner-wide-control',r['seconds']):r for r in sequence if r['caseId']=='0:owner-wide-control'})
    fields=['width','projectedSpanRatio','widthReason','phase','movements','reference','exercise']
    negatives=Counter();counts={}
    for entry in plan:
        fs=[r for r in sequence if r['caseId']==str(entry['position'])+':'+entry['caseId']]
        assert len(fs)==entry['frames'] and fs[0]['width']=='unknown'
        for r in fs:
            assert all(r[k]==baselines[entry['caseId'],r['seconds']][k] for k in fields)
            if entry['caseId']=='owner-wide-control':
                key=r['caseId'],r['sourceEpoch'];increment=max(0,r['movements']-counts.get(key,0));counts[key]=r['movements']
                phase='setup' if r['seconds']<4.8 else 'release' if r['seconds']>=20.55 else None
                if phase:
                    negatives[phase+'Frames']+=1;negatives['newMovements']+=increment
                    negatives['exerciseAdmissions']+=r['exercise']['eligible'];negatives['knownWidthFrames']+=r['width']!='unknown'
    assert dict(negatives)==dict(setupFrames=288,newMovements=0,exerciseAdmissions=0,knownWidthFrames=0,releaseFrames=148)
    tests=read(OUT/'shared-test-receipt-r3.json');assert tests['tests']==820 and tests['failures']==tests['errors']==0
    report=dict(schemaVersion=22,scope='Exposed development/regression. Historical provisional medium references disputed, not relabeled. No qualified narrow/medium accuracy gain.',
        frozenPinsVerified=pins,nativeCounterContextFramesBitIdentical=7579,assembledCounterContextFramesBitIdentical=5112,
        widthPolicyVersion=2,noWidthStateCarryover=True,negativeControls=dict(negatives),sharedTests=820,
        learnedModelsChanged=False,categoryThresholdsChanged=False,repCounterChanged=False,independentAccuracyQualified=False,
        sha256={str(p):sha(p) for p in [OUT/'comparison-r3.json',OUT/'native-output-r3.jsonl',OUT/'rep-records-r3.json',OUT/'alternating-output-r3.jsonl',OUT/'alternating-rep-records-r3.json',OUT/'auxiliary-body.jsonl',OUT/'candidate-revision3-freeze.json',Path(__file__)]})
    with (OUT/'verification.json').open('x') as f:json.dump(report,f,indent=2);f.write('\n')
    print(json.dumps({k:v for k,v in report.items() if k!='sha256'},indent=2))

if __name__=='__main__':main()
