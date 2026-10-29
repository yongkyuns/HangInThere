#!/usr/bin/env python3
"""Apply blind activity-window review without dropping or moving fixed frames."""
import argparse
import hashlib
import json
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path)
    p.add_argument('review',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not a.output.exists(),'Preserve earlier reviewed manifests'
    review=json.loads(a.review.read_text());sources={s['id']:s for s in review['sources']}
    rows=[json.loads(l) for l in a.input.read_text().splitlines()]
    for r in rows:
        s=sources[r['caseId']]
        assert r['annotation']['split']=='test' and s['grip']!='exclude'
        assert r['recordingId']==s['sha256'] and r['annotation']['sourceGroup']==s['sourceGroup']
        assert r['annotation']['label']==s['grip'],'Activity review cannot relabel exercise'
        r['annotation']['start']=s.get('start',0);r['annotation']['end']=s.get('end',1000)
    a.output.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    print(json.dumps(dict(rows=len(rows),inputSHA256=hashlib.sha256(a.input.read_bytes()).hexdigest(),
                          reviewSHA256=hashlib.sha256(a.review.read_bytes()).hexdigest(),
                          outputSHA256=hashlib.sha256(a.output.read_bytes()).hexdigest())))


if __name__=='__main__':main()
