#!/usr/bin/env python3
"""Render every scored held-out underhand patch with source-bound audit context.

No inference, relabeling, model fitting, exclusion or benchmark changes occur.
Saved RGB patches are checked against original frame crops, not just file hashes.
"""
import argparse
import hashlib
import json
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw,ImageFont,ImageOps

ROOT=Path(__file__).absolute().parents[1]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('output',type=Path)
 p.add_argument('--review',type=Path)
 a=p.parse_args();a.output.mkdir(parents=True,exist_ok=True)
 review=json.loads(a.review.read_text()) if a.review else None
 src=ROOT/'build/grip-context';items=[];font=ImageFont.load_default(size=17);small=ImageFont.load_default(size=14)
 for cohort,manifest,score in [('older','verified-development-test-corrected.jsonl','oriented-development-corrected-score'),('fresh','verified-final-test.jsonl','oriented-fresh-corrected-score')]:
  rows=[json.loads(l) for l in (src/manifest).read_text().splitlines()];preds=[json.loads(l) for l in (src/score/'predictions.jsonl').read_text().splitlines()]
  for pred in preds:
   if pred['expected']!='underhand' or not pred.get('patch'):continue
   row=next(r for r in rows if r['recordingId']==pred['recordingId'] and r['pose']['timestamp']==pred['timestamp'])
   patch=next(p for p in row['patches'] if p['side']==pred['side']);path=Path(row['framesDirectory'])/row['image']
   assert hashlib.sha256(path.read_bytes()).hexdigest()==row['imageSHA256']
   assert hashlib.sha256(Path(patch['patch']).read_bytes()).hexdigest()==patch['patchSHA256']
   frame=Image.open(path).convert('RGB');actual=Image.open(patch['patch']).convert('RGB');rebuilt=frame.crop(patch['box']).resize((128,128),Image.Resampling.BILINEAR)
   assert np.array_equal(np.asarray(actual),np.asarray(rebuilt)),'Saved patch is not its claimed source crop'
   items.append(dict(index=len(items),cohort=cohort,row=row,patch=patch,prediction=pred,originalCropSide=patch['box'][2]-patch['box'][0],sourceCropPixelExact=True))
 assert len(items)==12
 sheet=Image.new('RGB',(4*304,3*370),'#f4f6f8');d=ImageDraw.Draw(sheet)
 for i,it in enumerate(items):
  x=(i%4)*304;y=(i//4)*370;r=it['row'];p=it['patch'];t=r['pose']['timestamp'];time=t['value']/t['timescale']
  # Nearest-neighbor enlargement adds no synthetic detail.
  sheet.paste(Image.open(p['patch']).resize((256,256),Image.Resampling.NEAREST),(x+12,y+8))
  d.text((x+12,y+270),f"#{i+1}  {it['cohort']}  {time:.3f}s  {p['side']}",font=font,fill='black')
  d.text((x+12,y+295),f"Original crop: {it['originalCropSide']} x {it['originalCropSide']} px",font=small,fill='black')
  d.text((x+12,y+318),f"Predicted: {it['prediction']['argmax']}",font=small,fill='#a02622')
  if review:
   entry=review['patches'][i]
   assert entry['patchSHA256']==p['patchSHA256'] and entry['imageSHA256']==r['imageSHA256'] and entry['side']==p['side']
   if entry['labelStatus'].startswith('unverified'):
    d.text((x+12,y+335),'Reference UNVERIFIED: occluded',font=small,fill='#986800')
 sheet.save(a.output/'all-12-patches.png')
 for page in range((len(items)+3)//4):
  canvas=Image.new('RGB',(1260,4*335),'white');d=ImageDraw.Draw(canvas)
  for j,it in enumerate(items[page*4:(page+1)*4]):
   r=it['row'];p=it['patch'];y=j*335;frame=Image.open(Path(r['framesDirectory'])/r['image']).convert('RGB');marked=frame.copy();ImageDraw.Draw(marked).rectangle(p['box'],outline='#ff1f1f',width=1)
   canvas.paste(ImageOps.contain(marked,(640,295)),(8,y+32))
   # Hand plus nearby bar/other hand. Keep enough context to establish grip.
   x0,y0,x1,y1=p['box'];cx=(x0+x1)/2;cy=(y0+y1)/2;side=160
   region=[round(cx-side/2),round(cy-side/2),round(cx+side/2),round(cy+side/2)]
   zoom=marked.crop(region).resize((320,320),Image.Resampling.NEAREST);canvas.paste(zoom,(660,y+8))
   exact=Image.open(p['patch']).resize((240,240),Image.Resampling.NEAREST);canvas.paste(exact,(1000,y+8))
   d.text((8,y+7),f"#{it['index']+1}  {r['caseId']}  {r['image']}  {p['side']}",font=font,fill='black')
   d.text((1000,y+256),f"Exact model input",font=small,fill='black')
   d.text((1000,y+280),str(p['box']),font=small,fill='black')
  canvas.save(a.output/f'source-context-{page+1}.png')
 (a.output/'cases.json').write_text(json.dumps(items,indent=2)+'\n');print(json.dumps(dict(patches=len(items),cropSizes=[i['originalCropSide'] for i in items],allPixelExact=True)))

if __name__=='__main__':main()
