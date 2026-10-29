#!/usr/bin/env python3
"""Score frozen models causally; references enter accounting only, never inference."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import statistics
import time

import torch
from PIL import Image
from torchvision.transforms import ToTensor

spec = importlib.util.spec_from_file_location('temporal_eval', Path(__file__).with_name('grip-temporal-v5.py'))
temporal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(temporal)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def infer(rows, quality, neutral, torso):
    """Only RGB, pose geometry, timestamps and automatic track identity are inputs."""
    tensor = ToTensor()
    output = []
    with torch.inference_mode():
        for row in rows:
            source = Path(row['framesDirectory']) / row['image']
            assert sha(source) == row['imageSHA256']
            timestamp = row['pose']['timestamp']
            context = row['context']
            ready = context['status'] == 'ready'
            if ready:
                assert sha(context['image']) == context['sha256']
                assert context['personIndex'] == row['subjectTrack']['personIndex']
                body = tensor(Image.open(context['image']).convert('RGB')).unsqueeze(0)
            else:
                body = torch.zeros(1, 3, 128, 128)
            # Torso RGB only. No inferred face landmark or missing face can
            # reverse orientation. The frozen model already exposes this path.
            _, view = torso(torch.zeros(1, 3, 128, 128), body, torch.tensor([[0., 0., 0., 0., 0., float(ready)]]))
            observation = dict(caseId=row['caseId'], recordingId=row['recordingId'], sourceGroup=row['sourceGroup'],
                               imageSHA256=row['imageSHA256'], seconds=timestamp['value'] / timestamp['timescale'],
                               trackId=row['subjectTrack']['trackId'] if row['subjectTrack']['status'] == 'tracked' else None,
                               contextReady=ready, view=view[0].tolist(), hands=[])
            for patch in row['patches']:
                if patch['status'] != 'patchReady':
                    continue
                assert sha(patch['patch']) == patch['patchSHA256']
                assert sha(patch['contactCrop']['image']) == patch['contactCrop']['sha256']
                hand = tensor(Image.open(patch['patch']).convert('RGB')).unsqueeze(0)
                wide = tensor(Image.open(patch['contactCrop']['image']).convert('RGB')).unsqueeze(0)
                surface, visible, contact = quality(hand, wide)
                neutral_by_view = []
                for i in range(3):
                    inputs = torch.zeros(1, 3)
                    inputs[0, i] = 1.
                    grip, _, _ = neutral(hand, inputs)
                    neutral_by_view.append(float(grip[0, 2]))
                index = row['subjectTrack']['personIndex']
                person = row['pose']['people'][index]
                wrist = temporal.point(person, patch['side'] + 'Wrist')
                shoulder = temporal.point(person, patch['side'] + 'Shoulder')
                raised = wrist is not None and shoulder is not None and wrist[1] < shoulder[1] - .3 * row['subjectTrack']['shoulderSpan']
                observation['hands'].append(dict(reviewId=patch['contactReviewId'], side=patch['side'],
                    surface=surface[0].tolist(), visible=float(visible[0, 1]), contact=float(contact[0, 1]),
                    neutralByView=neutral_by_view, raised=raised, patchSHA256=patch['patchSHA256']))
            output.append(observation)
    return output


def score(clips, references):
    counts = Counter()
    by_class = defaultdict(Counter)
    times = []
    for clip in clips:
        reference = references[clip['recordingId']]['grip']
        predicted = clip['label']
        category = 'unknown' if predicted == 'unknown' else 'acceptedUnverifiable' if reference == 'unknown' else 'correct' if predicted == reference else 'wrong'
        counts[category] += 1
        by_class[reference][category] += 1
        if category == 'correct' and clip['firstDecisionSeconds'] is not None:
            times.append(clip['firstDecisionSeconds'])
    return dict(videos=len(clips), counts={k: counts[k] for k in ['correct', 'wrong', 'unknown', 'acceptedUnverifiable']},
                byReferenceClass=dict(by_class), medianFirstDecisionSecondsOnCorrectFinalLabels=statistics.median(times) if times else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['input', 'intake', 'references', 'freeze', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    assert not (args.output / 'report.json').exists() and not (args.output / 'observations.jsonl').exists()
    freeze = json.loads(args.freeze.read_text())
    assert all(sha(path) == digest for path, digest in freeze['sha256'].items())
    rows = [json.loads(line) for line in args.input.read_text().splitlines()]
    intake = json.loads(args.intake.read_text())
    cases = intake['cases']
    assert {r['recordingId'] for r in rows} <= {s['sha256'] for s in cases}
    assert all(r['annotation']['split'] == 'test' for r in rows)
    # Fitting/test disjointness is checked by source group and recording hash,
    # before touching model output. Annotations are not inference features.
    test_groups, test_hashes = {s['sourceGroup'] for s in cases}, {s['sha256'] for s in cases}
    for role in freeze['fittingSources']:
        assert not test_groups & set(role['groups'])
        assert not test_hashes & set(role['recordings'])
    refs = json.loads(args.references.read_text())
    assert refs['inputSHA256'] == sha(args.input)
    references = {r['recordingId']: r for r in refs['videos']}
    assert set(references) == test_hashes
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    quality = torch.jit.load(freeze['models']['quality']).eval()
    neutral = torch.jit.load(freeze['models']['neutral']).eval()
    torso = torch.jit.load(freeze['models']['torso']).eval()
    start = time.perf_counter()
    observations = infer(rows, quality, neutral, torso)
    elapsed = time.perf_counter() - start
    groups = defaultdict(list)
    for obs in observations:
        groups[obs['recordingId']].append(obs)
    modes = ['frame-gated', 'temporal-contact-support', 'temporal-no-contact']
    clips = {mode: [dict(temporal.decide(groups[c['sha256']], mode), caseId=c['id'], recordingId=c['sha256']) for c in cases] for mode in modes}
    # Privileged orientation is a diagnostic ablation, never credited as app
    # accuracy or used by the three automatic variants above.
    oracle = []
    for case in cases:
        view = references[case['sha256']]['view']
        frames = []
        for frame in groups[case['sha256']]:
            altered = dict(frame)
            altered['contextReady'] = view in ['front', 'rear', 'oblique']
            altered['view'] = [float(view == v) for v in ['front', 'rear', 'oblique']] if altered['contextReady'] else [1/3]*3
            frames.append(altered)
        oracle.append(dict(temporal.decide(frames), caseId=case['id'], recordingId=case['sha256']))
    patch_refs = refs['patches']
    surface_counts = Counter()
    for frame in observations:
        for hand in frame['hands']:
            ref = patch_refs[hand['reviewId']]
            label = ['palm', 'dorsum', 'side'][max(range(3), key=lambda i: hand['surface'][i])]
            surface_counts['crops'] += 1
            surface_counts['readable' if ref['surface'] != 'unclear' else 'unreadable'] += 1
            if max(hand['surface']) >= .8 and hand['visible'] >= .8:
                surface_counts['qualityAccepted'] += 1
                surface_counts['acceptedCorrect' if ref['surface'] == label else 'acceptedUnverifiable' if ref['surface'] == 'unclear' else 'acceptedWrong'] += 1
    report = dict(qualification='Offline first-score video-group experiment; source and crop labels are agent annotations unless explicitly marked human. No model fitting or threshold changes. Oracle orientation is privileged diagnostic only.',
        inputSHA256=sha(args.input), intakeSHA256=sha(args.intake), referencesSHA256=sha(args.references), freezeSHA256=sha(args.freeze),
        scriptSHA256=sha(__file__), metrics={mode: score(value, references) for mode, value in clips.items()},
        oracleOrientationDiagnostic=score(oracle, references), cropDiagnostics=dict(surface_counts),
        frames=len(rows), modelInferenceSeconds=elapsed, modelInferenceEnvironment='Mac CPU, three frozen models, three neutral view passes; not phone latency',
        missingSlots=dict(Counter(p['status'] for r in rows for p in r['patches'] if p['status'] != 'patchReady')),
        noFrameRows=[c['id'] for c in cases if not groups[c['sha256']]])
    (args.output / 'observations.jsonl').write_text(''.join(json.dumps(x) + '\n' for x in observations))
    (args.output / 'clips.json').write_text(json.dumps(dict(automatic=clips, oracleOrientationDiagnostic=oracle), indent=2) + '\n')
    (args.output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
