#!/usr/bin/env python3
"""Frozen model, complete-pipeline and oracle-view comparisons on reserved videos.

Reference reviews never suppress inference. Invalid subject/contact crops count
as pipeline failures when accepted, while hand-classification accuracy is also
reported separately on valid hand images. Oracle view is diagnostic only.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse
from collections import Counter,defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import numpy as np
import torch


def module(name,file):
    s=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file));m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m


learning=module('expanded','train-expanded-grip.py');base=learning.base;LABELS=base.LABELS;VIEWS=learning.components.VIEWS


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def key(r,p):return r['recordingId'],r['imageSHA256'],p['side']


def add(m,score,target,valid=True):
    m['expectedHands']+=1
    if score is None:m['missingInput']+=1;m['unknown']+=1;return
    m['availablePatches']+=1
    if not valid:m['invalidTargetCrop']+=1
    if abs(score[0]-score[1])<1e-7:m['tied']+=1;m['unknown']+=1;return
    correct=valid and LABELS[int(np.argmax(score))]==target
    m['rawCorrect' if correct else 'rawWrong']+=1
    if max(score)>=.8:m['acceptedCorrect' if correct else 'acceptedWrong']+=1
    else:m['unknown']+=1;m['lowScore']+=1


def predict(root,rows):
    receipt=json.loads((root/'training-receipt.json').read_text());assert sha(root/'GripContextClassifier.pt')==receipt['modelSHA256']
    model=torch.jit.load(str(root/'GripContextClassifier.pt')).eval();out={};pairs=[(r,p) for r in rows for p in r['patches'] if p['status']=='patchReady']
    with torch.inference_mode():
        for start in range(0,len(pairs),16):
            batch=pairs[start:start+16];data=[base.images(x) for x in batch]
            scores,views=model(*(torch.stack([d[i] for d in data]) for i in range(3)))
            for i,(r,p) in enumerate(batch):out[key(r,p)]=dict(scores=scores[i].tolist(),view=views[i].tolist())
    return out,receipt


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['new_manifest','legacy_manifest','review','model','previous','reserve','output']:p.add_argument(name,type=Path)
    a=p.parse_args();assert not (a.output/'report.json').exists();a.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    rows=base.load(a.new_manifest);legacy=base.load(a.legacy_manifest);review=json.loads(a.review.read_text());reserve=json.loads(a.reserve.read_text())
    assert all(r['annotation']['split']=='test' for r in rows+legacy)
    assert review['outputSHA256']==sha(a.new_manifest)
    refs={(r['recordingId'],r['reviewId']):r for r in review['patches']};frames={(r['recordingId'],r['imageSHA256']):r for r in review['frames']}
    for r in rows:
        assert r['frameReview']==frames[(r['recordingId'],r['imageSHA256'])]
        for patch in r['patches']:
            assert patch.get('origin')!='manualReviewedTrainingCrop','Manual test crop forbidden'
            if patch['status']=='patchReady':assert patch['manualReview']==refs[(r['recordingId'],patch['reviewId'])]
    fresh={s['sha256'] for s in reserve['sources']};groups={s['sourceGroup'] for s in reserve['sources']}
    new,receipt=predict(a.model,rows);same,previous_receipt=predict(a.previous,rows);old,_=predict(a.previous,legacy)
    assert receipt['testReserveSHA256']==sha(a.reserve)
    assert {r['recordingId'] for r in rows}<=fresh
    for rr in [receipt,previous_receipt]:
        assert not fresh & set(sum(rr['sources'].values(),[]))
        assert not groups & set(sum(rr['sourceGroups'].values(),[]))
    legacy_lookup={key(r,p):(r,p) for r in legacy for p in r['patches']}
    eager={}
    for name,root in [('new',a.model),('previous',a.previous)]:
        model=learning.ReviewedGrip(False);model.load_state_dict(torch.load(root/'weights.pt',weights_only=True));eager[name]=model.eval()
    oracle={name:{} for name in eager};maximum_gap=0.
    pairs=[(r,p) for r in rows for p in r['patches'] if p['status']=='patchReady' and p['manualReview']['grip'] in LABELS]
    with torch.inference_mode():
        for start in range(0,len(pairs),16):
            batch=pairs[start:start+16];data=[base.images(x) for x in batch]
            h,c,f=[torch.stack([d[i] for d in data]) for i in range(3)]
            v=torch.nn.functional.one_hot(torch.tensor([VIEWS.index(p['manualReview']['view']) for r,p in batch]),3).float()
            for name,model in eager.items():
                scores=(model.grip(model.grip_features(model.hand.embed(h),v))/model.temperature).softmax(1)
                actual,views=model(h,c,f);frozen=new if name=='new' else same
                for i,(r,p) in enumerate(batch):
                    k=key(r,p);maximum_gap=max(maximum_gap,float((actual[i]-torch.tensor(frozen[k]['scores'])).abs().max()),float((views[i]-torch.tensor(frozen[k]['view'])).abs().max()))
                    oracle[name][k]=scores[i].tolist()
    assert maximum_gap<1e-5
    summary=defaultdict(Counter);cases=defaultdict(Counter);orientation=defaultdict(Counter);quality=defaultdict(Counter);predictions=[];seen=set()
    for r in rows:
        frame=r['frameReview'];eligible_frame=frame['gripActive'] and frame['grip'] in LABELS
        for patch in r['patches']:
            k=key(r,patch);target=frame['grip'];manual=patch.get('manualReview');valid=manual is not None and manual['grip'] in LABELS
            result=dict(new=new.get(k),previousSameDetector=same.get(k),previousPipeline=old.get(k),oracleNew=oracle['new'].get(k),oraclePrevious=oracle['previous'].get(k))
            rec=dict(caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],side=patch['side'],
                expected=target if eligible_frame else None,referenceEligible=eligible_frame,handReferenceEligible=valid,
                patchStatus=patch['status'],patch=patch.get('patch'),context=r['context'],frameReview=frame,manualReview=manual,results=result)
            predictions.append(rec)
            if eligible_frame:
                for name,scores in [('pipeline:new',result['new']),('pipeline:previous',result['previousPipeline']),('sameDetector:previous',result['previousSameDetector'])]:
                    probability=scores['scores'] if scores else None
                    # On shared legacy inputs the original ownership is the same;
                    # reviews describe target identity, not model predictions.
                    is_valid=valid if probability is not None else True
                    for label in [name,name+':'+target]:add(summary[label],probability,target,is_valid)
                    add(cases[name+':'+r['caseId']],probability,target,is_valid)
                if valid and k in new:
                    for name,probability in [('new',new[k]['scores']),('previous',same[k]['scores'])]:
                        add(summary['hand:'+name+':'+target],probability,target)
                        add(summary['hand:'+name],probability,target)
                    for name in oracle:
                        add(summary['oracleView:'+name+':'+target],oracle[name][k],target)
                        add(summary['oracleView:'+name],oracle[name][k],target)
                    if k in old:
                        for name,probability in [('new',new[k]['scores']),('previous',old[k]['scores'])]:add(summary['common:'+name+':'+target],probability,target)
            for name,result_key in [('new','new'),('previous','previousSameDetector')]:
                value=result[result_key]
                if value and (not eligible_frame or not valid):
                    quality[name]['nonReferenceCrop']+=1;quality[name]['acceptedNonReferenceCrop']+=max(value['scores'])>=.8
                fkey=(name,r['recordingId'],r['imageSHA256'])
                if eligible_frame and value and frame['bodyEligible'] and r['context']['status']=='ready' and fkey not in seen:
                    seen.add(fkey);v=frame['view'];orientation[name+':'+v]['correct' if VIEWS[int(np.argmax(value['view']))]==v else 'wrong']+=1
    used_videos={r['recordingId'] for r in rows if r['frameReview']['gripActive'] and r['frameReview']['grip'] in LABELS}
    hand_videos={r['recordingId'] for r,p in pairs if r['frameReview']['gripActive']}
    report=dict(qualification='First evaluation on reserved videos after model and detector freeze; manually reviewed agent references, not independent gold labels; oracle orientation is diagnostic only',
        modelSHA256=receipt['modelSHA256'],previousSHA256=previous_receipt['modelSHA256'],reserveSHA256=sha(a.reserve),reviewSHA256=sha(a.review),
        newManifestSHA256=sha(a.new_manifest),legacyManifestSHA256=sha(a.legacy_manifest),scriptSHA256=sha(__file__),threshold=.8,
        torchScriptEagerMaximumDifference=maximum_gap,population=dict(reservedVideos=len(reserve['sources']),sourceReviewedVideos=len({r['recordingId'] for r in rows}),
        binaryReferenceVideos=len(used_videos),videosWithValidHandCrops=len(hand_videos),zeroValidHandCropVideos=len(used_videos-hand_videos),sampledFrames=len(rows)),
        summary=summary,orientation=orientation,quality=quality,cases=cases)
    (a.output/'predictions.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in predictions));(a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:report[k] for k in ['population','summary','orientation','quality','torchScriptEagerMaximumDifference']},indent=2))


if __name__=='__main__':main()
