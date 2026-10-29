#!/usr/bin/env python3
"""Review actual tight/contact pixels without displaying inherited contact labels."""
import argparse
import hashlib
import json
from pathlib import Path
import random

from PIL import Image, ImageDraw, ImageFont, ImageOps


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def sheets(rows, output, second, required):
    pairs = [(r, p) for r in rows for p in r['patches'] if p['status'] == 'patchReady' and (not required or p.get('contactReviewRequired'))]
    if second:
        random.Random(3106).shuffle(pairs)
    output.mkdir(parents=True, exist_ok=True)
    assert not (output / 'index.json').exists()
    font, small = ImageFont.load_default(size=15), ImageFont.load_default(size=13)
    pages = []
    for offset in range(0, len(pairs), 12):
        batch = pairs[offset:offset + 12]
        canvas = Image.new('RGB', (1240, 50 + ((len(batch) + 1) // 2) * 220), 'white')
        draw = ImageDraw.Draw(canvas)
        draw.text((8, 8), 'PASS 2 shuffled: tight hand / wider contact; earlier labels hidden' if second else 'PASS 1: source / tight hand / wider contact; direct contact labels', font=font, fill='black')
        for j, (r, p) in enumerate(batch):
            x, y = j % 2 * 620 + 8, j // 2 * 220 + 45
            draw.text((x, y), p['contactReviewId'], font=font, fill='black')
            if not second:
                image = Image.open(Path(r['framesDirectory']) / r['image']).convert('RGB')
                ImageDraw.Draw(image).rectangle(p['contactCrop']['box'], outline='red', width=3)
                canvas.paste(ImageOps.contain(image, (245, 165)), (x, y + 28))
                draw.text((x + 80, y), r['caseId'][:58], font=small, fill='black')
            left = x + (255 if not second else 0)
            canvas.paste(Image.open(p['patch']).resize((155, 155)), (left, y + 28))
            canvas.paste(Image.open(p['contactCrop']['image']).resize((175, 175)), (left + 165, y + 28))
        filename = f'review-{offset // 12 + 1:02d}.png'
        canvas.save(output / filename)
        pages.append(dict(image=filename, patches=[p['contactReviewId'] for r, p in batch]))
    (output / 'index.json').write_text(json.dumps(dict(second=second, pages=pages,
        patches=[dict(reviewId=p['contactReviewId'], caseId=r['caseId'], recordingId=r['recordingId'], imageSHA256=r['imageSHA256'],
                      tightSHA256=p['patchSHA256'], contactSHA256=p['contactCrop']['sha256']) for r, p in pairs]), indent=2) + '\n')
    print(json.dumps(dict(patches=len(pairs), pages=len(pages), second=second)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--second', action='store_true')
    parser.add_argument('--required-only', action='store_true')
    args = parser.parse_args()
    rows = list(map(json.loads, args.input.read_text().splitlines()))
    sheets(rows, args.output, args.second, args.required_only)


if __name__ == '__main__':
    main()
