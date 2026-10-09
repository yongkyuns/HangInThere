#!/usr/bin/env python3
"""Replay actual Android model observations through unchanged production Swift.

This isolates the counter contract from model differences. Inputs must be the three
public-fixture reports; private recordings never belong in this CI directory.
"""
import argparse,hashlib,json,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def main(directory):
    r=json.loads((directory/'report.json').read_text())
    assert {c['id'] for c in r['cases']}=={'pullup_margin','pullup_smoke','dip_controlled_pexels'}
    binary=ROOT/'build/android-swift-counter';binary.parent.mkdir(parents=True,exist_ok=True)
    names=['PoseObservation','Geometry','BarGeometry','ArmMeasurement','PullUpArmMeasurement',
           'DipSupportMeasurement','PullUpProgress','DipProgress','ExerciseCounter']
    subprocess.run(['swiftc','-O','-o',str(binary)]+[str(ROOT/'HangInThere/Analysis'/f'{x}.swift') for x in names]+[str(ROOT/'Evaluation/CountReplay.swift')],check=True)
    evidence=[]
    for case in r['cases']:
        poses=directory/(case['id']+'-observations.jsonl');lines=poses.read_text().splitlines()
        assert len(lines)==case['frames'] and len(lines)>0
        rows=[]
        for i,line in enumerate(lines):
            row=json.loads(line);row.update(frameIndex=i,timebase='source_pts');rows.append(json.dumps(row))
        edge=case['referenceEdge'];coords=[edge['a']['x'],edge['a']['y'],edge['b']['x'],edge['b']['y']]
        result=subprocess.run([str(binary),case['exercise'],case['side']]+list(map(str,coords)),
            input='\n'.join(rows)+'\n',text=True,capture_output=True,check=True)
        swift=json.loads(result.stdout)
        assert swift['summary']['observedMovements']==case['observedMovements'],case['id']+' Swift/Kotlin policy mismatch'
        assert len(swift['events'])==len(case['events']),case['id']+' event count'
        for s,k in zip(swift['events'],case['events']):
            assert s['outcome']==k['outcome'] and s['reason']==k['reason']
            assert abs(s['sourceSeconds']-k['sourceSeconds'])<=1e-9
        evidence.append(dict(id=case['id'],frames=len(lines),observationsSha256=hashlib.sha256(poses.read_bytes()).hexdigest(),
            movements=swift['summary']['observedMovements'],expectedMovements=case['expectedMovements'],
            accuracyPassed=case['observedMovements']==case['expectedMovements'],events=swift['events'],matched=True))
    output=directory/'swift-native-parity.json'
    output.write_text(json.dumps(dict(policyVersion=8,scope='same Android-produced poses and source PTS; production Swift vs Kotlin counter',cases=evidence),indent=2)+'\n')
    print(f'Android model observations match production Swift counting: {output}')
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('directory',type=Path);main(p.parse_args().directory.resolve())
