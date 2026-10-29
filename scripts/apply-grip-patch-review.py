#!/usr/bin/env python3
"""Join independent, source-bound manual grip reviews without changing splits."""
import argparse
import copy
import hashlib
import json
from pathlib import Path


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def apply(rows, review):
    lookup={r['reviewId']:r for r in review['patches']};seen=set();out=copy.deepcopy(rows)
    for row in out:
        reviewed_views=set()
        for patch in row['patches']:
            if patch['status']!='patchReady':continue
            key=patch['reviewId'];checked=lookup[key];seen.add(key)
            for name in ['caseId','recordingId','imageSHA256']:assert checked[name]==row[name]
            for name in ['side','patchSHA256','box']:assert checked[name]==patch[name]
            assert checked['timestamp']==row['pose']['timestamp']
            assert checked['split']==row['annotation']['split'],'Review cannot change source role'
            assert checked['grip'] in ['overhand','underhand','exclude']
            assert checked['view'] in ['front','rear','oblique']
            patch['manualReview']=checked
            reviewed_views.add(checked['view'])
            # A reference review must not silently suppress test-model inputs.
            # Fitting filters labels explicitly; scoring still sees all crops.
            patch['referenceEligible']=checked['grip']!='exclude'
        if reviewed_views:
            assert len(reviewed_views)==1,'Same frame cannot have conflicting torso views'
            view=reviewed_views.pop()
            row['reviewedViewpoint']=view
            if row['annotation']['split']!='test':
                row['annotation']['originalViewpoint']=row['annotation'].get('viewpoint')
                row['annotation']['viewpoint']=view
            row['manualViewReview']=True
        if sum(p['status']=='patchReady' for p in row['patches'])==2:
            a,b=[p['box'] for p in row['patches']]
            area=max(0,min(a[2],b[2])-max(a[0],b[0]))*max(0,min(a[3],b[3])-max(a[1],b[1]))
            union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-area
            if area/union>.35:
                for p in row['patches']:p['status']='overlappingExpandedCrops'
    assert seen==set(lookup),'Missing/unmatched manual review'
    return out


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('review',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not a.output.exists(),'Preserve prior reviewed manifests'
    review=json.loads(a.review.read_text());assert sha(a.input)==review['inputSHA256']
    rows=apply([json.loads(l) for l in a.input.read_text().splitlines()],review)
    a.output.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    print(json.dumps(dict(rows=len(rows),automaticAvailable=sum(p['status']=='patchReady' for r in rows for p in r['patches']),
                         referenceEligible=sum(p.get('referenceEligible',False) for r in rows for p in r['patches']),
                         reviewSHA256=sha(a.review),outputSHA256=sha(a.output))))


if __name__=='__main__':main()
