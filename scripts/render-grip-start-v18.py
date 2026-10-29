#!/usr/bin/env python3
"""Plot exact replay measurements alongside their original source image."""
import json
from pathlib import Path
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-start-v18'
RID='022e9cdd583f112960aaac5755ef9dd1d5335090575ab510a28c80dd111bf15e'


def lines(path):return [json.loads(x) for x in path.read_text().splitlines()]


def main():
    diagnostics=[r for r in lines(OUT/'start-diagnostics.jsonl') if r['recordingId']==RID]
    frames=[r for r in lines(OUT/'native-input.jsonl') if r['recordingId']==RID]
    rows=lines(ROOT/'build/grip-controlled-v17/test/rows.jsonl')
    source=min([r for r in rows if r['recordingId']==RID],key=lambda r:abs(r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']-2.24))
    native=next(r for r in frames if r['imageSHA256']==source['imageSHA256'])
    sample=next(r for r in diagnostics if abs(r['seconds']-2.24)<1e-8)
    fig,(photo,chart)=plt.subplots(1,2,figsize=(13,5.3),gridspec_kw=dict(width_ratios=[1.1,1]))
    with Image.open(Path(source['framesDirectory'])/source['image']) as image:photo.imshow(image)
    points={p['joint']:p['position'] for p in native['pose']['people'][0]['landmarks']}
    for side,color in [('left','#36c7ff'),('right','#ffad32')]:
        chain=[points[side+j] for j in ['Shoulder','Elbow','Wrist']]
        photo.plot([p['x'] for p in chain],[p['y'] for p in chain],'-o',color=color,linewidth=3,markersize=6)
        angle=next(a['degrees'] for a in sample['arms'] if a['side']==side)
        photo.text(chain[1]['x']+10,chain[1]['y'],f'{side}: {angle:.0f}°',color=color,fontsize=11,
                   bbox=dict(facecolor='black',alpha=.75,edgecolor='none'))
    photo.set_title('Original frame, 2.24 s\nOverlay is the detector estimate',fontsize=12)
    photo.axis('off')
    for side,color in [('left','#1683b5'),('right','#bc6d00')]:
        times=[];angles=[]
        for row in diagnostics:
            times.append(row['seconds']);angles.append(next((a['degrees'] for a in row['arms'] if a['side']==side),float('nan')))
        chart.plot(times,angles,'.-',color=color,label=side.capitalize(),linewidth=1,markersize=2)
    chart.axhline(155,color='#b92d3b',linestyle='--',label='Existing start requirement: 155°')
    chart.set(xlim=(0,5.6),ylim=(0,180),xlabel='Original source time (seconds)',ylabel='Measured elbow angle (degrees)')
    chart.set_title('All 141 original frames in fixed-view segment\nNo frame meets the starting condition',fontsize=12)
    chart.grid(alpha=.2);chart.legend(loc='lower left',fontsize=9)
    fig.suptitle('Rear-view acquisition failure: body-pose evidence, before hand classification',fontsize=14)
    fig.text(.5,.02,'Known camera view and clear hand crops do not guarantee a measured starting hang. Count and hand-confidence thresholds are unchanged.',ha='center',fontsize=9)
    fig.tight_layout(rect=(0,.05,1,.94))
    target=OUT/'rear-start-diagnostic.png';assert not target.exists();fig.savefig(target,dpi=150);plt.close(fig)
    print(target)


if __name__=='__main__':main()
