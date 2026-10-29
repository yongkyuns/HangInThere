#!/usr/bin/env python3
"""Post-score view/texture sensitivity probe, not an accuracy experiment."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import hashlib
import json
from pathlib import Path
import sys
from collections import Counter
import torch
from torchvision.transforms import ToTensor
from PIL import Image


def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    root=Path(sys.argv[1])
    freeze=json.loads((root/'inference-freeze.json').read_text())
    assert all(sha(p)==digest for p,digest in freeze['sha256'].items())
    output=root/'view-texture-probe.json'
    assert not output.exists()
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    model=torch.jit.load(freeze['models']['grip']).eval()
    rows=[json.loads(x) for x in (root/'predictions.jsonl').read_text().splitlines()]
    tensor=ToTensor();results=[]
    labels=['overhand','underhand','neutral'];views=['front','rear','oblique']
    counts={}
    with torch.inference_mode():
        for row in rows:
            if not row['manual']:continue
            patch=row['manual']['1.6']
            assert sha(patch['image'])==patch['sha256']
            rgb=tensor(Image.open(patch['image']).convert('RGB')).unsqueeze(0)
            for kind,hand in [('original',rgb),('constantMeanColor',rgb.mean(dim=(2,3),keepdim=True).expand_as(rgb))]:
                for vi,view in enumerate(views):
                    inputs=torch.zeros(1,3);inputs[0,vi]=1.
                    g,s,c=model(hand,inputs)
                    result=dict(id=row['id'],input=kind,view=view,grip=g[0].tolist(),surface=s[0].tolist(),contact=c[0].tolist(),raw=labels[int(g[0].argmax())])
                    results.append(result)
                    counts.setdefault(kind+':'+view,Counter())[result['raw']]+=1
    output.write_text(json.dumps(dict(qualification='Post-primary-score sensitivity probe. Counterfactual camera views may be inconsistent with the actual image; constant-color controls are out of distribution. No training/threshold tuning or accuracy credit. Tests dependency, not causal mechanism proof.',
        scriptSHA256=sha(__file__),primaryPredictionsSHA256=sha(root/'predictions.jsonl'),freezeSHA256=sha(root/'inference-freeze.json'),modelSHA256=sha(freeze['models']['grip']),counts=counts,results=results),indent=2)+'\n')
    print(json.dumps(counts,indent=2))


if __name__=='__main__':main()
