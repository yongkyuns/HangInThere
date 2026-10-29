#!/usr/bin/env python3
"""Bounded development experiment: synthetic quality negatives, frozen anatomy.

No new test evidence is produced. Corrupted copies retain their parent's role
and source group. Only the existing two-class readability head may change.
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

import numpy as np
from PIL import Image, ImageDraw, ImageFilter
import torch
from torch import nn

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/grip-quality-robustness-v12'
INPUT = ROOT / 'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl'
INITIAL = ROOT / 'build/grip-anatomy-v11/frozenQuality/weights.pt'
RESERVE = ROOT / 'build/grip-front-rear-v9/combined-final-reserve.json'
FAMILIES = ['defocus', 'washedBlur']
TEMPERATURES = [1., 1.25, 1.5, 2., 3., 4.]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


s = module('surface_v12', 'train-grip-front-rear-v9.py')
evaluation = module('score_v12', 'evaluate-grip-anatomy-v11.py')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, value):
    assert not path.exists(), 'Evidence must not be overwritten: ' + str(path)
    path.write_text(json.dumps(value, indent=2) + '\n')


def group(item):
    row, _ = item
    return row['annotation'].get('sourceGroup') or 'recording:' + row['recordingId']


def corrupt(image, family):
    """Fixed severe corruptions at the model's 128-pixel input resolution."""
    if family == 'defocus':
        return image.filter(ImageFilter.GaussianBlur(12))
    if family == 'clippedExposure':
        a = np.asarray(image, dtype=np.float32) / 255
        return Image.fromarray((np.clip((a - .2) * 3 + .85, 0, 1) * 255).round().astype('uint8'))
    if family == 'washedBlur':
        a = np.asarray(image.filter(ImageFilter.GaussianBlur(6)), dtype=np.float32) / 255
        return Image.fromarray(((a * .12 + .86) * 255).round().astype('uint8'))
    raise ValueError(family)


def data():
    rows = s.base.load(INPUT)
    items = s.base.pairs(rows)
    train = [x for x in items if x[0]['annotation']['split'] == 'train']
    val = [x for x in items if x[0]['annotation']['split'] == 'validation']
    assert len(train) == 526 and len(val) == 218
    roles = defaultdict(set)
    for item in items:
        roles[group(item)].add(item[0]['annotation']['split'])
    assert all(len(x) == 1 for x in roles.values())
    reserve = json.loads(RESERVE.read_text())
    assert not {group(x) for x in items} & {x['sourceGroup'] for x in reserve['sources']}
    assert not {r['recordingId'] for r, _ in items} & {x['sha256'] for x in reserve['sources']}
    return train, val


def new_model():
    model = s.SurfaceModel(False)
    model.load_state_dict(torch.load(INITIAL, weights_only=True))
    model.eval()
    for p in model.parameters():
        p.requires_grad = False
    return model


def prepare():
    OUT.mkdir(exist_ok=False)
    train, val = data()
    clear = [x for x in train if x[1]['threewayReview']['surface'] in s.SURFACES]
    assert len(clear) == 201
    dependencies = [Path(__file__), ROOT / 'scripts/train-grip-front-rear-v9.py',
                    ROOT / 'scripts/train-threeway-grip.py', ROOT / 'scripts/evaluate-grip-anatomy-v11.py']
    freeze = dict(
        purpose=__doc__, inputSHA256=sha(INPUT), initialWeightsSHA256=sha(INITIAL),
        reserveManifestSHA256=sha(RESERVE), scripts={str(p): sha(p) for p in dependencies},
        seed=4115, trainingCrops=len(train), validationCrops=len(val), syntheticParents=len(clear),
        syntheticCopies=len(clear) * len(FAMILIES), syntheticFamilies=FAMILIES,
        recipes=dict(defocus='PIL GaussianBlur radius 12 at 128x128',
                     washedBlur='GaussianBlur radius 6, then RGB x * 0.12 + 0.86, uint8 rounded'),
        rejectedBeforeTraining='Exposure clipping alone retained readable side anatomy in the training preview; proposal-01 archived, never fitted.',
        supervision='All 201 reviewed clear TRAIN crops only; synthetic target is unambiguous-anatomy=0. Original labels and all 526 real train crops retained.',
        arms=dict(realOnly='Class/group balanced: 0.5 clear, 0.5 real unclear/mixed',
                  synthetic='0.5 clear, 0.25 real unclear/mixed, 0.25 corrupted. Equal source groups, parent crops and corruption families within synthetic mass.'),
        budget=dict(maxEpochs=80, patience=8, drawsPerEpoch=526, batchSize=16,
                    learningRate=.0001, weightDecay=.02, labelSmoothing=.02),
        selection='Each arm: minimum class/source-group balanced NLL on all 218 real validation crops, including starting checkpoint. No selection on two known false accepts.',
        calibrationTemperatures=TEMPERATURES, thresholds=dict(surface=.8, visible=.8, contact=.8, grip=.8),
        frozen='Encoder, anatomy, contact, normalization and their temperatures. Only visible MLP and visible temperature can change.',
        criteria='Report good acceptance, wrong known acceptance, unclear/mixed/noncontact acceptance, source groups and full denominators. Rejecting everything is not a win.',
        limits='Development validation reused for checkpoint/calibration, agent labels, severe simulated quality defects are not a fresh real-world quality benchmark.',
        finalReserveInspected=False, fullGripCohortReady=False, enabledInApp=False)
    write(OUT / 'protocol.json', freeze)
    manifest = []
    for item in clear:
        r, p = item
        for family in FAMILIES:
            image = corrupt(Image.open(p['patch']).convert('RGB'), family)
            manifest.append(dict(parent=p['threewayReview']['anatomyReviewId'],
                recordingId=r['recordingId'], sourceGroup=group(item), split='train',
                parentPatch=p['patch'], parentSHA256=p['patchSHA256'], family=family,
                corruptedRGBSHA256=hashlib.sha256(image.tobytes()).hexdigest(), target=0))
    write(OUT / 'synthetic-manifest.json', manifest)
    # Deterministic class/group-stratified preview, selected without model scores.
    rng = np.random.default_rng(4115)
    preview = []
    for surface in s.SURFACES:
        candidates = [x for x in clear if x[1]['threewayReview']['surface'] == surface]
        grouped = defaultdict(list)
        for item in candidates:
            grouped[group(item)].append(item)
        groups = sorted(grouped)
        rng.shuffle(groups)
        for key in groups[:4]:
            preview.append(grouped[key][int(rng.integers(len(grouped[key])))])
    assert len(preview) == 12
    review = []
    for sheet in range(2):
        canvas = Image.new('RGB', (880, 1120), '#eeeeee')
        draw = ImageDraw.Draw(canvas)
        for j, (r, p) in enumerate(preview[sheet*6:(sheet+1)*6]):
            ident = p['threewayReview']['anatomyReviewId']
            original = Image.open(p['patch']).convert('RGB')
            for col, family in enumerate(['original'] + FAMILIES):
                image = original if family == 'original' else corrupt(original, family)
                left, top = col * 220 + 10, j * 184 + 25
                canvas.paste(image.resize((150, 150)), (left, top))
                draw.text((left, top - 17), ident + ' ' + family, fill='black')
            review.append(dict(id=ident, surface=p['threewayReview']['surface'],
                               patch=p['patch'], patchSHA256=p['patchSHA256'], sourceGroup=group((r, p))))
        canvas.save(OUT / f'training-negative-preview-{sheet+1}.jpg', quality=93)
    write(OUT / 'preview-index.json', review)
    print(json.dumps(dict(prepared=True, syntheticCopies=len(manifest), previewParents=len(preview))))


def check_protocol():
    freeze = json.loads((OUT / 'protocol.json').read_text())
    assert sha(INPUT) == freeze['inputSHA256'] and sha(INITIAL) == freeze['initialWeightsSHA256']
    assert sha(RESERVE) == freeze['reserveManifestSHA256']
    for path, digest in freeze['scripts'].items():
        assert sha(path) == digest, path
    return freeze


def embed(model, images):
    with torch.inference_mode():
        result = torch.cat([model.embed(images[i:i+16]) for i in range(0, len(images), 16)])
    return result.clone()


def fit(head, x, y, weights, vx, vy, vw, budget):
    head = copy.deepcopy(head)
    for p in head.parameters():
        p.requires_grad = True
    head.eval()
    with torch.no_grad():
        best = s.base.nll(head(vx), vy, vw)
    state = copy.deepcopy(head.state_dict())
    best_epoch = 0
    history = [dict(epoch=0, validationNLL=best)]
    opt = torch.optim.AdamW(head.parameters(), lr=budget['learningRate'], weight_decay=budget['weightDecay'])
    sampler_rng = torch.Generator().manual_seed(4115)
    sample_plans = []
    # Same RNG seed, draw count and update budget in both arms; datasets differ.
    torch.manual_seed(4115)
    for epoch in range(1, budget['maxEpochs'] + 1):
        head.train()
        indices = torch.multinomial(weights, budget['drawsPerEpoch'], replacement=True, generator=sampler_rng)
        sample_plans.append(indices.tolist())
        for start in range(0, len(indices), budget['batchSize']):
            ids = indices[start:start+budget['batchSize']]
            opt.zero_grad()
            loss = nn.functional.cross_entropy(head(x[ids]), y[ids], label_smoothing=budget['labelSmoothing'])
            loss.backward()
            opt.step()
        head.eval()
        with torch.no_grad():
            nll = s.base.nll(head(vx), vy, vw)
        history.append(dict(epoch=epoch, validationNLL=nll))
        if nll < best:
            best, state, best_epoch = nll, copy.deepcopy(head.state_dict()), epoch
        if epoch - best_epoch >= budget['patience']:
            break
    head.load_state_dict(state)
    head.eval()
    with torch.no_grad():
        logits = head(vx)
    calibration = {str(t): s.base.nll(logits / t, vy, vw) for t in TEMPERATURES}
    return head, dict(bestEpoch=best_epoch, epochsRun=len(history)-1, history=history,
        calibration=calibration, temperature=float(min(calibration, key=calibration.get)),
        samplePlanSHA256=hashlib.sha256(json.dumps(sample_plans).encode()).hexdigest())


def train_models():
    freeze = check_protocol()
    review = json.loads((OUT / 'preview-review.json').read_text())
    assert review['allPreviewNegativesUnclear'] and review['protocolSHA256'] == sha(OUT / 'protocol.json')
    assert review['manifestSHA256'] == sha(OUT / 'synthetic-manifest.json')
    train, val = data()
    h, _ = s.base.data(train)
    vh, _ = s.base.data(val)
    y = s.targets(train)[1]
    vy = s.targets(val)[1]
    model = new_model()
    x, vx = embed(model, h), embed(model, vh)
    manifest = json.loads((OUT / 'synthetic-manifest.json').read_text())
    assert len(manifest) == 402
    patch_to_item = {p['patch']: item for item in train for p in [item[1]]}
    synth_images, synth_items = [], []
    for row in manifest:
        item = patch_to_item[row['parentPatch']]
        assert row['split'] == 'train' and group(item) == row['sourceGroup']
        assert sha(row['parentPatch']) == row['parentSHA256']
        image = corrupt(Image.open(row['parentPatch']).convert('RGB'), row['family'])
        assert hashlib.sha256(image.tobytes()).hexdigest() == row['corruptedRGBSHA256']
        synth_images.append(s.base.transforms.ToTensor()(image))
        synth_items.append(item)
    sx = embed(model, torch.stack(synth_images))
    del synth_images
    sy = torch.zeros(len(sx), dtype=torch.long)
    real_weights = s.group_weights(train, y)
    synthetic_real_weights = real_weights.clone()
    synthetic_real_weights[y == 0] *= .5
    assert abs(float(synthetic_real_weights[y == 1].sum()) - .5) < 1e-6
    synthetic_weights = s.group_weights(synth_items, sy) * .25
    vw = s.group_weights(val, vy)
    for arm in ['realOnly', 'synthetic']:
        folder = OUT / arm
        folder.mkdir(exist_ok=False)
        xx, yy, weights = (x, y, real_weights) if arm == 'realOnly' else (
            torch.cat([x, sx]), torch.cat([y, sy]), torch.cat([synthetic_real_weights, synthetic_weights]))
        assert abs(float(weights.sum()) - 1) < 1e-6
        head, receipt = fit(model.visible, xx, yy, weights, vx, vy, vw, freeze['budget'])
        candidate = new_model()
        candidate.visible.load_state_dict(head.state_dict())
        candidate.temperature[1] = receipt['temperature']
        candidate.eval()
        before = model.state_dict()
        for key, value in candidate.state_dict().items():
            if not key.startswith('visible.') and key != 'temperature':
                assert torch.equal(value, before[key]), key
        assert torch.equal(candidate.temperature[[0, 2]], model.temperature[[0, 2]])
        torch.save(candidate.state_dict(), folder / 'weights.pt')
        torch.jit.trace(candidate, torch.zeros(1, 3, 128, 128)).save(str(folder / 'GripSurfaceClassifier.pt'))
        receipt.update(protocolSHA256=sha(OUT / 'protocol.json'), previewReviewSHA256=sha(OUT / 'preview-review.json'),
                       weightsSHA256=sha(folder / 'weights.pt'), modelSHA256=sha(folder / 'GripSurfaceClassifier.pt'),
                       onlyReadabilityHeadChanged=True, realTrainingInputs=len(train), syntheticInputs=0 if arm == 'realOnly' else len(manifest))
        write(folder / 'receipt.json', receipt)
        print(json.dumps(dict(arm=arm, bestEpoch=receipt['bestEpoch'], epochsRun=receipt['epochsRun'], temperature=receipt['temperature'])), flush=True)


def acceptance(prob, items):
    accepted = (prob[0].max(1).values >= .8) & (prob[1][:, 1] >= .8) & (prob[2][:, 1] >= .8)
    details = []
    groups = defaultdict(Counter)
    wrong = 0
    for i, (r, p) in enumerate(items):
        if not accepted[i]:
            continue
        ref = p['threewayReview']
        known = ref['surface'] in s.SURFACES
        predicted = s.SURFACES[int(prob[0][i].argmax())]
        bad = not known or ref['contact'] != 'closed'
        is_wrong = known and predicted != ref['surface'] and ref['contact'] == 'closed'
        wrong += int(is_wrong)
        outcome = 'badQuality' if bad else 'wrongAnatomy' if is_wrong else 'correctClosed'
        groups[group((r, p))][outcome] += 1
        details.append(dict(id=ref['anatomyReviewId'], patch=p['patch'], sourceGroup=group((r, p)),
                            surface=ref['surface'], category=ref['anatomyReviewCategory'], prediction=predicted,
                            surfaceConfidence=float(prob[0][i].max()), readability=float(prob[1][i, 1]),
                            contact=float(prob[2][i, 1]), outcome=outcome))
    scored = evaluation.score(prob, items)
    assert scored['acceptedAnatomyAndQuality'] == scored['acceptedCorrectClosed'] + scored['acceptedUnclearMixedOrNoncontact'] + wrong
    return dict(**scored, acceptedWrongKnownClosed=wrong, acceptedSourceGroups={k: dict(v) for k, v in groups.items()}, acceptedDetails=details)


def evaluate():
    check_protocol()
    _, val = data()
    vh, _ = s.base.data(val)
    model = new_model()
    initial_state = model.state_dict()
    initial = s.infer(model, vh, True)
    vy = s.targets(val)[1]
    vw = s.group_weights(val, vy)
    results = {}
    for arm in ['baseline', 'realOnly', 'synthetic']:
        candidate = model if arm == 'baseline' else new_model()
        if arm != 'baseline':
            candidate.load_state_dict(torch.load(OUT / arm / 'weights.pt', weights_only=True))
        candidate.eval()
        prob = s.infer(candidate, vh, True)
        assert torch.equal(prob[0], initial[0]) and torch.equal(prob[2], initial[2])
        for key, value in candidate.state_dict().items():
            if not key.startswith('visible.') and key != 'temperature':
                assert torch.equal(initial_state[key], value), key
        known = vy == 1
        readable = prob[1][:, 1] >= .8
        result = dict(reviewedReferences=acceptance(prob, val), originalReferences=evaluation.score(prob, val, True),
            anatomyAndContactPredictionsBitIdentical=True,
            quality=dict(realInputs=len(val), known=int(known.sum()), unreadableOrMixed=int((~known).sum()),
                readableKnown=int((readable & known).sum()), readableUnclearOrMixed=int((readable & ~known).sum()),
                groupBalancedCalibratedNLL=s.base.nll(prob[1].log(), vy, vw)))
        results[arm] = result
    baseline = results['baseline']['reviewedReferences']
    assert baseline['acceptedCorrectClosed'] == 12 and baseline['acceptedUnclearMixedOrNoncontact'] == 2
    bad_ids = {x['id'] for x in baseline['acceptedDetails'] if x['outcome'] == 'badQuality'}
    assert bad_ids == {'H338', 'H573'}
    results['knownWashedOutFrame'] = {}  # No per-frame checkpoint selection.
    for arm in ['baseline', 'realOnly', 'synthetic']:
        candidate = new_model()
        if arm != 'baseline':
            candidate.load_state_dict(torch.load(OUT / arm / 'weights.pt', weights_only=True))
        prob = s.infer(candidate, vh, True)
        results['knownWashedOutFrame'][arm] = {
            p['threewayReview']['anatomyReviewId']: dict(readability=float(prob[1][i, 1]), passesReadability=bool(prob[1][i, 1] >= .8))
            for i, (_, p) in enumerate(val) if p['threewayReview']['anatomyReviewId'] in bad_ids}
    # Confirm earlier frozen artifacts, without decoding reserve images.
    v7 = json.loads((ROOT / 'build/grip-oracle-v7/final/inference-freeze.json').read_text())
    for path, digest in v7['sha256'].items():
        assert sha(path) == digest, path
    v9 = json.loads((ROOT / 'build/grip-front-rear-v9/completion-receipt.json').read_text())
    for path, digest in v9['outputs'].items():
        assert sha(path) == digest, path
    old = json.loads((ROOT / 'build/grip-anatomy-v11/verification.json').read_text())
    for path, digest in old['outputHashes'].items():
        assert sha(path) == digest, path
    report = dict(protocolSHA256=sha(OUT / 'protocol.json'), results=results,
                  historicalV7PinsVerified=len(v7['sha256']), historicalV9AndV11OutputsUnchanged=True,
                  confidenceThresholdsUnchanged=True, finalReserveInspected=False,
                  fullGripCohortReady=False, enabledInApp=False,
                  qualification='Repeated development validation only. No grip, video, bilateral or fresh-test accuracy claim.')
    write(OUT / 'comparison.json', report)
    print(json.dumps({arm: dict(accepted=results[arm]['reviewedReferences']['acceptedAnatomyAndQuality'],
        correct=results[arm]['reviewedReferences']['acceptedCorrectClosed'],
        badQuality=results[arm]['reviewedReferences']['acceptedUnclearMixedOrNoncontact'],
        wrong=results[arm]['reviewedReferences']['acceptedWrongKnownClosed'], quality=results[arm]['quality'])
        for arm in ['baseline', 'realOnly', 'synthetic']}, indent=2))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['prepare', 'train', 'evaluate'])
    args = parser.parse_args()
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    torch.manual_seed(4115)
    {'prepare': prepare, 'train': train_models, 'evaluate': evaluate}[args.mode]()


if __name__ == '__main__':
    main()
