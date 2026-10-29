#!/usr/bin/env python3
"""Bind completed blind test frame/hand review without changing model inputs."""
import argparse
import hashlib
import json
from pathlib import Path


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['input','sources','notes','output','review']:p.add_argument(n,type=Path)
    a=p.parse_args();assert not a.output.exists() and not a.review.exists()
    source=json.loads(a.sources.read_text());sources={s['id']:s for s in source['sources']};notes=json.loads(a.notes.read_text())
    rows=[json.loads(l) for l in a.input.read_text().splitlines()];frames=[];patches=[]
    for r in rows:
        s=sources[r['caseId']];assert s['sha256']==r['recordingId'] and r['annotation']['split']=='test'
        for k in ['start','end']:
            if k in notes.get('windows',{}).get(r['caseId'],{}):r['annotation'][k]=notes['windows'][r['caseId']][k]
        t=r['pose']['timestamp'];t=t['value']/t['timescale'];ann=r['annotation'];over=notes.get('frames',{}).get(r['frameReviewId'],{})
        correction=notes.get('sourceCorrections',{}).get(r['caseId'],{})
        grip=correction.get('grip',s['grip']);view=over.get('view',s['view'])
        active=ann['start']<=t<=ann['end'] and over.get('gripActive',True)
        frame=dict(frameReviewId=r['frameReviewId'],caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],
            split='test',grip=grip,view=view,gripActive=active,personIndex=r['context'].get('personIndex'),
            bodyEligible=active and r['context']['status']=='ready' and over.get('bodyEligible',True),
            reason=over.get('reason',correction.get('reason',s['reason'])))
        frames.append(frame);r.update(frameReview=frame,reviewedViewpoint=view,manualViewReview=True)
        for patch in r['patches']:
            assert patch.get('origin')!='manualReviewedTrainingCrop','Manual test crop forbidden'
            if patch['status']!='patchReady':continue
            over=notes.get('patches',{}).get(patch['reviewId'],{});target=over.get('grip',grip)
            assert target in ['overhand','underhand','exclude']
            if not active:target='exclude'
            checked=dict(reviewId=patch['reviewId'],frameReviewId=r['frameReviewId'],caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],
                split='test',side=patch['side'],box=patch['box'],patchSHA256=patch['patchSHA256'],grip=target,view=view,
                reason=over.get('reason',correction.get('reason',s['reason'])),reviewMethod=notes.get('reviewMethod','Actual corrected hand RGB and fixed source frame individually inspected before classifier scoring; no predicted labels shown'))
            patch['manualReview']=checked;patch['referenceEligible']=target!='exclude';patches.append(checked)
    a.output.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    review=dict(schemaVersion=2,qualification='Completed agent manual frame and actual hand-pixel references, not independent gold labels; reviews do not gate inference inputs',
        inputs={str(f):sha(f) for f in [a.input,a.sources,a.notes]},outputSHA256=sha(a.output),modelSHA256=source['modelSHA256'],reserveSHA256=source['reserveSHA256'],
        sourceCorrections=notes.get('sourceCorrections',{}),reviewTiming=notes.get('reviewTiming','before classifier scoring'),
        priorReviewSHA256=notes.get('priorReviewSHA256'),reviewedPages=notes['reviewedPages'],frames=frames,patches=patches)
    a.review.write_text(json.dumps(review,indent=2)+'\n');print(json.dumps(dict(frames=len(frames),patches=len(patches),outputSHA256=sha(a.output))))


if __name__=='__main__':main()
