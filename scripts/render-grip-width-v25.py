#!/usr/bin/env python3
"""Plot original source-time width credit on the second wide-grip rep."""
import json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-width-v25'
def lines(p):return [json.loads(s) for s in p.read_text().splitlines()]
canvas=Image.new('RGB',(1000,390),'#101820');d=ImageDraw.Draw(canvas)
title=ImageFont.load_default(size=27);font=ImageFont.load_default(size=21);small=ImageFont.load_default(size=18)
d.text((22,15),'Second wide-grip rep: saved width becomes available',font=title,fill='white')
d.text((22,54),'Source times 5.4 to 8.4 s. No width is credited during invalid frames.',font=small,fill='#ccd7df')
start,end=5.4,8.4
x=lambda t:30+(t-start)/(end-start)*940
for name,folder,y in [('Before',ROOT/'build/grip-width-v24',135),('After',OUT,250)]:
    frames=[r for r in lines(folder/'native-output.jsonl') if r['caseId']=='PdRboCRf1Mg' and start<=r['seconds']<=end]
    groups=json.loads((folder/'native-rep-records.json').read_text())
    rep=next(r for g in groups for r in g['reps'] if abs(r['startedAt']-start)<1e-9 and g['identity'].startswith('2f48f25b'))
    label=rep['grip']['width'].title();coverage=rep['grip']['widthCoverage']
    d.text((25,y-34),f'{name}: {label} saved · {coverage:.0%} credited coverage',font=font,fill='white')
    d.rectangle((30,y,970,y+28),fill='#53616b')
    for a,b in zip(frames,frames[1:]):
        if a['width']=='wide' and b['width']=='wide':d.rectangle((x(a['seconds']),y,x(b['seconds']),y+28),fill='#00d897')
    for r in frames:
        if r['widthReason']=='wristConfidencePause':d.line((x(r['seconds']),y-5,x(r['seconds']),y+33),fill='#ffb347',width=3)
    for tick in [5.4,6.0,6.8,7.4,8.4]:
        d.line((x(tick),y+31,x(tick),y+37),fill='white')
        d.text((x(tick)-16,y+40),str(tick),font=small,fill='#ccd7df')
d.text((22,337),'Green: measured/confirmed Wide. Grey: uncredited. Orange: wrist-confidence pause.',font=small,fill='white')
d.text((22,365),'Confidence cutoff stays 0.5; fresh width-band confirmation still takes 0.5 seconds.',font=small,fill='white')
canvas.save(OUT/'wide-rep-continuity.png')
