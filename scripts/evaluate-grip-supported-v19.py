#!/usr/bin/env python3
"""Prescreen capture scope, then score frozen readable-hand inference at native body cadence."""
import argparse
from collections import Counter
from fractions import Fraction
import hashlib
import importlib.util
import json
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-supported-v19'


def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def clock(p):return Fraction(p['timestamp']['value'],p['timestamp']['timescale'])
def write(p,obj):
    with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    obj=importlib.util.module_from_spec(spec);spec.loader.exec_module(obj);return obj


def verify():
    p=read(OUT/'protocol.json')
    assert all(sha(f)==h for f,h in p['sha256'].items())
    return p


def phase(case,t):
    if case['activeWindow'][0]<=t<=case['activeWindow'][1]:return 'exercise'
    if t<case['activeWindow'][0]:return 'setup'
    if t>=case['releaseStart']:return 'release'
    return 'uncertain'


def summarize(case,frames,states,sampled_times):
    """Keep all scheduled images in coverage, including absent crops/tracks."""
    assert len(frames)==len(states)
    by_time={s['seconds']:s for s in states}
    scheduled=[by_time[t] for t in sampled_times]
    exercise=[s for s in scheduled if phase(case,s['seconds'])=='exercise']
    decisions=[s for s in states if s['observed'] and phase(case,s['seconds'])=='exercise']
    labels={s['label'] for s in decisions}
    if not case['supported']:outcome='excluded'
    elif case['grip']=='unknown':outcome='unverifiable'
    elif not labels:outcome='unknown'
    elif labels=={case['grip']}:outcome='correct'
    else:outcome='wrongOrInconsistent'
    known_reference=case['grip']!='unknown'
    correct=[s for s in states if known_reference and s['label']==case['grip'] and phase(case,s['seconds'])=='exercise']
    negatives=Counter();last={}
    for frame,state in zip(frames,states):
        epoch=frame['sourceEpoch'];increment=max(0,frame['movements']-last.get(epoch,0));last[epoch]=frame['movements']
        ph=phase(case,state['seconds'])
        if ph in ['setup','release']:
            negatives[ph+'Frames']+=1;negatives['newMovements']+=increment
            negatives['exerciseAdmissions']+=frame['exercise']['eligible'];negatives['visibleLabels']+=state['label']!='unknown'
    cycles=[dict(window=w,correctVisible=known_reference and any(w[0]<=s['seconds']<=w[1] and s['label']==case['grip'] for s in states)) for w in case['fullCycleWindows']]
    return dict(id=case['id'],role=case['role'],supported=case['supported'],exclusion=case.get('exclusion'),referenceGrip=case['grip'],
                outcome=outcome,nativeFrames=len(frames),counterEligibleFrames=sum(f['exercise']['eligible'] for f in frames),
                originalScheduledFrames=len(scheduled),originalExerciseScheduledFrames=len(exercise),
                exerciseLabelFrames=sum(s['label']!='unknown' for s in exercise),
                exerciseLabelCoverage=sum(s['label']!='unknown' for s in exercise)/len(exercise) if exercise else None,
                firstCorrectSeconds=correct[0]['seconds'] if correct else None,
                wrongNewDecisions=sum(known_reference and s['label']!=case['grip'] for s in decisions),
                cycles=cycles,negativeControls=dict(negatives))


def prepare():
    from PIL import Image,ImageDraw,ImageFont
    verify();cases=read(OUT/'source-references.json')['cases']
    tracker_module=module('supported_track','grip-temporal-v5.py');crop=module('supported_crop','prepare-threeway-crops.py')
    folder=OUT/'test';folder.mkdir(exist_ok=True);native=[];rows=[];patches=[];receipts=[]
    for case in cases:
        directory=Path(case['framesDirectory']);sampled=lines(directory/'frames.jsonl');by_time={clock(r['pose']):r for r in sampled}
        body_directory=Path(case['bodyDirectory']);poses=lines(body_directory/'poses.jsonl');receipt=read(body_directory/'export.json')
        assert sha(body_directory/'poses.jsonl')==receipt['observationsSHA256'] and receipt['videoSHA256']==case['recordingId']
        tracker=tracker_module.SubjectTracker();matched=0
        for pose in poses:
            t=clock(pose);tracked=tracker.update(pose['people'],float(t));index=tracked['personIndex'] if tracked['status']=='tracked' else None
            source=by_time.get(t);image_key=source['imageSHA256'] if source else f"noRGB:{case['recordingId']}:{t}"
            native.append(dict(recordingId=case['recordingId'],imageSHA256=image_key,trackId=tracked['trackId'] if index is not None else None,
                               pose=dict(pose,people=[pose['people'][index]] if index is not None else [])))
            if source is None:continue
            assert source['pose']==pose;matched+=1;source_path=directory/source['image'];assert sha(source_path)==source['imageSHA256']
            row=dict(source,caseId=case['id'],framesDirectory=str(directory),patches=[])
            with Image.open(source_path) as original:
                original=original.convert('RGB')
                for side in ['left','right']:
                    geometry=crop.geometry(pose['people'][index],side,original.size) if case['supported'] and index is not None else None
                    patch=dict(side=side,status='outsideCaptureScope' if not case['supported'] else 'noTrackedWrist')
                    if geometry:
                        box,wrist=geometry;path=folder/'patches'/f"{case['recordingId'][:10]}-{source['imageSHA256'][:12]}-{side}.png";path.parent.mkdir(exist_ok=True)
                        original.crop(box).resize((128,128),Image.Resampling.BILINEAR).save(path)
                        patch.update(status='patchReady',box=box,wrist=wrist,patch=str(path),patchSHA256=sha(path))
                        patches.append(dict(caseId=case['id'],seconds=float(t),**patch))
                    row['patches'].append(patch)
            rows.append(row)
        assert matched==len(sampled),case['id'];receipts.append(dict(id=case['id'],originalFrames=len(poses),sampledPosesBitIdentical=matched,supported=case['supported']))
    for filename,objects in [('rows.jsonl',rows),('native-input.jsonl',native)]:
        with (folder/filename).open('x') as f:f.write(''.join(json.dumps(r)+'\n' for r in objects))
    font=ImageFont.load_default(size=14)
    for page in range(math.ceil(len(patches)/64)):
        sheet=Image.new('RGB',(1280,1312),'white');d=ImageDraw.Draw(sheet)
        for i,p in enumerate(patches[page*64:(page+1)*64]):
            x=i%8*160;y=i//8*164;d.text((x+3,y+2),f"{p['caseId'][:13]} {p['seconds']:.2f}",fill='black',font=font);d.text((x+3,y+18),p['side'],fill='black',font=font)
            with Image.open(p['patch']) as image:sheet.paste(image,(x+3,y+35))
        sheet.save(folder/f'crop-review-{page+1:02d}.jpg',quality=90)
    write(folder/'prepare-receipt.json',dict(cases=receipts,nativeFrames=len(native),scheduledImages=len(rows),automaticCrops=len(patches),sha256={str((folder/f).resolve()):sha(folder/f) for f in ['rows.jsonl','native-input.jsonl']}))
    print(json.dumps(dict(nativeFrames=len(native),scheduledImages=len(rows),automaticCrops=len(patches))))


def infer():
    verify();freeze=read(OUT/'pre-inference-freeze.json');assert all(sha(p)==h for p,h in freeze['sha256'].items())
    frozen=module('frozen_inference','evaluate-grip-controlled-v17.py');frozen.OUT=OUT;frozen.verify=verify;frozen.infer('test')


def score():
    verify();policy=module('frozen_decision','grip-controlled-v17.py');folder=OUT/'test'
    rgb={r['imageSHA256']:r for r in lines(folder/'observations.jsonl')};native=lines(folder/'native-output.jsonl');cases=read(OUT/'source-references.json')['cases']
    results=[];metrics=[]
    for case in cases:
        frames=[f for f in native if f['recordingId']==case['recordingId']]
        result=policy.decide(frames,rgb,case['confirmedView'] if case['supported'] else None,True)
        sampled=[r for r in lines(folder/'rows.jsonl') if r['recordingId']==case['recordingId']]
        metrics.append(summarize(case,frames,result['states'],[float(clock(r['pose'])) for r in sampled]))
        results.append(dict(id=case['id'],**result))
    new=[r for r in metrics if r['role']=='separateSource'];supported=[r for r in new if r['supported']]
    write(folder/'results.json',results)
    write(folder/'report.json',dict(scope='Supported capture conditions screened before hand inference. Explicit source-confirmed view is diagnostic setup input; no phone qualification.',
        allNewSources=len(new),supportedNewSources=len(supported),excludedNewSources=len(new)-len(supported),
        supportedVideoOutcomes=dict(Counter(r['outcome'] for r in supported)),cases=metrics,
        modelFitPerformed=False,modelThresholdsChanged=False,counterPolicyChanged=False,phoneInstalled=False,
        hashes={str((folder/p).resolve()):sha(folder/p) for p in ['rows.jsonl','native-input.jsonl','native-output.jsonl','observations.jsonl','results.json']}))
    print((folder/'report.json').read_text())


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['prepare','infer','score']);a=parser.parse_args();globals()[a.action]()
