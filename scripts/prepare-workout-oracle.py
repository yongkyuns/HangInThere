#!/usr/bin/env python3
"""Generate original analytic inputs; Swift production engine supplies all snapshots.

No recordings, model predictions or private user data are inputs to these fixtures.
Explicit movement labels guard against two engines sharing the same counting bug.
"""
import argparse
import json
import math
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]

def pose(t, degrees=170, exercise='pullUp', right=None, missing=False, people=1,
         confidence=1, rotation=0, scale=1, timescale=1000):
    landmarks = []
    for side, x, angle in [('left', 300, degrees), ('right', 700, right if right is not None else degrees)]:
        half = math.radians(angle / 2)
        y = 120 * math.sin(half) * (1 if exercise == 'pullUp' else -1)
        for joint, px, py in [('Shoulder', x, 500+2*y), ('Elbow', x+120*math.cos(half), 500+y), ('Wrist', x, 500)]:
            if missing and side == 'right': continue
            c, s = math.cos(rotation), math.sin(rotation)
            landmarks.append(dict(joint=side+joint, position=dict(x=(c*(px-500)-s*(py-500)+500)*scale,
                                                                 y=(s*(px-500)+c*(py-500)+500)*scale), confidence=confidence))
    return dict(timestamp=dict(value=round(t*1000), timescale=timescale),
                imageSize=dict(width=1000*scale, height=1000*scale),
                people=[dict(landmarks=landmarks)]*people, backend='original-analytic-fixture', requestRevision=0)

def prepare(output):
    cases = []
    def add(name, exercise, frames, expected, **kw):
        rotation, scale = kw.get('rotation',0), kw.get('scale',1)
        c,s=math.cos(rotation),math.sin(rotation)
        def pt(x,y):return dict(x=(c*(x-500)-s*(y-500)+500)*scale,y=(s*(x-500)+c*(y-500)+500)*scale)
        edge = dict(a=pt(100,500),b=pt(900,500)) if exercise=='pullUp' else dict(a=pt(300,480),b=pt(300,620))
        steps = [dict(interrupt=f) if isinstance(f,str) else dict(pose=pose(f[0],f[1],exercise=exercise,**kw)) for f in frames]
        cases.append(dict(id=name,exercise=exercise,side='left',referenceEdge=edge,expectedMovements=expected,steps=steps))
    cycle=[(0,170),(.15,170),(.3,120),(.45,80),(.6,80),(.75,170),(.9,170)]
    for ex in ['pullUp','dip']:
        add(ex+'-one',ex,cycle,1)
        add(ex+'-two',ex,cycle+[(t+1.05,a) for t,a in cycle],2)
        add(ex+'-rotated',ex,cycle,1,rotation=.6)
        add(ex+'-scaled',ex,cycle,1,scale=.32)
        add(ex+'-static',ex,[(i*.15,170) for i in range(20)],0)
        add(ex+'-partial',ex,[(0,170),(.15,170),(.3,125),(.45,170),(.6,170)],0)
        add(ex+'-interrupted',ex,cycle[:3]+['inferenceFailure']+[(.6,80),(.75,80)],0)
        add(ex+'-gap',ex,[(0,170),(.15,170),(1.2,80),(1.35,80)],0)
        add(ex+'-crowd',ex,cycle,0,people=2)
        add(ex+'-confidence',ex,cycle,0,confidence=.1)
        add(ex+'-duplicate-time',ex,[(0,170),(0,170),(.15,80),(.3,80)],0)
        add(ex+'-invalid-time',ex,cycle,0,timescale=0)
    add('pullUp-opposite-still', 'pullUp', cycle,0,right=170)
    add('pullUp-single-visible', 'pullUp',cycle,1,missing=True)
    add('dip-opposite-still', 'dip',cycle,0,right=170)
    output.mkdir(parents=True,exist_ok=True)
    inputs=output/'inputs.json';inputs.write_text(json.dumps(cases,allow_nan=False))
    binary=output/'swift-workout-oracle'
    names=['PoseObservation','Geometry','BarGeometry','ArmMeasurement','PullUpArmMeasurement',
           'DipSupportMeasurement','PullUpProgress','DipProgress','ExerciseCounter']
    subprocess.run(['swiftc','-O','-o',str(binary)]+[str(ROOT/'HangInThere/Analysis'/f'{x}.swift') for x in names]+[str(ROOT/'Evaluation/WorkoutOracle.swift')],check=True)
    temporary=output/'oracle.json.tmp'
    with temporary.open('w') as out: subprocess.run([str(binary),str(inputs)],stdout=out,check=True)
    temporary.replace(output/'oracle.json')
    print(f'Generated {len(cases)} original cases: {output / "oracle.json"}')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=ROOT/'build/workout-oracle')
    prepare(p.parse_args().output.resolve())
