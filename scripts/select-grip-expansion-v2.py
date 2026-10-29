#!/usr/bin/env python3
"""Fixed-time train/validation samples, with roles frozen before detection."""
import argparse
from collections import defaultdict,Counter
import hashlib
import json
from pathlib import Path


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('plans',type=Path);p.add_argument('review',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not a.output.exists();review=json.loads(a.review.read_text());groups=defaultdict(list)
    for line in a.plans.read_text().splitlines():
        r=json.loads(line);groups[r['caseId']].append(r)
    rows=[];coverage=[]
    def seconds(r):
        t=r['pose']['timestamp'];return t['value']/t['timescale']
    for s in review['sources']:
        if not s['selected']:continue
        assert s['split'] in ['train','validation'] and s['grip'] in ['overhand','underhand']
        group=groups[s['id']];assert group;seen=set()
        for t in review['frameSampling']['seconds']:
            if t>max(map(seconds,group))+.125:
                coverage.append(dict(caseId=s['id'],requested=t,status='beyondSourceEnd'));continue
            r=min(group,key=lambda r:abs(seconds(r)-t));assert abs(seconds(r)-t)<=.26
            assert r['imageSHA256'] not in seen;seen.add(r['imageSHA256'])
            assert r['recordingId']==s['sha256']
            r['annotation']=dict(recordingId=s['sha256'],sourceGroup=s['sourceGroup'],subjectId=s.get('subjectId'),
                split=s['split'],label=s['grip'],viewpoint=s['view'],start=s['start'],end=s['end'],evidence=s['reason'])
            r['requestedSeconds']=t;rows.append(r);coverage.append(dict(caseId=s['id'],requested=t,status='selected',imageSHA256=r['imageSHA256']))
    a.output.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    a.output.with_suffix('.receipt.json').write_text(json.dumps(dict(plansSHA256=hashlib.sha256(a.plans.read_bytes()).hexdigest(),
        reviewSHA256=hashlib.sha256(a.review.read_bytes()).hexdigest(),outputSHA256=hashlib.sha256(a.output.read_bytes()).hexdigest(),coverage=coverage),indent=2)+'\n')
    print(json.dumps(dict(rows=len(rows),partitions=dict(Counter(r['annotation']['split'] for r in rows)))))


if __name__=='__main__':main()
