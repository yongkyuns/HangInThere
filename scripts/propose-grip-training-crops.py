#!/usr/bin/env python3
"""Pose-seeded TRAIN-ONLY hand proposals; no proposal is a verified target.

Two fixed observations from each newly reviewed underhand training video with no
automatic hand crop. Each proposed image requires explicit visual acceptance.
Validation/test inputs cannot enter this tool. Original SDK failures are retained.
"""
import argparse
from collections import defaultdict
import copy
import hashlib
import json
import math
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageOps


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not (a.output/'rows.jsonl').exists();a.output.mkdir(parents=True,exist_ok=True)
    rows=[json.loads(l) for l in a.input.read_text().splitlines()];groups=defaultdict(list)
    for r in rows:
        t=r['pose']['timestamp'];t=t['value']/t['timescale'];ann=r['annotation']
        if ann['split']=='train' and ann['label']=='underhand' and ann['start']<=t<=ann['end']:groups[r['recordingId']].append(r)
    selected=[]
    for g in groups.values():
        if any(p['status']=='patchReady' for r in g for p in r['patches']):continue
        selected.extend([g[i] for i in sorted({0,len(g)-1})])
    proposals=[];display=[]
    for r in selected:
        path=Path(r['framesDirectory'])/r['image'];assert hashlib.sha256(path.read_bytes()).hexdigest()==r['imageSHA256']
        im=Image.open(path).convert('RGB');short=min(im.size);people=r['pose']['people']
        if not people:continue
        index=r['patchPlan'].get('personIndex')
        if index is None:
            def score(person):
                ps={p['joint']:p for p in person['landmarks']};return sum(ps.get(s+'Wrist',{}).get('confidence',0) for s in ['left','right'])
            index=max(range(len(people)),key=lambda i:score(people[i]))
        points={p['joint']:p for p in people[index]['landmarks']};added=False
        for side in ['left','right']:
            wrist=points.get(side+'Wrist');toward=points.get(side+'Elbow',points.get(side+'Shoulder'))
            if not wrist or not toward or wrist['confidence']<.1:continue
            w=[wrist['position'][k] for k in ['x','y']];e=[toward['position'][k] for k in ['x','y']]
            if not all(math.isfinite(x) for x in w+e):continue
            length=math.dist(w,e);extent=math.ceil(min(short*.32,max(24,length*.9)))
            cx=w[0]+(w[0]-e[0])*.18;cy=w[1]+(w[1]-e[1])*.18
            x=min(max(0,math.floor(cx-extent/2)),im.width-extent);y=min(max(0,math.floor(cy-extent/2)),im.height-extent)
            box=[x,y,x+extent,y+extent];original=next(p for p in r['patches'] if p['side']==side)
            ident=f'M{len(proposals)+1:04d}';target=a.output/(ident+'.png');im.crop(box).resize((128,128),Image.Resampling.BILINEAR).save(target)
            proposal=dict(side=side,status='patchReady',box=box,patch=str(target.absolute()),patchSHA256=hashlib.sha256(target.read_bytes()).hexdigest(),
                origin='manualReviewedTrainingCrop',reviewId=ident,manualAcceptanceRequired=True,originalAutomatic=copy.deepcopy(original),
                proposalPersonIndex=index,proposalMethod='pose-seeded 0.9 forearm square shifted 0.18 forearm away from elbow; TRAIN ONLY; visual acceptance required')
            original.clear();original.update(proposal);added=True
            proposals.append(dict(reviewId=ident,frameReviewId=r['frameReviewId'],caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],split='train',side=side,box=box,patchSHA256=proposal['patchSHA256']))
        if added:display.append(r)
    font=ImageFont.load_default(size=16);small=ImageFont.load_default(size=14)
    for page in range((len(display)+3)//4):
        batch=display[page*4:(page+1)*4];canvas=Image.new('RGB',(1100,len(batch)*235+36),'white');d=ImageDraw.Draw(canvas)
        d.text((8,8),'TRAIN ONLY: unverified proposals / accept or reject each hand / never used to change validation or test crops',font=font,fill='black')
        for j,r in enumerate(batch):
            y=36+j*235;im=Image.open(Path(r['framesDirectory'])/r['image']).convert('RGB');mark=ImageDraw.Draw(im)
            d.text((8,y),r['frameReviewId']+' '+r['caseId'],font=small,fill='black')
            for k,side in enumerate(['left','right']):
                patch=next(p for p in r['patches'] if p['side']==side);x=400+k*330
                if patch.get('origin')!='manualReviewedTrainingCrop':continue
                mark.rectangle(patch['box'],outline='magenta',width=3)
                canvas.paste(Image.open(patch['patch']).resize((160,160)),(x,y+30));d.text((x,y+8),patch['reviewId']+' '+side,font=font,fill='black')
                d.text((x,y+196),patch['originalAutomatic']['status'],font=small,fill='black')
            canvas.paste(ImageOps.contain(im,(370,200)),(8,y+23))
        canvas.save(a.output/f'review-{page+1:02d}.png')
    (a.output/'rows.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
    (a.output/'proposals.json').write_text(json.dumps(dict(inputSHA256=hashlib.sha256(a.input.read_bytes()).hexdigest(),proposals=proposals,trainingOnly=True),indent=2)+'\n')
    print(json.dumps(dict(candidateVideos=len({r['recordingId'] for r in display}),proposedPatches=len(proposals),pages=(len(display)+3)//4)))


if __name__=='__main__':main()
