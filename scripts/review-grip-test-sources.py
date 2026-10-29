#!/usr/bin/env python3
"""Show reserved source frames only after a research model is frozen."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import subprocess
from PIL import Image,ImageDraw,ImageFont,ImageOps

def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('reserve',type=Path);p.add_argument('model',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--model-file',default='GripContextClassifier.pt');a=p.parse_args()
    reserve=json.loads(a.reserve.read_text());receipt=json.loads((a.model/'training-receipt.json').read_text())
    assert receipt['testReserveSHA256']==sha(a.reserve)
    assert receipt['modelSHA256']==sha(a.model/a.model_file)
    assert not (a.output/'sources.json').exists(),'Preserve earlier source review'
    a.output.mkdir(parents=True,exist_ok=True);sources=[];font=ImageFont.load_default(size=16)
    for index,source in enumerate(reserve['sources'],1):
        assert sha(source['path'])==source['sha256']
        unavailable=[]
        for t in [2,5,8]:
            data=subprocess.check_output(['ffmpeg','-v','error','-ss',str(t),'-i',source['path'],'-frames:v','1','-threads','2','-f','image2pipe','-vcodec','png','-'])
            if data:image=Image.open(io.BytesIO(data)).convert('RGB')
            else:
                unavailable.append(t);image=Image.new('RGB',(340,195),'#dddddd')
                ImageDraw.Draw(image).text((12,60),f'No frame at {t}s',font=font,fill='black')
            image.save(a.output/f'C{index:03d}-{t}.png')
        sources.append(dict(source,reviewId=f'C{index:03d}',unavailableReviewTimes=unavailable))
    for page in range((len(sources)+5)//6):
        batch=sources[page*6:(page+1)*6];canvas=Image.new('RGB',(1050,len(batch)*230),'white');draw=ImageDraw.Draw(canvas)
        for j,source in enumerate(batch):
            y=j*230;draw.text((8,y+4),source['reviewId']+' '+source['id'],font=font,fill='black')
            for col,t in enumerate([2,5,8]):
                im=Image.open(a.output/(source['reviewId']+f'-{t}.png'))
                canvas.paste(ImageOps.contain(im,(340,195)),(col*350+5,y+28))
        canvas.save(a.output/f'source-review-{page+1:02d}.png')
    (a.output/'sources.json').write_text(json.dumps(dict(modelSHA256=receipt['modelSHA256'],reserveSHA256=sha(a.reserve),sources=sources),indent=2)+'\n')
    print(json.dumps(dict(sources=len(sources),pages=(len(sources)+5)//6,frozenModelSHA256=receipt['modelSHA256'])))

if __name__=='__main__':main()
