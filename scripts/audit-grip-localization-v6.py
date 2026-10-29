#!/usr/bin/env python3
"""Verify source-bound crops and unchanged freezes; expose successes and failures."""
import hashlib
import importlib.util
import json
from pathlib import Path

from PIL import Image, ImageChops, ImageDraw, ImageFont, ImageOps


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    work = root/'build/grip-localization-v6'
    checked = {}
    for name in ['grip-surface-v2','grip-quality-v3','grip-contact-v4','grip-temporal-v5','grip-localization-v6']:
        freeze = json.loads((root/'build'/name/'pre-test-freeze.json').read_text())
        assert all(sha(root/p) == h for p,h in freeze['sha256'].items())
        checked[name] = len(freeze['sha256'])
    assert all(sha(root/p) == h for p,h in json.loads((work/'source-reference-freeze.json').read_text()).items())
    spec = importlib.util.spec_from_file_location('v6_audit_policy', root/'scripts/grip-localization-v6.py')
    policy = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(policy)
    counts = dict(candidateFrames=0, candidateCrops=0, reviewedCrops=0)
    for name in ['regression-final','fresh-candidate']:
        rows = [json.loads(l) for l in (work/name/'rows.jsonl').read_text().splitlines()]
        for row in rows:
            source = Path(row['framesDirectory'])/row['image']
            assert sha(source) == row['imageSHA256']
            assert 1 <= row['detectorCalls'] <= 4
            image = Image.open(source).convert('RGB')
            counts['candidateFrames'] += 1
            for p in row['patches']:
                assert p['origin'] == 'detectedHandOnly' and len(p['points']) == 21
                assert sha(p['patch']) == p['patchSHA256']
                expected = image.crop(p['box']).resize((128,128), Image.Resampling.BILINEAR)
                assert ImageChops.difference(expected, Image.open(p['patch']).convert('RGB')).getbbox() is None
                assert all(p['box'][0] <= x <= p['box'][2] and p['box'][1] <= y <= p['box'][3] for x,y in p['points'])
                counts['candidateCrops'] += 1
        actual = [json.loads(l) for l in (work/name/'selected.jsonl').read_text().splitlines()]
        assert actual == policy.select_clearer(rows)
    for name in ['regression','fresh']:
        index = json.loads((work/(name+'-crop-review/index.json')).read_text())
        labels = json.loads((work/(name+'-crop-codes.json')).read_text())
        assert labels['indexSHA256'] == sha(work/(name+'-crop-review/index.json'))
        assert all(sha(p)==h for p,h in labels['reviewedPageSHA256'].items())
        for p in index['patches']:
            assert sha(p['source']) == p['imageSHA256'] and sha(p['patch']) == p['patchSHA256']
            image = Image.open(p['source']).convert('RGB')
            expected = image.crop(p['box']).resize((128,128),Image.Resampling.BILINEAR)
            assert ImageChops.difference(expected,Image.open(p['patch']).convert('RGB')).getbbox() is None
            counts['reviewedCrops'] += 1
    # These are diagnostic examples only; they do not expand the frozen score set.
    reg_index = json.loads((work/'regression-crop-review/index.json').read_text())
    fresh_index = json.loads((work/'fresh-crop-review/index.json').read_text())
    reg_good = next(p['reviewId'] for p in reg_index['patches'] if p['method']=='candidate' and p['frameId']=='R004')
    fresh_good = next(p['reviewId'] for p in fresh_index['patches'] if p['method']=='candidate' and p['frameId']=='F001')
    chosen = [(reg_index,'P001','Old forearm crop; new extractor emits no hand for this frame'),
              (reg_index,reg_good,'New detected hand retains the whole visible grip'),
              (reg_index,'P046','New SDK false positive: bracelet/forearm mistaken for a hand'),
              (fresh_index,'P008','Two active athletes: baseline assigns a hand; new policy abstains'),
              (fresh_index,fresh_good,'Fresh complete target hand; most fresh hands remain undetected')]
    canvas = Image.new('RGB',(1350,250*len(chosen)),'white')
    draw = ImageDraw.Draw(canvas)
    font = ImageFont.load_default(size=18)
    for i,(index,id,reason) in enumerate(chosen):
        p=next(p for p in index['patches'] if p['reviewId']==id)
        image=Image.open(p['source']).convert('RGB')
        ImageDraw.Draw(image).rectangle(p['box'],outline='red',width=3)
        y=i*250
        draw.text((5,y+5),reason,fill='black',font=font)
        canvas.paste(ImageOps.contain(image,(700,205)),(5,y+35))
        canvas.paste(Image.open(p['patch']).resize((205,205)),(750,y+35))
        draw.text((990,y+50),p['frameId']+' / '+p['method'],fill='black',font=font)
    canvas.save(work/'examples.png')
    result=dict(freezePinsVerified=checked, originalSourceReferenceFreezeVerified=True, reconstruction=counts,
        detectorBudgetVerified=True, selectedCropsExactlyRecomputed=True,
        qualification='Structural landmark containment is not proof of hand correctness. Pixel review catches a remaining SDK forearm false positive. No model/classifier/app qualification.')
    (work/'audit.json').write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result))


if __name__=='__main__':
    main()
