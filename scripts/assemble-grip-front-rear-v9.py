#!/usr/bin/env python3
"""Assemble explicitly reviewed development supplements, preserving source roles."""
import copy,hashlib,json,importlib.util
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-front-rear-v9'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def read(p):return [json.loads(x) for x in Path(p).read_text().splitlines()]
def dump(p,x):Path(p).write_text(json.dumps(x,indent=2)+'\n')
def labels(kind,n):
    # Explicit direct pixel annotations, reviewed against source and shuffled crop sheets.
    surface='unclear';contact='closed';grip='exclude';view='rear';note='Hand anatomy/grip insufficiently readable; no grip supervision'
    first=surface
    if kind=='kinetics':
        if n in [1,3,5,8,11,48,50,73]:contact='invalid';note='SDK proposed head/torso, not a hand'
        if n in [9,12]:surface='dorsum';grip='overhand';note='Visible back of tattooed hand on straight bar; provisional underhand corrected'
        if 13<=n<=20:view='front';first='palm';note='Second pass: blurred palm/dorsum ambiguity; doorway grip axis unverified, provisional neutral excluded'
        if n==21:contact='open';note='Released bar; open moving hand'
        if 23<=n<=38:surface='dorsum';grip='overhand';note='Dorsal knuckles/watch wrist on straight green bar'
        if 39<=n<=41:view='oblique';grip='neutral';note='Assisted neutral source has unsupported oblique torso; anatomy blurred'
        if n in [42,43]:surface='palm';view='front';note='Palm visible; angled handle axis insufficient to verify neutral vs pronated'
        if 44<=n<=47:contact='open';view='front';note='Standing open hand after release'
        if n==59:view='front';grip='neutral';note='Real machine grip, closed but anatomy unclear'
        if 60<=n<=70:
            view='front';surface='side' if n in [61,63,65,68,70] else 'dorsum'
            grip='neutral';note='Separate hanging parallel handles, readable actual hand'
            if n in [60,61]:contact='open';grip='exclude';note='Setup reaching/open fingers before suspended set'
        if n in [71,72]:contact='open';note='Released handle, open/blurred hand'
    elif kind=='publishers':
        if n<=21:
            grip='neutral';view='rear' if n<=4 else 'oblique' if n<=14 else 'front'
            surface='side';note='Actual closed thumb-side profile on separate fore/aft neutral handles'
            if n in [8,14,19,21]:surface='unclear';note='Motion blur obscures anatomical surface'
            if n in [6,7,9,10,11,13]:surface='palm';note='Palmar thenar/finger-pad surface visible on neutral handle'
        elif n<=35:
            grip='underhand';surface='palm';note='Visible finger pads/palmar hand below straight bar; supinated source motion confirmed against publisher instructions'
        else:
            grip='neutral';view='oblique';surface='palm' if n in [36,38,40,43,44,47,49,50] else 'dorsum'
            note='Real neutral handles; source is strongly oblique, correct provisional rear label; excluded from supported fitting'
    else:
        grip='neutral';surface='side';note='Closed thumb-side hand profile on fore/aft parallel handles; back of torso visible'
        if n in [8,11,16,17,18]:
            view='oblique';surface='palm';note='Front/side close-up camera cut, not a rear example'
        if n==18:surface='unclear';contact='invalid';grip='exclude';note='SDK proposed silver apparatus cap, not a hand'
    return dict(surface=surface,contact=contact,grip=grip,view=view,note=note,firstSurface=first if kind=='kinetics' and 13<=n<=20 else surface)
def main():
    target=OUT/'fitting.jsonl';assert not target.exists()
    original=ROOT/'build/grip-surface-v2/fitting.jsonl';old=read(original)
    supported=[];rejected=[];refs=[];supplements=[]
    # Previous references remain unchanged, including old view exclusions.
    for row in old:
        row=copy.deepcopy(row)
        good=all(p['threewayReview']['view'] in ['front','rear'] for p in row['patches'] if p['status']=='patchReady')
        (supported if good else rejected).append(row)
    review_inputs={}
    for kind,folder in [('kinetics',OUT/'mined-wrist'),('publishers',OUT/'publishers/mined-pyramid'),('additional',OUT/'publishers-additional/mined-pyramid')]:
        index=folder/'index.json';review_inputs[str(index)]=sha(index)
        for row in read(folder/'rows-unreviewed.jsonl'):
            for p in row['patches']:
                code=int(p['reviewId'][1:]);label=labels(kind,code)
                p['reviewId']=kind+'-'+p['reviewId']
                ref=dict(recordingId=row['recordingId'],imageSHA256=row['imageSHA256'],patchSHA256=p['patchSHA256'],box=p['box'],
                    side=p['side'],reviewId=p['reviewId'],split='validation',timestamp=row['pose']['timestamp'],
                    surface=label['surface'],contact=label['contact'],grip=label['grip'],view=label['view'],
                    qualityEligible=True,firstPass=dict(surface=label['firstSurface'],method='Source RGB plus automatic crop; no classifier predictions'),
                    secondPass=dict(surface=label['surface'],method='Seeded shuffled crop-only sheet; no classifier predictions'),
                    conflict=label['surface']!=label['firstSurface'],adjudication=label['note'],
                    reviewer='Codex two-pass direct pixel review; not independent human gold')
                p['threewayReview']=ref;refs.append(ref)
            # Per-patch view is authoritative; split rows for supported vs rejected inputs.
            for is_supported in [True,False]:
                subset=[p for p in row['patches'] if (p['threewayReview']['view'] in ['front','rear'])==is_supported]
                if subset:
                    item=dict(row,patches=subset);(supported if is_supported else rejected).append(item);supplements.append(item)
    # Exclude known final reserves, and retain all untouched source groups together.
    reserves=[]
    for p in [ROOT/'Evaluation/fixtures/grip-surface-test-reserve-v2.json',OUT/'untouched-final-reserve.json',OUT/'k600-val/untouched-final-reserve.json']:
        d=json.loads(p.read_text());reserves.extend(d['sources'])
    roles={}
    for r in supported+rejected:
        g=r['annotation'].get('sourceGroup') or 'recording:'+r['recordingId'];roles.setdefault(g,set()).add(r['annotation']['split'])
    assert all(len(v)==1 for v in roles.values())
    assert not {r['recordingId'] for r in supported+rejected}&{r['sha256'] for r in reserves}
    assert not set(roles)&{r['sourceGroup'] for r in reserves}
    target.write_text(''.join(json.dumps(r)+'\n' for r in supported))
    (OUT/'unsupported-reviewed.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rejected))
    (OUT/'supplements-reviewed.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in supplements))
    dump(OUT/'review.json',dict(qualification=__doc__,outputSHA256=sha(target),priorInputSHA256=sha(original),
        reviewInputHashes=review_inputs,patches=[p['threewayReview'] for r in supported for p in r['patches'] if p['status']=='patchReady'],
        supplementalReviews=refs,sourceRolesPreserved=True,manualValidationBoxes=False))
    dump(OUT/'combined-final-reserve.json',dict(qualification='All prior surface reserve plus fourteen unread new groups; no decoding, labels, predictions or model selection',sources=reserves))
    print(json.dumps(dict(supportedFrames=len(supported),unsupportedFrames=len(rejected),newReviewedPatches=len(refs))))
if __name__=='__main__':main()
