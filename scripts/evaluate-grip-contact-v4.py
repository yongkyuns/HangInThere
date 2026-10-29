#!/usr/bin/env python3
"""Frozen contact ablation with unchanged anatomical/visibility outputs and gates."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import importlib.util
import json
from pathlib import Path

import torch

spec = importlib.util.spec_from_file_location('contact_eval_training', Path(__file__).with_name('train-grip-contact-v4.py'))
train = importlib.util.module_from_spec(spec)
spec.loader.exec_module(train)
spec = importlib.util.spec_from_file_location('contact_eval_counts', Path(__file__).with_name('evaluate-grip-quality-v3.py'))
counts = importlib.util.module_from_spec(spec)
spec.loader.exec_module(counts)
base = train.base


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['input', 'review', 'model', 'baseline', 'freeze', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    assert not (args.output / 'report.json').exists()
    args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    freeze = json.loads(args.freeze.read_text())
    assert all(base.sha(path) == digest for path, digest in freeze['sha256'].items())
    rows = train.load(args.input, True)
    assert all(r['annotation']['split'] == 'test' for r in rows)
    review = json.loads(args.review.read_text())
    assert review['outputSHA256'] == base.sha(args.input)
    refs = {p['reviewId']: p for p in review['patches']}
    items = base.pairs(rows)
    assert all(p['contactReference'] == refs[p['contactReviewId']] for r, p in items)
    receipt = json.loads((args.model / 'training-receipt.json').read_text())
    fitting_groups = set.union(*(set(v['groups']) for v in receipt['roles'].values()))
    assert not fitting_groups & {train.quality.group(r) for r in rows}
    assert {r['recordingId'] for r in rows} <= set(freeze['freshTestRecordings'] + freeze['regressionTestRecordings'])
    previous = torch.jit.load(str(args.baseline / 'GripSurfaceClassifier.pt')).eval()
    models = {'previous': previous, **{k: torch.jit.load(str(args.model / k / 'GripSurfaceClassifier.pt')).eval() for k in receipt['candidates']}}
    tight, wide = train.tensors(items)
    values = {k: [] for k in models}
    parity, invariance = 0., 0.
    eager = train.ContactModel(train.quality.QualityModel(train.quality.surface.SurfaceModel(False), True), receipt['selectedVariant'])
    eager.load_state_dict(torch.load(args.model / 'weights.pt', weights_only=True))
    eager.eval()
    with torch.inference_mode():
        for start in range(0, len(tight), 16):
            t, w = tight[start:start + 16], wide[start:start + 16]
            original = previous(t)
            for name, model in models.items():
                output = original if name == 'previous' else model(t, w)
                values[name].extend(list(zip(*output)))
                invariance = max(invariance, float((output[0] - original[0]).abs().max()), float((output[1] - original[1]).abs().max()))
                if name == receipt['selectedVariant']:
                    check = eager(t, w)
                    parity = max(parity, *(float((a - b).abs().max()) for a, b in zip(check, output)))
    assert invariance == 0.
    assert parity < 1e-6
    references = []
    for r, p in items:
        ref = p['contactReference']
        references.append(dict(ref, surface=ref.get('surface', p.get('threewayReview', {}).get('surface', 'unclear'))))
    metrics = {name: counts.gate_metrics(value, references) for name, value in values.items()}
    per_video = {}
    for name, xs in values.items():
        videos = defaultdict(Counter)
        for (r, p), ref, v in zip(items, references, xs):
            if ref['qualityEligible'] and ref['contact'] != 'uncertain':
                videos[r['recordingId']]['positive' if ref['contact'] == 'closed' else 'negative'] += 1
                if float(v[2][1]) >= .8:
                    videos[r['recordingId']]['truePositive' if ref['contact'] == 'closed' else 'falsePositive'] += 1
            if max(v[0]) >= .8 and v[1][1] >= .8 and v[2][1] >= .8:
                videos[r['recordingId']]['fullGateAccepted'] += 1
        per_video[name] = dict(videos)
    records = []
    for index, ((r, p), ref) in enumerate(zip(items, references)):
        records.append(dict(reviewId=p['contactReviewId'], caseId=r['caseId'], recordingId=r['recordingId'], patch=p['patch'], contactCrop=p['contactCrop']['image'], reference=ref,
                            results={name: dict(surface=xs[index][0].tolist(), visible=xs[index][1].tolist(), contact=xs[index][2].tolist()) for name, xs in values.items()}))
    report = dict(qualification='Contact-only comparison, appearance/visibility unchanged. Previously unscored reserve is first-score evidence; earlier tests are regression only. Agent reviews, not independent human gold labels.',
                  inputSHA256=base.sha(args.input), reviewSHA256=base.sha(args.review), freezeSHA256=base.sha(args.freeze), scriptSHA256=base.sha(__file__), selectedVariant=receipt['selectedVariant'],
                  thresholds=dict(surface=.8, visible=.8, contact=.8), population=dict(videos=len({r['recordingId'] for r in rows}), frames=len(rows), handSlots=sum(len(r['patches']) for r in rows), crops=len(items)),
                  metrics=metrics, perVideo=per_video, anatomyVisibilityMaximumDifference=invariance, eagerExportMaximumDifference=parity,
                  missingStatus=dict(Counter(p['status'] for r in rows for p in r['patches'] if p['status'] != 'patchReady')))
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    (args.output / 'predictions.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in records))
    print(json.dumps({k: v for k, v in report.items() if k != 'perVideo'}, indent=2))


if __name__ == '__main__':
    main()
