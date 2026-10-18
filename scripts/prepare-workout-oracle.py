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
    def add(name, exercise, frames, expected, *, side='left', expected_partial=None, **kw):
        rotation, scale = kw.get('rotation',0), kw.get('scale',1)
        c,s=math.cos(rotation),math.sin(rotation)
        def pt(x,y):return dict(x=(c*(x-500)-s*(y-500)+500)*scale,y=(s*(x-500)+c*(y-500)+500)*scale)
        wrist_x = 300 if side == 'left' else 700
        edge = dict(a=pt(100,500),b=pt(900,500)) if exercise=='pullUp' else dict(a=pt(wrist_x,480),b=pt(wrist_x,620))
        steps = [dict(interrupt=f) if isinstance(f,str) else
                 dict(pose=f if isinstance(f,dict) else pose(f[0],f[1],exercise=exercise,**kw)) for f in frames]
        case = dict(id=name,exercise=exercise,side=side,referenceEdge=edge,expectedMovements=expected,steps=steps)
        if expected_partial is not None: case['expectedPartialAttempts'] = expected_partial
        cases.append(case)
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
    # A fleeting upper return is not a completed endpoint. A second observed
    # descent must close that attempt before it can contaminate another rep.
    for side in ('left', 'right'):
        frames = [(0,170),(.15,170),(.3,80),(.45,80),(.6,170),(.65,140),(.7,80),
                  (.75,80),(.9,170),(1.05,170),(1.2,80),(1.35,80),(1.5,170),(1.65,170)]
        add('dip-unconfirmed-return-reacquisition-'+side, 'dip', frames, 1, side=side, expected_partial=0)
        phases = ['seekingStart','ready','outbound','returning','returning','returning','seekingStart',
                  'seekingStart','seekingStart','ready','outbound','returning','returning','ready']
        for index, (step, phase) in enumerate(zip(cases[-1]['steps'], phases)):
            step.update(expectedPhase=phase, expectedMovements=int(index == len(phases) - 1))
        partial_frames = frames[:2] + [(.3,110),(.45,110)] + frames[4:]
        add('dip-unconfirmed-return-partial-reacquisition-'+side, 'dip', partial_frames, 1, side=side, expected_partial=0)
        partial_phases = phases[:3] + ['outbound','outbound','outbound'] + phases[6:]
        for index, (step, phase) in enumerate(zip(cases[-1]['steps'], partial_phases)):
            step.update(expectedPhase=phase, expectedMovements=int(index == len(partial_phases) - 1))
        for kind, tail in [('upper-jitter',[(.6,170),(.65,160),(.75,170)]),
                           ('sub-top-bounce',[(.6,120),(.75,80),(.9,170),(1.05,170)])]:
            add('dip-unconfirmed-return-'+kind+'-'+side, 'dip', frames[:4]+tail, 1, side=side, expected_partial=0)
    # Height and angle vary independently. No synthetic maximum may substitute
    # for one actually observed upper-return witness.
    def dip_return_pose(t, degrees, side, drop=0, visible=True, span=None, bilateral=False):
        frame = pose(t, 170, exercise='dip')
        points = {p['joint']:p for p in frame['people'][0]['landmarks']}
        for arm in (('left','right') if bilateral else (side,)):
            shoulder = points[arm+'Shoulder']['position']
            wrist = points[arm+'Wrist']['position']
            shoulder['y'] = (shoulder['y'] if span is None else wrist['y']-span)+drop
            elbow = points[arm+'Elbow']
            elbow['position'] = dict(x=wrist['x']+(wrist['y']-shoulder['y'])/(2*math.tan(math.radians(degrees/2))),
                                     y=(shoulder['y']+wrist['y'])/2)
            elbow['confidence'] = 1 if visible else .1
        return frame
    for side in ('left','right'):
        baseline = [pose(t,a,exercise='dip') for t,a in [(0,170),(.15,170),(.3,80),(.45,80)]]
        for label, returns in [('same-height',[(150,0),(170,0)]),
                               ('higher-angle-lower',[(150,0),(170,10)]),
                               ('higher-angle-first',[(170,10),(150,0)])]:
            frames = baseline+[dip_return_pose(.6+i*.05,a,side,drop) for i,(a,drop) in enumerate(returns)]
            frames += [dip_return_pose(.67,170,side,visible=False),dip_return_pose(.7,140,side,40)]
            add('dip-return-witness-'+label+'-'+side,'dip',frames,0,side=side,expected_partial=0)
            cases[-1]['steps'][-1]['expectedPhase'] = 'seekingStart'
        frames = baseline+[dip_return_pose(t,a,side,drop) for t,a,drop in
            [(.6,150,0),(.65,170,10),(.7,140,25),(.85,170,10),(1,170,10)]]
        add('dip-return-no-synthetic-maxima-'+side,'dip',frames,1,side=side,expected_partial=0)
        frames = baseline+[dip_return_pose(.6+i*.001,151+i*.1,side,i*.05) for i in range(65)]
        add('dip-return-bounded-history-'+side,'dip',frames,0,side=side,expected_partial=0)
        cases[-1]['steps'][-1]['expectedPhase'] = 'seekingStart'
        frames = baseline+[pose(t,a,exercise='dip') for t,a in
            [(.6,170),(.6,170),(.8,170),(.95,170),(1.1,80),(1.25,80),(1.4,170),(1.55,170)]]
        add('dip-return-source-reset-'+side,'dip',frames,1,side=side,expected_partial=0)
        frames = [dip_return_pose(t,a,side,drop,span=100,bilateral=True) for t,a,drop in
            [(0,170,0),(.15,170,0),(.3,150,10),(.34,150,10),(.38,130,20),(.53,130,20),(.6,170,0),(.75,170,0)]]
        add('dip-departure-plateau-'+side,'dip',frames,1,side=side,expected_partial=0)
        frames = [dip_return_pose(t,a,side,drop,span=100,bilateral=True) for t,a,drop in
            [(0,170,0),(.15,170,0),(.3,150,15),(.34,130,15),(.38,150,0),(.42,130,20),
             (.57,130,20),(.7,170,0),(.85,170,0),(.99,130,20),(1.14,130,20),(1.29,170,0),(1.44,170,0)]]
        add('dip-outbound-low-witness-'+side,'dip',frames,1,side=side,expected_partial=0)
        cases[-1]['steps'][5]['expectedPhase'] = 'seekingStart'
        cases[-1]['steps'][5]['expectedMovements'] = 0
        frames = [dip_return_pose(t,a,side,drop,span=100,bilateral=True) for t,a,drop in
            [(0,170,0),(.15,170,0),(.3,150,20),(.34,130,15),(.38,150,7),(.42,130,20),(.57,130,20),(.7,170,0),(.85,170,0)]]
        add('dip-outbound-no-combined-lows-'+side,'dip',frames,1,side=side,expected_partial=0)
    # Independent labels for frontal projection: shorter projected segments can
    # accompany meaningful shoulder travel while every elbow stays above 120°.
    def projected_dip(t, degrees, arm_scale, right=None):
        frame = pose(t,degrees,exercise='dip',right=right)
        points = frame['people'][0]['landmarks']
        wrists = {p['joint'][:-5]:dict(p['position']) for p in points if p['joint'].endswith('Wrist')}
        for point in points:
            side = 'left' if point['joint'].startswith('left') else 'right'
            wrist = wrists[side]
            point['position'] = {axis:wrist[axis]+(point['position'][axis]-wrist[axis])*arm_scale for axis in ('x','y')}
        return frame
    for side in ('left','right'):
        cycles = [projected_dip(rep+t,a,scale) for rep in range(3)
                  for t,a,scale in [(0,170,1),(.15,170,1),(.3,156,.95),(.45,127,.7),(.6,127,.7),(.75,170,1),(.9,170,1)]]
        cycles += [projected_dip(t,125,.7) for t in (3,3.15)]
        add('dip-projected-three-and-incomplete-'+side,'dip',cycles,3,side=side,expected_partial=0)
        for kind in ('insufficient-travel','opposite-still'):
            frames = [projected_dip(t,170,1) for t in (0,.15)]
            left = 170 if kind=='opposite-still' and side=='right' else 125
            right = 170 if kind=='opposite-still' and side=='left' else 125
            frames += [projected_dip(t,left,1 if kind=='insufficient-travel' else .7,right) for t in (.3,.45)]
            frames += [projected_dip(t,170,1) for t in (.6,.75)]
            add('dip-projected-'+kind+'-'+side,'dip',frames,0,side=side,expected_partial=1)
    # Reliable shoulders/wrists do not keep an expired extension witness alive.
    # Explicit one-cycle labels prevent two engines from sharing a recovery bug.
    def elbow_hole(t):
        frame = pose(t,140,exercise='dip')
        for landmark in frame['people'][0]['landmarks']:
            if landmark['joint'].endswith('Elbow'): landmark['confidence'] = .1
        return frame
    expired_start = [(0,140)] + [elbow_hole(i / 10) for i in range(1,10)]
    add('dip-start-expired-elbow-recovery','dip',expired_start +
        [(1,140),(1.15,140),(1.3,70),(1.45,70),(1.6,140),(1.75,140)],1)
    add('dip-start-expired-witness-needs-extension','dip',expired_start +
        [(1,130),(1.15,130),(1.3,130),(1.45,140),(1.6,140),
         (1.75,70),(1.9,70),(2.05,140),(2.2,140)],1)
    add('dip-start-elbow-outage-boundary','dip',[(0,140)] +
        [elbow_hole(i / 10) for i in range(1,8)] +
        [(.75,140),(.9,70),(1.05,70),(1.2,140),(1.35,140)],1)
    # Contradictory actual arm evidence revokes a top witness, independently of
    # the shoulder/wrist geometry. Missing elbows alone may still sustain one.
    def dip_frame(t, degrees, side, *, top=False, visible=True):
        frame = pose(t,170 if top else degrees,exercise='dip')
        points = [p for p in frame['people'][0]['landmarks'] if p['joint'].startswith(side)]
        if top:
            shoulder, elbow, wrist = [p['position'] for p in points]
            elbow.update(x=wrist['x']+(wrist['y']-shoulder['y'])/(2*math.tan(math.radians(degrees/2))),
                         y=(shoulder['y']+wrist['y'])/2)
        if not visible: points[1]['confidence'] = .1
        frame['people'][0]['landmarks'] = points
        return frame
    for side in ('left','right'):
        for kind, bottom, contradiction in [('cycle',80,140),('partial',110,110)]:
            for ending in ('witness','contradicted','reacquired'):
                frames = [dip_frame(t,a,side) for t,a in [(0,170),(.15,170),(.3,bottom),(.45,bottom)]]
                frames.append(dip_frame(.6,170,side,top=True))
                if ending != 'witness': frames.append(dip_frame(.65,contradiction,side,top=True))
                frames.append(dip_frame(.8,170,side,top=True,visible=False))
                if ending != 'witness': frames.append(dip_frame(.95,170,side,top=True,visible=False))
                if ending == 'reacquired':
                    frames += [dip_frame(1.1,170,side,top=True),dip_frame(1.25,170,side,top=True,visible=False)]
                returned = ending != 'contradicted'
                add(f'dip-return-{kind}-{side}-{ending}','dip',frames,int(kind=='cycle' and returned),
                    side=side,expected_partial=int(kind=='partial' and returned))
    # Explicit per-frame phase/count labels protect both engines independently:
    # exact source boundaries pass, one-tick-short dwell and over-limit gaps fail.
    def add_clock(name, exercise, side, origin, offsets, phases, movement_at):
        frames = []
        counts = []
        for offset, degrees in offsets:
            frame = pose(0,degrees,exercise=exercise)
            frame['timestamp'] = dict(value=origin+offset,timescale=1_000_000)
            frame['people'][0]['landmarks'] = [p for p in frame['people'][0]['landmarks']
                                              if p['joint'].startswith(side)]
            frames.append(frame)
            counts.append(int(movement_at is not None and offset >= movement_at))
        add(f'source-clock-{exercise}-{side}-{name}',exercise,frames,counts[-1],side=side,expected_partial=0)
        for step, phase, count in zip(cases[-1]['steps'],phases,counts):
            step.update(expectedPhase=phase,expectedMovements=count)
    for exercise in ('pullUp','dip'):
        for side in ('left','right'):
            for name, origin in [('ordinary',1_100_000),('large',9_007_199_254_740_992)]:
                movement_at = 360_000 if exercise=='pullUp' else 600_000
                add_clock(name+'-exact',exercise,side,origin,
                    [(0,170),(120_000,170),(240_000,80),(360_000,80),(480_000,170),(600_000,170)],
                    ['seekingStart','ready','outbound','returning','returning','ready'],movement_at)
                add_clock(name+'-short',exercise,side,origin,
                    [(0,170),(119_999,170),(120_000,170),(240_000,80),(359_999,80),
                     (360_000,80),(480_000,170),(599_999,170),(600_000,170)],
                    ['seekingStart','seekingStart','ready','outbound','outbound',
                     'returning','returning','returning','ready'],movement_at)
            for excess, kind, end in [(0,'gap','returning'),(1,'gap-over','seekingStart')]:
                # A dip has only reached bottom here; it cannot earn a rep.
                add_clock(kind,exercise,side,0,
                    [(0,170),(150_000,170),(300_000,80),(650_000+excess,80)],
                    ['seekingStart','ready','outbound',end],
                    650_000 if exercise=='pullUp' and excess==0 else None)
    output.mkdir(parents=True,exist_ok=True)
    inputs=output/'inputs.json';inputs.write_text(json.dumps(cases,allow_nan=False))
    binary=output/'swift-workout-oracle'
    # Match the other native harnesses: this layer contains SDK-free policy and
    # transport types, including optional metadata referenced by PoseResult.
    sources=sorted((ROOT/'ios/HangInThere/Analysis').glob('*.swift'))
    subprocess.run(['swiftc','-O','-o',str(binary)]+list(map(str,sources))+[str(ROOT/'Evaluation/WorkoutOracle.swift')],check=True)
    temporary=output/'oracle.json.tmp'
    with temporary.open('w') as out: subprocess.run([str(binary),str(inputs)],stdout=out,check=True)
    temporary.replace(output/'oracle.json')
    print(f'Generated {len(cases)} original cases: {output / "oracle.json"}')

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,default=ROOT/'build/workout-oracle')
    prepare(p.parse_args().output.resolve())
