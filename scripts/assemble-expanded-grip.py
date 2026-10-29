#!/usr/bin/env python3
"""Join completed blind frame/patch review; preserve all source split roles."""
import argparse
import copy
from collections import Counter
import hashlib
import json
from pathlib import Path


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def active(r):
    t=r['pose']['timestamp'];t=t['value']/t['timescale'];return r['annotation']['start']<=t<=r['annotation']['end']


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['old','new','sources','notes','reserve','output','review']:p.add_argument(name,type=Path)
    a=p.parse_args();assert not a.output.exists() and not a.review.exists()
    sources=json.loads(a.sources.read_text());lookup={s['id']:s for s in sources['sources']};notes=json.loads(a.notes.read_text())
    reserve=json.loads(a.reserve.read_text());reserved={s['sourceGroup'] for s in reserve['sources']}
    old=[json.loads(l) for l in a.old.read_text().splitlines()];new=[json.loads(l) for l in a.new.read_text().splitlines()]
    patches=[];frames=[]
    for r in old:
        r['reviewedViewpoint']=r.get('reviewedViewpoint',r['annotation']['viewpoint'])
        r['bodyReferenceEligible']=r['context']['status']=='ready' and active(r)
        for patch in r['patches']:
            if 'manualReview' in patch:patches.append(patch['manualReview'])
    for r in new:
        s=lookup[r['caseId']];assert s['selected'] and s['split']==r['annotation']['split']
        assert s['sha256']==r['recordingId'] and s['sourceGroup']==r['annotation']['sourceGroup']
        assert s['split'] in ['train','validation'] and s['sourceGroup'] not in reserved
        window=notes['windows'].get(r['caseId'],{})
        for k in ['start','end']:
            if k in window:r['annotation'][k]=window[k]
        checked=notes['frames'].get(r['frameReviewId'],{});view=checked.get('view',s['view'])
        is_active=active(r) and checked.get('gripActive',True)
        body_eligible=is_active and r['context']['status']=='ready' and checked.get('bodyEligible',True)
        frame=dict(frameReviewId=r['frameReviewId'],caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],
            timestamp=r['pose']['timestamp'],split=s['split'],personIndex=r['context'].get('personIndex'),view=view,
            gripActive=is_active,bodyEligible=body_eligible,reason=checked.get('reason','Source frame and selected subject/torso visually reviewed; model scores hidden'))
        frames.append(frame);r.update(frameReview=frame,reviewedViewpoint=view,manualViewReview=True,bodyReferenceEligible=body_eligible)
        source_correction=notes['sourceCorrections'].get(r['caseId'],{})
        for patch in r['patches']:
            if patch['status']!='patchReady':continue
            override=notes['patches'].get(patch['reviewId'],{})
            target=override.get('grip',source_correction.get('grip',s['grip']))
            reason=override.get('reason',source_correction.get('reason',s['reason']))
            if not is_active:target='exclude';reason='Outside reviewed closed-grip activity window or explicit setup/release frame: '+reason
            if patch.get('origin')=='manualReviewedTrainingCrop':
                assert s['split']=='train','Manual image crop cannot enter validation or test'
                patch.update(manualAcceptanceRequired=False,manualApproved=target!='exclude')
            review=dict(reviewId=patch['reviewId'],frameReviewId=r['frameReviewId'],caseId=r['caseId'],recordingId=r['recordingId'],
                imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],split=s['split'],side=patch['side'],box=patch['box'],patchSHA256=patch['patchSHA256'],
                grip=target,view=view,reason=reason,origin=patch.get('origin','automaticSDK'),
                reviewMethod='Individual actual hand pixels and source frame manually reviewed with predictions hidden; direct binary reference, no derived surface target')
            patch['manualReview']=review;patch['referenceEligible']=target!='exclude';patches.append(review)
    rows=old+new
    assert all(r['annotation']['split'] in ['train','validation'] for r in rows)
    assert not {r['annotation'].get('sourceGroup') for r in rows} & reserved
    ids=[(p['recordingId'],p['reviewId']) for p in patches];assert len(set(ids))==len(ids)
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    review=dict(schemaVersion=2,qualification='Completed agent manual frame and actual RGB patch review, not independent gold adjudication; manual crops confined to training',
        inputs={str(f):sha(f) for f in [a.old,a.new,a.sources,a.notes,a.reserve]},outputSHA256=sha(a.output),sourceCorrections=notes['sourceCorrections'],
        activityWindowCorrections=notes['windows'],frames=frames,patches=patches)
    a.review.write_text(json.dumps(review,indent=2)+'\n')
    used=[p for p in patches if p['grip']!='exclude'];print(json.dumps(dict(rows=len(rows),patchReviews=len(patches),approved=dict(Counter((p['split']+':'+p['grip']) for p in used)),
        approvedVideos=dict(Counter((role+':'+grip) for role,grip,source in {(p['split'],p['grip'],p['recordingId']) for p in used})),manualApproved=sum(p.get('origin')=='manualReviewedTrainingCrop' for p in used))))


if __name__=='__main__':main()
