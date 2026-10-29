#!/usr/bin/env python3
"""Verify contact evidence, unchanged base tensors, and first-test trade-offs."""
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
    assert all(sha(path) == value for path, value in freeze['sha256'].items())
    baseline = torch.load('build/grip-quality-v3/model-guarded/weights.pt', weights_only=True)
    selected = torch.load(root / 'model/weights.pt', weights_only=True)
    assert all(torch.equal(value, selected['quality.' + key]) for key, value in baseline.items())
    receipt = json.loads((root / 'model/training-receipt.json').read_text())
    original_encoder = {k.removeprefix('appearance.features.'): v for k, v in baseline.items() if k.startswith('appearance.features.')}
    assert all(torch.equal(value, selected['wide_features.' + key]) for key, value in original_encoder.items())
    audit = dict(unchangedFreezePins=len(freeze['sha256']), unchangedBaseStateTensors=len(baseline),
                 selectedWideEncoderUnchanged=True, pixelChecks={}, contactLabels={}, sourceDisjointness=True)
    source_groups = {}
    for role, filename in [('fitting', 'fitting.jsonl'), ('regression', 'regression-input.jsonl'), ('fresh', 'fresh-approved.jsonl')]:
        rows = [json.loads(line) for line in (root / filename).read_text().splitlines()]
        counts, labels = Counter(), Counter()
        source_groups[role] = {r['annotation'].get('sourceGroup') or 'recording:' + r['recordingId'] for r in rows}
        for row in rows:
            source = Path(row['framesDirectory']) / row['image']
            assert sha(source) == row['imageSHA256']
            counts['frames'] += 1
            with Image.open(source) as image:
                image = image.convert('RGB')
                for patch in row['patches']:
                    if patch['status'] != 'patchReady':
                        continue
                    for item, path_key, digest_key, size, count in [(patch, 'patch', 'patchSHA256', 128, 'tightCrops'), (patch['contactCrop'], 'image', 'sha256', 192, 'contactCrops')]:
                        assert sha(item[path_key]) == item[digest_key]
                        expected = image.crop(item['box']).resize((size, size), Image.Resampling.BILINEAR)
                        with Image.open(item[path_key]) as actual:
                            assert np.array_equal(np.asarray(expected), np.asarray(actual.convert('RGB')))
                        counts[count] += 1
                    labels[patch['contactReference']['contact'] + (':masked' if not patch['contactReference']['qualityEligible'] else '')] += 1
        audit['pixelChecks'][role], audit['contactLabels'][role] = dict(counts), dict(labels)
    assert not source_groups['fitting'] & (source_groups['fresh'] | source_groups['regression'])
    assert not source_groups['fresh'] & source_groups['regression']
    records = [json.loads(line) for line in (root / 'fresh-comparison/predictions.jsonl').read_text().splitlines()]
    report = json.loads((root / 'fresh-comparison/report.json').read_text())
    name = report['selectedVariant']
    audit['freshPerVideo'] = {}
    for model in ['previous', name]:
        videos = report['perVideo'][model]
        full = []
        for r in records:
            v, ref = r['results'][model], r['reference']
            if max(v['surface']) >= .8 and v['visible'][1] >= .8 and v['contact'][1] >= .8:
                predicted = ['palm', 'dorsum', 'side'][int(np.argmax(v['surface']))]
                if ref.get('surface') == predicted and ref['contact'] == 'closed' and ref['qualityEligible']:
                    full.append(r)
        audit['freshPerVideo'][model] = dict(contactHitVideos=sum(v.get('truePositive', 0) > 0 for v in videos.values()),
                                             positiveContactVideos=sum(v.get('positive', 0) > 0 for v in videos.values()),
                                             verifiedFullAcceptVideos=len({r['recordingId'] for r in full}), verifiedFullAcceptCrops=len(full))
    ids = ['X0017', 'X0016', 'X0028', 'X0070', 'X0007', 'X0047', 'X0096', 'X0103', 'X0040']
    indexed = {r['reviewId']: r for r in records}
    font, small = ImageFont.load_default(size=16), ImageFont.load_default(size=14)
    canvas = Image.new('RGB', (1440, 1160), 'white')
    draw = ImageDraw.Draw(canvas)
    draw.text((10, 10), 'Contact repair: actual tight / wider inputs, fixed 0.8 gates', font=font, fill='black')
    draw.text((10, 35), 'Contact improves; full grip evidence remains unreliable. Verified fresh accepts all come from one video.', font=small, fill='black')
    for index, key in enumerate(ids):
        r = indexed[key]
        ref = r['reference']
        x, y = index % 3 * 480 + 10, index // 3 * 355 + 75
        draw.text((x, y), key + ' / ' + ref['surface'] + ' / ' + ref['contact'], font=font, fill='black')
        for offset, path in [(0, r['patch']), (175, r['contactCrop'])]:
            with Image.open(path) as image:
                canvas.paste(image.resize((165, 165)), (x + offset, y + 27))
        v = r['results'][name]
        lines = [f'Same raw class: {["palm", "dorsum", "side"][int(np.argmax(v["surface"]))]} {max(v["surface"]):.2f}', f'Same visibility: {v["visible"][1]:.2f}']
        for model, label in [('previous', 'Before'), (name, 'After')]:
            v = r['results'][model]
            passed = max(v['surface']) >= .8 and v['visible'][1] >= .8 and v['contact'][1] >= .8
            lines.append(f'{label} contact {v["contact"][1]:.2f}; full gates ' + ('ACCEPT' if passed else 'reject'))
        if not ref['qualityEligible']:
            lines.append('Contact identity/observability masked')
        for j, line in enumerate(lines):
            draw.text((x, y + 205 + j * 22), line, font=small, fill='black')
    canvas.save(root / 'examples.png')
    (root / 'audit.json').write_text(json.dumps(audit, indent=2) + '\n')
    print(json.dumps(audit, indent=2))


if __name__ == '__main__':
    main()
