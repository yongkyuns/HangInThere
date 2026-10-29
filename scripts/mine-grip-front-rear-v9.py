#!/usr/bin/env python3
"""Automatic development-dataset hand proposals; human review labels ownership.

This deliberately bypasses body-preflight for dataset mining only. It is not
an app localization policy or an end-to-end validation test. No manual boxes.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
import hashlib
import json
import math
from pathlib import Path
import sys
import argparse
import numpy as np
import mediapipe as mp
from PIL import Image,ImageDraw,ImageOps,ImageFont


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('root',type=Path)
    parser.add_argument('--wrist-searches',action='store_true')
    parser.add_argument('--all-prepared-frames',action='store_true')
    parser.add_argument('--small-wrist-searches',action='store_true');args=parser.parse_args()
    root=args.root;out=root/('mined-pyramid' if args.small_wrist_searches else 'mined-wrist' if args.wrist_searches else 'mined');out.mkdir(exist_ok=False)
    intake=json.loads((root/'mining-intake.json').read_text())
    by_id={x['id']:x for x in intake['cases']}
    native=json.loads((root/'native/prepared-threeway.json').read_text())
    model=Path('models/grip/hand_landmarker.task')
    assert sha(model)=='fbc2a30080c3c557093b5ddfc334698132eb341044ccee322ccf8bcf3607cde1'
    options=mp.tasks.vision.HandLandmarkerOptions(base_options=mp.tasks.BaseOptions(model_asset_path=str(model)),
        num_hands=4,min_hand_detection_confidence=.5,min_hand_presence_confidence=.5)
    rows=[];patches=[];misses=[]
    with mp.tasks.vision.HandLandmarker.create_from_options(options) as sdk:
        for case in native['cases']:
            if case['status']!='ready':continue
            source=by_id[case['id']]
            available=[json.loads(x) for x in (Path(case['framesDirectory'])/'frames.jsonl').read_text().splitlines()]
            seen=set()
            sampling=([r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale'] for r in available]
                      if args.all_prepared_frames else list(range(1,9)) if args.wrist_searches else [2,4,6,8])
            for t in sampling:
                time=lambda r:r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']
                if not available or t>time(available[-1]):misses.append(dict(caseId=case['id'],requestedSeconds=t));continue
                row=min(available,key=lambda r:abs(time(r)-t))
                if row['imageSHA256'] in seen:continue
                seen.add(row['imageSHA256']);path=Path(case['framesDirectory'])/row['image']
                assert sha(path)==row['imageSHA256']
                rgb=Image.open(path).convert('RGB');w,h=rgb.size;pixels=np.asarray(rgb)
                # Fixed three searches, independent of pose/grip/view labels.
                regions=[(0,0,w,h),(0,0,math.ceil(w*.6),math.ceil(h*.6)),
                         (math.floor(w*.4),0,w,math.ceil(h*.6))]
                if args.wrist_searches and len(row['pose']['people'])==1:
                    points=row['pose']['people'][0]['landmarks']
                    for joint in ['leftWrist','rightWrist']:
                        p=next((p for p in points if p['joint']==joint and p['confidence']>=.5),None)
                        if p:
                            for fraction in ([.3,.16] if args.small_wrist_searches else [.3]):
                                edge=math.ceil(min(w,h)*fraction);cx=p['position']['x'];cy=p['position']['y']
                                a=max(0,min(w-edge,math.floor(cx-edge/2)));b=max(0,min(h-edge,math.floor(cy-edge/2)))
                                regions.append((a,b,a+edge,b+edge))
                proposals=[]
                for x0,y0,x1,y1 in regions:
                    im=np.ascontiguousarray(pixels[y0:y1,x0:x1])
                    result=sdk.detect(mp.Image(image_format=mp.ImageFormat.SRGB,data=im))
                    for points in result.hand_landmarks:
                        pts=np.array([[x0+p.x*(x1-x0),y0+p.y*(y1-y0)] for p in points])
                        lo=pts.min(0);hi=pts.max(0);extent=float(max(hi-lo))
                        if not np.isfinite(pts).all() or extent<16:continue
                        if min(lo)<1 or hi[0]>w-1 or hi[1]>h-1:continue
                        if any(np.linalg.norm(pts[0]-p['wrist'])<max(extent,p['extent'])*.35 for p in proposals):continue
                        edge=min(math.ceil(extent*1.6),w,h)
                        center=(lo+hi)/2
                        x=min(max(0,math.floor(center[0]-edge/2)),w-edge)
                        y=min(max(0,math.floor(center[1]-edge/2)),h-edge)
                        proposals.append(dict(box=[x,y,x+edge,y+edge],wrist=pts[0],extent=extent))
                record=dict(row,framesDirectory=case['framesDirectory'],caseId=case['id'],recordingId=case['sha256'],
                    sourceGroup=case['sourceGroup'],requestedSeconds=t,context=dict(status='unavailable'),
                    annotation=dict(split='validation',recordingId=case['sha256'],sourceGroup=case['sourceGroup'],
                                    label=source['provisionalGrip'],viewpoint=row.get('reviewedSourceView',source['provisionalView'])),patches=[])
                for proposal in proposals:
                    ident=f'J{len(patches)+1:03d}';dest=out/(ident+'.png');box=proposal['box']
                    rgb.crop(box).resize((128,128),Image.Resampling.BILINEAR).save(dest)
                    patch=dict(status='patchReady',side='unassigned',origin='automaticImageHandMiningV9',
                               patch=str(dest.resolve()),patchSHA256=sha(dest),box=box,reviewId=ident)
                    record['patches'].append(patch)
                    patches.append(dict(id=ident,patch=patch,sourceImage=str(path.resolve()),imageSHA256=row['imageSHA256'],
                        caseId=case['id'],recordingId=case['sha256'],timestamp=row['pose']['timestamp'],
                        provisionalGrip=source['provisionalGrip'],provisionalView=record['annotation']['viewpoint']))
                rows.append(record)
    (out/'rows-unreviewed.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
    (out/'index.json').write_text(json.dumps(dict(qualification=__doc__,modelSHA256=sha(model),samplingSeconds='allPreparedFrames' if args.all_prepared_frames else list(range(1,9)) if args.wrist_searches else [2,4,6,8],
        detectionThreshold=.5,minimumExtentNativePixels=16,searchVariant='full+upperTiles+observedWrists',wristRegionFractions=[.3,.16] if args.small_wrist_searches else [.3] if args.wrist_searches else [],rows=len(rows),patches=patches,missingRequestedFrames=misses),indent=2)+'\n')
    font=ImageFont.load_default(size=15)
    for page in range((len(patches)+5)//6):
        batch=patches[page*6:(page+1)*6];canvas=Image.new('RGB',(1500,len(batch)*245),'white');d=ImageDraw.Draw(canvas)
        for i,p in enumerate(batch):
            y=i*245;d.text((5,y+2),p['id']+' '+p['caseId'],font=font,fill='black')
            im=Image.open(p['sourceImage']).convert('RGB');draw=ImageDraw.Draw(im);draw.rectangle(p['patch']['box'],outline='red',width=max(1,im.width//300));im.thumbnail((760,210));canvas.paste(im,(5,y+30))
            canvas.paste(Image.open(p['patch']['patch']).resize((208,208)),(800,y+30))
            d.text((1030,y+35),p['provisionalGrip']+' / '+p['provisionalView'],font=font,fill='black')
            d.text((1030,y+60),'Provisional; verify source and patch',font=font,fill='black')
        canvas.save(out/f'review-{page+1:02d}.png')
    print(json.dumps(dict(frames=len(rows),automaticHandPatches=len(patches),pages=(len(patches)+5)//6)))


if __name__=='__main__':main()
