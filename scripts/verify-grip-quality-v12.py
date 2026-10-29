#!/usr/bin/env python3
"""Verify real-data roles, synthetic ancestry and exported frozen components."""
import hashlib
import importlib.util
import json
from collections import Counter
from pathlib import Path

import torch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('experiment', ROOT / 'scripts/grip-quality-robustness-v12.py')
e = importlib.util.module_from_spec(spec)
spec.loader.exec_module(e)


def main():
    torch.set_num_threads(2)
    freeze = e.check_protocol()
    train, val = e.data()
    assert not {p['patchSHA256'] for _, p in train} & {p['patchSHA256'] for _, p in val}
    train_by_id = {p['threewayReview']['anatomyReviewId']: (r, p) for r, p in train}
    val_ids = {p['threewayReview']['anatomyReviewId'] for _, p in val}
    manifest = json.loads((e.OUT / 'synthetic-manifest.json').read_text())
    assert len(manifest) == 402
    parents = Counter(row['parent'] for row in manifest)
    assert len(parents) == 201 and set(parents.values()) == {2}
    assert not set(parents) & val_ids
    assert Counter(x['family'] for x in manifest) == dict(defocus=201, washedBlur=201)
    synthetic_items = []
    for entry in manifest:
        item = train_by_id[entry['parent']]
        r, p = item
        assert entry['split'] == r['annotation']['split'] == 'train'
        assert entry['target'] == 0 and p['threewayReview']['surface'] in e.s.SURFACES
        assert entry['sourceGroup'] == e.group(item)
        assert entry['parentPatch'] == p['patch'] and entry['parentSHA256'] == p['patchSHA256']
        image = e.corrupt(e.Image.open(p['patch']).convert('RGB'), entry['family'])
        assert hashlib.sha256(image.tobytes()).hexdigest() == entry['corruptedRGBSHA256']
        synthetic_items.append(item)
    # Corrupt copies do not increase a source group's total sampling mass.
    sy = torch.zeros(len(synthetic_items), dtype=torch.long)
    sw = e.s.group_weights(synthetic_items, sy)
    mass = Counter()
    for item, weight in zip(synthetic_items, sw):
        mass[e.group(item)] += float(weight)
    assert max(mass.values()) - min(mass.values()) < 1e-6
    review = json.loads((e.OUT / 'preview-review.json').read_text())
    for name, digest in review['previewHashes'].items():
        assert e.sha(e.OUT / name) == digest
    assert review['protocolSHA256'] == e.sha(e.OUT / 'protocol.json')
    assert review['manifestSHA256'] == e.sha(e.OUT / 'synthetic-manifest.json')
    before = e.new_model()
    vh, _ = e.s.base.data(val)
    initial_predictions = e.s.infer(before, vh, True)
    results = json.loads((e.OUT / 'comparison.json').read_text())
    for arm in ['realOnly', 'synthetic']:
        receipt = json.loads((e.OUT / arm / 'receipt.json').read_text())
        assert receipt['protocolSHA256'] == e.sha(e.OUT / 'protocol.json')
        assert receipt['modelSHA256'] == e.sha(e.OUT / arm / 'GripSurfaceClassifier.pt')
        assert receipt['weightsSHA256'] == e.sha(e.OUT / arm / 'weights.pt')
        model = e.new_model()
        model.load_state_dict(torch.load(e.OUT / arm / 'weights.pt', weights_only=True))
        for key, value in model.state_dict().items():
            if not key.startswith('visible.') and key != 'temperature':
                assert torch.equal(value, before.state_dict()[key]), key
        assert torch.equal(model.temperature[[0, 2]], before.temperature[[0, 2]])
        exported = torch.jit.load(str(e.OUT / arm / 'GripSurfaceClassifier.pt')).eval()
        native_prob = e.s.infer(model, vh, True)
        jit_prob = e.s.infer(exported, vh, True)
        assert all(torch.equal(a, b) for a, b in zip(native_prob, jit_prob))
        assert torch.equal(jit_prob[0], initial_predictions[0])
        assert torch.equal(jit_prob[2], initial_predictions[2])
        score = e.acceptance(jit_prob, val)
        assert score == results['results'][arm]['reviewedReferences']
        quality = results['results'][arm]['quality']
        assert quality['known'] + quality['unreadableOrMixed'] == 218
        # Explicitly record the negative result: both arms selected epoch zero.
        assert receipt['bestEpoch'] == 0 and receipt['epochsRun'] == 8
        assert all(torch.equal(a, b) for a, b in zip(jit_prob, initial_predictions))
    evidence = [e.INPUT, e.INITIAL, e.RESERVE, e.OUT / 'protocol.json', e.OUT / 'synthetic-manifest.json',
                e.OUT / 'preview-review.json', e.OUT / 'comparison.json', Path(__file__)]
    for arm in ['realOnly', 'synthetic']:
        evidence += [e.OUT / arm / name for name in ['weights.pt', 'GripSurfaceClassifier.pt', 'receipt.json']]
    report = dict(realTrainingCropsVerified=len(train), realValidationCropsVerified=len(val),
        syntheticCopiesVerified=len(manifest), syntheticParents=len(parents),
        noValidationPatchesInSyntheticTraining=True, noSourceGroupSplitLeakage=True,
        duplicateCopiesDoNotIncreaseSourceGroupMass=True, torchscriptRoundTripBitIdentical=True,
        anatomyAndContactFrozen=True, bothSelectedModelsBitIdenticalToBaseline=True,
        inputMetadataAndReferenceHashesVerified=True, confidenceThresholdsUnchanged=True,
        finalReserveManifestUnchanged=True, finalReserveInspected=False, enabledInApp=False,
        hashes={str(path): e.sha(path) for path in evidence},
        historicalV7PinsVerified=results['historicalV7PinsVerified'],
        historicalV9AndV11OutputsUnchanged=results['historicalV9AndV11OutputsUnchanged'])
    e.write(e.OUT / 'verification.json', report)
    print(json.dumps({key: value for key, value in report.items() if key != 'hashes'}, indent=2))


if __name__ == '__main__':
    main()
