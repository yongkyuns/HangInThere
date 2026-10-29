#!/usr/bin/env python3
"""Export the frozen three-input research model without enabling it in the app."""
import argparse
import hashlib
import json
from pathlib import Path
import torch
import coremltools as ct

p=argparse.ArgumentParser(description=__doc__);p.add_argument('directory',type=Path);a=p.parse_args()
torch.set_num_threads(2)
model=torch.jit.load(str(a.directory/'GripContextClassifier.pt')).eval()
converted=ct.convert(model,inputs=[ct.ImageType(name='hand',shape=(1,3,128,128),scale=1/255),
    ct.ImageType(name='context',shape=(1,3,128,128),scale=1/255),ct.TensorType(name='faceCues',shape=(1,6))],
    outputs=[ct.TensorType(name='scores'),ct.TensorType(name='orientation')],convert_to='mlprogram',
    minimum_deployment_target=ct.target.iOS16,compute_precision=ct.precision.FLOAT32)
converted.short_description='Research hand/upper-body grip classifier with auxiliary orientation'
receipt=json.loads((a.directory/'training-receipt.json').read_text())
cue_order=('actual face confidence,width/shoulder span,height/shoulder span,absolute yaw/pi,absolute roll/pi,context mask'
    if receipt['variant'] in ['oriented','supervisedComponents'] else 'body-pose face confidences nose,leftEye,rightEye,leftEar,rightEar plus context mask')
converted.user_defined_metadata.update(qualification='development-only',labels='overhand,underhand',orientation='front,rear,oblique',
    variant=receipt['variant'],inputContract='Unmirrored RGB 128 hand; '+receipt.get('contextInput','selected-athlete upper torso RGB 128')+'; '+cue_order)
dest=a.directory/'GripContextClassifier-float32.mlpackage';converted.save(str(dest))
r=dict(packageBytes=sum(p.stat().st_size for p in dest.rglob('*') if p.is_file()),
    torchScriptSHA256=hashlib.sha256((a.directory/'GripContextClassifier.pt').read_bytes()).hexdigest(),precision='float32')
(a.directory/'coreml-receipt.json').write_text(json.dumps(r,indent=2)+'\n');print(r)
