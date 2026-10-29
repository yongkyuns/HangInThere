#!/usr/bin/env python3
"""Audit frozen quality-only changes and show successes alongside rejects/errors."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
import torch


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    root = args.root
    torch.set_num_threads(2)
    freeze = json.loads((root / 'pre-test-freeze.json').read_text())
    assert all(sha(path) == digest for path, digest in freeze['sha256'].items())
    baseline = torch.load('build/grip-surface-v2/model/weights.pt', weights_only=True)
    state = torch.load(root / 'model-guarded/weights.pt', weights_only=True)
    assert all(torch.equal(value, state['appearance.' + key]) for key, value in baseline.items())
    receipt = json.loads((root / 'model-guarded/training-receipt.json').read_text())
    raw_state = torch.load(root / 'model' / receipt['selectedVariant'] / 'weights.pt', weights_only=True)
    assert all(torch.equal(value, state[key]) for key, value in raw_state.items() if key not in ['slope', 'bias'])
    audit = dict(unchangedFreezePins=len(freeze['sha256']), unchangedAppearanceStateTensors=len(baseline),
                 guardOnlyChangesCalibrationBuffers=True, pixelChecks={}, populationBySurface={}, acceptedSourceCounts={})
    for role, path in [('regression', Path('build/grip-surface-v2/test-approved.jsonl')), ('fresh', root / 'fresh-approved.jsonl')]:
        rows = [json.loads(line) for line in path.read_text().splitlines()]
        counts = Counter()
        surfaces = Counter()
        for row in rows:
            source = Path(row['framesDirectory']) / row['image']
            assert sha(source) == row['imageSHA256']
            counts['frames'] += 1
            with Image.open(source) as image:
                image = image.convert('RGB')
                for patch in row['patches']:
                    if patch['status'] != 'patchReady':
                        continue
                    assert sha(patch['patch']) == patch['patchSHA256']
                    expected = image.crop(patch['box']).resize((128, 128), Image.Resampling.BILINEAR)
                    with Image.open(patch['patch']) as actual:
                        assert np.array_equal(np.asarray(expected), np.asarray(actual.convert('RGB')))
                    counts['handCrops'] += 1
                    surfaces[patch['threewayReview']['surface']] += 1
        audit['pixelChecks'][role] = dict(counts)
        audit['populationBySurface'][role] = dict(surfaces)
        records = [json.loads(line) for line in (root / f'{role}-comparison/predictions.jsonl').read_text().splitlines()]
        accepted = []
        for r in records:
            v = r['results']['new']
            if max(v['surface']) >= .8 and v['visible'][1] >= .8 and v['contact'][1] >= .8:
                accepted.append(r)
        audit['acceptedSourceCounts'][role] = dict(crops=len(accepted), videos=len({r['recordingId'] for r in accepted}))
    examples = [('fresh', 'Q0040'), ('fresh', 'Q0042'), ('regression', 'E0107'),
                ('regression', 'E0119'), ('fresh', 'Q0085'), ('fresh', 'Q0020'),
                ('fresh', 'Q0075'), ('fresh', 'Q0044'), ('fresh', 'Q0067')]
    records = {}
    for role in ['fresh', 'regression']:
        records[role] = {r['reviewId']: r for r in map(json.loads, (root / f'{role}-comparison/predictions.jsonl').read_text().splitlines())}
    canvas = Image.new('RGB', (1320, 1140), 'white')
    draw = ImageDraw.Draw(canvas)
    font, small = ImageFont.load_default(size=17), ImageFont.load_default(size=14)
    draw.text((12, 10), 'Quality-head repair / same surface classifier / all thresholds still 0.8', font=font, fill='black')
    draw.text((12, 36), 'Fresh successes are from one video; includes a wrong accepted class and difficult rejects.', font=small, fill='black')
    for i, (role, key) in enumerate(examples):
        r = records[role][key]
        x, y = i % 3 * 440 + 12, i // 3 * 350 + 75
        draw.text((x, y), f'{role} {key} / {r["reference"]["surface"]}', font=font, fill='black')
        with Image.open(r['patch']) as patch:
            canvas.paste(patch.resize((170, 170)), (x, y + 25))
        v = r['results']['new']
        raw = ['palm', 'dorsum', 'side'][int(np.argmax(v['surface']))]
        lines = [f'Raw class: {raw} ({max(v["surface"]):.2f}), unchanged', 'reference contact: ' + r['reference']['contact']]
        for name in ['previous', 'new']:
            v = r['results'][name]
            passed = max(v['surface']) >= .8 and v['visible'][1] >= .8 and v['contact'][1] >= .8
            lines.append(f'{name}: visibility {v["visible"][1]:.2f}, contact {v["contact"][1]:.2f}')
            lines.append(f'{name}: ' + ('ACCEPT' if passed else 'reject'))
        for j, line in enumerate(lines):
            draw.text((x, y + 202 + j * 20), line, font=small, fill='black')
    canvas.save(root / 'examples.png')
    (root / 'audit.json').write_text(json.dumps(audit, indent=2) + '\n')
    print(json.dumps(audit, indent=2))


if __name__ == '__main__':
    main()
