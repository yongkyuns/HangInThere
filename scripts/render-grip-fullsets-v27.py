#!/usr/bin/env python3
"""Render immutable full-set evaluation evidence, never model input."""
import os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-width-v27';RUN=OUT/'run'
os.environ['MPLCONFIGDIR']=str(OUT/'render-cache')
import json
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image,ImageDraw,ImageFont

def read(p):return json.loads(p.read_text())
def lines(p):return [json.loads(s) for s in p.read_text().splitlines()]
def main():
 report=read(RUN/'test/report.json');refs=read(RUN/'source-references.json');states=read(RUN/'test/grip-results.json');frames=lines(RUN/'test/native-output.jsonl');rows=lines(RUN/'test/rows.jsonl');rgb=lines(RUN/'test/observations.jsonl')
 grip={'unknown':0,'overhand':1,'underhand':2,'neutral':3};width={'unknown':0,'narrow':1,'shoulderWidth':2,'wide':3}
 fig,axes=plt.subplots(4,1,figsize=(15,10),layout='constrained')
 for index,id in enumerate(['YnykpezuXWg','hD0BOEH7bME']):
  c=next(c for c in refs['cases'] if c['id']==id);cr=next(c for c in report['cases'] if c['id']==id);ss=next(c for c in states if c['caseId']==id)['states'];fs=[f for f in frames if f['caseId']==id]
  for kind,ax in [('grip',axes[index*2]),('width',axes[index*2+1])]:
   values=[grip[s['label']] for s in ss] if kind=='grip' else [width[f['width']] for f in fs]
   ax.step([f['seconds'] for f in fs],values,where='post',color='#176a96',lw=1,label='Detector output')
   for s in c['sets']:
    expected=s['referenceGrip'] if kind=='grip' else s['referenceWidth'];mapping=grip if kind=='grip' else width
    if expected in mapping:ax.hlines(mapping[expected],*s['activeWindow'],color='#dd5c24',lw=4,alpha=.55,label='Source reference' if s==c['sets'][0] else None)
    else:ax.axvspan(*s['activeWindow'],color='#aaaaaa',alpha=.12)
    ax.axvline(s['activeWindow'][0],color='#888888',lw=.5,ls=':')
   for w in c['negativeWindows']:ax.axvspan(*w,color='#8b939a',alpha=.12)
   ax.set_yticks([0,1,2,3],['Unknown','Overhand','Underhand','Neutral'] if kind=='grip' else ['Unknown','Narrow','Medium','Wide']);ax.set_ylim(-.25,3.4);ax.grid(axis='y',alpha=.2);ax.set_xlim((80,278) if id=='YnykpezuXWg' else (0,51.3));ax.set_xlabel('Original video seconds');ax.set_title(f'{id}  |  {kind.title()}  |  same uninterrupted replay across sets',loc='left',fontsize=11)
   ax.legend(loc='upper right',fontsize=8,ncol=2)
 fig.suptitle('Fresh full-set validation — frozen detector, no set-label resets\nGray shading: setup/rest or uncertain width reference. Manually confirmed view; offline RGB, not phone acquisition.',fontsize=13)
 fig.savefig(OUT/'continuous-timelines.png',dpi=150);plt.close(fig)
 font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',22);small=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',18)
 examples=[('hD0BOEH7bME',6.133333333333334,'Overhand reference; fresh underhand prediction','Visible-hand patch accepts dorsal (0.811). Other hand is occluded/unclassified.'),('hD0BOEH7bME',26.4,'Underhand reference; fresh overhand prediction','Hand crop accepts palm (0.822). Release reset occurred before this set.'),('c79BFTkrt9s',7.2072,'Underhand patches correct; exercise context rejects','Both hands predict palm (0.987 / 0.912), but counter has not begun exercise.'),('YnykpezuXWg',60.,'One-hand rest retains an exercise/grip label','No counted rep; one supported arm lets prior exercise context remain active.')]
 sheet=Image.new('RGB',(1280,4*430),'#f5f7f9');d=ImageDraw.Draw(sheet)
 for index,(id,t,title,note) in enumerate(examples):
  r=min((r for r in rows if r['caseId']==id),key=lambda r:abs(r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']-t));time=r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale'];ob=next(x for x in rgb if x['caseId']==id and x['seconds']==time);f=next(x for x in frames if x['caseId']==id and x['seconds']==time);st=min((x for c in states if c['caseId']==id for x in c['states']),key=lambda x:abs(x['seconds']-time))
  y=index*430;d.text((16,y+10),title,fill='#102b3a',font=font);d.text((16,y+42),f'{id}  {time:.3f}s | phase={f["phase"]} | eligible={f["exercise"]["eligible"]} | grip={st["label"]}',fill='#334d5c',font=small)
  im=Image.open(Path(r['framesDirectory'])/r['image']);im.thumbnail((605,330));sheet.paste(im,(16,y+78))
  for j,h in enumerate(ob['hands']):
   patch=Image.open(h['patch']).resize((190,190));x=650+j*290;sheet.paste(patch,(x,y+88));d.text((x,y+285),h['side'],fill='#102b3a',font=small);d.text((x,y+310),'surface '+', '.join(f'{p:.3f}' for p in h['surface']),fill='#334d5c',font=small)
  d.text((650,y+344),'Scores: palm, dorsal, other (raw model outputs)',fill='#334d5c',font=small)
  # Wrap note to keep the evidence panel readable at original pixels.
  words=note.split();line='';yy=y+370
  for word in words:
   if d.textlength(line+word,font=small)>590:d.text((650,yy),line,fill='#334d5c',font=small);line='';yy+=23
   line+=word+' '
  d.text((650,yy),line,fill='#334d5c',font=small)
 sheet.save(OUT/'failure-examples.png')
 print('Rendered continuous timelines and four attributed source/patch examples')
if __name__=='__main__':main()
