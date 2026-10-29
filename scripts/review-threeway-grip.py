#!/usr/bin/env python3
"""Fixed benchmark assembly and blinded direct appearance/contact review sheets.

No surface label is derived from exercise/grip or athlete orientation. Pass two
shuffles actual patches and hides the source and all earlier labels.
"""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import random

from PIL import Image, ImageDraw, ImageFont, ImageOps


def rows(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines()]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def prepare(a):
    old = rows(a.previous)
    eligible = {r['recordingId'] for r in old for p in r['patches']
                if p.get('manualReview', {}).get('grip') in ['overhand', 'underhand']}
    groups = defaultdict(list)
    for r in old:
        if r['recordingId'] in eligible:
            groups[r['recordingId']].append(r)
    selected, sampling = [], []
    def time(r):
        t = r['pose']['timestamp']
        return t['value'] / t['timescale']
    for source, group in sorted(groups.items()):
        seen = set()
        for t in [2, 4]:
            row = min(group, key=lambda r: abs(time(r) - t))
            if abs(time(row) - t) > .6 or row['imageSHA256'] in seen:
                sampling.append(dict(recordingId=source, requested=t, status='noUniqueNearArchivedFrame'))
                continue
            seen.add(row['imageSHA256'])
            row['benchmarkOrigin'] = 'previousFitting'
            selected.append(row)
            sampling.append(dict(recordingId=source, requested=t, imageSHA256=row['imageSHA256']))
    added = rows(a.additional)
    assert not {r['recordingId'] for r in selected} & {r['recordingId'] for r in added}
    for r in added:
        r['benchmarkOrigin'] = 'additionalFitting'
    result = selected + added
    for index, r in enumerate(result, 1):
        r['benchmarkFrameId'] = f'B{index:04d}'
        for p in r['patches']:
            if p['status'] != 'patchReady':
                continue
            if 'reviewId' in p:
                p['priorReviewId'] = p['reviewId']
            p['reviewId'] = f'H{sum(q["status"] == "patchReady" for x in result[:index-1] for q in x["patches"]) + sum(q["status"] == "patchReady" for q in r["patches"][:r["patches"].index(p)]) + 1:04d}'
    a.output.mkdir(parents=True, exist_ok=True)
    assert not (a.output/'rows.jsonl').exists()
    (a.output/'rows.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in result))
    (a.output/'selection.json').write_text(json.dumps(dict(inputs={str(f):sha(f) for f in [a.previous,a.additional]},
        policy='Previously reviewed fitting recordings only; unique archived frames nearest 2/4 sec within .6 sec; new sources fixed 2/4 sec before SDK; no score-based selection', sampling=sampling),indent=2)+'\n')
    sheets(result,a.output,False)


def sheets(data, output, shuffled):
    pairs = [(r,p) for r in data for p in r['patches'] if p['status']=='patchReady']
    if shuffled:
        random.Random(3103).shuffle(pairs)
    font=ImageFont.load_default(size=16);small=ImageFont.load_default(size=13)
    pages=[]
    for offset in range(0,len(pairs),12):
        batch=pairs[offset:offset+12]
        im=Image.new('RGB',(1120,44+((len(batch)+1)//2)*200),'white');draw=ImageDraw.Draw(im)
        title='PASS 2: shuffled hand RGB only / palm, dorsum, side, unclear / earlier labels hidden' if shuffled else 'PASS 1: source + actual hand / visible surface and contact must be labeled directly'
        draw.text((8,8),title,font=font,fill='black')
        for j,(r,p) in enumerate(batch):
            x=(j%2)*560;y=44+(j//2)*200
            draw.text((x+8,y),p['reviewId'],font=font,fill='black')
            if shuffled:
                im.paste(Image.open(p['patch']).resize((160,160)),(x+8,y+27))
            else:
                frame=Image.open(Path(r['framesDirectory'])/r['image']).convert('RGB')
                ImageDraw.Draw(frame).rectangle(p['box'],outline='red',width=3)
                im.paste(ImageOps.contain(frame,(300,160)),(x+8,y+26))
                im.paste(Image.open(p['patch']).resize((160,160)),(x+320,y+26))
                draw.text((x+80,y),r['benchmarkFrameId']+' '+r['caseId'][:45],font=small,fill='black')
        name=f'review-{offset//12+1:02d}.png';im.save(output/name)
        pages.append(dict(image=name,patches=[p['reviewId'] for r,p in batch]))
    (output/'index.json').write_text(json.dumps(dict(shuffled=shuffled,pages=pages,patches=[dict(reviewId=p['reviewId'],caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],patchSHA256=p['patchSHA256'],box=p['box'],side=p['side'],split=r['annotation']['split']) for r,p in pairs]),indent=2)+'\n')
    print(json.dumps(dict(rows=len(data),patches=len(pairs),pages=len(pages),shuffled=shuffled)))


def main():
    p=argparse.ArgumentParser(description=__doc__);sub=p.add_subparsers(dest='command',required=True)
    q=sub.add_parser('prepare')
    for name in ['previous','additional','output']:q.add_argument(name,type=Path)
    q=sub.add_parser('second-pass');q.add_argument('input',type=Path);q.add_argument('output',type=Path)
    a=p.parse_args()
    if a.command=='prepare':prepare(a)
    else:
        a.output.mkdir(parents=True,exist_ok=True);assert not (a.output/'index.json').exists()
        sheets(rows(a.input),a.output,True)


if __name__=='__main__':main()
