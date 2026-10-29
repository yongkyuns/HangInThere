#!/usr/bin/env python3
"""Same-validation RGB anatomy comparison; not a fresh final-test claim."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
import collections,hashlib,importlib.util,json
from pathlib import Path
import torch
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-front-rear-v9'
spec=importlib.util.spec_from_file_location('surface',ROOT/'scripts/train-grip-front-rear-v9.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def score(path,items):
    model=torch.jit.load(str(path)).eval();h,_=s.base.data(items);labels=s.targets(items)
    with torch.inference_mode():out=s.infer(model,h,True)
    groups=collections.defaultdict(list);counts={}
    for i,name in enumerate(s.SURFACES):
        good=labels[0]==i;correct=out[0].argmax(1)==i
        counts[name]=dict(correct=int((good&correct).sum()),total=int(good.sum()),recall=float(correct[good].float().mean()))
        for (r,p),valid,right in zip(items,good,correct):
            if valid:groups[name,r['annotation'].get('sourceGroup') or r['recordingId']].append(int(right))
    weighted={name:sum(sum(v)/len(v) for (n,g),v in groups.items() if n==name)/sum(n==name for n,g in groups) for name in s.SURFACES}
    accepted=(out[0].max(1).values>=.8)&(out[1][:,1]>=.8)&(out[2][:,1]>=.8)
    invalid=(labels[1]==0)|(labels[2]!=1)
    return dict(modelSHA256=sha(path),surface=counts,macroRecall=sum(v['recall'] for v in counts.values())/3,
        sourceGroupMacroRecall=weighted,clearVisibilityConfusion=s.confusion(out[1],labels[1]),closedContactConfusion=s.confusion(out[2],labels[2]),
        acceptedAnatomyAndQualityCrops=int(accepted.sum()),acceptedUnclearOrNoncontactCrops=int((accepted&invalid).sum()),
        qualification='Anatomy + visibility + contact confidence only; no grip head and no end-to-end phone accuracy')
def main():
    torch.set_num_threads(2)
    rows=s.base.load(OUT/'fitting.jsonl');items=[x for x in s.base.pairs(rows) if x[0]['annotation']['split']=='validation']
    report=dict(qualification=__doc__,inputSHA256=sha(OUT/'fitting.jsonl'),validationCrops=len(items),
        thresholds=dict(surface=.8,visible=.8,contact=.8),
        baseline=score(ROOT/'build/grip-surface-v2/model/GripSurfaceClassifier.pt',items),
        candidate=score(OUT/'model/GripSurfaceClassifier.pt',items),
        validationSelectionCaveat='Candidate checkpoint/calibration used these validation groups; results are development evidence. Final reserve remains unread unless independent surface gate passes.')
    (OUT/'validation-comparison.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
if __name__=='__main__':main()
