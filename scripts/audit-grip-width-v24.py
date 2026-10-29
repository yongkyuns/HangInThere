#!/usr/bin/env python3
"""Compare width policy 3 with exposed policy-2 regressions without relabeling."""
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-width-v24'
OLD=ROOT/'build/grip-width-v23'

def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def module(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def write(p,obj):
    with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')

def main():
    pins=0
    for filename in ['baseline-freeze.json','candidate-freeze.json']:
        for path,digest in read(OUT/filename)['sha256'].items():
            if sha(path)!=digest:
                assert filename=='baseline-freeze.json'
                backup=OUT/'baseline-code'/Path(path).relative_to(ROOT)
                assert backup.exists() and sha(backup)==digest,path
            pins+=1
    fields=['recordingId','caseId','shotId','imageSHA256','seconds','trackId','sourceEpoch',
            'phase','trackingIssue','movements','released','reference','supportBySide','exercise',
            'armMeasurements','armUnavailableReason']
    counts={}
    for name,oldpath in [('native',OLD/'test/native-output.jsonl'),
            ('alternating',OLD/'test/alternating-output.jsonl'),
            ('older',ROOT/'build/grip-width-v22/native-output-r3.jsonl')]:
        before=lines(oldpath);after=lines(OUT/(name+'-output.jsonl'))
        assert len(before)==len(after)
        assert all(all(a[k]==b[k] for k in fields) for a,b in zip(before,after)),name
        assert all(r['widthPolicyVersion']==3 for r in after)
        assert all(r['width']=='unknown' for r in after if not r['widthBodyUsable'])
        junit=ET.parse(OUT/(name+'-replay-junit.xml')).getroot()
        assert int(junit.attrib['tests'])==1 and int(junit.attrib['failures'])==int(junit.attrib['errors'])==0
        counts[name]=len(after)
    assert counts==dict(native=1517,alternating=2152,older=7579)
    policy=module('v24grip','grip-controlled-v17.py')
    accounting=module('v24accounting','evaluate-grip-supported-v19.py')
    refs=read(OLD/'source-references.json')['cases'];before=lines(OLD/'test/native-output.jsonl')
    after=lines(OUT/'native-output.jsonl');rgb=lines(OLD/'test/observations.jsonl')
    report=[]
    for c in refs:
        fs=[r for r in after if r['caseId']==c['id']]
        obs={r['imageSHA256']:r for r in rgb if r['caseId']==c['id']}
        grip=policy.decide(fs,obs,c['confirmedView'],False)
        assert grip==policy.decide([r for r in before if r['caseId']==c['id']],obs,c['confirmedView'],False)
        shot=c['shots'][0];window=shot['window']
        def summarize(frames):
            exercise=[r for r in frames if r['caseId']==c['id'] and
                window[0]<=r['seconds']<window[1] and accounting.phase(c,r['seconds'])=='exercise']
            known=[r for r in exercise if r['width']!='unknown'];ref=shot['referenceWidth']
            return dict(exerciseFrames=len(exercise),knownWidthFrames=len(known),
                coverage=len(known)/len(exercise),bands=dict(Counter(r['width'] for r in exercise)),
                medianRatio=sorted(r['projectedSpanRatio'] for r in known)[len(known)//2] if known else None,
                wrongKnownFrames=sum(r['width']!=ref for r in known) if ref!='uncertain' else None,
                unknownReasons=dict(Counter(r['widthReason'] for r in exercise if r['width']=='unknown')))
        negative=accounting.summarize(c,fs,grip['states'],[])['negativeControls']
        assert negative.get('newMovements',0)==negative.get('visibleLabels',0)==0
        report.append(dict(id=c['id'],referenceWidth=shot['referenceWidth'],referenceUnchanged=True,
            baseline=summarize(before),candidate=summarize(after),gripStatesBitIdentical=True,negativeControls=negative))

    prior=read(OLD/'test/rep-records.json');current=read(OUT/'native-rep-records.json')
    assert len(prior)==len(current)
    actual=[]
    for a,b in zip(prior,current):
        assert {k:v for k,v in a.items() if k!='reps'}=={k:v for k,v in b.items() if k!='reps'}
        assert len(a['reps'])==len(b['reps'])
        for old,new in zip(a['reps'],b['reps']):
            assert {k:v for k,v in old.items() if k!='grip'}=={k:v for k,v in new.items() if k!='grip'}
            complete=new.get('acceptedAt') is not None and new.get('returnedAt') is not None and new['closeReason']=='returned'
            actual.append(dict(recordingId=b['identity'].split(':')[0],ordinal=new['ordinal'],
                startedAt=new['startedAt'],closedAt=new['closedAt'],complete=complete,
                baselineWidth=old['grip']['width'],candidateWidth=new['grip']['width'],
                baselineCoverage=old['grip']['widthCoverage'],candidateCoverage=new['grip']['widthCoverage']))

    sequence=lines(OUT/'alternating-output.jsonl');isolated={(r['caseId'],r['seconds']):r for r in after}
    plan=read(OLD/'alternating-plan.json')['order']
    widthfields=['width','projectedSpanRatio','widthReason','widthBodyUsable','phase','movements','reference','exercise']
    for entry in plan:
        fs=[r for r in sequence if r['caseId']==str(entry['position'])+':'+entry['caseId']]
        assert len(fs)==entry['frames'] and fs[0]['width']=='unknown'
        for r in fs:assert all(r[k]==isolated[entry['caseId'],r['seconds']][k] for k in widthfields)
    tests=read(OUT/'shared-test-receipt.json')
    assert tests==dict(tests=824,failures=0,errors=0,skipped=0)
    complete=[r for r in actual if r['complete']]
    result=dict(schemaVersion=24,scope='Exposed development regression; original labels and source-view inputs retained. Not independent accuracy or phone acquisition.',
        widthPolicyVersion=3,frozenPinsVerified=pins,counterAndExerciseFramesBitIdentical=counts,
        gripPolicyOutputsBitIdentical=True,cases=report,attempts=actual,
        completedReps=len(complete),completedRepWidthBands=dict(Counter(r['candidateWidth'] for r in complete)),
        noWidthCarryoverAcrossExplicitSetBoundaries=True,continuousAlternatingWorkoutValidated=False,
        sharedTests=tests,modelsChanged=False,repCounterChanged=False,categoryBoundariesChanged=False,
        confidenceThresholdsChanged=False,liveViewGateChanged=False,phoneInstalled=False,
        sha256={str(p):sha(p) for p in [OUT/'baseline-freeze.json',OUT/'candidate-freeze.json',
            OUT/'native-output.jsonl',OUT/'native-rep-records.json',OUT/'alternating-output.jsonl',
            OUT/'alternating-rep-records.json',OUT/'older-output.jsonl',OUT/'older-rep-records.json',Path(__file__)]})
    write(OUT/'comparison.json',result)
    print(json.dumps({k:v for k,v in result.items() if k!='sha256'},indent=2))

if __name__=='__main__':main()
