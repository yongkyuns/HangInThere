#!/usr/bin/env python3
"""Train separately supervised hand appearance, torso view and face evidence.

No grip loss or grip label is passed to either orientation network. Appearance
targets are derived from reviewed front/rear view and grip labels; oblique and
unknown views are excluded. Test files are scored only after weights are saved.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
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
from torchvision import models, transforms

SPEC = importlib.util.spec_from_file_location('context_base', Path(__file__).with_name('train-grip-context.py'))
base = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(base)
APPEARANCE = ['palm', 'dorsum']
VIEWS = base.VIEWS


def appearance_target(annotation):
    """A derived target, not a claim of independent human palm annotation."""
    view = annotation.get('viewpoint')
    if view not in ['front', 'rear']:
        return None
    palm = (annotation['label'] == 'overhand') == (view == 'front')
    return 0 if palm else 1


def fuse(appearance, orientation, context_mask):
    front = orientation[:, 0] + .5 * orientation[:, 2]
    rear = orientation[:, 1] + .5 * orientation[:, 2]
    over = appearance[:, 0] * front + appearance[:, 1] * rear
    # Missing selected-athlete context has no assumed front/rear direction.
    over = over * context_mask + .5 * (1 - context_mask)
    return torch.stack([over, 1 - over], 1)


def orientation_fusion(body, face, cues):
    # Mix probabilities, so unbounded torso logits cannot swamp the face branch.
    # A missing face contributes NO rear-view vote. A visible face is evidence,
    # not a compulsory front label; the face classifier also learns oblique views.
    face_weight = .75 * cues[:, 0:1] * cues[:, 5:6]
    return body * (1 - face_weight) + face * face_weight


class ImageHead(nn.Module):
    def __init__(self, classes, pretrained=True):
        super().__init__()
        self.features = models.mobilenet_v3_small(
            weights=models.MobileNet_V3_Small_Weights.IMAGENET1K_V1 if pretrained else None).features
        self.pool = nn.AdaptiveAvgPool2d(1)
        self.head = nn.Sequential(nn.Linear(576, 128), nn.Hardswish(), nn.Dropout(.25), nn.Linear(128, classes))
        self.register_buffer('mean', torch.tensor([.485, .456, .406]).reshape(1, 3, 1, 1))
        self.register_buffer('std', torch.tensor([.229, .224, .225]).reshape(1, 3, 1, 1))

    def embed(self, image):
        return self.pool(self.features((image - self.mean) / self.std)).flatten(1)

    def forward(self, image):
        return self.head(self.embed(image))


class Components(nn.Module):
    def __init__(self, pretrained=True):
        super().__init__()
        self.appearance = ImageHead(2, pretrained)
        self.body = ImageHead(3, pretrained)
        self.face = nn.Sequential(nn.Linear(6, 16), nn.Hardswish(), nn.Linear(16, 3))

    def components(self, hand, context, cues):
        a = self.appearance(hand).softmax(1)
        b = self.body(context).softmax(1)
        f = self.face(cues).softmax(1)
        view = orientation_fusion(b, f, cues)
        return fuse(a, view, cues[:, 5]), view, a, b, f

    def forward(self, hand, context, cues):
        scores, view, _, _, _ = self.components(hand, context, cues)
        return scores, view


def examples(rows, kind):
    out = []
    if kind == 'appearance':
        for row, patch in base.available(rows):
            target = appearance_target(row['annotation'])
            if target is not None:
                out.append(dict(row=row, patch=patch, target=target))
    else:
        # Exactly once per frame, including frames without an eligible hand.
        for row in rows:
            view = row['annotation'].get('viewpoint')
            if base.base.in_window(row) and row['context']['status'] == 'ready' and view in VIEWS:
                out.append(dict(row=row, target=VIEWS.index(view)))
    return out


def sampling_weights(items):
    counts = Counter((x['target'], x['row']['recordingId']) for x in items)
    sources = defaultdict(set)
    for x in items:
        sources[x['target']].add(x['row']['recordingId'])
    weights = torch.tensor([1 / (counts[x['target'], x['row']['recordingId']] * len(sources[x['target']])) for x in items])
    return weights / weights.sum()


def balanced_nll(logits, items):
    losses = nn.functional.cross_entropy(logits, torch.tensor([x['target'] for x in items]), reduction='none')
    return float((losses * sampling_weights(items)).sum())


def input_tensor(item, kind):
    row = item['row']
    if kind == 'face':
        return torch.tensor(row['context']['faceCues'], dtype=torch.float32)
    path = item['patch']['patch'] if kind == 'appearance' else row['context']['image']
    return transforms.ToTensor()(Image.open(path).convert('RGB'))


def batched(module, data):
    return torch.cat([module(data[i:i+16]) for i in range(0, len(data), 16)])


def fit(kind, model, rows, args):
    items = examples(rows, kind)
    training = [x for x in items if x['row']['annotation']['split'] == 'train']
    validation = [x for x in items if x['row']['annotation']['split'] == 'validation']
    assert training and validation
    x = torch.stack([input_tensor(i, kind) for i in training])
    vx = torch.stack([input_tensor(i, kind) for i in validation])
    y = torch.tensor([i['target'] for i in training])
    weights = sampling_weights(training)
    history = []
    best = float('inf')
    best_state = None
    best_epoch = None
    original = {k: p.detach().clone() for k, p in model.named_parameters() if k.startswith('features.')}
    model.eval()
    if kind != 'face':
        with torch.no_grad():
            cached = batched(model.embed, x).clone()
            vcached = batched(model.embed, vx).clone()
    total_epochs = 160 if kind == 'face' else args.warmup + args.epochs
    optimizer = torch.optim.AdamW(model.parameters() if kind == 'face' else model.head.parameters(), lr=.003, weight_decay=.01)
    augmentation = transforms.Compose([
        transforms.RandomAffine(10, translate=(.025, .025), scale=(.95, 1.05)),
        transforms.ColorJitter(.15, .15, .12, .02)])
    phase_best = 0
    for epoch in range(total_epochs):
        started = time.perf_counter()
        fine = kind != 'face' and epoch >= args.warmup
        if kind != 'face' and epoch == args.warmup:
            model.load_state_dict(best_state)
            optimizer = torch.optim.AdamW([
                {'params': model.features.parameters(), 'lr': .00005},
                {'params': model.head.parameters(), 'lr': .0003}], weight_decay=.01)
            phase_best = epoch
        model.train()
        for layer in model.modules():
            if isinstance(layer, nn.BatchNorm2d):
                layer.eval()
        if kind == 'face':
            optimizer.zero_grad()
            losses = nn.functional.cross_entropy(model(x), y, reduction='none')
            loss = (losses * weights).sum()
            loss.backward()
            optimizer.step()
            train_loss = float(loss.detach())
        else:
            ids = torch.multinomial(weights, len(training), replacement=True).tolist()
            losses = []
            for offset in range(0, len(ids), 16):
                batch = ids[offset:offset+16]
                optimizer.zero_grad()
                logits = model(torch.stack([augmentation(x[i]) for i in batch])) if fine else model.head(cached[batch])
                loss = nn.functional.cross_entropy(logits, y[batch])
                loss.backward()
                nn.utils.clip_grad_norm_(model.parameters(), 5.)
                optimizer.step()
                losses.append(float(loss.detach()))
            train_loss = float(np.mean(losses))
        model.eval()
        with torch.inference_mode():
            logits = batched(model, vx) if kind == 'face' or fine else batched(model.head, vcached)
            val = balanced_nll(logits, validation)
        if val < best:
            best = val
            best_state = copy.deepcopy(model.state_dict())
            best_epoch = epoch + 1
            phase_best = epoch
            torch.save(best_state, args.output / (kind + '-weights.pt'))
        entry = dict(component=kind, epoch=epoch+1, phase='fineTune' if fine else 'head',
                     trainingNLL=train_loss, validationSourceClassBalancedNLL=val,
                     seconds=time.perf_counter()-started)
        history.append(entry)
        if kind != 'face' or epoch % 20 == 0:
            print(json.dumps(entry), flush=True)
        (args.output / (kind + '-history.json')).write_text(json.dumps(history, indent=2) + '\n')
        if fine and epoch - phase_best >= 3:
            break
    model.load_state_dict(best_state)
    model.eval()
    with torch.inference_mode():
        logits = batched(model, vx)
    classes = APPEARANCE if kind == 'appearance' else VIEWS
    by_class = defaultdict(Counter)
    for item, predicted in zip(validation, logits.argmax(1).tolist()):
        by_class[classes[item['target']]]['correct' if predicted == item['target'] else 'wrong'] += 1
    changed = sum(not torch.equal(p.detach(), original[k]) for k, p in model.named_parameters() if k in original)
    return dict(bestEpoch=best_epoch, bestValidationNLL=best, epochsRun=len(history),
                checkpointSelection='component label NLL, equal classes/equal videos within class; validation only',
                changedFeatureParameterTensors=changed, trainingExamples=len(training), validationExamples=len(validation),
                trainingClassCounts=dict(Counter(classes[x['target']] for x in training)),
                validationClassCounts=dict(Counter(classes[x['target']] for x in validation)),
                validationAccuracy=by_class,
                trainingSources=sorted({i['row']['recordingId'] for i in training}),
                validationSources=sorted({i['row']['recordingId'] for i in validation}))


def train(args):
    assert not (args.output / 'training-receipt.json').exists(), 'Use a new output directory; preserve frozen runs'
    rows = base.load(args.input, args.patch_review)
    assert all(r['annotation']['split'] in ['train', 'validation'] for r in rows), 'Test data cannot enter training'
    assert all('faceDetector' in r['context'] for r in rows), 'Verified face data required'
    torch.manual_seed(31)
    np.random.seed(31)
    random.seed(31)
    model = Components()
    receipt = {}
    for kind in ['appearance', 'body', 'face']:
        receipt[kind] = fit(kind, getattr(model, kind), rows, args)
        (args.output / 'component-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    model.eval()
    torch.save(model.state_dict(), args.output / 'weights.pt')
    inputs = (torch.zeros(1, 3, 128, 128), torch.zeros(1, 3, 128, 128), torch.zeros(1, 6))
    traced = torch.jit.trace(model, inputs)
    traced.save(str(args.output / 'GripContextClassifier.pt'))
    receipt.update(variant='supervisedComponents', seed=31, inputSHA256=hashlib.sha256(args.input.read_bytes()).hexdigest(),
                   scriptSHA256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                   parameters=sum(p.numel() for p in model.parameters()),
                   faceWeightWhenVisible=.75, missingFace='no orientation vote', missingContext='uniform grip',
                   appearanceTargets='derived from grip plus reviewed front/rear view; no oblique targets',
                   batchNorm='pretrained running statistics fixed; affine trained during fine-tuning',
                   sources={s: sorted({r['recordingId'] for r in rows if r['annotation']['split'] == s}) for s in ['train', 'validation']},
                   trainingGroups=sorted({r['annotation']['sourceGroup'] for r in rows if r['annotation']['split'] == 'train' and r['annotation'].get('sourceGroup')}),
                   trainingPatchSHA256=sorted(p['patchSHA256'] for r, p in base.available(rows) if r['annotation']['split'] == 'train' and appearance_target(r['annotation']) is not None),
                   modelSHA256=hashlib.sha256((args.output / 'GripContextClassifier.pt').read_bytes()).hexdigest())
    (args.output / 'training-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps(receipt), flush=True)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('--patch-review', type=Path)
    p.add_argument('--warmup', type=int, default=12)
    p.add_argument('--epochs', type=int, default=8)
    args = p.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    train(args)


if __name__ == '__main__':
    main()
