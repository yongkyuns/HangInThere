#!/usr/bin/env python3
"""Original-frame comparison of width outputs before/after the pixel-gate fix."""
import json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont

ROOT=Path(__file__).resolve().parents[1]
OLD=ROOT/'build/grip-width-v23'
OUT=ROOT/'build/grip-width-v24'
def lines(p):return [json.loads(s) for s in p.read_text().splitlines()]
old=lines(OLD/'test/native-output.jsonl');new=lines(OUT/'native-output.jsonl')
rows=lines(OLD/'native-rgb/frames/qVztO-F-IwI/frames.jsonl')
font=ImageFont.load_default(size=24);small=ImageFont.load_default(size=20);title=ImageFont.load_default(size=29)
canvas=Image.new('RGB',(1120,820),'#101820');d=ImageDraw.Draw(canvas)
d.text((20,16),'Same rep, before and after the width fix',font=title,fill='white')
d.text((20,57),'Starting-hang shoulder reference stays frozen; wrists remain on the support.',font=small,fill='#d0d8e0')
for i,t in enumerate([11.2,11.48,11.76]):
    a=next(r for r in old if r['caseId']=='qVztO-F-IwI' and r['seconds']==t)
    b=next(r for r in new if r['caseId']=='qVztO-F-IwI' and r['seconds']==t)
    r=next(r for r in rows if r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']==t)
    im=Image.open(OLD/'native-rgb/frames/qVztO-F-IwI'/r['image']).convert('RGB')
    x=20+370*i;canvas.paste(im.crop((400,90,600,430)).resize((340,578),Image.Resampling.LANCZOS),(x,142))
    d.text((x,98),f'{t:.2f}s',font=font,fill='white')
    label=lambda s:'Medium' if s=='shoulderWidth' else s.title()
    d.text((x,736),'Before: '+label(a['width']),font=small,fill='#ffb347')
    d.text((x,767),'After: '+label(b['width']),font=font,fill='#00ff99')
canvas.save(OUT/'medium-before-after.png')
