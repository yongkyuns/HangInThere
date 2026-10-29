#!/usr/bin/env python3
"""Disclosed per-arm revision on the same exposed, recording-held-out cohorts."""
import argparse
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def module(name, filename):
    s = importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    m = importlib.util.module_from_spec(s)
    s.loader.exec_module(m)
    return m


evaluation = module('evaluation_v15', 'evaluate-grip-motion-v15.py')
evaluation.motion = module('motion_v15b', 'grip-exercise-motion-v15b.py')
evaluation.OUT = ROOT/'build/grip-motion-v15b'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['prepare', 'evaluate'])
    args = parser.parse_args()
    if args.stage == 'evaluate':
        evaluation.evaluate()
        return
    evaluation.prepare()
    out = evaluation.OUT
    freeze = out/'protocol-freeze.json'
    f = json.loads(freeze.read_text())
    f['scope'] = 'Per-arm architecture revision after disclosed v15 failure. Same exposed recording-held-out regression; no fresh qualification and no model fitting/threshold changes.'
    for p in [Path(__file__), ROOT/'scripts/grip-exercise-motion-v15b.py',
              ROOT/'scripts/tests/test_grip_exercise_motion_v15b.py', ROOT/'build/grip-motion-v15/report.json']:
        f['sha256'][str(p)] = evaluation.sha(p)
    freeze.write_text(json.dumps(f, indent=2)+'\n')
    labels = ROOT/'build/grip-motion-v15/lifecycle-labels.json'
    (out/'lifecycle-labels.json').write_bytes(labels.read_bytes())
    evaluation.write(out/'lifecycle-references.json', dict(protocolSHA256=evaluation.sha(freeze),
        referenceSHA256=evaluation.sha(labels), reviewer='agent', humanVerified=False,
        review='Exact same pre-first-score source-phase labels; no relabeling after v15 predictions.'))


if __name__ == '__main__':
    main()
