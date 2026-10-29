#!/usr/bin/env python3
"""Bind two explicit direct hand-surface reviews; never derive them from grip/view."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

GRIPS=['overhand','underhand','neutral']
SURFACES=['palm','dorsum','side','unclear']
CONTACTS=['closed','open','invalid']


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def bind(data, first, second):
    expected={p['reviewId'] for r in data for p in r['patches'] if p['status']=='patchReady'}
    assert expected==set(first['patches'])==set(second['patches']), 'Every actual patch needs two explicit reviews'
    reviews=[];frames=[]
    for row in data:
        ann=row['annotation'];ident=row.get('benchmarkFrameId',row.get('frameReviewId'))
        assert ident,'Source frame review identity required'
        correction=first.get('sources',{}).get(row['caseId'],{})
        frame=first.get('frames',{}).get(ident,{})
        t=row['pose']['timestamp'];time=t['value']/t['timescale']
        start=correction.get('start',ann['start']);end=correction.get('end',ann['end'])
        grip=frame.get('grip',correction.get('grip',ann['label']))
        view=frame.get('view',correction.get('view',row.get('reviewedViewpoint',ann['viewpoint'])))
        assert grip in GRIPS+['exclude'] and view in ['front','rear','oblique']
        active=start<=time<=end and frame.get('active',True) and grip!='exclude'
        fr=dict(id=ident,caseId=row['caseId'],recordingId=row['recordingId'],imageSHA256=row['imageSHA256'],
            timestamp=row['pose']['timestamp'],split=ann['split'],grip=grip,view=view,active=active,
            bodyEligible=frame.get('bodyEligible',row.get('bodyReferenceEligible',True)),reason=frame.get('reason',correction.get('reason','Direct source/crop review')))
        row['threewayFrameReview']=fr;frames.append(fr)
        for patch in row['patches']:
            if patch['status']!='patchReady':continue
            key=patch['reviewId'];a=first['patches'][key];b=second['patches'][key]
            assert a['surface'] in SURFACES and b['surface'] in SURFACES
            assert a['contact'] in CONTACTS and b['contact'] in CONTACTS
            conflict=a['surface']!=b['surface'] or a['contact']!=b['contact']
            adjudicated=first.get('adjudications',{}).get(key)
            assert not conflict or adjudicated, 'Conflicting review must be visually adjudicated: '+key
            surface=adjudicated['surface'] if adjudicated else a['surface']
            contact=adjudicated['contact'] if adjudicated else a['contact']
            target=a.get('grip',grip)
            assert target in GRIPS+['exclude']
            if not active or contact!='closed':target='exclude'
            if patch.get('origin') in ['manualReviewedTrainingCrop','manualTrainingRegion']:
                assert ann['split']=='train','Manual localization must not enter held-out inputs'
            review=dict(reviewId=key,frameId=ident,caseId=row['caseId'],recordingId=row['recordingId'],
                imageSHA256=row['imageSHA256'],patchSHA256=patch['patchSHA256'],timestamp=row['pose']['timestamp'],
                side=patch['side'],box=patch['box'],split=ann['split'],surface=surface,contact=contact,grip=target,view=view,
                firstPass=a,secondPass=b,conflict=conflict,adjudication=adjudicated,qualityEligible=a.get('qualityEligible',True),
                method='Two explicit visual reviews of actual hand RGB; surface never derived from grip or athlete view')
            patch['threewayReview']=review;reviews.append(review)
    return frames,reviews


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['input','first','second','output','review']:p.add_argument(n,type=Path)
    a=p.parse_args();assert not a.output.exists() and not a.review.exists()
    data=[json.loads(l) for l in a.input.read_text().splitlines()]
    first=json.loads(a.first.read_text());second=json.loads(a.second.read_text())
    assert first['inputSHA256']==second['inputSHA256']==sha(a.input),'Review bound to different input pixels'
    frames,reviews=bind(data,first,second)
    a.output.write_text(''.join(json.dumps(r)+'\n' for r in data))
    a.review.write_text(json.dumps(dict(schemaVersion=1,qualification='Two agent visual passes, not independent human gold labels; inference inputs remain unchanged',
        inputs={str(f):sha(f) for f in [a.input,a.first,a.second]},outputSHA256=sha(a.output),
        reviewedPages=dict(first=first['reviewedPages'],second=second['reviewedPages']),frames=frames,patches=reviews),indent=2)+'\n')
    print(dict(frames=len(frames),patches=len(reviews),surfaces=dict(Counter(r['surface'] for r in reviews)),
        acceptedGrip=dict(Counter(r['grip'] for r in reviews)),conflicts=sum(r['conflict'] for r in reviews)))


if __name__=='__main__':main()
