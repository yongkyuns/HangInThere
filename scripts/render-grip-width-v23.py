#!/usr/bin/env python3
"""Render original-pixel width-gate evidence without changing test references."""
import json
import math
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'build/grip-width-v23'
rows = [json.loads(s) for s in (OUT/'test/native-input.jsonl').read_text().splitlines()]
row = min((r for r in rows if r['caseId']=='PdRboCRf1Mg'),
          key=lambda r: abs(r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']-8))
lm = {p['joint']:p for p in row['pose']['people'][0]['landmarks']}
points = [lm[k]['position'] for k in ['leftShoulder','rightShoulder']]
span = math.hypot(points[0]['x']-points[1]['x'],points[0]['y']-points[1]['y'])
image = Image.open(OUT/'PdRboCRf1Mg-8.0.png').convert('RGB')
canvas = Image.new('RGB',(1280,900),'#101820'); canvas.paste(image,(0,100))
draw = ImageDraw.Draw(canvas)
font = ImageFont.load_default(size=26); small = ImageFont.load_default(size=21)
draw.text((24,20),'Tracked hands, but width blocked by the shoulder-size gate',font=font,fill='white')
draw.text((24,57),f'8.0 s | tracked shoulders {span:.1f} px | required >57.6 px | frozen detector',font=small,fill='#ffd166')
for k,color in [('leftShoulder','#00ff99'),('rightShoulder','#00ff99'),
                ('leftWrist','#00cfff'),('rightWrist','#00cfff')]:
    p=lm[k]['position']; x,y=p['x'],p['y']+100
    draw.ellipse((x-5,y-5,x+5,y+5),fill=color,outline='black',width=1)
draw.line([(p['x'],p['y']+100) for p in points],fill='#00ff99',width=3)
draw.text((24,836),'Green: tracked shoulder joint centers. Blue: tracked wrists.',font=small,fill='white')
draw.text((24,866),'Grip type is correct; width remains unknown. Original source and test labels are unchanged.',font=small,fill='white')
canvas.save(OUT/'width-gate-diagnostic.png')
