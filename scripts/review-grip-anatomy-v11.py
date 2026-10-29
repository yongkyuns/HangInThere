#!/usr/bin/env python3
"""Prediction-hidden, whole-cohort review preparation. No model scoring or fitting."""
import hashlib,json,random
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-anatomy-v11'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
    OUT.mkdir(exist_ok=False)
    protocol=dict(qualification='Development re-adjudication by Codex, not independent human gold. Prior model errors have been seen; this full-cohort review hides stored labels/predictions and preserves original reference metrics.',
        rubric=dict(palm='Dominant palmar surface with visible thenar/palm crease or finger-pad structure; do not infer from exercise/view',
          dorsum='Dominant back of hand with dorsal knuckle/tendon/back-of-hand structure; do not infer from exercise/view',
          side='Dominant thumb/ulnar hand profile; palm/dorsal surfaces do not dominate',
          mixed='Actual hand is visible, but palm/edge/dorsal mixture has no defensible single dominant class',
          unclear='Blur, clipping, occlusion, exposure or invalid localization prevents image-only anatomy judgment'),
        policy='Apply same rule to all train/validation inputs. Do not remove uncertain rows from end-to-end denominator. Mixed/unclear mask anatomy loss and supervise unambiguous-anatomy visibility=0. Keep original contact/grip/source-view labels unless separate source review establishes error. No threshold changes.',
        trainingScope='All original training views can teach anatomy; app grip classification remains front/rear only',
        nextArms=['baseline-weight anatomy-only adaptation, quality heads frozen','same warm-start, jointly trained anatomy/quality heads'],
        finalReserve='Unread and never selected by model or review outcome')
    (OUT/'review-protocol.json').write_text(json.dumps(protocol,indent=2)+'\n')
    old=ROOT/'build/grip-surface-v2/fitting.jsonl';v9=ROOT/'build/grip-front-rear-v9/fitting.jsonl'
    rows=[json.loads(x) for x in old.read_text().splitlines() if json.loads(x)['annotation']['split']=='train']+[json.loads(x) for x in v9.read_text().splitlines() if json.loads(x)['annotation']['split']=='validation']
    pairs=[(r,p) for r in rows for p in r['patches'] if p['status']=='patchReady'];random.Random(4111).shuffle(pairs)
    index=[];font=ImageFont.load_default(size=16)
    for i,(r,p) in enumerate(pairs):
        assert sha(p['patch'])==p['patchSHA256']
        index.append(dict(id=f'H{i+1:03d}',row=r,patch=p))
    (OUT/'review-index.json').write_text(json.dumps(dict(inputs={str(p):sha(p) for p in [old,v9]},protocolSHA256=sha(OUT/'review-protocol.json'),items=index),indent=2)+'\n')
    for start in range(0,len(index),48):
        canvas=Image.new('RGB',(8*150,6*158),'white');d=ImageDraw.Draw(canvas)
        for i,x in enumerate(index[start:start+48]):
            xx=i%8*150;yy=i//8*158;canvas.paste(Image.open(x['patch']['patch']),(xx,yy+24));d.text((xx,yy),x['id'],font=font,fill='black')
        canvas.save(OUT/f'blind-{start//48+1:02d}.png')
    print(json.dumps(dict(totalPatches=len(index),pages=(len(index)+47)//48)))
if __name__=='__main__':main()
