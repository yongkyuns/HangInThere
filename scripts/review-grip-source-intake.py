#!/usr/bin/env python3
"""Blind source review for a training candidate pool, never a test reserve."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
from PIL import Image,ImageDraw,ImageFont,ImageOps


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('intake',type=Path);p.add_argument('reserve',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    intake=json.loads(a.intake.read_text());reserve=json.loads(a.reserve.read_text())
    assert intake['role']=='trainingCandidates' and reserve['role']=='test'
    assert not {s['sourceGroup'] for s in intake['cases']} & {s['sourceGroup'] for s in reserve['sources']}
    assert not (a.output/'sources.json').exists();a.output.mkdir(parents=True,exist_ok=True)
    font=ImageFont.load_default(size=16);sources=[]
    for n,s in enumerate(intake['cases'],1):
        assert sha(s['path'])==s['sha256'];missing=[]
        for t in [2,5,8]:
            target=a.output/f'D{n:03d}-{t}.png'
            if not target.exists():
                data=subprocess.check_output(['ffmpeg','-v','error','-ss',str(t),'-i',s['path'],'-frames:v','1','-threads','2','-f','image2pipe','-vcodec','png','-'])
                if data:im=Image.open(io.BytesIO(data)).convert('RGB')
                else:
                    missing.append(t);im=Image.new('RGB',(340,195),'#dddddd');ImageDraw.Draw(im).text((12,60),f'No frame at {t}s',font=font,fill='black')
                im.save(target)
        sources.append(dict(s,reviewId=f'D{n:03d}',unavailableReviewTimes=missing))
        if n%30==0:print(f'{n} source triplets prepared',flush=True)
    for page in range((len(sources)+5)//6):
        batch=sources[page*6:(page+1)*6];im=Image.new('RGB',(1050,len(batch)*230),'white');d=ImageDraw.Draw(im)
        for j,s in enumerate(batch):
            y=j*230;d.text((8,y+4),s['reviewId']+' '+s['id'],font=font,fill='black')
            for col,t in enumerate([2,5,8]):im.paste(ImageOps.contain(Image.open(a.output/f'{s["reviewId"]}-{t}.png'),(340,195)),(col*350+5,y+28))
        im.save(a.output/f'source-review-{page+1:02d}.png')
    (a.output/'sources.json').write_text(json.dumps(dict(intakeSHA256=sha(a.intake),reserveSHA256=sha(a.reserve),sources=sources),indent=2)+'\n')
    print(json.dumps(dict(sources=len(sources),pages=(len(sources)+5)//6)))


if __name__=='__main__':main()
