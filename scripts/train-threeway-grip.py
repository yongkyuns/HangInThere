#!/usr/bin/env python3
"""Direct hand RGB learning with independent surface/contact supervision.

The oracle-view stage learns grip with reviewed orientation. A binary control
uses exactly the same images/auxiliary labels but masks neutral grip loss.
No test image or test label enters this executable.
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
from torchvision import models,transforms

GRIPS=['overhand','underhand','neutral']
SURFACES=['palm','dorsum','side','unclear']
VIEWS=['front','rear','oblique']


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


class HandModel(nn.Module):
    def __init__(self,classes=3,pretrained=True):
        super().__init__()
        self.features=models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.IMAGENET1K_V1 if pretrained else None).features
        self.pool=nn.AdaptiveAvgPool2d(1)
        self.grip=nn.Sequential(nn.Linear(576*3,128),nn.Hardswish(),nn.Dropout(.25),nn.Linear(128,classes))
        self.surface=nn.Sequential(nn.Linear(576,64),nn.Hardswish(),nn.Dropout(.2),nn.Linear(64,4))
        self.contact=nn.Sequential(nn.Linear(576,64),nn.Hardswish(),nn.Dropout(.2),nn.Linear(64,2))
        self.register_buffer('mean',torch.tensor([.485,.456,.406]).reshape(1,3,1,1))
        self.register_buffer('std',torch.tensor([.229,.224,.225]).reshape(1,3,1,1))
        self.register_buffer('temperature',torch.ones(3))

    def embed(self,image):return self.pool(self.features((image-self.mean)/self.std)).flatten(1)

    def heads(self,embedding,view):
        conditional=torch.cat([embedding*view[:,i:i+1] for i in range(3)],1)
        return self.grip(conditional),self.surface(embedding),self.contact(embedding)

    def forward(self,image,view):
        g,s,c=self.heads(self.embed(image),view)
        return (g/self.temperature[0]).softmax(1),(s/self.temperature[1]).softmax(1),(c/self.temperature[2]).softmax(1)


def load(path,allow_test=False):
    data=[json.loads(l) for l in path.read_text().splitlines()]
    for r in data:
        assert allow_test or r['annotation']['split'] in ['train','validation'],'Test source cannot enter fitting'
        assert r['recordingId']==r['annotation']['recordingId']
        assert sha(Path(r['framesDirectory'])/r['image'])==r['imageSHA256']
        for p in r['patches']:
            if p['status']!='patchReady':continue
            review=p['threewayReview']
            for k in ['recordingId','imageSHA256']:assert review[k]==r[k]
            for k in ['patchSHA256','box','side','reviewId']:assert review[k]==p[k]
            assert review['split']==r['annotation']['split'] and review['timestamp']==r['pose']['timestamp']
            assert sha(p['patch'])==p['patchSHA256']
            assert review['surface'] in SURFACES and review['contact'] in ['closed','open','invalid']
            assert not review['conflict'] or review['adjudication']
            if p.get('origin') in ['manualReviewedTrainingCrop','manualTrainingRegion']:
                assert r['annotation']['split']=='train','Manual held-out crop forbidden'
    return data


def pairs(data):return [(r,p) for r in data for p in r['patches'] if p['status']=='patchReady']


def targets(items,classes,training=False):
    g=[];s=[];c=[]
    for r,p in items:
        ref=p['threewayReview'];label=ref['grip']
        good=label in GRIPS[:classes] and (not training or ref['surface']!='unclear')
        g.append(GRIPS.index(label) if good else -100)
        s.append(SURFACES.index(ref['surface']))
        c.append(int(ref['contact']=='closed') if ref.get('qualityEligible',True) else -100)
    return [torch.tensor(x) for x in [g,s,c]]


def weights(items,y):
    counts=Counter((int(label),r['recordingId']) for (r,p),label in zip(items,y) if label>=0)
    groups=defaultdict(set)
    for (r,p),label in zip(items,y):
        if label>=0:groups[int(label)].add(r['recordingId'])
    result=torch.tensor([0. if label<0 else 1/(counts[int(label),r['recordingId']]*len(groups[int(label)])) for (r,p),label in zip(items,y)])
    assert result.sum()>0
    return result/result.sum()


def nll(logits,y,w):
    return float((nn.functional.cross_entropy(logits,y,ignore_index=-100,reduction='none')*w).sum())


def data(items):
    tensor=transforms.ToTensor()
    h=torch.stack([tensor(Image.open(p['patch']).convert('RGB')) for r,p in items])
    view=nn.functional.one_hot(torch.tensor([VIEWS.index(p['threewayReview']['view']) for r,p in items]),3).float()
    return h,view


def infer(model,h,v,logits=False):
    result=[[],[],[]]
    with torch.inference_mode():
        for start in range(0,len(h),16):
            values=model.heads(model.embed(h[start:start+16]),v[start:start+16]) if logits else model(h[start:start+16],v[start:start+16])
            for dst,x in zip(result,values):dst.append(x)
    return [torch.cat(x) for x in result]


def train(a):
    assert not (a.output/'training-receipt.json').exists()
    a.output.mkdir(parents=True,exist_ok=True)
    allrows=load(a.input);review=json.loads(a.review.read_text())
    assert review['outputSHA256']==sha(a.input)
    refs={(r['recordingId'],r['reviewId']):r for r in review['patches']}
    for r,p in pairs(allrows):assert p['threewayReview']==refs[r['recordingId'],p['reviewId']]
    reserve=json.loads(a.test_reserve.read_text());test_hashes={x['sha256'] for x in reserve['sources']};test_groups={x['sourceGroup'] for x in reserve['sources']}
    assert not test_hashes & {r['recordingId'] for r in allrows}
    assert not test_groups & {r['annotation'].get('sourceGroup') for r in allrows}
    source_roles=defaultdict(set)
    for r in allrows:source_roles[r['annotation'].get('sourceGroup') or ('recording:'+r['recordingId'])].add(r['annotation']['split'])
    assert all(len(v)==1 for v in source_roles.values()),'Video group crosses fitting roles'
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    torch.manual_seed(3103);np.random.seed(3103);random.seed(3103)
    items=pairs(allrows);training=[x for x in items if x[0]['annotation']['split']=='train'];validation=[x for x in items if x[0]['annotation']['split']=='validation']
    classes=2 if a.variant=='binary' else 3
    h,v=data(training);vh,vv=data(validation);ys=targets(training,classes,True);vys=targets(validation,classes)
    for label in range(classes):assert (ys[0]==label).any() and (vys[0]==label).any(),'Every grip needs train/validation videos'
    ws=[weights(training,y) for y in ys];vws=[weights(validation,y) for y in vys]
    sampler=ws[0]*.7+(ws[1]+ws[2])*.15
    model=HandModel(classes).eval();original={k:p.detach().clone() for k,p in model.features.named_parameters()}
    optimizer=torch.optim.AdamW(list(model.grip.parameters())+list(model.surface.parameters())+list(model.contact.parameters()),lr=.001,weight_decay=.02)
    aug=transforms.Compose([transforms.RandomAffine(7,translate=(.02,.02),scale=(.95,1.05)),transforms.ColorJitter(.12,.12,.08,.01)])
    best=float('inf');best_state=None;best_epoch=0;phase_best=0;history=[]
    for epoch in range(a.warmup+a.epochs):
        started=time.perf_counter();fine=epoch>=a.warmup
        if epoch==a.warmup:
            model.load_state_dict(best_state)
            optimizer=torch.optim.AdamW([{'params':model.features.parameters(),'lr':.00003},
                {'params':list(model.grip.parameters())+list(model.surface.parameters())+list(model.contact.parameters()),'lr':.0003}],weight_decay=.02)
            phase_best=epoch
        for p in model.features.parameters():p.requires_grad=fine
        model.train()
        for layer in model.modules():
            if isinstance(layer,nn.BatchNorm2d):layer.eval()
        indices=torch.multinomial(sampler,len(training),replacement=True).tolist();losses=[]
        for offset in range(0,len(indices),16):
            ids=indices[offset:offset+16];optimizer.zero_grad();batch=torch.stack([aug(h[i]) for i in ids])
            embedding=model.embed(batch);view=v[ids]*.95+.05/3
            logits=model.heads(embedding,view)
            loss=embedding.sum()*0
            for j,(output,y) in enumerate(zip(logits,ys)):
                valid=y[ids]>=0
                if valid.any():loss=loss+[1.,.4,.2][j]*nn.functional.cross_entropy(output[valid],y[ids][valid],label_smoothing=.02)
            loss.backward();nn.utils.clip_grad_norm_(model.parameters(),5);optimizer.step();losses.append(float(loss.detach()))
        model.eval();val=infer(model,vh,vv,True);parts=[nll(x,y,w) for x,y,w in zip(val,vys,vws)]
        criterion=parts[0]+.2*parts[1]+.1*parts[2]
        if criterion<best:
            best=criterion;best_state=copy.deepcopy(model.state_dict());best_epoch=epoch+1;phase_best=epoch
            torch.save(best_state,a.output/'selected-weights.pt')
        entry=dict(epoch=epoch+1,phase='fineTune' if fine else 'head',trainingLoss=float(np.mean(losses)),validationGripNLL=parts[0],validationSurfaceNLL=parts[1],validationContactNLL=parts[2],selectionLoss=criterion,seconds=time.perf_counter()-started)
        history.append(entry);(a.output/'history.json').write_text(json.dumps(history,indent=2)+'\n');print(json.dumps(entry),flush=True)
        if fine and epoch-phase_best>=5:break
    model.load_state_dict(best_state);model.eval();logits=infer(model,vh,vv,True);calibration=[]
    for j,(x,y,w) in enumerate(zip(logits,vys,vws)):
        losses={str(t):nll(x/t,y,w) for t in [1.,1.25,1.5,2.,3.,4.]};temperature=float(min(losses,key=losses.get));model.temperature[j]=temperature
        calibration.append(dict(temperature=temperature,validationLoss=losses))
    torch.save(model.state_dict(),a.output/'weights.pt');script=torch.jit.trace(model,(torch.zeros(1,3,128,128),torch.tensor([[1.,0.,0.]])))
    script.save(str(a.output/'GripHandClassifier.pt'))
    accuracies=[]
    for x,y in zip(logits,vys):
        c=defaultdict(Counter)
        for target,predicted in zip(y.tolist(),x.argmax(1).tolist()):
            if target>=0:c[str(target)]['correct' if target==predicted else 'wrong']+=1
        accuracies.append(c)
    receipt=dict(variant=a.variant,grips=GRIPS[:classes],surfaces=SURFACES,viewClasses=VIEWS,seed=3103,
        architecture='MobileNetV3-small RGB hand encoder; directly reviewed surface/contact heads; grip head conditioned on reviewed athlete orientation',
        inputSHA256=sha(a.input),reviewSHA256=sha(a.review),testReserveSHA256=sha(a.test_reserve),scriptSHA256=sha(__file__),
        modelSHA256=sha(a.output/'GripHandClassifier.pt'),trainingPatches=len(training),validationPatches=len(validation),
        trainingGripCounts=dict(Counter(GRIPS[int(y)] for y in ys[0] if y>=0)),trainingSurfaceCounts=dict(Counter(SURFACES[int(y)] for y in ys[1])),
        validationAccuracy=dict(grip=accuracies[0],surface=accuracies[1],contact=accuracies[2]),bestEpoch=best_epoch,bestValidationLoss=best,epochsRun=len(history),
        checkpointSelection='equal-class/equal-video validation grip NLL + .2 surface NLL + .1 contact NLL; reviewed orientation only; no test reads',
        calibration=calibration,changedFeatureParameterTensors=sum(not torch.equal(p.detach(),original[k]) for k,p in model.features.named_parameters()),
        threshold=.8,contactThreshold=.8,unknownPolicy='Grip score below .8 or predicted closed-contact below .8; missing input remains unknown',
        sources={s:sorted({r['recordingId'] for r in allrows if r['annotation']['split']==s}) for s in ['train','validation']},
        sourceGroups={s:sorted({r['annotation'].get('sourceGroup') or ('recording:'+r['recordingId']) for r in allrows if r['annotation']['split']==s}) for s in ['train','validation']},
        parameters=sum(p.numel() for p in model.parameters()),trainingHandSHA256=sorted(p['patchSHA256'] for r,p in training))
    (a.output/'training-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    (a.output/'trainer-snapshot.py').write_bytes(Path(__file__).read_bytes())
    print(json.dumps({k:receipt[k] for k in ['variant','bestEpoch','changedFeatureParameterTensors','validationAccuracy','modelSHA256']}),flush=True)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['input','review','test_reserve','output']:p.add_argument(n,type=Path)
    p.add_argument('--variant',choices=['binary','threeway'],default='threeway');p.add_argument('--warmup',type=int,default=3);p.add_argument('--epochs',type=int,default=20)
    train(p.parse_args())


if __name__=='__main__':main()
