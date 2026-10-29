#!/usr/bin/env python3
"""Post-test accounting; never changes frozen v17 inputs, models or decisions."""
import hashlib
import importlib.util
import json
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/grip-controlled-v17'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def read(path):
    return json.loads(Path(path).read_text())


def lines(path):
    return [json.loads(x) for x in Path(path).read_text().splitlines()]


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / 'scripts' / filename)
    obj = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(obj)
    return obj


def write(path, value):
    with Path(path).open('x') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')


def main():
    from PIL import Image
    protocol = read(OUT / 'protocol.json')
    candidate = read(OUT / 'candidate-freeze.json')
    freeze = read(OUT / 'test/pre-inference-freeze.json')
    pins = {}
    for document in [protocol, candidate, freeze]:
        for path, expected in document['sha256'].items():
            assert sha(path) == expected, path
            pins[path] = expected
    assert all(sha(p) == h for p, h in freeze['patchSHA256'].items())
    refs = read(OUT / 'test-source-references.json')
    intake = read(OUT / 'test-intake.json')
    assert refs['candidateSHA256'] == intake['candidateFreezeSHA256'] == sha(OUT / 'candidate-freeze.json')
    assert {s['sha256'] for s in protocol['plannedSeparateTestSources']} == {s['sha256'] for s in intake['cases']}
    for source in intake['cases']:
        assert sha(source['path']) == source['sha256']
    test_ids = {s['sha256'] for s in intake['cases']}
    test_groups = {s['sourceGroup'] for s in intake['cases']}
    fitting = lines(ROOT / 'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl')
    neutral_fitting = lines(ROOT / 'build/grip-threeway/fitting.jsonl')
    for name, rows in [('qualityAllRoles', fitting), ('neutralAllRoles', neutral_fitting)]:
        ids = {r['recordingId'] for r in rows}
        groups = {r['annotation']['sourceGroup'] for r in rows}
        assert not test_ids & ids, name
        assert not test_groups & groups, name

    tracker_module = module('audit_tracker', 'grip-temporal-v5.py')
    crop_module = module('audit_crop', 'prepare-threeway-crops.py')
    stage_metrics = {}
    counts = Counter()
    for stage in ['baseline', 'test']:
        folder = OUT / stage
        cases = protocol['cases'] if stage == 'baseline' else refs['cases']
        rows = lines(folder / 'rows.jsonl')
        native_input = lines(folder / 'native-input.jsonl')
        native = lines(folder / 'native-output.jsonl')
        rgb = lines(folder / 'observations.jsonl')
        assert len(rows) == len(native_input) == len(native) == len(rgb)
        position = 0
        for case in cases:
            tracker = tracker_module.SubjectTracker()
            directory = Path(case['framesDirectory'])
            source_rows = lines(directory / 'frames.jsonl')
            for original in source_rows:
                row, inp, replay, observation = [v[position] for v in [rows, native_input, native, rgb]]
                position += 1
                timestamp = original['pose']['timestamp']
                seconds = timestamp['value'] / timestamp['timescale']
                assert row['imageSHA256'] == inp['imageSHA256'] == replay['imageSHA256'] == observation['imageSHA256'] == original['imageSHA256']
                assert replay['seconds'] == observation['seconds'] == seconds
                track = tracker.update(original['pose']['people'], seconds)
                index = track['personIndex'] if track['status'] == 'tracked' else None
                expected_pose = dict(original['pose'], people=[original['pose']['people'][index]] if index is not None else [])
                assert inp['pose'] == expected_pose
                assert inp['trackId'] == (track['trackId'] if index is not None else None)
                assert set(inp) == {'recordingId', 'imageSHA256', 'trackId', 'pose'}
                source_path = directory / original['image']
                assert sha(source_path) == original['imageSHA256']
                with Image.open(source_path) as source:
                    source = source.convert('RGB')
                    for patch in row['patches']:
                        geometry = crop_module.geometry(original['pose']['people'][index], patch['side'], source.size) if index is not None else None
                        assert bool(geometry) == (patch['status'] == 'patchReady')
                        if geometry:
                            assert tuple(patch['box']) == tuple(geometry[0])
                            expected_pixels = source.crop(geometry[0]).resize((128, 128), Image.Resampling.BILINEAR)
                            with Image.open(patch['patch']) as actual:
                                assert actual.convert('RGB').tobytes() == expected_pixels.tobytes()
                            assert sha(patch['patch']) == patch['patchSHA256']
                            counts['verifiedCrops'] += 1
                counts['verifiedSourceFrames'] += 1
        assert position == len(rows)
        results = read(folder / 'results.json')
        stage_metrics[stage] = {}
        for variant, clips in results.items():
            videos = Counter()
            details = []
            negatives = Counter()
            for clip in clips:
                states = clip['states']
                exercise = [s for s in states if s['phaseReference'] == 'exercise']
                observed = [s for s in exercise if s['observed']]
                labels = {s['label'] for s in observed}
                if clip['grip'] == 'unknown':
                    outcome = 'unverifiableAccepted' if labels else 'unverifiableAbstained'
                elif not labels:
                    outcome = 'unknown'
                elif labels == {clip['grip']}:
                    outcome = 'correct'
                else:
                    outcome = 'wrongOrInconsistent'
                videos[outcome] += 1
                correct = [s for s in exercise if clip['grip'] != 'unknown' and s['label'] == clip['grip']]
                # Frozen scorer's firstCorrectSeconds matches unknown==unknown on
                # unlabelled sources. Preserve first results; expose guarded value.
                first = correct[0]['seconds'] if correct else None
                eligible = sum(n['exercise']['eligible'] for n in native if n['recordingId'] == clip['recordingId'])
                for s, n in zip(states, [n for n in native if n['recordingId'] == clip['recordingId']]):
                    if s['phaseReference'] in ['setup', 'release']:
                        negatives[s['phaseReference']+'Frames'] += 1
                        negatives['counterEligible'] += n['exercise']['eligible']
                        negatives['visibleLabels'] += s['label'] != 'unknown'
                details.append(dict(id=clip['id'], referenceGrip=clip['grip'], outcome=outcome,
                                    captureEligible=clip.get('captureEligible', True),
                                    observedLabels=sorted(labels), counterEligibleFrames=eligible,
                                    firstCorrectSeconds=first, exerciseLabelCoverage=clip['exerciseLabelCoverage'],
                                    completeSetPassed=clip['completeSetPassed'],
                                    wrongConfirmedDecisions=clip['wrongConfirmedDecisions']))
            stage_metrics[stage][variant] = dict(videos=dict(videos), recordings=details, negatives=dict(negatives))

    fresh = []
    for path in ['build/grip-front-rear-v9/untouched-final-reserve.json', 'build/grip-front-rear-v9/k600-val/untouched-final-reserve.json']:
        fresh.extend(read(ROOT / path)['sources'])
    remaining = [s for s in fresh if s['sha256'] not in test_ids]
    assert len(remaining) == 8
    assert sum(c['originalNativeFrames'] for c in refs['cases']) == 209
    assert sum(c['evaluatedNativeFrames'] for c in refs['cases']) == 194
    assert sum(c['outOfScopeNativeFrames'] for c in refs['cases']) == 15
    report = dict(schemaVersion=17, qualification='Offline confirmed-view diagnostic; sparse source sampling, no phone qualification.',
                  verification=dict(pinsVerified=len(pins), **counts, originalSeparateFrames=209,
                                    evaluatedSeparateFrames=194, predeclaredCameraOrbitFrames=15,
                                    recordingAndSourceGroupDisjointFromBothModelsAllFittingRoles=True,
                                    originalPosesTimestampsAndAutomaticCropsVerified=True,
                                    frozenReadabilityAblationNotPromoted=True, modelFitPerformed=False, phoneInstalled=False),
                  metrics=stage_metrics,
                  scorerCaveat='Original firstCorrectSeconds erroneously matches unknown==unknown for unlabelled references. This addendum guards referenceGrip!=unknown; no correctness credit is given to those four sources. Frozen files unchanged.',
                  evidenceHashes={str(p.resolve()):sha(p) for stage in ['baseline','test'] for p in (OUT/stage).glob('*.json*')},
                  protocolSHA256=sha(OUT/'protocol.json'), candidateSHA256=sha(OUT/'candidate-freeze.json'))
    write(OUT / 'verification.json', report)
    write(OUT / 'reserve-exposure-ledger.json', dict(
        exposed=[dict(id=c['id'], recordingId=c['recordingId'], sourceGroup=c['sourceGroup'], role='v17 test, now exposed; no fitting or calibration') for c in refs['cases']],
        remainingUnread=[dict(sha256=s['sha256'], sourceGroup=s['sourceGroup']) for s in remaining],
        note='Eight remaining in the fourteen newly acquired reserve groups; historical reserve manifests unchanged. No remaining source images decoded/read in this run.'))
    write(ROOT / 'Evaluation/fixtures/grip-controlled-baseline-v17.json', report)
    print(json.dumps(dict(verification=report['verification'], metrics=stage_metrics), indent=2))


if __name__ == '__main__':
    main()
