#!/usr/bin/env python3
"""Rescore fixed research models on identical corrected source-separated roles."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).absolute().parents[1]
p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);a=p.parse_args();root=a.directory.absolute()
inputs=dict(development=root/'development-test-corrected.jsonl',fresh=root/'final-test.jsonl')
verified=dict(development=root/'verified-development-test-corrected.jsonl',fresh=root/'verified-final-test.jsonl')
models=dict(frozen=ROOT/'build/grip-expansion/reproduced-model/GripPatchClassifier.pt',
    hand=root/'hand-model/GripContextClassifier.pt',context=root/'context-model/GripContextClassifier.pt',oriented=root/'oriented-model/GripContextClassifier.pt')
reports={}
for name,model in models.items():
    for cohort in ['development','fresh']:
        input_path=(verified if name=='oriented' else inputs)[cohort];out=root/(name+'-'+cohort+'-corrected-score')
        if name=='frozen':cmd=[sys.executable,str(ROOT/'scripts/score-grip-patches.py'),str(model),str(input_path),str(out)]
        else:cmd=[sys.executable,str(ROOT/'scripts/train-grip-context.py'),'score',str(input_path),str(out),'--model',str(model)]
        if cohort=='development':cmd+=['--patch-review',str(root/'development-exclusions.json')]
        with (root/(name+'-'+cohort+'-corrected-score.log')).open('w') as log:subprocess.run(cmd,stdout=log,stderr=subprocess.STDOUT,check=True)
        reports[name+':'+cohort]=json.loads((out/'report.json').read_text())
# Model selection uses validation NLL only; held-out statistics are descriptive.
receipts={name:json.loads((model.parent/'training-receipt.json').read_text()) for name,model in models.items() if name!='frozen'}
selected=min(receipts,key=lambda name:receipts[name]['bestValidationNLL'])
comparison=dict(qualification='development-only; provisional corrected references; follow-up holdouts development-exposed',
    threshold=.8,selectedByValidation=selected,validationNLL={name:r['bestValidationNLL'] for name,r in receipts.items()},
    trainingPatches=693,reports=reports,
    models={name:dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest()) for name,path in models.items()},
    inputHashes={name:hashlib.sha256(path.read_bytes()).hexdigest() for name,path in dict(inputs,verifiedDevelopment=verified['development'],verifiedFresh=verified['fresh']).items()})
(root/'comparison.json').write_text(json.dumps(comparison,indent=2)+'\n')
for name,r in reports.items():print(name,json.dumps(r['splits']['test']))
print('Selected on validation:',selected)
