#!/usr/bin/env python3
"""Reuse exact shared counter + phone torso geometry, with frozen hand RGB scores."""
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

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'build/grip-rep-context-v16'


def module(name, filename):
    s = importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    m = importlib.util.module_from_spec(s)
    s.loader.exec_module(m)
    return m


prior = module('v15_inputs', 'evaluate-grip-motion-v15.py')
policy = module('v16_policy', 'grip-rep-context-v16.py')
sha, lines, write = prior.sha, prior.readlines, prior.write


def freeze():
    OUT.mkdir(parents=True, exist_ok=True)
    old = json.loads((ROOT/'build/grip-motion-v15b/protocol-freeze.json').read_text())
    assert all(sha(p) == h for p, h in old['sha256'].items())
    rows = prior.selected()
    sampled = {r['imageSHA256']: r for r in rows}
    trajectories = lines(ROOT/'build/grip-motion-v15b/body-trajectories.jsonl')
    native = {}
    for r in rows:
        if r['recordingId'] not in native:
            native[r['recordingId']] = {n['imageSHA256']: n for n in lines(Path(r['framesDirectory'])/'frames.jsonl')}
    replay = []
    for t in trajectories:
        r = native[t['recordingId']][t['imageSHA256']]
        track = t['track']
        index = track['personIndex'] if track['status'] == 'tracked' else None
        pose = dict(r['pose'], people=[r['pose']['people'][index]] if index is not None else [])
        replay.append(dict(recordingId=t['recordingId'], imageSHA256=t['imageSHA256'],
                           trackId=track['trackId'] if index is not None else None, pose=pose))
    replay.sort(key=lambda r: (r['recordingId'], prior.seconds(r)))
    with (OUT/'native-input.jsonl').open('x') as f:
        f.write(''.join(json.dumps(r)+'\n' for r in replay))
    pins = dict(old['sha256'])
    paths = [Path(__file__), ROOT/'scripts/grip-rep-context-v16.py',
             ROOT/'scripts/tests/test_grip_rep_context_v16.py',
             ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGripExerciseContext.kt',
             ROOT/'shared/src/commonTest/kotlin/dev/hanginthere/core/WorkoutGripExerciseContextTests.kt',
             ROOT/'Evaluation/androidTests/GripRepContextV16Tests.kt',
             ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/ExerciseCounter.kt',
             ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGrip.kt',
             ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutAnalysis.kt',
             ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/ArmMeasurement.kt',
             ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/PullUpArmMeasurement.kt',
             ROOT/'models/grip/pose_landmarker_lite.task', ROOT/'models/grip/manifest.json',
             ROOT/'build/grip-motion-v15b/observations.jsonl',
             ROOT/'build/grip-motion-v15b/lifecycle-labels.json', OUT/'native-input.jsonl']
    for p in paths:
        pins[str(p)] = sha(p)
    for r in rows:
        source = Path(r['framesDirectory'])/r['image']
        assert sha(source) == r['imageSHA256']
        pins[str(source)] = r['imageSHA256']
    write(OUT/'protocol.json', dict(schemaVersion=16, cases=old['cases'], sha256=pins, models=old['models'],
        scope='Previously exposed recording-held-out regression. Same source groups and train/calibration separation as v15. No fitting, fresh qualification or phone deployment.',
        changes='Reuse exact shared ExerciseCounter phases + small phase-context adapter. Replace RGB torso orientation with exact phone WorkoutGripGeometry.bodyBasis/supportsView on same pinned MediaPipe pose model. Preserve frozen RGB hand outputs, .8 thresholds and separate neutral evidence.',
        reference='Replay-only support line seeded from first observed extended arm wrist(s); this is a measured wrist reference, not a detected/verified physical bar or full native workout-runtime replay. Existing counter owns all motion thresholds/dwell. Current wrist/support association removes released hands.',
        memory='Two distinct source-second images separated >=1s, same continuous counter exercise epoch and view. Retain consistent label within active exercise; positive release, tracking loss, view loss/change or qualified conflicting hand clears it. No additional motion gate or overhead-wrist heuristic.',
        orientation='Phone auxiliary body model CPU on retained sampled RGB; exact shared 3D torso geometry, same .85 alignment. Primary body/wrist identity rules unchanged. No face inputs or reference labels.',
        comparisons=['rep-context-rgb-view', 'rep-context-body-view'],
        accounting='Exercise acquisition, view output correctness/coverage, observed hand decisions and retained labels separately; negative source-frame setup/release admissions and rep events separately. All original source-frame phase/grip references retained. Unknown reference accepts are unverifiable. Native failure retained.',
        candidateSelection='Single prespecified revision; no score-based thresholds, reference relabeling or test-to-train movement.',
        frames=len(rows), nativeFrames=len(replay)))
    print(json.dumps(dict(nativeFrames=len(replay), sampledFrames=len(sampled), pins=len(pins))))


def verify():
    f = json.loads((OUT/'protocol.json').read_text())
    assert all(sha(p) == h for p, h in f['sha256'].items())
    return f


def body():
    import importlib.metadata
    import mediapipe as mp
    import numpy as np
    from PIL import Image
    verify()
    model = ROOT/'models/grip/pose_landmarker_lite.task'
    rows = {r['imageSHA256']: r for r in prior.selected()}
    joints = [('leftShoulder', 11), ('rightShoulder', 12), ('leftHip', 23), ('rightHip', 24), ('leftWrist', 15), ('rightWrist', 16)]
    options = mp.tasks.vision.PoseLandmarkerOptions(base_options=mp.tasks.BaseOptions(
        model_asset_path=str(model), delegate=mp.tasks.BaseOptions.Delegate.CPU), num_poses=2,
        min_pose_detection_confidence=.5, min_pose_presence_confidence=.5)
    count = Counter()
    with mp.tasks.vision.PoseLandmarker.create_from_options(options) as sdk, (OUT/'replay-input.jsonl').open('x') as output:
        for r in lines(OUT/'native-input.jsonl'):
            observation = None
            if r['imageSHA256'] in rows:
                sample = rows[r['imageSHA256']]
                path = Path(sample['framesDirectory'])/sample['image']
                assert sha(path) == sample['imageSHA256']
                image = Image.open(path).convert('RGB')
                pose = r['pose']; size = pose['imageSize']
                assert image.size == (size['width'], size['height'])
                tracked = [dict(joint=p['joint'], position=p['position'], score=p['confidence'])
                           for person in pose['people'] for p in person['landmarks'] if p['joint'] in dict(joints)]
                result = sdk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=np.asarray(image)))
                points = []
                if len(result.pose_landmarks) == len(result.pose_world_landmarks) == 1:
                    for name, i in joints:
                        p, w = result.pose_landmarks[0][i], result.pose_world_landmarks[0][i]
                        points.append(dict(joint=name, position=dict(x=float(p.x)*size['width'], y=float(p.y)*size['height']),
                            world=dict(x=float(w.x), y=float(w.y), z=float(w.z)), score=float(min(p.visibility, p.presence))))
                observation = dict(seconds=prior.seconds(r), imageSize=size, trackedBody=tracked, body=points,
                    hands=[], modelId='mediapipe_hand_full_v1_pose_lite_v1',
                    backend='MediaPipe Python '+importlib.metadata.version('mediapipe')+' CPU IMAGE; phone torso model and shared geometry')
                count['sampledFrames'] += 1
                count['singleAuxiliaryBody'] += bool(points)
            output.write(json.dumps(dict(r, observation=observation), allow_nan=False)+'\n')
    write(OUT/'body-receipt.json', dict(counts=dict(count), protocolSHA256=sha(OUT/'protocol.json'),
        inputSHA256=sha(OUT/'native-input.jsonl'), outputSHA256=sha(OUT/'replay-input.jsonl'),
        modelSHA256=sha(model), runtime=importlib.metadata.version('mediapipe'),
        limitation='Same pinned model and shared geometry; Mac CPU Python SDK is not the iPhone inference runtime.'))
    print(json.dumps(count))


def score():
    freeze = verify()
    native = lines(OUT/'replay-output.jsonl')
    original = lines(OUT/'native-input.jsonl')
    assert len(native) == len(original) == freeze['nativeFrames']
    assert [(r['recordingId'], r['imageSHA256'], r['seconds']) for r in native] == [
        (r['recordingId'], r['imageSHA256'], prior.seconds(r)) for r in original]
    rgb = {r['imageSHA256']: r for r in lines(ROOT/'build/grip-motion-v15b/observations.jsonl')}
    labels = json.loads((ROOT/'build/grip-motion-v15b/lifecycle-labels.json').read_text())['frames']
    groups = defaultdict(list)
    for r in native:
        groups[r['recordingId']].append(r)
    metrics, results = {}, {}
    for mode in ['rgb-view', 'body-view']:
        counts, classes, phases = Counter(), defaultdict(Counter), defaultdict(Counter)
        clips = []
        for case in freeze['cases']:
            frames = groups[case['recordingId']]
            result = policy.decide(frames, rgb, mode)
            predictions, identities = set(), set()
            for f, state in zip(frames, result['states']):
                if f['imageSHA256'] not in labels:
                    continue
                phase = labels[f['imageSHA256']]['phase']
                tally = phases[phase]
                tally['frames'] += 1
                tally['counterExerciseEligible'] += f['exercise']['eligible']
                tally['labelVisible'] += state['label'] != 'unknown'
                tally['newHandDecision'] += state['observed']
                if phase == 'exercise' and state['observed']:
                    predictions.add(state['label']); identities.add((f['sourceEpoch'], f['trackId']))
                    truth = case['grip']
                    tally['unverifiable' if truth == 'unknown' else 'correct' if state['label'] == truth else 'wrong'] += 1
            pred = next(iter(predictions)) if len(predictions) == 1 and len(identities) == 1 else 'unknown'
            category = 'unknown' if pred == 'unknown' else 'acceptedUnverifiable' if case['grip'] == 'unknown' else 'correct' if pred == case['grip'] else 'wrong'
            counts[category] += 1; classes[case['grip']][category] += 1
            clips.append(dict(case, prediction=pred, category=category, **result))
        metrics[mode] = dict(videos=len(freeze['cases']), counts=dict(counts), byReferenceClass=dict(classes), sampledLifecycle=dict(phases))
        results[mode] = clips
    orientation = Counter()
    orientation_cases = []
    case_by_id = {c['recordingId']: c for c in freeze['cases']}
    for r in native:
        if r['imageSHA256'] not in rgb:
            continue
        truth = case_by_id[r['recordingId']]['view']
        view = r['bodyView']
        category = 'unknown' if not view['supported'] else 'acceptedUnverifiable' if truth == 'unknown' else 'correct' if truth == view['view'] else 'wrongOrOutOfScope'
        orientation['frames'] += 1; orientation['bodyBasisAvailable'] += view['available']; orientation[category] += 1
        orientation_cases.append(dict(recordingId=r['recordingId'], imageSHA256=r['imageSHA256'], seconds=r['seconds'], reference=truth, **view, category=category))
    counter_phases = Counter(r['phase'] for r in native)
    counter_issues = Counter(r['trackingIssue'] for r in native)
    all_contact_independent = True
    for group in groups.values():
        expected = policy.decide(group, rgb)
        altered = copy.deepcopy(rgb)
        for r in altered.values():
            for h in r['hands']: h['contact'] = 0.; h['raised'] = False
        assert expected == policy.decide(group, altered)
    report = dict(schemaVersion=16, qualification=freeze['scope'], metrics=metrics,
        orientationFrameDiagnostics=dict(orientation), counterPhases=dict(counter_phases), counterIssues=dict(counter_issues),
        nativeFrames=len(native), sampledFrames=len(rgb), contactIndependent=all_contact_independent,
        phoneInstalled=False, originalConfidenceThresholdsUnchanged=True,
        hashes=dict(protocol=sha(OUT/'protocol.json'), replayInput=sha(OUT/'replay-input.jsonl'), replayOutput=sha(OUT/'replay-output.jsonl')))
    write(OUT/'report.json', report); write(OUT/'results.json', results)
    write(OUT/'orientation.json', orientation_cases)
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('stage', choices=['freeze', 'body', 'score'])
    args = p.parse_args()
    globals()[args.stage]()
