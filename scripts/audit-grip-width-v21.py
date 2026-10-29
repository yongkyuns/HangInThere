#!/usr/bin/env python3
"""Independently audit frozen source separation, original pixels and sequence isolation."""
from collections import Counter
from fractions import Fraction
import hashlib
import json
from pathlib import Path
import xml.etree.ElementTree as ET
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-width-v21'
def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def seconds(p):return float(Fraction(p['timestamp']['value'],p['timestamp']['timescale']))

def main():
    pins={}
    for name in ['protocol.json','reference-freeze.json','pre-inference-freeze.json','test/prepare-receipt.json']:
        for p,h in read(OUT/name)['sha256'].items():
            if p.endswith('/scripts/evaluate-grip-width-v21.py') and sha(p)!=h:
                repair=read(OUT/'accounting-repair.json')
                assert h==repair['originalSHA256']==sha(OUT/'original-code/evaluate-grip-width-v21.py')
                assert sha(p)==repair['correctedSHA256']
            else:assert sha(p)==h,p
            pins[p]=h
    cases=read(OUT/'source-references.json')['cases']
    for c in cases:assert sha(c['path'])==c['sha256']
    ids={c['sha256'] for c in cases};groups={c['sourceGroup'] for c in cases}
    for file in ['build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl','build/grip-threeway/fitting.jsonl']:
        fitting=lines(ROOT/file)
        assert not ids&{r['recordingId'] for r in fitting}
        assert not groups&{r['annotation']['sourceGroup'] for r in fitting}
        assert not any(token in json.dumps(fitting).lower() for token in ['physitrack','lgpbf4y5ow8','iqoaa0jhpee','d4kpdlbv-d4'])
    native=lines(OUT/'test/native-input.jsonl');output=lines(OUT/'test/native-output.jsonl')
    assert len(native)==len(output)==7579
    lookup={}
    for n,o in zip(native,output):
        assert n['caseId']==o['caseId'] and n['recordingId']==o['recordingId'] and n['imageSHA256']==o['imageSHA256']
        assert seconds(n['pose'])==o['seconds'];lookup[n['recordingId'],o['seconds']]=n
        if not n['widthViewSupported']:assert o['width']=='unknown'
    rows=lines(OUT/'test/rows.jsonl');rgb=lines(OUT/'test/observations.jsonl');assert len(rows)==len(rgb)==1137
    patches=0;grip_hashes=set()
    for r,g in zip(rows,rgb):
        assert r['recordingId']==g['recordingId'] and r['imageSHA256']==g['imageSHA256'] and seconds(r['pose'])==g['seconds']
        n=lookup[r['recordingId'],g['seconds']]
        assert n['pose']['people']==[] or n['pose']['people'][0] in r['pose']['people']
        if g['hands']:
            assert g['imageSHA256'] not in grip_hashes;grip_hashes.add(g['imageSHA256'])
        source=Path(r['framesDirectory'])/r['image'];assert sha(source)==r['imageSHA256']
        with Image.open(source) as im:
            for patch in r['patches']:
                if patch['status']!='patchReady':continue
                assert sha(patch['patch'])==patch['patchSHA256']
                expected=im.convert('RGB').crop(patch['box']).resize((128,128),Image.Resampling.BILINEAR)
                with Image.open(patch['patch']) as actual:assert actual.convert('RGB').tobytes()==expected.tobytes()
                patches+=1
    report=read(OUT/'test/report.json')
    for shot in report['shots']:
        fs=[r for r in output if r['caseId']==shot['caseId'] and r['shotId']==shot['shotId']]
        assert len(fs)==shot['nativeFrames'] and dict(Counter(f['width'] for f in fs))==shot['predictedBands']
        known=[f for f in fs if f['width']!='unknown']
        assert len(known)==shot['knownWidthFrames']
        assert shot['wrongWidthFrames']==sum(f['width']!=shot['referenceWidth'] for f in known)
        assert shot['widthCoverage']==len(known)/len(fs)
    plan=read(OUT/'alternating-plan.json');sequence=lines(OUT/'test/alternating-output.jsonl')
    assert sha(OUT/'test/alternating-input.jsonl')==plan['inputSHA256'];assert len(sequence)==5112
    keys=['seconds','width','projectedSpanRatio','widthReason','phase','trackingIssue','movements','released','reference','supportBySide','exercise']
    baselines={(r['caseId'],r['seconds']):r for r in output}
    baselines.update({('owner-wide-control',r['seconds']):r for r in sequence if r['caseId']=='0:owner-wide-control'})
    for entry in plan['order']:
        fs=[r for r in sequence if r['caseId']==str(entry['position'])+':'+entry['caseId']]
        assert len(fs)==entry['frames'] and fs[0]['width']=='unknown'
        for r in fs:assert all(r[k]==baselines[entry['caseId'],r['seconds']][k] for k in keys)
    tests=[]
    for p in OUT.glob('TEST-*Width*.xml'):
        suite=ET.parse(p).getroot();assert suite.attrib['failures']==suite.attrib['errors']=='0'
        tests.append(dict(name=suite.attrib['name'],tests=int(suite.attrib['tests'])))
    assert sum(s['tests'] for s in tests)==11
    receipt=dict(schemaVersion=21,frozenFilesVerified=len(pins),newSources=9,newSourceGroups=len(groups),allFittingRolesDisjoint=True,
        nativeFramesVerified=7579,scheduledImagesVerified=1137,automaticCropPixelsVerified=patches,
        sequenceFramesVerified=5112,noWidthCarryoverOnAssembledSequence=True,realContinuousAlternationQualified=False,
        independentNarrowMediumAccuracyQualified=False,tests=tests,modelFitPerformed=False,modelThresholdsChanged=False,productionCodeChanged=False,
        sha256={str(p):sha(p) for p in [OUT/'source-references.json',OUT/'test/report.json',OUT/'test/native-output.jsonl',OUT/'test/observations.jsonl',OUT/'test/alternating-output.jsonl',OUT/'test/rep-records.json',OUT/'accounting-repair.json',Path(__file__)]})
    with (OUT/'verification.json').open('x') as f:json.dump(receipt,f,indent=2);f.write('\n')
    print(json.dumps({k:v for k,v in receipt.items() if k!='sha256'},indent=2))

if __name__=='__main__':main()
