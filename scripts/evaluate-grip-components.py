#!/usr/bin/env python3
"""Compare frozen components on development-exposed, video-disjoint cohorts.

The known unverified source stays visible in predictions but is excluded from
binary accuracy, including its missing-hand denominator. No model is fitted here.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np
import torch

ROOT = Path(__file__).absolute().parents[1]
SPEC = importlib.util.spec_from_file_location('components', ROOT / 'scripts/train-grip-components.py')
learning = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(learning)


def add_metric(metric, scores, expected):
    metric['expectedHands'] += 1
    if scores is None:
        metric['missingPatch'] += 1
        metric['unknown'] += 1
        return
    prediction = int(np.argmax(scores))
    correct = learning.base.LABELS[prediction] == expected
    metric['availablePatches'] += 1
    metric['rawCorrect' if correct else 'rawWrong'] += 1
    if max(scores) >= .8:
        metric['acceptedCorrect' if correct else 'acceptedWrong'] += 1
    else:
        metric['unknown'] += 1
        metric['lowScore'] += 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--data', type=Path, default=ROOT / 'build/grip-context')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    data = args.data
    path = args.model / 'GripContextClassifier.pt'
    receipt = json.loads((args.model / 'training-receipt.json').read_text())
    assert receipt['modelSHA256'] == hashlib.sha256(path.read_bytes()).hexdigest()
    model = torch.jit.load(str(path)).eval()
    old_path = ROOT / 'build/grip-context/oriented-model/GripContextClassifier.pt'
    old = torch.jit.load(str(old_path)).eval()
    review_path = ROOT / 'Evaluation/fixtures/grip-patch-failure-audit-v1.json'
    review = json.loads(review_path.read_text())
    excluded = {s['caseId'] for s in review['sources'] if s['status'] == 'unverifiedBinaryReference'}
    review_patches = {p['patchSHA256']: p for p in review['patches']}
    found_review = set()
    used = set(sum(receipt['sources'].values(), []))
    summary = defaultdict(Counter)
    cases = defaultdict(Counter)
    component_metrics = defaultdict(Counter)
    manifests = []
    predictions = []
    frame_metrics_seen = set()
    max_reconstruction_difference = 0.
    for cohort, name, patch_review in [
        ('fitting', 'verified-train-validation.jsonl', 'training-exclusions.json'),
        ('older', 'verified-development-test-corrected.jsonl', 'development-exclusions.json'),
        ('additional', 'verified-final-test.jsonl', None)]:
        manifest = data / name
        rows = learning.base.load(manifest, data / patch_review if patch_review else None)
        if cohort == 'fitting':
            assert hashlib.sha256(manifest.read_bytes()).hexdigest() == receipt['inputSHA256']
        if cohort != 'fitting':
            assert not used & {r['recordingId'] for r in rows}, 'Video overlap'
            assert not set(receipt['trainingGroups']) & {r['annotation'].get('sourceGroup') for r in rows}, 'Source-group overlap'
        manifests.append(dict(cohort=cohort, path=str(manifest), sha256=hashlib.sha256(manifest.read_bytes()).hexdigest()))
        pairs = learning.base.available(rows)
        lookup = {}
        with torch.inference_mode():
            for start in range(0, len(pairs), 16):
                batch = pairs[start:start+16]
                tensors = [learning.base.images(p) for p in batch]
                h, c, f = [torch.stack([t[i] for t in tensors]) for i in range(3)]
                scores, view = model(h, c, f)
                old_contexts = []
                for r, _ in batch:
                    original = r.get('torsoContext', r['context'])
                    # Baseline keeps its original torso pixels, not the new
                    # head crop. Validate that inherited context before use.
                    if original['status'] == 'ready':
                        assert hashlib.sha256(Path(original['image']).read_bytes()).hexdigest() == original['sha256']
                        old_contexts.append(learning.transforms.ToTensor()(learning.Image.open(original['image']).convert('RGB')))
                    else:
                        old_contexts.append(torch.zeros(3, 128, 128))
                old_scores, old_view = old(h, torch.stack(old_contexts), f)
                appearance = model.appearance(h).softmax(1)
                body = model.body(c).softmax(1)
                face = model.face(f).softmax(1)
                reconstructed_view = learning.orientation_fusion(body, face, f)
                reconstructed = learning.fuse(appearance, reconstructed_view, f[:, 5])
                gap = max(float((scores-reconstructed).abs().max()), float((view-reconstructed_view).abs().max()))
                assert gap < 1e-5
                max_reconstruction_difference = max(max_reconstruction_difference, gap)
                for i, (r, p) in enumerate(batch):
                    key = (r['recordingId'], r['imageSHA256'], p['side'], p['patchSHA256'])
                    assert key not in lookup
                    lookup[key] = dict(scores=scores[i].tolist(), orientationScores=view[i].tolist(),
                                       appearanceScores=appearance[i].tolist(), bodyOrientation=body[i].tolist(),
                                       faceOrientation=face[i].tolist(), baselineScores=old_scores[i].tolist(),
                                       baselineOrientation=old_view[i].tolist())
        for r in rows:
            if not learning.base.base.in_window(r):
                continue
            a = r['annotation']
            partition = a['split'] if cohort == 'fitting' else cohort
            eligible_reference = r['caseId'] not in excluded
            for p in r['patches']:
                rec = dict(caseId=r['caseId'], recordingId=r['recordingId'], imageSHA256=r['imageSHA256'],
                           timestamp=r['pose']['timestamp'], side=p['side'], split=a['split'], cohort=partition,
                           expected=a['label'] if eligible_reference else None,
                           archivedReference=a['label'], referenceEligible=eligible_reference,
                           patchStatus=p['status'], context=r['context'], orientationExpected=a.get('viewpoint'))
                score = old_score = None
                if p['status'] == 'patchReady':
                    result = lookup[(r['recordingId'], r['imageSHA256'], p['side'], p['patchSHA256'])]
                    score, old_score = result['scores'], result['baselineScores']
                    predicted = learning.base.LABELS[int(np.argmax(score))]
                    rec.update(result, probabilities=score, argmax=predicted,
                               predicted=predicted if max(score) >= .8 else 'unknown',
                               patch=p['patch'], patchSHA256=p['patchSHA256'])
                    if p['patchSHA256'] in review_patches:
                        checked = review_patches[p['patchSHA256']]
                        for field in ['recordingId', 'imageSHA256', 'side']:
                            assert rec[field] == checked[field]
                        assert rec['timestamp'] == checked['timestamp'] and p['box'] == checked['box']
                        found_review.add(p['patchSHA256'])
                    if eligible_reference:
                        target = learning.appearance_target(a)
                        if target is not None:
                            cm = component_metrics[partition + ':appearance:' + learning.APPEARANCE[target]]
                            cm['correct' if int(np.argmax(result['appearanceScores'])) == target else 'wrong'] += 1
                        component_metrics[partition + ':appearanceOutputs'][learning.APPEARANCE[int(np.argmax(result['appearanceScores']))]] += 1
                    frame_key = (partition, r['recordingId'], r['imageSHA256'])
                    if frame_key not in frame_metrics_seen and a.get('viewpoint') in learning.VIEWS and r['context']['status'] == 'ready':
                        frame_metrics_seen.add(frame_key)
                        cm = component_metrics[partition + ':orientation:' + a['viewpoint']]
                        target = learning.VIEWS.index(a['viewpoint'])
                        for label, field in [('new', 'orientationScores'), ('baseline', 'baselineOrientation'),
                                             ('body', 'bodyOrientation'), ('face', 'faceOrientation')]:
                            cm[label + ('Correct' if int(np.argmax(result[field])) == target else 'Wrong')] += 1
                        cm['verifiedFace'] += r['context']['faceDetector']['status'] == 'verifiedFace'
                else:
                    rec.update(predicted='unknown', reason=p['status'])
                predictions.append(rec)
                if eligible_reference:
                    for model_name, probability in [('new', score), ('baseline', old_score)]:
                        for key in [partition + ':' + model_name, partition + ':' + model_name + ':' + a['label']]:
                            add_metric(summary[key], probability, a['label'])
                        add_metric(cases[partition + ':' + model_name + ':' + r['caseId']], probability, a['label'])
                else:
                    summary[partition + ':excludedReference']['expectedHands'] += 1
                    summary[partition + ':excludedReference']['availablePatches'] += score is not None
    assert found_review == set(review_patches), 'Failure-audit population changed'
    report = dict(qualification='Development-exposed video-disjoint comparisons; provisional agent labels. Not a fresh blind test.',
                  modelSHA256=receipt['modelSHA256'], baselineSHA256=hashlib.sha256(old_path.read_bytes()).hexdigest(),
                  scriptSHA256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                  referenceReviewSHA256=hashlib.sha256(review_path.read_bytes()).hexdigest(),
                  excludedBinaryReferenceCases=sorted(excluded), manifests=manifests,
                  threshold=.8, maxReconstructionDifference=max_reconstruction_difference,
                  summary=summary, cases=cases, components=component_metrics)
    (args.output / 'predictions.jsonl').write_text(''.join(json.dumps(r) + '\n' for r in predictions))
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    (args.output / 'audited-predictions.json').write_text(json.dumps([r for r in predictions if r.get('patchSHA256') in review_patches], indent=2) + '\n')
    print(json.dumps({k: v for k, v in report.items() if k not in ['cases', 'manifests']}, indent=2))


if __name__ == '__main__':
    main()
