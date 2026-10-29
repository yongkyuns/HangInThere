#!/usr/bin/env python3
"""Render all fresh source samples and exact crops without model scores or labels."""
import argparse
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['input', 'intake', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    assert not (args.output / 'index.json').exists()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = [json.loads(line) for line in args.input.read_text().splitlines()]
    intake = json.loads(args.intake.read_text())
    font = ImageFont.load_default(size=16)
    index = []
    for i, case in enumerate(intake['cases']):
        frames = [r for r in rows if r['recordingId'] == case['sha256']]
        canvas = Image.new('RGB', (1320, 55 + max(1, (len(frames)+2)//3) * 285), 'white')
        draw = ImageDraw.Draw(canvas)
        draw.text((8, 8), f"V{i+1:02d}  {case['id']}  -- source pixels; labels/scores hidden", fill='black', font=font)
        for j, row in enumerate(frames):
            x, y = j % 3 * 440, 50 + j // 3 * 285
            source = Path(row['framesDirectory']) / row['image']
            assert sha(source) == row['imageSHA256']
            image = Image.open(source).convert('RGB')
            draw.text((x+5, y), f"{row['requestedSeconds']:.1f}s  {row['subjectTrack']['status']}", fill='black', font=font)
            canvas.paste(ImageOps.contain(image, (430, 250)), (x+5, y+25))
        canvas.save(args.output / f'source-{i+1:02d}.png')
        index.append(dict(reviewId=f'V{i+1:02d}', caseId=case['id'], recordingId=case['sha256'], frames=len(frames),
                          image=f'source-{i+1:02d}.png', sourceSHA256=case['sha256']))
    (args.output / 'index.json').write_text(json.dumps(dict(inputSHA256=sha(args.input), intakeSHA256=sha(args.intake),
        scriptSHA256=sha(__file__), videos=index), indent=2) + '\n')
    print(json.dumps(dict(videos=len(index), frames=len(rows))))


if __name__ == '__main__':
    main()
