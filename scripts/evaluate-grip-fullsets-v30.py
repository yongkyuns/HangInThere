#!/usr/bin/env python3
"""Separate-source evaluation with source-only capture qualification.

Reuses the audited v21 crop preparation, v17 raw RGB inference and v27 temporal
accounting. The v28 shared replay owns counter/context/width. References never
enter predictions or trigger resets. Compatibility output retains its v27 schema;
report-v30.json records the new cohort and its explicit limitations.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/grip-fullsets-v30'
RUN = OUT / 'run'

def read(path):
    return json.loads(Path(path).read_text())

def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def write(path, obj):
    with Path(path).open('x') as f:
        json.dump(obj, f, indent=2)
        f.write('\n')

def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT/'scripts'/filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result

def verify():
    for name in ['protocol.json', 'reference-freeze.json', 'implementation-freeze.json', 'pre-inference-freeze.json']:
        p = OUT/name
        if p.exists():
            for path, digest in read(p)['sha256'].items():
                assert sha(path) == digest, path
    refs = read(RUN/'source-references.json')
    fitting = '\n'.join((ROOT/p).read_text() for p in [
        'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl',
        'build/grip-threeway/fitting.jsonl'])
    for case in refs['cases'] + refs['excludedCases']:
        assert sha(case['path']) == case['sha256'], case['id']
        assert case['id'] not in fitting and case['sha256'] not in fitting, case['id']
    return refs

def prepare():
    verify()
    target = module('v30prepare', 'evaluate-grip-width-v21.py')
    target.OUT = RUN
    target.verify = verify
    target.prepare()
    paths = [RUN/'source-references.json', RUN/'test/native-input.jsonl', RUN/'test/rows.jsonl']
    for row in [json.loads(s) for s in (RUN/'test/rows.jsonl').read_text().splitlines()]:
        for patch in row['patches']:
            if patch['status'] == 'patchReady':
                assert sha(patch['patch']) == patch['patchSHA256']
                paths.append(Path(patch['patch']))
    write(OUT/'pre-inference-freeze.json', {'sha256': {str(p):sha(p) for p in paths}})

def infer():
    verify()
    target = module('v30infer', 'evaluate-grip-controlled-v17.py')
    target.OUT = RUN
    target.verify = lambda: {'models': {
        'quality': 'build/grip-anatomy-v11/frozenQuality/GripSurfaceClassifier.pt',
        'neutral': 'build/grip-threeway/staged-models/threeway/GripHandClassifier.pt'}}
    target.infer('test')

def score():
    refs = verify()
    target = module('v30score', 'evaluate-grip-fullsets-v27.py')
    target.OUT = OUT
    target.RUN = RUN
    target.verify = verify
    target.score()
    report = read(RUN/'test/report.json')
    report.update(schemaVersion=30, sourceTemporalCorrection=None,
        sourceAcquisitionFailures=refs['acquisitionFailures'],
        qualification=refs['qualification'],
        cohortRoles={c['id']: c['cohortRole'] for c in refs['cases']},
        modelTraining=False, counterChanged=False, phoneInstalled=False)
    write(RUN/'test/report-v30.json', report)

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=['verify', 'prepare', 'infer', 'score'])
    globals()[p.parse_args().action]()
