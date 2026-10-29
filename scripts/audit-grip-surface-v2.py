#!/usr/bin/env python3
"""Verify reviewed RGB reconstruction and summarize an already frozen comparison."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    args = parser.parse_args()
    root = args.root
    audit = {'pixelChecks': {}, 'gripViewDistribution': {}, 'confidenceDiagnostics': {}}
    groups = {}
    for role, filename in [('fitting', 'fitting.jsonl'), ('test', 'test-approved.jsonl')]:
        rows = [json.loads(line) for line in (root / filename).read_text().splitlines()]
        counts = Counter()
        distribution = Counter()
        groups[role] = set()
        for row in rows:
            groups[role].add(row.get('sourceGroup') or row['annotation'].get('sourceGroup') or 'recording:' + row['recordingId'])
            source = Path(row['framesDirectory']) / row['image']
            assert sha(source) == row['imageSHA256']
            counts['frames'] += 1
            with Image.open(source) as image:
                image = image.convert('RGB')
                items = [(p, 'patch', 'patchSHA256', 'handCrops') for p in row['patches'] if p['status'] == 'patchReady']
                context = row.get('context', {})
                if context.get('status') == 'ready':
                    items.append((context, 'image', 'sha256', 'torsoCrops'))
                for item, path_key, hash_key, category in items:
                    assert sha(item[path_key]) == item[hash_key]
                    expected = image.crop(item['box']).resize((128, 128), Image.Resampling.BILINEAR)
                    with Image.open(item[path_key]) as actual:
                        assert np.array_equal(np.asarray(expected), np.asarray(actual.convert('RGB'))), item[path_key]
                    counts[category] += 1
            for patch in row['patches']:
                ref = patch.get('threewayReview', {})
                if ref.get('grip') in ['overhand', 'underhand', 'neutral']:
                    distribution[row['annotation']['split'] + ':' + ref['grip'] + ':' + ref['view']] += 1
        audit['pixelChecks'][role] = dict(counts)
        audit['gripViewDistribution'][role] = dict(distribution)
    assert not groups['fitting'] & groups['test']
    audit['disjointSourceGroups'] = True
    frozen = json.loads((root / 'pre-test-freeze.json').read_text())
    assert all(sha(path) == digest for path, digest in frozen['sha256'].items())
    audit['unchangedFreezePins'] = len(frozen['sha256'])
    records = [json.loads(line) for line in (root / 'test-comparison/predictions.jsonl').read_text().splitlines()]
    known = [r for r in records if r['reference']['surface'] != 'unclear']
    for name in ['previous', 'new']:
        values = [r['results'][name] for r in known]
        audit['confidenceDiagnostics'][name] = {
            'knownCrops': len(values),
            'surfaceAtLeastPointEight': sum(max(v['surface']) >= .8 for v in values),
            'visibleAtLeastPointEight': sum(v['visible'] >= .8 for v in values),
            'surfaceMaxQuantiles': np.quantile([max(v['surface']) for v in values], [0, .25, .5, .75, 1]).tolist(),
            'visibleQuantiles': np.quantile([v['visible'] for v in values], [0, .25, .5, .75, 1]).tolist(),
        }
    ids = ['E0071', 'E0107', 'E0119', 'E0141', 'E0039', 'E0117', 'E0093', 'E0005', 'E0015']
    indexed = {r['reviewId']: r for r in records}
    font = ImageFont.load_default(size=18)
    small = ImageFont.load_default(size=15)
    canvas = Image.new('RGB', (1260, 1090), 'white')
    draw = ImageDraw.Draw(canvas)
    draw.text((12, 10), 'Fresh held-out hand pixels: appearance predictions only', fill='black', font=font)
    draw.text((12, 35), 'Raw = forced three-class choice; accepted = fixed confidence + visibility gates', fill='black', font=small)
    for i, key in enumerate(ids):
        record = indexed[key]
        ref = record['reference']
        x, y = i % 3 * 420 + 12, i // 3 * 335 + 75
        draw.text((x, y), key + ' reference: ' + ref['surface'], fill='black', font=font)
        with Image.open(record['patch']) as patch:
            canvas.paste(patch.resize((176, 176)), (x, y + 27))
        lines = ['contact: ' + ref['contact'] + ' / view: ' + ref['view']]
        for name in ['previous', 'new']:
            value = record['results'][name]
            lines += [name + ' raw: ' + value['forcedKnownPrediction'] + ' ' + format(max(value['surface']), '.2f'),
                      name + ' accepted: ' + value['acceptedPrediction']]
        for j, line in enumerate(lines):
            draw.text((x, y + 210 + j * 20), line, fill='black', font=small)
    canvas.save(root / 'test-comparison/examples.png')
    (root / 'test-comparison/audit.json').write_text(json.dumps(audit, indent=2) + '\n')
    print(json.dumps(audit, indent=2))


if __name__ == '__main__':
    main()
