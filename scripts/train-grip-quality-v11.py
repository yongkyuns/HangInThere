#!/usr/bin/env python3
"""Fit quality heads on a frozen qualified development anatomy encoder."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2');os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import copy,hashlib,importlib.util,json
from pathlib import Path
import numpy as np
import torch
from torch import nn
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-anatomy-v11'
spec=importlib.util.spec_from_file_location('s',ROOT/'scripts/train-grip-front-rear-v9.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def embed(model,images):
    with torch.inference_mode():out=torch.cat([model.embed(images[i:i+16]) for i in range(0,len(images),16)])
    return out.clone()
def fit(head,x,y,vx,vy,items,vitems):
    weights=s.group_weights(items,y);vw=s.group_weights(vitems,vy);opt=torch.optim.AdamW(head.parameters(),lr=1e-4,weight_decay=.02)
    head.eval()
    with torch.no_grad():best=s.base.nll(head(vx),vy,vw)
    state=copy.deepcopy(head.state_dict());best_epoch=0;history=[dict(epoch=0,validationNLL=best)]
    for epoch in range(80):
        head.train();indices=torch.multinomial(weights,len(items),replacement=True).tolist()
        for start in range(0,len(indices),16):
            ids=indices[start:start+16];opt.zero_grad();loss=nn.functional.cross_entropy(head(x[ids]),y[ids],label_smoothing=.02);loss.backward();opt.step()
        head.eval()
        with torch.no_grad():nll=s.base.nll(head(vx),vy,vw)
        history.append(dict(epoch=epoch+1,validationNLL=nll))
        if nll<best:best=nll;state=copy.deepcopy(head.state_dict());best_epoch=epoch+1
        if epoch+1-best_epoch>=8:break
    head.load_state_dict(state);head.eval()
    with torch.no_grad():logits=head(vx)
    temperatures={str(t):s.base.nll(logits/t,vy,vw) for t in [1.,1.25,1.5,2.,3.,4.]}
    return dict(bestEpoch=best_epoch,history=history,temperature=float(min(temperatures,key=temperatures.get)),calibration=temperatures)
def main():
    torch.set_num_threads(2);torch.set_num_interop_threads(2);torch.manual_seed(4114);np.random.seed(4114)
    receipt=json.loads((OUT/'joint/receipt.json').read_text());assert receipt['reviewedAnatomyGatePassed']
    folder=OUT/'frozenQuality';folder.mkdir(exist_ok=False)
    freeze=dict(qualification=__doc__,initialSHA256=sha(OUT/'joint/weights.pt'),inputSHA256=sha(OUT/'fitting.jsonl'),reviewSHA256=sha(OUT/'review.json'),scriptSHA256=sha(__file__),
      anatomicalGate='Passes revised development refs only; full grip cohort remains unready; no final-test or deployment claim',
      seed=4114,budget=dict(epochsPerHead=80,patience=8,learningRate=1e-4,weightDecay=.02),
      training='Freeze encoder and surface parameters, train existing quality heads independently with class/source-group-balanced sampling; baseline checkpoint included',
      calibrationTemperatures=[1.,1.25,1.5,2.,3.,4.],thresholds=dict(surface=.8,visible=.8,contact=.8,grip=.8),finalReserveInspected=False)
    (folder/'freeze.json').write_text(json.dumps(freeze,indent=2)+'\n')
    rows=s.base.load(OUT/'fitting.jsonl');pairs=s.base.pairs(rows);train=[x for x in pairs if x[0]['annotation']['split']=='train'];val=[x for x in pairs if x[0]['annotation']['split']=='validation']
    model=s.SurfaceModel(False);model.load_state_dict(torch.load(OUT/'joint/weights.pt',weights_only=True));model.eval();initial=copy.deepcopy(model.state_dict())
    for p in model.parameters():p.requires_grad=False
    h,_=s.base.data(train);vh,_=s.base.data(val);ys=s.targets(train);vys=s.targets(val);x=embed(model,h);vx=embed(model,vh)
    with torch.inference_mode():before=model(vh)[0].clone()
    reports={}
    for i,name in [(1,'visible'),(2,'contact')]:
        head=getattr(model,name)
        for p in head.parameters():p.requires_grad=True
        reports[name]=fit(head,x,ys[i],vx,vys[i],train,val);model.temperature[i]=reports[name]['temperature']
    model.eval()
    for key,value in initial.items():
        if not key.startswith(('visible.','contact.')) and key!='temperature':assert torch.equal(value,model.state_dict()[key]),key
    assert torch.equal(initial['temperature'][:1],model.temperature[:1])
    with torch.inference_mode():after=model(vh)[0]
    assert torch.equal(before,after),'Quality adaptation changed anatomy predictions'
    torch.save(model.state_dict(),folder/'weights.pt');torch.jit.trace(model,torch.zeros(1,3,128,128)).save(str(folder/'GripSurfaceClassifier.pt'))
    (folder/'receipt.json').write_text(json.dumps(dict(qualification=__doc__,freezeSHA256=sha(folder/'freeze.json'),modelSHA256=sha(folder/'GripSurfaceClassifier.pt'),anatomyPredictionsBitIdentical=True,heads=reports,candidateEnabledInApp=False),indent=2)+'\n')
    print(json.dumps(dict(anatomyPredictionsBitIdentical=True,bestEpoch={n:r['bestEpoch'] for n,r in reports.items()})))
if __name__=='__main__':main()
