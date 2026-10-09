#!/usr/bin/env python3
"""Fail closed on missing evidence. Emulator budgets are regressions, not phone promises.

Original capture accuracy stays a separate, visibly failing CI job. Changing a
fixture's role never relaxes source integrity, native execution or resource checks.
"""
import argparse
import json
import math
from pathlib import Path

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


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    for name in ('core', 'native', 'setup', 'accuracy'):
        parser.add_argument('--' + name, type=Path)
    args = parser.parse_args()
    assert any(vars(args).values())
    for name, path in vars(args).items():
        if path:
            globals()[name](path)
