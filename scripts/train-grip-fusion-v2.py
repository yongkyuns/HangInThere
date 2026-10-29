#!/usr/bin/env python3
"""Fit RGB grip heads after surface qualification; view cannot change neutral probability."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
import argparse
from collections import Counter
import copy
import importlib.util
import json
from pathlib import Path

import numpy as np
import torch
from torch import nn

spec=importlib.util.spec_from_file_location('fusion_surface',Path(__file__).with_name('train-grip-surface-v2.py'))
surface=importlib.util.module_from_spec(spec);spec.loader.exec_module(surface);base=surface.base


def combine(binary_logits, neutral_logits, surfaces, view, strength):
    """Front palm/rear dorsum support overhand; neither view nor side alone implies neutral."""
    correction=strength*(surfaces[:,0]-surfaces[:,1])*(view[:,0]-view[:,1])
    odds=torch.stack([binary_logits[:,0]+correction*.5,binary_logits[:,1]-correction*.5],1).softmax(1)
    neutral=neutral_logits.softmax(1)[:,1:2]
    return torch.cat([(1-neutral)*odds,neutral],1)


class FusionModel(nn.Module):
    def __init__(self):
        super().__init__();self.appearance=surface.SurfaceModel(False)
        self.binary=nn.Sequential(nn.Linear(576,64),nn.Hardswish(),nn.Dropout(.2),nn.Linear(64,2))
        self.neutral=nn.Sequential(nn.Linear(576,64),nn.Hardswish(),nn.Dropout(.2),nn.Linear(64,2))
        self.register_buffer('temperature',torch.ones(2));self.register_buffer('strength',torch.tensor(0.))

    def forward(self,image,view):
        e=self.appearance.embed(image)
        s=(self.appearance.surface(e)/self.appearance.temperature[0]).softmax(1)
        visible=(self.appearance.visible(e)/self.appearance.temperature[1]).softmax(1)
        contact=(self.appearance.contact(e)/self.appearance.temperature[2]).softmax(1)
        g=combine(self.binary(e)/self.temperature[0],self.neutral(e)/self.temperature[1],s,view,self.strength)
        return g,s,visible,contact


def fit_head(head,x,y,vx,vy,items,vitems):
    w=base.weights(items,y);vw=base.weights(vitems,vy);opt=torch.optim.AdamW(head.parameters(),lr=.001,weight_decay=.03)
    assert set(y[y>=0].tolist())=={0,1};best=float('inf');state=None;best_epoch=0;history=[]
    for epoch in range(120):
        head.train();ids=torch.multinomial(w,len(items),replacement=True).tolist()
        for start in range(0,len(ids),16):
            batch=ids[start:start+16];opt.zero_grad();loss=nn.functional.cross_entropy(head(x[batch]),y[batch],label_smoothing=.02);loss.backward();opt.step()
        head.eval()
        with torch.no_grad():val=base.nll(head(vx),vy,vw)
        if val<best:best=val;state=copy.deepcopy(head.state_dict());best_epoch=epoch+1
        history.append(dict(epoch=epoch+1,validationNLL=val))
        if epoch+1-best_epoch>=12:break
    head.load_state_dict(state);head.eval()
    with torch.no_grad():logits=head(vx)
    values={str(t):base.nll(logits/t,vy,vw) for t in [1.,1.25,1.5,2.,3.,4.]}
    return dict(bestEpoch=best_epoch,history=history,temperature=float(min(values,key=values.get)),calibration=values)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['input','review','surface','reserve','output']:p.add_argument(name,type=Path)
    a=p.parse_args();receipt=json.loads((a.surface/'training-receipt.json').read_text())
    assert receipt['surfaceValidationGatePassed'],'Surface qualification must pass before grip fitting'
    assert receipt['inputSHA256']==base.sha(a.input) and receipt['reviewSHA256']==base.sha(a.review)
    assert receipt['testReserveSHA256']==base.sha(a.reserve)
    assert not (a.output/'training-receipt.json').exists();a.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2);torch.manual_seed(3104);np.random.seed(3104)
    rows=base.load(a.input);pairs=base.pairs(rows);training=[x for x in pairs if x[0]['annotation']['split']=='train'];validation=[x for x in pairs if x[0]['annotation']['split']=='validation']
    h,v=base.data(training);vh,vv=base.data(validation);ys=base.targets(training,3,True)[0];vys=base.targets(validation,3)[0]
    model=FusionModel();model.appearance.load_state_dict(torch.load(a.surface/'weights.pt',weights_only=True));model.appearance.eval()
    for par in model.appearance.parameters():par.requires_grad=False
    with torch.inference_mode():
        x=torch.cat([model.appearance.embed(h[i:i+16]) for i in range(0,len(h),16)]).clone();vx=torch.cat([model.appearance.embed(vh[i:i+16]) for i in range(0,len(vh),16)]).clone()
    # Clone outside inference_mode so linear backward can retain these fixed features.
    x=x.clone();vx=vx.clone()
    result={}
    for name in ['binary','neutral']:
        y=ys.clone();vy=vys.clone()
        if name=='binary':y[y==2]=-100;vy[vy==2]=-100
        else:y=torch.where(y>=0,(y==2).long(),y);vy=torch.where(vy>=0,(vy==2).long(),vy)
        result[name]=fit_head(getattr(model,name),x,y,vx,vy,training,validation)
        model.temperature[0 if name=='binary' else 1]=result[name]['temperature']
    model.eval();weights=base.weights(validation,vys)
    with torch.no_grad():
        s=(model.appearance.surface(vx)/model.appearance.temperature[0]).softmax(1);binary=model.binary(vx)/model.temperature[0];neutral=model.neutral(vx)/model.temperature[1]
        losses={str(k):base.nll(combine(binary,neutral,s,vv,k).clamp_min(1e-8).log(),vys,weights) for k in [0.,.5,1.,2.]}
        model.strength.fill_(float(min(losses,key=losses.get)))
        a0=model(vh[:8],torch.tensor([[1.,0.,0.]]).repeat(min(8,len(vh)),1))[0][:,2]
        a1=model(vh[:8],torch.tensor([[0.,1.,0.]]).repeat(min(8,len(vh)),1))[0][:,2]
        assert torch.equal(a0,a1),'Supplied view must never alter neutral probability'
    torch.save(model.state_dict(),a.output/'weights.pt');torch.jit.trace(model,(torch.zeros(1,3,128,128),torch.tensor([[1.,0.,0.]]))).save(str(a.output/'GripFusionClassifier.pt'))
    result.update(modelSHA256=base.sha(a.output/'GripFusionClassifier.pt'),surfaceModelSHA256=receipt['modelSHA256'],testReserveSHA256=receipt['testReserveSHA256'],
        inputSHA256=receipt['inputSHA256'],reviewSHA256=receipt['reviewSHA256'],strength=float(model.strength),validationFusionNLL=losses,neutralViewInvariant=True,
        qualification='No view input to RGB heads; bounded semantic over/under odds correction; neutral probability exactly image-only; validation selection only')
    (a.output/'training-receipt.json').write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(dict(strength=float(model.strength),neutralViewInvariant=True)))


if __name__=='__main__':main()
