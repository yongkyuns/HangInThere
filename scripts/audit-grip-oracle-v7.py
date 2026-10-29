#!/usr/bin/env python3
"""Audit retained pixels, frozen artifacts and paired diagnostic accounting."""
import importlib.util
import json
from collections import Counter
from pathlib import Path
import sys
from PIL import Image, ImageDraw, ImageOps
import numpy as np

spec=importlib.util.spec_from_file_location('oracle_audit',Path(__file__).with_name('evaluate-grip-oracle-v7.py'))
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


def main():
    root=Path(sys.argv[1]);refs=json.loads(Path(sys.argv[2]).read_text())
    freeze=json.loads((root/'inference-freeze.json').read_text())
    assert all(m.sha(p)==v for p,v in freeze['sha256'].items())
    old={}
    for version,name in [(2,'surface'),(3,'quality'),(4,'contact'),(5,'temporal'),(6,'localization')]:
        file=Path(f'build/grip-{name}-v{version}/pre-test-freeze.json')
        previous=json.loads(file.read_text())
        assert all(m.sha(p)==v for p,v in previous['sha256'].items()),str(file)
        old[str(version)]=dict(path=str(file),sha256=m.sha(file),pins=len(previous['sha256']))
    rows=[json.loads(x) for x in (root/'predictions.jsonl').read_text().splitlines()]
    selected=[json.loads(x) for x in (root/'selected.jsonl').read_text().splitlines()]
    sources=json.loads(Path('Evaluation/fixtures/grip-threeway-test-source-labels-v1.json').read_text())['sources']
    videos={r['recordingId'] for r in rows}
    verified=[]
    for s in sources:
        if s['sha256'] in videos:
            assert m.sha(s['path'])==s['sha256']
            verified.append(dict(path=s['path'],sha256=s['sha256'],sourceGroup=s['sourceGroup']))
    assert len(verified)==9 and len(selected)==18 and len(rows)==36
    receipt=json.loads(Path('build/grip-threeway/staged-models/threeway/training-receipt.json').read_text())
    assert not videos & {h for values in receipt['sources'].values() for h in values}
    assert not {r['sourceGroup'] for r in rows} & {g for values in receipt['sourceGroups'].values() for g in values}
    crops=0
    for r in rows:
        assert m.sha(r['image'])==r['imageSHA256']
        rgb=Image.open(r['image']).convert('RGB')
        reference=refs['frames'][r['frameId']]['hands'][r['id'].split('-')[-1]]
        assert reference==r['handReference']
        for scale,p in r['manual'].items():
            assert reference['status']=='complete'
            assert p['box']==m.crop_box(reference['bounds'],rgb.size,float(scale))
            expected=np.asarray(rgb.crop(p['box']).resize((128,128),Image.Resampling.BILINEAR))
            assert np.array_equal(expected,np.asarray(Image.open(p['image']).convert('RGB')))
            assert m.sha(p['image'])==p['sha256'];crops+=1
    report=json.loads((root/'report.json').read_text())
    for p,v in report['hashes'].items():assert m.sha(p)==v
    for key,value in report['summary'].items():assert value==m.measure(rows,key)
    # Stronger anatomical classifier is diagnostic only, not substituted into
    # the frozen staged grip head or credited as a newly qualified system.
    anatomy={};failures=[];surface_labels=['palm','dorsum','side','unclear']
    for kind in ['automatic','manual1.6','manual2.0']:
        counts=Counter()
        for r in rows:
            value=r['modes'][kind+':verifiedView'];ref=r['handReference']
            if ref['status']!='complete' or ref['surface']=='unclear' or not value['inputAvailable']:continue
            if kind=='automatic' and not r['automatic']['correctHand']:continue
            counts['readableCrops']+=1
            counts['stagedSurfaceCorrect']+=surface_labels[max(range(4),key=lambda i:value['surface'][i])]==ref['surface']
            q=value['qualityDiagnostic']
            counts['v4SurfaceCorrect']+=surface_labels[max(range(3),key=lambda i:q['surface'][i])]==ref['surface']
        anatomy[kind]=dict(counts)
    for r in rows:
        value=r['modes']['manual1.6:verifiedView']
        if r['handReference']['status']=='complete' and value['raw']!=r['gripReference']:failures.append(r['id'])
    lookup={r['id']:r for r in rows}
    gallery_ids=['D05-h2','D07-h2','D08-h2','D09-h1','D13-h1']
    canvas=Image.new('RGB',(1280,330*len(gallery_ids)),'white');d=ImageDraw.Draw(canvas)
    row_lookup={r['diagnosticId']:r for r in selected}
    for i,ident in enumerate(gallery_ids):
        r=lookup[ident];y=i*330
        auto=r['modes']['automatic:automaticView'];oracle=r['modes']['manual1.6:verifiedView']
        d.text((10,y+5),f"{ident} | reference {r['gripReference']} / {r['viewReference']} / {r['handReference']['surface']}",fill='black')
        d.text((10,y+24),'Original source (manual hand box outlined) | automatic crop | corrected crop | automatic torso context',fill='black')
        source=Image.open(r['image']).convert('RGB');sd=ImageDraw.Draw(source)
        if r['manual']:sd.rectangle(r['manual']['1.6']['box'],outline='red',width=max(1,source.width//300))
        source.thumbnail((380,230));canvas.paste(source,(10,y+48))
        for x,p in [(410,r['automatic']),(660,r['manual'].get('1.6'))]:
            if p:canvas.paste(Image.open(p['image']).convert('RGB').resize((224,224)),(x,y+48))
        context=row_lookup[r['frameId']]['context']
        if context['status']=='ready':canvas.paste(Image.open(context['image']).convert('RGB').resize((224,224)),(910,y+48))
        else:d.text((920,y+100),'No automatic torso input',fill='black')
        d.text((10,y+280),f"Automatic: {auto['raw']} | corrected + reviewed view: {oracle['raw']} | grip max {max(oracle['grip']):.3f}; contact {oracle['contact']:.3f}",fill='black')
        d.text((10,y+300),f"Accepted corrected result: {oracle['accepted']} (both grip and contact must reach 0.8)",fill='black')
    gallery=root/'examples.png';canvas.save(gallery)
    probe=json.loads((root/'view-texture-probe.json').read_text())
    orientation=Counter()
    for frame in selected:
        f=refs['frames'][frame['diagnosticId']]
        if not f['strictViewVerified']:continue
        v=report['orientation'][frame['diagnosticId']]
        orientation['strictReferenceFrames']+=1
        if not v['ready']:orientation['missingContext']+=1
        else:
            predicted=m.VIEWS[max(range(3),key=lambda i:v['view'][i])]
            orientation['argmaxCorrect' if predicted==f['view'] else 'argmaxWrong']+=1
    output=root/'audit.json'
    assert not output.exists()
    result=dict(qualification='Post-primary-score audit; agent-reviewed references, not independent human gold.',
                preScorePinsVerified=len(freeze['sha256']),priorFreezesVerified=old,originalVideosVerified=verified,
                exactManualPixelCropsVerified=crops,sourceSlots=len(rows),stagedModelFitGroupsDisjoint=True,
                anatomyDiagnostics=anatomy,strictOrientationDiagnostic=dict(orientation),
                remainingPrimaryOracleErrors=failures,postScoreViewTextureProbe=probe['counts'],
                gallery=dict(path=str(gallery),sha256=m.sha(gallery),ids=gallery_ids),
                scriptSHA256=m.sha(__file__))
    output.write_text(json.dumps(result,indent=2)+'\n')
    print(json.dumps(result,indent=2))


if __name__=='__main__':main()
