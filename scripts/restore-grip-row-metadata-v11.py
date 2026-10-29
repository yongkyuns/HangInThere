#!/usr/bin/env python3
"""Restore exact source-row provenance without overwriting completed experiments."""
import copy,hashlib,importlib.util,json
from pathlib import Path
import torch
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-anatomy-v11'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
    original=OUT/'fitting.jsonl';target=OUT/'fitting-restored-row-metadata.jsonl';assert not target.exists()
    spec=importlib.util.spec_from_file_location('s',ROOT/'scripts/train-grip-front-rear-v9.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
    rows=s.base.load(original);items=s.base.pairs(rows);index={x['id']:x for x in json.loads((OUT/'review-index.json').read_text())['items']};restored=[];changes=[]
    for r,p in items:
        old=index[p['threewayReview']['anatomyReviewId']]['row'];new=dict(copy.deepcopy(old),patches=[p]);restored.append(new)
        if r['annotation']!=old['annotation']:
            changes.append(dict(id=p['threewayReview']['anatomyReviewId'],before=r['annotation'],restored=old['annotation']))
        # Every field consumed by surface, quality or grip training must match.
        assert r['recordingId']==new['recordingId'] and r['imageSHA256']==new['imageSHA256']
        assert r['annotation']['split']==new['annotation']['split'] and r['annotation'].get('sourceGroup')==new['annotation'].get('sourceGroup')
        # Duplicate native exports may use different paths for identical RGB.
        assert sha(Path(r['framesDirectory'])/r['image'])==sha(Path(new['framesDirectory'])/new['image'])==r['imageSHA256']
    target.write_text(''.join(json.dumps(r)+'\n' for r in restored))
    loaded=s.base.load(target);fixed=s.base.pairs(loaded)
    assert [p for r,p in items]==[p for r,p in fixed]
    for split in ['train','validation']:
        a=[x for x in items if x[0]['annotation']['split']==split];b=[x for x in fixed if x[0]['annotation']['split']==split]
        for yy,zz in zip(s.targets(a),s.targets(b)):
            assert torch.equal(yy,zz) and torch.equal(s.group_weights(a,yy),s.group_weights(b,zz))
        h,v=s.base.data(a);hh,vv=s.base.data(b);assert torch.equal(h,hh) and torch.equal(v,vv)
    receipt=dict(qualification=__doc__,originalInputSHA256=sha(original),restoredInputSHA256=sha(target),
        originalExperimentalInputRetained=True,affectedCropAnnotations=changes,allOriginalRowMetadataRestored=True,
        allTrainingImagesTargetsWeightsViewsRolesAndOrderBitIdentical=True,noRefittingOrMetricChange=True)
    (OUT/'row-metadata-restoration.json').write_text(json.dumps(receipt,indent=2)+'\n')
    print(json.dumps(dict(affectedCropAnnotations=len(changes),trainingInputsBitIdentical=True)))
if __name__=='__main__':main()
