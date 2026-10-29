#!/usr/bin/env python3
"""Set-metadata development reprojection; never reruns or edits v30 inference."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-set-metadata-v31';BASE=ROOT/'build/grip-fullsets-v30/run/test'
def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,obj):
    with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
def verify():
    for name in ['baseline-freeze.json','candidate-freeze.json','input-freeze.json']:
        p=OUT/name
        if not p.exists():continue
        for path,digest in read(p)['sha256'].items():
            if sha(path)!=digest:
                assert name=='baseline-freeze.json'
                assert sha(OUT/'baseline-code'/Path(path).relative_to(ROOT))==digest,path
def prepare():
    verify();frames=lines(BASE/'native-output.jsonl');results=read(BASE/'grip-results.json');groups=read(BASE/'rep-records.json');out=[]
    for g in groups:
        fs=[f for f in frames if f['sourceEpoch']==g['sourceEpoch']];assert fs
        c=next(c for c in results if c['caseId']==fs[0]['caseId'])
        decisions=[d for d in c['decisions'] if d['sourceEpoch']==g['sourceEpoch']]
        set_id=g['reps'][0]['setId'] if g['reps'] else f"v22:{g['sourceEpoch']}"
        record=dict(id=set_id,exercise='pullUp',startedAt=fs[0]['seconds'],endedAt=fs[-1]['seconds'],endReason='sourceEnded',reps=g['reps'],backend='v30 frozen native/RGB research',modelRevision=1)
        out.append(dict(caseId=fs[0]['caseId'],sourceEpoch=g['sourceEpoch'],record=record,decisions=decisions))
    write(OUT/'input.json',out);write(OUT/'input-freeze.json',dict(sha256={str(OUT/'input.json'):sha(OUT/'input.json')}))
def score():
    verify();before=read(BASE/'scored-reps.json');candidates=read(OUT/'output.json');originals=read(OUT/'input.json');items=[]
    for candidate,original in zip(candidates,originals):
        assert candidate['caseId']==original['caseId'] and candidate['sourceEpoch']==original['sourceEpoch']
        assert candidate['record']['reps']==original['record']['reps']
        by={r['repId']:r for r in candidate['report']['reps']}
        for rep in before:
            if rep['caseId']!=candidate['caseId'] or rep['sourceEpoch']!=candidate['sourceEpoch'] or not rep['complete']:continue
            after=by[rep['repId']];assert after['width']==rep['nativeSavedWidth']
            items.append(dict(caseId=rep['caseId'],repId=rep['repId'],before=rep['offlineGrip'],after=after['orientation'],source=after['orientationSource'],reference=rep['referenceGrip'],correct=after['orientation']==rep['referenceGrip'],firstConfirmedAt=candidate['record']['setGrip']['firstConfirmedAt']))
    assert len(items)==12
    write(OUT/'comparison.json',dict(schemaVersion=31,scope='Exposed-v30 metadata reprojection using the same qualified decisions, source-identity episodes and unchanged measured reps. Not a classifier accuracy improvement, automatic physical-set acquisition or fresh test.',completedReps=len(items),before=dict(Counter('unknown' if i['before']=='unknown' else 'correct' if i['before']==i['reference'] else 'wrong' for i in items)),after=dict(Counter('unknown' if i['after']=='unknown' else 'correct' if i['correct'] else 'wrong' for i in items)),newlyAnnotatedEarlierReps=sum(i['before']=='unknown' and i['correct'] for i in items),counterWidthAndRawRepRecordsUnchanged=True,originalFrameLabelsAndScoresUnchanged=True,items=items,sha256={str(p):sha(p) for p in [OUT/'input.json',OUT/'output.json',BASE/'observations.jsonl',BASE/'grip-results.json',BASE/'rep-records.json']}))
    print(json.dumps({k:v for k,v in read(OUT/'comparison.json').items() if k not in ['items','sha256']},indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('action',choices=['prepare','score','verify']);globals()[p.parse_args().action]()
