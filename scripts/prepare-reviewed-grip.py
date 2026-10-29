#!/usr/bin/env python3
"""Recrop original RGB and create blinded, indexed manual-review sheets.

No grip/view annotation enters crop geometry. Original frame, crop and pose
identities are retained. New labels must be supplied by a separate review file.
"""
import argparse
from collections import defaultdict
import copy
import hashlib
import importlib.util
import json
import math
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps

SPEC = importlib.util.spec_from_file_location('context', Path(__file__).with_name('train-grip-context.py'))
base = importlib.util.module_from_spec(SPEC); SPEC.loader.exec_module(base)


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def expanded_hand_box(box, toward, wrist, size):
    """Include contact beyond fingertips, rather than shifting toward elbow."""
    old_side = max(box[2]-box[0], box[3]-box[1])
    side = math.ceil(old_side*1.7)
    if side > min(size)*.45 or side < 16:
        return None
    center = np.array([(box[0]+box[2])/2, (box[1]+box[3])/2])
    away = np.array(wrist)-np.array(toward)
    length = float(np.linalg.norm(away))
    if length > 0: center += away/length*old_side*.2
    x = min(max(0, math.floor(center[0]-side/2)), size[0]-side)
    y = min(max(0, math.floor(center[1]-side/2)), size[1]-side)
    return [x, y, x+side, y+side]


def torso_box(row):
    if row['context']['status'] != 'ready': return None
    person = row['pose']['people'][row['context']['personIndex']]
    p = {x['joint']:x['position'] for x in person['landmarks']}
    left, right = p['leftShoulder'], p['rightShoulder']
    span = math.hypot(left['x']-right['x'], left['y']-right['y'])
    cx, cy = (left['x']+right['x'])/2, (left['y']+right['y'])/2
    side = max(16, round(2*span)); x, y = round(cx-side/2), round(cy-.08*span)
    return [x, y, x+side, y+side]


def recrop(rows, output):
    output.mkdir(parents=True, exist_ok=True)
    for row in rows:
        source = Path(row['framesDirectory'])/row['image']; assert sha(source)==row['imageSHA256']
        frame = Image.open(source).convert('RGB')
        old = copy.deepcopy(row['context']); row['originalContext']=old
        box = torso_box(row)
        if box is not None:
            target = output/(row['recordingId']+'-'+row['imageSHA256']+'-torso.png')
            frame.crop(box).resize((128,128), Image.Resampling.BILINEAR).save(target)
            row['context']=dict(old, image=str(target.absolute()), sha256=sha(target), box=box,
                                geometry='torso-only square side=2 shoulder spans; top=.08 span above shoulders')
        for patch in row['patches']:
            if patch['status']!='patchReady':continue
            patch['originalPatch']=dict(patch)
            plan=row.get('manualPlan',row['patchPlan'])
            targets=[t for t in plan.get('targets',[]) if t['side']==patch['side']]
            if not targets:
                # Manual training extraction preserves its original reviewed search plan.
                points=row['pose']['people'][row['patchPlan']['personIndex']]['landmarks'] if row['patchPlan']['personIndex'] is not None else []
                p={x['joint']:x['position'] for x in points}
                name=patch['side'];joint=name+'Elbow'
                if joint not in p:patch['status']='missingForearmDirection';continue
                toward=[p[joint]['x'],p[joint]['y']]
            else:toward=[targets[0]['forearmToward'][k] for k in ['x','y']]
            box=expanded_hand_box(patch['box'],toward,patch['detectedWrist'],frame.size)
            if box is None:patch['status']='expandedCropTooLarge';continue
            target=output/(row['recordingId']+'-'+row['imageSHA256']+'-'+patch['side']+'.png')
            frame.crop(box).resize((128,128),Image.Resampling.BILINEAR).save(target)
            patch.update(box=box,patch=str(target.absolute()),patchSHA256=sha(target),cropRevision=2)
    return rows


def select(rows):
    groups=defaultdict(list)
    for row in rows:
        if base.base.in_window(row) and any(p['status']=='patchReady' for p in row['patches']):groups[row['recordingId']].append(row)
    selected=[]
    for group in groups.values():
        # Training/validation selection only. No test subset is chosen by labels.
        if group[0]['annotation']['label']=='underhand':indices=range(len(group))
        else:indices=sorted({round(i*(len(group)-1)/5) for i in range(6)})
        selected.extend(group[i] for i in indices)
    return selected


def sheets(rows, output):
    groups=defaultdict(list); candidates=[]
    for row in rows:
        for p in row['patches']:
            if p['status']=='patchReady':
                p['reviewId']=f'R{len(candidates)+1:04d}'
                candidates.append(dict(reviewId=p['reviewId'],caseId=row['caseId'],recordingId=row['recordingId'],
                                       imageSHA256=row['imageSHA256'],timestamp=row['pose']['timestamp'],side=p['side'],
                                       patchSHA256=p['patchSHA256'],box=p['box']))
        groups[row['caseId']].append(row)
    font=ImageFont.load_default(size=17);small=ImageFont.load_default(size=14)
    pages=[]
    for source, group in groups.items():
        for offset in range(0,len(group),6):
            batch=group[offset:offset+6]; canvas=Image.new('RGB',(1160,len(batch)*230+38),'white');draw=ImageDraw.Draw(canvas)
            draw.text((8,8),f'{source} / {group[0]["annotation"]["split"]} / no model scores or proposed labels shown',font=font,fill='black')
            for j,row in enumerate(batch):
                y=j*230+38; frame=Image.open(Path(row['framesDirectory'])/row['image']).convert('RGB');marked=frame.copy();d=ImageDraw.Draw(marked)
                if row['context']['status']=='ready':d.rectangle(row['context']['box'],outline='green',width=2)
                for side,x in [('left',380),('right',760)]:
                    p=next(p for p in row['patches'] if p['side']==side)
                    if p['status']!='patchReady':draw.text((x,y+20),side+': '+p['status'],font=small,fill='black');continue
                    d.rectangle(p['box'],outline='red',width=2)
                    canvas.paste(Image.open(p['originalPatch']['patch']).resize((96,96),Image.Resampling.NEAREST),(x,y+28))
                    canvas.paste(Image.open(p['patch']).resize((160,160),Image.Resampling.NEAREST),(x+105,y+28))
                    draw.text((x,y+3),p['reviewId']+' '+side+' / '+row['image'],font=font,fill='black')
                    draw.text((x,y+195),'old -> corrected crop',font=small,fill='black')
                canvas.paste(ImageOps.contain(marked,(360,190)),(8,y+20))
            name=f'review-{len(pages)+1:03d}.png';canvas.save(output/name);pages.append(dict(image=name,source=source,rows=len(batch)))
    (output/'review-candidates.json').write_text(json.dumps(dict(pages=pages,patches=candidates),indent=2)+'\n')
    return len(candidates),len(pages)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--patch-review',type=Path);p.add_argument('--sample-training',action='store_true');args=p.parse_args()
    assert not (args.output/'rows.jsonl').exists(),'Preserve previous prepared data'
    rows=base.load(args.input,args.patch_review)
    if args.sample_training:
        assert all(r['annotation']['split'] in ['train','validation'] for r in rows)
        rows=select(rows)
    rows=recrop(rows,args.output/'images');args.output.mkdir(parents=True,exist_ok=True)
    count,pages=sheets(rows,args.output)
    (args.output/'rows.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
    print(json.dumps(dict(rows=len(rows),patches=count,pages=pages,inputSHA256=sha(args.input),outputSHA256=sha(args.output/'rows.jsonl'),scriptSHA256=sha(__file__))))


if __name__=='__main__':main()
