#!/usr/bin/env python3
"""Disclosed torso-only revision; identical saved hand scores and counter replay."""
import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('v16', ROOT/'scripts/evaluate-grip-rep-context-v16.py')
evaluation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(evaluation)
evaluation.OUT = ROOT/'build/grip-rep-context-v16b'


def freeze():
    out = evaluation.OUT
    out.mkdir(parents=True, exist_ok=True)
    old = ROOT/'build/grip-rep-context-v16'
    protocol = json.loads((old/'protocol.json').read_text())
    assert all(evaluation.sha(p) == h for p,h in protocol['sha256'].items())
    protocol['scope'] = 'Disclosed torso-only architecture revision after v16 score. Same exposed recording-held-out regressions; no new blind qualification, fitting, threshold changes or phone deployment.'
    protocol['changes'] += ' Torso-only assessment drops wrist dependencies. When hips are unobservable, a scoped upright-camera 3D shoulder-axis branch retains the same .85 alignment and .5 shoulder identity scores. Strong contradictory hips still reject identity.'
    protocol['orientation'] = 'WorkoutGripTorsoView: primary/auxiliary shoulder identity within .12 short-side, model shoulders >=.5, 3D axis scale .08..1m. Reliable hips use full torso; otherwise assume upright camera and observed shoulder line within30 degrees of horizontal. No face or hand inputs; no confidence threshold reduction.'
    for p in [Path(__file__), ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGripTorsoView.kt',
              ROOT/'shared/src/commonTest/kotlin/dev/hanginthere/core/WorkoutGripTorsoViewTests.kt',
              ROOT/'Evaluation/androidTests/GripTorsoViewV16Tests.kt', old/'replay-output.jsonl',
              old/'replay-input.jsonl', old/'report.json']:
        protocol['sha256'][str(p)] = evaluation.sha(p)
    evaluation.write(out/'protocol.json', protocol)
    for name in ['native-input.jsonl','replay-input.jsonl']:
        (out/name).symlink_to(old/name)
    print(json.dumps(dict(pins=len(protocol['sha256']), mediaCopied=False)))


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('stage', choices=['freeze','score'])
    args = p.parse_args()
    if args.stage == 'freeze': freeze()
    else:
        original = evaluation.lines(ROOT/'build/grip-rep-context-v16/replay-output.jsonl')
        revised = evaluation.lines(evaluation.OUT/'replay-output.jsonl')
        assert len(original) == len(revised)
        assert [{k:v for k,v in r.items() if k != 'bodyView'} for r in original] == [
            {k:v for k,v in r.items() if k != 'bodyView'} for r in revised]
        evaluation.score()
