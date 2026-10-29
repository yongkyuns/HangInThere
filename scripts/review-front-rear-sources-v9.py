#!/usr/bin/env python3
"""Fixed-time pixel review of new validation candidates, no model predictions."""
import hashlib
import io
import json
from pathlib import Path
import subprocess
import sys
from PIL import Image,ImageDraw,ImageOps,ImageFont


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    root=Path(sys.argv[1]);out=root/'source-review';out.mkdir(exist_ok=False)
    intake=json.loads((root/'new-intake.json').read_text())
    reserve=json.loads((root/'untouched-final-reserve.json').read_text())
    assert not {x['sourceGroup'] for x in intake['cases']} & {x['sourceGroup'] for x in reserve['sources']}
    rows=[]
    for i,s in enumerate(sorted(intake['cases'],key=lambda x:x['sha256']),1):
        assert sha(s['path'])==s['sha256']
        row=dict(s,reviewId=f'V{i:03d}',frames=[])
        for t in [2,5,8]:
            data=subprocess.check_output(['ffmpeg','-v','error','-ss',str(t),'-i',s['path'],'-frames:v','1','-threads','2','-f','image2pipe','-vcodec','png','-'])
            if not data:row['frames'].append(dict(requestedSeconds=t,status='pastEnd'));continue
            p=out/f'V{i:03d}-{t}.png';p.write_bytes(data)
            row['frames'].append(dict(requestedSeconds=t,status='ready',image=str(p.resolve()),sha256=sha(p)))
        rows.append(row)
    font=ImageFont.load_default(size=16)
    for page in range((len(rows)+5)//6):
        batch=rows[page*6:(page+1)*6];canvas=Image.new('RGB',(1320,len(batch)*280),'white');d=ImageDraw.Draw(canvas)
        for j,row in enumerate(batch):
            y=j*280;d.text((5,y+3),row['reviewId']+' '+row['id'],font=font,fill='black')
            for col,f in enumerate(row['frames']):
                if f['status']=='ready':
                    im=ImageOps.contain(Image.open(f['image']).convert('RGB'),(430,240));canvas.paste(im,(col*440+5,y+30))
                else:d.text((col*440+5,y+80),'No source frame at '+str(f['requestedSeconds'])+'s',font=font,fill='black')
        canvas.save(out/f'page-{page+1:02d}.png')
    (out/'index.json').write_text(json.dumps(dict(qualification='Source review only; known action label is not verified grip truth. No inference or fitting.',intakeSHA256=sha(root/'new-intake.json'),sources=rows),indent=2)+'\n')
    print(json.dumps(dict(sources=len(rows),pages=(len(rows)+5)//6)))


if __name__=='__main__':main()
