#!/usr/bin/env python3
"""Controlled confirmed-view baseline, frozen readability ablation, then separate videos."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-controlled-v17'


def module(name,filename):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m


policy=module('controlled_policy','grip-controlled-v17.py')
crop=module('controlled_crop','prepare-threeway-crops.py')
tracker=module('controlled_track','grip-temporal-v5.py')


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def lines(p):return [json.loads(x) for x in Path(p).read_text().splitlines()]
def seconds(r):
    t=r['pose']['timestamp'];return t['value']/t['timescale']
def write(p,obj):
    with p.open('x') as f:f.write(json.dumps(obj,indent=2)+'\n')


def freeze():
    OUT.mkdir(parents=True,exist_ok=True)
    cases=[]
    for name,grip,active,release,cycles in [
        ('pullup','overhand',[4.8,19.75],20.55,[[10.5,13.3],[14.7,18.0]]),
        ('chinup','underhand',[4.5,17.1],18.,[[13.6,17.2]])]:
        directory=ROOT/'build/grip-training'/(name+'-frames');rs=lines(directory/'frames.jsonl')
        cases.append(dict(id=name,recordingId=rs[0]['recordingId'],sourceGroup='owner-development',framesDirectory=str(directory),
                          confirmedView='front',grip=grip,activeWindow=active,releaseStart=release,fullCycleWindows=cycles,
                          role='Development-only, previously exposed owner/setting. Pull-up recording has v11 fitting exposure; neither is independent qualification.'))
    sources=[]
    for p in [ROOT/'build/grip-front-rear-v9/untouched-final-reserve.json',ROOT/'build/grip-front-rear-v9/k600-val/untouched-final-reserve.json']:
        sources+=json.loads(p.read_text())['sources']
    # Metadata-only selection before new source pixels. Preserve eight reserves.
    planned=sorted(sources,key=lambda s:s['sha256'])[:6]
    models=dict(quality='build/grip-anatomy-v11/frozenQuality/GripSurfaceClassifier.pt',neutral='build/grip-threeway/staged-models/threeway/GripHandClassifier.pt')
    paths=[Path(__file__),ROOT/'scripts/grip-controlled-v17.py',ROOT/'scripts/tests/test_grip_controlled_v17.py',
           ROOT/'scripts/prepare-threeway-crops.py',ROOT/'scripts/prepare-reviewed-grip.py',ROOT/'scripts/grip-temporal-v5.py',
           ROOT/'Evaluation/androidTests/GripRepContextV16Tests.kt',ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/ExerciseCounter.kt',
           ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGripExerciseContext.kt',ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutAnalysis.kt',
           ROOT/'build/grip-front-rear-v9/combined-final-reserve.json',ROOT/'build/grip-training/exporter/.build/release/grip-frames']+[ROOT/p for p in models.values()]
    for case in cases:paths.append(Path(case['framesDirectory'])/'frames.jsonl')
    write(OUT/'protocol.json',dict(schemaVersion=17,cases=cases,models=models,plannedSeparateTestSources=planned,sha256={str(p):sha(p) for p in paths},
        scope='Controlled exposed development baseline with explicitly confirmed camera view; no automatic orientation claim or model fitting.',
        crop='Original observed-wrist .9 forearm square fallback; both variants use identical exact crops. Not a complete-hand detector; source/crop inspection separately recorded. No manual crop adjustment or desired-grip input.',
        ablation='A requires readability>=.8. B omits readability only; raw surface/neutral model scores unchanged, each>=.8. Neither uses learned contact. Confirmed view is setup input, not inferred from target grip.',
        references='Direct source-only agent review of entire development sequences before predictions. Active windows include hang/partial movement; full-cycle windows are visible excursions, not strict form judgments. Boundary bands stay uncertain; old references unchanged.',
        passRule='Candidate B must have zero wrong confirmed development decisions, zero visible labels in definite setup/release, and >=1 development recording with a correct label in every marked full-cycle window. Report both complete recordings, timing, coverage, first partials and all failures. No subset chosen by model success.',
        subsequentTest='Only after baseline pass, freeze B and input/model hashes before decoding six metadata-selected unread recordings. Source-screen capture scope and labels before inference; keep outliers and failures in all-source accounting. No replacement clips or post-test tuning. Other eight new reserves remain unread.',
        deployment='Offline diagnostic. No phone installation, rep-policy change, new training or source transfer into fitting.'))
    print(json.dumps(dict(developmentRecordings=2,plannedSeparateRecordings=len(planned),newReservesLeft=len(sources)-len(planned))))


def verify():
    p=json.loads((OUT/'protocol.json').read_text());assert all(sha(path)==h for path,h in p['sha256'].items());return p


def prepare(stage):
    from PIL import Image,ImageDraw,ImageOps,ImageFont
    protocol=verify();folder=OUT/stage;folder.mkdir(exist_ok=True)
    if stage=='baseline':cases=protocol['cases']
    else:
        gate=json.loads((OUT/'candidate-freeze.json').read_text());assert gate['baselinePassed']
        cases=json.loads((OUT/'test-source-references.json').read_text())['cases']
    output=[];native=[];patches=[]
    for case in cases:
        directory=Path(case['framesDirectory']);rs=lines(directory/'frames.jsonl');target_tracker=tracker.SubjectTracker()
        for r in rs:
            track=target_tracker.update(r['pose']['people'],seconds(r))
            index=track['personIndex'] if track['status']=='tracked' else None
            # Body-free setup/release rows stay in the timeline and denominator.
            pose=dict(r['pose'],people=[r['pose']['people'][index]] if index is not None else [])
            native.append(dict(recordingId=case['recordingId'],imageSHA256=r['imageSHA256'],trackId=track['trackId'] if index is not None else None,pose=pose))
            source=directory/r['image'];assert sha(source)==r['imageSHA256']
            row=dict(r,caseId=case['id'],framesDirectory=str(directory),patches=[])
            with Image.open(source) as im:
                im=im.convert('RGB')
                for side in ['left','right']:
                    geometry=crop.geometry(r['pose']['people'][index],side,im.size) if index is not None else None
                    patch=dict(side=side,status='noTrackedWrist')
                    if geometry:
                        box,wrist=geometry;path=folder/'patches'/f"{case['recordingId'][:10]}-{r['imageSHA256'][:12]}-{side}.png";path.parent.mkdir(exist_ok=True)
                        im.crop(box).resize((128,128),Image.Resampling.BILINEAR).save(path)
                        patch.update(status='patchReady',box=box,wrist=wrist,patch=str(path),patchSHA256=sha(path))
                        patches.append(dict(caseId=case['id'],seconds=seconds(r),side=side,**{k:v for k,v in patch.items() if k!='side'}))
                    row['patches'].append(patch)
            output.append(row)
    for filename,rs in [('rows.jsonl',output),('native-input.jsonl',native)]:
        with (folder/filename).open('x') as f:f.write(''.join(json.dumps(r)+'\n' for r in rs))
    font=ImageFont.load_default(size=14)
    for page in range(math.ceil(len(patches)/64)):
        sheet=Image.new('RGB',(1280,8*164),'white');d=ImageDraw.Draw(sheet)
        for j,p in enumerate(patches[page*64:(page+1)*64]):
            x=j%8*160;y=j//8*164;d.text((x+3,y+2),f"{p['caseId'][:13]} {p['seconds']:.2f}",fill='black',font=font);d.text((x+3,y+18),p['side'],fill='black',font=font)
            sheet.paste(Image.open(p['patch']),(x+3,y+35))
        sheet.save(folder/f'crop-review-{page+1:02d}.jpg',quality=88)
    write(folder/'prepare-receipt.json',dict(frames=len(output),patches=len(patches),sourceImagesVerified=True,
        nativeInputSHA256=sha(folder/'native-input.jsonl'),rowsSHA256=sha(folder/'rows.jsonl'),cropPages=math.ceil(len(patches)/64)))
    print(json.dumps(dict(frames=len(output),patches=len(patches),cropPages=math.ceil(len(patches)/64))))


def infer(stage):
    import torch
    from PIL import Image
    from torchvision.transforms import ToTensor
    protocol=verify();folder=OUT/stage;rows=lines(folder/'rows.jsonl')
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    quality=torch.jit.load(str(ROOT/protocol['models']['quality'])).eval();neutral=torch.jit.load(str(ROOT/protocol['models']['neutral'])).eval()
    tensor=ToTensor();outputs=[]
    with torch.inference_mode():
        for r in rows:
            out=dict(recordingId=r['recordingId'],caseId=r['caseId'],imageSHA256=r['imageSHA256'],seconds=seconds(r),hands=[])
            for p in r['patches']:
                if p['status']!='patchReady':continue
                assert sha(p['patch'])==p['patchSHA256'];x=tensor(Image.open(p['patch']).convert('RGB')).unsqueeze(0);s,v,c=quality(x);n=[]
                for i in range(3):
                    view=torch.zeros(1,3);view[0,i]=1.;g,_,_=neutral(x,view);n.append(float(g[0,2]))
                out['hands'].append(dict(side=p['side'],surface=s[0].tolist(),visible=float(v[0,1]),contact=float(c[0,1]),neutralByView=n,patch=p['patch'],patchSHA256=p['patchSHA256']))
            outputs.append(out)
    with (folder/'observations.jsonl').open('x') as f:f.write(''.join(json.dumps(r)+'\n' for r in outputs))
    print(json.dumps(dict(frames=len(outputs),hands=sum(len(r['hands']) for r in outputs))))


def phase(case,t):
    if case['activeWindow'][0]<=t<=case['activeWindow'][1]:return 'exercise'
    if t<case['activeWindow'][0]:return 'setup'
    if t>=case['releaseStart']:return 'release'
    return 'uncertain'


def score(stage):
    protocol=verify();folder=OUT/stage;rgb=lines(folder/'observations.jsonl');native=lines(folder/'native-output.jsonl');groups=defaultdict(list)
    for r in native:groups[r['recordingId']].append(r)
    cases=protocol['cases'] if stage=='baseline' else json.loads((OUT/'test-source-references.json').read_text())['cases']
    observations={r['imageSHA256']:r for r in rgb};results={};metrics={}
    for variant,readability in [('withReadability',True),('withoutReadability',False)]:
        clips=[];totals=Counter();phases=defaultdict(Counter)
        for case in cases:
            frames=groups[case['recordingId']];r=policy.decide(frames,observations,case['confirmedView'],readability)
            states=[];wrong=negative=0
            for f,s in zip(frames,r['states']):
                ph=phase(case,s['seconds']);states.append(dict(s,phaseReference=ph));phases[ph]['frames']+=1
                phases[ph]['counterExerciseEligible']+=f['exercise']['eligible'];phases[ph]['labelVisible']+=s['label']!='unknown';phases[ph]['newDecision']+=s['observed']
                if s['label']!='unknown' and ph in ['setup','release']:negative+=1
                if s['observed']:
                    category='acceptedUnverifiable' if case['grip']=='unknown' else 'correct' if s['label']==case['grip'] else 'wrong'
                    totals[category]+=1;wrong+=category=='wrong'
            cycles=[]
            for window in case['fullCycleWindows']:
                ss=[s for s in states if window[0]<=s['seconds']<=window[1]]
                cycles.append(dict(window=window,correctVisible=any(s['label']==case['grip'] for s in ss),
                                   correctNewDecision=any(s['label']==case['grip'] and s['observed'] for s in ss)))
            exercise=[s for s in states if s['phaseReference']=='exercise'];known=[s for s in exercise if s['label']!='unknown']
            correct=[s for s in exercise if s['label']==case['grip']]
            passed=bool(cycles) and all(c['correctVisible'] for c in cycles) and wrong==negative==0
            clips.append(dict(case,states=states,decisions=r['decisions'],fullCycles=cycles,completeSetPassed=passed,
                wrongConfirmedDecisions=wrong,negativeVisibleFrames=negative,firstCorrectSeconds=correct[0]['seconds'] if correct else None,
                exerciseLabelCoverage=len(known)/len(exercise) if exercise else None))
        metrics[variant]=dict(decisionCounts=dict(totals),sampledLifecycle=dict(phases),completeSetsPassed=sum(c['completeSetPassed'] for c in clips),
                              recordings=len(cases),wrongConfirmedDecisions=sum(c['wrongConfirmedDecisions'] for c in clips),negativeVisibleFrames=sum(c['negativeVisibleFrames'] for c in clips))
        results[variant]=clips
    candidate=metrics['withoutReadability'];passed=candidate['completeSetsPassed']>=1 and candidate['wrongConfirmedDecisions']==candidate['negativeVisibleFrames']==0
    report=dict(schemaVersion=17,stage=stage,qualification=protocol['scope'],confirmedViewProvided=True,metrics=metrics,candidateBaselinePassed=passed if stage=='baseline' else None,
        rawAnatomyAndNeutralThresholds=.8,learnedContactRequired=False,modelFitPerformed=False,phoneInstalled=False,
        hashes=dict(protocol=sha(OUT/'protocol.json'),rows=sha(folder/'rows.jsonl'),observations=sha(folder/'observations.jsonl'),native=sha(folder/'native-output.jsonl')))
    write(folder/'report.json',report);write(folder/'results.json',results)
    if stage=='baseline' and passed:
        paths=[OUT/'protocol.json',Path(__file__),ROOT/'scripts/grip-controlled-v17.py',folder/'report.json',folder/'results.json',folder/'observations.jsonl']+[ROOT/p for p in protocol['models'].values()]
        write(OUT/'candidate-freeze.json',dict(baselinePassed=True,variant='withoutReadability',confirmedViewRequired=True,sha256={str(p):sha(p) for p in paths},
            qualification='Frozen after controlled development pass, before separate-source decoding/review/inference. Readability removal remains an unqualified ablation, not a confidence calibration fix.'))
    print(json.dumps(report,indent=2))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['freeze','prepare','infer','score']);p.add_argument('--stage',choices=['baseline','test'],default='baseline');a=p.parse_args()
    freeze() if a.action=='freeze' else globals()[a.action](a.stage)
