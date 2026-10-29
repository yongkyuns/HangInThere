#!/usr/bin/env python3
"""Post-evaluation attribution and integrity audit; no inference or label changes."""
from collections import Counter
import importlib.util
import json
from pathlib import Path
import hashlib
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-width-v27';RUN=OUT/'run'
def module(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def read(p):return json.loads(p.read_text())
def lines(p):return [json.loads(x) for x in p.read_text().splitlines()]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def write(p,x):
 with p.open('x') as f:json.dump(x,f,indent=2);f.write('\n')
def main():
 evaluator=module('fullsetaudit','evaluate-grip-fullsets-v27.py');refs=evaluator.verify();policy=module('policyattribution','grip-controlled-v17.py')
 fs=lines(RUN/'test/native-output.jsonl');rgb=lines(RUN/'test/observations.jsonl');states=read(RUN/'test/grip-results.json');report=read(RUN/'test/report.json');reps=read(RUN/'test/scored-reps.json');cases=[]
 for path,h in read(RUN/'pre-inference-freeze.json')['sha256'].items():assert sha(Path(path))==h,path
 for c in refs['cases']:
  frames=[f for f in fs if f['caseId']==c['id']];by_time={f['seconds']:f for f in frames};results=next(x for x in states if x['caseId']==c['id']);observations=[r for r in rgb if r['caseId']==c['id']];setmetrics=[]
  for s in c['sets']:
   raw=Counter();supported=Counter();eligible=Counter();evidence_samples=[]
   for r in observations:
    if not evaluator.inside(r['seconds'],s['activeWindow']):continue
    f=by_time[r['seconds']];shot=next((x for x in c['shots'] if x['id']==f['shotId']),None);view=shot['view'] if shot else None
    def classify(hands):
     candidates=[policy.evidence(h,view,False) for h in hands];labels={v['label'] for v in candidates if v}
     return next(iter(labels)) if len(labels)==1 else 'conflict' if labels else 'unknown'
    label=classify(r['hands']);supported_label=classify([h for h in r['hands'] if f['supportBySide'].get(h['side'],False)])
    raw[label]+=1;supported[supported_label]+=1;eligible['yes' if f['exercise']['eligible'] else 'no']+=1
    if len(evidence_samples)<3 and label not in ['unknown','conflict']:
     evidence_samples.append(dict(seconds=r['seconds'],rawLabel=label,supportedLabel=supported_label,contextEligible=f['exercise']['eligible'],contextEpoch=f['exercise']['epoch'],sourceEpoch=f['sourceEpoch'],hands=r['hands']))
   decisions=[d for d in results['decisions'] if evaluator.inside(d['seconds'],s['activeWindow'])]
   setmetrics.append(dict(id=s['id'],referenceGrip=s['referenceGrip'],scheduledRawEvidence=dict(raw),scheduledSupportedEvidence=dict(supported),scheduledExerciseEligibility=dict(eligible),firstNewDecisions=decisions[:3],sampleEvidence=evidence_samples))
  negatives=[]
  for w in c['negativeWindows']:
   selected=[(f,s) for f,s in zip(frames,results['states']) if evaluator.inside(f['seconds'],w)]
   positive=[(f,s) for f,s in selected if s['label']!='unknown']
   negatives.append(dict(window=w,frames=len(selected),visibleGripFrames=len(positive),firstVisibleSeconds=positive[0][0]['seconds'] if positive else None,lastVisibleSeconds=positive[-1][0]['seconds'] if positive else None,firstFrame=positive[0] if positive else None))
  transition_audit=[]
  for a,b in zip(c['sets'],c['sets'][1:]):
   between=[(f,s) for f,s in zip(frames,results['states']) if a['activeWindow'][1]<=f['seconds']<b['activeWindow'][0]]
   clear=[(f,s) for f,s in between if s['label']=='unknown' and (f['released'] or not f['exercise']['eligible'])]
   new=next((d for d in results['decisions'] if evaluator.inside(d['seconds'],b['activeWindow'])),None)
   transition_audit.append(dict(fromSet=a['id'],toSet=b['id'],hadActualReleaseOrContextClear=bool(clear),firstClearSeconds=clear[0][0]['seconds'] if clear else None,firstNewDecision=new,note='Matching the previous reference label does not prove inheritance. Current decisions after an actual clear are fresh wrong evidence if their label is wrong.'))
  cases.append(dict(id=c['id'],sets=setmetrics,negativeWindows=negatives,transitionEvidence=transition_audit))
 completed=[r for r in reps if r['complete']];knownw=[r for r in completed if r['nativeSavedWidth']!='unknown'];knownt=[r for r in completed if r['offlineGrip']!='unknown']
 totals=dict(nativeFrames=report['nativeFrames'],scheduledImages=report['scheduledImages'],patches=report['automaticHandCrops'],completedCounterReps=sum(c['completedCounterReps'] for c in report['cases']),scoredCompletedReps=len(completed),nativeKnownWidthReps=len(knownw),knownWidthBands=dict(Counter(r['nativeSavedWidth'] for r in knownw)),verifiableKnownWidthReps=sum(r['widthCorrect'] is not None for r in completed),wrongVerifiableWidthReps=sum(r['widthCorrect'] is False for r in completed),offlineKnownGripReps=len(knownt),offlineCorrectKnownGripReps=sum(r['gripCorrect'] is True for r in completed),offlineWrongKnownGripReps=sum(r['gripCorrect'] is False for r in completed),negativeFrames=sum(c['negatives']['frames'] for c in report['cases']),negativeCompletedReps=sum(c['negatives']['completedReps'] for c in report['cases']),negativeNewAcceptances=sum(c['negatives']['newAcceptances'] for c in report['cases']),negativeVisibleGripFrames=sum(c['negatives']['visibleGripFrames'] for c in report['cases']))
 result=dict(qualification='Attribution on already exposed fresh evaluation; no retuning. Six sources, correlated frames and source-confirmed orientation, not population accuracy.',totals=totals,cases=cases,frozenProductionFilesUnchanged=True,allPreInferencePatchHashesVerified=True,sha256={str(p):sha(p) for p in [Path(__file__),RUN/'test/report.json',RUN/'test/scored-reps.json']})
 write(RUN/'test/attribution-audit.json',result);print(json.dumps(totals,indent=2))
if __name__=='__main__':main()
