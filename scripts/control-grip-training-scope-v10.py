#!/usr/bin/env python3
"""Development-only controlled anatomy fitting; camera rejection remains unchanged.

A/B vary only whether original oblique-camera training patches are retained.
Both use the frozen v2 recipe, late blocks, identical expanded v9 validation,
original labels, seed, budget and selection. No new final video is read.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS','2');os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse,hashlib,importlib.util,json
from collections import Counter
from pathlib import Path
import torch
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-regression-v10'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def load_module():
    spec=importlib.util.spec_from_file_location('surface_v2',ROOT/'scripts/train-grip-surface-v2.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s);return s

def inputs(s):
    old=s.base.load(ROOT/'build/grip-surface-v2/fitting.jsonl')
    current=s.base.load(ROOT/'build/grip-front-rear-v9/fitting.jsonl')
    val=[x for x in s.base.pairs(current) if x[0]['annotation']['split']=='validation']
    alltrain=[x for x in s.base.pairs(old) if x[0]['annotation']['split']=='train']
    supported=[x for x in s.base.pairs(current) if x[0]['annotation']['split']=='train']
    key=lambda x:(x[0]['recordingId'],x[1]['patchSHA256'],tuple(x[1]['box']))
    assert {key(x) for x in supported}<={key(x) for x in alltrain}
    assert not {r['recordingId'] for r,p in alltrain}&{r['recordingId'] for r,p in val}
    return dict(allViews=alltrain,supportedOnly=supported),val

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('mode',choices=['freeze','allViews','supportedOnly']);a=parser.parse_args()
    s=load_module();training,val=inputs(s);freeze=OUT/'training-scope-control-freeze.json'
    sources=[ROOT/'build/grip-surface-v2/fitting.jsonl',ROOT/'build/grip-front-rear-v9/fitting.jsonl',ROOT/'scripts/train-grip-surface-v2.py',Path(__file__)]
    hashes={str(p):sha(p) for p in sources}
    if a.mode=='freeze':
        assert not freeze.exists()
        f=dict(qualification=__doc__,inputHashes=hashes,variant='late',warmupEpochs=3,fineTuneEpochs=30,patience=8,seed=3104,
            training={name:dict(crops=len(v),surfaces=dict(Counter(p['threewayReview']['surface'] for r,p in v)),
             recordingIds=sorted({r['recordingId'] for r,p in v})) for name,v in training.items()},validationCrops=len(val),
            unchanged=['validation crops and references','surface/visible/contact targets','model initialization','sampler and loss','augmentation','checkpoint selection','calibration','0.8 thresholds','app camera gate'],
            decision='Diagnostic only; do not deploy, fit fusion, or inspect final reserve. No reference corrections based on model predictions.')
        freeze.write_text(json.dumps(f,indent=2)+'\n');print(json.dumps(f,indent=2));return
    f=json.loads(freeze.read_text());assert f['inputHashes']==hashes
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    output=OUT/('control-'+a.mode);assert not output.exists()
    result=s.fit('late',training[a.mode],val,output,30)
    result.update(qualification=__doc__,control=a.mode,freezeSHA256=sha(freeze),trainingCrops=len(training[a.mode]),validationCrops=len(val),candidateEnabledInApp=False)
    (output/'diagnostic-receipt.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(dict(control=a.mode,coverage=result['surfaceCoverage'])),flush=True)
if __name__=='__main__':main()
