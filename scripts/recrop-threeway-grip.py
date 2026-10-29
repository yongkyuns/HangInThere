#!/usr/bin/env python3
"""Original RGB torso/SDK recrop, preserving automatic observed-wrist fallback."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

spec=importlib.util.spec_from_file_location('threeway_crop',Path(__file__).with_name('prepare-reviewed-grip.py'))
prepare=importlib.util.module_from_spec(spec);spec.loader.exec_module(prepare)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not (a.output/'rows.jsonl').exists();data=[json.loads(l) for l in a.input.read_text().splitlines()]
    for row in data:
        for patch in row['patches']:
            if patch.get('origin')=='automaticWristFallback':patch['status']='deferWristFallback'
    data=prepare.recrop(data,a.output/'images')
    for row in data:
        for patch in row['patches']:
            if patch['status']=='deferWristFallback':patch['status']='patchReady'
    (a.output/'rows.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in data))
    (a.output/'receipt.json').write_text(json.dumps(dict(inputSHA256=hashlib.sha256(a.input.read_bytes()).hexdigest(),rows=len(data),ready=sum(p['status']=='patchReady' for r in data for p in r['patches'])),indent=2)+'\n')


if __name__=='__main__':main()
