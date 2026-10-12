#!/usr/bin/env python3
"""Fail closed on missing evidence. Emulator budgets are regressions, not phone promises.

Original capture accuracy stays a separate, visibly failing CI job. Changing a
fixture's role never relaxes source integrity, native execution or resource checks.
"""
import argparse
import json
import math
from pathlib import Path
import re

CASES = {'pullup_margin': ('positive', 108, 1),
         'pullup_smoke': ('diagnostic', 40, 1),
         'dip_controlled_pexels': ('diagnostic', 300, 9)}


def finite_positive(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def core(path):
    report = json.loads(path.read_text())
    assert report['cases'] >= 25 and report['frames'] >= 180
    p95 = report['p95NanosecondsPerFrame']
    assert finite_positive(p95) and p95 < 1_000_000, 'Shared core p95 exceeds 1ms/frame'
    swift = report['swift']['p95NanosecondsPerFrame']
    assert finite_positive(swift)
    assert finite_positive(report['checksum']) and abs(report['checksum'] - report['swift']['checksum']) < 1e-7, 'Benchmark output was not consumed identically'
    print(f'Same-host core p95: Swift {swift/1000:.1f}µs, Kotlin {p95/1000:.1f}µs ({p95/swift:.2f}×); core budget<1ms. Camera/model excluded.')


def cases(report):
    assert report['policyVersion'] == 8
    assert len(report['cases']) == len(CASES) and {case['id'] for case in report['cases']} == set(CASES), 'Missing or duplicate native fixture'
    for case in report['cases']:
        role, frames, movements = CASES[case['id']]
        assert case['qualificationRole'] == role, 'Frozen qualification role changed'
        assert case['expectedMovements'] == movements, 'Reviewed movement label changed'
        assert case['frames'] == frames, 'Exact decoded frame count changed'
    return report['cases']


def accuracy_matches(case):
    production = case['productionReplay']
    return (case['observedMovements'] == case['expectedMovements']
            and production['observedMovements'] == case['expectedMovements']
            and production['functionalParity'] is True)


def native(path):
    report = json.loads(path.read_text())
    assert not report['failures'], 'Native execution or positive qualification failed'
    for case in cases(report):
        assert 'error' not in case and case['singlePersonFrames'] > 0, 'Missing usable native poses'
        latency = case['poseLatencyMillis']
        assert latency['samples'] == case['frames']
        assert finite_positive(latency['p95']) and latency['p95'] < 2000, 'CPU emulator pose p95 exceeds2s'
        assert 0 < case['peakPssKb'] < 1_048_576, 'Peak PSS exceeds1GiB'
        production = case['productionReplay']
        assert 'error' not in production and production['singlePersonFrames'] > 0, 'Production native execution failed'
        assert production['decodedFrames'] > 0, 'Production source never decoded'
        pipeline = production['pipelineWithDisplayCopyLatencyMillis']
        assert pipeline['samples'] == production['decodedFrames']
        assert finite_positive(pipeline['p95']) and pipeline['p95'] < 2500, 'Decode-inclusive production pipeline exceeds2.5s/frame'
        assert 0 < production['peakPssKb'] < 1_048_576, 'Production replay exceeds1GiB PSS'
        matches = accuracy_matches(case)
        assert case['functionalParity'] is matches, 'Accuracy mismatch hidden in report'
        assert case['fixedEdgeMovementParity'] is (case['observedMovements'] == case['expectedMovements'])
        assert bool(case['accuracyFailures']) is (not matches), 'Accuracy failure evidence omitted'
        if case['qualificationRole'] == 'positive':
            assert matches and production['replayOutcome'] == 'complete', f"Positive fixed-edge/Auto replay failed: {case['id']}"
            assert production['countingSourceEnded'] and production['countingSourceFrames'] == case['frames'], 'Positive production source incomplete'
        print(f"{case['id']} ({case['qualificationRole']}): {case['observedMovements']}/{case['expectedMovements']} fixed-edge, {production['observedMovements']} Auto; {case['frames']} decoded frames; pose p95 {latency['p95']:.1f}ms; full pipeline p95 {pipeline['p95']:.1f}ms; peak PSS {production['peakPssKb']/1024:.1f}MiB")
    flattened = [message for case in report['cases'] for message in case['accuracyFailures']]
    assert report['accuracyFailures'] == flattened, 'Top-level accuracy evidence incomplete'
    print('Native mechanics, required positive and emulator regression budgets passed. Original accuracy diagnostics remain separately graded; physical performance is unqualified.')


def accuracy(path):
    report = json.loads(path.read_text())
    failures = []
    for case in cases(report):
        if case['qualificationRole'] != 'diagnostic':
            continue
        production = case['productionReplay']
        print(f"Original capture {case['id']}: fixed-edge {case['observedMovements']}/{case['expectedMovements']}; Auto {production['observedMovements']}/{case['expectedMovements']}; accuracyPassed={accuracy_matches(case)}", flush=True)
        if not accuracy_matches(case):
            failures.append(case['id'])
    assert not failures, 'Original capture detection accuracy remains failed: ' + ', '.join(failures)


def crop_region(pose):
    size = pose['imageSize']; width, height = size['width'], size['height']
    numeric = lambda x: isinstance(x, (int, float)) and not isinstance(x, bool) and math.isfinite(x)
    if not all(numeric(x) and 0 < x <= 2_147_483_647 and int(x) == x for x in (width, height)) or len(pose['people']) != 1:
        return None
    points = pose['people'][0]['landmarks']
    if len({p['joint'] for p in points}) != len(points):
        return None
    visible = [p for p in points if numeric(p['confidence']) and .3 <= p['confidence'] <= 1
               and all(numeric(p['position'][axis]) and 0 <= p['position'][axis] <= bound for axis, bound in (('x', width), ('y', height)))]
    if len(visible) < 8 or not {'leftShoulder', 'rightShoulder', 'leftHip', 'rightHip'} <= {p['joint'] for p in visible}:
        return None
    xmin, xmax = min(p['position']['x'] for p in visible), max(p['position']['x'] for p in visible)
    ymin, ymax = min(p['position']['y'] for p in visible), max(p['position']['y'] for p in visible)
    if xmax - xmin < 16 or ymax - ymin < 16:
        return None
    padx, pady = max(16, (xmax-xmin)*.15), max(16, (ymax-ymin)*.15)
    left, top = math.floor(max(0, xmin-padx)), math.floor(max(0, ymin-pady))
    right, bottom = math.ceil(min(width, xmax+padx)), math.ceil(min(height, ymax+pady))
    return dict(left=left, top=top, width=right-left, height=bottom-top)


def crop_observation(observation, witness):
    region = crop_region(witness)
    assert observation['region'] == region, 'Crop recipe or current VIDEO witness changed'
    assert observation['inferencePerformed'] is (region is not None), 'Crop inference/skip status hidden'
    pose = observation['pose']
    if region is None:
        assert pose['people'] == [] and pose['backend'] == 'Diagnostic crop unavailable', 'Skipped crop fabricated native observations'
        assert all(observation.get(key) is None for key in ('rawPose', 'cropPixelSha256', 'inferenceMillis')), 'Skipped crop claims inference evidence'
        return False
    assert all(isinstance(x,int) and not isinstance(x,bool) for x in observation['region'].values()), 'Crop rectangle must use integer pixels'
    assert re.fullmatch('[0-9a-f]{64}', observation['cropPixelSha256']), 'Missing crop pixel checksum'
    raw = observation['rawPose']
    assert raw['imageSize'] == dict(width=region['width'], height=region['height']) and raw['timestamp'] == pose['timestamp'], 'Native crop geometry/PTS changed'
    assert raw['backend'] == 'MediaPipe Heavy CPU IMAGE visibility/presence minimum' and raw['requestRevision'] == 1, 'Crop model changed'
    assert finite_positive(observation['inferenceMillis']), 'Missing crop inference timing'
    expected_people = []
    for person in raw['people']:
        landmarks = []
        for point in person['landmarks']:
            x, y = point['position']['x'], point['position']['y']
            if math.isfinite(x) and math.isfinite(y) and 0 <= x <= region['width'] and 0 <= y <= region['height']:
                landmarks.append(dict(point, position=dict(x=x+region['left'], y=y+region['top'])))
        expected_people.append(dict(landmarks=landmarks))
    assert pose['people'] == expected_people, 'Crop restoration changed scores, people, joints or original coordinates'
    assert pose['backend'] == 'MediaPipe Heavy CPU IMAGE person crop visibility/presence minimum', 'Crop provenance omitted'
    return True


def pose_modes(path):
    """Validate the experiment against the unchanged native run, without grading it as parity."""
    report = json.loads(path.read_text())
    baseline = json.loads(path.with_name('report.json').read_text())
    baseline_cases = {case['id']: case for case in cases(baseline)}
    cropped = report['schemaVersion'] == 2
    assert report['schemaVersion'] in (1, 2) and report['policyVersion'] == 8 and not report['failures'], 'Pose-mode native execution failed'
    if cropped:
        assert (report['regionRecipeVersion'], report['paddingFraction'], report['minimumPaddingPixels']) == (1, .15, 16), 'Frozen crop recipe changed'
    for key in ('sdk', 'modelSha256', 'poseArtifactSha256'):
        assert report[key] == baseline[key], 'Pose-mode inputs differ: ' + key
    assert len(report['cases']) == len(CASES) and {case['id'] for case in report['cases']} == set(CASES), 'Missing or duplicate pose-mode fixture'
    names = {'video', 'image', 'crop'} if cropped else {'video', 'image'}
    for case in report['cases']:
        identifier = case['id']; original = baseline_cases[identifier]
        _, frames, expected = CASES[identifier]
        assert 'error' not in case, 'Pose-mode native exception'
        assert case['frames'] == case['expectedFrames'] == frames and case['expectedMovements'] == expected, 'Pose-mode source/label changed'
        assert re.fullmatch('[0-9a-f]{64}', case['sha256']) and case['sha256'] == original['sha256'], 'Pose-mode video bytes differ'
        assert case['exercise'] == original['exercise'] and case['side'] == original['side'], 'Pose-mode exercise/side changed'
        source = [json.loads(line) for line in path.with_name(identifier + '-observations.jsonl').read_text().splitlines()]
        suffix = '-pose-crops.jsonl' if cropped else '-pose-modes.jsonl'
        trace = [json.loads(line) for line in path.with_name(identifier + suffix).read_text().splitlines()]
        assert len(source) == len(trace) == frames, 'Incomplete pose-mode observations'
        pts = case['sourcePtsMicroseconds']
        assert len(pts) == frames and all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in pts)
        assert all(a < b for a, b in zip(pts, pts[1:])), 'Non-increasing pose-mode PTS'
        assert set(case['modes']) == names, 'Missing comparison mode'
        single = dict.fromkeys(names, 0)
        arms = {name: dict(left=0, right=0) for name in names}
        inferred = dict.fromkeys(names, 0)
        for index, (row, native_pose) in enumerate(zip(trace, source)):
            assert row['frameIndex'] == index and row['sourcePtsMicroseconds'] == pts[index], 'Pose-mode PTS/order mismatch'
            timestamp = dict(value=pts[index], timescale=1_000_000)
            assert native_pose['timestamp'] == timestamp, 'Pose-mode timeline differs from required native run'
            assert re.fullmatch('[0-9a-f]{64}', row['pixelSha256']), 'Missing shared pixel checksum'
            assert row['referenceEdge'] == original['referenceEdge'], 'Frozen reference edge changed'
            assert row['inferenceOrder'] == (['video', 'image'] if index % 2 == 0 else ['image', 'video']) + (['crop'] if cropped else []), 'Comparison order changed'
            assert set(row['modes']) == names
            for name in names:
                observation = row['modes'][name]; pose = observation['pose']
                assert pose['timestamp'] == timestamp and pose['imageSize'] == native_pose['imageSize'], 'Mode source geometry/PTS changed'
                if name == 'crop':
                    performed = crop_observation(observation,row['modes']['video']['pose'])
                else:
                    backend = 'MediaPipe Heavy CPU IMAGE visibility/presence minimum' if name == 'image' else 'MediaPipe Heavy CPU visibility/presence minimum'
                    assert pose['backend'] == backend and finite_positive(observation['inferenceMillis']), 'Unexpected pose backend/timing'
                    performed = True
                assert pose['requestRevision'] == 1, 'Unexpected pose revision'
                assert observation['counter']['policyVersion'] == 8, 'Comparison count policy changed'
                inferred[name] += performed
                single[name] += len(pose['people']) == 1
                assert len(observation['arms']) == 2 and {arm['side'] for arm in observation['arms']} == {'left', 'right'}
                for arm in observation['arms']:
                    arms[name][arm['side']] += arm.get('estimate') is not None
        for name, summary in case['modes'].items():
            assert summary['singlePersonFrames'] == single[name] and summary['usableArmFrames'] == arms[name], 'Mode coverage summary differs from trace'
            count = summary['observedMovements']
            assert isinstance(count, int) and not isinstance(count, bool) and count >= 0
            assert count == trace[-1]['modes'][name]['counter']['observedMovements'], 'Mode count summary differs from trace'
            assert count == sum(event['outcome'] == 'movement' for event in summary['events']), 'Mode count events incomplete'
            latency = summary['inferenceLatencyMillis']
            assert latency['samples'] == inferred[name], 'Inference timing includes unperformed calls'
            if name == 'crop':
                assert summary['skippedFrames'] == frames-inferred[name], 'Crop skip summary differs from source trace'
                assert inferred[name] > 0 or count == 0, 'Unobserved crop cannot claim movements'
            assert (finite_positive(latency.get('p95')) if inferred[name] else set(latency) == {'samples'})
            print(f"{identifier} {name}: fixed-edge {count}/{expected}; usable arms {arms[name]}; inference {inferred[name]}/{frames} frames; p95 {latency.get('p95', 'unavailable')}ms", flush=True)
    print('Complete source-pinned pose comparison collected. Diagnostic counts do not qualify Auto, full pipeline performance, athlete identity, form or physical parity.')


def setup(path):
    report = json.loads(path.read_text())
    assert report['schemaVersion'] == 1 and not report['failures'], 'Native setup integration failed'
    expected = {'pullup_held_start': 'pullUp', 'dip_held_start': 'dip'}
    assert len(report['cases']) == len(expected) and {case['id'] for case in report['cases']} == set(expected), 'Missing native setup fixture'
    for case in report['cases']:
        assert case['pass'] and case['decodedFrames'] == 72, 'Authored setup timeline not fully evaluated'
        assert case['selectedExercise'] == expected[case['id']] and case['acquiredActualContours']
        assert case['finalPhase'] == 'running' and case['observedMovements'] == 0, 'Setup did not start or earned a false rep'
        assert {1, 2, 3}.issubset(set(case['countdownValues'])), 'Three-second countdown missing'
        start = case['firstCountdownSeconds']; running = case['firstRunningSeconds']
        assert finite_positive(start) and finite_positive(running) and running - start >= 3 - 1e-6
        print(f"{case['id']}: actual contour acquisition, Auto {case['selectedExercise']}, three-second countdown, zero false reps")
    print('Authored held-frame setup only; excludes dynamic movement and physical-phone qualification.')


def matching_inputs(paths):
    """Compare actual native-verified media, independently of pose/count agreement."""
    reports = [json.loads(path.read_text()) for path in paths]
    assert {report['sdk'] for report in reports} == {29, 35}, 'Need distinct API29/API35 results'
    native_cases = [{case['id']: case for case in cases(report)} for report in reports]
    setup_reports = [json.loads(path.with_name('setup-report.json').read_text()) for path in paths]
    assert {report['apiLevel'] for report in setup_reports} == {29, 35}, 'Missing setup API pair'
    for native_report, setup_report in zip(reports, setup_reports):
        assert native_report['sdk'] == setup_report['apiLevel'], 'Setup/native API mismatch'
    for key in ('modelSha256', 'poseArtifactSha256'):
        values = [report[key] for report in reports]
        assert all(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) for value in values), 'Missing model/runtime checksum'
        assert values[0] == values[1], 'Model/runtime inputs differ: ' + key
        setup_key = 'backendAarSha256' if key == 'poseArtifactSha256' else key
        assert all(report[setup_key] == values[0] for report in setup_reports), 'Setup model/runtime inputs differ'
    setup_cases = [{case['id']: case for case in report['cases']} for report in setup_reports]
    for report, entries in zip(setup_reports, setup_cases):
        assert len(report['cases']) == 2 and set(entries) == {'pullup_held_start', 'dip_held_start'}, 'Missing or duplicate setup input'
        assert all(case['expectedFrames'] == case['decodedFrames'] == 72 for case in entries.values()), 'Setup input timeline changed'
    for pair in (native_cases, setup_cases):
        for identifier in sorted(pair[0]):
            hashes = [entries[identifier]['sha256'] for entries in pair]
            assert all(isinstance(value, str) and re.fullmatch('[0-9a-f]{64}', value) for value in hashes), 'Missing video checksum: ' + identifier
            print(f'{identifier}: API29/API35 derivative checksums {hashes[0]} / {hashes[1]}', flush=True)
            assert hashes[0] == hashes[1], 'Native evaluation video inputs differ: ' + identifier
    print('Both APIs evaluated identical dynamic/setup media and model/runtime bytes. This does not establish identical decoded pixels or vision outputs.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('core', 'native', 'setup', 'accuracy', 'pose_modes'):
        parser.add_argument('--' + name.replace('_', '-'), type=Path)
    parser.add_argument('--matching-inputs', type=Path, nargs=2, metavar=('API29_REPORT', 'API35_REPORT'))
    args = parser.parse_args()
    assert any(vars(args).values())
    for name, path in vars(args).items():
        if path:
            globals()[name](path)
