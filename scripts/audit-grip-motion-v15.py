#!/usr/bin/env python3
"""Verify both disclosed candidates, fixed RGB, phase accounting and contact independence."""
from collections import Counter, defaultdict
import copy
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    s = importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    m = importlib.util.module_from_spec(s)
    s.loader.exec_module(m)
    return m


evaluation = module('motion_evaluation', 'evaluate-grip-motion-v15.py')
temporal = evaluation.temporal


def audit(name, policy):
    root = ROOT/'build'/name
    freeze = json.loads((root/'protocol-freeze.json').read_text())
    assert all(evaluation.sha(p) == digest for p, digest in freeze['sha256'].items())
    refs = json.loads((root/'lifecycle-references.json').read_text())
    assert refs['protocolSHA256'] == evaluation.sha(root/'protocol-freeze.json')
    assert refs['referenceSHA256'] == evaluation.sha(root/'lifecycle-labels.json')
    labels = json.loads((root/'lifecycle-labels.json').read_text())['frames']
    report = json.loads((root/'report.json').read_text())
    results = json.loads((root/'results.json').read_text())
    observations = evaluation.readlines(root/'observations.jsonl')
    groups = defaultdict(list)
    for r in observations:
        groups[r['recordingId']].append(r)
    oracle_counts = Counter()
    oracle_cases = []
    reasons = Counter()
    body_cases, negative_cases = set(), defaultdict(set)
    for case in freeze['cases']:
        frames = groups[case['recordingId']]
        original = policy.decide(frames, temporal, 'motion-no-contact')
        for contact in [0., 1.]:
            altered = copy.deepcopy(frames)
            for f in altered:
                for h in f['hands']:
                    h['contact'] = contact
            assert policy.decide(altered, temporal, 'motion-no-contact') == original
        for f, state in zip(frames, original['states']):
            phase = labels[f['imageSHA256']]['phase']
            if phase in ['setup', 'release']:
                negative_cases[phase].add(case['recordingId'])
            if f['exercise']['eligible']:
                body_cases.add(case['recordingId'])
                reasons[state['reason']] += 1
        # Post-score, privileged diagnostic only. Source labels never enter
        # the automatic result; this is not an alternative deployable policy.
        forced = copy.deepcopy(frames)
        for f in forced:
            ready = case['view'] in ['front', 'rear']
            f['contextReady'] = ready
            f['view'] = [float(case['view'] == v) for v in ['front', 'rear', 'oblique']] if ready else [1/3]*3
        oracle = policy.decide(forced, temporal, 'motion-no-contact')
        predictions = {s['label'] for f, s in zip(forced, oracle['states'])
                       if labels[f['imageSHA256']]['phase'] == 'exercise' and s['label'] != 'unknown'}
        pred = next(iter(predictions)) if len(predictions) == 1 else 'unknown'
        category = 'unknown' if pred == 'unknown' else 'unverifiable' if case['grip'] == 'unknown' else 'correct' if pred == case['grip'] else 'wrong'
        oracle_counts[category] += 1
        if pred != 'unknown':
            oracle_cases.append(dict(caseId=case['caseId'], grip=case['grip'], prediction=pred, category=category))
    return dict(verifiedFreezePins=len(freeze['sha256']), allContactCounterfactualsIdentical=True,
                bodyEligibleVideos=len(body_cases), negativeVideos={k: len(v) for k, v in negative_cases.items()},
                bodyEligibleRGBBlockReasons=dict(reasons),
                privilegedReviewedOrientationDiagnostic=dict(qualification='Post-score oracle diagnostic, not automatic accuracy. Only known front/rear reference view substituted; no hand/threshold/motion changes.',
                    counts=dict(oracle_counts), accepted=oracle_cases),
                counts=report['metrics']['motion-no-contact'], outputSHA256={str(p.relative_to(ROOT)): evaluation.sha(p)
                    for p in [root/x for x in ['report.json', 'results.json', 'observations.jsonl', 'body-trajectories.jsonl',
                                                'protocol-freeze.json', 'lifecycle-labels.json', 'lifecycle-references.json']]})


def main():
    a = audit('grip-motion-v15', evaluation.motion)
    b = audit('grip-motion-v15b', module('motion_revision', 'grip-exercise-motion-v15b.py'))
    first = evaluation.readlines(ROOT/'build/grip-motion-v15/observations.jsonl')
    second = evaluation.readlines(ROOT/'build/grip-motion-v15b/observations.jsonl')
    assert len(first) == len(second)
    for x, y in zip(first, second):
        assert {k: v for k, v in x.items() if k != 'exercise'} == {k: v for k, v in y.items() if k != 'exercise'}
    # Preserve earlier experimental evidence, including the unopened reserve.
    checked = 0
    for name, field in [('grip-oracle-v7/final/inference-freeze.json', 'sha256'),
                        ('grip-front-rear-v9/completion-receipt.json', 'outputs')]:
        data = json.loads((ROOT/'build'/name).read_text())
        for p, digest in data[field].items():
            assert evaluation.sha(p) == digest, p
            checked += 1
    result = dict(initial=a, perArmRevision=b, unchangedRGBObservations=len(first),
                  preservedEarlierPins=checked, phoneInstalled=False,
                  deployment='Offline only. No model fit, no grip confidence threshold change, no rep/readiness changes. No fresh qualification claimed.',
                  auditScriptSHA256=evaluation.sha(__file__))
    evaluation.write(ROOT/'build/grip-motion-v15b/verification.json', result)
    print(json.dumps(result, indent=2))


if __name__ == '__main__':
    main()
