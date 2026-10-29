#!/usr/bin/env python3
"""Partition source-bound context manifests and frozen annotation overlays."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).absolute().parents[1]
def rows(path):return [json.loads(l) for l in path.read_text().splitlines()]
def write(path,data):
    path.write_text(''.join(json.dumps(r)+'\n' for r in data))

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('existing',type=Path);p.add_argument('fresh',type=Path);p.add_argument('supplement',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
    views=json.loads((ROOT/'Evaluation/fixtures/grip-context-original-views-v1.json').read_text())['views']
    existing=rows(a.existing);training=[];development=[]
    for row in existing:
        if row['annotation']['split']=='test':development.append(row)
        else:
            assert row['annotation']['split'] in ['train','validation']
            if row['caseId'] in views:
                assert row['annotation']['split']=='train'
                row['annotation']['viewpoint']=views[row['caseId']]
            training.append(row)
    fresh=rows(a.fresh)+rows(a.supplement)
    expected={}
    for name in ['grip-context-fresh-test-v1.json','grip-context-fresh-test-supplement-v1.json']:
        for c in json.loads((ROOT/'Evaluation/fixtures'/name).read_text())['cases']:
            assert c['recordingId'] not in expected;expected[c['recordingId']]=c
    assert set(expected)=={r['recordingId'] for r in fresh}
    for row in fresh:
        e=expected[row['recordingId']]
        for key in ['recordingId','label','split','viewpoint','start','end','sourceGroup','subjectId']:
            assert row['annotation'][key]==e[key]
        assert row['annotation']['split']=='test'
    corrections=json.loads((ROOT/'Evaluation/fixtures/grip-context-test-review-corrections-v1.json').read_text())['corrections']
    withdrawn={c['recordingId'] for c in corrections}
    assert withdrawn<=set(expected)
    unresolved=[r for r in fresh if r['recordingId'] in withdrawn]
    fresh=[r for r in fresh if r['recordingId'] not in withdrawn]
    write(a.output/'unresolved-test-reference.jsonl',unresolved)
    groups=[{r['annotation'].get('sourceGroup') for r in rs if r['annotation'].get('sourceGroup')} for rs in [training,development,fresh]]
    sources=[{r['recordingId'] for r in rs} for rs in [training,development,fresh]]
    for i in range(3):
        for j in range(i):assert not groups[i]&groups[j] and not sources[i]&sources[j],'Source leakage'
    for name,data in [('train-validation',training),('development-test',development),('final-test',fresh)]:write(a.output/(name+'.jsonl'),data)
    exclusions=json.loads((ROOT/'Evaluation/fixtures/grip-patch-expansion-exclusions-v1.json').read_text())
    for name,data in [('training-exclusions',training),('development-exclusions',development)]:
        ids={r['recordingId'] for r in data};subset=dict(exclusions,exclusions=[e for e in exclusions['exclusions'] if e['recordingId'] in ids])
        (a.output/(name+'.json')).write_text(json.dumps(subset,indent=2)+'\n')
    receipt={name:hashlib.sha256((a.output/name).read_bytes()).hexdigest() for name in ['train-validation.jsonl','development-test.jsonl','final-test.jsonl','training-exclusions.json','development-exclusions.json']}
    (a.output/'assembly-receipt.json').write_text(json.dumps(receipt,indent=2)+'\n');print(receipt)

if __name__=='__main__':main()
