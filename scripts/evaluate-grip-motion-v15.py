#!/usr/bin/env python3
"""Frozen posture/motion vs image-contact experiment on recording-held-out regressions."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'build/grip-motion-v15'


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


motion = module('motion_v15', 'grip-exercise-motion-v15.py')
temporal = module('temporal_v5', 'grip-temporal-v5.py')
INPUTS = ['build/grip-temporal-v5/inputs.jsonl',
          'build/grip-localization-v6/fresh-baseline-context/context.jsonl',
          'build/grip-threeway/test-approved.jsonl']
MODELS = dict(quality='build/grip-anatomy-v11/frozenQuality/GripSurfaceClassifier.pt',
              neutral='build/grip-threeway/staged-models/threeway/GripHandClassifier.pt',
              torso='build/grip-reviewed/model/GripContextClassifier.pt')
MODES = ['image-contact', 'no-contact', 'motion-and-contact', 'motion-no-contact']


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def readlines(path):
    return [json.loads(x) for x in Path(path).read_text().splitlines()]


def write(path, obj):
    with path.open('x') as f:
        f.write(json.dumps(obj, indent=2)+'\n')


def selected():
    v7 = readlines(ROOT/'build/grip-oracle-v7/final/selected.jsonl')
    ids = {r['recordingId'] for r in v7}
    rows = []
    for name in INPUTS:
        rs = readlines(ROOT/name)
        rows += rs if 'threeway/test-approved' not in name else [r for r in rs if r['recordingId'] in ids]
    return rows


def prepare():
    from PIL import Image, ImageDraw, ImageOps
    OUT.mkdir(exist_ok=True)
    rows = selected()
    by_video = defaultdict(list)
    for r in rows:
        by_video[r['recordingId']].append(r)
    pins = {str(p): sha(p) for p in [ROOT/x for x in INPUTS+list(MODELS.values())]+[
        Path(__file__), ROOT/'scripts/grip-exercise-motion-v15.py', ROOT/'scripts/grip-temporal-v5.py',
        ROOT/'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl',
        ROOT/'build/grip-temporal-v5/pre-test-freeze.json',
        ROOT/'Evaluation/fixtures/grip-temporal-test-references-v5.json',
        ROOT/'build/grip-oracle-v7/final/prepared.jsonl',
        ROOT/'scripts/tests/test_grip_exercise_motion_v15.py']}
    for rs in by_video.values():
        native = Path(rs[0]['framesDirectory'])/'frames.jsonl'
        pins[str(native)] = sha(native)
    v5_freeze = json.loads((ROOT/'build/grip-temporal-v5/pre-test-freeze.json').read_text())
    fits = v5_freeze['fittingSources']+[dict(input='v11-all-roles',
        groups=sorted({r['annotation'].get('sourceGroup') or r['recordingId'] for r in readlines(ROOT/'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl')}),
        recordings=sorted({r['recordingId'] for r in readlines(ROOT/'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl')}))]
    for role in fits:
        assert not {r['sourceGroup'] for r in rows} & set(role['groups'])
        assert not set(by_video) & set(role['recordings'])
    videos = json.loads((ROOT/'Evaluation/fixtures/grip-temporal-test-references-v5.json').read_text())['videos']
    v7 = readlines(ROOT/'build/grip-oracle-v7/final/prepared.jsonl')
    known = {r['recordingId']: dict(grip=r['gripReference'], view=r['viewReference']) for r in v7}
    known.update({r['recordingId']: dict(grip=r['grip'], view=r['view']) for r in videos})
    cases = []
    for i, (recording, rs) in enumerate(sorted(by_video.items()), 1):
        ref = known.get(recording, dict(grip='unknown', view='unknown'))
        cases.append(dict(id=f'M{i:02d}', caseId=rs[0]['caseId'], recordingId=recording,
                          sourceGroup=rs[0]['sourceGroup'], frames=len(rs), **ref))
        if recording not in {r['recordingId'] for r in v7}:
            continue
        rs.sort(key=seconds)
        sheet = Image.new('RGB', (1280, ((len(rs)+3)//4)*250), 'white')
        d = ImageDraw.Draw(sheet)
        for j, r in enumerate(rs):
            x, y = j%4*320, j//4*250
            source = Path(r['framesDirectory'])/r['image']
            assert sha(source) == r['imageSHA256']
            d.text((x+4, y+3), f"{cases[-1]['id']} {seconds(r):.3f}s", fill='black')
            sheet.paste(ImageOps.contain(Image.open(source), (316, 222)), (x, y+25))
        sheet.save(OUT/f"{cases[-1]['id']}-source.jpg")
    # Include the native-failed v5 recording in the denominator.
    for v in videos:
        if v['recordingId'] not in by_video:
            cases.append(dict(id='native-failure', caseId=v['caseId'], recordingId=v['recordingId'],
                              grip=v['grip'], view=v['view'], frames=0))
    write(OUT/'protocol-freeze.json', dict(scope='No fitting; three previously exposed regression cohorts, disjoint from train AND calibration by source group and recording. Not a new blind qualification.',
        cases=cases, policy=motion.POLICY, models=MODELS, sha256=pins, fittingSources=fits,
        thresholds=dict(surface=.8, readability=.8, frontRear=.8, neutralEveryView=.8, contactComparator=.8),
        modes=MODES, sampling='All retained native ~4Hz body poses; original scheduled RGB slots only. No new decoding, crop selection or oracle inputs.',
        inference='Torso-only frozen RGB orientation; no face absence vote. v11 frozen anatomy/readability; prior frozen neutral head, all three counterfactual supplied views. Front/rear required even for neutral. No side=>neutral shortcut.',
        accounting='Every source including native failures retained. Current-frame output, separate setup/release false accepts and body-only activation, exercise-only actual grip video labels, per-class counts. Agent labels, not human gold. No tuning after score. RGB hand inputs from original automatic crops; wrong crop remains failure.',
        lifecycle='Source-frame annotations frozen before new predictions. Sparse sampled phases are not continuous transition-time ground truth.',
        deployment='Offline experiment; RGB model is not the live phone geometry classifier. No rep/readiness changes. Untouched final reserve remains unopened.'))
    print(json.dumps(dict(videos=len(cases), frames=len(rows), cases=cases), indent=2))


def seconds(r):
    t = r['pose']['timestamp']
    return t['value']/t['timescale']


def evaluate():
    import torch
    from PIL import Image
    from torchvision.transforms import ToTensor
    freeze = json.loads((OUT/'protocol-freeze.json').read_text())
    assert all(sha(p) == h for p, h in freeze['sha256'].items())
    refs_path = OUT/'lifecycle-references.json'
    refs = json.loads(refs_path.read_text())
    assert refs['protocolSHA256'] == sha(OUT/'protocol-freeze.json')
    assert refs['referenceSHA256'] == sha(OUT/'lifecycle-labels.json')
    labels = json.loads((OUT/'lifecycle-labels.json').read_text())['frames']
    rows = selected()
    assert set(labels) == {r['imageSHA256'] for r in rows}
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    models = {k: torch.jit.load(str(ROOT/p)).eval() for k, p in MODELS.items()}
    tensor = ToTensor()
    by_video = defaultdict(list)
    for r in rows:
        by_video[r['recordingId']].append(r)
    observations, trajectories = [], []
    started = time.perf_counter()
    with torch.inference_mode():
        for recording, rs in sorted(by_video.items()):
            tracker, gate = temporal.SubjectTracker(), motion.ExerciseGate()
            full = readlines(Path(rs[0]['framesDirectory'])/'frames.jsonl')
            tracks = {}
            for r in sorted(full, key=seconds):
                t = seconds(r)
                track = tracker.update(r['pose']['people'], t)
                index = track['personIndex'] if track['status'] == 'tracked' else None
                evidence = gate.update(t, track['trackId'] if index is not None else None,
                                       r['pose']['people'][index] if index is not None else None)
                tracks[r['imageSHA256']] = (track, evidence)
                trajectories.append(dict(recordingId=recording, caseId=rs[0]['caseId'], imageSHA256=r['imageSHA256'],
                                         track=track, **evidence))
            for r in sorted(rs, key=seconds):
                source = Path(r['framesDirectory'])/r['image']
                assert sha(source) == r['imageSHA256']
                track, exercise = tracks[r['imageSHA256']]
                index = track['personIndex'] if track['status'] == 'tracked' else None
                # New trajectory cannot inherit crops belonging to another body.
                same_owner = index is not None and r['patchPlan'].get('personIndex') == index
                context = r['context']
                ready = same_owner and context['status'] == 'ready' and context['personIndex'] == index
                if ready:
                    assert sha(context['image']) == context['sha256']
                body = tensor(Image.open(context['image']).convert('RGB')).unsqueeze(0) if ready else torch.zeros(1, 3, 128, 128)
                _, view = models['torso'](torch.zeros_like(body), body, torch.tensor([[0., 0., 0., 0., 0., float(ready)]]))
                observation = dict(recordingId=recording, caseId=r['caseId'], seconds=seconds(r),
                    imageSHA256=r['imageSHA256'], trackId=track['trackId'] if same_owner else None,
                    exercise=exercise, contextReady=ready, view=view[0].tolist(), hands=[])
                if same_owner:
                    person = r['pose']['people'][index]
                    for p in r['patches']:
                        if p['status'] != 'patchReady':
                            continue
                        assert sha(p['patch']) == p['patchSHA256']
                        hand = tensor(Image.open(p['patch']).convert('RGB')).unsqueeze(0)
                        surface, visible, contact = models['quality'](hand)
                        neutral = []
                        for i in range(3):
                            v = torch.zeros(1, 3); v[0, i] = 1.
                            grip, _, _ = models['neutral'](hand, v)
                            neutral.append(float(grip[0, 2]))
                        wrist = temporal.point(person, p['side']+'Wrist')
                        shoulder = temporal.point(person, p['side']+'Shoulder')
                        raised = wrist is not None and shoulder is not None and wrist[1] < shoulder[1]-.3*track['shoulderSpan']
                        observation['hands'].append(dict(side=p['side'], surface=surface[0].tolist(),
                            visible=float(visible[0, 1]), contact=float(contact[0, 1]), raised=raised,
                            neutralByView=neutral, patchSHA256=p['patchSHA256'], patch=p['patch']))
                observations.append(observation)
    grouped = defaultdict(list)
    for r in observations:
        grouped[r['recordingId']].append(r)
    results = {mode: [] for mode in MODES}
    metrics = {}
    for mode in MODES:
        video_counts, phase_counts, by_class = Counter(), defaultdict(Counter), defaultdict(Counter)
        for case in freeze['cases']:
            frames = grouped[case['recordingId']]
            result = motion.decide(frames, temporal, mode)
            exercise_labels = set()
            for f, state in zip(frames, result['states']):
                phase = labels[f['imageSHA256']]['phase']
                phase_counts[phase]['frames'] += 1
                phase_counts[phase]['bodyEligible'] += int(f['exercise']['eligible'])
                phase_counts[phase]['gripAccepted'] += int(state['label'] != 'unknown')
                if phase == 'exercise' and state['label'] != 'unknown':
                    exercise_labels.add(state['label'])
            pred = next(iter(exercise_labels)) if len(exercise_labels) == 1 else 'unknown'
            truth = case['grip']
            category = ('unknown' if pred == 'unknown' else 'acceptedUnverifiable' if truth == 'unknown' else
                        'correct' if pred == truth else 'wrong')
            video_counts[category] += 1
            by_class[truth][category] += 1
            results[mode].append(dict(case, **result, exerciseOnlyLabel=pred, category=category))
        metrics[mode] = dict(videos=len(freeze['cases']), counts=dict(video_counts),
                            byReferenceClass=dict(by_class), sampledLifecycle=dict(phase_counts))
    write(OUT/'report.json', dict(scope=freeze['scope'], models=MODELS, metrics=metrics,
        frames=len(rows), nativePoseFrames=len(trajectories), seconds=time.perf_counter()-started,
        qualification='Previously exposed recording-held-out regression only; source-frame lifecycle agent review, sparse timings, not population or phone accuracy. No policy/threshold/model selection after score.',
        hashes=dict(protocol=sha(OUT/'protocol-freeze.json'), lifecycle=sha(refs_path))))
    write(OUT/'results.json', results)
    with (OUT/'observations.jsonl').open('x') as f:
        f.write(''.join(json.dumps(r)+'\n' for r in observations))
    with (OUT/'body-trajectories.jsonl').open('x') as f:
        f.write(''.join(json.dumps(r)+'\n' for r in trajectories))
    print(json.dumps(metrics, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['prepare', 'evaluate'])
    args = parser.parse_args()
    prepare() if args.stage == 'prepare' else evaluate()
