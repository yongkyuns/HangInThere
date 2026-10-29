#!/usr/bin/env python3
"""Versioned whole-cohort anatomy review; originals, roles and rows stay intact."""
import copy,hashlib,json
from collections import Counter,defaultdict
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-anatomy-v11'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
    target=OUT/'fitting.jsonl';assert not target.exists()
    index=json.loads((OUT/'review-index.json').read_text());first=json.loads((OUT/'pass-one.json').read_text());lookup={x['id']:x for x in first['items']}
    overrides={'H033':('unclear','Source review: blurred hand lacks sufficient palmar structure'),
      'H094':('unclear','Source review: heavy blur and forearm profile do not support dorsal label'),
      'H113':('unclear','Source review: open moving hand is too blurred for surface'),
      'H375':('mixed','Source review: palm/dorsal/edge mixture has no defensible dominant surface')}
    for ident in ['H023','H185','H377','H412']:overrides[ident]=('mixed','Source verification: cropped closed fingers and palm/edge mixture; do not force one class')
    for item in index['items']:
        ident=item['id'];case=item['row']['caseId']
        if 'hKX6CteXJUQ' in case and lookup[ident]['surface']=='palm':overrides[ident]=('mixed','Towel grip contains overlapping/side surfaces; no dominant palmar target established')
        if 'tgH6YD' in case and lookup[ident]['surface']=='palm':overrides[ident]=('unclear','Opaque red glove and motion/exposure obscure anatomical surface')
    rows={};refs=[];changes=[]
    for item in index['items']:
        r=item['row'];p=copy.deepcopy(item['patch']);old=p['threewayReview'];ident=item['id']
        assert sha(p['patch'])==p['patchSHA256']==lookup[ident]['patchSHA256']
        initial=lookup[ident]['surface'];final,note=overrides.get(ident,(initial,'Second shuffled crop-only pass retains first-pass dominant surface/readability'))
        assert final in ['palm','dorsum','side','mixed','unclear']
        ref=dict(old);ref.update(originalReview=copy.deepcopy(old),surface=final if final in ['palm','dorsum','side'] else 'unclear',
            anatomyReviewCategory=final,anatomyReviewId=ident,
            anatomyReviewV11=dict(firstPass=initial,secondPass=final,adjudication=note,
                protocolSHA256=sha(OUT/'review-protocol.json'),reviewer='Codex complete two-pass review, prior disagreements exposed; not independent human gold'),
            conflict=False,adjudication=note)
        p['threewayReview']=ref
        # The same source frame can carry distinct historical annotation
        # windows/provenance. Preserve the entire original row identity.
        key=hashlib.sha256(json.dumps(r,sort_keys=True).encode()).hexdigest()
        if key not in rows:rows[key]=dict(r,patches=[])
        rows[key]['patches'].append(p);refs.append(ref)
        if old['surface']!=ref['surface'] or final=='mixed':changes.append(dict(id=ident,split=r['annotation']['split'],sourceGroup=r['annotation'].get('sourceGroup'),patchSHA256=p['patchSHA256'],before=old['surface'],after=ref['surface'],category=final,note=note))
    result=list(rows.values());result.sort(key=lambda r:(r['annotation']['split'],r['recordingId'],r['imageSHA256']))
    roles=defaultdict(set)
    for r in result:roles[r['annotation'].get('sourceGroup') or r['recordingId']].add(r['annotation']['split'])
    assert all(len(v)==1 for v in roles.values())
    reserve=json.loads((ROOT/'build/grip-front-rear-v9/combined-final-reserve.json').read_text())
    assert not {r['recordingId'] for r in result}&{x['sha256'] for x in reserve['sources']}
    assert not set(roles)&{x['sourceGroup'] for x in reserve['sources']}
    target.write_text(''.join(json.dumps(r)+'\n' for r in result))
    (OUT/'review.json').write_text(json.dumps(dict(qualification=__doc__,outputSHA256=sha(target),priorInputs=index['inputs'],
        protocolSHA256=sha(OUT/'review-protocol.json'),firstPassSHA256=sha(OUT/'pass-one.json'),
        reviewedFirstPassPages=16,reviewedSecondPassPages=16,changedOrPromotedSurfaceSourcePages=9,
        patches=refs,corrections=changes,allInputRowsAndPatchesRetained=True,sourceRolesUnchanged=True),indent=2)+'\n')
    counts=Counter((r['annotation']['split'],p['threewayReview']['anatomyReviewCategory']) for r in result for p in r['patches'])
    print(json.dumps(dict(patches=len(refs),corrections=len(changes),counts={str(k):v for k,v in counts.items()}),indent=2))
if __name__=='__main__':main()
