#!/usr/bin/env python3
"""Write source-only agent annotations; refuses to overwrite the cohort freeze."""
import datetime
import hashlib
import importlib.util
import json
from pathlib import Path
from PIL import Image

ROOT=Path(__file__).resolve().parents[1];OUT=ROOT/'build/grip-fullsets-v30';RUN=OUT/'run'
def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()
def write(p,x):
    with p.open('x') as f:json.dump(x,f,indent=2);f.write('\n')
spec=importlib.util.spec_from_file_location('reference',ROOT/'scripts/grip-width-reference.py')
reference=importlib.util.module_from_spec(spec);spec.loader.exec_module(reference)
inventories=['source-review-inventory.json','supplement-review-inventory.json','completion-review-inventory.json']
by={c['id']:c for name in inventories for c in json.loads((OUT/name).read_text())}
annotations={
 'QA5ko5Ibu0g':dict(time=19.5,regions=[[729,318,745,340],[610,318,630,340],[730,177,744,192],[620,177,637,192]],support=[[200,150],[1100,178]],grip='underhand',active=[17.8,26.5],held=[12.2,26.9],cycles=[[19.2,21.7],[23.4,25.8]],visible=3,complete=True,role='completeSourceSet',neg=[[0,11.8],[27.4,38.3],[38.5,42.2]],note='Front, both arms visible. Clear foreground exercising subject despite background people. Three visible excursions; first starts from standing without a full hang, so only two complete hang-to-hang cycles. Small observed camera motion retained. Real camera cut after the set at 38.4 s.'),
 'B4I9eBw88yQ':dict(time=19.5,regions=[[766,235,781,255],[694,235,709,255],[781,132,794,149],[689,132,702,149]],support=[[568,128],[832,124]],grip='overhand',active=[19.3,33.7],held=[19.1,33.9],cycles=[[19.8,21.8],[22,23.8],[24,25.8],[28.2,29.8],[30,31.8],[32,33.7]],visible=6,complete=True,role='completeSourceSet',neg=[[0,18.3],[34.3,42]],note='Front, clear athlete in black and coach at left. Three loaded then three unloaded reps. Weight drops around 26 s while hands remain supported. No grip type/width change. Continuous shot, no reset at load change.'),
 'BJ8qpXv3bxk':dict(time=7.5,regions=[[505,219,521,240],[414,219,430,240],[521,103,537,119],[402,103,418,119]],support=[[310,94],[627,94]],grip='underhand',active=[3.4,17],held=[3.4,17],cycles=[[4.6,7.4],[8,10.8],[11.2,14]],visible=5,complete=False,role='partialCycleDiagnostic',neg=[[0,2.5],[18,20]],note='Front, arms/hands clearly visible. Opening already raised, ending cuts off last return/release. Five visible peaks include initial/final incomplete cycles; three complete cycles. Partial-cycle diagnostics only.'),
 '0j9CUk1NjOY':dict(time=3.5,regions=[[721,395,735,413],[588,395,602,413],[766,117,778,135],[557,117,569,135]],support=[[405,95],[907,95]],grip='overhand',active=[3.9,10.1],held=[2.9,12.4],cycles=[[3.9,6.8],[7.3,10.1]],visible=2,complete=False,role='partialCycleDiagnostic',neg=[[0,2.4]],note='Front, visible arms/hands. Two full cycles, source ends hanging without release. Not complete-set qualification. Medium title is not categorical width truth.')}
reasons={
 'V0B0fB47jic':'Rear narrow top positions obscure forearms behind torso/head.',
 'mvvnVXwvLHU':'Substantial side view during exercise.',
 'ZouXLZwAUvs':'Rear close hands/forearms obscured near top; cut-off return/release.',
 'AYa5qc_KAY0':'Oblique rear with self-occlusion; setup/release absent.',
 'OX4Iln6xdgc':'Camera travels side-to-rear, cropped body and cut-off set.',
 'y_Oewv1cHtQ':'320x180 source, tiny hands; rear narrow forearms obscured at top.',
 'RA7ouLeKq2E':'Oblique rear; close-grip top positions obscure forearms/near hand.',
 'x8yB1e5utwo':'Cuts, camera orbit and close-ups crop/obstruct body/arms.',
 'DUZXBwgxeCg':'Strong oblique view, upright obscures one side, dark hands, cut-off set.',
 '8mryJ3w2S78':'Strong oblique rear with top self-occlusion and cut-off final cycle.',
 'aJ376cEUpVY':'Side/oblique rear with far arm obscured.',
 'EEgOZa-xAjw':'Strong side view, hand glare, camera cuts.',
 'TNahREcax6k':'Exercise from side, far arm/hands overlap.',
 'ulzSEd68Fgo':'Gloved demonstration with heavy oblique/side view.'}
cases=[]
for i,a in annotations.items():
    c=dict(by[i]);p=OUT/'source-review-large'/f'{i}-{a["time"]}.png'
    size=list(Image.open(p).size);regions=dict(zip(['leftShoulder','rightShoulder','leftWrist','rightWrist'],a['regions']))
    interval=list(reference.ratio_interval(regions,a['support'],size));band=reference.band(interval)
    truth=dict(sourceImage=str(p),sourceImageSHA256=sha(p),seconds=a['time'],imageSize=size,regions=regions,support=a['support'],ratioInterval=interval,convention='jointCenterRegions_v1',reviewQualification='Agent source-only review, not independent human gold.')
    shot=dict(id='continuous',window=[0,c['duration']+.1],view='front',gripInference=True,fullCaptureSupported=True)
    shots=[shot] if i!='QA5ko5Ibu0g' else [dict(shot,window=[0,38.4]),dict(shot,id='after-real-cut',window=[38.4,c['duration']+.1])]
    c.update(sourceURL=f'https://www.youtube.com/watch?v={i}',sourceGroup=f'youtube:{i}',recordingId=c['sha256'],supported=True,cohortRole=a['role'],confirmedView='front',framesDirectory=str(OUT/'native-rgb'/i),bodyDirectory=str(OUT/'native-body'/i),shots=shots,negativeWindows=a['neg'],sourceFullCycleWindows=a['cycles'],sets=[dict(id='exercise',referenceGrip=a['grip'],referenceWidth=band,reference=truth,activeWindow=a['active'],heldWindow=a['held'],completeSet=a['complete'],sourceVisibleCycles=a['visible'],sourceCompleteCycles=len(a['cycles']))],note=a['note'])
    cases.append(c);print(i,band,interval)
excluded=[dict(by[i],sourceURL=f'https://www.youtube.com/watch?v={i}',sourceGroup=f'youtube:{i}',excludedBeforeInference=True,reason=reason) for i,reason in reasons.items()]
assert len(cases)+len(excluded)==18
refs=dict(schemaVersion=30,qualification='Agent source-only references before inference. Fresh source groups, not athlete-independent or human gold. Exact shared counter/width with source-confirmed view and wrist-seeded reference; offline RGB, not phone acquisition.',allNewSources=19,cases=cases,excludedCases=excluded,acquisitionFailures=[dict(id='XS4mhJuAQ84',reason='YouTube source unavailable.')],unqualifiedGaps=['No accepted real grip type/width change full-set recording; load change is not a grip change.','Boundary-crossing width intervals remain unscorable. Partial diagnostics never qualify complete sets.'])
(RUN/'test').mkdir(parents=True,exist_ok=True);write(RUN/'source-references.json',refs)
paths=[RUN/'source-references.json',OUT/'protocol.json',OUT/'acquisition-amendment.json',OUT/'acquisition-completion-amendment.json',Path(__file__)]+[Path(c['path']) for c in cases+excluded]+[Path(c['sets'][0]['reference']['sourceImage']) for c in cases]
for directory in ['source-review','source-review-dense','source-review-large']:paths+=list((OUT/directory).glob('*.png'))
write(OUT/'reference-freeze.json',dict(frozenAt=datetime.datetime.now(datetime.timezone.utc).isoformat(),stage='Before all pose and learned inference.',sha256={str(p):sha(p) for p in paths}))
paths=[ROOT/'scripts/evaluate-grip-fullsets-v30.py',ROOT/'build/grip-training/exporter/.build/release/grip-frames',ROOT/'build/dip-body-motion/verified/exporter/.build/release/dip-body-export']
write(OUT/'implementation-freeze.json',dict(sha256={str(p):sha(p) for p in paths}))
write(OUT/'source-review-correction.json',dict(performed=False,note='Compatibility field for unchanged v27 accounting; no reference correction.'))
