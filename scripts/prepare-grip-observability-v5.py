#!/usr/bin/env python3
"""Build a source-bound human review packet; model scores and labels stay hidden."""
import argparse
import hashlib
import html
import json
from pathlib import Path
import subprocess

from PIL import Image, ImageDraw, ImageFont, ImageOps


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    out = args.output.absolute()
    out.mkdir(parents=True, exist_ok=True)
    assert not (out / 'index.json').exists()
    root = Path(__file__).resolve().parents[1]
    rows = [json.loads(x) for x in (root / 'build/grip-contact-v4/fresh-approved.jsonl').read_text().splitlines()]
    sources = {s['id']: s for s in json.loads((root / 'Evaluation/fixtures/grip-contact-test-reserve-v4.json').read_text())['sources']}
    ids = ['X0007', 'X0016', 'X0017', 'X0028', 'X0047', 'X0070', 'X0079', 'X0096']
    lookup = {p['contactReviewId']: (r, p) for r in rows for p in r['patches'] if p['status'] == 'patchReady'}
    font = ImageFont.load_default(size=20)
    cards, records = [], []
    summary = Image.new('RGB', (1500, len(ids) * 220), 'white')
    for i, review_id in enumerate(ids):
        row, patch = lookup[review_id]
        source = sources[row['caseId']]
        assert sha(source['path']) == source['sha256']
        timestamp = row['pose']['timestamp']['value'] / row['pose']['timestamp']['timescale']
        name = f'A{i + 1:02d}'
        start = max(0., timestamp - 1.5)
        video = out / f'{name}.mp4'
        subprocess.run(['ffmpeg', '-v', 'error', '-nostdin', '-n', '-ss', str(start), '-i', source['path'],
                        '-t', '4', '-an', '-c:v', 'libx264', '-crf', '20', '-preset', 'fast',
                        '-pix_fmt', 'yuv420p', '-movflags', '+faststart', str(video)], check=True)
        frame = Path(row['framesDirectory']) / row['image']
        assert sha(frame) == row['imageSHA256'] and sha(patch['patch']) == patch['patchSHA256']
        image = Image.open(frame).convert('RGB')
        ImageDraw.Draw(image).rectangle(patch['box'], outline='red', width=3)
        card = Image.new('RGB', (1500, 220), 'white')
        draw = ImageDraw.Draw(card)
        draw.text((8, 8), f'{name}: source frame (red = actual model input)', fill='black', font=font)
        card.paste(ImageOps.contain(image, (460, 175)), (8, 40))
        card.paste(Image.open(patch['patch']).resize((175, 175)), (490, 40))
        wide = Image.open(patch['contactCrop']['image'])
        card.paste(wide.resize((175, 175)), (690, 40))
        draw.text((490, 8), 'Tight hand', fill='black', font=font)
        draw.text((690, 8), 'Wider contact', fill='black', font=font)
        draw.multiline_text((900, 45), '1. Grip clear in video?\n2. Hand surface clear in tight crop?\n3. Correct athlete and hand?\nUnknown is a valid answer.', fill='black', font=font, spacing=12)
        card.save(out / f'{name}.png')
        summary.paste(card, (0, i * 220))
        records.append(dict(id=name, oldReviewId=review_id, caseId=row['caseId'], recordingId=row['recordingId'],
                            originalSource=source['path'], sourceSHA256=source['sha256'], imageSHA256=row['imageSHA256'],
                            patchSHA256=patch['patchSHA256'], box=patch['box'], side=patch['side'],
                            videoStartSeconds=start, originalTimestampSeconds=timestamp, videoSHA256=sha(video),
                            role='Exposed diagnostic only; never fresh evaluation or automatic human verification'))
        cards.append(f'<section><h2>{name}</h2><video controls muted playsinline preload="metadata" src="{name}.mp4"></video><img src="{name}.png" alt="{name} source and actual crops"><p>Reply with grip (pull-up / chin-up / neutral / unknown), tight-crop surface (palm / back / side / unknown), and whether this is the exercising athlete’s hand.</p></section>')
    summary.save(out / 'summary.png')
    (out / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Grip evidence review</title><style>body{font:20px system-ui;margin:24px;background:#fafafa;color:#111}section{border-top:2px solid #bbb;padding:16px 0}video{width:min(100%,800px);max-height:500px}img{display:block;width:100%;max-width:1500px}p{max-width:1000px;line-height:1.5}</style><h1>Can the grip actually be seen?</h1><p>These eight diagnostic examples come from already inspected videos. The clips retain their original viewpoint; the red box identifies the exact hand crop. No model guesses or previous labels are shown. Judge what the pixels support; use unknown when unsure.</p>' + ''.join(cards))
    (out / 'index.json').write_text(json.dumps(dict(scriptSHA256=sha(__file__), reviewer='Pending human input; generated packet is not a human annotation', cases=records), indent=2) + '\n')
    print(json.dumps(dict(cases=len(records), html=str(out / 'index.html'), summary=str(out / 'summary.png'))))


if __name__ == '__main__':
    main()
