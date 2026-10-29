#!/usr/bin/env python3
"""Post-inference attribution; never changes frozen references or predictions."""
from collections import Counter
import datetime
import hashlib
import importlib.util
import json
import math
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-fullsets-v30';RUN=OUT/'run/test'
def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(x) for x in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def compatible_bands(interval):
    """Uncertain does not excuse a band wholly outside the reference interval.

    Uses nominal frozen bands, not a proposed threshold or relaxed hysteresis.
    This supplemental diagnostic was added after seeing the test outputs.
    """
    low,high=interval
    if not all(math.isfinite(v) for v in interval) or not 0.1<=low<=high<=4:
        raise ValueError('Invalid reference interval')
    return [name for name,ok in [('narrow',low<.85),('shoulderWidth',high>=.85 and low<=1.30),('wide',high>1.30)] if ok]

def main():
    spec=importlib.util.spec_from_file_location('v30',ROOT/'scripts/evaluate-grip-fullsets-v30.py')
    evaluation=importlib.util.module_from_spec(spec);spec.loader.exec_module(evaluation);refs=evaluation.verify()
    frames=lines(RUN/'native-output.jsonl');inputs=lines(RUN/'native-input.jsonl')
    scored=read(RUN/'scored-reps.json');groups=read(RUN/'rep-records.json');states=read(RUN/'grip-results.json')
    report=read(RUN/'report-v30.json');cases=[]
    for c in refs['cases']:
        rs=[r for g in groups if g['identity'].split(':')[0]==c['sha256'] for r in g['reps']]
        completed=[r for r in rs if evaluation.module('accounting','evaluate-grip-fullsets-v27.py').complete(r)]
        matches=[sum(w[0]<=(r.get('extremeAt') or r['closedAt'])<w[1] for r in completed) for w in c['sourceFullCycleWindows']]
        fs=[f for f in frames if f['caseId']==c['id']];s=c['sets'][0];compatible=compatible_bands(s['reference']['ratioInterval'])
        items=[r for r in scored if r['caseId']==c['id'] and r['complete']]
        ss=next(x['states'] for x in states if x['caseId']==c['id'])
        unknowns=[dict(repId=r['repId'],startedAt=r['startedAt'],closedAt=r['closedAt'],coverage=r['offlineGripCoverage'],reasons=dict(Counter(x['reason'] for x in ss if r['startedAt']<=x['seconds']<=r['closedAt']))) for r in items if r['offlineGrip']=='unknown']
        cases.append(dict(id=c['id'],cohortRole=c['cohortRole'],referenceFullCycles=len(matches),matchedReferenceCycles=sum(v>0 for v in matches),duplicateMatches=sum(max(0,v-1) for v in matches),completedCounterReps=len(completed),completedRepGrip=dict(Counter('unknown' if r['offlineGrip']=='unknown' else 'correct' if r['gripCorrect'] is True else 'wrong' for r in items)),unknownGripReps=unknowns,sourceWidthInterval=s['reference']['ratioInterval'],nominalCompatibleWidthBands=compatible,knownWidthReps=sum(r['nativeSavedWidth']!='unknown' for r in items),nominalIntervalIncompatibleWidthReps=[r['repId'] for r in items if r['nativeSavedWidth']!='unknown' and r['nativeSavedWidth'] not in compatible]))
    primary=[c for c in cases if c['cohortRole']=='completeSourceSet'];partial=[c for c in cases if c['cohortRole']=='partialCycleDiagnostic']
    q=min((f for f in frames if f['caseId']=='QA5ko5Ibu0g' and f['projectedSpanRatio'] is not None),key=lambda f:abs(f['seconds']-19.5))
    pose=next(i['pose'] for i in inputs if i['caseId']==q['caseId'] and i['pose']['timestamp']['value']/i['pose']['timestamp']['timescale']==q['seconds'])
    points={p['joint']:p for p in pose['people'][0]['landmarks']}
    p=lambda name:points[name]['position']
    shoulder=math.hypot(p('leftShoulder')['x']-p('rightShoulder')['x'],p('leftShoulder')['y']-p('rightShoulder')['y'])
    a,b=q['reference']['a'],q['reference']['b'];dx=b['x']-a['x'];dy=b['y']-a['y'];length=math.hypot(dx,dy)
    span=abs((p('leftWrist')['x']-p('rightWrist')['x'])*dx/length+(p('leftWrist')['y']-p('rightWrist')['y'])*dy/length)
    attribution=dict(caseId=q['caseId'],seconds=q['seconds'],poseShoulderSpanPixels=shoulder,projectedWristSpanPixels=span,reportedRatio=q['projectedSpanRatio'],impliedFrozenShoulderSpanPixels=span/q['projectedSpanRatio'],referenceImageSeconds=19.5,sourceReferenceInterval=next(c for c in cases if c['id']==q['caseId'])['sourceWidthInterval'],note='Pose shoulders are medial relative to frozen source regions; frozen denominator is smaller still. Geometry/calibration concern, not a hand-surface or orientation decision. Provisional agent reference, not human gold. No points or thresholds were changed.')
    raw=lines(OUT/'native-body/0j9CUk1NjOY/poses.jsonl')
    empty=[p['timestamp']['value']/p['timestamp']['timescale'] for p in raw if not p['people'] and 4.8<p['timestamp']['value']/p['timestamp']['timescale']<5.2]
    result=dict(schemaVersion=30,recordedAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),scope='Post-inference attribution on exposed fresh-test results. Nominal interval compatibility supplements the original categorical scorer; no rewritten references, selected successes, threshold tuning or model fitting.',cases=cases,completeSourceSets=dict(sources=len(primary),referenceFullCycles=sum(c['referenceFullCycles'] for c in primary),matchedReferenceCycles=sum(c['matchedReferenceCycles'] for c in primary),completedCounterReps=sum(c['completedCounterReps'] for c in primary)),partialDiagnostics=dict(sources=len(partial),referenceFullCycles=sum(c['referenceFullCycles'] for c in partial),matchedReferenceCycles=sum(c['matchedReferenceCycles'] for c in partial),completedCounterReps=sum(c['completedCounterReps'] for c in partial)),negativeControls={k:sum(c['negatives'][k] for c in report['cases']) for k in report['cases'][0]['negatives']},widthAttribution=attribution,missedPartialCycle=dict(caseId='0j9CUk1NjOY',nativeVisionNoPersonSourceTimes=empty,note='At the first peak, head rises outside frame; raw Apple body output briefly has no person. Kept in partial diagnostics as frozen, not retrospectively removed.'),limits=['No qualifying real grip type/width alternation recording in this acquisition.','Narrow and Medium band accuracy remain unqualified. Three source intervals cross category boundaries.','Zero wrong known grip labels is limited-source precision with Unknowns, not overall accuracy.','Native Mac research replay does not qualify automatic phone acquisition or live phone performance.'],sha256={str(p):sha(p) for p in [Path(__file__),RUN/'report-v30.json',RUN/'native-output.jsonl',RUN/'scored-reps.json',OUT/'run/source-references.json']})
    with (RUN/'attribution-audit.json').open('x') as f:json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps({k:v for k,v in result.items() if k not in ['cases','sha256','limits']},indent=2))

if __name__=='__main__':main()
