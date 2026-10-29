#!/usr/bin/env python3
"""Fit direct surface labels first, then contact and matched conditional grip heads.

Grip labels never update the appearance encoder. Binary and ternary heads share
exactly the selected encoder, surface head, contact head and reviewed image set.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse
from collections import Counter
import copy
import importlib.util
import json
from pathlib import Path
import random
import time

import numpy as np
import torch
from torch import nn
from torchvision import transforms

spec=importlib.util.spec_from_file_location('staged_base',Path(__file__).with_name('train-threeway-grip.py'))
base=importlib.util.module_from_spec(spec);spec.loader.exec_module(base)


class StagedHand(base.HandModel):
    def __init__(self,classes=3,pretrained=True):
        super().__init__(classes,pretrained)
        self.grip=nn.Sequential(nn.Linear((576+4)*3,64),nn.Hardswish(),nn.Dropout(.2),nn.Linear(64,classes))

    def grip_features(self,embedding,view):
        surface=(self.surface(embedding)/self.temperature[1]).softmax(1)
        image=torch.cat([embedding,surface],1)
        return torch.cat([image*view[:,i:i+1] for i in range(3)],1)

    def heads(self,embedding,view):
        return self.grip(self.grip_features(embedding,view)),self.surface(embedding),self.contact(embedding)


def seed():
    torch.manual_seed(3103);np.random.seed(3103);random.seed(3103)


def batched(call,x):
    with torch.no_grad():return torch.cat([call(x[i:i+16]) for i in range(0,len(x),16)])


def calibrate(logits,y,w):
    losses={str(t):base.nll(logits/t,y,w) for t in [1.,1.25,1.5,2.,3.,4.]}
    return dict(temperature=float(min(losses,key=losses.get)),validationNLL=losses)


def accuracy(logits,y):
    result={}
    for target,pred in zip(y.tolist(),logits.argmax(1).tolist()):
        if target<0:continue
        bucket=result.setdefault(str(target),dict(correct=0,wrong=0));bucket['correct' if target==pred else 'wrong']+=1
    return result


def fit_surface(model,training,validation,out,epochs):
    h,_=base.data(training);vh,_=base.data(validation)
    y=base.targets(training,3)[1];vy=base.targets(validation,3)[1]
    w=base.weights(training,y);vw=base.weights(validation,vy)
    for n in range(4):assert (y==n).any() and (vy==n).any(),'Surface classes need independent validation examples'
    aug=transforms.Compose([transforms.RandomAffine(7,translate=(.02,.02),scale=(.95,1.05)),transforms.ColorJitter(.12,.12,.08,.01)])
    original={k:p.detach().clone() for k,p in model.features.named_parameters()}
    optimizer=torch.optim.AdamW(model.surface.parameters(),lr=.001,weight_decay=.02)
    best=float('inf');state=None;history=[];best_epoch=0;phase_best=0
    for epoch in range(2+epochs):
        started=time.perf_counter();fine=epoch>=2
        if epoch==2:
            model.load_state_dict(state)
            optimizer=torch.optim.AdamW([{'params':model.features.parameters(),'lr':.00005},{'params':model.surface.parameters(),'lr':.0005}],weight_decay=.02)
            phase_best=epoch
        for p in model.features.parameters():p.requires_grad=fine
        model.train()
        for layer in model.modules():
            if isinstance(layer,nn.BatchNorm2d):layer.eval()
        ids=torch.multinomial(w,len(training),replacement=True).tolist();losses=[]
        for offset in range(0,len(ids),16):
            batch=ids[offset:offset+16];optimizer.zero_grad();x=torch.stack([aug(h[i]) for i in batch]);loss=nn.functional.cross_entropy(model.surface(model.embed(x)),y[batch],label_smoothing=.02)
            loss.backward();nn.utils.clip_grad_norm_(model.parameters(),5);optimizer.step();losses.append(float(loss.detach()))
        model.eval();logits=batched(lambda x:model.surface(model.embed(x)),vh);val=base.nll(logits,vy,vw)
        if val<best:best=val;state=copy.deepcopy(model.state_dict());best_epoch=epoch+1;phase_best=epoch
        entry=dict(epoch=epoch+1,phase='fineTune' if fine else 'head',trainingNLL=float(np.mean(losses)),validationBalancedNLL=val,seconds=time.perf_counter()-started)
        history.append(entry);(out/'surface-history.json').write_text(json.dumps(history,indent=2)+'\n');print(json.dumps(dict(stage='surface',**entry)),flush=True)
        if fine and epoch-phase_best>=5:break
    model.load_state_dict(state);model.eval();logits=batched(lambda x:model.surface(model.embed(x)),vh)
    calibration=calibrate(logits,vy,vw);model.temperature[1]=calibration['temperature']
    return dict(bestEpoch=best_epoch,validationNLL=best,validationAccuracy=accuracy(logits,vy),calibration=calibration,
        changedFeatureParameterTensors=sum(not torch.equal(p.detach(),original[k]) for k,p in model.features.named_parameters()))


def fit_head(model,kind,training,validation,cached,vcached,out,classes):
    h,v=cached;vh,vv=vcached;task=2 if kind=='contact' else 0
    y=base.targets(training,classes,True)[task];vy=base.targets(validation,classes)[task]
    w=base.weights(training,y);vw=base.weights(validation,vy)
    assert set(y[y>=0].tolist())==set(range(2 if kind=='contact' else classes))
    head=model.contact if kind=='contact' else model.grip
    optimizer=torch.optim.AdamW(head.parameters(),lr=.001,weight_decay=.03)
    model.eval()
    x=h if kind=='contact' else model.grip_features(h,v*.95+.05/3).detach()
    vx=vh if kind=='contact' else model.grip_features(vh,vv).detach()
    best=float('inf');state=None;history=[];best_epoch=0
    for epoch in range(120):
        head.train();ids=torch.multinomial(w,len(training),replacement=True).tolist();losses=[]
        for offset in range(0,len(ids),16):
            batch=ids[offset:offset+16];optimizer.zero_grad();logits=head(x[batch]);loss=nn.functional.cross_entropy(logits,y[batch],label_smoothing=.02)
            loss.backward();nn.utils.clip_grad_norm_(head.parameters(),5);optimizer.step();losses.append(float(loss.detach()))
        head.eval()
        with torch.no_grad():val=base.nll(head(vx),vy,vw)
        if val<best:best=val;state=copy.deepcopy(head.state_dict());best_epoch=epoch+1
        history.append(dict(epoch=epoch+1,trainingNLL=float(np.mean(losses)),validationBalancedNLL=val))
        if epoch+1-best_epoch>=12:break
    head.load_state_dict(state);head.eval()
    with torch.no_grad():logits=head(vx)
    calibration=calibrate(logits,vy,vw);model.temperature[2 if kind=='contact' else 0]=calibration['temperature']
    (out/(kind+'-history.json')).write_text(json.dumps(history,indent=2)+'\n')
    return dict(bestEpoch=best_epoch,epochsRun=len(history),validationNLL=best,validationAccuracy=accuracy(logits,vy),calibration=calibration)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['input','review','reserve','output']:p.add_argument(name,type=Path)
    p.add_argument('--epochs',type=int,default=20);a=p.parse_args()
    assert not (a.output/'paired-receipt.json').exists();a.output.mkdir(parents=True,exist_ok=True)
    data=base.load(a.input);review=json.loads(a.review.read_text());reserve=json.loads(a.reserve.read_text())
    assert review['outputSHA256']==base.sha(a.input)
    lookup={(x['recordingId'],x['reviewId']):x for x in review['patches']}
    for r,patch in base.pairs(data):assert patch['threewayReview']==lookup[r['recordingId'],patch['reviewId']]
    assert not {r['recordingId'] for r in data}&{s['sha256'] for s in reserve['sources']}
    assert not {r['annotation'].get('sourceGroup') for r in data}&{s['sourceGroup'] for s in reserve['sources']}
    bygroup={}
    for r in data:
        group=r['annotation'].get('sourceGroup') or r['recordingId'];split=r['annotation']['split']
        assert group not in bygroup or bygroup[group]==split,'Fitting group crosses splits'
        bygroup[group]=split
    torch.set_num_threads(2);torch.set_num_interop_threads(2);seed()
    items=base.pairs(data);training=[x for x in items if x[0]['annotation']['split']=='train'];validation=[x for x in items if x[0]['annotation']['split']=='validation']
    model=StagedHand();surface=fit_surface(model,training,validation,a.output,a.epochs)
    for par in model.parameters():par.requires_grad=False
    h,v=base.data(training);vh,vv=base.data(validation);embedding=batched(model.embed,h).detach();vembedding=batched(model.embed,vh).detach()
    for par in model.contact.parameters():par.requires_grad=True
    contact=fit_head(model,'contact',training,validation,(embedding,v),(vembedding,vv),a.output,3)
    shared=copy.deepcopy(model.state_dict());receipts={}
    for classes,name in [(2,'binary'),(3,'threeway')]:
        seed();current=StagedHand(classes,False);weights={k:v for k,v in shared.items() if not k.startswith('grip.')};current.load_state_dict(weights,strict=False)
        for par in current.parameters():par.requires_grad=False
        for par in current.grip.parameters():par.requires_grad=True
        target=a.output/name;target.mkdir(exist_ok=True)
        grip=fit_head(current,'grip',training,validation,(embedding,v),(vembedding,vv),target,classes)
        current.eval();torch.save(current.state_dict(),target/'weights.pt');script=torch.jit.trace(current,(torch.zeros(1,3,128,128),torch.tensor([[1.,0.,0.]])))
        script.save(str(target/'GripHandClassifier.pt'))
        receipt=dict(variant=name,grips=base.GRIPS[:classes],surfaces=base.SURFACES,views=base.VIEWS,
            modelSHA256=base.sha(target/'GripHandClassifier.pt'),inputSHA256=base.sha(a.input),reviewSHA256=base.sha(a.review),testReserveSHA256=base.sha(a.reserve),scriptSHA256=base.sha(__file__),
            architecture='Shared directly supervised surface CNN, independently fitted contact head, conditional RGB-embedding+surface head; grip cannot change surface CNN',
            surface=surface,contact=contact,grip=grip,trainingPatches=len(training),validationPatches=len(validation),
            trainingGripCounts=dict(Counter(base.GRIPS[int(y)] for y in base.targets(training,classes,True)[0] if y>=0)),
            trainingSurfaceCounts=dict(Counter(p['threewayReview']['surface'] for r,p in training)),threshold=.8,contactThreshold=.8,parameters=sum(p.numel() for p in current.parameters()),
            sources={s:sorted({r['recordingId'] for r in data if r['annotation']['split']==s}) for s in ['train','validation']},
            sourceGroups={s:sorted({r['annotation'].get('sourceGroup') or ('recording:'+r['recordingId']) for r in data if r['annotation']['split']==s}) for s in ['train','validation']})
        (target/'training-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');receipts[name]=receipt
        print(json.dumps(dict(variant=name,surface=surface,contact=contact,grip=grip,modelSHA256=receipt['modelSHA256'])),flush=True)
    for key in ['features.','surface.','contact.']:
        x=torch.load(a.output/'binary/weights.pt',weights_only=True);y=torch.load(a.output/'threeway/weights.pt',weights_only=True)
        assert all(torch.equal(v,y[k]) for k,v in x.items() if k.startswith(key))
    (a.output/'paired-receipt.json').write_text(json.dumps(dict(sharedEncoderSurfaceContactIdentical=True,models=receipts),indent=2)+'\n')
    (a.output/'trainer-snapshot.py').write_bytes(Path(__file__).read_bytes())


if __name__=='__main__':main()
