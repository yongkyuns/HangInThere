#!/usr/bin/env python3
"""Source-bound head/upper-torso RGB crops and soft face visibility cues.

No grip/view labels participate in crop construction. Selected primary athlete
only; absent/ambiguous context is explicit, never a fabricated rear-view label.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
from PIL import Image

FACE = ['nose','leftEye','rightEye','leftEar','rightEar']

def context_geometry(row):
    pose=row['pose']; index=row['patchPlan'].get('personIndex')
    if index is None or not 0<=index<len(pose['people']):return None
    points={p['joint']:p for p in pose['people'][index]['landmarks']}
    shoulders=[points.get(k) for k in ['leftShoulder','rightShoulder']]
    if any(p is None or p['confidence']<.3 for p in shoulders):return None
    w,h=pose['imageSize']['width'],pose['imageSize']['height']
    xy=[(p['position']['x'],p['position']['y']) for p in shoulders]
    if not all(math.isfinite(v) and 0<=v<=lim for p in xy for v,lim in zip(p,[w,h])):return None
    span=math.dist(*xy)
    if span<min(w,h)*.06:return None
    cx=sum(p[0] for p in xy)/2;cy=sum(p[1] for p in xy)/2
    # Head through upper torso, square padded rather than stretched at edges.
    side=math.ceil(span*3.2)
    box=[math.floor(cx-side/2),math.floor(cy-side*.43)]
    box += [box[0]+side,box[1]+side]
    cues=[]
    for name in FACE:
        p=points.get(name)
        valid=p is not None and math.isfinite(p['confidence']) and 0<=p['position']['x']<=w and 0<=p['position']['y']<=h
        # Ignore a face point far outside the selected head/torso crop.
        if valid:valid=box[0]<=p['position']['x']<=box[2] and box[1]<=p['position']['y']<=box[3]
        cues.append(float(p['confidence']) if valid else 0.)
    return box,cues+[1.]

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('output',type=Path)
    a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True);counts={}
    with (a.output/'context.jsonl').open('x') as out:
        for line in a.input.read_text().splitlines():
            row=json.loads(line);geometry=context_geometry(row)
            if geometry is None:
                row['context']=dict(status='unavailable',faceCues=[0.]*6)
            else:
                image=Path(row['framesDirectory'])/row['image']
                assert hashlib.sha256(image.read_bytes()).hexdigest()==row['imageSHA256']
                im=Image.open(image).convert('RGB');assert im.size==(row['pose']['imageSize']['width'],row['pose']['imageSize']['height'])
                box,cues=geometry
                dest=a.output/row['caseId']/row['image'];dest.parent.mkdir(parents=True,exist_ok=True)
                im.crop(box).resize((128,128),Image.Resampling.BILINEAR).save(dest)
                row['context']=dict(status='ready',box=box,faceCues=cues,image=str(dest.absolute()),
                    sha256=hashlib.sha256(dest.read_bytes()).hexdigest(),sourceId=row['recordingId'],imageSHA256=row['imageSHA256'],
                    timestamp=row['pose']['timestamp'],personIndex=row['patchPlan']['personIndex'])
            s=row['context']['status'];counts[s]=counts.get(s,0)+1
            out.write(json.dumps(row)+'\n')
    (a.output/'receipt.json').write_text(json.dumps(dict(policyVersion=1,inputSHA256=hashlib.sha256(a.input.read_bytes()).hexdigest(),counts=counts),indent=2)+'\n')
    print(counts)

if __name__=='__main__':main()
