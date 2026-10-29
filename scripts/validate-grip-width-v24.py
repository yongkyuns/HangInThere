#!/usr/bin/env python3
"""Width-only fresh-source validation; no learned hand inference or fitting."""
import argparse
from collections import Counter
from fractions import Fraction
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-width-v24/fresh'
def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,obj):
    with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
def module(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def verify():
    for filename in [OUT/'reference-freeze.json',OUT.parent/'candidate-freeze.json']:
        for path,digest in read(filename)['sha256'].items():assert sha(path)==digest,path
    refs=read(OUT/'source-references.json')
    for c in refs['cases']+refs['excludedCases']:assert sha(c['path'])==c['sha256']
    for name in ['build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl','build/grip-threeway/fitting.jsonl']:
        fit=(ROOT/name).read_text()
        assert all(c['id'] not in fit and c['sha256'] not in fit for c in refs['cases']+refs['excludedCases'])
    return refs
def prepare():
    refs=verify();assert len(refs['cases'])==1
    tracker_module=module('v24fresh_track','grip-temporal-v5.py');c=refs['cases'][0]
    poses=lines(OUT/'native-body/poses.jsonl');receipt=read(OUT/'native-body/export.json')
    assert receipt['observationsSHA256']==sha(OUT/'native-body/poses.jsonl')
    assert receipt['videoSHA256']==c['sha256'];tracker=tracker_module.SubjectTracker();prior=None
    rows=[]
    for pose in poses:
        t=Fraction(pose['timestamp']['value'],pose['timestamp']['timescale'])
        shot=next((s for s in c['shots'] if s['window'][0]<=float(t)<s['window'][1]),None)
        key=shot['id'] if shot else 'unsupported'
        if prior!=key:tracker=tracker_module.SubjectTracker();prior=key
        tracked=tracker.update(pose['people'],float(t));idx=tracked['personIndex'] if tracked['status']=='tracked' else None
        rows.append(dict(recordingId=c['sha256'],caseId=c['id'],shotId=key,
            imageSHA256=f"noRGB:{c['sha256']}:{t}",trackId=tracked['trackId'] if idx is not None else None,
            pose=dict(pose,people=[pose['people'][idx]] if idx is not None else []),widthViewSupported=shot is not None))
    with (OUT/'native-input.jsonl').open('x') as f:f.write(''.join(json.dumps(r)+'\n' for r in rows))
    paths=[OUT/'native-input.jsonl',OUT/'native-body/poses.jsonl',OUT/'source-references.json',Path(__file__)]
    write(OUT/'input-freeze.json',dict(nativeFrames=len(rows),sha256={str(p):sha(p) for p in paths}))
    print('Prepared',len(rows),'native frames')
def score():
    refs=verify()
    for path,h in read(OUT/'input-freeze.json')['sha256'].items():assert sha(path)==h,path
    inputs=lines(OUT/'native-input.jsonl');frames=lines(OUT/'native-output.jsonl')
    assert len(inputs)==len(frames)==read(OUT/'input-freeze.json')['nativeFrames']
    reference=module('v24fresh_ref','grip-width-reference.py');reports=[]
    for c in refs['cases']:
        shot=c['shots'][0];anchor=shot['reference']
        interval=reference.ratio_interval(anchor['regions'],anchor['support'],anchor['imageSize'])
        assert list(interval)==anchor['ratioInterval'] and reference.band(interval)==shot['referenceWidth']
        eligible=[r for r in frames if shot['window'][0]<=r['seconds']<shot['window'][1]]
        known=[r for r in eligible if r['width']!='unknown']
        for i,o in zip(inputs,frames):
            assert i['caseId']==o['caseId']==c['id'] and i['imageSHA256']==o['imageSHA256']
            assert o['seconds']==i['pose']['timestamp']['value']/i['pose']['timestamp']['timescale']
            if not i['widthViewSupported']:assert o['width']=='unknown'
        reports.append(dict(id=c['id'],referenceWidth=shot['referenceWidth'],referenceInterval=interval,
            shotFrames=len(eligible),knownWidthFrames=len(known),coverage=len(known)/len(eligible),
            bands=dict(Counter(r['width'] for r in eligible)),
            wrongKnownFrames=sum(r['width']!=shot['referenceWidth'] for r in known),
            unknownReasons=dict(Counter(r['widthReason'] for r in eligible if r['width']=='unknown')),
            counterPhases=dict(Counter(r['phase'] for r in eligible)),
            firstWidthSeconds=known[0]['seconds'] if known else None))
    result=dict(schemaVersion=24,scope=refs['scope'],newSources=2,processedSources=1,excludedBeforeInference=1,
        originalNativeFrames=len(frames),cases=reports,modelsFit=False,learnedHandInference=False,
        independentHumanGold=False,completeRepCycleAvailable=False,phoneAcquisitionValidated=False,
        sha256={str(p):sha(p) for p in [OUT/'reference-freeze.json',OUT/'input-freeze.json',
            OUT/'native-output.jsonl',OUT/'rep-records.json',Path(__file__)]})
    write(OUT/'report.json',result);print(json.dumps(result,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','score'])
    globals()[p.parse_args().action]()
