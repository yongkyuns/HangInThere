#!/usr/bin/env python3
"""Original analytical setup evidence with explicit acquisition/start labels.

Uses the unchanged native Swift setup policies as oracle. No private recordings,
model outputs, constant camera eligibility, or Kotlin-generated expectations.
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
    single=[[candidate(x,545,x,660)] for x in [300,700]]
    add('dip-single-edges-are-not-rails','dip',[step(i/10,'dip',candidates=single) for i in range(56)],False,False)
    forearms=[[candidate(x,480,x,660),candidate(x+8,480,x+8,660)] for x in [300,700]]
    add('dip-forearm-silhouettes','dip',[step(i/10,'dip',candidates=forearms) for i in range(56)],False,False)
    partial=[step(i/10,'dip') for i in range(56)]
    # Preserve a paired observed handle through later partial occlusion.
    for s in partial[3:]:s['candidates']=[[candidate(x,550,x,650)] for x in [300,700]]
    add('dip-paired-witness-occlusion','dip',partial,True,True)
    ambiguous=[[candidate(200,450,800,450),candidate(200,495,800,495)]]
    add('pullUp-two-separate-bars','pullUp',[step(i/10,'pullUp',candidates=ambiguous) for i in range(56)],False,False)
    thin=[[candidate(200,495,800,495),candidate(200,500,800,500)]]
    add('pullUp-thin-parallel-bar','pullUp',[step(i/10,'pullUp',candidates=thin) for i in range(56)],True,True)
    settling=[step(i/10,'pullUp',degrees=145 if i<10 else 170) for i in range(66)]
    add('pullUp-hang-settles-before-countdown','pullUp',settling,True,True)
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
    geometry_inputs=output/'geometry-inputs.json';geometry_inputs.write_text(json.dumps(geometry_cases,allow_nan=False))
    names=['PoseObservation' ,'Geometry','BarGeometry','ArmMeasurement','PullUpArmMeasurement','DipSupportMeasurement',
        'PullUpProgress','DipProgress','ExerciseCounter','AutomaticPullUpBar','AutomaticDipRails','AutomaticExerciseDetection',
        'HandsFreeSetStart','PhoneOrientationStability','StaticSceneStability']
    binary=output/'swift-setup-oracle'
    subprocess.run(['swiftc','-O','-o',str(binary)]+[str(ROOT/'HangInThere/Analysis'/f'{name}.swift') for name in names]+[str(ROOT/'Evaluation/SetupOracle.swift')],check=True)
    pending=output/'oracle.json.pending'
    with pending.open('w') as out:subprocess.run([str(binary),str(inputs),str(geometry_inputs)],stdout=out,check=True)
    pending.replace(output/'oracle.json')
    print(f'Generated {len(cases)} original setup cases + {len(geometry_cases)} observed geometry cases: {output / "oracle.json"}')

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,default=ROOT/'build/setup-oracle')
    prepare(parser.parse_args().output.resolve())
