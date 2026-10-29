#!/usr/bin/env python3
"""Frozen-video surface comparison; no training or test-based threshold selection."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
import argparse
from collections import Counter,defaultdict
import importlib.util
import json
from pathlib import Path

import numpy as np
from PIL import Image
import torch
from torchvision.transforms import ToTensor

spec=importlib.util.spec_from_file_location('surface_evaluation',Path(__file__).with_name('train-grip-surface-v2.py'))
surface=importlib.util.module_from_spec(spec);spec.loader.exec_module(surface);base=surface.base


def count(bucket,truth,conditional,visible):
    raw=int(np.argmax(conditional));accepted=float(max(conditional))>=.8 and visible>=.8
    bucket['crops']+=1
    if truth<0:
        bucket['unverifiable']+=1;bucket['acceptedUnverifiable']+=accepted
    else:
        bucket['known']+=1;bucket['forcedKnownCorrect']+=raw==truth
        if accepted:bucket['acceptedCorrect' if raw==truth else 'acceptedWrong']+=1
        else:bucket['unknown']+=1
    return raw,accepted


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['input','review','reserve','model','baseline','freeze','output']:p.add_argument(name,type=Path)
    a=p.parse_args();assert not (a.output/'report.json').exists();a.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    frozen=json.loads(a.freeze.read_text());assert all(base.sha(path)==digest for path,digest in frozen['sha256'].items())
    rows=base.load(a.input,True);assert all(r['annotation']['split']=='test' for r in rows)
    review=json.loads(a.review.read_text());assert review['outputSHA256']==base.sha(a.input)
    refs={(x['recordingId'],x['reviewId']):x for x in review['patches']}
    pairs=base.pairs(rows)
    for r,p in pairs:assert p['threewayReview']==refs[r['recordingId'],p['reviewId']]
    reserve=json.loads(a.reserve.read_text());receipt=json.loads((a.model/'training-receipt.json').read_text())
    assert receipt['testReserveSHA256']==base.sha(a.reserve) and receipt['modelSHA256']==base.sha(a.model/'GripSurfaceClassifier.pt')
    assert {r['recordingId'] for r in rows}<={s['sha256'] for s in reserve['sources']}
    assert not {s['sourceGroup'] for s in reserve['sources']}&set(sum(receipt['sourceGroups'].values(),[]))
    model=torch.jit.load(str(a.model/'GripSurfaceClassifier.pt')).eval();old=torch.jit.load(str(a.baseline/'GripHandClassifier.pt')).eval()
    eager=surface.SurfaceModel(False);eager.load_state_dict(torch.load(a.model/'weights.pt',weights_only=True));eager.eval()
    transform=ToTensor();predictions={};parity=0.
    with torch.inference_mode():
        for start in range(0,len(pairs),16):
            batch=pairs[start:start+16];h=torch.stack([transform(Image.open(p['patch']).convert('RGB')) for r,p in batch]);new=model(h);check=eager(h)
            for x,y in zip(new,check):parity=max(parity,float((x-y).abs().max()))
            _,os,oc=old(h,torch.zeros(len(batch),3))
            for i,(r,p) in enumerate(batch):
                visible=1-float(os[i,3]);conditional=os[i,:3]/os[i,:3].sum().clamp_min(1e-8)
                predictions[p['reviewId']]={
                    'previous':dict(surface=conditional.tolist(),visible=visible,contact=oc[i].tolist(),originalFourWay=os[i].tolist()),
                    'new':dict(surface=new[0][i].tolist(),visible=float(new[1][i,1]),contact=new[2][i].tolist())}
    assert parity<1e-5
    metrics=defaultdict(Counter);confusion=defaultdict(lambda:defaultdict(Counter));source=defaultdict(lambda:defaultdict(Counter));contact=defaultdict(Counter);records=[]
    for r in rows:
        for p in r['patches']:
            if p['status']!='patchReady':continue
            ref=p['threewayReview'];truth=surface.SURFACES.index(ref['surface']) if ref['surface'] in surface.SURFACES else -1;values=predictions[p['reviewId']]
            for name,value in values.items():
                raw,accepted=count(metrics[name],truth,value['surface'],value['visible'])
                count(metrics[name+':'+ref['surface']],truth,value['surface'],value['visible'])
                if ref['grip'] in base.GRIPS:count(metrics[name+':grip:'+ref['grip']+':'+ref['view']],truth,value['surface'],value['visible'])
                confusion[name][ref['surface']][surface.SURFACES[raw]]+=1
                if truth>=0:
                    source[name+':'+ref['surface']][r['recordingId']]['n']+=1
                    source[name+':'+ref['surface']][r['recordingId']]['correct']+=raw==truth
                if ref.get('qualityEligible',True):
                    positive=ref['contact']=='closed';pred=value['contact'][1]>=.8
                    contact[name]['truePositive' if positive and pred else 'falseNegative' if positive else 'falsePositive' if pred else 'trueNegative']+=1
                value['forcedKnownPrediction']=surface.SURFACES[raw];value['acceptedPrediction']=surface.SURFACES[raw] if accepted else 'unknown'
            records.append(dict(caseId=r['caseId'],recordingId=r['recordingId'],reviewId=p['reviewId'],patch=p['patch'],origin=p.get('origin','SDK'),reference=ref,results=values))
    video={key:dict(videos=len(values),meanSourceAccuracy=float(np.mean([v['correct']/v['n'] for v in values.values()]))) for key,values in source.items()}
    report=dict(qualification='Independent RGB hand-surface comparison on separate public videos; two agent pixel-review passes, not independent human gold labels. Forced-known argmax compares three anatomical classes for both models, without claiming confidence acceptance.',
        modelSHA256=receipt['modelSHA256'],baselineSHA256=base.sha(a.baseline/'GripHandClassifier.pt'),inputSHA256=base.sha(a.input),reviewSHA256=base.sha(a.review),reserveSHA256=base.sha(a.reserve),freezeSHA256=base.sha(a.freeze),scriptSHA256=base.sha(__file__),
        population=dict(reservedVideos=len(reserve['sources']),sampledVideos=len({r['recordingId'] for r in rows}),sampledFrames=len(rows),expectedHandSlots=sum(len(r['patches']) for r in rows),automaticHandCrops=len(pairs)),
        surfaceValidationGatePassed=receipt['surfaceValidationGatePassed'],thresholds=dict(conditionalSurface=.8,visible=.8,contact=.8),metrics=metrics,forcedKnownConfusion=confusion,sourceMacro=video,contact=contact,torchScriptEagerMaximumDifference=parity,
        missingStatus=dict(Counter(p['status'] for r in rows for p in r['patches'] if p['status']!='patchReady')))
    (a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');(a.output/'predictions.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in records));print(json.dumps(report,indent=2))


if __name__=='__main__':main()
