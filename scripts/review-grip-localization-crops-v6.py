#!/usr/bin/env python3
"""Blind mixed-method review of exact source/crop pairs for localization only."""
import argparse
import hashlib
import json
from pathlib import Path
import random

from PIL import Image, ImageDraw, ImageFont, ImageOps


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['source', 'baseline', 'candidate', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    assert not (args.output/'index.json').exists()
    sources = {(r['recordingId'], r['imageSHA256']): r for r in map(json.loads, args.source.read_text().splitlines()) if r.get('evaluationFrame', True)}
    items = []
    for method, path in [('baseline', args.baseline), ('candidate', args.candidate)]:
        for r in map(json.loads, path.read_text().splitlines()):
            key = r['recordingId'], r['imageSHA256']
            if key not in sources:
                continue
            source = sources[key]
            for i, p in enumerate(r['patches']):
                if p['status'] != 'patchReady':
                    continue
                items.append(dict(method=method, frameId=source['localizationFrameId'], recordingId=r['recordingId'], imageSHA256=r['imageSHA256'],
                    source=str(Path(source['framesDirectory'])/source['image']), patch=p['patch'], patchSHA256=p['patchSHA256'], box=p['box'],
                    candidateId=p.get('candidateId'), slot=p.get('side'), patchIndex=i))
    random.Random(3108).shuffle(items)
    font = ImageFont.load_default(size=15)
    pages = []
    for offset in range(0, len(items), 12):
        batch = items[offset:offset+12]
        canvas = Image.new('RGB', (1320, 6*220), 'white')
        draw = ImageDraw.Draw(canvas)
        for i, item in enumerate(batch):
            item['reviewId'] = f'P{offset+i+1:03d}'
            x, y = i%2*660, i//2*220
            draw.text((x+5, y+3), item['reviewId']+' '+item['frameId'], fill='black', font=font)
            source = Image.open(item['source']).convert('RGB')
            ImageDraw.Draw(source).rectangle(item['box'], outline='red', width=3)
            canvas.paste(ImageOps.contain(source, (430, 180)), (x+5, y+30))
            canvas.paste(Image.open(item['patch']).resize((180, 180)), (x+460, y+30))
        name = f'crops-{offset//12+1:02d}.png'
        canvas.save(args.output/name)
        pages.append(dict(image=name, reviewIds=[x['reviewId'] for x in batch]))
    (args.output/'index.json').write_text(json.dumps(dict(inputSHA256={str(p):sha(p) for p in [args.source,args.baseline,args.candidate]},
        scope='Method hidden; manually assign primary image-left/right visible hand h1/h2, partial p, non-hand n, wrong person w, ambiguous ownership a, non-overhead o, unknown u. No grip labels.', pages=pages, patches=items), indent=2)+'\n')
    print(json.dumps(dict(crops=len(items), pages=len(pages))))


if __name__ == '__main__':
    main()
