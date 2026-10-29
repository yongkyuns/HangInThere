#!/usr/bin/env python3
"""Repair quality supervision without changing the frozen anatomical classifier."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import copy
import importlib.util
import json
from pathlib import Path

import numpy as np
import torch
from torch import nn

spec = importlib.util.spec_from_file_location('surface_quality_base', Path(__file__).with_name('train-grip-surface-v2.py'))
surface = importlib.util.module_from_spec(spec)
spec.loader.exec_module(surface)
base = surface.base


def group(row):
    return row['annotation'].get('sourceGroup') or 'recording:' + row['recordingId']


def quality_weights(items, labels, balanced):
    """Each video contributes equally; optionally each binary class does too."""
    keys = [(int(y), group(r)) if balanced else group(r) for (r, _), y in zip(items, labels)]
    counts = Counter(k for k, y in zip(keys, labels) if y >= 0)
    class_groups = defaultdict(set)
    for (r, _), y in zip(items, labels):
        if y >= 0:
            class_groups[int(y)].add(group(r))
    weights = torch.tensor([0. if y < 0 else 1. / counts[k] / (len(class_groups[int(y)]) if balanced else 1.)
                            for k, y in zip(keys, labels)])
    assert weights.sum() > 0
    return weights / weights.sum()


def weighted_loss(logits, labels, weights):
    return (nn.functional.cross_entropy(logits, labels, ignore_index=-100, reduction='none') * weights).sum()


def calibrated_binary(logits, slope, bias):
    positive = torch.sigmoid((logits[:, 1] - logits[:, 0]) * slope + bias)
    return torch.stack((1 - positive, positive), 1)


def guarded_calibration(logits, labels, slope, bias):
    """A high positive prior must not manufacture evidence on known negatives."""
    negative = labels == 0
    assert negative.any(), 'Calibration needs reviewed negative examples'
    raw_false = int(((logits.softmax(1)[:, 1] >= .8) & negative).sum())
    proposed_false = int(((calibrated_binary(logits, slope, bias)[:, 1] >= .8) & negative).sum())
    rejected = proposed_false > raw_false
    return dict(slope=1. if rejected else slope, bias=0. if rejected else bias,
                rejected=rejected, rawFalseAccepts=raw_false, proposedFalseAccepts=proposed_false,
                reason='Affine calibration increases false accepts on reviewed calibration negatives' if rejected else 'No increase in reviewed calibration-negative false accepts')


def fit_calibration(logits, labels, weights):
    # A positive slope preserves ranking; the intercept can repair prior shift.
    logits = logits.detach().double()
    labels = labels.detach()
    weights = weights.detach().double()
    valid = labels >= 0
    raw_slope = torch.tensor(.5413248546, dtype=torch.float64, requires_grad=True)
    bias = torch.tensor(0., dtype=torch.float64, requires_grad=True)
    optimizer = torch.optim.LBFGS([raw_slope, bias], max_iter=100, line_search_fn='strong_wolfe')

    def closure():
        optimizer.zero_grad()
        slope = nn.functional.softplus(raw_slope)
        z = (logits[:, 1] - logits[:, 0]) * slope + bias
        loss = (nn.functional.binary_cross_entropy_with_logits(z[valid], labels[valid].double(), reduction='none') * weights[valid]).sum()
        loss = loss + .02 * ((slope - 1).square() + bias.square())
        loss.backward()
        return loss

    optimizer.step(closure)
    return float(nn.functional.softplus(raw_slope).detach()), float(bias.detach())


class QualityModel(nn.Module):
    def __init__(self, original, multiscale):
        super().__init__()
        self.appearance = original
        self.multiscale = multiscale
        dimensions = 648 if multiscale else 576
        self.quality = nn.ModuleList([nn.Sequential(nn.Linear(dimensions, 32), nn.Hardswish(), nn.Dropout(.2), nn.Linear(32, 2)) for _ in range(2)])
        self.register_buffer('feature_mean', torch.zeros(1, dimensions))
        self.register_buffer('feature_std', torch.ones(1, dimensions))
        self.register_buffer('slope', torch.ones(2))
        self.register_buffer('bias', torch.zeros(2))
        for p in self.appearance.parameters():
            p.requires_grad = False

    def encode(self, image):
        x = (image - self.appearance.mean) / self.appearance.std
        scales = []
        for i, block in enumerate(self.appearance.features):
            x = block(x)
            if self.multiscale and (i == 3 or i == 8):
                scales.append(self.appearance.pool(x).flatten(1))
        embedding = self.appearance.pool(x).flatten(1)
        scales.append(embedding)
        return embedding, torch.cat(scales, 1)

    def forward(self, image):
        embedding, features = self.encode(image)
        features = (features - self.feature_mean) / self.feature_std
        anatomical = (self.appearance.surface(embedding) / self.appearance.temperature[0]).softmax(1)
        visible = calibrated_binary(self.quality[0](features), self.slope[0], self.bias[0])
        contact = calibrated_binary(self.quality[1](features), self.slope[1], self.bias[1])
        return anatomical, visible, contact


def summarize(prob, labels):
    valid = labels >= 0
    positive = labels == 1
    negative = labels == 0
    pred = prob[:, 1] >= .8
    return dict(positive=int(positive.sum()), negative=int(negative.sum()), truePositive=int((pred & positive).sum()),
                falsePositive=int((pred & negative).sum()), trueNegative=int((~pred & negative).sum()), falseNegative=int((~pred & positive).sum()),
                nll=float(nn.functional.nll_loss(prob[valid].clamp_min(1e-8).log(), labels[valid])),
                brier=float(((prob[valid, 1] - labels[valid].float()) ** 2).mean()))


def fit_candidate(name, original, sets, output, budget):
    torch.manual_seed(budget['seed'])
    model = QualityModel(copy.deepcopy(original), name == 'multi-scale').eval()
    output.mkdir(parents=True, exist_ok=True)
    features = {}
    targets = {}
    for role, items in sets.items():
        images, _ = base.data(items)
        with torch.inference_mode():
            parts = [model.encode(images[i:i + 16])[1] for i in range(0, len(images), 16)]
        features[role] = torch.cat(parts).clone()
        targets[role] = surface.targets(items)[1:]
    model.feature_mean.copy_(features['train'].mean(0, keepdim=True))
    model.feature_std.copy_(features['train'].std(0, keepdim=True).clamp_min(.05))
    for role in features:
        features[role] = ((features[role] - model.feature_mean) / model.feature_std).detach()
    receipts = []
    for index, head in enumerate(model.quality):
        labels = targets['train'][index]
        train_w = quality_weights(sets['train'], labels, True)
        val_y = targets['selection'][index]
        val_w = quality_weights(sets['selection'], val_y, True)
        assert set(labels[labels >= 0].tolist()) == set(val_y[val_y >= 0].tolist()) == {0, 1}
        optimizer = torch.optim.AdamW(head.parameters(), lr=budget['learningRate'], weight_decay=budget['weightDecay'])
        history = []
        best = float('inf')
        state = None
        best_epoch = 0
        for epoch in range(1, budget['epochs'] + 1):
            head.train()
            optimizer.zero_grad()
            loss = weighted_loss(head(features['train']), labels, train_w)
            loss.backward()
            nn.utils.clip_grad_norm_(head.parameters(), 5)
            optimizer.step()
            head.eval()
            with torch.inference_mode():
                selection = float(weighted_loss(head(features['selection']), val_y, val_w))
            history.append(dict(epoch=epoch, trainNLL=float(loss.detach()), selectionNLL=selection))
            if selection < best:
                best, best_epoch, state = selection, epoch, copy.deepcopy(head.state_dict())
            if epoch - best_epoch >= budget['patience']:
                break
        head.load_state_dict(state)
        head.eval()
        calibration_y = targets['calibration'][index]
        assert set(calibration_y[calibration_y >= 0].tolist()) == {0, 1}
        with torch.no_grad():
            logits = head(features['calibration'])
        slope, bias = fit_calibration(logits, calibration_y, quality_weights(sets['calibration'], calibration_y, False))
        guard = guarded_calibration(logits, calibration_y, slope, bias)
        slope, bias = guard['slope'], guard['bias']
        model.slope[index], model.bias[index] = slope, bias
        metrics = {}
        with torch.inference_mode():
            for role in ['selection', 'calibration']:
                z = head(features[role])
                metrics[role] = dict(raw=summarize(z.softmax(1), targets[role][index]), calibrated=summarize(calibrated_binary(z, slope, bias), targets[role][index]))
        receipts.append(dict(head=['visible', 'contact'][index], bestEpoch=best_epoch, selectionNLL=best, slope=slope, bias=bias, calibrationGuard=guard, history=history, metrics=metrics))
        print(json.dumps(dict(candidate=name, head=receipts[-1]['head'], bestEpoch=best_epoch, selectionNLL=best, slope=slope, bias=bias, metrics=metrics)), flush=True)
    model.eval()
    torch.save(model.state_dict(), output / 'weights.pt')
    torch.jit.trace(model, torch.zeros(1, 3, 128, 128)).save(str(output / 'GripSurfaceClassifier.pt'))
    result = dict(candidate=name, selectionNLL=sum(r['selectionNLL'] for r in receipts) / 2, heads=receipts,
                  modelSHA256=base.sha(output / 'GripSurfaceClassifier.pt'))
    (output / 'candidate-receipt.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['input', 'review', 'protocol', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    assert not (args.output / 'training-receipt.json').exists()
    args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    protocol = json.loads(args.protocol.read_text())
    assert base.sha(args.input) == protocol['fitSHA256']
    assert base.sha(protocol['baseWeights']) == protocol['baseWeightsSHA256']
    rows = base.load(args.input)
    review = json.loads(args.review.read_text())
    assert review['outputSHA256'] == base.sha(args.input)
    refs = {(p['recordingId'], p['reviewId']): p for p in review['patches']}
    pairs = base.pairs(rows)
    assert all(p['threewayReview'] == refs[r['recordingId'], p['reviewId']] for r, p in pairs)
    validation_groups = sorted({group(r) for r, _ in pairs if r['annotation']['split'] == 'validation'}, key=lambda g: __import__('hashlib').sha256(g.encode()).hexdigest())
    selection_groups = set(validation_groups[::2])
    sets = {'train': [(r, p) for r, p in pairs if r['annotation']['split'] == 'train'],
            'selection': [(r, p) for r, p in pairs if r['annotation']['split'] == 'validation' and group(r) in selection_groups],
            'calibration': [(r, p) for r, p in pairs if r['annotation']['split'] == 'validation' and group(r) not in selection_groups]}
    group_sets = {role: {group(r) for r, _ in items} for role, items in sets.items()}
    assert not group_sets['train'] & (group_sets['selection'] | group_sets['calibration'])
    assert not group_sets['selection'] & group_sets['calibration']
    for role, items in sets.items():
        print(json.dumps(dict(role=role, crops=len(items), groups=len(group_sets[role]))), flush=True)
    original = surface.SurfaceModel(False)
    original.load_state_dict(torch.load(protocol['baseWeights'], weights_only=True))
    original.eval()
    candidates = {name: fit_candidate(name, original, sets, args.output / name, protocol['budget']) for name in protocol['candidates']}
    selected = min(candidates, key=lambda name: candidates[name]['selectionNLL'])
    for name in ['weights.pt', 'GripSurfaceClassifier.pt']:
        (args.output / name).write_bytes((args.output / selected / name).read_bytes())
    receipt = dict(selectedVariant=selected, candidates=candidates, modelSHA256=base.sha(args.output / 'GripSurfaceClassifier.pt'),
                   weightsSHA256=base.sha(args.output / 'weights.pt'), baseWeightsSHA256=protocol['baseWeightsSHA256'], protocolSHA256=base.sha(args.protocol),
                   scriptSHA256=base.sha(__file__), fittingSHA256=base.sha(args.input), reviewSHA256=base.sha(args.review),
                   qualityRoles={role: dict(crops=len(sets[role]), groups=sorted(group_sets[role])) for role in sets},
                   thresholds=protocol['confidence'], qualification='Frozen appearance encoder/head; quality-only retraining and calibration. No test inputs used for fitting or selection; no phone deployment.')
    (args.output / 'training-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('selected', selected, flush=True)


if __name__ == '__main__':
    main()
