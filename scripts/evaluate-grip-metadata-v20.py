#!/usr/bin/env python3
"""No readability veto within prescreened capture scope; independent shared width metadata."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'build/grip-metadata-v20'
OLD=ROOT/'build/grip-supported-v19'


def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,obj):
    with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
def module(name,filename):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/filename)
    obj=importlib.util.module_from_spec(spec);spec.loader.exec_module(obj);return obj


def freeze():
    source=read(OLD/'protocol.json')
    paths=[Path(__file__),ROOT/'scripts/grip-controlled-v17.py',ROOT/'scripts/evaluate-grip-supported-v19.py',
           OLD/'source-references.json',OLD/'pre-inference-freeze.json',OLD/'test/rows.jsonl',OLD/'test/native-input.jsonl',
           OLD/'test/native-output.jsonl',OLD/'test/observations.jsonl',OLD/'test/report.json',
           ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGripWidth.kt',
           ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGripAnalysis.kt',
           ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGrip.kt',
           ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/ExerciseCounter.kt',
           ROOT/'Evaluation/androidTests/GripWidthMetadataV20Tests.kt']+[ROOT/f for f in source['models'].values()]
    write(OUT/'protocol.json',dict(schemaVersion=20,
        scope='Exposed supported-capture regression; source screen/view are diagnostic inputs, not an automatic phone eligibility or RGB-deployment claim.',
        change='Remove learned readability only within previously frozen supported sources. Raw surface/neutral thresholds and full-hang counter stay unchanged. Reuse same image scores; no retraining or new source decoding.',
        width='Exact shared width component: wrist separation projected along support / median shoulder span from stable ready hang; narrow<.85, medium .85..1.30, wide>1.30. Existing shoulderWidth serialized name retained; width independent of orientation.',
        cases=read(OLD/'source-references.json')['cases'],sha256={str(p):sha(p) for p in paths}))
    print('Frozen v20 against existing source screen, same images/models, new shared width component')


def score():
    protocol=read(OUT/'protocol.json');assert all(sha(p)==h for p,h in protocol['sha256'].items())
    policy=module('no_readability_policy','grip-controlled-v17.py');accounting=module('supported_accounting','evaluate-grip-supported-v19.py')
    native=lines(OLD/'test/native-output.jsonl');rgb={r['imageSHA256']:r for r in lines(OLD/'test/observations.jsonl')}
    rows=lines(OLD/'test/rows.jsonl');width=lines(OUT/'width-output.jsonl');assert len(native)==len(width)
    for n,w in zip(native,width):assert (n['recordingId'],n['imageSHA256'],n['seconds'])==(w['recordingId'],w['imageSHA256'],w['seconds'])
    reports={};all_results={}
    for name,readability in [('previousReadability',True),('supportedWithoutReadability',False)]:
        metrics=[];results=[]
        for case in protocol['cases']:
            frames=[f for f in native if f['recordingId']==case['recordingId']]
            result=policy.decide(frames,rgb,case['confirmedView'] if case['supported'] else None,readability)
            times=[float(accounting.clock(r['pose'])) for r in rows if r['recordingId']==case['recordingId']]
            summary=accounting.summarize(case,frames,result['states'],times)
            samples=[w for w in width if w['recordingId']==case['recordingId']]
            bands=Counter(w['width'] for w in samples if accounting.phase(case,w['seconds'])=='exercise')
            observed=[w['projectedSpanRatio'] for w in samples if w['width']!='unknown' and accounting.phase(case,w['seconds'])=='exercise']
            summary.update(widthBands=dict(bands),medianShoulderRatio=sorted(observed)[len(observed)//2] if observed else None)
            # Per-source-cycle metadata are manually marked diagnostic windows;
            # production rep records keep their actual counter-owned intervals.
            summary['cycleMetadata']=[]
            for window in case['fullCycleWindows']:
                states=[s for s in result['states'] if window[0]<=s['seconds']<=window[1]]
                ws=[w for w in samples if window[0]<=w['seconds']<=window[1]]
                summary['cycleMetadata'].append(dict(window=window,observedOrientations=sorted({s['label'] for s in states if s['observed']}),
                    observedWidths=sorted({w['width'] for w in ws if w['width']!='unknown'})))
            metrics.append(summary);results.append(dict(id=case['id'],**result))
        reports[name]=metrics;all_results[name]=results
    write(OUT/'results.json',all_results)
    report=dict(schemaVersion=20,scope=protocol['scope'],comparisons=reports,readabilityVetoRemoved=True,thresholdsChanged=False,
                modelFitPerformed=False,newSourcesDecoded=0,phoneInstalled=False,
                noFreshAccuracy='Twelve newly screened sources remain outside scope; supported cases here are exposed development/regression sources.',
                widthOutputSHA256=sha(OUT/'width-output.jsonl'),inputProtocolSHA256=sha(OUT/'protocol.json'))
    write(OUT/'report.json',report)
    write(ROOT/'Evaluation/fixtures/grip-width-metadata-v20.json',report)
    for name,metrics in reports.items():
        print(name)
        for m in metrics:
            if m['supported']:print(json.dumps({k:v for k,v in m.items() if k not in ['cycles','negativeControls']}))


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('action',choices=['freeze','score']);a=parser.parse_args();globals()[a.action]()
