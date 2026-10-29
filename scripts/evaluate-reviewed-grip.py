#!/usr/bin/env python3
"""Compare frozen models on identical reviewed source/time/side observations.

The previous model receives its original tight hand and head inputs. The revised
model receives the expanded original-pixel crop and torso-only context. Reference
quality exclusions never suppress model inputs. No fitting happens here.
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
from PIL import Image
import torch


def module(name,file):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file))
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


learning=module('reviewed','train-reviewed-grip.py')
head=module('head','prepare-grip-head-context.py')
base=learning.base
LABELS=base.LABELS
VIEWS=['front','rear','oblique']


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def metric(m,scores,target):
    m['expectedHands']+=1
    if scores is None:m['missingPatch']+=1;m['unknown']+=1;return
    m['availablePatches']+=1
    correct=LABELS[int(np.argmax(scores))]==target
    m['rawCorrect' if correct else 'rawWrong']+=1
    if max(scores)>=.8:m['acceptedCorrect' if correct else 'acceptedWrong']+=1
    else:m['unknown']+=1;m['lowScore']+=1


def baseline_images(row,patch,output):
    original=patch['originalPatch'];assert sha(original['patch'])==original['patchSHA256']
    h=base.transforms.ToTensor()(Image.open(original['patch']).convert('RGB'))
    context=row['originalContext']
    if context['status']=='ready':
        source=Path(row['framesDirectory'])/row['image'];assert sha(source)==row['imageSHA256']
        box=head.head_box(row);target=output/(row['imageSHA256']+'-head.png')
        Image.open(source).convert('RGB').crop(box).resize((128,128),Image.Resampling.BILINEAR).save(target)
        c=base.transforms.ToTensor()(Image.open(target).convert('RGB'))
    else:c=torch.zeros(3,128,128)
    return h,c,torch.tensor(context['faceCues'],dtype=torch.float32)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('manifest',type=Path)
    p.add_argument('review',type=Path);p.add_argument('model',type=Path);p.add_argument('baseline',type=Path)
    p.add_argument('reserve',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not (a.output/'report.json').exists(),'Preserve completed evaluation'
    a.output.mkdir(parents=True,exist_ok=True);(a.output/'baseline-heads').mkdir(exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    receipt=json.loads((a.model/'training-receipt.json').read_text())
    assert receipt['testReserveSHA256']==sha(a.reserve)
    assert receipt['modelSHA256']==sha(a.model/'GripContextClassifier.pt')
    reserve=json.loads(a.reserve.read_text());review=json.loads(a.review.read_text())
    rows=base.load(a.manifest);assert all(r['annotation']['split']=='test' for r in rows)
    test_sources={s['sha256'] for s in reserve['sources']}
    assert {r['recordingId'] for r in rows}<=test_sources
    assert not test_sources & set(sum(receipt['sources'].values(),[]))
    assert not {s['sourceGroup'] for s in reserve['sources']} & set(sum(receipt['sourceGroups'].values(),[]))
    reviewed={r['reviewId']:r for r in review['patches']}
    for r in rows:
        for patch in r['patches']:
            if 'manualReview' in patch:assert patch['manualReview']==reviewed[patch['reviewId']]
    models={name:torch.jit.load(str(root/'GripContextClassifier.pt')).eval() for name,root in [('new',a.model),('baseline',a.baseline)]}
    previous=json.loads((a.baseline/'training-receipt.json').read_text())
    assert previous['modelSHA256']==sha(a.baseline/'GripContextClassifier.pt')
    assert not test_sources & set(sum(previous['sources'].values(),[]))
    pairs=[(r,p) for r in rows for p in r['patches'] if p['status']=='patchReady']
    lookup={}
    with torch.inference_mode():
        for start in range(0,len(pairs),16):
            batch=pairs[start:start+16];result={}
            for name,model in models.items():
                data=[base.images(x) if name=='new' else baseline_images(*x,a.output/'baseline-heads') for x in batch]
                scores,views=model(*(torch.stack([d[i] for d in data]) for i in range(3)))
                result[name]=(scores.tolist(),views.tolist())
            for i,(r,patch) in enumerate(batch):
                lookup[(r['recordingId'],r['imageSHA256'],patch['side'])]={n:dict(scores=s[i],view=v[i]) for n,(s,v) in result.items()}
    summary=defaultdict(Counter);cases=defaultdict(Counter);quality=defaultdict(Counter)
    orientation=defaultdict(Counter);predictions=[];seen=set()
    for r in rows:
        window=base.base.in_window(r)
        for patch in r['patches']:
            result=lookup.get((r['recordingId'],r['imageSHA256'],patch['side']))
            manual=patch.get('manualReview');target=manual['grip'] if manual else r['annotation']['label']
            eligible=window and target in LABELS
            rec=dict(caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],
                     timestamp=r['pose']['timestamp'],side=patch['side'],status=patch['status'],
                     expected=target if eligible else None,inReviewedWindow=window,referenceEligible=eligible,
                     patch=patch.get('patch'),manualReview=manual,context=r['context'],results=result)
            predictions.append(rec)
            for name in models:
                score=result[name]['scores'] if result else None
                if eligible:
                    for key in [name,name+':'+target]:metric(summary[key],score,target)
                    metric(cases[name+':'+r['caseId']],score,target)
                else:
                    quality[name]['excludedReference']+=1
                    quality[name]['excludedWithPatch']+=score is not None
                    quality[name]['acceptedOnExcludedReference']+=score is not None and max(score)>=.8
                frame=(name,r['recordingId'],r['imageSHA256'])
                if result and window and frame not in seen and r['context']['status']=='ready':
                    seen.add(frame);view=(manual or {}).get('view',r['annotation']['viewpoint'])
                    orientation[name+':'+view]['correct' if VIEWS[int(np.argmax(result[name]['view']))]==view else 'wrong']+=1
    report=dict(qualification='First scoring of reserved, video-disjoint sources after model freeze; agent manual references, not independent gold labels',
                modelSHA256=receipt['modelSHA256'],baselineSHA256=previous['modelSHA256'],threshold=.8,
                manifestSHA256=sha(a.manifest),reviewSHA256=sha(a.review),reserveSHA256=sha(a.reserve),scriptSHA256=sha(__file__),
                population=dict(reservedVideos=len(reserve['sources']),eligibleSourceVideos=len({r['recordingId'] for r in rows}),
                                sampledFrames=len(rows),manuallyVerifiedPatches=sum(p.get('manualReview',{}).get('grip') in LABELS for r,p in pairs),
                                zeroAvailablePatchVideos=sorted({r['caseId'] for r in rows}-{r['caseId'] for r,p in pairs})),
                summary=summary,cases=cases,orientation=orientation,quality=quality)
    (a.output/'predictions.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in predictions))
    (a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
