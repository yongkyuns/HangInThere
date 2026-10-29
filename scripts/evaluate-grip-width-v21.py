#!/usr/bin/env python3
"""Separate-video width validation. Source references never enter model evidence."""
import argparse
from collections import Counter
from fractions import Fraction
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-width-v21'
def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def clock(p):return Fraction(p['timestamp']['value'],p['timestamp']['timescale'])
def write(p,obj):
    with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def verify():
    for filename in ['protocol.json','reference-freeze.json']:
        for p,h in read(OUT/filename)['sha256'].items():
            if Path(p)==Path(__file__) and sha(p)!=h:
                repair=read(OUT/'accounting-repair.json')
                assert h==repair['originalSHA256']==sha(OUT/'original-code/evaluate-grip-width-v21.py')
                assert sha(p)==repair['correctedSHA256']
            else:assert sha(p)==h,p
def window(case,t):
    return next((w for w in case['shots'] if w['window'][0]<=t<w['window'][1]),None)

def prepare():
    from PIL import Image
    verify();tracker_module=module('v21track','grip-temporal-v5.py');crop=module('v21crop','prepare-threeway-crops.py')
    native=[];rows=[];receipts=[]
    for case in read(OUT/'source-references.json')['cases']:
        directory=Path(case['framesDirectory']);sampled=lines(directory/'frames.jsonl');by_clock={clock(r['pose']):r for r in sampled}
        body=Path(case['bodyDirectory']);poses=lines(body/'poses.jsonl');receipt=read(body/'export.json')
        assert sha(body/'poses.jsonl')==receipt['observationsSHA256'] and receipt['videoSHA256']==case['sha256']
        tracker=tracker_module.SubjectTracker();last_shot=None;matched=0
        for pose in poses:
            t=clock(pose);w=window(case,float(t));shot=w['id'] if w else 'unsupported'
            # Source-only camera cuts/view boundaries reset temporal evidence.
            if shot!=last_shot:tracker=tracker_module.SubjectTracker();last_shot=shot
            track=tracker.update(pose['people'],float(t));index=track['personIndex'] if track['status']=='tracked' else None
            source=by_clock.get(t);selected=dict(pose,people=[pose['people'][index]] if index is not None else [])
            key=source['imageSHA256'] if source else f"noRGB:{case['sha256']}:{t}"
            native.append(dict(recordingId=case['sha256'],caseId=case['id'],shotId=shot,imageSHA256=key,
                trackId=track['trackId'] if index is not None else None,pose=selected,widthViewSupported=w is not None))
            if source is None:continue
            assert source['pose']==pose;matched+=1
            row=dict(source,recordingId=case['sha256'],caseId=case['id'],shotId=shot,framesDirectory=str(directory),patches=[])
            with Image.open(directory/source['image']) as im:
                im=im.convert('RGB');assert sha(directory/source['image'])==source['imageSHA256']
                for side in ['left','right']:
                    geometry=crop.geometry(pose['people'][index],side,im.size) if w and w.get('gripInference',False) and index is not None else None
                    patch=dict(side=side,status='outsideCaptureScope' if not w else 'noTrackedWrist')
                    if geometry:
                        box,wrist=geometry;path=OUT/'test/patches'/f"{case['sha256'][:10]}-{source['imageSHA256'][:12]}-{side}.png";path.parent.mkdir(exist_ok=True)
                        im.crop(box).resize((128,128),Image.Resampling.BILINEAR).save(path)
                        patch.update(status='patchReady',box=box,wrist=wrist,patch=str(path),patchSHA256=sha(path))
                    row['patches'].append(patch)
            rows.append(row)
        assert matched==len(sampled),case['id'];receipts.append(dict(id=case['id'],originalFrames=len(poses),scheduledFrames=matched))
    for name,objects in [('native-input.jsonl',native),('rows.jsonl',rows)]:
        with (OUT/'test'/name).open('x') as f:f.write(''.join(json.dumps(o)+'\n' for o in objects))
    write(OUT/'test/prepare-receipt.json',dict(cases=receipts,nativeFrames=len(native),scheduledImages=len(rows),automaticCrops=sum(p['status']=='patchReady' for r in rows for p in r['patches']),
        sha256={str(OUT/'test'/p):sha(OUT/'test'/p) for p in ['native-input.jsonl','rows.jsonl']}))
    print(json.dumps(receipts))

def infer():
    verify();frozen=read(OUT/'pre-inference-freeze.json');assert all(sha(p)==h for p,h in frozen['sha256'].items())
    original=module('v21infer','evaluate-grip-controlled-v17.py');original.OUT=OUT
    original.verify=lambda:dict(models=dict(quality='build/grip-anatomy-v11/frozenQuality/GripSurfaceClassifier.pt',neutral='build/grip-threeway/staged-models/threeway/GripHandClassifier.pt'))
    original.infer('test')

def score():
    verify();policy=module('v21policy','grip-controlled-v17.py')
    frames=lines(OUT/'test/native-output.jsonl');rows=lines(OUT/'test/rows.jsonl');rgb={r['imageSHA256']:r for r in lines(OUT/'test/observations.jsonl')}
    metrics=[];states=[]
    for case in read(OUT/'source-references.json')['cases']:
        native=[f for f in frames if f['caseId']==case['id']];sampled=[r for r in rows if r['caseId']==case['id']]
        for shot in case['shots']:
            fs=[f for f in native if f['shotId']==shot['id']];result=policy.decide(fs,rgb,shot['view'],False)
            ref=shot['referenceWidth'];known=[f for f in fs if f['width']!='unknown'];ratios=sorted(f['projectedSpanRatio'] for f in known)
            rs=[s for s in result['states'] if s['label']!='unknown']
            metrics.append(dict(caseId=case['id'],shotId=shot['id'],sourceGroup=case['sourceGroup'],fullCaptureSupported=shot['fullCaptureSupported'],
                referenceWidth=ref,referenceGrip=shot['referenceGrip'],nativeFrames=len(fs),knownWidthFrames=len(known),widthCoverage=len(known)/len(fs) if fs else None,
                predictedBands=dict(Counter(f['width'] for f in fs)),wrongWidthFrames=sum(f['width']!=ref for f in known) if ref!='unknown' else None,
                medianRatio=ratios[len(ratios)//2] if ratios else None,gripLabeledFrames=len(rs),
                wrongGripFrames=sum(s['label']!=shot['referenceGrip'] for s in rs) if shot['referenceGrip']!='unknown' else None,
                widthReasons=dict(Counter(f['widthReason'] for f in fs if f['width']=='unknown'))))
            states+= [dict(s,caseId=case['id'],shotId=shot['id']) for s in result['states']]
        # Identical title-card pixels may recur at different source times or
        # recordings. Coverage counts scheduled rows, not unique image hashes.
        assert len(sampled)==sum(r['caseId']==case['id'] for r in lines(OUT/'test/observations.jsonl'))
    record_sets=read(OUT/'test/rep-records.json');reps=[rep for group in record_sets for rep in group['reps']]
    report=dict(schemaVersion=21,qualification='Agent-reviewed source references; exact shared width/counter, source-confirmed view and wrist-seeded support. Offline, not phone acquisition or human gold.',
        sources=len(read(OUT/'source-references.json')['cases']),nativeFrames=len(frames),scheduledImages=len(rows),shots=metrics,
        counterAttempts=len(reps),completedCounterReps=sum(r.get('returnedAt') is not None and r.get('acceptedAt') is not None and r['closeReason']=='returned' for r in reps),
        widthRepMetadata=dict(Counter(r['grip']['width'] for r in reps)),trained=False,thresholdsChanged=False,phoneInstalled=False)
    write(OUT/'test/report.json',report);write(OUT/'test/grip-states.json',states)
    print(json.dumps(report,indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','infer','score']);a=p.parse_args();globals()[a.action]()
