#!/usr/bin/env python3
"""Post-freeze diagnostics only: oracle-view ablation and saved failure sheets."""
import argparse
from collections import Counter
import copy
import importlib.util
import json
from pathlib import Path
import torch
from PIL import Image,ImageDraw,ImageFont,ImageOps


def module(name,file):
    spec=importlib.util.spec_from_file_location(name,Path(__file__).with_name(file))
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


learning=module('reviewed','train-reviewed-grip.py');prepare=module('crop','prepare-reviewed-grip.py')
base=learning.base


def sheet(items,path,title):
    font=ImageFont.load_default(size=17);small=ImageFont.load_default(size=15)
    canvas=Image.new('RGB',(1200,len(items)*235+65),'white');d=ImageDraw.Draw(canvas)
    d.text((12,8),title,font=font,fill='black')
    d.text((12,35),'Source frame                 Original hand -> corrected hand       Torso input         Frozen model outputs',font=small,fill='black')
    for i,x in enumerate(items):
        r,p=x['row'],x['patch'];y=65+i*235
        frame=Image.open(Path(r['framesDirectory'])/r['image']).convert('RGB')
        marker=ImageDraw.Draw(frame);marker.rectangle(p['box'],outline='red',width=3)
        if r['context']['status']=='ready':marker.rectangle(r['context']['box'],outline='green',width=3)
        canvas.paste(ImageOps.contain(frame,(335,195)),(8,y+22))
        canvas.paste(Image.open(p['originalPatch']['patch']).resize((96,96)),(350,y+28))
        canvas.paste(Image.open(p['patch']).resize((160,160)),(465,y+28))
        if r['context']['status']=='ready':canvas.paste(Image.open(r['context']['image']),(650,y+28))
        t=r['pose']['timestamp'];d.text((8,y+3),f'{t["value"]/t["timescale"]:.2f}s / {p["side"]}',font=font,fill='black')
        for j,line in enumerate(x['lines']):d.text((800,y+25+j*27),line,font=font,fill='black')
    canvas.save(path)


def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('model',type=Path);p.add_argument('manifest',type=Path)
    p.add_argument('predictions',type=Path);p.add_argument('prior_audit',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    assert not (a.output/'report.json').exists();a.output.mkdir(parents=True,exist_ok=True)
    torch.set_num_threads(2);torch.set_num_interop_threads(2)
    model=learning.ReviewedGrip(pretrained=False);model.load_state_dict(torch.load(a.model/'weights.pt',weights_only=True));model.eval()
    receipt=json.loads((a.model/'training-receipt.json').read_text())
    rows=base.load(a.manifest);lookup={(r['recordingId'],r['imageSHA256'],p['side']):(r,p) for r in rows for p in r['patches']}
    predictions=[json.loads(l) for l in a.predictions.read_text().splitlines()]
    failures=[];ablations=[]
    with torch.inference_mode():
        for prediction in predictions:
            if prediction['expected']!='underhand' or not prediction['results']:continue
            row,patch=lookup[(prediction['recordingId'],prediction['imageSHA256'],prediction['side'])]
            h,c,f=[t.unsqueeze(0) for t in base.images((row,patch))]
            view=torch.tensor([[1.,0.,0.]])
            scores=(model.grip(model.grip_features(model.hand.embed(h),view))/model.temperature).softmax(1)[0].tolist()
            new=prediction['results']['new'];ablations.append(dict(imageSHA256=row['imageSHA256'],side=patch['side'],
                expected='underhand',normalScores=new['scores'],orientation=new['view'],oracleFrontScores=scores))
            failures.append(dict(row=row,patch=patch,lines=['Reference: chin-up / front',
                f'Old pull-up: {prediction["results"]["baseline"]["scores"][0]:.1%}',
                f'New pull-up: {new["scores"][0]:.1%}',f'Torso front: {new["view"][0]:.1%}',
                f'Oracle front -> pull-up: {scores[0]:.1%}']))
    sheet(failures,a.output/'chinup-failures.png','All five available chin-up test patches: labels and crop contact visually verified before scoring')
    prior=json.loads(a.prior_audit.read_text());cases=[x for x in prior if 'irFDiPohB8E' in x['row']['caseId']]
    unique={x['row']['imageSHA256']:copy.deepcopy(x['row']) for x in cases}
    for r in unique.values():r['context']=r.get('torsoContext',r['context'])
    revised=prepare.recrop(list(unique.values()),a.output/'head-turn-inputs')
    new_lookup={(r['imageSHA256'],p['side']):(r,p) for r in revised for p in r['patches'] if p['status']=='patchReady'}
    counts=Counter();diagnostics=[];turn_items=[]
    with torch.inference_mode():
        for x in cases:
            key=(x['row']['imageSHA256'],x['patch']['side']);r,patch=new_lookup[key]
            h,c,f=[t.unsqueeze(0) for t in base.images((r,patch))]
            score,view=model(h,c,f)
            absent=f.clone();absent[:,0:5]=0
            visible=f.clone();visible[:,0]=1
            absent_view=model.orientation(c,absent);visible_view=model.orientation(c,visible)
            old=x['prediction']['orientationScores'];v=view[0].tolist();s=score[0].tolist()
            counts['oldFrontCorrect']+=old.index(max(old))==0;counts['newFrontCorrect']+=v.index(max(v))==0
            counts['newGripCorrect']+=s[0]>s[1]
            counts['faceCueDirectionFlips']+=int(absent_view.argmax(1)!=visible_view.argmax(1))
            diagnostics.append(dict(imageSHA256=r['imageSHA256'],side=patch['side'],oldView=old,newView=v,newGrip=s))
            turn_items.append(dict(row=r,patch=patch,lines=['Reference: pull-up / front',f'Old front: {old[0]:.1%}',
                f'New front: {v[0]:.1%}',f'New pull-up: {s[0]:.1%}','Face toggle: no direction flip']))
    sheet([turn_items[i] for i in sorted({0,len(turn_items)//2,len(turn_items)-1})],a.output/'head-turn-diagnostic.png',
          'Previously exposed head-turn failures: diagnostic only; these are not part of fresh test accuracy')
    report=dict(modelSHA256=receipt['modelSHA256'],qualification='Post-freeze diagnostic ablations; oracle view is supplied only for failure attribution, never headline accuracy',
                chinupOracleFront=ablations,headTurnDevelopment=dict(observations=len(cases),uniqueFrames=len(revised),counts=counts,observationsDetail=diagnostics))
    (a.output/'report.json').write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(dict(oracleFrontStillWrong=sum(x['oracleFrontScores'][0]>x['oracleFrontScores'][1] for x in ablations),headTurn=counts)))


if __name__=='__main__':main()
