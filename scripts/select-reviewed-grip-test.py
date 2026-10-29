#!/usr/bin/env python3
"""Select prespecified times only after full-clock subject acquisition."""
import argparse
import hashlib
import json
from collections import defaultdict
from pathlib import Path


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def select(rows, reviews, times):
    groups=defaultdict(list)
    for r in rows:groups[r['caseId']].append(r)
    out=[];coverage=[]
    for source in reviews['sources']:
        if source['grip']=='exclude':continue
        group=groups[source['id']];assert group,'Missing native source'
        def seconds(r):
            t=r['pose']['timestamp'];return t['value']/t['timescale']
        group.sort(key=seconds);used=set()
        annotation=dict(recordingId=source['sha256'],sourceGroup=source['sourceGroup'],
                        label=source['grip'],viewpoint=source['view'],split='test',subjectId=None,
                        start=source.get('start',0),end=source.get('end',1000),
                        evidence=source['reason'])
        for time in times:
            if time>seconds(group[-1])+.125:
                coverage.append(dict(caseId=source['id'],requestedSeconds=time,status='beyondSourceEnd'));continue
            r=min(group,key=lambda r:abs(seconds(r)-time))
            if abs(seconds(r)-time)>.26:
                coverage.append(dict(caseId=source['id'],requestedSeconds=time,status='noNearbyNativeSample'));continue
            assert r['imageSHA256'] not in used;used.add(r['imageSHA256'])
            assert r['recordingId']==source['sha256']
            r['annotation']=annotation;r['requestedSeconds']=time
            out.append(r);coverage.append(dict(caseId=source['id'],requestedSeconds=time,status='selected',
                                              actualSeconds=seconds(r),imageSHA256=r['imageSHA256']))
    return out,coverage


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('plans',type=Path);p.add_argument('review',type=Path)
    p.add_argument('reserve',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not a.output.exists()
    review=json.loads(a.review.read_text());reserve=json.loads(a.reserve.read_text())
    assert review['reserveSHA256']==sha(a.reserve)
    rows,coverage=select([json.loads(l) for l in a.plans.read_text().splitlines()],review,reserve['frameSampling']['seconds'])
    a.output.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    a.output.with_suffix('.receipt.json').write_text(json.dumps(dict(plansSHA256=sha(a.plans),reviewSHA256=sha(a.review),
        reserveSHA256=sha(a.reserve),outputSHA256=sha(a.output),coverage=coverage),indent=2)+'\n')
    print(json.dumps(dict(frames=len(rows),requested=len(coverage),unavailable=sum(x['status']!='selected' for x in coverage))))


if __name__=='__main__':main()
