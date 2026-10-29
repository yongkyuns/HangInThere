#!/usr/bin/env python3
"""Direct grip learning from manually reviewed RGB patches and frozen torso view.

No derived palm/dorsum training targets. No grip gradient enters orientation.
Face evidence can support an agreeing front-torso prediction, never reverse it.
Only train/validation manifests enter this executable.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse
from collections import Counter,defaultdict
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import random
import time

import numpy as np
from PIL import Image
import torch
from torch import nn
from torchvision import transforms

SPEC=importlib.util.spec_from_file_location('components',Path(__file__).with_name('train-grip-components.py'))
components=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(components)
base=components.base
LABELS=base.LABELS


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def supported_view(body,cues):
    front=torch.zeros_like(body);front[:,0]=1
    # Positive face evidence is only allowed to agree with torso direction.
    weight=.1*cues[:,0:1]*cues[:,5:6]*(body.argmax(1,keepdim=True)==0)
    return body*(1-weight)+front*weight


class ReviewedGrip(nn.Module):
    def __init__(self,pretrained=True):
        super().__init__()
        self.torso=components.ImageHead(3,pretrained)
        self.hand=components.ImageHead(2,pretrained)
        self.hand.head=nn.Identity()
        self.grip=nn.Sequential(nn.Linear(576*3,128),nn.Hardswish(),nn.Dropout(.3),nn.Linear(128,2))
        self.register_buffer('temperature',torch.ones(1))

    def orientation(self,context,cues):
        body=self.torso(context).softmax(1)
        view=supported_view(body,cues)
        return view*cues[:,5:6]+(1-cues[:,5:6])/3

    def grip_features(self,hand_features,view):
        return torch.cat([hand_features*view[:,i:i+1] for i in range(3)],1)

    def grip_logits(self,hand,context,cues):
        # Frozen orientation is a read-only conditioning input.
        view=self.orientation(context,cues).detach()
        return self.grip(self.grip_features(self.hand.embed(hand),view)),view

    def forward(self,hand,context,cues):
        logits,view=self.grip_logits(hand,context,cues)
        scores=(logits/self.temperature).softmax(1)
        mask=cues[:,5:6]
        return scores*mask+.5*(1-mask),view


def load(path):
    rows=base.load(path)
    for row in rows:
        assert row.get('manualViewReview'),'Torso views must be manually reviewed'
        # Original recording annotation stays immutable on disk. Per-frame view
        # review handles real camera cuts and never changes source roles.
        row['annotation']=dict(row['annotation'],viewpoint=row['reviewedViewpoint'])
        for p in row['patches']:
            if p['status']!='patchReady':continue
            if p.get('origin')=='manualReviewedTrainingCrop':
                assert row['annotation']['split']=='train','Manual RGB crop cannot enter held-out inputs'
                assert p.get('manualAcceptanceRequired') is False,'Unreviewed crop proposal cannot enter fitting'
            m=p['manualReview']
            for k in ['caseId','recordingId','imageSHA256']:assert m[k]==row[k]
            for k in ['side','box','patchSHA256']:assert m[k]==p[k]
            assert m['timestamp']==row['pose']['timestamp'] and m['split']==row['annotation']['split']
    return rows


def hand_examples(rows):
    return [(r,p) for r,p in base.available(rows) if p.get('manualReview',{}).get('grip') in LABELS
            and p['manualReview']['view'] in components.VIEWS]


def reviewed_views(pairs):
    return nn.functional.one_hot(torch.tensor([components.VIEWS.index(p['manualReview']['view']) for r,p in pairs]),3).float()


def items(pairs):
    return [dict(target=LABELS.index(p['manualReview']['grip']),row=r) for r,p in pairs]


def validation(model,pairs,oracle=False):
    data=[base.images(p) for p in pairs]
    logits=[];model.eval()
    with torch.inference_mode():
        for start in range(0,len(data),16):
            batch=data[start:start+16]
            h,c,f=[torch.stack([x[i] for x in batch]) for i in range(3)]
            if oracle:
                v=reviewed_views(pairs[start:start+16])
                logits.append(model.grip(model.grip_features(model.hand.embed(h),v)))
            else:logits.append(model.grip_logits(h,c,f)[0]*f[:,5:6])
    return torch.cat(logits)


def fit_grip(model,rows,args):
    pairs=hand_examples(rows)
    train=[x for x in pairs if x[0]['annotation']['split']=='train']
    val=[x for x in pairs if x[0]['annotation']['split']=='validation']
    assert set(p['manualReview']['grip'] for r,p in train)==set(LABELS)
    assert set(p['manualReview']['grip'] for r,p in val)==set(LABELS)
    training_items=items(train);validation_items=items(val)
    y=torch.tensor([x['target'] for x in training_items]);sampling=components.sampling_weights(training_items)
    cached=[base.images(x) for x in train]
    aug=transforms.Compose([transforms.RandomAffine(10,translate=(.04,.04),scale=(.9,1.1)),transforms.ColorJitter(.15,.15,.12,.02)])
    original={k:p.detach().clone() for k,p in model.hand.features.named_parameters()}
    for p in model.torso.parameters():p.requires_grad=False
    model.eval()
    with torch.no_grad():
        # Reviewed orientation supervises the conditional hand component. This
        # isolates its visual learning from mistakes in the independently fitted
        # torso head. Inference still uses predicted orientation only.
        views=reviewed_views(train)*.9+.1/3
        # Four fixed appearance augmentations per reviewed training crop.
        embeddings=[]
        for repeat in range(4):
            images=torch.stack([x[0] if repeat==0 else aug(x[0]) for x in cached])
            embeddings.append(torch.cat([model.hand.embed(images[i:i+16]) for i in range(0,len(images),16)]))
        embeddings=torch.stack(embeddings)
    optimizer=torch.optim.AdamW(model.grip.parameters(),lr=.001,weight_decay=.03)
    best=float('inf');best_state=None;history=[];phase_best=0;best_epoch=None
    for epoch in range(args.warmup+args.epochs):
        start=time.perf_counter();fine=epoch>=args.warmup
        if epoch==args.warmup:
            model.load_state_dict(best_state)
            optimizer=torch.optim.AdamW([{'params':model.hand.features.parameters(),'lr':.00005},
                                         {'params':model.grip.parameters(),'lr':.0003}],weight_decay=.03)
            phase_best=epoch
        model.eval();model.grip.train()
        if fine:
            model.hand.train()
            for layer in model.hand.modules():
                if isinstance(layer,nn.BatchNorm2d):layer.eval()
        indices=torch.multinomial(sampling,len(train),replacement=True).tolist();losses=[]
        for offset in range(0,len(indices),16):
            ids=indices[offset:offset+16];optimizer.zero_grad()
            if fine:
                h=torch.stack([aug(cached[i][0]) for i in ids]);features=model.hand.embed(h)
            else:features=torch.stack([embeddings[random.randrange(4),i] for i in ids])
            v=views[ids].clone();drop=torch.rand(len(ids))<.15;v[drop]=1/3
            logits=model.grip(model.grip_features(features,v))
            loss=nn.functional.cross_entropy(logits,y[ids],label_smoothing=.05)
            loss.backward();nn.utils.clip_grad_norm_(list(model.hand.parameters())+list(model.grip.parameters()),5)
            optimizer.step();losses.append(float(loss.detach()))
        logits=validation(model,val,oracle=True);nll=components.balanced_nll(logits,validation_items)
        if nll<best:
            best=nll;best_state=copy.deepcopy(model.state_dict());best_epoch=epoch+1;phase_best=epoch
            torch.save(best_state,args.output/'grip-weights.pt')
        entry=dict(epoch=epoch+1,phase='fineTune' if fine else 'head',trainingNLL=float(np.mean(losses)),validationSourceClassBalancedNLL=nll,seconds=time.perf_counter()-start)
        history.append(entry);(args.output/'grip-history.json').write_text(json.dumps(history,indent=2)+'\n');print(json.dumps(entry),flush=True)
        if fine and epoch-phase_best>=4:break
    model.load_state_dict(best_state);model.eval();logits=validation(model,val)
    oracle_logits=validation(model,val,oracle=True)
    candidates=[1.,1.25,1.5,2.,3.,4.,6.,8.]
    losses={str(t):components.balanced_nll(logits/t,validation_items) for t in candidates}
    temperature=min(candidates,key=lambda t:losses[str(t)]);model.temperature.fill_(temperature)
    accuracy=defaultdict(Counter);oracle_accuracy=defaultdict(Counter)
    for (_,patch),pred in zip(val,logits.argmax(1).tolist()):
        target=patch['manualReview']['grip'];accuracy[target]['correct' if LABELS[pred]==target else 'wrong']+=1
    for (_,patch),pred in zip(val,oracle_logits.argmax(1).tolist()):
        target=patch['manualReview']['grip'];oracle_accuracy[target]['correct' if LABELS[pred]==target else 'wrong']+=1
    return dict(bestEpoch=best_epoch,bestValidationNLL=best,epochsRun=len(history),temperature=temperature,temperatureValidationNLL=losses,
                checkpointSelection='minimum conditional hand NLL with reviewed validation orientation, equal-class/equal-video; no test inputs',
                trainingPatches=len(train),validationPatches=len(val),trainingClasses=dict(Counter(p['manualReview']['grip'] for r,p in train)),
                validationClasses=dict(Counter(p['manualReview']['grip'] for r,p in val)),validationRawAccuracy=accuracy,validationOracleViewAccuracy=oracle_accuracy,
                changedHandFeatureTensors=sum(not torch.equal(p.detach(),original[k]) for k,p in model.hand.features.named_parameters()),
                trainingPatchSHA256=sorted(p['patchSHA256'] for r,p in train))


def train(args):
    assert not (args.output/'training-receipt.json').exists(),'Use a new output directory'
    rows=load(args.input)
    assert all(r['annotation']['split'] in ['train','validation'] for r in rows),'Test manifest cannot enter fitting'
    review=json.loads(args.review.read_text());labels={(p['recordingId'],p['reviewId']):p for p in review['patches']}
    assert review['outputSHA256']==sha(args.input),'Reviewed fitting manifest changed'
    for row in rows:
        for p in row['patches']:
            if 'manualReview' in p:assert p['manualReview']==labels[(row['recordingId'],p['reviewId'])],'Unbound manual label'
    reserve=json.loads(args.test_reserve.read_text());assert reserve['role']=='test'
    assert not {s['sha256'] for s in reserve['sources']} & {r['recordingId'] for r in rows}
    assert not {s['sourceGroup'] for s in reserve['sources']} & {r['annotation'].get('sourceGroup') for r in rows},'Reserved test group leaked into fitting'
    torch.manual_seed(31);np.random.seed(31);random.seed(31)
    model=ReviewedGrip()
    body_rows=[r for r in rows if r.get('bodyReferenceEligible',False)]
    body=components.fit('body',model.torso,body_rows,args)
    before={k:p.detach().clone() for k,p in model.torso.named_parameters()}
    grip=fit_grip(model,rows,args)
    assert all(torch.equal(p.detach(),before[k]) for k,p in model.torso.named_parameters()),'Grip loss changed torso weights'
    model.eval();torch.save(model.state_dict(),args.output/'weights.pt')
    traced=torch.jit.trace(model,(torch.zeros(1,3,128,128),torch.zeros(1,3,128,128),torch.zeros(1,6)))
    traced.save(str(args.output/'GripContextClassifier.pt'))
    receipt=dict(variant='expandedReviewedGrip',seed=31,body=body,grip=grip,threshold=.8,
                 inputSHA256=sha(args.input),scriptSHA256=sha(__file__),componentTrainerSHA256=sha(components.__file__),
                 fittingReviewSHA256=sha(args.review),testReserveSHA256=sha(args.test_reserve),
                 contextInput='selected-athlete torso-only crop, unmirrored RGB 128',
                 manualTargets='direct reviewed overhand/underhand labels; no derived palm/dorsum targets',
                 orientationPolicy='torso direction; bounded positive face support only when torso already predicts front; head turns cannot reverse direction',
                 missingContext='uniform grip and orientation; not a forced label',
                 parameters=sum(p.numel() for p in model.parameters()),
                 sources={s:sorted({r['recordingId'] for r in rows if r['annotation']['split']==s}) for s in ['train','validation']},
                 sourceGroups={s:sorted({r['annotation'].get('sourceGroup') for r in rows if r['annotation']['split']==s and r['annotation'].get('sourceGroup')}) for s in ['train','validation']},
                 modelSHA256=sha(args.output/'GripContextClassifier.pt'))
    (args.output/'training-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    sources=args.output/'sources';sources.mkdir(exist_ok=True)
    for path in [Path(__file__),Path(components.__file__),Path(base.__file__),Path(base.base.__file__)]:
        (sources/path.name).write_bytes(path.read_bytes())
    print(json.dumps({k:receipt[k] for k in ['modelSHA256','body','grip','parameters']}),flush=True)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--review',type=Path,required=True);p.add_argument('--test-reserve',type=Path,required=True)
    p.add_argument('--warmup',type=int,default=6);p.add_argument('--epochs',type=int,default=20)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2);train(args)


if __name__=='__main__':main()
