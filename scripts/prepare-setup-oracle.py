#!/usr/bin/env python3
"""Analytical and frozen native setup evidence with acquisition/start labels.

Uses production Swift setup policies as oracle. Frozen public-source native
observations are separately identified policy regressions, not new inference
or an accuracy label. No private recordings or Kotlin-generated expectations.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('workout_fixtures', ROOT/'scripts/prepare-workout-oracle.py')
fixtures = importlib.util.module_from_spec(spec)
spec.loader.exec_module(fixtures)

def candidate(x0, y0, x1, y1):
    return dict(edge=dict(a=dict(x=x0,y=y0),b=dict(x=x1,y=y1)),geometryScore=((x1-x0)**2+(y1-y0)**2)**.5)

def step(t, exercise, **kw):
    pose = fixtures.pose(t, kw.pop('degrees',170), exercise=exercise, **kw.pop('pose_options',{}))
    # Crossed legs remain optional intent evidence, never an apparatus reference.
    if kw.pop('crossed_legs',False):
        pose['people'][0]['landmarks'] += [dict(joint=j,position=dict(x=x,y=y),confidence=1)
            for j,x,y in [('leftKnee',450,850),('rightKnee',550,850),('leftAnkle',550,940),('rightAnkle',450,940)]]
    edges = [[candidate(200,495,800,495)]] if exercise=='pullUp' else [[candidate(x,545,x,660),candidate(x+8,545,x+8,660)] for x in [300,700]]
    return dict(pose=pose,candidates=kw.pop('candidates',edges),attitude=kw.pop('attitude',dict(x=0,y=0,z=0,w=1)),
                translations=kw.pop('translations',[dict(dxPixels=0,dyPixels=0),dict(dxPixels=.1,dyPixels=.1),dict(dxPixels=-.1,dyPixels=-.1)]),
                globalScaleFraction=kw.pop('scale',0),eligible=kw.pop('eligible',True),paused=kw.pop('paused',False))

def prepare(output):
    cases=[]
    def add(name, exercise, steps, acquired, started,side='left'):
        cases.append(dict(id=name,exercise=exercise,side=side,expectedAcquired=acquired,expectedStarted=started,steps=steps))
    for ex in ['pullUp','dip']:
        frames=lambda **kw: [step(i/10,ex,**kw) for i in range(56)]
        add(ex+'-observed-start',ex,frames(),True,True)
        add(ex+'-pose-only',ex,frames(candidates=[]),False,False)
        add(ex+'-unavailable-sensor',ex,frames(attitude=None),False,False)
        add(ex+'-scale-missing',ex,frames(scale=None),True,False)
        add(ex+'-one-scene-patch',ex,frames(translations=[dict(dxPixels=0,dyPixels=0)]),True,False)
        # A formerly qualified background cannot stand in for a lost current
        # signal during countdown. These labels are authored contract cases.
        for name,missing in [('scale',dict(scale=None)),
                             ('patches',dict(translations=[]))]:
            lost=frames()
            lost[15:]=[step(i/10,ex,**missing) for i in range(15,56)]
            add(ex+'-scene-'+name+'-lost-after-stable',ex,lost,True,False)
            recovered=lost+[step(i/10,ex) for i in range(56,96)]
            add(ex+'-scene-'+name+'-recovers',ex,recovered,True,True)
        add(ex+'-settings-paused',ex,frames(paused=True),True,False)
        add(ex+'-ineligible',ex,frames(eligible=False),True,False)
        add(ex+'-crowd',ex,frames(pose_options=dict(people=2)),False,False)
        add(ex+'-one-arm',ex,frames(pose_options=dict(missing=True)),False,False)
        add(ex+'-low-confidence',ex,frames(pose_options=dict(confidence=.1)),False,False)
        add(ex+'-duplicate-time',ex,[step(0,ex) for _ in range(56)],False,False)
        add(ex+'-source-gaps',ex,[step(i*.6,ex) for i in range(30)],False,False)
        add(ex+'-invalid-time',ex,frames(pose_options=dict(timescale=0)),False,False)
        patches=[dict(dxPixels=20,dyPixels=0) for _ in range(3)]
        add(ex+'-scene-translated',ex,frames(translations=patches),True,False)
        add(ex+'-scene-scaled',ex,frames(scale=.03),True,False)
        moved=frames()
        for s in moved[15:]: s['attitude']=dict(x=0,y=0,z=.01745240643728351,w=.9998476951563913)
        add(ex+'-phone-rotated',ex,moved,True,False)
        outlier=[dict(dxPixels=0,dyPixels=0),dict(dxPixels=.1,dyPixels=.1),dict(dxPixels=-.1,dyPixels=-.1),dict(dxPixels=900,dyPixels=900)]
        add(ex+'-scene-outlier',ex,frames(translations=outlier),True,True)
    add('dip-crossed-legs-optional','dip',[step(i/10,'dip',crossed_legs=True) for i in range(56)],True,True)
    add('dip-right-reference','dip',[step(i/10,'dip') for i in range(56)],True,True,side='right')
    shared=[candidate(200,y,800,y) for y in [505,515]]
    shared_cases=[
        ('observed',shared,True),
        ('single-edge',shared[:1],False),
        ('short-pair',[shared[0],candidate(280,515,400,515)],False),
        ('ambiguous',shared+[candidate(200,y,800,y) for y in [555,565]],False),
        ('above-grip',[candidate(200,y-65,800,y-65) for y in [505,515]],False),
        ('forearm',[candidate(300,480,300,660),candidate(310,480,310,660)],False),
    ]
    for side in ['left','right']:
        for name,edges,accepted in shared_cases:
            add('dip-shared-bar-'+name+'-'+side,'dip',
                [step(i/10,'dip',candidates=[[],[],edges]) for i in range(56)],accepted,accepted,side=side)
        # Alternating a lone silhouette with a pair cannot accumulate fresh
        # paired support. The ordinary two-rail dwell retains priority when
        # both modes are visible; its selected reference must remain unchanged.
        add('dip-shared-bar-missing-pair-'+side,'dip',
            [step(i/10,'dip',candidates=[[],[],shared if i%2 else shared[:1]]) for i in range(56)],
            False,False,side=side)
        add('dip-parallel-before-shared-bar-'+side,'dip',
            [step(i/10,'dip',candidates=step(0,'dip')['candidates']+[shared]) for i in range(56)],
            True,True,side=side)
    single=[[candidate(x,545,x,660)] for x in [300,700]]
    add('dip-single-edges-are-not-rails','dip',[step(i/10,'dip',candidates=single) for i in range(56)],False,False)
    forearms=[[candidate(x,480,x,660),candidate(x+8,480,x+8,660)] for x in [300,700]]
    add('dip-forearm-silhouettes','dip',[step(i/10,'dip',candidates=forearms) for i in range(56)],False,False)
    partial=[step(i/10,'dip') for i in range(56)]
    # Preserve a paired observed handle through later partial occlusion.
    for s in partial[3:]:s['candidates']=[[candidate(x,550,x,650)] for x in [300,700]]
    add('dip-paired-witness-occlusion','dip',partial,True,True)
    # Pair centerlines can pass the grip gate while the longer selected edge
    # fails it. Such a pair must not outrank a wholly eligible observed rail.
    unreachable=[]
    for hand,outward in [(300,-1),(700,1)]:
        unreachable.append([candidate(hand+outward*offset,540,hand+outward*offset,540+length)
            for offset,length in [(118,150),(88,140)]])
    valid=step(0,'dip')['candidates']
    for side in ['left','right']:
        mixed=[bad+good for bad,good in zip(unreachable,valid)]
        add('dip-ineligible-pair-before-valid-'+side,'dip',
            [step(i/10,'dip',candidates=mixed) for i in range(56)],True,True,side=side)
    add('dip-unreachable-selected-edges','dip',
        [step(i/10,'dip',candidates=unreachable) for i in range(56)],False,False)
    # A grasp can hide the top of the handle. Longer offset tubes also have
    # closer endpoints; finite supporting-line alignment must rank the grasp.
    wrists=[landmark['position'] for landmark in step(0,'dip')['pose']['people'][0]['landmarks']
            if landmark['joint'] in ['leftWrist','rightWrist']]
    aligned=[]
    for hand in wrists:
        x,y=hand['x'],hand['y']
        clutter=[candidate(px,y+40,px,y+190) for px in [x-40,x-30]]
        handle=[candidate(px,y+70,px,y+190) for px in [x-10,x]]
        aligned.append(clutter+handle)
    for side in ['left','right']:
        add('dip-wrist-aligned-before-longer-clutter-'+side,'dip',
            [step(i/10,'dip',candidates=aligned) for i in range(56)],True,True,side=side)
    def transverse(slope=0, offset=0, opposite=False, reversed=False, right_slope=None):
        rails=[]
        for index,x in enumerate([300,700]):
            lines=[]
            for edge_index,height in enumerate([0,10]):
                half=55 if opposite and index==edge_index else 50
                direction=right_slope if index==1 and right_slope is not None else slope
                def y(px): return 550+slope*(x-300)+direction*(px-x)+height+(offset if index==1 else 0)
                endpoints=(x-half,y(x-half),x+half,y(x+half))
                if reversed:endpoints=endpoints[2:]+endpoints[:2]
                lines.append(candidate(*endpoints))
            rails.append(lines)
        return rails
    for name,edges in [
        ('horizontal',transverse()),('slanted',transverse(slope=.03)),
        ('opposite-silhouettes',transverse(opposite=True)),
        ('reversed',transverse(slope=.03,opposite=True,reversed=True))]:
        add('dip-one-tube-'+name,'dip',[step(i/10,'dip',candidates=edges) for i in range(56)],False,False)
    for name,edges in [('parallel',transverse(offset=45,opposite=True)),
                       ('perspective',transverse(slope=.03,offset=45,opposite=True,right_slope=-.02))]:
        add('dip-distinct-transverse-'+name,'dip',[step(i/10,'dip',candidates=edges) for i in range(56)],True,True)
    replaced=[step(i/10,'dip',candidates=transverse(offset=45) if i<3 else transverse(opposite=True)) for i in range(56)]
    add('dip-witness-replaced-by-one-tube','dip',replaced,False,False)
    ambiguous=[[candidate(200,450,800,450),candidate(200,495,800,495)]]
    add('pullUp-two-separate-bars','pullUp',[step(i/10,'pullUp',candidates=ambiguous) for i in range(56)],False,False)
    thin=[[candidate(200,495,800,495),candidate(200,500,800,500)]]
    add('pullUp-thin-parallel-bar','pullUp',[step(i/10,'pullUp',candidates=thin) for i in range(56)],True,True)
    # Exact authored hold duration, with a nonzero source-clock origin. Source
    # ticks must not turn 0.9s into an extra-observation requirement by rounding.
    add('pullUp-exact-source-dwell','pullUp',
        [step(t,'pullUp') for t in [3.0,3.3,3.6,3.9]],True,False)
    short_dwell=[step(t,'pullUp') for t in [3.0,3.3,3.6,3.899999]]
    # This new boundary case needs finer ticks than the existing ms fixtures.
    for frame,ticks in zip(short_dwell,[3_000_000,3_300_000,3_600_000,3_899_999]):
        frame['pose']['timestamp']=dict(value=ticks,timescale=1_000_000)
    add('pullUp-one-microsecond-short-source-dwell','pullUp',short_dwell,False,False)
    settling=[step(i/10,'pullUp',degrees=145 if i<10 else 170) for i in range(66)]
    add('pullUp-hang-settles-before-countdown','pullUp',settling,True,True)
    # Once a hang qualifies identity, fresh overhead hands still identify the
    # exercise during flexion. Observed apparatus may settle during that motion,
    # but flexion cannot start a live set or substitute for a new full hang.
    moving=[step(i/10,'pullUp',degrees=170 if i<7 else 90) for i in range(56)]
    add('pullUp-qualified-identity-through-flexion','pullUp',moving,True,False)
    add('pullUp-flexion-then-fresh-hang-start','pullUp',
        moving+[step(i/10,'pullUp') for i in range(56,96)],True,True)
    add('pullUp-flexion-identity-without-apparatus','pullUp',
        [step(i/10,'pullUp',degrees=170 if i<7 else 90,candidates=[]) for i in range(56)],False,False)
    # Preserve the actual failed public held-dip observations independently of
    # the unchanged positive native fixture. Projected foreground tube fragments
    # must never become two rails or earn countdown through a geometry relaxation.
    native=json.loads((ROOT/'Evaluation/fixtures/native-dip-overlap.json').read_text())
    assert native['schemaVersion']==1 and len(native['steps'])==72
    for side in ['left','right']:
        add('dip-native-overlap-'+side,'dip',native['steps'],False,False,side=side)
    output.mkdir(parents=True,exist_ok=True)
    inputs=output/'inputs.json';inputs.write_text(json.dumps(cases,allow_nan=False))
    geometry_cases=[]
    def geo(name, contours, region=(0,0,200,200), size=(200,200)):
        geometry_cases.append(dict(id=name,contours=[[dict(x=x,y=y) for x,y in c] for c in contours],
            region=dict(zip(['minX','minY','maxX','maxY'],region)),size=dict(width=size[0],height=size[1])))
    geo('fragmented-horizontal',[[(20,25),(80,25)],[(90,25),(170,25)]])
    geo('fragmented-vertical',[[(25,20),(25,80)],[(25,90),(25,170)]])
    geo('tube-contours',[[(25,20),(25,170),(33,170),(33,20)]])
    geo('horizontal-tube',[[(20,25),(170,25),(170,33),(20,33)]])
    geo('reversed-endpoints',[[(170,25),(90,25)],[(80,25),(20,25)]])
    geo('parallel-independent',[[(20,25),(170,25)],[(20,70),(170,70)]])
    geo('crop-border-not-evidence',[[(0,0),(200,0),(200,200),(0,200)]])
    geo('crooked-observed-fragments',[[(20,25),(70,25.4),(120,24.8),(170,25.3)]])
    geo('outside-points',[[(20,25),(250,25)]])
    geo('invalid-region',[[(20,25),(170,25)]],region=(0,0,250,200))
    geo('duplicate-contour',[[(20,25),(170,25)],[(20,25),(170,25)]])
    geo('crossing-contours',[[(20,20),(170,30)],[(20,30),(170,20)]])
    geo('near-vertical-tube-directions',[[(100,20),(102,180),(106,180),(108,20)]])
    geo('near-vertical-fragment-noise',[[(100,20),(101,90)],[(101,100),(100,180)]])
    geo('thin-observed-tube',[[(20,50),(470,50)],[(20,52.5),(470,52.5)]],region=(0,0,500,500),size=(500,500))
    geo('tapered-shared-support',[[(20,50),(320,50)],[(270,53.2),(470,49.2)]],region=(0,0,500,500),size=(500,500))
    geo('noisy-disjoint-fragments',[[(20,100),(200,100)],[(210,104),(470,104)]],region=(0,0,500,500),size=(500,500))
    geometry_inputs=output/'geometry-inputs.json';geometry_inputs.write_text(json.dumps(geometry_cases,allow_nan=False))
    sources=sorted((ROOT/'ios/HangInThere/Analysis').glob('*.swift'))
    binary=output/'swift-setup-oracle'
    subprocess.run(['swiftc','-O','-o',str(binary)]+list(map(str,sources))+[str(ROOT/'Evaluation/SetupOracle.swift')],check=True)
    pending=output/'oracle.json.pending'
    with pending.open('w') as out:subprocess.run([str(binary),str(inputs),str(geometry_inputs)],stdout=out,check=True)
    pending.replace(output/'oracle.json')
    print(f'Generated {len(cases)} setup cases (including 2 frozen native regressions) + {len(geometry_cases)} observed geometry cases: {output / "oracle.json"}')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'build/setup-oracle')
    prepare(parser.parse_args().output.resolve())
