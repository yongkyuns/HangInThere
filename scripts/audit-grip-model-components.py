#!/usr/bin/env python3
"""Inspect frozen TorchScript components; no training or weight changes.

Manual front-view overrides are counterfactual diagnostics, never benchmark
predictions. Appearance names describe the intended latent channels; they were
not directly supervised by independent palm/dorsum labels.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import argparse
from collections import Counter,defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import numpy as np
from PIL import Image
import torch

ROOT=Path(__file__).absolute().parents[1]

def rgb(path):
 return torch.from_numpy(np.array(Image.open(path).convert('RGB'),dtype=np.float32)/255).permute(2,0,1)

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path);a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 spec=importlib.util.spec_from_file_location('learning',ROOT/'scripts/train-grip-context.py');learning=importlib.util.module_from_spec(spec);spec.loader.exec_module(learning)
 torch.set_num_threads(2);src=ROOT/'build/grip-context';model_path=src/'oriented-model/GripContextClassifier.pt';model=torch.jit.load(str(model_path)).eval()
 records=[];maximum=0.
 for name,review in [('verified-train-validation.jsonl','training-exclusions.json'),('verified-development-test-corrected.jsonl','development-exclusions.json'),('verified-final-test.jsonl',None)]:
  rows=learning.load(src/name,src/review if review else None);pairs=learning.available(rows)
  with torch.inference_mode():
   for start in range(0,len(pairs),16):
    batch=pairs[start:start+16];h=torch.stack([rgb(p['patch']) for r,p in batch]);c=torch.stack([rgb(r['context']['image']) if r['context']['status']=='ready' else torch.zeros(3,128,128) for r,p in batch]);f=torch.tensor([r['context']['faceCues'] for r,p in batch],dtype=torch.float32)
    he=model.pool(model.features((h-model.mean)/model.std)).flatten(1);ce=model.pool(model.features((c-model.mean)/model.std)).flatten(1)*f[:,5:6]
    appearance=model.head(he).softmax(1);face=model.orientation.face(f);body=.25*model.orientation.body(ce);view=(face+body).softmax(1)
    front=view[:,0]+.5*view[:,2];rear=view[:,1]+.5*view[:,2];over=appearance[:,0]*front+appearance[:,1]*rear
    reconstructed=torch.stack([over,1-over],1).clamp(min=1e-7,max=1-1e-7);reconstructed=reconstructed.log().softmax(1)
    actual,actual_view=model(h,c,f);gap=max((reconstructed-actual).abs().max().item(),(view-actual_view).abs().max().item());maximum=max(maximum,gap);assert gap<1e-5
    for i,(r,p) in enumerate(batch):
     ann=r['annotation'];v=ann.get('viewpoint');derived=None
     if v in ['front','rear']:derived='palm' if (v=='front')==(ann['label']=='overhand') else 'dorsum'
     records.append(dict(caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],side=p['side'],patchSHA256=p['patchSHA256'],
      split=ann['split'],expectedGrip=ann['label'],viewpoint=v,derivedAppearance=derived,faceCues=f[i].tolist(),
      appearanceScores=appearance[i].tolist(),predictedAppearance='palm' if appearance[i,0]>appearance[i,1] else 'dorsum',
      faceOnlyLogits=face[i].tolist(),weightedBodyLogits=body[i].tolist(),faceOnlyOrientation=face[i].softmax(0).tolist(),
      orientation=view[i].tolist(),grip=actual[i].tolist(),oracleFrontGrip=appearance[i].tolist()))
 summary=defaultdict(Counter)
 for r in records:
  key=r['split']+':'+r['expectedGrip']+':'+str(r['viewpoint']);summary[key][r['predictedAppearance']]+=1
  if r['derivedAppearance']:summary[key]['derivedAppearanceCorrect']+=r['predictedAppearance']==r['derivedAppearance']
 result=dict(modelSHA256=hashlib.sha256(model_path.read_bytes()).hexdigest(),scriptSHA256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
  patches=len(records),reconstructionMaxAbsoluteDifference=maximum,appearanceChannelCounts=dict(Counter(r['predictedAppearance'] for r in records)),
  summary=summary,records=records,qualification='fixed-weight component audit; derived appearance semantics and orientation interventions are diagnostic, not gold labels or improved test accuracy')
 (a.output/'model-components.json').write_text(json.dumps(result,indent=2)+'\n')
 print(json.dumps({k:v for k,v in result.items() if k!='records'},indent=2))

if __name__=='__main__':main()
