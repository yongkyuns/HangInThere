#!/usr/bin/env python3
"""Current-image localization probe; no synthetic joints, confidence, or counting."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
import hashlib,json,math
from pathlib import Path
import mediapipe as mp
import numpy as np
from PIL import Image
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-context-v28'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
 manifest=json.loads((ROOT/'models/grip/manifest.json').read_text())
 for m in manifest['models']:assert sha(ROOT/'models/grip'/m['file'])==m['sha256']
 rows=[json.loads(s) for s in (ROOT/'build/grip-width-v27/run/test/rows.jsonl').read_text().splitlines()]
 rows=[r for r in rows if r['caseId']=='c79BFTkrt9s']
 hand_options=mp.tasks.vision.HandLandmarkerOptions(base_options=mp.tasks.BaseOptions(model_asset_path=str(ROOT/'models/grip/hand_landmarker.task'),delegate=mp.tasks.BaseOptions.Delegate.CPU),running_mode=mp.tasks.vision.RunningMode.IMAGE,num_hands=2,min_hand_detection_confidence=.5,min_hand_presence_confidence=.5)
 body_options=mp.tasks.vision.PoseLandmarkerOptions(base_options=mp.tasks.BaseOptions(model_asset_path=str(ROOT/'models/grip/pose_landmarker_lite.task'),delegate=mp.tasks.BaseOptions.Delegate.CPU),running_mode=mp.tasks.vision.RunningMode.IMAGE,num_poses=2,min_pose_detection_confidence=.5,min_pose_presence_confidence=.5)
 results=[];reference=None
 with mp.tasks.vision.HandLandmarker.create_from_options(hand_options) as hands,mp.tasks.vision.PoseLandmarker.create_from_options(body_options) as body:
  for r in rows:
   p=Path(r['framesDirectory'])/r['image'];assert sha(p)==r['imageSHA256'];im=Image.open(p).convert('RGB');pixels=np.asarray(im);w,h=im.size;short=min(w,h)
   # Initial bilateral high-confidence observed wrist positions only define search ROIs.
   people=r['pose']['people'];d={x['joint']:x for x in people[0]['landmarks']} if len(people)==1 else {}
   if reference is None and all(d.get(s+'Wrist',{}).get('confidence',0)>=.5 and d.get(s+'Shoulder',{}).get('confidence',0)>=.5 and d[s+'Shoulder']['position']['y']-d[s+'Wrist']['position']['y']>short*.08 for s in ['left','right']):reference={s:d[s+'Wrist']['position'] for s in ['left','right']}
   br=body.detect(mp.Image(image_format=mp.ImageFormat.SRGB,data=pixels));bp=[]
   for person in br.pose_landmarks:
    bp.append([dict(joint=name,x=person[i].x*w,y=person[i].y*h,visibility=person[i].visibility,presence=person[i].presence) for name,i in [('leftShoulder',11),('rightShoulder',12),('leftElbow',13),('rightElbow',14),('leftWrist',15),('rightWrist',16)]])
   detected=[]
   regions=[('full',[0,0,w,h])]
   if reference:
    for side,p0 in reference.items():
     extent=int(short*.28);x=max(0,min(w-extent,int(p0['x']-extent/2)));y=max(0,min(h-extent,int(p0['y']-extent/2)));regions.append((side+'InitialSearch',[x,y,x+extent,y+extent]))
   for name,box in regions:
    x,y,x1,y1=box;crop=np.ascontiguousarray(pixels[y:y1,x:x1]);hr=hands.detect(mp.Image(image_format=mp.ImageFormat.SRGB,data=crop))
    detected.append(dict(region=name,box=box,hands=[dict(points=[dict(x=x+p.x*(x1-x),y=y+p.y*(y1-y),visibility=p.visibility,presence=p.presence) for p in points],sdkHandedness=[dict(label=c.category_name,score=c.score) for c in hr.handedness[i]]) for i,points in enumerate(hr.hand_landmarks)]))
   results.append(dict(caseId=r['caseId'],image=r['image'],imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],imageSize=r['pose']['imageSize'],initialSearchReference=reference,body=bp,hands=detected))
 with (OUT/'current-image-probe.jsonl').open('x') as f:f.write(''.join(json.dumps(r)+'\n' for r in results))
 receipt=dict(images=len(results),scope='Development-only current-image detector probe on exposed narrow chin-up. Initial wrist positions define search only, never positive current evidence. SDK handedness scores are not landmark confidence. No fusion, counting, labels, crops, thresholds or phone changes.',sha256={str(p):sha(p) for p in [Path(__file__),ROOT/'models/grip/hand_landmarker.task',ROOT/'models/grip/pose_landmarker_lite.task',OUT/'current-image-probe.jsonl']})
 with (OUT/'probe-receipt.json').open('x') as f:json.dump(receipt,f,indent=2)
 print(json.dumps(dict(images=len(results),withAnyHand=sum(any(s['hands'] for s in r['hands']) for r in results),withBody=sum(bool(r['body']) for r in results))))
if __name__=='__main__':main()
