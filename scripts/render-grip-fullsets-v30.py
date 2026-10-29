#!/usr/bin/env python3
"""Render source-clock timelines and unchanged automatic hand crops."""
import json
import os
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-fullsets-v30';RUN=OUT/'run/test'
os.environ['MPLCONFIGDIR']=str(OUT/'render-cache')
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image,ImageDraw,ImageFont
def read(p):return json.loads(p.read_text())
def lines(p):return [json.loads(x) for x in p.read_text().splitlines()]
refs=read(OUT/'run/source-references.json');frames=lines(RUN/'native-output.jsonl');states=read(RUN/'grip-results.json');rows=lines(RUN/'rows.jsonl');rgb=lines(RUN/'observations.jsonl')
fig,axes=plt.subplots(2,2,figsize=(13,7),layout='constrained')
for index,i in enumerate(['QA5ko5Ibu0g','B4I9eBw88yQ']):
    c=next(c for c in refs['cases'] if c['id']==i);s=c['sets'][0];fs=[f for f in frames if f['caseId']==i];ss=next(c['states'] for c in states if c['caseId']==i);ts=[f['seconds'] for f in fs];ax,aw=axes[index]
    ax.step(ts,[{'unknown':0,'overhand':1,'underhand':2}[s['label']] for s in ss],where='post',color='#176b8f');ax.hlines(2 if s['referenceGrip']=='underhand' else 1,*s['activeWindow'],color='#a85732',linestyle='--',label='Source reference');ax.set_yticks([0,1,2],['Unknown','Overhand','Underhand']);ax.set_title(i+' | '+s['referenceGrip']+' | '+str(s['sourceCompleteCycles'])+' full cycles');ax.legend(fontsize=8,loc='upper left');ax.set_ylim(-.2,2.8)
    aw.plot(ts,[f['projectedSpanRatio'] if f['projectedSpanRatio'] is not None else float('nan') for f in fs],color='#176b8f',lw=1,label='Native ratio');low,high=s['reference']['ratioInterval'];aw.fill_between(s['activeWindow'],low,high,color='#518f62',alpha=.2,label='Frozen source interval');aw.axhline(.85,color='gray',ls=':');aw.axhline(1.3,color='#bc5040',ls='--',label='Nominal Wide boundary');aw.set_ylim(.4,2.6);aw.set_title('Width / shoulders'+(' | false Wide concern' if index==0 else ' | reference crosses bands'));aw.legend(fontsize=8,loc='upper right')
    for a in [ax,aw]:a.set_xlim(max(0,s['activeWindow'][0]-3),min(c['duration'],s['activeWindow'][1]+4));a.set_xlabel('Original source seconds');a.grid(alpha=.15)
fig.suptitle('Fresh complete-set replay | counting: 8/8 full cycles\nGrip unknowns and width errors remain; no model/policy change',fontsize=13);fig.savefig(OUT/'complete-set-timelines.png',dpi=150);plt.close(fig)
font=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',23);small=ImageFont.truetype('/System/Library/Fonts/Supplemental/Arial.ttf',18)
sheet=Image.new('RGB',(1280,2*490),'#f5f7f9');d=ImageDraw.Draw(sheet)
for index,(i,t,title,note) in enumerate([
 ('QA5ko5Ibu0g',19.77,'Chin-up: automatic crops sit below the grip','Finger area is clipped; patches mostly show wrists/forearms. Right predicts underhand, but that does not establish correct localization.'),
 ('B4I9eBw88yQ',20.55,'Pull-up: first rep Unknown despite correct raw hand evidence','First label arrives at 22.155 s after the unchanged two-image / one-second confirmation.')]):
    r=min((r for r in rows if r['caseId']==i),key=lambda r:abs(r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']-t));ob=next(x for x in rgb if x['caseId']==i and x['imageSHA256']==r['imageSHA256']);y=index*490;d.text((15,y+10),title,font=font,fill='#173448');d.text((15,y+43),f'{i} | {ob["seconds"]:.3f}s | Original automatic crops; no manual rescue',font=small,fill='#334d5c');im=Image.open(Path(r['framesDirectory'])/r['image']).convert('RGB');draw=ImageDraw.Draw(im)
    for patch in r['patches']:
        if patch['status']=='patchReady':draw.rectangle(patch['box'],outline='#00d8e8',width=3)
    im.thumbnail((610,345));sheet.paste(im,(15,y+78))
    for j,h in enumerate(ob['hands']):
        x=650+j*300;sheet.paste(Image.open(h['patch']).resize((190,190)),(x,y+85));d.text((x,y+285),h['side'],font=small,fill='#173448');d.text((x,y+314),' / '.join(f'{v:.3f}' for v in h['surface']),font=small,fill='#334d5c')
    d.text((650,y+346),'Raw surface: palm / dorsal / other',font=small,fill='#334d5c')
    words=note.split();line='';yy=y+379
    for word in words:
        if d.textlength(line+word,font=small)>590:d.text((650,yy),line,font=small,fill='#334d5c');line='';yy+=24
        line+=word+' '
    d.text((650,yy),line,font=small,fill='#334d5c')
sheet.save(OUT/'first-rep-examples.png')
print('Rendered two continuous complete-set timelines and two first-rep examples.')
