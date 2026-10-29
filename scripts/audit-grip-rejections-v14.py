#!/usr/bin/env python3
"""Diagnose fixed-model rejection on exposed development inputs; never change gates.

Bypass/raw-temperature results are diagnostic counterfactuals, not candidates.
Readable subsets use agent reference labels and cannot be deployed as filters.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import random

from PIL import Image, ImageDraw, ImageOps
import torch

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT/'build/grip-rejection-v14'
INPUT = ROOT/'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl'
WEIGHTS = ROOT/'build/grip-anatomy-v11/frozenQuality/weights.pt'
MODEL = ROOT/'build/grip-anatomy-v11/frozenQuality/GripSurfaceClassifier.pt'
HEADS = ['surface', 'readability', 'contact']
spec = importlib.util.spec_from_file_location('surface', ROOT/'scripts/train-grip-front-rear-v9.py')
s = importlib.util.module_from_spec(spec)
spec.loader.exec_module(s)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def write(path, data):
    assert not path.exists(), str(path)
    path.write_text(json.dumps(data, indent=2)+'\n')


def group(item):
    r, _ = item
    return r['annotation'].get('sourceGroup') or r['recordingId']


def clean(item):
    ref = item[1]['threewayReview']
    return ref['surface'] in s.SURFACES and ref['view'] in ['front', 'rear'] and ref['contact'] == 'closed'


def decompose(records):
    patterns = Counter()
    failed = Counter()
    for record in records:
        reject = [name for name in HEADS if not record['passes'][name]]
        patterns['+'.join(reject) if reject else 'accepted'] += 1
        failed.update(reject)
    return dict(crops=len(records), failurePatterns=dict(patterns), failedEachGate=dict(failed))


def accepted_score(records, bypass=None, contact_raw=False):
    selected = [r for r in records if all(
        name == bypass or (r['rawContactConfidence'] >= .8 if name == 'contact' and contact_raw else r['passes'][name])
        for name in HEADS)]
    return dict(accepted=len(selected), correctClosed=sum(r['correctSurface'] and r['contactReference'] == 'closed' for r in selected),
        wrongKnownClosed=sum(r['knownSurface'] and not r['correctSurface'] and r['contactReference'] == 'closed' for r in selected),
        unclearMixedOrNoncontact=sum(not r['knownSurface'] or r['contactReference'] != 'closed' for r in selected),
        ids=[r['id'] for r in selected], sourceGroups=len({r['sourceGroup'] for r in selected}))


def diverse(items, count, seed):
    rng = random.Random(seed)
    items = list(items)
    rng.shuffle(items)
    chosen, seen = [], set()
    for item in items:
        if group(item) not in seen:
            chosen.append(item)
            seen.add(group(item))
            if len(chosen) == count:
                return chosen
    for item in items:
        if item not in chosen:
            chosen.append(item)
            if len(chosen) == count:
                break
    return chosen


def main():
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    OUT.mkdir(exist_ok=False)
    protocol = dict(purpose=__doc__, inputSHA256=sha(INPUT), modelSHA256=sha(MODEL), weightsSHA256=sha(WEIGHTS),
        scriptSHA256=sha(__file__), helperSHA256=sha(ROOT/'scripts/train-grip-front-rear-v9.py'),
        confidenceThresholds={h: .8 for h in HEADS}, fitting=False, appChanged=False,
        analysis='All 218 validation crops, reviewed clear closed front/rear subset, correct vs incorrect anatomy, independent gate patterns and single-gate bypass counterfactuals.',
        sampleReview='Six source-diverse contact-only correct rejections; six multiple-gate correct rejections; all eight existing clear open-hand controls; six training unclear-anatomy/closed-contact examples. Model-dependent error sampling is diagnostic, not independent gold.',
        annotationRule='Inspect contact evidence in both source and patch. Unclear anatomy does NOT automatically imply unclear contact. Preserve every original reference and source role. No new label enters fitting.',
        selectionSeed=4214, finalReserveInspected=False)
    write(OUT/'protocol.json', protocol)
    rows = s.base.load(INPUT)
    pairs = s.base.pairs(rows)
    train = [x for x in pairs if x[0]['annotation']['split'] == 'train']
    val = [x for x in pairs if x[0]['annotation']['split'] == 'validation']
    assert len(train) == 526 and len(val) == 218
    h, _ = s.base.data(val)
    m = s.SurfaceModel(False)
    m.load_state_dict(torch.load(WEIGHTS, weights_only=True))
    m.eval()
    pr = s.infer(m, h, True)
    exported = torch.jit.load(str(MODEL)).eval()
    assert all(torch.equal(a, b) for a, b in zip(pr, s.infer(exported, h, True)))
    raw = s.infer(m, h)
    raw_contact = raw[2].softmax(1)[:, 1]
    records = []
    for i, item in enumerate(val):
        r, p = item
        ref = p['threewayReview']
        predicted = s.SURFACES[int(pr[0][i].argmax())]
        confidence = [float(pr[0][i].max()), float(pr[1][i, 1]), float(pr[2][i, 1])]
        records.append(dict(id=ref['anatomyReviewId'], sourceGroup=group(item), split='validation',
            patch=p['patch'], patchSHA256=p['patchSHA256'], sourceImage=str(Path(r['framesDirectory'])/r['image']),
            sourceImageSHA256=r['imageSHA256'], surfaceReference=ref['surface'], category=ref['anatomyReviewCategory'],
            contactReference=ref['contact'], prediction=predicted, knownSurface=ref['surface'] in s.SURFACES,
            correctSurface=predicted == ref['surface'], clean=clean(item),
            confidence=dict(zip(HEADS, confidence)), passes={n: c >= .8 for n, c in zip(HEADS, confidence)},
            rawContactConfidence=float(raw_contact[i])))
    readable = [r for r in records if r['clean']]
    right = [r for r in readable if r['correctSurface']]
    wrong = [r for r in readable if not r['correctSurface']]
    assert len(readable) == 100 and len(right) == 83 and len(wrong) == 17
    assert accepted_score(records)['accepted'] == 14 and accepted_score(readable)['accepted'] == 12
    stats = {}
    for split, items in [('train', train), ('validation', val)]:
        counts = Counter()
        open_groups, closed_groups = set(), set()
        for item in items:
            r, p = item
            ref = p['threewayReview']
            category = ref.get('anatomyReviewCategory', ref['surface'])
            counts[f"{ref['contact']} / {category} / eligible={ref.get('qualityEligible', True)}"] += 1
            if ref['surface'] in s.SURFACES and ref.get('qualityEligible', True):
                if ref['contact'] == 'open': open_groups.add(group(item))
                if ref['contact'] == 'closed': closed_groups.add(group(item))
        stats[split] = dict(targetInventory=dict(counts), clearOpenSourceGroups=sorted(open_groups),
                            clearClosedSourceGroups=sorted(closed_groups))
    saved = json.loads((ROOT/'build/grip-anatomy-v11/frozenQuality/receipt.json').read_text())
    report = dict(protocolSHA256=sha(OUT/'protocol.json'), temperatures=m.temperature.tolist(),
        cleanCorrect=decompose(right), cleanWrong=decompose(wrong), cleanAll=decompose(readable),
        allUnclearOrMixed=decompose([r for r in records if not r['knownSurface']]),
        counterfactuals={name: dict(allValidation=accepted_score(records, bypass),
            reviewedReadableClosed=accepted_score(readable, bypass))
            for name, bypass in [('current', None)]+[('bypass_'+h, h) for h in HEADS]},
        rawContactTemperatureOne=dict(allValidation=accepted_score(records, contact_raw=True),
            reviewedReadableClosed=accepted_score(readable, contact_raw=True),
            warning='Diagnostic only. Existing calibration selected T=4 over T=1 by lower balanced validation NLL. Confidence inflation is not a fix.'),
        qualityCalibration=saved['heads'], labelInventory=stats,
        primaryFinding='Contact rejects 70/83 correctly classified readable closed hands; it is not the only blocker.',
        finalReserveInspected=False, thresholdsChanged=False, modelChanged=False, appChanged=False)
    assert report['cleanCorrect']['failedEachGate']['contact'] == 70
    write(OUT/'records.json', records)
    write(OUT/'audit.json', report)
    record_by_id = {r['id']: r for r in records}
    val_by_id = {p['threewayReview']['anatomyReviewId']: (r, p) for r, p in val}
    single = [val_by_id[r['id']] for r in right if r['passes']['surface'] and r['passes']['readability'] and not r['passes']['contact']]
    multiple = [val_by_id[r['id']] for r in right if not r['passes']['readability'] and not r['passes']['contact']]
    open_controls = [x for x in pairs if x[1]['threewayReview']['surface'] in s.SURFACES and x[1]['threewayReview']['contact'] == 'open']
    ambiguous_closed = [x for x in train if x[1]['threewayReview']['surface'] == 'unclear' and x[1]['threewayReview']['contact'] == 'closed']
    selections = [('contact only', x) for x in diverse(single, 6, 4214)] + [('multiple quality gates', x) for x in diverse(multiple, 6, 4215)]
    selections += [('clear open control', x) for x in open_controls]
    selections += [('train ambiguous anatomy / closed', x) for x in diverse(ambiguous_closed, 6, 4216)]
    assert len(open_controls) == 8 and len(selections) == 26
    manifest = []
    for start in range(0, len(selections), 6):
        block = selections[start:start+6]
        canvas = Image.new('RGB', (990, len(block)*210+35), '#eeeeee')
        draw = ImageDraw.Draw(canvas)
        draw.text((10, 8), 'V14 diagnostic contact review: source / actual hand patch / inherited reference. No relabeling or fitting.', fill='black')
        for j, (reason, (r, p)) in enumerate(block):
            ref = p['threewayReview']
            top = j*210+35
            source = Path(r['framesDirectory'])/r['image']
            full = ImageOps.contain(Image.open(source).convert('RGB'), (300, 195))
            patch = Image.open(p['patch']).convert('RGB').resize((170, 170))
            canvas.paste(full, (10, top)); canvas.paste(patch, (325, top))
            ident = ref['anatomyReviewId']
            text = [ident+'  '+r['annotation']['split'], reason,
                'inherited contact: '+ref['contact'], 'surface: '+ref['anatomyReviewCategory'], group((r,p))[:72]]
            prediction = record_by_id.get(ident)
            if prediction:
                text += ['surface/readability/contact:', ' / '.join(f"{prediction['confidence'][h]:.3f}" for h in HEADS)]
            draw.multiline_text((515, top+10), '\n'.join(text), fill='black', spacing=9)
            manifest.append(dict(id=ident, reason=reason, split=r['annotation']['split'], sourceGroup=group((r,p)),
                inheritedContact=ref['contact'], anatomyCategory=ref['anatomyReviewCategory'],
                patch=p['patch'], patchSHA256=p['patchSHA256'], sourceImage=str(source), sourceImageSHA256=r['imageSHA256']))
        canvas.save(OUT/f'contact-review-{start//6+1:02}.jpg', quality=93)
    write(OUT/'review-index.json', manifest)
    assert sha(MODEL) == protocol['modelSHA256'] and sha(WEIGHTS) == protocol['weightsSHA256'] and sha(INPUT) == protocol['inputSHA256']
    print(json.dumps(dict(cleanCorrect=report['cleanCorrect'], cleanWrong=report['cleanWrong'],
        counterfactuals=report['counterfactuals'], rawContactTemperatureOne=report['rawContactTemperatureOne'],
        clearOpenGroups={k: v['clearOpenSourceGroups'] for k, v in stats.items()}), indent=2))


if __name__ == '__main__':
    main()
