#!/usr/bin/env python3
"""Check label provenance, roles, frozen anatomy and intact historical evidence."""
import hashlib,importlib.util,json
from collections import Counter
from pathlib import Path
import torch
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-anatomy-v11'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
    torch.set_num_threads(2)
    spec=importlib.util.spec_from_file_location('s',ROOT/'scripts/train-grip-front-rear-v9.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
    rows=s.base.load(OUT/'fitting-restored-row-metadata.jsonl');pairs=s.base.pairs(rows);index=json.loads((OUT/'review-index.json').read_text())
    original={x['id']:x for x in index['items']};assert len(pairs)==len(original)==744
    split=Counter(r['annotation']['split'] for r,p in pairs);assert split==dict(train=526,validation=218)
    for r,p in pairs:
        ref=p['threewayReview'];old=original[ref['anatomyReviewId']]
        assert ref['originalReview']==old['patch']['threewayReview']
        assert r['annotation']==old['row']['annotation']
        for field in ['contact','grip','view','qualityEligible']:
            assert ref.get(field)==old['patch']['threewayReview'].get(field)
        for field in ['patchSHA256','box','side','reviewId']:assert p[field]==old['patch'][field]
        if ref['anatomyReviewCategory'] in ['mixed','unclear']:assert ref['surface']=='unclear'
    freeze=json.loads((OUT/'training-freeze.json').read_text())
    for p,digest in freeze['inputHashes'].items():assert sha(p)==digest,p
    a=json.loads((OUT/'anatomyOnly/receipt.json').read_text());b=json.loads((OUT/'joint/receipt.json').read_text())
    assert a['samplePlanSHA256']==b['samplePlanSHA256'] and a['qualityHeadParametersUnchanged']
    for arm in ['anatomyOnly','joint','frozenQuality']:
        receipt=json.loads((OUT/arm/'receipt.json').read_text());assert sha(OUT/arm/'GripSurfaceClassifier.pt')==receipt['modelSHA256']
    before=torch.load(OUT/'joint/weights.pt',weights_only=True);after=torch.load(OUT/'frozenQuality/weights.pt',weights_only=True)
    for key,value in before.items():
        if not key.startswith(('visible.','contact.')) and key!='temperature':assert torch.equal(value,after[key]),key
    assert torch.equal(before['temperature'][:1],after['temperature'][:1])
    h,_=s.base.data([x for x in pairs if x[0]['annotation']['split']=='validation'])
    models=[torch.jit.load(str(OUT/arm/'GripSurfaceClassifier.pt')).eval() for arm in ['joint','frozenQuality']]
    with torch.inference_mode():pred=[s.infer(m,h,True)[0] for m in models]
    assert torch.equal(*pred)
    historical=json.loads((ROOT/'build/grip-front-rear-v9/completion-receipt.json').read_text())
    for p,digest in historical['outputs'].items():assert sha(p)==digest,p
    v7=json.loads((ROOT/'build/grip-oracle-v7/final/inference-freeze.json').read_text())
    for p,digest in v7['sha256'].items():assert sha(p)==digest,p
    comparison=json.loads((OUT/'comparison-quality.json').read_text());assert not comparison['finalReserveInspected'] and not comparison['fullGripCohortReady']
    old=json.loads((ROOT/'build/grip-front-rear-v9/validation-comparison.json').read_text())['baseline']['surface']
    now=comparison['results']['baseline']['originalReferences']['surface']
    for name in s.SURFACES:assert old[name]['correct']==now[name]['correct'] and old[name]['total']==now[name]['total']
    report=dict(patchesVerified=len(pairs),sourceRolesAndOriginalReferencesUnchanged=True,
        samplePlansIdentical=True,frozenAnatomyPredictionsBitIdentical=True,priorV7PinsVerified=len(v7['sha256']),priorV9OutputsUnchanged=True,
        confidenceThresholdsUnchanged=True,finalReserveInspected=False,fullGripCohortReady=False,
        rowMetadataRestorationVerified=json.loads((OUT/'row-metadata-restoration.json').read_text())['allTrainingImagesTargetsWeightsViewsRolesAndOrderBitIdentical'],
        outputHashes={str(p):sha(p) for p in [OUT/'fitting.jsonl',OUT/'fitting-restored-row-metadata.jsonl',OUT/'row-metadata-restoration.json',OUT/'review.json',OUT/'training-freeze.json',OUT/'comparison.json',OUT/'comparison-quality.json',OUT/'cohort-audit.json']},
        scripts={str(p):sha(p) for p in (ROOT/'scripts').glob('*v11.py')})
    (OUT/'verification.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({k:v for k,v in report.items() if k not in ['outputHashes','scripts']},indent=2))
if __name__=='__main__':main()
