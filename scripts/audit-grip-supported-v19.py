#!/usr/bin/env python3
"""Verify source-screened accounting without altering first-run scores."""
from collections import Counter
from fractions import Fraction
import hashlib
import json
from pathlib import Path
from PIL import Image

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-supported-v19'


def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def clock(p):return Fraction(p['timestamp']['value'],p['timestamp']['timescale'])


def main():
    pins={}
    for filename in ['protocol.json','source-references.json','pre-inference-freeze.json','test/prepare-receipt.json']:
        for path,h in read(OUT/filename)['sha256'].items():
            assert sha(path)==h,path;pins[path]=h
    refs=read(OUT/'source-references.json')['cases'];cases={c['recordingId']:c for c in refs}
    rows=lines(OUT/'test/rows.jsonl');native=lines(OUT/'test/native-input.jsonl');replay=lines(OUT/'test/native-output.jsonl');rgb=lines(OUT/'test/observations.jsonl')
    assert len(rows)==len(rgb)==615 and len(native)==len(replay)==4599
    by_time={(r['recordingId'],clock(r['pose'])):r for r in native}
    verified=0
    for row,observation in zip(rows,rgb):
        assert row['imageSHA256']==observation['imageSHA256']
        current=by_time[(row['recordingId'],clock(row['pose']))]
        assert current['imageSHA256']==row['imageSHA256']
        assert current['pose']['people']==[] or current['pose']['people'][0] in row['pose']['people']
        if not cases[row['recordingId']]['supported']:
            assert not observation['hands']
        with Image.open(Path(row['framesDirectory'])/row['image']) as source:
            source=source.convert('RGB')
            assert sha(Path(row['framesDirectory'])/row['image'])==row['imageSHA256']
            for patch in row['patches']:
                if patch['status']!='patchReady':continue
                expected=source.crop(patch['box']).resize((128,128),Image.Resampling.BILINEAR)
                with Image.open(patch['patch']) as actual:assert actual.convert('RGB').tobytes()==expected.tobytes()
                assert sha(patch['patch'])==patch['patchSHA256'];verified+=1
    assert verified==330
    for inp,out in zip(native,replay):
        assert inp['recordingId']==out['recordingId'] and inp['imageSHA256']==out['imageSHA256']
        assert float(clock(inp['pose']))==out['seconds']
    sources=read(OUT/'protocol.json')['sources']+read(OUT/'additional-intake-freeze.json')['sources']
    assert len(sources)==12
    for f in ['build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl','build/grip-threeway/fitting.jsonl']:
        fitting=lines(ROOT/f);ids={r['recordingId'] for r in fitting};groups={r['annotation']['sourceGroup'] for r in fitting}
        assert not ids&{s['sha256'] for s in sources};assert not groups&{s['sourceGroup'] for s in sources}
    for source in sources:assert sha(source['path'])==source['sha256']
    report=read(OUT/'test/report.json');assert report['allNewSources']==12 and report['supportedNewSources']==0
    failures=[]
    for case in refs:
        if not case['supported']:continue
        rid=case['recordingId'];counter={n['imageSHA256']:n for n in replay if n['recordingId']==rid};component=Counter();examples=[]
        for observation in rgb:
            if observation['recordingId']!=rid:continue
            state=counter[observation['imageSHA256']]
            if not state['exercise']['eligible']:continue
            for hand in observation['hands']:
                if not state['supportBySide'].get(hand['side'],False):continue
                expected=0 if (case['confirmedView']=='front')==(case['grip']=='overhand') else 1
                component['eligibleHandCrops']+=1
                component['correctSurfaceAboveThreshold']+=hand['surface'][expected]>=.8
                component['opposingSurfaceAboveThreshold']+=hand['surface'][1-expected]>=.8
                component['readabilityAboveThreshold']+=hand['visible']>=.8
                if hand['surface'][expected]>=.8 and hand['visible']<.8:
                    component['correctSurfaceRejectedByReadability']+=1
                    examples.append(dict(seconds=observation['seconds'],side=hand['side'],surface=hand['surface'],readability=hand['visible'],patch=hand['patch']))
        failures.append(dict(id=case['id'],componentCounts=dict(component),examples=examples))
    negatives=Counter()
    for case in report['cases']:
        if case['supported']:negatives.update(case['negativeControls'])
    verification=dict(schemaVersion=19,scope='User retains full starting-hang requirement; outside-scope sources excluded before grip inference. No activity-gate decoupling.',
        frozenPinsVerified=len(pins),sourceImagesVerified=615,automaticCropPixelsVerified=330,nativePoseFramesVerified=4599,
        newSourceGroupsDisjointFromBothModelsAllFittingRoles=True,newSourcesScreened=12,supportedNewSources=0,freshSupportedAccuracy=None,
        supportedRegressionCases=[c for c in report['cases'] if c['role']=='exposedRegression' and c['supported']],
        developmentControls=[c for c in report['cases'] if c['role']=='developmentControl'],
        supportedNegativeFrames=dict(negatives),handComponentDiagnostics=failures,
        warning='Correct per-hand surface evidence with source-confirmed view is a component diagnostic, not automatic grip video accuracy. Excluded sources do not count as classification successes or failures.',
        modelFitPerformed=False,modelThresholdsChanged=False,counterPolicyChanged=False,phoneInstalled=False,
        sha256={str((OUT/p).resolve()):sha(OUT/p) for p in ['protocol.json','additional-intake-freeze.json','source-references.json','pre-inference-freeze.json','test/report.json','test/results.json','test/observations.jsonl']})
    for filename in [OUT/'verification.json',ROOT/'Evaluation/fixtures/grip-supported-capture-v19.json']:
        with filename.open('x') as f:json.dump(verification,f,indent=2);f.write('\n')
    remaining=read(OUT/'protocol.json')['remainingUnread']
    with (OUT/'exposure-ledger.json').open('x') as f:json.dump(dict(exposedSources=[dict(id=s['id'],sha256=s['sha256'],sourceGroup=s['sourceGroup'],role='v19 source-screened; excluded before grip inference; no fitting') for s in sources],remainingUnreadNewGroups=remaining,qualification='Historical reserve manifests unchanged. The four remaining groups in the newer acquisition remain unread. Additional old-source records are newly screened here; no fresh accuracy claimed.'),f,indent=2)
    print(json.dumps(dict(verification={k:v for k,v in verification.items() if k not in ['handComponentDiagnostics','sha256','developmentControls','supportedRegressionCases']},componentCounts=[dict(id=c['id'],**c['componentCounts']) for c in failures]),indent=2))


if __name__=='__main__':main()
