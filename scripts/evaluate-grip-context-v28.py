#!/usr/bin/env python3
"""Frozen metadata-only regression; body/counter/width outputs must be identical."""
from collections import Counter
import hashlib,importlib.util,json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-context-v28';BASE=ROOT/'build/grip-width-v27'
def read(p):return json.loads(Path(p).read_text())
def lines(p):return [json.loads(s) for s in Path(p).read_text().splitlines()]
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def module(name,file):
 s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
def write(p,obj):
 with Path(p).open('x') as f:json.dump(obj,f,indent=2);f.write('\n')
def inside(t,w):return w[0]<=t<w[1]
def verify():
 for name in ['baseline-freeze.json','candidate-freeze.json','replay-input-freeze.json']:
  for p,h in read(OUT/name)['sha256'].items():
   if sha(p)!=h:
    assert name=='baseline-freeze.json'
    assert sha(OUT/'baseline-code'/Path(p).relative_to(ROOT))==h,p
 # Do not weaken the old freeze to make a new source/model appear untouched.
 # These source, pixel and input pins remain exactly the v27 evidence.
 for name in ['reference-freeze.json','supplement-reference-freeze.json','implementation-completion-freeze.json','run/pre-inference-freeze.json']:
  for p,h in read(BASE/name)['sha256'].items():assert sha(p)==h,p
 old=read(BASE/'protocol.json')['sha256']
 for p,h in old.items():
  if sha(p)!=h:
   assert Path(p)==ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGripExerciseContext.kt'
   assert sha(OUT/'baseline-code'/Path(p).relative_to(ROOT))==h
 return read(BASE/'run/source-references.json')
def main():
 refs=verify();accounting=module('v28accounting','evaluate-grip-fullsets-v27.py');policy=module('v28policy','grip-controlled-v17.py')
 correction=read(BASE/'source-review-correction.json')
 for c in refs['cases']:
  if c['id']==correction['caseId']:
   s=next(s for s in c['sets'] if s['id']==correction['setId']);s.update(activeWindow=correction['correctedActiveWindow'],heldWindow=correction['correctedHeldWindow'],completeSet=True);c['negativeWindows'].append(correction['additionalNegativeWindow'])
 comparisons={}
 for name,old,records in [('fresh',BASE/'run/test/native-output.jsonl',BASE/'run/test/rep-records.json'),('native',ROOT/'build/grip-width-v26/native-output.jsonl',ROOT/'build/grip-width-v26/native-rep-records.json'),('alternating',ROOT/'build/grip-width-v26/alternating-output.jsonl',ROOT/'build/grip-width-v26/alternating-rep-records.json'),('older',ROOT/'build/grip-width-v26/older-output.jsonl',ROOT/'build/grip-width-v26/older-rep-records.json'),('freshRegression',ROOT/'build/grip-width-v26/freshRegression-output.jsonl',ROOT/'build/grip-width-v26/freshRegression-rep-records.json')]:
  before=lines(old);after=lines(OUT/f'{name}-output.jsonl');assert len(before)==len(after)
  assert sha(records)==sha(OUT/f'{name}-rep-records.json'),name
  for a,b in zip(before,after):
   assert {k:v for k,v in a.items() if k!='exercise'}=={k:v for k,v in b.items() if k!='exercise'},(name,a['seconds'])
   assert not b['exercise']['eligible'] or a['exercise']['eligible'],(name,a['seconds'])
  comparisons[name]=dict(frames=len(after),counterAndWidthFramesBitIdentical=True,savedRepRecordsByteIdentical=True,newEligibilitySubsetOfBaseline=True,priorEligibleFrames=sum(f['exercise']['eligible'] for f in before),candidateEligibleFrames=sum(f['exercise']['eligible'] for f in after),observedDepartureFrames=sum(f['exercise']['reason']=='handLeftSupport' for f in after))
 before=lines(BASE/'run/test/native-output.jsonl');after=lines(OUT/'fresh-output.jsonl');observations=lines(BASE/'run/test/observations.jsonl');saved=read(OUT/'fresh-rep-records.json');reports=[];allstates=[];repitems=[]
 def decide(c,fs):
  obs=[r for r in observations if r['caseId']==c['id']];states=[];decisions=[];offset=0
  while offset<len(fs):
   end=offset+1
   while end<len(fs) and fs[end]['shotId']==fs[offset]['shotId']:end+=1
   block=fs[offset:end];shot=next((s for s in c['shots'] if s['id']==block[0]['shotId']),None);hashes={f['imageSHA256'] for f in block}
   r=policy.decide(block,{r['imageSHA256']:r for r in obs if r['imageSHA256'] in hashes},shot['view'] if shot else None,False);states+=r['states'];decisions+=r['decisions'];offset=end
  return dict(states=states,decisions=decisions)
 for c in refs['cases']:
  fs=[f for f in after if f['caseId']==c['id']];oldfs=[f for f in before if f['caseId']==c['id']];now=decide(c,fs);old=decide(c,oldfs)
  allstates.append(dict(caseId=c['id'],**now));sets=[]
  for s in c['sets']:
   reference=s['referenceGrip'];verifiable=reference in ['overhand','underhand','neutral'];w=s['activeWindow']
   def summary(result):
    ss=[s for s in result['states'] if inside(s['seconds'],w)];known=[s for s in ss if s['label']!='unknown']
    return dict(frames=len(ss),visibleGripFrames=len(known),correctVisibleGripFrames=sum(s['label']==reference for s in known) if verifiable else None,wrongVisibleGripFrames=sum(s['label']!=reference for s in known) if verifiable else None,gripBands=dict(Counter(s['label'] for s in known)))
   reps=[r for g in saved if g['identity'].split(':')[0]==c['sha256'] for r in g['reps'] if accounting.complete(r) and inside(r.get('extremeAt') or (r['startedAt']+r['closedAt'])/2,w)]
   metadata={}
   for name,frames,result in [('baseline',oldfs,old),('candidate',fs,now)]:
    items=[]
    for r in reps:
     durations=accounting.credit(frames,result['states'],r['startedAt'],r['closedAt']);label=max(durations,key=durations.get) if durations else 'unknown';coverage=durations.get(label,0)/(r['closedAt']-r['startedAt']) if r['closedAt']>r['startedAt'] else 0
     if coverage<.8:label='unknown'
     contained=s['heldWindow'][0]<=r['startedAt']<=r['closedAt']<=s['heldWindow'][1]
     item=dict(caseId=c['id'],setId=s['id'],repId=r['id'],variant=name,label=label,coverage=coverage,referenceGrip=reference,correct=label==reference if verifiable and contained and label!='unknown' else None);items.append(item);repitems.append(item)
    metadata[name]=dict(completedReps=len(items),correct=sum(i['correct'] is True for i in items),wrong=sum(i['correct'] is False for i in items),unknown=sum(i['label']=='unknown' for i in items))
   sets.append(dict(id=s['id'],referenceGrip=reference,baseline=summary(old),candidate=summary(now),repMetadata=metadata))
  negative=lambda t:any(inside(t,w) for w in c['negativeWindows'])
  negatives={}
  for variant,frames,result in [('baseline',oldfs,old),('candidate',fs,now)]:
   ss=[(f,s) for f,s in zip(frames,result['states']) if negative(f['seconds'])]
   negatives[variant]=dict(frames=len(ss),visibleGripFrames=sum(s['label']!='unknown' for _,s in ss),eligibleFrames=sum(f['exercise']['eligible'] for f,_ in ss),newGripDecisions=sum(negative(d['seconds']) for d in result['decisions']))
  reports.append(dict(id=c['id'],sets=sets,negativeControls=negatives))
 negatives={variant:dict(visibleGripFrames=sum(c['negativeControls'][variant]['visibleGripFrames'] for c in reports),eligibleFrames=sum(c['negativeControls'][variant]['eligibleFrames'] for c in reports),newGripDecisions=sum(c['negativeControls'][variant]['newGripDecisions'] for c in reports)) for variant in ['baseline','candidate']}
 regular=next(c for c in reports if c['id']=='YnykpezuXWg')['sets'];regular=[s for s in regular if s['id'] in ['overhand-regular','underhand-regular']]
 checks=dict(regularCorrectVisibleFramesNotRegressed=all(s['candidate']['correctVisibleGripFrames']>=s['baseline']['correctVisibleGripFrames'] for s in regular),noNegativeLabels=negatives['candidate']['visibleGripFrames']==0,regularCorrectRepMetadataNotRegressed=all(s['repMetadata']['candidate']['correct']>=s['repMetadata']['baseline']['correct'] for s in regular),noNewWrongRegularRepMetadata=all(s['repMetadata']['candidate']['wrong']<=s['repMetadata']['baseline']['wrong'] for s in regular),counterAndWidthUnchanged=True,newContextEligibilityNeverAddsFrames=True)
 totals={v:dict(correct=sum(r['correct'] is True for r in repitems if r['variant']==v),wrong=sum(r['correct'] is False for r in repitems if r['variant']==v),unknown=sum(r['label']=='unknown' for r in repitems if r['variant']==v)) for v in ['baseline','candidate']}
 result=dict(schemaVersion=28,scope='Exposed-source development regression, not new holdout accuracy or phone deployment. Context lifecycle only; no wrist fusion, training or counter changes.',checks=checks,candidatePassed=all(checks.values()),replays=comparisons,negativeControls=negatives,completedRepGripMetadata=totals,cases=reports,sha256={str(p):sha(p) for p in [Path(__file__),OUT/'candidate-freeze.json',OUT/'fresh-output.jsonl',OUT/'fresh-rep-records.json']})
 write(OUT/'comparison.json',result);write(OUT/'grip-results.json',allstates);write(OUT/'rep-grip-comparison.json',repitems);print(json.dumps({k:v for k,v in result.items() if k not in ['cases','sha256']},indent=2))
if __name__=='__main__':main()
