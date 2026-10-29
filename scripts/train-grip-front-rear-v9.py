#!/usr/bin/env python3
"""Qualify the reviewed front/rear cohort; source-balanced independent quality losses."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import copy
import importlib.util
import json
from pathlib import Path
import random
import time

import numpy as np
import torch
from torch import nn
from torchvision import models, transforms

spec = importlib.util.spec_from_file_location('surface_base', Path(__file__).with_name('train-threeway-grip.py'))
base = importlib.util.module_from_spec(spec); spec.loader.exec_module(base)
SURFACES = ['palm', 'dorsum', 'side']


def group_weights(items, labels):
    def group(row): return row['annotation'].get('sourceGroup') or 'recording:'+row['recordingId']
    counts = Counter((int(y), group(r)) for (r,p), y in zip(items,labels) if y >= 0)
    groups = defaultdict(set)
    for (r,p),y in zip(items,labels):
        if y >= 0: groups[int(y)].add(group(r))
    w = torch.tensor([0. if y < 0 else 1/(counts[int(y),group(r)]*len(groups[int(y)])) for (r,p),y in zip(items,labels)])
    assert w.sum()>0
    return w/w.sum()


base.weights = group_weights


class SurfaceModel(nn.Module):
    def __init__(self, pretrained=True):
        super().__init__()
        self.features = models.mobilenet_v3_small(weights=models.MobileNet_V3_Small_Weights.IMAGENET1K_V1 if pretrained else None).features
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.surface = nn.Sequential(nn.Linear(576, 64), nn.Hardswish(), nn.Dropout(.2), nn.Linear(64, 3))
        self.visible = nn.Sequential(nn.Linear(576, 32), nn.Hardswish(), nn.Dropout(.2), nn.Linear(32, 2))
        self.contact = nn.Sequential(nn.Linear(576, 32), nn.Hardswish(), nn.Dropout(.2), nn.Linear(32, 2))
        self.register_buffer('mean', torch.tensor([.485, .456, .406]).reshape(1, 3, 1, 1))
        self.register_buffer('std', torch.tensor([.229, .224, .225]).reshape(1, 3, 1, 1))
        self.register_buffer('temperature', torch.ones(3))

    def embed(self, image):
        return self.pool(self.features((image-self.mean)/self.std)).flatten(1)

    def logits(self, image):
        e = self.embed(image)
        return self.surface(e), self.visible(e), self.contact(e)

    def forward(self, image):
        s, v, c = self.logits(image)
        return (s/self.temperature[0]).softmax(1), (v/self.temperature[1]).softmax(1), (c/self.temperature[2]).softmax(1)


def targets(items):
    ys = [[], [], []]
    for r, p in items:
        ref = p['threewayReview']; s = ref['surface']
        ys[0].append(SURFACES.index(s) if s in SURFACES else -100)
        ys[1].append(int(s in SURFACES))
        ys[2].append(int(ref['contact'] == 'closed') if ref.get('qualityEligible', True) else -100)
    return [torch.tensor(y) for y in ys]


def infer(model, images, probabilities=False):
    out = [[], [], []]
    with torch.inference_mode():
        for start in range(0, len(images), 16):
            values = model(images[start:start+16]) if probabilities else model.logits(images[start:start+16])
            for dest, v in zip(out, values): dest.append(v)
    return [torch.cat(x) for x in out]


def confusion(logits, labels):
    counts = defaultdict(Counter)
    for truth, pred in zip(labels.tolist(), logits.argmax(1).tolist()):
        if truth >= 0: counts[str(truth)][str(pred)] += 1
    return counts


def fit(variant, training, validation, output, epochs):
    random.seed(3104); np.random.seed(3104); torch.manual_seed(3104)
    output.mkdir(parents=True, exist_ok=True)
    model = SurfaceModel(); h, _ = base.data(training); vh, _ = base.data(validation)
    y = targets(training); vy = targets(validation)
    weights = [base.weights(training, labels) for labels in y]
    vw = [base.weights(validation, labels) for labels in vy]
    sampler = .6*weights[0]+.2*weights[1]+.2*weights[2]
    aug = transforms.Compose([transforms.RandomHorizontalFlip(), transforms.RandomVerticalFlip(),
        transforms.RandomAffine(35, translate=(.025, .025), scale=(.95, 1.05)), transforms.ColorJitter(.15, .15, .1, .02)])
    initial = {k: p.detach().clone() for k, p in model.features.named_parameters()}
    heads = list(model.surface.parameters())+list(model.visible.parameters())+list(model.contact.parameters())
    opt = torch.optim.AdamW(heads, lr=.001, weight_decay=.02)
    best = float('inf'); selected = None; selected_epoch = 0; history = []
    for epoch in range(3+epochs):
        started = time.perf_counter(); fine = epoch >= 3
        for name, par in model.features.named_parameters():
            par.requires_grad = fine and (variant == 'full' or int(name.split('.')[0]) >= 9)
        if epoch == 3:
            model.load_state_dict(selected)
            opt = torch.optim.AdamW([{'params': [p for p in model.features.parameters() if p.requires_grad], 'lr': .0001 if variant == 'full' else .0003},
                                     {'params': heads, 'lr': .0007}], weight_decay=.02)
        model.train()
        for module in model.modules():
            if isinstance(module, nn.BatchNorm2d): module.eval()
        ids = torch.multinomial(sampler, len(training), replacement=True).tolist(); losses = []
        for start in range(0, len(ids), 16):
            batch = ids[start:start+16]; opt.zero_grad(); images = torch.stack([aug(h[i]) for i in batch])
            outputs = model.logits(images); loss = outputs[0].sum()*0
            for coefficient, logits, labels, head_weights in zip([1., .25, .2], outputs, y, weights):
                valid = labels[batch] >= 0
                if valid.any():
                    ce = nn.functional.cross_entropy(logits, labels[batch], ignore_index=-100, reduction='none', label_smoothing=.02)
                    # Correct the shared sampler's positive bias for each independent head.
                    loss = loss+coefficient*(ce*head_weights[batch]/sampler[batch]).mean()
            loss.backward(); nn.utils.clip_grad_norm_(model.parameters(), 5); opt.step(); losses.append(float(loss.detach()))
        model.eval(); val = infer(model, vh); nll = [base.nll(x, target, w) for x, target, w in zip(val, vy, vw)]
        criterion = nll[0]+.25*nll[1]
        if criterion < best:
            best = criterion; selected = copy.deepcopy(model.state_dict()); selected_epoch = epoch+1
        entry = dict(epoch=epoch+1,phase='fineTune' if fine else 'head',trainingLoss=float(np.mean(losses)),
            validationSurfaceNLL=nll[0],validationVisibilityNLL=nll[1],validationContactNLL=nll[2],selectionLoss=criterion,seconds=time.perf_counter()-started)
        history.append(entry); (output/'history.json').write_text(json.dumps(history,indent=2)+'\n'); print(json.dumps(dict(variant=variant,**entry)),flush=True)
        if fine and epoch+1-selected_epoch >= 8: break
    model.load_state_dict(selected); model.eval(); val = infer(model, vh); calibration = []
    for i, (logits, labels, w) in enumerate(zip(val, vy, vw)):
        values = {str(t): base.nll(logits/t, labels, w) for t in [1., 1.25, 1.5, 2., 3., 4.]}
        temperature = float(min(values, key=values.get)); model.temperature[i] = temperature
        calibration.append(dict(temperature=temperature, validationNLL=values))
    coverage = {}
    for i, name in enumerate(SURFACES):
        valid = vy[0] == i
        coverage[name] = dict(crops=int(valid.sum()), videos=len({r['recordingId'] for (r, p), good in zip(validation, valid) if good}),
                             correct=int((val[0].argmax(1)[valid] == i).sum()))
    torch.save(model.state_dict(), output/'weights.pt')
    torch.jit.trace(model, torch.zeros(1, 3, 128, 128)).save(str(output/'GripSurfaceClassifier.pt'))
    receipt = dict(variant=variant, bestEpoch=selected_epoch, selectionLoss=best, epochsRun=len(history),
        surfaceCoverage=coverage, surfaceConfusion=confusion(val[0],vy[0]), visibilityConfusion=confusion(val[1],vy[1]), contactConfusion=confusion(val[2],vy[2]),
        calibration=calibration, modelSHA256=base.sha(output/'GripSurfaceClassifier.pt'),
        changedFeatureParameterTensors=sum(not torch.equal(p.detach(),initial[k]) for k,p in model.features.named_parameters()))
    (output/'candidate-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    return receipt


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['input', 'review', 'reserve', 'protocol', 'output']: p.add_argument(name, type=Path)
    a = p.parse_args(); assert not (a.output/'training-receipt.json').exists(); a.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2); torch.set_num_interop_threads(2)
    audit_path=a.input.parent/'coverage-before-training.json'
    audit=json.loads(audit_path.read_text())
    assert audit['inputSHA256']==base.sha(a.input) and audit['cohortReadyForFullGripRetraining']
    assert audit['minimumReadableGroupsPerFittingCell']==3
    rows = base.load(a.input); review = json.loads(a.review.read_text()); reserve = json.loads(a.reserve.read_text()); protocol = json.loads(a.protocol.read_text())
    assert review['outputSHA256'] == base.sha(a.input)
    refs = {(x['recordingId'], x['reviewId']): x for x in review['patches']}
    for r, patch in base.pairs(rows): assert patch['threewayReview'] == refs[r['recordingId'],patch['reviewId']]
    assert not {r['recordingId'] for r in rows} & {s['sha256'] for s in reserve['sources']}
    assert not {r['annotation'].get('sourceGroup') or ('recording:'+r['recordingId']) for r in rows} & {s['sourceGroup'] for s in reserve['sources']}
    groups = defaultdict(set)
    for r in rows: groups[r['annotation'].get('sourceGroup') or ('recording:'+r['recordingId'])].add(r['annotation']['split'])
    assert all(len(v) == 1 for v in groups.values())
    pairs = base.pairs(rows); training = [x for x in pairs if x[0]['annotation']['split'] == 'train']; validation = [x for x in pairs if x[0]['annotation']['split'] == 'validation']
    receipts = {variant: fit(variant, training, validation, a.output/variant, protocol['trainingBudget']['fineTuneEpochs']) for variant in ['full','late']}
    selected = min(receipts, key=lambda name: receipts[name]['selectionLoss']); chosen = receipts[selected]
    for name in ['weights.pt', 'GripSurfaceClassifier.pt']:
        (a.output/name).write_bytes((a.output/selected/name).read_bytes())
    coverage = chosen['surfaceCoverage']; gate = protocol['qualificationGate']; recalls = [v['correct']/v['crops'] if v['crops'] else 0 for v in coverage.values()]
    passed = all(v['crops'] >= gate['minimumValidationCropsPerClass'] and v['videos'] >= gate['minimumValidationVideosPerClass'] for v in coverage.values()) and min(recalls) >= gate['validationKnownSurfaceRecallPerClass'] and np.mean(recalls) >= gate['validationKnownSurfaceMacroRecall']
    receipt = dict(selectedVariant=selected, modelSHA256=base.sha(a.output/'GripSurfaceClassifier.pt'),
        coverageAuditSHA256=base.sha(audit_path),testReserveSHA256=base.sha(a.reserve), inputSHA256=base.sha(a.input), reviewSHA256=base.sha(a.review), protocolSHA256=base.sha(a.protocol),scriptSHA256=base.sha(__file__),
        candidates=receipts, surfaceValidationGatePassed=bool(passed), macroRecall=float(np.mean(recalls)),
        trainingCrops=len(training),validationCrops=len(validation),
        sources={split:sorted({r['recordingId'] for r in rows if r['annotation']['split']==split}) for split in ['train','validation']},
        sourceGroups={split:sorted({r['annotation'].get('sourceGroup') or ('recording:'+r['recordingId']) for r in rows if r['annotation']['split']==split}) for split in ['train','validation']},
        qualification='Supported front/rear RGB anatomy; equal-class/source-group loss per head with sampler importance correction; no orientation input; validation-only selection')
    (a.output/'training-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(dict(selected=selected,gatePassed=bool(passed),coverage=coverage)),flush=True)


if __name__ == '__main__': main()
