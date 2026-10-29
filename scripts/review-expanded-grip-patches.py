#!/usr/bin/env python3
"""Recrop fixed frames and show every source frame/automatic hand for review."""
import argparse
from collections import defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageOps

spec=importlib.util.spec_from_file_location('crops',Path(__file__).with_name('prepare-reviewed-grip.py'))
crops=importlib.util.module_from_spec(spec);spec.loader.exec_module(crops)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('input',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not (a.output/'rows.jsonl').exists();a.output.mkdir(parents=True,exist_ok=True)
    rows=crops.recrop(crops.base.load(a.input),a.output/'images');groups=defaultdict(list);patches=[];frames=[]
    for r in rows:
        r['frameReviewId']=f'F{len(frames)+1:04d}'
        frames.append(dict(frameReviewId=r['frameReviewId'],caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],split=r['annotation']['split'],personIndex=r['context'].get('personIndex')))
        for patch in r['patches']:
            if patch['status']!='patchReady':continue
            patch['reviewId']=f'E{len(patches)+1:04d}'
            patches.append(dict(reviewId=patch['reviewId'],frameReviewId=r['frameReviewId'],caseId=r['caseId'],recordingId=r['recordingId'],imageSHA256=r['imageSHA256'],timestamp=r['pose']['timestamp'],split=r['annotation']['split'],side=patch['side'],box=patch['box'],patchSHA256=patch['patchSHA256']))
        groups[r['caseId']].append(r)
    sources=list(groups.items());font=ImageFont.load_default(size=16);small=ImageFont.load_default(size=13);pages=[]
    for page in range((len(sources)+3)//4):
        batch=sources[page*4:(page+1)*4];im=Image.new('RGB',(1040,len(batch)*325+30),'white');d=ImageDraw.Draw(im)
        d.text((8,6),'All fixed frames / corrected RGB crops / model scores and proposed grip/view labels hidden',font=font,fill='black')
        for j,(case,source_rows) in enumerate(batch):
            y=30+j*325;d.text((8,y),case+' / '+source_rows[0]['annotation']['split'],font=font,fill='black')
            for i,r in enumerate(source_rows):
                x=i*260;frame=Image.open(Path(r['framesDirectory'])/r['image']).convert('RGB');mark=ImageDraw.Draw(frame)
                if r['context']['status']=='ready':mark.rectangle(r['context']['box'],outline='green',width=3)
                for patch in r['patches']:
                    if patch['status']=='patchReady':mark.rectangle(patch['box'],outline='red',width=3)
                t=r['pose']['timestamp'];d.text((x+4,y+22),r['frameReviewId']+f' / {t["value"]/t["timescale"]:.2f}s',font=small,fill='black')
                im.paste(ImageOps.contain(frame,(248,140)),(x+4,y+40))
                for k,side in enumerate(['left','right']):
                    patch=next(p for p in r['patches'] if p['side']==side);px=x+4+k*128
                    if patch['status']=='patchReady':
                        d.text((px,y+184),patch['reviewId']+' '+side,font=small,fill='black')
                        im.paste(Image.open(patch['patch']).resize((112,112)),(px,y+202))
                    else:d.text((px,y+184),side+'\n'+patch['status'][:18],font=small,fill='black')
        name=f'review-{page+1:02d}.png';im.save(a.output/name);pages.append(dict(image=name,sources=[x[0] for x in batch]))
    (a.output/'rows.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in rows))
    (a.output/'review-candidates.json').write_text(json.dumps(dict(frames=frames,patches=patches,pages=pages,inputSHA256=hashlib.sha256(a.input.read_bytes()).hexdigest()),indent=2)+'\n')
    print(json.dumps(dict(frames=len(rows),patches=len(patches),pages=len(pages))))


if __name__=='__main__':main()
