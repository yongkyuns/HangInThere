#!/usr/bin/env python3
"""Observed-wrist RGB fallback and original-frame recropping, without grip labels.

SDK-ready crops retain their original recipe. A failed hand landmark result may
use observed body wrist/elbow pixels; the learned contact head must judge the
image. This is automatic geometry, never a manually localized held-out hand.
"""
import argparse
from collections import Counter
import copy
import hashlib
import importlib.util
import json
import math
from pathlib import Path

from PIL import Image

spec=importlib.util.spec_from_file_location('threeway_original_crop',Path(__file__).with_name('prepare-reviewed-grip.py'))
crops=importlib.util.module_from_spec(spec);spec.loader.exec_module(crops)


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def observed(person,joint,threshold=.15):
    points=[p for p in person['landmarks'] if p['joint']==joint and p['confidence']>=threshold]
    if len(points)!=1:return None
    xy=points[0]['position']
    return [xy['x'],xy['y']] if all(math.isfinite(xy[k]) for k in ['x','y']) else None


def choose_person(row):
    people=row['pose']['people'];existing=row['patchPlan'].get('personIndex')
    if existing is not None and 0<=existing<len(people):return existing
    scores=[]
    for index,person in enumerate(people):
        raised=0
        for side in ['left','right']:
            wrist=observed(person,side+'Wrist');shoulder=observed(person,side+'Shoulder',.3)
            if wrist and shoulder and wrist[1]<=shoulder[1]:raised+=1
        scores.append((raised,index))
    if not scores:return None
    best=max(s[0] for s in scores)
    candidates=[i for score,i in scores if score==best]
    return candidates[0] if best>0 and len(candidates)==1 else None


def geometry(person,side,size):
    wrist=observed(person,side+'Wrist');toward=observed(person,side+'Elbow') or observed(person,side+'Shoulder',.3)
    if wrist is None or toward is None:return None
    width,height=size;short=min(size)
    if not (0<=wrist[0]<width and 0<=wrist[1]<height):return None
    length=math.dist(wrist,toward)
    extent=math.ceil(min(short*.32,max(24,length*.9)))
    if extent>short:return None
    cx=wrist[0]+(wrist[0]-toward[0])*.18;cy=wrist[1]+(wrist[1]-toward[1])*.18
    x=min(max(0,math.floor(cx-extent/2)),width-extent);y=min(max(0,math.floor(cy-extent/2)),height-extent)
    return [x,y,x+extent,y+extent],wrist


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for n in ['input','output']:p.add_argument(n,type=Path)
    a=p.parse_args();assert not (a.output/'rows.jsonl').exists();a.output.mkdir(parents=True,exist_ok=True)
    data=[json.loads(l) for l in a.input.read_text().splitlines()];counts=Counter()
    for row in data:
        index=choose_person(row);row['originalPatchPlan']=copy.deepcopy(row['patchPlan'])
        if index is not None:
            row['patchPlan']=dict(row['patchPlan'],personIndex=index)
        source=Path(row['framesDirectory'])/row['image'];assert sha(source)==row['imageSHA256']
        image=Image.open(source).convert('RGB')
        for patch in row['patches']:
            if patch['status']=='patchReady':counts['preservedSDK']+=1;continue
            if index is None:counts['noUniqueSubject']+=1;continue
            g=geometry(row['pose']['people'][index],patch['side'],image.size)
            if g is None:counts['noObservedWristOrForearm']+=1;continue
            box,wrist=g;original=copy.deepcopy(patch)
            target=a.output/(row['recordingId']+'-'+row['imageSHA256']+'-'+patch['side']+'-wrist.png')
            image.crop(box).resize((128,128),Image.Resampling.BILINEAR).save(target)
            patch.update(status='patchReady',origin='automaticWristFallback',box=box,patch=str(target.absolute()),patchSHA256=sha(target),
                detectedWrist=wrist,originalAutomatic=original,geometryVersion=1,
                geometry='Observed body wrist/forearm only; .9 forearm square, max .32 short dimension, .18 forearm shift; no grip/view labels used')
            counts['automaticFallback']+=1
    (a.output/'rows.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in data))
    (a.output/'receipt.json').write_text(json.dumps(dict(inputSHA256=sha(a.input),scriptSHA256=sha(__file__),counts=counts),indent=2)+'\n')
    print(json.dumps(counts))


if __name__=='__main__':main()
