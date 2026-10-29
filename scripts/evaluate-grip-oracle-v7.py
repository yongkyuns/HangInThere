#!/usr/bin/env python3
"""Privileged crop/orientation diagnosis on previously exposed recordings.

Manual boxes are diagnostic inputs, never automatic localization results. This
does not fit any model or change a threshold. All selected source slots survive.
"""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
os.environ.setdefault('OPENBLAS_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path

GRIPS = ['overhand', 'underhand', 'neutral']
VIEWS = ['front', 'rear', 'oblique']


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def crop_box(bounds, size, padding):
    """Square around the actual annotated hand, without inventing border pixels."""
    x0, y0, x1, y1 = bounds
    width, height = size
    assert 0 <= x0 < x1 <= width and 0 <= y0 < y1 <= height
    edge = min(math.ceil(max(x1-x0, y1-y0) * padding), width, height)
    x = min(max(math.floor((x0+x1-edge)/2), 0), width-edge)
    y = min(max(math.floor((y0+y1-edge)/2), 0), height-edge)
    assert x <= x0 and x1 <= x+edge and y <= y0 and y1 <= y+edge
    return [x, y, x+edge, y+edge]


def decision(grip, contact, context_ready):
    if grip is None or not context_ready:
        return dict(raw='unknown', accepted='unknown')
    best = max(range(3), key=lambda i: grip[i])
    raw = GRIPS[best]
    accepted = raw if grip[best] >= .8 and contact >= .8 else 'unknown'
    return dict(raw=raw, accepted=accepted)


def measure(records, mode):
    result = Counter()
    per_class = defaultdict(Counter)
    for record in records:
        target = record['gripReference']
        prediction = record['modes'][mode]
        # Unreadable/source-clipped hands stay in counts, never counted correct
        # merely because a random non-hand crop emits the source activity label.
        valid = record['handReference']['status'] == 'complete'
        tally = Counter(expectedSlots=1, completeSourceHands=int(valid))
        tally['rawCorrect' if valid and prediction['raw'] == target else
              'rawUnknown' if prediction['raw'] == 'unknown' else 'rawWrongOrInvalid'] += 1
        tally['acceptedCorrect' if valid and prediction['accepted'] == target else
              'unknown' if prediction['accepted'] == 'unknown' else 'acceptedWrongOrInvalid'] += 1
        result.update(tally)
        per_class[target].update(tally)
    return dict(total=dict(result), byClass={k: dict(v) for k, v in per_class.items()})


def prepare(root, refs):
    from PIL import Image, ImageDraw, ImageOps
    rows = [json.loads(x) for x in (root/'selected.jsonl').read_text().splitlines()]
    selected_hash = sha(root/'selected.jsonl')
    assert refs['selectedSHA256'] == selected_hash
    out = root/'prepared.jsonl'
    assert not out.exists()
    cropdir = root/'manual-crops'
    cropdir.mkdir(exist_ok=False)
    records = []
    for row in rows:
        ref = refs['frames'][row['diagnosticId']]
        source = Path(row['framesDirectory']) / row['image']
        assert sha(source) == row['imageSHA256'] == ref['imageSHA256']
        rgb = Image.open(source).convert('RGB')
        for slot, hand in ref['hands'].items():
            rec = dict(id=row['diagnosticId']+'-'+slot, frameId=row['diagnosticId'],
                       caseId=row['caseId'], recordingId=row['recordingId'],
                       sourceGroup=row['sourceGroup'], image=str(source),
                       imageSHA256=sha(source), timestamp=row['pose']['timestamp'],
                       gripReference=ref['grip'], viewReference=ref['view'],
                       handReference=hand, automatic=None, manual={})
            auto = next((p for p in row['patches'] if p['side'] == hand['automaticSide']), None)
            if auto and auto['status'] == 'patchReady':
                assert sha(auto['patch']) == auto['patchSHA256']
                rec['automatic'] = dict(image=auto['patch'], sha256=auto['patchSHA256'],
                                        box=auto['box'], correctHand=hand['automaticCorrectHand'])
            if hand['status'] == 'complete':
                for scale in [1.6, 2.0]:
                    box = crop_box(hand['bounds'], rgb.size, scale)
                    image = cropdir/f"{rec['id']}-{scale}.png"
                    rgb.crop(box).resize((128,128), Image.Resampling.BILINEAR).save(image)
                    rec['manual'][str(scale)] = dict(image=str(image.resolve()), sha256=sha(image), box=box)
            records.append(rec)
    out.write_text(''.join(json.dumps(x)+'\n' for x in records))
    for page in range(math.ceil(len(records)/8)):
        canvas = Image.new('RGB', (1280, 1120), 'white')
        draw = ImageDraw.Draw(canvas)
        for i, rec in enumerate(records[page*8:(page+1)*8]):
            x=(i%2)*640; y=(i//2)*280
            draw.text((x+5,y+5),rec['id']+' | '+rec['handReference']['status'],fill='black')
            draw.text((x+5,y+22),'Automatic | manual 1.6 | manual 2.0',fill='black')
            for j, patch in enumerate([rec['automatic'], rec['manual'].get('1.6'), rec['manual'].get('2.0')]):
                if patch:
                    im=Image.open(patch['image']).convert('RGB').resize((192,192))
                    canvas.paste(im,(x+5+j*210,y+45))
                else:
                    draw.text((x+5+j*210,y+80),'Unavailable',fill='black')
        canvas.save(root/f'crop-review-{page+1}.png')
    print(json.dumps(dict(slots=len(records), manualComplete=sum(bool(r['manual']) for r in records))))


def evaluate(root, refs):
    import torch
    from PIL import Image
    from torchvision.transforms import ToTensor
    torch.set_num_threads(2)
    torch.set_num_interop_threads(2)
    freeze = json.loads((root/'inference-freeze.json').read_text())
    assert all(sha(p) == digest for p,digest in freeze['sha256'].items())
    models = {k: torch.jit.load(p).eval() for k,p in freeze['models'].items()}
    records = [json.loads(x) for x in (root/'prepared.jsonl').read_text().splitlines()]
    rows = {r['diagnosticId']: r for r in
            (json.loads(x) for x in (root/'selected.jsonl').read_text().splitlines())}
    tensor = ToTensor()
    zero = torch.zeros(1,3,128,128)
    out = root/'predictions.jsonl'
    assert not out.exists()
    orientation = {}
    with torch.inference_mode():
        for frame_id, row in rows.items():
            context = row['context']
            ready = context['status'] == 'ready'
            if ready:
                assert sha(context['image']) == context['sha256']
            body = tensor(Image.open(context['image']).convert('RGB')).unsqueeze(0) if ready else zero
            # Torso path from v5: no face absence vote and no head-turn reversal.
            _, view = models['torso'](zero, body, torch.tensor([[0.,0.,0.,0.,0.,float(ready)]]))
            orientation[frame_id] = dict(ready=ready, view=view[0].tolist())
        for rec in records:
            reference = refs['frames'][rec['frameId']]
            assert rec['handReference'] == reference['hands'][rec['id'].split('-')[-1]]
            assert sha(rec['image']) == rec['imageSHA256'] == reference['imageSHA256']
            rec['strictViewVerified'] = reference['strictViewVerified']
            automatic_view = orientation[rec['frameId']]
            manual_view = [float(v == rec['viewReference']) for v in VIEWS]
            rec['automaticOrientation'] = automatic_view
            rec['modes'] = {}
            # Fourth cell (automatic pixels + verified view) makes a full
            # factorial: crop and view interactions cannot be confused.
            for kind, patch in [('automatic',rec['automatic']),('manual1.6',rec['manual'].get('1.6')),
                                ('manual2.0',rec['manual'].get('2.0'))]:
                for view_kind, view, ready in [('automaticView', automatic_view['view'], automatic_view['ready']),
                                              ('verifiedView',manual_view,rec['viewReference'] in VIEWS)]:
                    key=kind+':'+view_kind
                    value=dict(decision(None,None,False), inputAvailable=patch is not None)
                    if patch:
                        assert sha(patch['image']) == patch['sha256']
                        hand=tensor(Image.open(patch['image']).convert('RGB')).unsqueeze(0)
                        grip,surface,contact=models['grip'](hand,torch.tensor([view]))
                        value.update(decision(grip[0].tolist(),float(contact[0,1]),ready),
                                     grip=grip[0].tolist(),surface=surface[0].tolist(),contact=float(contact[0,1]))
                        # Deliberately distinct from the staged model's contract;
                        # quality head is reported only as a component diagnostic.
                        rgb=Image.open(rec['image']).convert('RGB')
                        box=crop_box(patch['box'],rgb.size,1.6)
                        wide=tensor(rgb.crop(box).resize((192,192),Image.Resampling.BILINEAR)).unsqueeze(0)
                        qs,qv,qc=models['quality'](hand,wide)
                        value['qualityDiagnostic']=dict(surface=qs[0].tolist(),visibility=float(qv[0,1]),contact=float(qc[0,1]))
                    # A forearm/background crop is invalid for this hand slot,
                    # even if its unconstrained argmax matches the video label.
                    if kind == 'automatic' and patch and not patch['correctHand']:
                        value['unvalidatedRaw']=value['raw']; value['unvalidatedAccepted']=value['accepted']
                        value['raw']='invalid';value['accepted']='invalid' if value['accepted']!='unknown' else 'unknown'
                    rec['modes'][key]=value
    out.write_text(''.join(json.dumps(x)+'\n' for x in records))
    keys=list(records[0]['modes'])
    complete=[r for r in records if r['handReference']['status']=='complete']
    paired=[r for r in complete if r['automatic'] and r['automatic']['correctHand']]
    strict=[r for r in complete if r['strictViewVerified']]
    strict_paired=[r for r in paired if r['strictViewVerified']]
    # Frame/video correlation is explicit; these are not independent hand trials.
    clip_results={}
    for key in keys:
        grouped=defaultdict(list)
        for rec in records: grouped[rec['recordingId']].append(rec)
        clips=[]
        for recording, rs in grouped.items():
            frames=defaultdict(list)
            for rec in rs:
                value=rec['modes'][key]
                if value['raw'] not in ['unknown','invalid']: frames[rec['frameId']].append(value['grip'])
            if frames:
                # Two slots per frame. Average once per frame then once per clip.
                means=[[sum(v[i] for v in values)/len(values) for i in range(3)] for values in frames.values()]
                mean=[sum(v[i] for v in means)/len(means) for i in range(3)]
                pred=GRIPS[max(range(3),key=lambda i:mean[i])]
            else: pred='unknown'
            clips.append(dict(caseId=rs[0]['caseId'],gripReference=rs[0]['gripReference'],raw=pred,frames=len(frames)))
        clip_results[key]=clips
    report=dict(qualification=freeze['qualification'], selectedVideos=len({r['recordingId'] for r in records}),
                sourceFrames=len(rows), expectedSlots=len(records), completeHandSlots=len(complete),
                sameCorrectAutomaticHandSlots=len(paired),thresholds=freeze['thresholds'],
                summary={k:measure(records,k) for k in keys},
                pairedCorrectAutomaticCrops={k:measure(paired,k) for k in keys},
                strictVerifiedViewCompleteHands={k:measure(strict,k) for k in keys},
                strictVerifiedViewPairedCorrectAutomaticHands={k:measure(strict_paired,k) for k in keys},
                clipRawDiagnostic=clip_results,orientation=orientation,
                hashes={str(p):sha(p) for p in [root/'selected.jsonl',root/'prepared.jsonl',root/'inference-freeze.json',out]})
    (root/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps({k:v['total'] for k,v in report['summary'].items()},indent=2))


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('mode',choices=['prepare','evaluate'])
    p.add_argument('root',type=Path)
    p.add_argument('references',type=Path)
    args=p.parse_args()
    refs=json.loads(args.references.read_text())
    if args.mode=='prepare': prepare(args.root,refs)
    else: evaluate(args.root,refs)


if __name__=='__main__':main()
