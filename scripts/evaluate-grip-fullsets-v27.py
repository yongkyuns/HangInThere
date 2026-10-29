#!/usr/bin/env python3
"""Fresh continuous-set evaluation. Reference set boundaries never enter inference."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-width-v27'
RUN=OUT/'run'

def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,obj):
    with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
def inside(t,w):return w[0]<=t<w[1]
def complete(rep):return rep.get('acceptedAt') is not None and rep.get('returnedAt') is not None and rep['closeReason']=='returned'
def credit(frames,states,start,end):
    """Only adjacent equal labels in one exercise/source identity earn time."""
    amounts=Counter()
    for a,b,x,y in zip(frames,frames[1:],states,states[1:]):
        assert a['seconds']==x['seconds'] and b['seconds']==y['seconds']
        dt=b['seconds']-a['seconds']
        identity=lambda f:(f['sourceEpoch'],f['exercise']['epoch'],f['trackId'])
        if 0<dt<=.35 and identity(a)==identity(b) and x['label']==y['label'] and y['label']!='unknown':
            amounts[y['label']]+=max(0.,min(end,b['seconds'])-max(start,a['seconds']))
    return dict(amounts)
def new_movements(frames):
    previous=None;events=[]
    for f in frames:
        before=previous['movements'] if previous and previous['sourceEpoch']==f['sourceEpoch'] else 0
        if f['movements']>before:events.append(dict(seconds=f['seconds'],count=f['movements']-before))
        previous=f
    return events

def verify():
    for name in ['protocol.json','reference-freeze.json','implementation-completion-freeze.json','supplement-reference-freeze.json','run/input-freeze.json']:
        p=OUT/name
        if p.exists():
            for path,h in read(p)['sha256'].items():
                if Path(path)==Path(__file__) and sha(path)!=h:
                    repair=read(OUT/'accounting-repair.json')
                    assert h==repair['originalSHA256']==sha(repair['originalPath'])
                    assert sha(path)==repair['correctedSHA256']
                else:assert sha(path)==h,path
    refs=read(RUN/'source-references.json')
    fitting=''.join((ROOT/p).read_text() for p in ['build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl','build/grip-threeway/fitting.jsonl'])
    for c in refs['cases']+refs['excludedCases']+refs.get('nativeRejectedCases',[]):
        assert sha(c['path'])==c['sha256']
        assert c['id'] not in fitting and c['sha256'] not in fitting,c['id']
    correction=read(OUT/'source-review-correction.json')
    assert sha(OUT/'source-review-correction.json')==read(OUT/'accounting-repair.json')['sourceCorrectionSHA256']
    c=next(c for c in refs['cases'] if c['id']==correction['caseId'])
    s=next(s for s in c['sets'] if s['id']==correction['setId'])
    s.update(completeSet=correction['correctedCompleteSet'],activeWindow=correction['correctedActiveWindow'],heldWindow=correction['correctedHeldWindow'])
    c['negativeWindows'].append(correction['additionalNegativeWindow'])
    refs['unqualifiedGaps'][0]='No independently verified narrow-band reference in this cohort; narrow-titled example has a boundary-uncertain joint-center interval.'
    return refs

def prepare():
    verify();old=module('v27prepare','evaluate-grip-width-v21.py');old.OUT=RUN;old.verify=verify;old.prepare()
    paths=[RUN/'source-references.json',RUN/'test/native-input.jsonl',RUN/'test/rows.jsonl',Path(__file__)]
    write(RUN/'input-freeze.json',dict(sha256={str(p):sha(p) for p in paths}))

def infer():
    verify();old=module('v27infer','evaluate-grip-controlled-v17.py');old.OUT=RUN
    old.verify=lambda:dict(models=dict(quality='build/grip-anatomy-v11/frozenQuality/GripSurfaceClassifier.pt',neutral='build/grip-threeway/staged-models/threeway/GripHandClassifier.pt'))
    old.infer('test')

def score():
    refs=verify();policy=module('v27policy','grip-controlled-v17.py');reference=module('v27ref','grip-width-reference.py')
    inputs=lines(RUN/'test/native-input.jsonl');frames=lines(RUN/'test/native-output.jsonl')
    rows=lines(RUN/'test/rows.jsonl');rgb=lines(RUN/'test/observations.jsonl');records=read(RUN/'test/rep-records.json')
    assert len(inputs)==len(frames) and len(rows)==len(rgb)
    for i,o in zip(inputs,frames):
        assert i['caseId']==o['caseId'] and i['imageSHA256']==o['imageSHA256']
        assert o['seconds']==i['pose']['timestamp']['value']/i['pose']['timestamp']['timescale']
        if not i['widthViewSupported']:assert o['width']=='unknown'
    reports=[];all_states=[];all_reps=[]
    for c in refs['cases']:
        fs=[f for f in frames if f['caseId']==c['id']];observations=[r for r in rgb if r['caseId']==c['id']]
        assert len(observations)==sum(r['caseId']==c['id'] for r in rows)
        states=[];decisions=[];offset=0
        # Reset only for an actual source-view/shot change. Never split by set truth.
        while offset<len(fs):
            end=offset+1
            while end<len(fs) and fs[end]['shotId']==fs[offset]['shotId']:end+=1
            block=fs[offset:end];shot=next((s for s in c['shots'] if s['id']==block[0]['shotId']),None)
            hashes={f['imageSHA256'] for f in block};obs={r['imageSHA256']:r for r in observations if r['imageSHA256'] in hashes}
            result=policy.decide(block,obs,shot['view'] if shot else None,False)
            states+=result['states'];decisions+=result['decisions'];offset=end
        assert len(states)==len(fs)
        reps=[dict(r,sourceEpoch=g['sourceEpoch']) for g in records if g['identity'].split(':')[0]==c['sha256'] for r in g['reps']]
        completed=[r for r in reps if complete(r)];events=new_movements(fs);set_reports=[]
        for s in c['sets']:
            a=s['reference'];interval=reference.ratio_interval(a['regions'],a['support'],a['imageSize'])
            assert list(interval)==a['ratioInterval'] and reference.band(interval)==s['referenceWidth']
            active=[(f,state) for f,state in zip(fs,states) if inside(f['seconds'],s['activeWindow'])]
            current=[d for d in decisions if inside(d['seconds'],s['activeWindow'])]
            known=[f for f,_ in active if f['width']!='unknown'];type_known=[state for _,state in active if state['label']!='unknown']
            width_verifiable=s['referenceWidth'] not in ['uncertain','unknown'];type_verifiable=s['referenceGrip'] in ['underhand','overhand','neutral']
            owned=[r for r in reps if inside(r.get('extremeAt') or (r['startedAt']+r['closedAt'])/2,s['activeWindow'])]
            scored=[]
            for r in owned:
                duration=r['closedAt']-r['startedAt'];amounts=credit(fs,states,r['startedAt'],r['closedAt'])
                label=max(amounts,key=amounts.get) if amounts else 'unknown';coverage=amounts.get(label,0)/duration if duration>0 else 0
                if coverage<.8:label='unknown'
                saved=r['grip']['width'];contained=s['heldWindow'][0]<=r['startedAt']<=r['closedAt']<=s['heldWindow'][1]
                item=dict(caseId=c['id'],setId=s['id'],repId=r['id'],sourceEpoch=r['sourceEpoch'],startedAt=r['startedAt'],closedAt=r['closedAt'],complete=complete(r),containedInReferenceGripWindow=contained,
                    nativeSavedWidth=saved,nativeWidthCoverage=r['grip'].get('widthCoverage'),referenceWidth=s['referenceWidth'],
                    widthCorrect=(saved==s['referenceWidth']) if contained and width_verifiable and saved!='unknown' else None,
                    offlineGrip=label,offlineGripCoverage=coverage,referenceGrip=s['referenceGrip'],
                    gripCorrect=(label==s['referenceGrip']) if contained and type_verifiable and label!='unknown' else None)
                scored.append(item);all_reps.append(item)
            set_reports.append(dict(id=s['id'],referenceGrip=s['referenceGrip'],referenceWidth=s['referenceWidth'],referenceInterval=interval,completeSourceSet=s['completeSet'],sourceVisibleCycles=s['sourceVisibleCycles'],
                activeWindow=s['activeWindow'],nativeFrames=len(active),eligibleExerciseFrames=sum(f['exercise']['eligible'] for f,_ in active),scheduledImages=sum(inside(r['seconds'],s['activeWindow']) for r in observations),
                gripBands=dict(Counter(s['label'] for s in type_known)),gripCoverage=len(type_known)/len(active) if active else 0,
                wrongGripFrames=sum(st['label']!=s['referenceGrip'] for st in type_known) if type_verifiable else None,
                newGripDecisions=len(current),wrongNewGripDecisions=sum(d['label']!=s['referenceGrip'] for d in current) if type_verifiable else None,
                widthBands=dict(Counter(f['width'] for f,_ in active)),widthCoverage=len(known)/len(active) if active else 0,
                wrongWidthFrames=sum(f['width']!=s['referenceWidth'] for f in known) if width_verifiable else None,
                widthUnknownReasons=dict(Counter(f['widthReason'] for f,_ in active if f['width']=='unknown')),
                gripUnknownReasons=dict(Counter(st['reason'] for _,st in active if st['label']=='unknown')),
                completedCounterReps=sum(complete(r) for r in owned),counterAttempts=len(owned),reps=scored))
        negatives=[(f,s) for f,s in zip(fs,states) if any(inside(f['seconds'],w) for w in c['negativeWindows'])]
        is_negative=lambda t:any(inside(t,w) for w in c['negativeWindows'])
        transitions=[]
        for a,b in zip(c['sets'],c['sets'][1:]):
            first=[(f,s) for f,s in zip(fs,states) if inside(f['seconds'],b['activeWindow'])]
            correct_type=next((s['seconds'] for _,s in first if s['label']==b['referenceGrip']),None)
            first_width=next((f['seconds'] for f,_ in first if f['width']!='unknown'),None)
            transitions.append(dict(fromSet=a['id'],toSet=b['id'],typeChanged=a['referenceGrip']!=b['referenceGrip'],widthBandChangeVerifiable=a['referenceWidth'] not in ['unknown','uncertain'] and b['referenceWidth'] not in ['unknown','uncertain'],
                firstCorrectGrip=correct_type,firstKnownWidth=first_width,
                priorGripVisibleFrames=sum(st['label']==a['referenceGrip'] for _,st in first) if a['referenceGrip']!=b['referenceGrip'] else None,
                note='Whole continuous-shot replay. No reset at either reference set boundary.'))
        reports.append(dict(id=c['id'],nativeFrames=len(fs),scheduledImages=len(observations),sets=set_reports,transitions=transitions,
            actualCounterAttempts=len(reps),completedCounterReps=len(completed),unassignedCompletedReps=[dict(id=r['id'],startedAt=r['startedAt'],closedAt=r['closedAt']) for r in completed if not any(inside(r.get('extremeAt') or (r['startedAt']+r['closedAt'])/2,s['activeWindow']) for s in c['sets'])],
            negatives=dict(frames=len(negatives),newCounterMovements=sum(e['count'] for e in events if is_negative(e['seconds'])),completedReps=sum(is_negative(r['returnedAt']) for r in completed),
                newAcceptances=sum(r.get('acceptedAt') is not None and is_negative(r['acceptedAt']) for r in reps),exerciseEligibleFrames=sum(f['exercise']['eligible'] for f,_ in negatives),visibleGripFrames=sum(s['label']!='unknown' for _,s in negatives),newGripDecisions=sum(is_negative(d['seconds']) for d in decisions)),
            outsideViewKnownWidths=sum(f['width']!='unknown' for f in fs if f['shotId']=='unsupported')))
        all_states.append(dict(caseId=c['id'],states=states,decisions=decisions))
    result=dict(schemaVersion=27,qualification=refs['qualification'],allNewSources=refs['allNewSources'],processedSources=len(refs['cases']),excludedBeforeInference=refs['excludedCases'],nativeRejected=refs.get('nativeRejectedCases',[]),
        modelsFit=False,thresholdsChanged=False,phoneAcquisitionValidated=False,sourceSetBoundaryResets=False,sourceTemporalCorrection=read(OUT/'source-review-correction.json'),
        nativeFrames=len(frames),scheduledImages=len(rows),automaticHandCrops=sum(len(r['hands']) for r in rgb),cases=reports,unqualifiedGaps=refs['unqualifiedGaps'],
        sha256={str(p):sha(p) for p in [RUN/'test/native-input.jsonl',RUN/'test/native-output.jsonl',RUN/'test/rep-records.json',RUN/'test/observations.jsonl',Path(__file__)]})
    write(RUN/'test/report.json',result);write(RUN/'test/grip-results.json',all_states);write(RUN/'test/scored-reps.json',all_reps)
    print(json.dumps(dict(sources=len(reports),nativeFrames=len(frames),sets=sum(len(c['sets']) for c in reports),completedCounterReps=sum(c['completedCounterReps'] for c in reports),negatives=[dict(id=c['id'],**c['negatives']) for c in reports]),indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','infer','score']);globals()[p.parse_args().action]()
