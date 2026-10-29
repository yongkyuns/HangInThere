#!/usr/bin/env python3
"""Audit frozen RGB artifacts and view sensitivity; never fit or tune on test."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont, ImageOps
import numpy as np
import torch
from torchvision.transforms import ToTensor


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('root', type=Path)
    a = p.parse_args(); root = a.root
    torch.set_num_threads(2)
    audit = dict(pixelChecks={}, gripViewDistribution={}, counterfactualNeutral={})
    rows_by_role = {}
    for role, filename in [('fitting', 'fitting.jsonl'), ('test', 'test-approved.jsonl')]:
        rows = [json.loads(l) for l in (root/filename).read_text().splitlines()]
        rows_by_role[role] = rows
        count = Counter(); distribution = Counter()
        for r in rows:
            source = Path(r['framesDirectory'])/r['image']
            assert sha(source) == r['imageSHA256']; count['frames'] += 1
            im = Image.open(source).convert('RGB')
            for item, path_key, hash_key in [(q, 'patch', 'patchSHA256') for q in r['patches'] if q['status'] == 'patchReady'] + ([(r['context'], 'image', 'sha256')] if r['context']['status'] == 'ready' else []):
                assert sha(item[path_key]) == item[hash_key]
                expected = im.crop(item['box']).resize((128, 128), Image.Resampling.BILINEAR)
                assert np.array_equal(np.asarray(expected), np.asarray(Image.open(item[path_key]).convert('RGB'))), item[path_key]
                count['handCrops' if path_key == 'patch' else 'torsoCrops'] += 1
            for q in r['patches']:
                ref = q.get('threewayReview', {})
                if ref.get('grip') in ['overhand', 'underhand', 'neutral']:
                    distribution[r['annotation']['split']+':'+ref['grip']+':'+ref['view']] += 1
        audit['pixelChecks'][role] = count; audit['gripViewDistribution'][role] = distribution
    fit_sources = {r['recordingId'] for r in rows_by_role['fitting']}
    test_sources = {r['recordingId'] for r in rows_by_role['test']}
    assert not fit_sources & test_sources
    audit['disjointRecordingHashes'] = True
    states = [torch.load(root/'staged-models'/name/'weights.pt', weights_only=True) for name in ['binary', 'threeway']]
    shared = [k for k in states[0] if not k.startswith('grip.') and k != 'temperature']
    assert all(torch.equal(states[0][k], states[1][k]) for k in shared)
    assert torch.equal(states[0]['temperature'][1:], states[1]['temperature'][1:])
    audit['identicalSharedStateTensors'] = len(shared)
    model = torch.jit.load(str(root/'staged-models/threeway/GripHandClassifier.pt')).eval()
    neutral = [(r, q) for r in rows_by_role['test'] for q in r['patches'] if q.get('threewayReview', {}).get('grip') == 'neutral']
    with torch.inference_mode():
        images = torch.stack([ToTensor()(Image.open(q['patch']).convert('RGB')) for r, q in neutral])
        for i, view in enumerate(['front', 'rear', 'oblique']):
            v = torch.zeros(len(neutral), 3); v[:, i] = 1
            g, _, _ = model(images, v)
            audit['counterfactualNeutral'][view] = dict(Counter(['overhand', 'underhand', 'neutral'][x] for x in g.argmax(1).tolist()))
    audit['counterfactualQualification'] = 'Same 17 neutral RGB crops with artificially forced view inputs; sensitivity diagnostic, not accuracy on those viewpoints.'
    records = [json.loads(l) for l in (root/'test-comparison/predictions.jsonl').read_text().splitlines()]
    indexed = {r['reviewId']: r for r in records if r.get('reviewId')}
    ids = ['T0031', 'T0085', 'T0097', 'T0132', 'T0003', 'T0063', 'T0016', 'T0055', 'T0091']
    font = ImageFont.load_default(size=17); small = ImageFont.load_default(size=14)
    canvas = Image.new('RGB', (1230, 1000), 'white'); d = ImageDraw.Draw(canvas)
    d.text((12, 10), 'Held-out actual inputs / raw model choices (not accepted app labels)', fill='black', font=font)
    for i, key in enumerate(ids):
        r = indexed[key]; ref = r['reference']; xx = i%3*410; yy = 45+i//3*315
        canvas.paste(Image.open(r['patch']).resize((180, 180)), (xx+10, yy+25))
        d.text((xx+10, yy), key+' / '+str(r['expected']), fill='black', font=font)
        lines = ['surface: '+ref['surface'], 'contact: '+ref['contact'], 'source: '+r['origin']]
        for stage, label in [('oracleView', 'Reviewed view'), ('automaticView', 'Automatic view')]:
            if stage == 'automaticView' and r['context']['status'] != 'ready':
                lines.append(label+': unavailable')
                continue
            result = r['results']['threeway:'+stage]
            choice = ['overhand', 'underhand', 'neutral'][int(np.argmax(result['grip']))]
            lines.append(label+': '+choice+' '+format(max(result['grip']), '.2f'))
        for j, line in enumerate(lines): d.text((xx+10, yy+212+j*18), line, fill='black', font=small)
    canvas.save(root/'test-comparison/examples.png')
    (root/'test-comparison/audit.json').write_text(json.dumps(audit, indent=2)+'\n')
    print(json.dumps(audit, indent=2))


if __name__ == '__main__':
    main()
