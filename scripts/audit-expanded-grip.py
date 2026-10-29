#!/usr/bin/env python3
"""Post-freeze evidence: exact RGB reconstruction, stage failures and review sheets.

This tool never fits weights, selects checkpoints or changes test references.
"""
import argparse
from collections import Counter, defaultdict
import copy
import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps
import torch


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def rows(path):
    return [json.loads(line) for line in Path(path).read_text().splitlines()]


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def reconstruct(manifests):
    counts = Counter()
    for path in manifests:
        for row in rows(path):
            source = Path(row['framesDirectory']) / row['image']
            assert sha(source) == row['imageSHA256']
            frame = Image.open(source).convert('RGB')
            counts['sourceFrameChecks'] += 1
            images = [(p, 'patch', 'patchSHA256') for p in row['patches'] if p['status'] == 'patchReady']
            if row['context']['status'] == 'ready':
                images.append((row['context'], 'image', 'sha256'))
            for item, name, digest in images:
                assert sha(item[name]) == item[digest]
                expected = frame.crop(item['box']).resize((128, 128), Image.Resampling.BILINEAR)
                assert np.array_equal(np.asarray(expected), np.asarray(Image.open(item[name]).convert('RGB')))
                counts['exactHandCrops' if name == 'patch' else 'exactTorsoCrops'] += 1
                if item.get('origin') == 'manualReviewedTrainingCrop':
                    assert row['annotation']['split'] == 'train'
                    if item.get('referenceEligible'):
                        assert item['manualAcceptanceRequired'] is False
                    counts['trainingOnlyManualProposals'] += 1
    return dict(counts)


def sheet(items, output, title, subtitle):
    font = ImageFont.load_default(size=16)
    small = ImageFont.load_default(size=14)
    canvas = Image.new('RGB', (1320, 62 + len(items) * 204), 'white')
    draw = ImageDraw.Draw(canvas)
    draw.text((10, 8), title, font=font, fill='black')
    draw.text((10, 33), subtitle, font=small, fill='black')
    for index, item in enumerate(items):
        row, patch, lines = item
        y = 62 + index * 204
        frame = Image.open(Path(row['framesDirectory']) / row['image']).convert('RGB')
        marker = ImageDraw.Draw(frame)
        marker.rectangle(patch['box'], outline='red', width=3)
        if row['context']['status'] == 'ready':
            marker.rectangle(row['context']['box'], outline='green', width=3)
        canvas.paste(ImageOps.contain(frame, (320, 174)), (8, y + 24))
        canvas.paste(Image.open(patch['patch']).resize((160, 160)), (340, y + 30))
        if row['context']['status'] == 'ready':
            canvas.paste(Image.open(row['context']['image']), (520, y + 35))
        t = row['pose']['timestamp']
        draw.text((8, y + 3), f"{patch.get('reviewId', '')} / {t['value']/t['timescale']:.2f}s / {patch['side']}", font=small, fill='black')
        for j, line in enumerate(lines):
            draw.text((670, y + 12 + j * 25), line, font=font, fill='black')
    canvas.save(output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('root', type=Path)
    parser.add_argument('--manifest', default='test-approved.jsonl')
    parser.add_argument('--comparison', default='test-comparison')
    parser.add_argument('--output', default='audit')
    args = parser.parse_args()
    root = args.root
    output = root / args.output
    output.mkdir(exist_ok=True)
    assert not (output / 'report.json').exists(), 'Preserve prior evidence'
    freeze = json.loads((root / 'pre-test-freeze.json').read_text())
    assert all(sha(path) == digest for path, digest in freeze['sha256'].items())
    verified = reconstruct([root / 'fitting.jsonl', root / args.manifest, root / 'test-corrected-v1/rows.jsonl'])
    fit = rows(root / 'fitting.jsonl')
    old_sources = {r['recordingId'] for r in rows('build/grip-reviewed/fitting/approved-final.jsonl')}
    new_under = defaultdict(list)
    inventory = Counter()
    for row in fit:
        for patch in row['patches']:
            reference = patch.get('manualReview', {})
            if reference.get('grip') not in ['overhand', 'underhand']:
                continue
            inventory[(row['annotation']['split'], reference['grip'], reference['view'])] += 1
            if row['annotation']['split'] == 'train' and row['recordingId'] not in old_sources and reference['grip'] == 'underhand':
                new_under[row['recordingId']].append((row, patch))
    representatives = [values[0] for _, values in sorted(new_under.items())]
    for page in range(0, len(representatives), 6):
        examples = [(r, p, ['TRAIN reference: chin-up / ' + p['manualReview']['view'], r['caseId'],
                            'Origin: ' + p.get('origin', 'automatic SDK'), 'Reviewed hand RGB; labels are agent references']) for r, p in representatives[page:page+6]]
        sheet(examples, output / f'new-chinup-training-{page//6+1}.png', 'One verified hand crop per newly contributing chin-up training video', 'Original frame / actual 128px hand input / torso context. Training only; no test footage.')
    test = rows(root / args.manifest)
    lookup = {(r['recordingId'], r['imageSHA256'], p['side']): (r, p) for r in test for p in r['patches']}
    predictions = rows(root / args.comparison / 'predictions.jsonl')
    stages = defaultdict(Counter)
    chinups, over_failures, bad_crops = [], [], []
    for prediction in predictions:
        if prediction['patchStatus'] != 'patchReady':
            continue
        row, patch = lookup[(prediction['recordingId'], prediction['imageSHA256'], prediction['side'])]
        result = prediction['results']
        actual = result['new']
        reference = patch['manualReview']
        if prediction['expected'] and prediction['handReferenceEligible']:
            label = prediction['expected']
            target = ['overhand', 'underhand'].index(label)
            view = reference['view']
            for name, probability in [('new', actual['scores']), ('previous', result['previousSameDetector']['scores']), ('oracleNew', result['oracleNew']), ('oraclePrevious', result['oraclePrevious'])]:
                stages[name + ':' + label + ':' + view]['correct' if int(np.argmax(probability)) == target else 'wrong'] += 1
            text = [f'Reference: {label} / {view}', row['caseId'],
                    f"Old chin-up: {result['previousSameDetector']['scores'][1]:.1%}",
                    f"New chin-up: {actual['scores'][1]:.1%}",
                    f"Correct view supplied -> chin-up: {result['oracleNew'][1]:.1%}",
                    'Predicted view: ' + ['front', 'rear', 'oblique'][int(np.argmax(actual['view']))]]
            if label == 'underhand':
                chinups.append((row, patch, text))
            elif actual['scores'][1] > actual['scores'][0]:
                over_failures.append((row, patch, text))
        elif not prediction['handReferenceEligible']:
            bad_crops.append((row, patch, ['INVALID reference: ' + reference['reason'], row['caseId'],
                                         f"New highest score: {max(actual['scores']):.1%}", 'Inference retained; reference never removes model pixels']))
    for page in range(0, len(chinups), 6):
        sheet(chinups[page:page+6], output / f'test-chinup-{page//6+1}.png', 'Every available chin-up hand in the fresh video test', 'Old/new use the same images. Correct-view score is diagnostic only. Acceptance threshold remains 80%.')
    sheet(over_failures, output / 'test-overhand-regressions.png', 'All fresh pull-up crops whose new raw choice is chin-up', 'Correct labels and actual inputs were reviewed before predictions. No post-test fitting.')
    sheet(bad_crops, output / 'test-invalid-crops.png', 'Rejected reference crops remain visible to both models', 'These crops are not credited as correct hand classifications; accepted wrong targets count as pipeline failures.')

    # Reuse already exposed, reviewed head-turn RGB inputs. This is a regression
    # diagnostic, never part of fresh test accuracy or checkpoint selection.
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    cases = [x for x in json.loads(Path('build/grip-overhand-audit/cases.json').read_text()) if 'irFDiPohB8E' in x['row']['caseId']]
    originals = {x['row']['imageSHA256']: copy.deepcopy(x['row']) for x in cases}
    for row in originals.values():
        row['context'] = row.get('torsoContext', row['context'])
    prepare = load_module('expanded_audit_crop', 'prepare-reviewed-grip.py')
    prepared = prepare.recrop(list(originals.values()), output / 'head-turn-inputs')
    head_lookup = {(r['imageSHA256'], p['side']): (r, p) for r in prepared for p in r['patches'] if p['status'] == 'patchReady'}
    learning = load_module('expanded_audit_learning', 'train-expanded-grip.py')
    models = {}
    for name, path in [('new', root / 'model'), ('previous', Path('build/grip-reviewed/model'))]:
        model = learning.ReviewedGrip(False)
        model.load_state_dict(torch.load(path / 'weights.pt', weights_only=True))
        models[name] = model.eval()
    head = Counter()
    with torch.inference_mode():
        for case in cases:
            pair = head_lookup[(case['row']['imageSHA256'], case['patch']['side'])]
            hand, context, face = [x.unsqueeze(0) for x in learning.base.images(pair)]
            for name, model in models.items():
                scores, view = model(hand, context, face)
                head[name + 'FrontCorrect'] += int(view.argmax(1) == 0)
                head[name + 'GripCorrect'] += int(scores.argmax(1) == 0)
                absent = face.clone(); absent[:, :5] = 0
                visible = face.clone(); visible[:, 0] = 1
                head[name + 'FaceToggleFlips'] += int(model.orientation(context, absent).argmax(1) != model.orientation(context, visible).argmax(1))
    report = dict(qualification='Post-freeze audit only; agent labels; no weight, threshold or reference changes',
                  frozenBindingsUnchanged=True, reconstruction=verified, newlyContributingChinupTrainingVideos=len(new_under),
                  fittingClassViewInventory={':'.join(k): v for k, v in inventory.items()}, testViewBreakdown=stages,
                  headTurnDevelopment=dict(observations=len(cases), uniqueFrames=len(prepared), counts=head),
                  inputHashes={str(p): sha(p) for p in [root/'fitting.jsonl', root/args.manifest, root/args.comparison/'predictions.jsonl', Path(__file__)]})
    (output / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(json.dumps(report, indent=2))


if __name__ == '__main__':
    main()
