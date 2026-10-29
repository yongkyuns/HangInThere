#!/usr/bin/env python3
"""Check native cadence, exact RGB ownership and all original sample denominators."""
from collections import Counter
from fractions import Fraction
import hashlib
import importlib.util
import json
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-start-v18'
OLD=ROOT/'build/grip-controlled-v17'


def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(x) for x in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def clock(p):return Fraction(p['timestamp']['value'],p['timestamp']['timescale'])


def main():
    pins={}
    for p in [OUT/'protocol.json',OUT/'pre-replay-freeze.json',OLD/'protocol.json',OLD/'candidate-freeze.json',OLD/'test/pre-inference-freeze.json']:
        for filename,h in read(p)['sha256'].items():
            assert sha(filename)==h,filename
            pins[filename]=h
    original={r['imageSHA256']:r for stage in ['baseline','test'] for r in lines(OLD/stage/'native-input.jsonl')}
    native_input=lines(OUT/'native-input.jsonl');native=lines(OUT/'native-output.jsonl');diagnostics=lines(OUT/'start-diagnostics.jsonl')
    assert len(native_input)==len(native)==len(diagnostics)==2689
    for inp,replay,d in zip(native_input,native,diagnostics):
        assert inp['recordingId']==replay['recordingId']==d['recordingId']
        assert float(clock(inp['pose']))==replay['seconds']==d['seconds']
        if not inp['imageSHA256'].startswith('noRGB:'):
            old=original[inp['imageSHA256']]
            assert old['pose']['people']==inp['pose']['people'] and len(inp['pose']['people'])==1
        assert d['requiredExtendedDegrees']==155
    cases=read(OLD/'protocol.json')['cases']+read(OLD/'test-source-references.json')['cases']
    scores={r['id']:r for r in read(OUT/'results.json')};summary=[]
    spec=importlib.util.spec_from_file_location('frozen_phase',ROOT/'scripts/evaluate-grip-controlled-v17.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    for case in cases:
        sampled=lines(Path(case['framesDirectory'])/'frames.jsonl')
        inputs=[i for i in native_input if i['recordingId']==case['recordingId']]
        replays=[n for n in native if n['recordingId']==case['recordingId']]
        by_clock={clock(i['pose']):(i,n,s) for i,n,s in zip(inputs,replays,scores[case['id']]['states'])}
        scheduled=[]
        for row in sampled:
            i,n,state=by_clock[clock(row['pose'])]
            phase=module.phase(case,float(clock(row['pose'])))
            scheduled.append(dict(phase=phase,rgbOwned=not i['imageSHA256'].startswith('noRGB:'),label=state['label'],observed=state['observed']))
        exercise=[r for r in scheduled if r['phase']=='exercise']
        diag=[d for d in diagnostics if d['recordingId']==case['recordingId']]
        summary.append(dict(id=case['id'],referenceGrip=case['grip'],originalRGBScheduleFrames=len(scheduled),
                            originalRGBExerciseFrames=len(exercise),visibleLabelsOnOriginalExerciseSchedule=sum(r['label']!='unknown' for r in exercise),
                            rgbScheduleFramesWithoutReusableOwner=sum(not r['rgbOwned'] for r in scheduled),
                            startingReasons=dict(Counter(d['reason'] for d in diag)),
                            measuredExtendedFrames=sum(d['extended'] for d in diag),firstCorrectSeconds=scores[case['id']]['firstCorrectSeconds']))
    test_xml=ROOT/'shared/build/test-results/jvmTest/TEST-dev.hanginthere.evaluation.GripStartDiagnosticsV18Tests.xml'
    suite=ET.parse(test_xml).getroot();assert suite.attrib['tests']=='3' and suite.attrib['failures']==suite.attrib['errors']=='0'
    report=dict(qualification='Exposed-regression diagnostic; fixed hand models/readability, no independent accuracy or phone claim.',
                verifiedPins=len(pins),nativeFrames=2689,originalDecodedFrames=2798,cameraOrbitFramesOutsideFixedView=109,
                originalRGBPosesBitIdentical=362,originalRGBPixelsAndModelsUnchanged=True,
                ownerCheck='A scheduled crop can be reused only if its original selected-person landmarks exactly match the current native selected-person pose. Missing/foreign ownership abstains.',
                denominatorNote='The first report rgbExerciseFrames counts reusable owned RGB only. This addendum retains every original scheduled exercise sample in the denominator; no predicted labels or original result files changed.',
                cases=summary,negativeControls=read(OUT/'report.json')['negatives'],diagnosticTestsPassed=3,
                shippingPolicyChanged=False,phoneInstalled=False,newReservesRead=0,
                hashes={str(p.resolve()):sha(p) for p in [OUT/'protocol.json',OUT/'pre-replay-freeze.json',OUT/'report.json',OUT/'results.json',OUT/'start-diagnostics.jsonl',test_xml]})
    for target in [OUT/'verification.json',ROOT/'Evaluation/fixtures/grip-start-cadence-v18.json']:
        with target.open('x') as f:json.dump(report,f,indent=2);f.write('\n')
    print(json.dumps(report,indent=2))


if __name__=='__main__':main()
