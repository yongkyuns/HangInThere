#!/usr/bin/env python3
"""Train contact-only candidates with frozen anatomy/visibility and fixed gates."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import copy
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

spec = importlib.util.spec_from_file_location('contact_quality_base', Path(__file__).with_name('train-grip-quality-v3.py'))
quality = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality)
base = quality.base


def load(path, allow_test=False):
    rows = [json.loads(line) for line in Path(path).read_text().splitlines()]
    for row in rows:
        assert allow_test or row['annotation']['split'] in ['train', 'validation']
        assert base.sha(Path(row['framesDirectory']) / row['image']) == row['imageSHA256']
        for patch in row['patches']:
            if patch['status'] != 'patchReady':
                continue
            ref, crop = patch['contactReference'], patch['contactCrop']
            assert ref['recordingId'] == row['recordingId'] and ref['imageSHA256'] == row['imageSHA256']
            assert ref['patchSHA256'] == base.sha(patch['patch']) == patch['patchSHA256']
            assert ref['contactSHA256'] == base.sha(crop['image']) == crop['sha256']
            assert ref['box'] == crop['box'] and ref['side'] == patch['side']
            assert ref['reviewId'] == patch['contactReviewId'] and ref['split'] == row['annotation']['split']
    return rows


def targets(items):
    return torch.tensor([int(p['contactReference']['contact'] == 'closed') if p['contactReference']['qualityEligible'] and p['contactReference']['contact'] != 'uncertain' else -100 for r, p in items])


def training_weights(items):
    counts = Counter((p['contactReference']['contact'], quality.group(r)) for r, p in items if p['contactReference']['qualityEligible'])
    groups = defaultdict(set)
    for r, p in items:
        ref = p['contactReference']
        if ref['qualityEligible']:
            groups[ref['contact']].add(quality.group(r))
    assert set(groups) == {'closed', 'open', 'invalid'}
    masses = {'closed': .5, 'open': .25, 'invalid': .25}
    weights = torch.tensor([masses[p['contactReference']['contact']] / counts[p['contactReference']['contact'], quality.group(r)] / len(groups[p['contactReference']['contact']])
                            if p['contactReference']['qualityEligible'] else 0. for r, p in items])
    return weights / weights.sum()


def validation_partition(items, seed):
    grouped = defaultdict(Counter)
    for r, p in items:
        ref = p['contactReference']
        if ref['qualityEligible']:
            grouped[quality.group(r)][ref['contact']] += 1
    groups = sorted({quality.group(r) for r, p in items})
    totals = np.array([sum(c[label] for c in grouped.values()) for label in ['closed', 'open', 'invalid']])
    assert (totals > 0).all()
    rng = random.Random(seed)
    best = None
    for _ in range(4096):
        selection = set(rng.sample(groups, (len(groups) + 1) // 2))
        counts = np.array([sum(grouped[g][label] for g in selection) for label in ['closed', 'open', 'invalid']])
        if (counts == 0).any() or (counts == totals).any():
            continue
        loss = float(np.square((counts - totals / 2) / totals).sum())
        key = loss, tuple(sorted(selection))
        if best is None or key < best[0]:
            best = key, selection
    assert best is not None
    return best[1]


class ContactModel(nn.Module):
    def __init__(self, original, variant):
        super().__init__()
        self.quality = original
        self.use_wide = variant != 'tight-control'
        self.wide_features = copy.deepcopy(original.appearance.features)
        self.contact = nn.Sequential(nn.Linear(1296 if self.use_wide else 648, 32), nn.Hardswish(), nn.Dropout(.2), nn.Linear(32, 2))
        dimensions = 1296 if self.use_wide else 648
        self.register_buffer('feature_mean', torch.zeros(1, dimensions))
        self.register_buffer('feature_std', torch.ones(1, dimensions))
        self.register_buffer('slope', torch.ones(()))
        self.register_buffer('bias', torch.zeros(()))
        for p in self.quality.parameters():
            p.requires_grad = False
        for p in self.wide_features.parameters():
            p.requires_grad = False

    def encode_wide(self, image):
        x = (image - self.quality.appearance.mean) / self.quality.appearance.std
        pieces = []
        for i, block in enumerate(self.wide_features):
            x = block(x)
            if i == 3 or i == 8 or i == 12:
                pieces.append(self.quality.appearance.pool(x).flatten(1))
        return torch.cat(pieces, 1)

    def contact_logits(self, tight_features, wide):
        features = torch.cat((tight_features, self.encode_wide(wide)), 1) if self.use_wide else tight_features
        return self.contact((features - self.feature_mean) / self.feature_std)

    def forward(self, image, wide):
        embedding, tight = self.quality.encode(image)
        anatomical = (self.quality.appearance.surface(embedding) / self.quality.appearance.temperature[0]).softmax(1)
        normalized = (tight - self.quality.feature_mean) / self.quality.feature_std
        visible = quality.calibrated_binary(self.quality.quality[0](normalized), self.quality.slope[0], self.quality.bias[0])
        contact = quality.calibrated_binary(self.contact_logits(tight, wide), self.slope, self.bias)
        return anatomical, visible, contact


def tensors(items):
    tensor = transforms.ToTensor()
    return (torch.stack([tensor(Image.open(p['patch']).convert('RGB')) for r, p in items]),
            torch.stack([tensor(Image.open(p['contactCrop']['image']).convert('RGB')) for r, p in items]))


def infer_logits(model, tight, wide):
    output = []
    with torch.inference_mode():
        for i in range(0, len(wide), 16):
            output.append(model.contact_logits(tight[i:i + 16], wide[i:i + 16]))
    return torch.cat(output)


def fit(name, original, sets, data, output, budget):
    torch.manual_seed(budget['seed'])
    random.seed(budget['seed'])
    model = ContactModel(copy.deepcopy(original), name).eval()
    output.mkdir(parents=True, exist_ok=True)
    features, tight_features = {}, {}
    y = {role: targets(items) for role, items in sets.items()}
    for role, (tight, wide) in data.items():
        with torch.inference_mode():
            t = torch.cat([model.quality.encode(tight[i:i + 16])[1] for i in range(0, len(tight), 16)])
            w = torch.cat([model.encode_wide(wide[i:i + 16]) for i in range(0, len(wide), 16)]) if model.use_wide else None
        tight_features[role] = t.clone()
        features[role] = torch.cat((t, w), 1).clone() if model.use_wide else t.clone()
    model.feature_mean.copy_(features['train'].mean(0, keepdim=True))
    model.feature_std.copy_(features['train'].std(0, keepdim=True).clamp_min(.05))
    features = {role: ((f - model.feature_mean) / model.feature_std).detach() for role, f in features.items()}
    tw = training_weights(sets['train'])
    sw = quality.quality_weights(sets['selection'], y['selection'], True)
    optimizer = torch.optim.AdamW(model.contact.parameters(), lr=budget['headLearningRate'], weight_decay=budget['weightDecay'])
    history, best, best_epoch, state = [], float('inf'), 0, None
    for epoch in range(1, budget['headEpochs'] + 1):
        model.contact.train()
        optimizer.zero_grad()
        loss = quality.weighted_loss(model.contact(features['train']), y['train'], tw)
        loss.backward()
        nn.utils.clip_grad_norm_(model.contact.parameters(), 5)
        optimizer.step()
        model.eval()
        with torch.inference_mode():
            selection = float(quality.weighted_loss(model.contact(features['selection']), y['selection'], sw))
        history.append(dict(phase='head', epoch=epoch, trainNLL=float(loss.detach()), selectionNLL=selection))
        if selection < best:
            best, best_epoch, state = selection, epoch, copy.deepcopy(model.state_dict())
        if epoch - best_epoch >= budget['headPatience']:
            break
    model.load_state_dict(state)
    if name == 'tight-wide-finetune':
        for key, p in model.wide_features.named_parameters():
            p.requires_grad = int(key.split('.')[0]) >= 9
        optimizer = torch.optim.AdamW([{'params': [p for p in model.wide_features.parameters() if p.requires_grad], 'lr': budget['fineTuneEncoderLearningRate']},
                                       {'params': model.contact.parameters(), 'lr': budget['fineTuneHeadLearningRate']}], weight_decay=budget['weightDecay'])
        aug = transforms.Compose([transforms.RandomHorizontalFlip(), transforms.RandomVerticalFlip(), transforms.RandomAffine(12, translate=(.01, .01)), transforms.ColorJitter(.1, .1, .08, .01)])
        best_fine = 0
        for epoch in range(1, budget['fineTuneEpochs'] + 1):
            started = time.perf_counter()
            model.eval()
            model.contact.train()
            model.wide_features.train()
            for module in model.wide_features.modules():
                if isinstance(module, nn.BatchNorm2d):
                    module.eval()
            ids = torch.multinomial(tw, len(sets['train']), replacement=True).tolist()
            losses = []
            for start in range(0, len(ids), 16):
                batch = ids[start:start + 16]
                optimizer.zero_grad()
                wide = torch.stack([aug(data['train'][1][i]) for i in batch])
                logits = model.contact_logits(tight_features['train'][batch], wide)
                loss = nn.functional.cross_entropy(logits, y['train'][batch])
                loss.backward()
                nn.utils.clip_grad_norm_([p for p in model.parameters() if p.requires_grad], 5)
                optimizer.step()
                losses.append(float(loss.detach()))
            model.eval()
            val = infer_logits(model, tight_features['selection'], data['selection'][1])
            selection = float(quality.weighted_loss(val, y['selection'], sw))
            entry = dict(phase='fineTune', epoch=epoch, trainNLL=float(np.mean(losses)), selectionNLL=selection, seconds=time.perf_counter() - started)
            history.append(entry)
            print(json.dumps(dict(candidate=name, **entry)), flush=True)
            if selection < best:
                best, best_epoch, best_fine, state = selection, 'fine:' + str(epoch), epoch, copy.deepcopy(model.state_dict())
            if epoch - best_fine >= budget['fineTunePatience']:
                break
        model.load_state_dict(state)
    model.eval()
    calibration_logits = infer_logits(model, tight_features['calibration'], data['calibration'][1])
    slope, bias = quality.fit_calibration(calibration_logits, y['calibration'], quality.quality_weights(sets['calibration'], y['calibration'], True))
    guard = quality.guarded_calibration(calibration_logits, y['calibration'], slope, bias)
    model.slope.fill_(guard['slope'])
    model.bias.fill_(guard['bias'])
    metrics = {}
    for role in ['selection', 'calibration']:
        logits = infer_logits(model, tight_features[role], data[role][1])
        metrics[role] = dict(raw=quality.summarize(logits.softmax(1), y[role]), calibrated=quality.summarize(quality.calibrated_binary(logits, model.slope, model.bias), y[role]))
    torch.save(model.state_dict(), output / 'weights.pt')
    torch.jit.trace(model, (torch.zeros(1, 3, 128, 128), torch.zeros(1, 3, 192, 192))).save(str(output / 'GripSurfaceClassifier.pt'))
    receipt = dict(variant=name, bestEpoch=best_epoch, selectionNLL=best, calibration=dict(proposedSlope=slope, proposedBias=bias, guard=guard), metrics=metrics,
                   modelSHA256=base.sha(output / 'GripSurfaceClassifier.pt'), history=history)
    (output / 'candidate-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print(json.dumps({k: v for k, v in receipt.items() if k != 'history'}), flush=True)
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['input', 'review', 'protocol', 'reserve', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    assert not (args.output / 'training-receipt.json').exists()
    args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    protocol = json.loads(args.protocol.read_text())
    assert base.sha(args.input) == protocol['fittingSHA256'] and base.sha(args.review) == protocol['reviewSHA256']
    assert base.sha(protocol['baseWeights']) == protocol['baseWeightsSHA256'] and base.sha(args.reserve) == protocol['reserveSHA256']
    rows = load(args.input)
    review = json.loads(args.review.read_text())
    assert review['outputSHA256'] == base.sha(args.input)
    refs = {p['reviewId']: p for p in review['patches']}
    items = base.pairs(rows)
    assert all(p['contactReference'] == refs[p['contactReviewId']] for r, p in items)
    reserved = json.loads(args.reserve.read_text())['sources']
    assert not {quality.group(r) for r in rows} & {s['sourceGroup'] for s in reserved}
    assert not {r['recordingId'] for r in rows} & {s['sha256'] for s in reserved}
    validation = [(r, p) for r, p in items if r['annotation']['split'] == 'validation']
    selection = validation_partition(validation, protocol['budget']['seed'])
    sets = dict(train=[(r, p) for r, p in items if r['annotation']['split'] == 'train'],
                selection=[(r, p) for r, p in validation if quality.group(r) in selection],
                calibration=[(r, p) for r, p in validation if quality.group(r) not in selection])
    roles = {role: dict(crops=len(xs), groups=sorted({quality.group(r) for r, p in xs}), labels=dict(Counter(p['contactReference']['contact'] for r, p in xs if p['contactReference']['qualityEligible']))) for role, xs in sets.items()}
    assert not set(roles['train']['groups']) & (set(roles['selection']['groups']) | set(roles['calibration']['groups']))
    assert not set(roles['selection']['groups']) & set(roles['calibration']['groups'])
    print(json.dumps(roles), flush=True)
    original = quality.QualityModel(quality.surface.SurfaceModel(False), True)
    original.load_state_dict(torch.load(protocol['baseWeights'], weights_only=True))
    original.eval()
    data = {role: tensors(xs) for role, xs in sets.items()}
    candidates = {name: fit(name, original, sets, data, args.output / name, protocol['budget']) for name in protocol['candidates']}
    selected = min(candidates, key=lambda k: candidates[k]['selectionNLL'])
    for name in ['weights.pt', 'GripSurfaceClassifier.pt']:
        (args.output / name).write_bytes((args.output / selected / name).read_bytes())
    receipt = dict(selectedVariant=selected, candidates=candidates, roles=roles, protocolSHA256=base.sha(args.protocol), fittingSHA256=base.sha(args.input), reviewSHA256=base.sha(args.review),
                   reserveSHA256=base.sha(args.reserve), baseWeightsSHA256=protocol['baseWeightsSHA256'], modelSHA256=base.sha(args.output / 'GripSurfaceClassifier.pt'), scriptSHA256=base.sha(__file__),
                   qualification='Contact-only candidates; anatomy/visibility frozen; validation-only model selection and calibration; no test pixels/scores used', thresholds=protocol['confidence'])
    (args.output / 'training-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    print('selected', selected, flush=True)


if __name__ == '__main__':
    main()
