#!/usr/bin/env python3
"""Show actual source frames and model input pixels from the frozen v17 run."""
import json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / 'build/grip-controlled-v17'


def lines(path):
    return [json.loads(x) for x in path.read_text().splitlines()]


def main():
    cards = [
        ('baseline', 'pullup', 12.0166666667, 'Development: pull-up', 'Correct overhand; front view supplied.'),
        ('baseline', 'chinup', 16.0016666667, 'Development: chin-up', 'Correct underhand, but first label arrives late.'),
        ('test', 'k600_EVO-6Z8j3Dk_000070_000080', 9.0757333333, 'Separate video: wrong grip', 'Front is correct. Blurred palm mistaken for dorsum.'),
        ('test', 'k600_J5i4GcGt2oY_000004_000014', 1.4, 'Separate video: admission blocked', 'Rear is supplied; no extended-arm start acquired.'),
    ]
    canvas = Image.new('RGB', (1320, 1000), '#eff2f5')
    draw = ImageDraw.Draw(canvas)
    title = ImageFont.load_default(size=24)
    font = ImageFont.load_default(size=17)
    small = ImageFont.load_default(size=15)
    draw.text((18, 14), 'Frozen grip diagnostic: source image + exact automatic hand crops', fill='#182330', font=title)
    draw.text((18, 48), 'Confirmed camera view; model thresholds stay 0.80. Offline replay, not phone performance.', fill='#344454', font=font)
    records = []
    for index, (stage, case_id, target, heading, note) in enumerate(cards):
        folder = OUT / stage
        row = min([r for r in lines(folder/'rows.jsonl') if r['caseId'] == case_id],
                  key=lambda r: abs(r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']-target))
        observation = next(r for r in lines(folder/'observations.jsonl') if r['imageSHA256'] == row['imageSHA256'])
        x, y = 12 + index % 2 * 660, 90 + index // 2 * 450
        draw.rounded_rectangle((x, y, x+644, y+436), radius=10, fill='white')
        draw.text((x+12, y+10), heading, fill='#172839', font=title)
        draw.text((x+12, y+43), f"{observation['seconds']:.2f}s | {stage}", fill='#344454', font=font)
        with Image.open(Path(row['framesDirectory'])/row['image']) as source:
            thumb = ImageOps.contain(source.convert('RGB'), (302, 260))
            canvas.paste(thumb, (x+12+(302-thumb.width)//2, y+78+(260-thumb.height)//2))
        for j, hand in enumerate(observation['hands']):
            px = x+332+j*148
            draw.text((px, y+78), hand['side'], fill='#172839', font=font)
            with Image.open(hand['patch']) as patch:
                canvas.paste(patch, (px, y+104))
            surface = hand['surface']
            label = ['palm', 'dorsum', 'side'][max(range(3), key=lambda n: surface[n])]
            draw.text((px, y+244), f'{label} {max(surface):.2f}', fill='#172839', font=small)
            draw.text((px, y+266), f"readable {hand['visible']:.2f}", fill='#344454', font=small)
        draw.text((x+12, y+354), note, fill='#172839', font=font)
        draw.text((x+12, y+387), 'No crop corrections or test-time label changes.', fill='#536170', font=small)
        records.append(dict(stage=stage, caseId=case_id, imageSHA256=row['imageSHA256'], seconds=observation['seconds']))
    path = OUT / 'examples.jpg'
    assert not path.exists()
    canvas.save(path, quality=93)
    (OUT/'examples-index.json').write_text(json.dumps(records, indent=2)+'\n')
    print(path)


if __name__ == '__main__':
    main()
