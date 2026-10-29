#!/usr/bin/env python3
"""Warm-start gradient-isolation experiment, not grip deployment qualification."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2');os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse,copy,hashlib,importlib.util,json,random,time
from pathlib import Path
import numpy as np
import torch
from torch import nn
from torchvision import transforms
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-anatomy-v11'
spec=importlib.util.spec_from_file_location('s',ROOT/'scripts/train-grip-front-rear-v9.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def load_data():
    rows=s.base.load(OUT/'fitting.jsonl');pairs=s.base.pairs(rows)
    return rows,[x for x in pairs if x[0]['annotation']['split']=='train'],[x for x in pairs if x[0]['annotation']['split']=='validation']
def coverage(outputs,targets,items):
    result={}
    for i,name in enumerate(s.SURFACES):
        mask=targets==i
        result[name]=dict(total=int(mask.sum()),correct=int(((outputs.argmax(1)==i)&mask).sum()),
            groups=len({r['annotation'].get('sourceGroup') or r['recordingId'] for (r,p),good in zip(items,mask) if good}))
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('arm',choices=['freeze','anatomyOnly','joint']);a=parser.parse_args()
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    rows,train,val=load_data();initial=ROOT/'build/grip-surface-v2/model/weights.pt';freeze=OUT/'training-freeze.json'
    inputs=[OUT/'fitting.jsonl',OUT/'review.json',OUT/'review-protocol.json',initial,Path(__file__),ROOT/'scripts/train-grip-front-rear-v9.py',ROOT/'scripts/train-threeway-grip.py']
    hashes={str(p):sha(p) for p in inputs}
    if a.arm=='freeze':
        assert not freeze.exists()
        f=dict(qualification=__doc__,inputHashes=hashes,arms=['anatomyOnly','joint'],initialization='Previous trained v2 image model; no ImageNet reset',
            baselineSHA256=sha(initial),seed=4113,epochs=12,encoderLearningRate=3e-5,headLearningRate=1e-4,
            encoderBlocks='9 onward; BatchNorm always eval',batchSize=16,
            sampler='Identical class/source-group weighted .6 anatomy + .2 visibility + .2 contact; same sample and augmentation RNG plan in both arms',
            loss='Importance-corrected per-head group-balanced cross entropy; anatomyOnly masks quality gradients and freezes quality-head parameters; joint uses coefficients 1/.25/.2',
            selection='Minimum reviewed validation anatomy NLL, including unchanged baseline epoch 0; no quality term in either arm selection',
            qualityCalibration='anatomyOnly preserves original quality temperatures; joint calibrates quality temperatures on reviewed validation',
            qualificationGate=dict(recallPerClass=.65,macroRecall=.7,minimumCropsPerClass=6,minimumGroupsPerClass=3),
            confidence=dict(surface=.8,visible=.8,contact=.8,grip=.8),
            trainingCrops=len(train),validationCrops=len(val),finalReserveInspected=False,
            deployment='No fusion or phone activation; full grip cohort has reopened gaps. Keep both original-reference and reviewed-reference metrics.')
        freeze.write_text(json.dumps(f,indent=2)+'\n');print(json.dumps(f,indent=2));return
    f=json.loads(freeze.read_text());assert f['inputHashes']==hashes
    folder=OUT/a.arm;folder.mkdir(exist_ok=False)
    torch.manual_seed(f['seed']);np.random.seed(f['seed']);random.seed(f['seed'])
    model=s.SurfaceModel(False);model.load_state_dict(torch.load(initial,weights_only=True));model.eval()
    initial_state=copy.deepcopy(model.state_dict())
    h,_=s.base.data(train);vh,_=s.base.data(val);y=s.targets(train);vy=s.targets(val)
    weights=[s.group_weights(train,t) for t in y];vw=[s.group_weights(val,t) for t in vy];sampler=.6*weights[0]+.2*weights[1]+.2*weights[2]
    for name,p in model.features.named_parameters():p.requires_grad=int(name.split('.')[0])>=9
    for head in [model.visible,model.contact]:
        for p in head.parameters():p.requires_grad=a.arm=='joint'
    heads=list(model.surface.parameters())+([*model.visible.parameters(),*model.contact.parameters()] if a.arm=='joint' else [])
    opt=torch.optim.AdamW([dict(params=[p for p in model.features.parameters() if p.requires_grad],lr=f['encoderLearningRate']),dict(params=heads,lr=f['headLearningRate'])],weight_decay=.02)
    aug=transforms.Compose([transforms.RandomHorizontalFlip(),transforms.RandomVerticalFlip(),transforms.RandomAffine(20,translate=(.025,.025),scale=(.95,1.05)),transforms.ColorJitter(.12,.12,.08,.01)])
    with torch.inference_mode():pred=s.infer(model,vh)
    best=s.base.nll(pred[0],vy[0],vw[0]);selected=copy.deepcopy(model.state_dict());epoch_best=0
    history=[dict(epoch=0,anatomyNLL=best,coverage=coverage(pred[0],vy[0],val))];sample_plans=[]
    for epoch in range(f['epochs']):
        started=time.perf_counter();torch.manual_seed(f['seed']+epoch+1);random.seed(f['seed']+epoch+1)
        ids=torch.multinomial(sampler,len(train),replacement=True).tolist();sample_plans.append(ids);model.train()
        for m in model.modules():
            if isinstance(m,nn.BatchNorm2d):m.eval()
        losses=[]
        for start in range(0,len(ids),f['batchSize']):
            batch=ids[start:start+f['batchSize']];opt.zero_grad();image=torch.stack([aug(h[i]) for i in batch]);out=model.logits(image);loss=out[0].sum()*0
            for i,coefficient in enumerate([1.,.25,.2]):
                if i>0 and a.arm=='anatomyOnly':continue
                ce=nn.functional.cross_entropy(out[i],y[i][batch],ignore_index=-100,reduction='none',label_smoothing=.02)
                loss=loss+coefficient*(ce*weights[i][batch]/sampler[batch]).mean()
            loss.backward();nn.utils.clip_grad_norm_(model.parameters(),5);opt.step();losses.append(float(loss.detach()))
        model.eval();pred=s.infer(model,vh);nll=s.base.nll(pred[0],vy[0],vw[0])
        if nll<best:best=nll;selected=copy.deepcopy(model.state_dict());epoch_best=epoch+1
        entry=dict(epoch=epoch+1,trainingLoss=float(np.mean(losses)),anatomyNLL=nll,coverage=coverage(pred[0],vy[0],val),seconds=time.perf_counter()-started)
        history.append(entry);(folder/'history.json').write_text(json.dumps(history,indent=2)+'\n');print(json.dumps(entry),flush=True)
    model.load_state_dict(selected);model.eval();pred=s.infer(model,vh)
    if a.arm=='anatomyOnly':
        for name,value in initial_state.items():
            if name.startswith(('visible.','contact.')):assert torch.equal(model.state_dict()[name],value),name
        assert torch.equal(model.temperature[1:],initial_state['temperature'][1:])
    calibration=[]
    for i in range(3 if a.arm=='joint' else 1):
        choices={str(t):s.base.nll(pred[i]/t,vy[i],vw[i]) for t in [1.,1.25,1.5,2.,3.,4.]};temperature=float(min(choices,key=choices.get));model.temperature[i]=temperature;calibration.append(dict(head=i,temperature=temperature,validationNLL=choices))
    torch.save(model.state_dict(),folder/'weights.pt');torch.jit.trace(model,torch.zeros(1,3,128,128)).save(str(folder/'GripSurfaceClassifier.pt'))
    c=coverage(pred[0],vy[0],val);recall=[v['correct']/v['total'] for v in c.values()]
    gate=all(v['total']>=6 and v['groups']>=3 for v in c.values()) and min(recall)>=.65 and sum(recall)/3>=.7
    receipt=dict(arm=a.arm,freezeSHA256=sha(freeze),modelSHA256=sha(folder/'GripSurfaceClassifier.pt'),inputSHA256=sha(OUT/'fitting.jsonl'),
        bestEpoch=epoch_best,selectionLoss=best,coverage=c,macroRecall=sum(recall)/3,reviewedAnatomyGatePassed=gate,
        qualityHeadParametersUnchanged=a.arm=='anatomyOnly',samplePlanSHA256=hashlib.sha256(json.dumps(sample_plans).encode()).hexdigest(),
        calibration=calibration,epochsRun=f['epochs'],qualification='Reviewed development-reference anatomy result only; no independent final-test or grip deployment qualification')
    (folder/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps(receipt),flush=True)
if __name__=='__main__':main()
