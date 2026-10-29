#!/usr/bin/env python3
"""Diagnostic figures only; no synthetic/fused observations enter inference."""
import json,os
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-context-v28';BASE=ROOT/'build/grip-width-v27'
def read(p):return json.loads(p.read_text())
def lines(p):return [json.loads(s) for s in p.read_text().splitlines()]
def observations():
 rows=lines(BASE/'run/test/rows.jsonl');probes=lines(OUT/'current-image-probe.jsonl');font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',22);small=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',18)
 sheet=Image.new('RGB',(1280,3*430+85),'#f5f7f9');draw=ImageDraw.Draw(sheet);draw.text((15,12),'Narrow chin-up: current pose observations fail near the top',fill='#163246',font=font);draw.text((15,42),'Independent detector probe only. No point or confidence was substituted into counting.',fill='#334d5c',font=small)
 records=[]
 for index,t in enumerate([4,10.2,33.9]):
  r=min((r for r in rows if r['caseId']=='c79BFTkrt9s'),key=lambda r:abs(r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']-t));p=next(p for p in probes if p['imageSHA256']==r['imageSHA256']);time=p['timestamp']['value']/p['timestamp']['timescale'];image=Image.open(Path(r['framesDirectory'])/r['image']).convert('RGB');width,height=image.size;y=index*430+85
  apple=r['pose']['people'][0]['landmarks'] if r['pose']['people'] else []
  for column,title,persons in [(0,'Apple body pose',[{a['joint']:(a['position']['x'],a['position']['y'],a['confidence']) for a in apple}]),(1,'Auxiliary Pose Lite',[{a['joint']:(a['x'],a['y'],min(a['visibility'],a['presence'])) for a in person} for person in p['body']])]:
   x=column*640;draw.text((x+15,y+3),f'{title} | {time:.3f}s',fill='#163246',font=font);im=image.copy();im.thumbnail((610,345));sx=im.width/width;sy=im.height/height;d=ImageDraw.Draw(im)
   for person in persons:
    for side,color in [('left','#16dddd'),('right','#f9ce42')]:
     names=[side+s for s in ['Shoulder','Elbow','Wrist']]
     for a,b in zip(names,names[1:]):
      if a in person and b in person:d.line([(person[a][0]*sx,person[a][1]*sy),(person[b][0]*sx,person[b][1]*sy)],fill=color,width=3)
     for name in names:
      if name not in person:continue
      px,py,confidence=person[name];cx,cy=px*sx,py*sy;c=color if confidence>=.3 else '#ef4565';d.ellipse((cx-4,cy-4,cx+4,cy+4),fill=c)
    labels=[f'{s} wrist {person[s+"Wrist"][2]:.2f}' for s in ['left','right'] if s+'Wrist' in person]
    draw.text((x+15,y+386),' / '.join(labels),fill='#334d5c',font=small)
   sheet.paste(im,(x+15,y+37))
  records.append(dict(seconds=time,imageSHA256=r['imageSHA256'],appleWrists=[a for a in apple if a['joint'].endswith('Wrist')],auxiliaryBody=p['body']))
 sheet.save(OUT/'pose-failure-probe.png')
 with (OUT/'pose-failure-examples.json').open('x') as f:json.dump(records,f,indent=2)
def lifecycle():
 os.environ['MPLCONFIGDIR']=str(OUT/'render-cache')
 import matplotlib
 matplotlib.use('Agg')
 import matplotlib.pyplot as plt
 before=next(c['states'] for c in read(BASE/'run/test/grip-results.json') if c['caseId']=='YnykpezuXWg');after=next(c['states'] for c in read(OUT/'final/grip-results.json') if c['caseId']=='YnykpezuXWg')
 fig,ax=plt.subplots(figsize=(12,3.7),layout='constrained');t=[s['seconds'] for s in before]
 ax.step(t,[s['label']!='unknown' for s in before],where='post',label='Before: lingering grip label',color='#c34735',lw=2);ax.step(t,[s['label']!='unknown' for s in after],where='post',label='After: current support and fresh departure required',color='#177482',lw=2)
 ax.axvspan(55,65.1,color='#989fa6',alpha=.15);ax.set_xlim(45,78);ax.set_ylim(-.15,1.2);ax.set_yticks([0,1],['Unknown','Grip visible']);ax.set_xlabel('Original source seconds');ax.set_title('One-hand explanatory rest: 303 lingering label frames → 0\nCounting and native saved width/rep journals remain identical');ax.legend(loc='upper right',fontsize=9);ax.grid(axis='y',alpha=.2);fig.savefig(OUT/'metadata-cleanup.png',dpi=150);plt.close(fig)
if __name__=='__main__':
 import argparse
 p=argparse.ArgumentParser();p.add_argument('action',choices=['observations','lifecycle']);globals()[p.parse_args().action]()
