#!/usr/bin/env python3
"""Frozen new-video comparison with uncertain joint-center references retained."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-width-v23'
def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,obj):
    with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
def module(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def verify():
    for filename in ['protocol.json','reference-freeze.json','implementation-freeze.json']:
        for path,h in read(OUT/filename)['sha256'].items():assert sha(path)==h,path

def prepare():
    verify();old=module('v23prepare','evaluate-grip-width-v21.py');old.OUT=OUT;old.verify=verify;old.prepare()

def infer():
    verify();freeze=read(OUT/'pre-inference-freeze.json');assert all(sha(p)==h for p,h in freeze['sha256'].items())
    old=module('v23infer','evaluate-grip-controlled-v17.py');old.OUT=OUT
    old.verify=lambda:dict(models=dict(quality='build/grip-anatomy-v11/frozenQuality/GripSurfaceClassifier.pt',neutral='build/grip-threeway/staged-models/threeway/GripHandClassifier.pt'))
    old.infer('test')

def score():
    verify();accounting=module('v23accounting','evaluate-grip-supported-v19.py');policy=module('v23policy','grip-controlled-v17.py')
    rows=lines(OUT/'test/rows.jsonl');frames=lines(OUT/'test/native-output.jsonl');observations=lines(OUT/'test/observations.jsonl')
    assert len(rows)==len(observations)
    refs=read(OUT/'source-references.json');summaries=[];results=[]
    for c in refs['cases']:
        fs=[f for f in frames if f['caseId']==c['id']];rgb={r['imageSHA256']:r for r in observations if r['caseId']==c['id']}
        result=policy.decide(fs,rgb,c['confirmedView'],False)
        scheduled=[accounting.clock(r['pose']) for r in rows if r['caseId']==c['id']]
        summary=accounting.summarize(c,fs,result['states'],[float(t) for t in scheduled])
        shot=c['shots'][0];eligible=[f for f in fs if shot['window'][0]<=f['seconds']<shot['window'][1]]
        exercise=[f for f in eligible if accounting.phase(c,f['seconds'])=='exercise']
        known=[f for f in exercise if f['width']!='unknown'];ratios=sorted(f['projectedSpanRatio'] for f in known)
        reference=shot['referenceWidth'];expectedKnown=reference!='uncertain'
        summary.update(referenceWidth=reference,referenceInterval=shot['reference']['ratioInterval'],widthReferenceVerifiable=expectedKnown,
            shotFrames=len(eligible),exerciseNativeFrames=len(exercise),exerciseWidthFrames=len(known),widthCoverage=len(known)/len(exercise) if exercise else None,
            predictedWidthBands=dict(Counter(f['width'] for f in exercise)),medianRatio=ratios[len(ratios)//2] if ratios else None,
            correctWidthFrames=sum(f['width']==reference for f in known) if expectedKnown else None,
            wrongWidthFrames=sum(f['width']!=reference for f in known) if expectedKnown else None,
            widthOutcome='unverifiable' if not expectedKnown else 'unknown' if not known else 'correct' if all(f['width']==reference for f in known) else 'wrongOrInconsistent',
            widthUnavailableReasons=dict(Counter(f['widthReason'] for f in exercise if f['width']=='unknown')))
        summaries.append(summary);results.append(dict(caseId=c['id'],**result))
    records=read(OUT/'test/rep-records.json');reps=[r for group in records for r in group['reps']]
    report=dict(schemaVersion=23,scope='New video groups; source-only joint-center region references frozen before inference. Source-confirmed view and wrist-seeded support remain diagnostic, not phone acquisition or human-adjudicated gold.',
        allNewSources=refs['allNewSources'],supportedNewSources=len(refs['cases']),excludedNewSources=len(refs['excludedCases']),
        cases=summaries,nativeFrames=len(frames),scheduledImages=len(rows),actualCounterAttempts=len(reps),
        completedCounterReps=sum(r.get('acceptedAt') is not None and r.get('returnedAt') is not None and r['closeReason']=='returned' for r in reps),
        repWidthBands=dict(Counter(r['grip']['width'] for r in reps)),newModelFit=False,thresholdsChanged=False,phoneInstalled=False)
    write(OUT/'test/report.json',report);write(OUT/'test/grip-results.json',results)
    print(json.dumps(report,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','infer','score']);a=p.parse_args();globals()[a.action]()
