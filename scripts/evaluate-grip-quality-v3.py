#!/usr/bin/env python3
"""Compare frozen quality gates with unchanged surface predictions and thresholds."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
import argparse
from collections import Counter
import importlib.util
import json
from pathlib import Path

import torch

spec = importlib.util.spec_from_file_location('quality_eval_base', Path(__file__).with_name('train-grip-quality-v3.py'))
quality = importlib.util.module_from_spec(spec)
spec.loader.exec_module(quality)
base = quality.base


def gate_metrics(values, references):
    result = dict(visibility=Counter(), contact=Counter(), appearanceGate=Counter(), fullQualityGate=Counter())
    for (surface, visible, contact), ref in zip(values, references):
        known = ref['surface'] in quality.surface.SURFACES
        closed = ref['contact'] == 'closed'
        eligible = ref.get('qualityEligible', True)
        raw = quality.surface.SURFACES[int(surface.argmax())]
        for name, positive, confidence, measured in [('visibility', known, float(visible[1]), True), ('contact', closed, float(contact[1]), eligible)]:
            if not measured:
                result[name]['masked'] += 1
                continue
            accepted = confidence >= .8
            result[name]['truePositive' if positive and accepted else 'falseNegative' if positive else 'falsePositive' if accepted else 'trueNegative'] += 1
        appearance_pass = float(surface.max()) >= .8 and float(visible[1]) >= .8
        for name, accepted in [('appearanceGate', appearance_pass), ('fullQualityGate', appearance_pass and float(contact[1]) >= .8)]:
            bucket = result[name]
            bucket['crops'] += 1
            bucket['known'] += known
            bucket['rawCorrect'] += known and raw == ref['surface']
            if not accepted:
                bucket['rejected'] += 1
                continue
            bucket['accepted'] += 1
            bucket['acceptedCorrect' if known and raw == ref['surface'] else 'acceptedWrong' if known else 'acceptedUnverifiable'] += 1
            bucket['acceptedNonContact'] += not closed
            bucket['acceptedIdentityUnverified'] += not eligible
            bucket['acceptedUsableCorrect' if known and raw == ref['surface'] and closed and eligible else 'acceptedUnsafe'] += 1
    return result


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
    assert all(base.sha(p) == digest for p, digest in freeze['sha256'].items())
    rows = base.load(args.input, True)
    assert all(r['annotation']['split'] == 'test' for r in rows)
    review = json.loads(args.review.read_text())
    assert review['outputSHA256'] == base.sha(args.input)
    refs = {(p['recordingId'], p['reviewId']): p for p in review['patches']}
    pairs = base.pairs(rows)
    assert all(p['threewayReview'] == refs[r['recordingId'], p['reviewId']] for r, p in pairs)
    receipt = json.loads((args.model / 'training-receipt.json').read_text())
    fit_groups = set.union(*(set(role['groups']) for role in receipt['qualityRoles'].values()))
    assert not fit_groups & {quality.group(r) for r in rows}
    allowed = set(freeze['freshTestRecordings']) | set(freeze['regressionTestRecordings'])
    assert {r['recordingId'] for r in rows} <= allowed
    new = torch.jit.load(str(args.model / 'GripSurfaceClassifier.pt')).eval()
    previous = torch.jit.load(str(args.baseline / 'GripSurfaceClassifier.pt')).eval()
    eager = quality.QualityModel(quality.surface.SurfaceModel(False), receipt['selectedVariant'] == 'multi-scale')
    eager.load_state_dict(torch.load(args.model / 'weights.pt', weights_only=True))
    eager.eval()
    images, _ = base.data(pairs)
    values = {'previous': [], 'new': []}
    anatomical_difference = 0.
    export_difference = 0.
    with torch.inference_mode():
        for i in range(0, len(images), 16):
            batch = images[i:i + 16]
            old_output, output, check = previous(batch), new(batch), eager(batch)
            anatomical_difference = max(anatomical_difference, float((old_output[0] - output[0]).abs().max()))
            export_difference = max(export_difference, *(float((a - b).abs().max()) for a, b in zip(output, check)))
            for name, tensors in [('previous', old_output), ('new', output)]:
                values[name].extend(list(zip(*tensors)))
    assert anatomical_difference == 0., 'Quality repair changed anatomical probabilities'
    assert export_difference < 1e-6
    references = [p['threewayReview'] for _, p in pairs]
    records = []
    for index, (r, p) in enumerate(pairs):
        records.append(dict(reviewId=p['reviewId'], caseId=r['caseId'], recordingId=r['recordingId'], patch=p['patch'], reference=references[index],
                            results={name: dict(surface=xs[index][0].tolist(), visible=xs[index][1].tolist(), contact=xs[index][2].tolist()) for name, xs in values.items()}))
    report = dict(qualification='Appearance unchanged; quality-only repair. Old holdout is regression evidence; new reserve previously unscored. Two agent crop-review passes, not independent human gold labels.',
                  inputSHA256=base.sha(args.input), reviewSHA256=base.sha(args.review), modelSHA256=base.sha(args.model / 'GripSurfaceClassifier.pt'), baselineSHA256=base.sha(args.baseline / 'GripSurfaceClassifier.pt'),
                  freezeSHA256=base.sha(args.freeze), scriptSHA256=base.sha(__file__), thresholds=dict(surface=.8, visible=.8, contact=.8),
                  population=dict(videos=len({r['recordingId'] for r in rows}), frames=len(rows), handSlots=sum(len(r['patches']) for r in rows), crops=len(pairs)),
                  metrics={name: gate_metrics(xs, references) for name, xs in values.items()}, missingStatus=dict(Counter(p['status'] for r in rows for p in r['patches'] if p['status'] != 'patchReady')),
                  anatomicalMaximumDifference=anatomical_difference, eagerExportMaximumDifference=export_difference)
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    (args.output / 'predictions.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in records))
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
