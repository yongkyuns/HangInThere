#!/usr/bin/env python3
"""Read-only, source-bound attribution and visual audit of overhand failures.

Physical appearance targets are derived from reviewed front/rear source labels.
Oblique views have no such target. Counterfactual component replacements are
diagnostics only; they do not alter weights, references or benchmark scores.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont, ImageOps
import torch

ROOT = Path(__file__).absolute().parents[1]
SPEC = importlib.util.spec_from_file_location('components', ROOT/'scripts/train-grip-components.py')
learning = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(learning)
COLORS = dict(appearance='#a73521', orientation='#285aa0', oblique='#806000')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def attribution(prediction):
    assert prediction['expected'] == 'overhand'
    assert prediction['argmax'] == 'underhand'
    view = prediction['orientationExpected']
    appearance = torch.tensor([prediction['appearanceScores']])
    orientation = torch.tensor([prediction['orientationScores']])
    assert prediction['context']['faceCues'][5] == 1
    if view == 'oblique':
        oracle_view = torch.tensor([[0., 0., 1.]])
        neutral = learning.fuse(appearance, oracle_view, torch.ones(1))[0].tolist()
        return dict(category='oblique', expectedAppearance=None, oracleViewGrip=neutral,
                    explanation='No binary appearance target; reviewed oblique view produces equal grip scores')
    assert view in ['front', 'rear']
    expected_appearance = 0 if view == 'front' else 1
    hand_wrong = int(appearance.argmax(1)) != expected_appearance
    # The fusion depends on front-minus-rear, not the top three-way class.
    direction = 'front' if orientation[0, 0] > orientation[0, 1] else 'rear'
    view_wrong = direction != view
    assert hand_wrong != view_wrong, 'A front/rear raw failure must have one incorrect direction'
    oracle_view = torch.tensor([[1., 0., 0.] if view == 'front' else [0., 1., 0.]])
    oracle_hand = torch.tensor([[1., 0.] if expected_appearance == 0 else [0., 1.]])
    corrected_view = learning.fuse(appearance, oracle_view, torch.ones(1))[0].tolist()
    corrected_hand = learning.fuse(oracle_hand, orientation, torch.ones(1))[0].tolist()
    category = 'appearance' if hand_wrong else 'orientation'
    assert (corrected_hand if hand_wrong else corrected_view)[0] > .5
    return dict(category=category, expectedAppearance=learning.APPEARANCE[expected_appearance],
                predictedAppearance=learning.APPEARANCE[int(appearance.argmax(1))],
                fusionDirection=direction, oracleViewGrip=corrected_view, oracleHandGrip=corrected_hand,
                explanation='Replace only the erroneous component to recover the overhand raw choice')


def verify_crop(row, patch):
    source = Path(row['framesDirectory'])/row['image']
    assert sha(source) == row['imageSHA256']
    assert sha(patch['patch']) == patch['patchSHA256']
    with Image.open(source) as original:
        frame = original.convert('RGB')
        rebuilt = frame.crop(patch['box']).resize((128, 128), Image.Resampling.BILINEAR)
        assert np.array_equal(np.asarray(rebuilt), np.asarray(Image.open(patch['patch']).convert('RGB')))
        context = row['context']
        assert sha(context['image']) == context['sha256']
        head = frame.crop(context['box']).resize((128, 128), Image.Resampling.BILINEAR)
        assert np.array_equal(np.asarray(head), np.asarray(Image.open(context['image']).convert('RGB')))


def render(items, output):
    font = ImageFont.load_default(size=16)
    small = ImageFont.load_default(size=14)
    title = ImageFont.load_default(size=19)
    for page in range((len(items)+15)//16):
        batch = items[page*16:(page+1)*16]
        canvas = Image.new('RGB', (1280, 4*325), '#f4f6f8')
        draw = ImageDraw.Draw(canvas)
        for j, item in enumerate(batch):
            x, y = (j%4)*320, (j//4)*325
            row, patch, pred = item['row'], item['patch'], item['prediction']
            category = item['diagnosis']['category']
            image = Image.open(patch['patch']).convert('RGB').resize((192, 192), Image.Resampling.NEAREST)
            canvas.paste(image, (x+8, y+6))
            head = Image.open(row['context']['image']).convert('RGB').resize((104, 104), Image.Resampling.NEAREST)
            canvas.paste(head, (x+208, y+6))
            draw.text((x+208, y+115), 'Head input', font=small, fill='black')
            draw.text((x+8, y+201), f"#{item['index']:02d} {category.upper()} / {pred['predicted']}", font=font, fill=COLORS[category])
            draw.text((x+8, y+224), row['caseId'][10:21]+' / '+row['image']+' / '+patch['side'], font=small, fill='black')
            a, v = pred['appearanceScores'], pred['orientationScores']
            draw.text((x+8, y+246), f"Palm {a[0]:.3f}  Dorsum {a[1]:.3f}", font=small, fill='black')
            draw.text((x+8, y+267), f"Front {v[0]:.3f} Rear {v[1]:.3f} Oblique {v[2]:.3f}", font=small, fill='black')
            draw.text((x+8, y+288), f"Reference view: {pred['orientationExpected']}; chin-up {pred['probabilities'][1]:.3f}", font=small, fill='black')
        canvas.save(output/f'all-failures-{page+1}.png')

    # Each source gets one representative, preferring accepted errors.
    by_source = defaultdict(list)
    for item in items:
        by_source[item['row']['caseId']].append(item)
    representatives = [max(v, key=lambda x: (x['newRegression'], x['prediction']['predicted'] != 'unknown',
                                             max(x['prediction']['probabilities']))) for v in by_source.values()]
    for page in range((len(representatives)+2)//3):
        batch = representatives[page*3:(page+1)*3]
        canvas = Image.new('RGB', (1420, len(batch)*390), 'white')
        draw = ImageDraw.Draw(canvas)
        for j, item in enumerate(batch):
            row, patch, pred = item['row'], item['patch'], item['prediction']
            diagnosis = item['diagnosis']; y = j*390
            source = Image.open(Path(row['framesDirectory'])/row['image']).convert('RGB')
            marked = source.copy(); annotation = ImageDraw.Draw(marked)
            annotation.rectangle(patch['box'], outline='#ff2424', width=2)
            annotation.rectangle(row['context']['box'], outline='#00c46a', width=2)
            canvas.paste(ImageOps.contain(marked, (580, 290)), (8, y+50))
            # Both hands and bar context, from original source resolution.
            ready = [p['box'] for p in row['patches'] if p['status']=='patchReady']
            x0, y0 = min(b[0] for b in ready), min(b[1] for b in ready)
            x1, y1 = max(b[2] for b in ready), max(b[3] for b in ready)
            padding = max(30, int((x1-x0)*.15))
            region = source.crop([x0-padding, y0-padding, x1+padding, y1+padding])
            canvas.paste(ImageOps.contain(region, (350, 235)), (600, y+50))
            canvas.paste(Image.open(patch['patch']).resize((224, 224), Image.Resampling.NEAREST), (960, y+50))
            canvas.paste(Image.open(row['context']['image']).resize((192, 192), Image.Resampling.NEAREST), (1208, y+50))
            draw.text((8, y+8), f"#{item['index']:02d} {row['caseId']} / {patch['side']} / {diagnosis['category'].upper()}", font=title, fill=COLORS[diagnosis['category']])
            draw.text((960, y+282), 'Exact hand input', font=font, fill='black')
            draw.text((1208, y+250), 'Head input', font=font, fill='black')
            a, v = pred['appearanceScores'], pred['orientationScores']
            draw.text((8, y+347), f"Reference: overhand / {pred['orientationExpected']}. Hand: palm {a[0]:.3f}, dorsum {a[1]:.3f}. View: front {v[0]:.3f}, rear {v[1]:.3f}, oblique {v[2]:.3f}.", font=font, fill='black')
            draw.text((8, y+370), f"Output: {pred['predicted']} (underhand score {pred['probabilities'][1]:.3f}). Red: hand crop. Green: selected-athlete head crop.", font=small, fill='black')
        canvas.save(output/f'representative-context-{page+1}.png')
    gallery = ['# Every overhand failure', '',
               'All 64 raw failures. `underhand` means accepted wrong; `unknown` means the research confidence policy abstains.', '',
               'Appearances are source-derived expectations, not independent gold palm/dorsum labels.', '',
               'Twenty-two angled-handle targets need independent review. Seven oblique inputs clip gripping contact. Appearance tags localize the hand-output/mapping disagreement under the existing references.', '']
    for page in range((len(items)+15)//16):
        path = (output/f'all-failures-{page+1}.png').absolute()
        gallery.append(f'![All failed patches, page {page+1}]({path})\n')
    (output/'all-failures.md').write_text('\n'.join(gallery))
    # Focused examples: a new orientation regression, a clear visual hand error,
    # ambiguous derived appearance targets, and insufficient grip coverage.
    selected = [items[i-1] for i in [44, 57, 6, 59]]
    titles = ['Orientation: head turns; torso faces camera',
              'Hand classifier: back of hand called palm',
              'Angled handle: derived target needs review',
              'Crop: gripping contact partly outside box']
    notes = ['Chest/logo faces camera; head crop loses that evidence.',
             'Rear view is correct. Knuckles/back of hand are visible.',
             'Rear view is correct; hand surface is mixed/oblique.',
             'Wrist-dominant input; source reference is oblique.']
    focused = Image.new('RGB', (1500, 820), 'white'); draw = ImageDraw.Draw(focused)
    for j, (item, heading, note) in enumerate(zip(selected, titles, notes)):
        x, y = (j%2)*750, (j//2)*410
        row, patch, pred = item['row'], item['patch'], item['prediction']
        frame = Image.open(Path(row['framesDirectory'])/row['image']).convert('RGB')
        annotation = ImageDraw.Draw(frame)
        annotation.rectangle(patch['box'], outline='#ff2424', width=2)
        annotation.rectangle(row['context']['box'], outline='#00c46a', width=2)
        draw.text((x+10, y+8), f"#{item['index']} {heading}", font=title, fill=COLORS[item['diagnosis']['category']])
        focused.paste(ImageOps.contain(frame, (360, 210)), (x+10, y+42))
        focused.paste(Image.open(patch['patch']).resize((160, 160), Image.Resampling.NEAREST), (x+388, y+42))
        focused.paste(Image.open(row['context']['image']).resize((160, 160), Image.Resampling.NEAREST), (x+570, y+42))
        draw.text((x+388, y+210), 'Exact hand input', font=small, fill='black')
        draw.text((x+570, y+210), 'Head input', font=small, fill='black')
        a, v = pred['appearanceScores'], pred['orientationScores']
        draw.text((x+10, y+265), f"Palm {a[0]:.3f}; dorsum {a[1]:.3f}. View: front {v[0]:.3f}; rear {v[1]:.3f}.", font=font, fill='black')
        draw.text((x+10, y+290), f"Reference: overhand / {pred['orientationExpected']}. Output: {pred['predicted']} ({max(pred['probabilities']):.3f}).", font=font, fill='black')
        draw.text((x+10, y+320), note, font=font, fill='black')
        draw.text((x+10, y+350), row['caseId'][10:21]+' / '+row['image']+' / '+patch['side'], font=small, fill='black')
        draw.text((x+10, y+375), 'Red: hand box. Green: head box. Enlargements add no detail.', font=small, fill='black')
    focused.save(output/'failure-examples.png')
    coverage = Image.new('RGB', (1120, 5*300), 'white'); draw = ImageDraw.Draw(coverage)
    for j, index in enumerate([6, 12, 58, 59, 64]):
        item = items[index-1]; row, patch = item['row'], item['patch']
        frame = Image.open(Path(row['framesDirectory'])/row['image']).convert('RGB')
        ImageDraw.Draw(frame).rectangle(patch['box'], outline='red', width=1)
        b = patch['box']; cx, cy = (b[0]+b[2])/2, (b[1]+b[3])/2; side = max(100, (b[2]-b[0])*3)
        box = [round(cx-side/2), round(cy-side/2), round(cx+side/2), round(cy+side/2)]
        coverage.paste(frame.crop(box).resize((280, 280), Image.Resampling.NEAREST), (10, j*300+20))
        coverage.paste(Image.open(patch['patch']).resize((256, 256), Image.Resampling.NEAREST), (310, j*300+20))
        draw.text((600, j*300+30), str(index)+' '+row['caseId'], font=font, fill='black')
        draw.text((600, j*300+65), row['image']+' '+patch['side']+' '+str(b), font=font, fill='black')
    coverage.save(output/'crop-coverage-review.png')
    return [item['index'] for item in representatives]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('output', type=Path)
    p.add_argument('--review', type=Path)
    args = p.parse_args(); args.output.mkdir(parents=True, exist_ok=True)
    evaluation = ROOT/'build/grip-supervised/head-evaluation'
    predictions_path = evaluation/'predictions.jsonl'
    report = json.loads((evaluation/'report.json').read_text())
    model_path = ROOT/'build/grip-supervised/head-model/GripContextClassifier.pt'
    assert sha(model_path) == report['modelSHA256']
    old_path = ROOT/'build/grip-failure-audit/model-components.json'
    old = json.loads(old_path.read_text())
    assert old['modelSHA256'] == report['baselineSHA256']
    previous = {r['patchSHA256']: r for r in old['records']}
    predictions = [json.loads(l) for l in predictions_path.read_text().splitlines()]
    manifest_rows = {}
    for entry in report['manifests']:
        if entry['cohort'] == 'fitting':
            continue
        assert sha(entry['path']) == entry['sha256']
        for line in Path(entry['path']).read_text().splitlines():
            r = json.loads(line)
            key = (r['recordingId'], r['imageSHA256'])
            assert key not in manifest_rows
            manifest_rows[key] = r
    items = []; summary = Counter(); by_source = defaultdict(Counter); baseline_cancellation = Counter()
    for pred in predictions:
        if pred['cohort'] not in ['older', 'additional'] or pred['expected'] != 'overhand' or not pred.get('patch'):
            continue
        summary['overhandPatches'] += 1
        by_source[pred['caseId']]['available'] += 1
        prior = previous[pred['patchSHA256']]
        view = pred['orientationExpected']
        if view in ['front', 'rear']:
            target = 0 if view == 'front' else 1
            old_hand_wrong = int(np.argmax(prior['appearanceScores'])) != target
            old_direction = 'front' if prior['orientation'][0] > prior['orientation'][1] else 'rear'
            if old_hand_wrong and old_direction != view and int(np.argmax(pred['baselineScores'])) == 0:
                baseline_cancellation[view] += 1
        if pred['argmax'] == 'overhand':
            continue
        row = manifest_rows[pred['recordingId'], pred['imageSHA256']]
        patch = next(p for p in row['patches'] if p['side'] == pred['side'])
        assert patch['patchSHA256'] == pred['patchSHA256']
        assert row['pose']['timestamp'] == pred['timestamp']
        verify_crop(row, patch)
        diagnosis = attribution(pred)
        category = diagnosis['category']
        decision = 'acceptedWrong' if pred['predicted'] == 'underhand' else 'unknown'
        summary[category] += 1; summary[decision] += 1
        summary[category+':'+decision] += 1
        by_source[pred['caseId']][category] += 1; by_source[pred['caseId']][decision] += 1
        regression = int(np.argmax(pred['baselineScores'])) == 0
        summary['newRegression' if regression else 'alreadyWrong'] += 1
        item = dict(index=len(items)+1, row=row, patch=patch, prediction=pred, diagnosis=diagnosis,
                    newRegression=regression, handCropPixelExact=True, headCropPixelExact=True)
        items.append(item)
    assert len(items) == 64 and summary['overhandPatches'] == 215
    review_counts = Counter()
    if args.review:
        review = json.loads(args.review.read_text())
        assert review['modelSHA256'] == report['modelSHA256']
        assert review['predictionsSHA256'] == sha(predictions_path)
        assert len(review['patches']) == len(items)
        for item, checked in zip(items, review['patches']):
            assert item['index'] == checked['index']
            for field in ['caseId', 'recordingId', 'imageSHA256']:
                assert item['row'][field] == checked[field]
            for field in ['side', 'patchSHA256', 'box']:
                assert item['patch'][field] == checked[field]
            assert item['row']['pose']['timestamp'] == checked['timestamp']
            assert item['diagnosis']['category'] == checked['componentAttribution']
            item['visualReview'] = checked
            review_counts[checked['reviewStatus']] += 1
    torch.set_num_threads(2)
    model = torch.jit.load(str(model_path)).eval()
    maximum = 0.; decision_mismatches = 0; raw_mismatches = 0
    with torch.inference_mode():
        for start in range(0, len(items), 16):
            batch = items[start:start+16]
            inputs = [learning.base.images((it['row'], it['patch'])) for it in batch]
            h, c, f = [torch.stack([x[j] for x in inputs]) for j in range(3)]
            score, view = model(h, c, f)
            appearance = model.appearance(h).softmax(1)
            for i, item in enumerate(batch):
                pred = item['prediction']
                decision_mismatches += (float(score[i].max()) >= .8) != (pred['predicted'] != 'unknown')
                raw_mismatches += int(score[i].argmax()) != int(np.argmax(pred['probabilities']))
                for actual, name in [(score[i], 'probabilities'), (view[i], 'orientationScores'), (appearance[i], 'appearanceScores')]:
                    gap = float((actual-torch.tensor(pred[name])).abs().max()); maximum = max(maximum, gap)
                    assert gap < 1e-4, (item['index'], name, gap)
    assert decision_mismatches == 0 and raw_mismatches == 0
    representatives = render(items, args.output)
    (args.output/'cases.json').write_text(json.dumps(items, indent=2)+'\n')
    receipt = dict(summary=summary, sources=by_source, failedPatches=len(items),
                   failedUniqueFrames=len({(i['row']['recordingId'], i['row']['imageSHA256']) for i in items}),
                   baselineCorrectWithCancellingComponentErrors=baseline_cancellation,
                   allHandAndHeadCropsPixelExact=True, frozenModelReplayMaxDifference=maximum,
                   replayDecisionMismatches=decision_mismatches, replayRawChoiceMismatches=raw_mismatches,
                   representativeIndices=representatives, modelSHA256=sha(model_path),
                   predictionsSHA256=sha(predictions_path), oldComponentAuditSHA256=sha(old_path),
                   reviewSHA256=sha(args.review) if args.review else None, visualReviewCounts=review_counts,
                   scriptSHA256=sha(__file__),
                   qualification='Read-only diagnostic; labels remain provisional source reviews. Counterfactuals do not change benchmark accuracy.')
    (args.output/'receipt.json').write_text(json.dumps(receipt, indent=2)+'\n')
    print(json.dumps(receipt, indent=2))


if __name__ == '__main__':
    main()
