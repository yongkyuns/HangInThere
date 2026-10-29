#!/usr/bin/env python3
"""Fine-tune hand RGB with optional selected-athlete context and orientation head.

Training/checkpoint selection only reads train/validation videos. Test scoring is
an explicit separate command after weights are frozen. All CNN weights train;
BatchNorm running statistics stay pretrained for this small correlated corpus.
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

SPEC=importlib.util.spec_from_file_location('patch_base',Path(__file__).with_name('train-grip-patches.py'))
base=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(base)
LABELS=base.LABELS
VIEWS=['front','rear','oblique']

class ContextClassifier(nn.Module):
    def __init__(self,context=True,pretrained=True):
        super().__init__();self.use_context=context
        self.features=models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.IMAGENET1K_V1 if pretrained else None).features
        self.pool=nn.AdaptiveAvgPool2d(1)
        self.orientation=nn.Linear(582,3)
        self.head=nn.Sequential(nn.Linear(1161 if context else 576,128),nn.Hardswish(),nn.Dropout(.25),nn.Linear(128,2))
        self.register_buffer('mean',torch.tensor([.485,.456,.406]).reshape(1,3,1,1))
        self.register_buffer('std',torch.tensor([.229,.224,.225]).reshape(1,3,1,1))
    def embed(self,x):return self.pool(self.features((x-self.mean)/self.std)).flatten(1)
    def logits(self,hand,context,cues):
        h=self.embed(hand)
        if self.use_context:
            c=self.embed(context)*cues[:,5:6]
            orient=self.orientation(torch.cat([c,cues],1))
            fusion=torch.cat([h,c,cues,orient.softmax(1)],1)
        else:
            orient=torch.zeros((hand.shape[0],3),device=hand.device)
            fusion=h
        return self.head(fusion),orient
    def forward(self,hand,context,cues):
        grip,view=self.logits(hand,context,cues)
        return grip.softmax(1),view.softmax(1)


class FaceBodyOrientation(nn.Module):
    """Keep verified low-dimensional face evidence explicit beside torso RGB."""
    def __init__(self):
        super().__init__()
        self.face=nn.Sequential(nn.Linear(6,16),nn.Hardswish(),nn.Linear(16,3))
        self.body=nn.Linear(576,3)
    def forward(self,features):
        return self.face(features[:,576:])+.25*self.body(features[:,:576])


class OrientedClassifier(ContextClassifier):
    """Factor grip into camera-facing hand appearance and athlete orientation.

    Front + visible palm implies overhand; rear reverses that relationship.
    Oblique orientation contributes equal front/rear weight rather than forcing
    the rule onto an ambiguous viewing angle. Final grip remains two classes.
    """
    def __init__(self):
        super().__init__(context=True)
        self.head=nn.Sequential(nn.Linear(576,128),nn.Hardswish(),nn.Dropout(.25),nn.Linear(128,2))
        self.orientation=FaceBodyOrientation()
    def logits(self,hand,context,cues):
        h=self.embed(hand);c=self.embed(context)*cues[:,5:6]
        orientation=self.orientation(torch.cat([c,cues],1))
        view=orientation.softmax(1);appearance=self.head(h).softmax(1)
        front=view[:,0]+.5*view[:,2];rear=view[:,1]+.5*view[:,2]
        over=appearance[:,0]*front+appearance[:,1]*rear
        scores=torch.stack([over,1-over],1).clamp(min=1e-7,max=1-1e-7)
        return scores.log(),orientation


def load(path,reviews=None):
    rows=base.load_rows(path)
    if reviews:base.apply_reviews(rows,json.loads(reviews.read_text())['exclusions'])
    for row in rows:
        c=row['context']
        assert len(c['faceCues'])==6 and all(0<=v<=1 for v in c['faceCues'])
        if 'faceDetector' in c:
            f=c['faceDetector']
            assert f['sourceId']==row['recordingId'] and f['timestamp']==row['pose']['timestamp']
            assert f['imageSHA256']==row['imageSHA256']
        if c['status']=='ready':
            assert c['sourceId']==row['recordingId'] and c['timestamp']==row['pose']['timestamp']
            assert c['imageSHA256']==row['imageSHA256'] and c['personIndex']==row['patchPlan']['personIndex']
            assert hashlib.sha256(Path(c['image']).read_bytes()).hexdigest()==c['sha256']
    return rows


def available(rows):return [(r,p) for r in rows if base.in_window(r) for p in r['patches'] if p['status']=='patchReady']

def images(pair):
    row,patch=pair;c=row['context'];tensor=transforms.ToTensor()
    hand=tensor(Image.open(patch['patch']).convert('RGB'))
    context=tensor(Image.open(c['image']).convert('RGB')) if c['status']=='ready' else torch.zeros(3,128,128)
    return hand,context,torch.tensor(c['faceCues'],dtype=torch.float32)


def predict(model,pairs):
    out=[];model.eval()
    with torch.inference_mode():
        for offset in range(0,len(pairs),16):
            batch=pairs[offset:offset+16];data=[images(p) for p in batch]
            score,orient=model(*(torch.stack([d[i] for d in data]) for i in range(3)))
            out.extend(zip(score.tolist(),orient.tolist()))
    return out


def validation_loss(model,pairs):
    scores=predict(model,pairs);by_source=defaultdict(list);source_label={}
    for (r,p),(score,_) in zip(pairs,scores):
        key=r['recordingId'];label=LABELS.index(r['annotation']['label'])
        by_source[key].append(-np.log(max(score[label],1e-8)));source_label[key]=label
    by_class=defaultdict(list)
    for key,losses in by_source.items():by_class[source_label[key]].append(float(np.mean(losses)))
    assert set(by_class)=={0,1},'Validation requires both classes'
    return float(np.mean([np.mean(v) for v in by_class.values()]))


def train(args):
    rows=load(args.input,args.patch_review)
    assert all(r['annotation']['split'] in ['train','validation'] for r in rows),'Test manifest cannot enter training'
    torch.manual_seed(31);np.random.seed(31);random.seed(31)
    if args.variant=='oriented':
        assert all('faceDetector' in r['context'] for r in rows),'Verified face observations required'
        model=OrientedClassifier()
    else:model=ContextClassifier(args.variant=='context')
    model.eval()
    original={k:p.detach().clone() for k,p in model.features.named_parameters()}
    pairs=available(rows);training=[p for p in pairs if p[0]['annotation']['split']=='train']
    validation=[p for p in pairs if p[0]['annotation']['split']=='validation']
    source_counts=Counter(r['recordingId'] for r,p in training)
    class_sources=defaultdict(set)
    for r,p in training:class_sources[r['annotation']['label']].add(r['recordingId'])
    sampling=torch.tensor([1/(source_counts[r['recordingId']]*len(class_sources[r['annotation']['label']])) for r,p in training],dtype=torch.double)
    # Train source/class balancing prevents neighboring owner frames dominating.
    cached=[images(p) for p in training]
    aug=transforms.Compose([transforms.RandomAffine(10,translate=(.025,.025),scale=(.95,1.05)),transforms.ColorJitter(.15,.15,.12,.02)])
    optimizer=torch.optim.AdamW([{'params':model.features.parameters(),'lr':.0001},
        {'params':list(model.head.parameters())+list(model.orientation.parameters()),'lr':.001}],weight_decay=.01)
    best=float('inf');best_state=None;history=[]
    for epoch in range(args.epochs):
        started=time.perf_counter();model.train()
        for layer in model.modules():
            if isinstance(layer,nn.BatchNorm2d):layer.eval()
        indices=torch.multinomial(sampling,len(training),replacement=True).tolist();losses=[]
        for start in range(0,len(indices),16):
            ids=indices[start:start+16];h=torch.stack([aug(cached[i][0]) for i in ids]);c=torch.stack([aug(cached[i][1]) for i in ids]);f=torch.stack([cached[i][2] for i in ids])
            y=torch.tensor([LABELS.index(training[i][0]['annotation']['label']) for i in ids])
            views=[training[i][0]['annotation'].get('viewpoint') for i in ids]
            v=torch.tensor([VIEWS.index(view) if view in VIEWS and cached[i][2][5]>0 else -100 for i,view in zip(ids,views)])
            optimizer.zero_grad();logits,orientation=model.logits(h,c,f)
            loss=nn.functional.cross_entropy(logits,y)
            auxiliary_weight=1. if args.variant=='oriented' else .3
            if model.use_context and (v!=-100).any():loss=loss+auxiliary_weight*nn.functional.cross_entropy(orientation,v,ignore_index=-100)
            loss.backward();nn.utils.clip_grad_norm_(model.parameters(),5.);optimizer.step();losses.append(loss.item())
        val=validation_loss(model,validation)
        if val<best:best=val;best_state=copy.deepcopy(model.state_dict());best_epoch=epoch+1
        entry=dict(epoch=epoch+1,trainingLoss=float(np.mean(losses)),validationSourceClassBalancedNLL=val,seconds=time.perf_counter()-started)
        history.append(entry);print(json.dumps(entry),flush=True)
        (args.output/'history.json').write_text(json.dumps(history,indent=2)+'\n')
    model.load_state_dict(best_state);model.eval()
    changed=sum(not torch.equal(p.detach(),original[k]) for k,p in model.features.named_parameters())
    assert changed>0,'Feature fine-tuning did not occur'
    torch.save(model.state_dict(),args.output/'weights.pt')
    traced=torch.jit.trace(model,(torch.zeros(1,3,128,128),torch.zeros(1,3,128,128),torch.zeros(1,6)))
    traced.save(str(args.output/'GripContextClassifier.pt'))
    receipt=dict(variant=args.variant,seed=31,epochs=args.epochs,bestEpoch=best_epoch,bestValidationNLL=best,
        checkpointSelection='minimum class-balanced mean per-source validation NLL; no test inputs',
        inputSHA256=hashlib.sha256(args.input.read_bytes()).hexdigest(),scriptSHA256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        parameters=sum(p.numel() for p in model.parameters()),trainableParameters=sum(p.numel() for p in model.parameters() if p.requires_grad),
        changedFeatureParameterTensors=changed,trainingPatches=len(training),classCounts=dict(Counter(r['annotation']['label'] for r,p in training)),
        sources={s:sorted({r['recordingId'] for r in rows if r['annotation']['split']==s}) for s in ['train','validation']},
        trainingGroups=sorted({r['annotation'].get('sourceGroup') for r,p in training if r['annotation'].get('sourceGroup')}),
        trainingPatchSHA256=sorted(p['patchSHA256'] for r,p in training),
        threshold=.8,orientationClasses=VIEWS,auxiliaryOrientationLossWeight=(1. if args.variant=='oriented' else .3) if model.use_context else 0,
        batchNorm='pretrained running statistics fixed; affine parameters trained',sampling='equal class, equal source within class',
        modelSHA256=hashlib.sha256((args.output/'GripContextClassifier.pt').read_bytes()).hexdigest())
    (args.output/'training-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(receipt),flush=True)


def score(args):
    rows=load(args.input,args.patch_review);receipt=json.loads((args.model.parent/'training-receipt.json').read_text())
    if any(r['annotation']['split']=='test' for r in rows):
        test_ids={r['recordingId'] for r in rows};used=set(sum(receipt['sources'].values(),[]))
        assert not test_ids&used,'Held-out video leaked into fitting/checkpoint selection'
        assert not set(receipt['trainingGroups']) & {r['annotation'].get('sourceGroup') for r in rows}
    model=torch.jit.load(str(args.model)).eval();pairs=available(rows)
    lookup={p['patchSHA256']:v for (_,p),v in zip(pairs,predict(model,pairs))}
    summary=defaultdict(Counter);by_case=defaultdict(Counter);predictions=[];orientation_cases=defaultdict(Counter)
    for row in rows:
        if not base.in_window(row):continue
        a=row['annotation'];expected=a['label'];split=a['split']
        for p in row['patches']:
            metrics=[summary[split],summary[split+':'+expected],by_case[row['caseId']]]
            rec=dict(caseId=row['caseId'],recordingId=row['recordingId'],timestamp=row['pose']['timestamp'],side=p['side'],
                split=split,expected=expected,patchStatus=p['status'],contextStatus=row['context']['status'])
            for m in metrics:m['expectedHands']+=1
            if p['status']!='patchReady':
                rec.update(predicted='unknown',reason=p['status'])
                for m in metrics:m['unknown']+=1;m['missingPatch']+=1
            else:
                prob,view=lookup[p['patchSHA256']];best=int(np.argmax(prob));pred=LABELS[best];accepted=prob[best]>=.8
                rec.update(probabilities=prob,orientationScores=view,argmax=pred,predicted=pred if accepted else 'unknown',
                    reason='accepted' if accepted else 'lowScore',patch=p['patch'],patchSHA256=p['patchSHA256'],
                    context=row['context'],orientationExpected=a.get('viewpoint'))
                for m in metrics:
                    m['availablePatches']+=1;m['argmaxCorrect' if pred==expected else 'argmaxWrong']+=1
                    m[('correct' if pred==expected else 'wrong') if accepted else 'unknown']+=1
                    if not accepted:m['lowScore']+=1
                if a.get('viewpoint') in VIEWS and row['context']['status']=='ready':
                    orientation_cases[row['caseId']]['correct' if VIEWS[int(np.argmax(view))]==a['viewpoint'] else 'wrong']+=1
            predictions.append(rec)
    report=dict(qualification='development-only; provisional agent labels',splits=summary,cases=by_case,orientationCases=orientation_cases,
        inputSHA256=hashlib.sha256(args.input.read_bytes()).hexdigest(),modelSHA256=hashlib.sha256(args.model.read_bytes()).hexdigest(),
        threshold=.8,sourceCounts=dict(Counter({s:len({r['recordingId'] for r in rows if r['annotation']['split']==s}) for s in ['train','validation','test']})))
    (args.output/'predictions.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in predictions))
    (args.output/'report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('mode',choices=['train','score']);p.add_argument('input',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--variant',choices=['hand','context','oriented'],default='context');p.add_argument('--epochs',type=int,default=20)
    p.add_argument('--patch-review',type=Path);p.add_argument('--model',type=Path)
    args=p.parse_args();args.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    if args.mode=='train':train(args)
    else:assert args.model is not None;score(args)

if __name__=='__main__':main()
