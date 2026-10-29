#!/usr/bin/env python3
"""Source-coordinate sheets for hand-box references; no detector outputs shown."""
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    assert not (args.output/'index.json').exists()
    rows = [r for r in map(json.loads, args.input.read_text().splitlines()) if r.get('evaluationFrame', True)]
    font = ImageFont.load_default(size=15)
    pages = []
    for offset in range(0, len(rows), 4):
        batch = rows[offset:offset+4]
        canvas = Image.new('RGB', (1400, 1050), 'white')
        draw = ImageDraw.Draw(canvas)
        for i, row in enumerate(batch):
            source = Path(row['framesDirectory'])/row['image']
            assert hashlib.sha256(source.read_bytes()).hexdigest() == row['imageSHA256']
            image = Image.open(source).convert('RGB')
            w, h = image.size
            scale = min(650/w, 465/h)
            image = image.resize((round(w*scale), round(h*scale)), Image.Resampling.BILINEAR)
            d = ImageDraw.Draw(image)
            step = 50 if max(w, h) <= 800 else 100
            for x in range(0, w, step):
                d.line([(x*scale, 0), (x*scale, image.height)], fill='#aaaaaa', width=1)
                d.text((x*scale+2, 5), str(x), fill='red', font=font)
            for y in range(0, h, step):
                d.line([(0, y*scale), (image.width, y*scale)], fill='#aaaaaa', width=1)
                d.text((2, y*scale+5), str(y), fill='red', font=font)
            x, y = i%2*700, i//2*525
            ts = row['pose']['timestamp']
            draw.text((x+4, y+4), f"{row['localizationFrameId']}  {ts['value']/ts['timescale']:.2f}s  {w}x{h}", fill='black', font=font)
            draw.text((x+4, y+25), row['caseId'], fill='black', font=font)
            canvas.paste(image, (x+4, y+50))
        name = f'source-{offset//4+1:02d}.png'
        canvas.save(args.output/name)
        pages.append(dict(image=name, frames=[r['localizationFrameId'] for r in batch]))
    (args.output/'index.json').write_text(json.dumps(dict(inputSHA256=hashlib.sha256(args.input.read_bytes()).hexdigest(),
        pages=pages, frames=[dict(id=r['localizationFrameId'], caseId=r['caseId'], imageSHA256=r['imageSHA256']) for r in rows]), indent=2)+'\n')
    print(json.dumps(dict(frames=len(rows), pages=len(pages))))


if __name__ == '__main__':
    main()
