#!/usr/bin/env python3
"""Check grip and auxiliary orientation parity for the frozen native export."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__);p.add_argument('reference',type=Path);p.add_argument('native',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
spec=importlib.util.spec_from_file_location('patch_check',Path(__file__).with_name('check-grip-patch-native.py'));check=importlib.util.module_from_spec(spec);spec.loader.exec_module(check)
expected={r['patch']:r for l in a.reference.read_text().splitlines() if (r:=json.loads(l)).get('patch')}
actual=[json.loads(l) for l in a.native.read_text().splitlines()];result=check.compare(expected,actual)
maximum=0.;mismatches=0
for r in actual:
 ref=expected[r['patch']]['orientationScores'];native=r['orientation']
 assert len(native)==3 and all(0<=v<=1 for v in native)
 maximum=max(maximum,max(abs(x-y) for x,y in zip(ref,native)))
 mismatches+=max(range(3),key=lambda i:ref[i])!=max(range(3),key=lambda i:native[i])
result.update(maxOrientationScoreDifference=maximum,orientationArgmaxMismatches=mismatches)
result['passParity']=result['passParity'] and maximum<.01 and mismatches==0
result.update(referenceSHA256=hashlib.sha256(a.reference.read_bytes()).hexdigest(),nativeSHA256=hashlib.sha256(a.native.read_bytes()).hexdigest())
a.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result,indent=2))
if not result['passParity']:raise SystemExit('Native predictions differ')
