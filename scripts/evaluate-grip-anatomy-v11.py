#!/usr/bin/env python3
"""Compare fixed models on original AND reviewed development references."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2');os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse,collections,hashlib,importlib.util,json
from pathlib import Path
import torch
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-anatomy-v11'
spec=importlib.util.spec_from_file_location('s',ROOT/'scripts/train-grip-front-rear-v9.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def score(prob,items,original=False):
    counts=collections.defaultdict(lambda:dict(correct=0,total=0));groups=collections.defaultdict(list)
    accepted=(prob[0].max(1).values>=.8)&(prob[1][:,1]>=.8)&(prob[2][:,1]>=.8)
    good_accept=bad_accept=0;known=0;categories=collections.Counter()
    for i,(r,p) in enumerate(items):
        ref=p['threewayReview']['originalReview'] if original else p['threewayReview'];surface=ref['surface'];categories[ref.get('anatomyReviewCategory',surface)]+=1
        valid=surface in s.SURFACES
        right=valid and s.SURFACES[int(prob[0][i].argmax())]==surface
        if valid:
            known+=1;counts[surface]['total']+=1;counts[surface]['correct']+=int(right)
            group=r['annotation'].get('sourceGroup') or r['recordingId'];groups[surface,group].append(int(right))
        if accepted[i]:
            if right and ref['contact']=='closed':good_accept+=1
            if not valid or ref['contact']!='closed':bad_accept+=1
    for name,v in counts.items():v['recall']=v['correct']/v['total']
    macro=sum(v['recall'] for v in counts.values())/3
    grouped={name:sum(sum(v)/len(v) for (surface,g),v in groups.items() if surface==name)/sum(surface==name for surface,g in groups) for name in s.SURFACES}
    return dict(surface=dict(counts),macroRecall=macro,sourceGroupMeanRecall=grouped,knownCrops=known,totalCrops=len(items),referenceCategories=dict(categories),
        acceptedAnatomyAndQuality=int(accepted.sum()),acceptedCorrectClosed=good_accept,acceptedUnclearMixedOrNoncontact=bad_accept,
        correctAcceptedPerAllInputCrops=good_accept/len(items),qualification='All 218 inputs retained; component confidence only, not final grip or end-to-end camera accuracy')
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--with-quality',action='store_true');args=parser.parse_args()
    torch.set_num_threads(2)
    rows=s.base.load(OUT/'fitting.jsonl');items=[x for x in s.base.pairs(rows) if x[0]['annotation']['split']=='validation'];h,_=s.base.data(items)
    models=dict(baseline=ROOT/'build/grip-surface-v2/model/GripSurfaceClassifier.pt',anatomyOnly=OUT/'anatomyOnly/GripSurfaceClassifier.pt',joint=OUT/'joint/GripSurfaceClassifier.pt')
    if args.with_quality:models['frozenQuality']=OUT/'frozenQuality/GripSurfaceClassifier.pt'
    results={}
    for name,path in models.items():
        m=torch.jit.load(str(path)).eval()
        with torch.inference_mode():prob=s.infer(m,h,True)
        results[name]=dict(modelSHA256=sha(path),originalReferences=score(prob,items,True),reviewedReferences=score(prob,items,False))
    receipts={n:json.loads((OUT/n/'receipt.json').read_text()) for n in ['anatomyOnly','joint']}
    assert receipts['anatomyOnly']['samplePlanSHA256']==receipts['joint']['samplePlanSHA256']
    assert receipts['anatomyOnly']['qualityHeadParametersUnchanged']
    report=dict(qualification=__doc__,inputSHA256=sha(OUT/'fitting.jsonl'),reviewSHA256=sha(OUT/'review.json'),freezeSHA256=sha(OUT/'training-freeze.json'),
        samplePlansIdentical=True,results=results,trainingReceipts=receipts,
        caveat='All references are agent development annotations. Both checkpoints were selected on this validation set. Revised-reference improvement is not new final-test accuracy; original-reference comparisons remain mandatory.',
        fullGripCohortReady=json.loads((OUT/'cohort-audit.json').read_text())['cohortReadyForFullGripRetraining'],
        finalReserveInspected=False,phoneModelEnabled=False)
    destination=OUT/('comparison-quality.json' if args.with_quality else 'comparison.json');assert not destination.exists()
    destination.write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({n:dict(originalMacro=x['originalReferences']['macroRecall'],reviewedMacro=x['reviewedReferences']['macroRecall'],reviewedSurface=x['reviewedReferences']['surface'],accepted=x['reviewedReferences']['acceptedAnatomyAndQuality'],badAccept=x['reviewedReferences']['acceptedUnclearMixedOrNoncontact']) for n,x in results.items()},indent=2))
if __name__=='__main__':main()
