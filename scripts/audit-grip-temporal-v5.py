#!/usr/bin/env python3
"""Verify frozen inputs and render failure evidence without changing first scores."""
from collections import Counter
import hashlib
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageOps


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    work = root / 'build/grip-temporal-v5'
    frozen = json.loads((work / 'pre-test-freeze.json').read_text())
    assert all(sha(root / p) == digest for p, digest in frozen['sha256'].items())
    reference_path = root / 'Evaluation/fixtures/grip-temporal-test-references-v5.json'
    refs = json.loads(reference_path.read_text())
    reference_freeze = json.loads((work / 'reference-freeze.json').read_text())
    assert reference_freeze['referencesSHA256'] == sha(reference_path)
    assert reference_freeze['modelFreezeSHA256'] == sha(work / 'pre-test-freeze.json')
    rows = [json.loads(l) for l in (work / 'inputs.jsonl').read_text().splitlines()]
    observations = [json.loads(l) for l in (work / 'first-evaluation/observations.jsonl').read_text().splitlines()]
    predictions = {h['reviewId']: (f, h) for f in observations for h in f['hands']}
    patches = {p['contactReviewId']: (r, p) for r in rows for p in r['patches'] if p['status'] == 'patchReady'}
    assert patches.keys() == refs['patches'].keys() == predictions.keys()
    reconstructed = Counter()
    for row in rows:
        source = Path(row['framesDirectory']) / row['image']
        assert sha(source) == row['imageSHA256']
        with Image.open(source) as original:
            image = original.convert('RGB')
            reconstructed['sourceFrames'] += 1
            for p in row['patches']:
                if p['status'] != 'patchReady':
                    continue
                for path, digest, box, size, name in [(p['patch'], p['patchSHA256'], p['box'], 128, 'tightCrops'),
                      (p['contactCrop']['image'], p['contactCrop']['sha256'], p['contactCrop']['box'], 192, 'widerCrops')]:
                    assert sha(path) == digest
                    expected = image.crop(box).resize((size, size), Image.Resampling.BILINEAR)
                    with Image.open(path) as actual:
                        assert ImageChops.difference(expected, actual.convert('RGB')).getbbox() is None
                    reconstructed[name] += 1
    videos = []
    for v in refs['videos']:
        frames = [f for f in observations if f['recordingId'] == v['recordingId']]
        hands = [h for f in frames for h in f['hands']]
        videos.append(dict(reviewId=v['reviewId'], caseId=v['caseId'], referenceGrip=v['grip'], referenceView=v['view'],
            frames=len(frames), trackedFrames=sum(f['trackId'] is not None for f in frames),
            contextFrames=sum(f['contextReady'] for f in frames), tracks=sorted({f['trackId'] for f in frames if f['trackId'] is not None}),
            crops=len(hands), appearanceQualified=sum(max(h['surface']) >= .8 and h['visible'] >= .8 for h in hands),
            maxAutomaticFrontRearEvidence=max((max(f['view'][:2]) for f in frames), default=0),
            maxNeutralEvidenceForAllViews=max((min(h['neutralByView']) for h in hands), default=0)))
    # Build an explicit diagnostic gallery, including the clear original hand
    # with NO model crop. Display details never become manual test inputs.
    chosen = [('T0007', 'Face instead of hand'), ('T0087', 'Readable back of hand; weak torso/contact scores'),
              ('T0041', 'Wrong bystander hand'), ('T0051', 'Gloved hand; anatomy unverifiable'),
              ('T0025', 'Repeated accepted guess, source grip unverifiable')]
    canvas = Image.new('RGB', (1420, 6 * 245), 'white')
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.load_default(size=17)
    for i, (id, explanation) in enumerate(chosen):
        row, patch = patches[id]
        frame, hand = predictions[id]
        image = Image.open(Path(row['framesDirectory']) / row['image']).convert('RGB')
        ImageDraw.Draw(image).rectangle(patch['box'], outline='red', width=3)
        y = i * 245
        draw.text((8, y+3), f'{id}: {explanation}', fill='black', font=font)
        canvas.paste(ImageOps.contain(image, (450, 195)), (8, y+30))
        canvas.paste(Image.open(patch['patch']).resize((190, 190)), (475, y+30))
        canvas.paste(Image.open(patch['contactCrop']['image']).resize((190, 190)), (685, y+30))
        scores = f"surface {[round(x, 2) for x in hand['surface']]}\nvisibility {hand['visible']:.2f}; contact {hand['contact']:.2f}\ntorso {[round(x, 2) for x in frame['view']]}\nreference: {refs['patches'][id]['surface']}"
        draw.multiline_text((905, y+40), scores, fill='black', font=font, spacing=10)
    child = next(v for v in refs['videos'] if v['reviewId'] == 'V15')
    row = [r for r in rows if r['recordingId'] == child['recordingId']][7]
    image = Image.open(Path(row['framesDirectory']) / row['image']).convert('RGB')
    y = 5 * 245
    draw.text((8, y+3), 'V15: Grip visible in source; no model hand crop because body acquisition fails', fill='black', font=font)
    canvas.paste(ImageOps.contain(image, (450, 195)), (8, y+30))
    draw.multiline_text((475, y+45), 'All 9 sampled frames: no tracked athlete\nNo hand patch reaches the classifier\nSource reference: underhand, front\nHuman verification still pending', fill='black', font=font, spacing=12)
    canvas.save(work / 'examples.png')
    audit = dict(freezePinsVerified=len(frozen['sha256']), referenceFreezeVerified=True, reconstructed=dict(reconstructed),
                 videoDiagnostics=videos, identityUnverifiedPatches=[id for id, p in refs['patches'].items() if not p['identityEligible']],
                 annotationMethod=refs['reviewMethod'], humanReviewStatus=refs['humanReviewStatus'],
                 decision='Temporal prototype not adopted: no verified automatic video decisions. Correct the extraction/ownership contract and independently verify labels before additional classifier training.')
    (work / 'audit.json').write_text(json.dumps(audit, indent=2) + '\n')
    print(json.dumps(dict(freezePinsVerified=audit['freezePinsVerified'], reconstructed=audit['reconstructed'], humanReviewStatus=audit['humanReviewStatus'])))


if __name__ == '__main__':
    main()
