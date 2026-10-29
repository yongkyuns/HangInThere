#!/usr/bin/env python3
"""Reserved-video paired binary/ternary test, with stage and clip diagnostics.

Reviewed views are privileged component inputs, reported separately from frozen
automatic torso orientation. References never suppress inference pixels.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse
from collections import Counter,defaultdict
import importlib.util
import json
from pathlib import Path

import numpy as np
import torch
from torch import nn
from torchvision import transforms
from PIL import Image

spec=importlib.util.spec_from_file_location('staged_eval_training',Path(__file__).with_name('train-staged-grip.py'))
staged=importlib.util.module_from_spec(spec);spec.loader.exec_module(staged);base=staged.base


def metrics(m,grip,contact,target,valid=True,orientation=True):
    m['expectedHands']+=1
    if grip is None:m['missingHandInput']+=1;m['unknown']+=1;return
    m['availablePatches']+=1
    if not valid:m['invalidTargetCrop']+=1
    if not orientation:m['missingOrientation']+=1;m['unknown']+=1;return
    best=int(np.argmax(grip));correct=valid and base.GRIPS[best]==target
    if max(grip)-min(grip)<1e-7:m['tied']+=1;m['unknown']+=1;return
    m['rawCorrect' if correct else 'rawWrong']+=1
    accepted=max(grip)>=.8 and contact[1]>=.8
    if accepted:m['acceptedCorrect' if correct else 'acceptedWrong']+=1
    else:
        m['unknown']+=1
        if contact[1]<.8:m['contactRejected']+=1
        if max(grip)<.8:m['lowGripScore']+=1


def predict(model,pairs,views):
    result={};tensor=transforms.ToTensor()
    with torch.inference_mode():
        for start in range(0,len(pairs),16):
            batch=pairs[start:start+16];images=torch.stack([tensor(Image.open(p['patch']).convert('RGB')) for r,p in batch])
            view=torch.stack(views[start:start+16]);g,s,c=model(images,view)
            for j,(r,p) in enumerate(batch):result[p['reviewId']]=dict(grip=g[j].tolist()+([0.] if g.shape[1]==2 else []),surface=s[j].tolist(),contact=c[j].tolist())
    return result


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['input','review','reserve','models','torso_model','freeze','output']:p.add_argument(name,type=Path)
    a=p.parse_args();assert not (a.output/'report.json').exists();a.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    frozen=json.loads(a.freeze.read_text());assert all(base.sha(path)==digest for path,digest in frozen['sha256'].items()),'Frozen source/model changed'
    data=base.load(a.input,True);assert all(r['annotation']['split']=='test' for r in data)
    review=json.loads(a.review.read_text());assert review['outputSHA256']==base.sha(a.input)
    refs={(x['recordingId'],x['reviewId']):x for x in review['patches']}
    pairs=base.pairs(data)
    for r,patch in pairs:assert patch['threewayReview']==refs[r['recordingId'],patch['reviewId']]
    reserve=json.loads(a.reserve.read_text());source_hashes={x['sha256'] for x in reserve['sources']};groups={x['sourceGroup'] for x in reserve['sources']}
    assert {r['recordingId'] for r in data}<=source_hashes
    receipts={};models={}
    for name in ['binary','threeway']:
        root=a.models/name;receipt=json.loads((root/'training-receipt.json').read_text());receipts[name]=receipt
        assert receipt['testReserveSHA256']==base.sha(a.reserve)
        assert base.sha(root/'GripHandClassifier.pt')==receipt['modelSHA256']
        assert not source_hashes&set(sum(receipt['sources'].values(),[]))
        assert not groups&set(sum(receipt['sourceGroups'].values(),[]))
        models[name]=torch.jit.load(str(root/'GripHandClassifier.pt')).eval()
    oracle=[nn.functional.one_hot(torch.tensor(base.VIEWS.index(p['threewayReview']['view'])),3).float() for r,p in pairs]
    torso=torch.jit.load(str(a.torso_model/'GripContextClassifier.pt')).eval();tensor=transforms.ToTensor();automatic=[]
    for start in range(0,len(pairs),16):
        batch=pairs[start:start+16];h=torch.stack([tensor(Image.open(p['patch']).convert('RGB')) for r,p in batch])
        c=torch.stack([tensor(Image.open(r['context']['image']).convert('RGB')) if r['context']['status']=='ready' else torch.zeros(3,128,128) for r,p in batch])
        face=torch.tensor([r['context']['faceCues'] for r,p in batch],dtype=torch.float32)
        with torch.inference_mode():_,v=torso(h,c,face)
        automatic.extend(v)
    outputs={name+':'+stage:predict(model,pairs,views) for name,model in models.items() for stage,views in [('oracleView',oracle),('automaticView',automatic)]}
    parity=0.
    with torch.inference_mode():
        for name in models:
            eager=staged.StagedHand(2 if name=='binary' else 3,False);eager.load_state_dict(torch.load(a.models/name/'weights.pt',weights_only=True));eager.eval()
            for start in range(0,len(pairs),16):
                batch=pairs[start:start+16];h=torch.stack([tensor(Image.open(p['patch']).convert('RGB')) for r,p in batch]);v=torch.stack(oracle[start:start+16]);g,s,c=eager(h,v)
                for j,(r,p) in enumerate(batch):
                    ref=outputs[name+':oracleView'][p['reviewId']]
                    for x,y in [(g[j],ref['grip'][:g.shape[1]]),(s[j],ref['surface']),(c[j],ref['contact'])]:parity=max(parity,float((x-torch.tensor(y)).abs().max()))
    assert parity<1e-5
    summary=defaultdict(Counter);surface=defaultdict(Counter);quality=defaultdict(Counter);orientation=defaultdict(Counter);seen=set();records=[];clip_inputs=defaultdict(lambda:defaultdict(list));clip_truth=defaultdict(set);origins=defaultdict(Counter)
    for row in data:
        frame=row['threewayFrameReview'];target=frame['grip'];eligible=frame['active'] and target in base.GRIPS
        if eligible:clip_truth[row['recordingId']].add(target)
        for patch in row['patches']:
            ref=patch.get('threewayReview');valid=ref is not None and ref['grip'] in base.GRIPS
            values={name:values.get(patch.get('reviewId')) for name,values in outputs.items()}
            t=row['pose']['timestamp'];ts=t['value']/t['timescale'];available=patch['status']=='patchReady'
            rec=dict(caseId=row['caseId'],recordingId=row['recordingId'],imageSHA256=row['imageSHA256'],timestamp=t,side=patch['side'],patchStatus=patch['status'],
                reviewId=patch.get('reviewId'),patch=patch.get('patch'),origin=patch.get('origin','SDK'),expected=target if eligible else None,
                validHandReference=valid,reference=ref,frameReview=frame,context=row['context'],results=values)
            records.append(rec)
            if eligible:
                for name,value in values.items():
                    g=value['grip'] if value else None;c=value['contact'] if value else None;has_view=not name.endswith('automaticView') or row['context']['status']=='ready'
                    for key in [name,name+':'+target]:metrics(summary[key],g,c,target,valid,has_view)
                    if valid and value:
                        for key in ['hand:'+name,'hand:'+name+':'+target]:metrics(summary[key],g,c,ref['grip'],True,has_view)
                if valid and available:
                    origins[patch.get('origin','SDK')][target+':valid']+=1
                    prediction=values['threeway:oracleView'];origins[patch.get('origin','SDK')][target+':correct']+=base.GRIPS[int(np.argmax(prediction['grip']))]==ref['grip']
            for name,value in values.items():
                if not value:continue
                accepted=max(value['grip'])>=.8 and value['contact'][1]>=.8
                if name.endswith('automaticView') and row['context']['status']!='ready':accepted=False
                if not eligible or not valid:quality[name]['nonReferenceCrops']+=1;quality[name]['acceptedNonReferenceCrops']+=accepted
                # No reference active-window/contact labels select clip inputs.
                if name.endswith('automaticView') and row['context']['status']!='ready':continue
                clip_inputs[name][row['recordingId']].append(dict(time=ts,grip=value['grip'],accepted=accepted))
            if not available:continue
            view_index=next(i for i,(r,p) in enumerate(pairs) if p is patch)
            fkey=(row['recordingId'],row['imageSHA256'])
            if eligible and frame['bodyEligible'] and row['context']['status']=='ready' and fkey not in seen:
                seen.add(fkey);predicted=base.VIEWS[int(automatic[view_index].argmax())];orientation[frame['view']]['correct' if predicted==frame['view'] else 'wrong']+=1
            value=values['threeway:oracleView'];pred=base.SURFACES[int(np.argmax(value['surface']))]
            surface[ref['surface']][pred]+=1
            if ref.get('qualityEligible',True):
                truth=ref['contact']=='closed';predicted=value['contact'][1]>=.8
                quality['contactThreshold']['truePositive' if truth and predicted else 'falseNegative' if truth else 'falsePositive' if predicted else 'trueNegative']+=1
    clips=defaultdict(Counter);clip_records=[]
    for source,truth in clip_truth.items():
        if len(truth)!=1:continue
        target=next(iter(truth))
        for name in outputs:
            observations=clip_inputs[name].get(source,[]);frames=defaultdict(list)
            for x in observations:frames[x['time']].append(x['grip'])
            raw=base.GRIPS[int(np.argmax(np.mean([np.mean(x,axis=0) for x in frames.values()],axis=0)))] if frames else 'unknown'
            accepted=defaultdict(set)
            for x in observations:
                if x['accepted']:accepted[base.GRIPS[int(np.argmax(x['grip']))]].add(x['time'])
            confident=next(iter(accepted)) if len(accepted)==1 and len(next(iter(accepted.values())))>=2 else 'unknown'
            for key in [name,name+':'+target]:
                clips[key]['videos']+=1;clips[key]['rawCorrect' if raw==target else 'rawUnknown' if raw=='unknown' else 'rawWrong']+=1
                clips[key]['acceptedCorrect' if confident==target else 'unknown' if confident=='unknown' else 'acceptedWrong']+=1
            clip_records.append(dict(recordingId=source,expected=target,model=name,raw=raw,accepted=confident,sourceFrames=len(frames)))
    report=dict(qualification='Video-group held-out research evaluation with two agent visual passes; oracle view is privileged diagnostic input, not app accuracy',
        modelSHA256={k:v['modelSHA256'] for k,v in receipts.items()},inputSHA256=base.sha(a.input),reviewSHA256=base.sha(a.review),reserveSHA256=base.sha(a.reserve),freezeSHA256=base.sha(a.freeze),scriptSHA256=base.sha(__file__),
        population=dict(reservedVideos=len(reserve['sources']),sampledVideos=len({r['recordingId'] for r in data}),sampledFrames=len(data),automaticHandCrops=len(pairs),binaryOrNeutralReferenceVideos=len(clip_truth)),
        threshold=.8,contactThreshold=.8,torchScriptEagerMaximumDifference=parity,summary=summary,surfaceConfusion=surface,quality=quality,orientation=orientation,origins=origins,
        clipSummary=clips,clipPolicy='Mean hand scores once per source frame then mean unique source frames for raw choice. Accepted label requires agreeing predictions at >=.8 grip and >=.8 contact on two distinct source times; any conflicting accepted label remains unknown. No reference window/contact label gates clip inference.')
    (a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');(a.output/'predictions.jsonl').write_text(''.join(json.dumps(x)+'\n' for x in records));(a.output/'clip-predictions.jsonl').write_text(''.join(json.dumps(x)+'\n' for x in clip_records))
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
