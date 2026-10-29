#!/usr/bin/env python3
"""Audit exposed validation RGB without training, new tests, or reference changes."""
import os
os.environ.setdefault('OMP_NUM_THREADS','2')
os.environ.setdefault('OPENBLAS_NUM_THREADS','2')
import collections,hashlib,importlib.util,json,random
from pathlib import Path
import torch
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-regression-v10'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def main():
    OUT.mkdir(exist_ok=False);torch.set_num_threads(2)
    spec=importlib.util.spec_from_file_location('s',ROOT/'scripts/train-grip-front-rear-v9.py');s=importlib.util.module_from_spec(spec);spec.loader.exec_module(s)
    source=ROOT/'build/grip-front-rear-v9/fitting.jsonl';rows=s.base.load(source)
    pairs=[x for x in s.base.pairs(rows) if x[0]['annotation']['split']=='validation'];h,_=s.base.data(pairs)
    paths=[ROOT/'build/grip-surface-v2/model/GripSurfaceClassifier.pt',ROOT/'build/grip-front-rear-v9/model/GripSurfaceClassifier.pt']
    models=[torch.jit.load(str(p)).eval() for p in paths]
    with torch.inference_mode():probs=[s.infer(m,h,True) for m in models]
    records=[];groups=collections.defaultdict(list)
    for i,(r,p) in enumerate(pairs):
        ref=p['threewayReview'];pred=[]
        for values in probs:
            q=[v[i].tolist() for v in values];pred.append(dict(surface=s.SURFACES[max(range(3),key=lambda j:q[0][j])],surfaceProbabilities=q[0],visible=q[1][1],closed=q[2][1]))
        known=ref['surface'] in s.SURFACES
        category='unclear' if not known else 'regression' if pred[0]['surface']==ref['surface'] and pred[1]['surface']!=ref['surface'] else 'recovered' if pred[0]['surface']!=ref['surface'] and pred[1]['surface']==ref['surface'] else 'bothCorrect' if pred[1]['surface']==ref['surface'] else 'bothWrong'
        rec=dict(id=f'R{i+1:03d}',caseId=r['caseId'],group=r['annotation'].get('sourceGroup') or r['recordingId'],reviewId=p['reviewId'],ref=ref,
            category=category,patch=p['patch'],patchSHA256=p['patchSHA256'],box=p['box'],sourceImage=str(Path(r['framesDirectory'])/r['image']),imageSHA256=r['imageSHA256'],predictions=pred)
        records.append(rec);groups[rec['group']].append(rec)
    summary=dict(qualification=__doc__,sourceSHA256=sha(source),models={str(p):sha(p) for p in paths},
      categories=dict(collections.Counter(x['category'] for x in records)),
      groupResults=[dict(group=g,crops=len(v),categories=dict(collections.Counter(x['category'] for x in v)),references=dict(collections.Counter(x['ref']['surface'] for x in v))) for g,v in sorted(groups.items())])
    (OUT/'predictions.json').write_text(json.dumps(records,indent=2)+'\n');(OUT/'summary.json').write_text(json.dumps(summary,indent=2)+'\n')
    font=ImageFont.load_default(size=17)
    for category in ['regression','bothWrong','recovered']:
        items=[r for r in records if r['category']==category]
        for start in range(0,len(items),6):
            batch=items[start:start+6];im=Image.new('RGB',(1280,240*len(batch)),'white');d=ImageDraw.Draw(im)
            for j,r in enumerate(batch):
                y=j*240;d.text((4,y+3),r['id']+' '+r['caseId'],font=font,fill='black')
                src=Image.open(r['sourceImage']).convert('RGB');draw=ImageDraw.Draw(src);draw.rectangle(r['box'],outline='red',width=max(1,src.width//300));src.thumbnail((580,198));im.paste(src,(4,y+32));im.paste(Image.open(r['patch']).resize((196,196)),(600,y+32))
                d.text((810,y+40),'Stored ref: '+r['ref']['surface']+' / '+r['ref']['view'],font=font,fill='black')
                d.text((810,y+70),'Previous: '+r['predictions'][0]['surface'],font=font,fill='black');d.text((810,y+100),'New: '+r['predictions'][1]['surface'],font=font,fill='black')
                d.text((810,y+130),'Native box '+str(r['box']),font=font,fill='black')
            im.save(OUT/f'{category}-{start//6+1:02d}.png')
    # Independent of predictions, inspect representative crops per source group.
    picked=[]
    for g,v in sorted(groups.items()):
        known=[x for x in v if x['ref']['surface'] in s.SURFACES]
        picked.extend(known[:1]+known[len(known)//2:len(known)//2+1]+known[-1:] if known else v[:1])
    picked=list({x['id']:x for x in picked}.values());random.Random(4010).shuffle(picked)
    for start in range(0,len(picked),48):
        im=Image.new('RGB',(8*150,6*156),'white');d=ImageDraw.Draw(im)
        for i,r in enumerate(picked[start:start+48]):
            x=i%8*150;y=i//8*156;im.paste(Image.open(r['patch']),(x,y+22));d.text((x,y),r['id'],font=font,fill='black')
        im.save(OUT/f'blind-groups-{start//48+1:02d}.png')
    print(json.dumps(summary,indent=2))
if __name__=='__main__':main()
