#!/usr/bin/env python3
"""Native-cadence starting-state regression. No new images, labels or fitting."""
import argparse
from collections import Counter
from fractions import Fraction
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'build/grip-start-v18'
OLD = ROOT/'build/grip-controlled-v17'


def read(path): return json.loads(Path(path).read_text())
def lines(path): return [json.loads(x) for x in Path(path).read_text().splitlines()]
def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def clock(pose):
    t = pose['timestamp']
    return Fraction(t['value'], t['timescale'])
def write(path, obj):
    with Path(path).open('x') as f: json.dump(obj, f, indent=2); f.write('\n')
def module(name, filename):
    spec=importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    obj=importlib.util.module_from_spec(spec); spec.loader.exec_module(obj); return obj


def cases():
    return read(OLD/'protocol.json')['cases']+read(OLD/'test-source-references.json')['cases']


def verify():
    protocol=read(OUT/'protocol.json')
    assert all(sha(f)==h for f,h in protocol['sha256'].items())
    return protocol


def export():
    protocol=verify()
    exporter=ROOT/'build/dip-body-motion/verified/exporter/.build/release/dip-body-export'
    for case in cases():
        video=protocol['sources'][case['recordingId']]
        assert sha(video)==case['recordingId']
        target=OUT/'native'/case['id']
        if (target/'export.json').exists():
            assert read(target/'export.json')['videoSHA256']==case['recordingId']
            continue
        with (OUT/(case['id']+'-export.log')).open('x') as f:
            subprocess.run([str(exporter),video,str(target)],check=True,stdout=f,stderr=subprocess.STDOUT)
        print(case['id'],read(target/'export.json')['frames'],flush=True)


def prepare():
    verify(); tracker_module=module('native_track','grip-temporal-v5.py')
    old_inputs={r['imageSHA256']:r for stage in ['baseline','test'] for r in lines(OLD/stage/'native-input.jsonl')}
    result=[]; statistics=[]
    for case in cases():
        target=OUT/'native'/case['id']; poses=lines(target/'poses.jsonl'); receipt=read(target/'export.json')
        assert sha(target/'poses.jsonl')==receipt['observationsSHA256']
        sampled=lines(Path(case['framesDirectory'])/'frames.jsonl')
        by_clock={clock(r['pose']):r for r in sampled}
        tracker=tracker_module.SubjectTracker(); matched=equal=missing=retained=ownership_rejected=0
        for pose in poses:
            time=clock(pose)
            if case.get('evaluationEnd') is not None and time>Fraction(str(case['evaluationEnd'])): continue
            tracked=tracker.update(pose['people'],float(time))
            index=tracked['personIndex'] if tracked['status']=='tracked' else None
            source=by_clock.get(time)
            if source:
                matched+=1; equal+=pose==source['pose']
            else: missing+=1
            # No RGB fabricated for intervening body frames. The legacy replay
            # key is opaque here and can match a model observation only at an
            # original, verified sampled image timestamp.
            selected_pose=dict(pose,people=[pose['people'][index]] if index is not None else [])
            same_owner=source is not None and old_inputs[source['imageSHA256']]['pose']['people']==selected_pose['people'] and index is not None
            if source and not same_owner: ownership_rejected+=1
            image_key=source['imageSHA256'] if same_owner else f"noRGB:{case['recordingId']}:{time}"
            result.append(dict(recordingId=case['recordingId'],imageSHA256=image_key,
                               trackId=tracked['trackId'] if index is not None else None,
                               pose=selected_pose))
            retained+=1
        assert matched==equal==len(sampled),case['id']
        statistics.append(dict(id=case['id'],originalFrames=len(poses),evaluatedFrames=retained,
                               sampledPosesBitIdentical=equal,bodyOnlyFrames=missing,rgbOwnershipRejected=ownership_rejected))
    with (OUT/'native-input.jsonl').open('x') as f: f.write(''.join(json.dumps(r)+'\n' for r in result))
    write(OUT/'prepare-receipt.json',dict(cases=statistics,frames=len(result),sha256=sha(OUT/'native-input.jsonl')))
    print(json.dumps(statistics,indent=2))


def score():
    verify(); policy=module('frozen_hand','grip-controlled-v17.py'); evaluator=module('frozen_score','evaluate-grip-controlled-v17.py')
    native=lines(OUT/'native-output.jsonl')
    rgb={r['imageSHA256']:r for stage in ['baseline','test'] for r in lines(OLD/stage/'observations.jsonl')}
    clips=[];negative=Counter()
    for case in cases():
        frames=[r for r in native if r['recordingId']==case['recordingId']]
        decision=policy.decide(frames,rgb,case['confirmedView'],True)
        previous={};phases=Counter();wrong=0;correct=[];selected=[]
        for frame,state in zip(frames,decision['states']):
            phase=evaluator.phase(case,state['seconds']); state['phaseReference']=phase
            epoch=frame['sourceEpoch']; increment=max(0,frame['movements']-previous.get(epoch,0));previous[epoch]=frame['movements']
            phases[frame['phase']]+=1
            if phase in ['setup','release']:
                negative[phase+'Frames']+=1;negative['newMovements']+=increment
                negative['exerciseAdmissions']+=frame['exercise']['eligible'];negative['visibleGripLabels']+=state['label']!='unknown'
            if state['observed'] and case['grip']!='unknown' and state['label']!=case['grip']:wrong+=1
            if phase=='exercise' and case['grip']!='unknown' and state['label']==case['grip']:correct.append(state['seconds'])
            if frame['imageSHA256'] in rgb: selected.append(state)
        exercise=[s for s in selected if s['phaseReference']=='exercise']
        cycles=[dict(window=w,correctVisible=any(w[0]<=s['seconds']<=w[1] and s['label']==case['grip'] for s in decision['states'])) for w in case['fullCycleWindows']]
        clips.append(dict(id=case['id'],referenceGrip=case['grip'],confirmedView=case['confirmedView'],
                          nativeFrames=len(frames),counterPhases=dict(phases),counterEligibleFrames=sum(f['exercise']['eligible'] for f in frames),
                          firstCorrectSeconds=correct[0] if correct else None,wrongNewGripDecisions=wrong,
                          rgbExerciseFrames=len(exercise),rgbExerciseVisibleLabels=sum(s['label']!='unknown' for s in exercise),
                          cycles=cycles,states=decision['states'],decisions=decision['decisions']))
    write(OUT/'results.json',clips)
    write(OUT/'report.json',dict(qualification='Development regressions only, same exposed sources and frozen readability-enabled v17 hand policy. Native body cadence; RGB only at original sampled timestamps.',
                                cases=[{k:v for k,v in c.items() if k not in ['states','decisions']} for c in clips],
                                negatives=dict(negative),modelThresholdsChanged=False,modelFitPerformed=False,phoneInstalled=False,
                                inputSHA256=sha(OUT/'native-input.jsonl'),nativeOutputSHA256=sha(OUT/'native-output.jsonl')))
    print((OUT/'report.json').read_text())


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['export','prepare','score']);args=parser.parse_args();globals()[args.action]()
