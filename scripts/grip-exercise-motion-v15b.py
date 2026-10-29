#!/usr/bin/env python3
"""Per-arm revision after v15's disclosed negative run; no image/contact inputs.

Hips and the other arm are optional. A visible shoulder-elbow-wrist chain must
earn its own extended baseline, fixed-wrist torso rise and elbow flexion.
This is an exposed-regression revision, not a second blind experiment.
"""
import importlib.util
import math
from pathlib import Path

spec = importlib.util.spec_from_file_location('initial_motion', Path(__file__).with_name('grip-exercise-motion-v15.py'))
initial = importlib.util.module_from_spec(spec)
spec.loader.exec_module(initial)
POLICY = dict(jointConfidence=.3, maximumGapSeconds=.4, baselineSeconds=.3,
              baselineSamples=3, extensionDegrees=150., flexionDegrees=25., bentDegrees=140.,
              riseArmFraction=.12, wristDriftArmFraction=.12, overheadArmFraction=.1,
              motionDwellSeconds=.12, motionFreshSeconds=3., maximumScaleRatio=1.33,
              revision='Visible arms independently; missing hips/other arm do not veto observed exercise.')


def measure_arm(person, side):
    if person is None:
        return None
    points = [initial.point(person, side+j) for j in ['Shoulder', 'Elbow', 'Wrist']]
    if any(p is None for p in points):
        return None
    shoulder, elbow, wrist = points
    scale = math.dist(shoulder, elbow)+math.dist(elbow, wrist)
    degrees = initial.angle(*points)
    if scale < 16 or degrees is None:
        return None
    return dict(shoulder=shoulder, wrist=wrist, scale=scale, degrees=degrees,
                overhead=wrist[1] < shoulder[1]+POLICY['overheadArmFraction']*scale)


class ExerciseGate:
    def __init__(self):
        self.last = None
        self.track = None
        self.epoch = 0
        self.arms = {}
        self.side_epochs = dict(left=0, right=0)

    def reset_side(self, side):
        self.arms.pop(side, None)
        self.side_epochs[side] += 1

    def clear(self):
        self.epoch += 1
        for side in ['left', 'right']:
            self.reset_side(side)

    def update(self, seconds, track, person):
        result = dict(seconds=seconds, eligible=False, eligibleSides=[], epoch=self.epoch,
                      sideEpochs=self.side_epochs.copy(), reason='waitingForAthlete', arms={})
        if not math.isfinite(seconds) or self.last is not None and seconds <= self.last:
            self.clear()
            return dict(result, epoch=self.epoch, sideEpochs=self.side_epochs.copy(), reason='invalidSourceTime')
        gap = self.last is not None and seconds-self.last > POLICY['maximumGapSeconds']
        self.last = seconds
        if track != self.track or gap:
            self.clear()
        self.track = track
        active = []
        for side in ['left', 'right']:
            m = measure_arm(person, side) if track is not None else None
            reason = 'armUnavailable'
            if m is None or not m['overhead']:
                self.reset_side(side)
                reason = 'armUnavailable' if m is None else 'releasedOrNotOverhead'
            else:
                a = self.arms.get(side)
                if a:
                    ratio = m['scale']/a['sample']['scale']
                    drift = math.dist(m['wrist'], a['sample']['wrist'])/a['sample']['scale']
                    if not 1/POLICY['maximumScaleRatio'] <= ratio <= POLICY['maximumScaleRatio'] or drift > POLICY['wristDriftArmFraction']:
                        self.reset_side(side)
                        a = None
                        reason = 'handsMovedOrCameraChanged'
                if a is None:
                    if m['degrees'] >= POLICY['extensionDegrees']:
                        a = dict(sample=m, since=seconds, count=1, ready=False, active=False,
                                 motionSince=None, lastMotion=None)
                        self.arms[side] = a
                        reason = 'establishingBaseline'
                    elif reason != 'handsMovedOrCameraChanged':
                        reason = 'waitingForExtendedPosture'
                elif not a['ready']:
                    if m['degrees'] < POLICY['extensionDegrees']:
                        self.reset_side(side)
                        reason = 'extensionDwellBroken'
                    else:
                        a['count'] += 1
                        a['ready'] = a['count'] >= POLICY['baselineSamples'] and seconds-a['since'] >= POLICY['baselineSeconds']
                        reason = 'waitingForPullingMotion' if a['ready'] else 'establishingBaseline'
                else:
                    rise = (a['sample']['shoulder'][1]-m['shoulder'][1])/a['sample']['scale']
                    flexion = a['sample']['degrees']-m['degrees']
                    moving = rise >= POLICY['riseArmFraction'] and flexion >= POLICY['flexionDegrees'] and m['degrees'] <= POLICY['bentDegrees']
                    if moving:
                        a['motionSince'] = seconds if a['motionSince'] is None else a['motionSince']
                        a['lastMotion'] = seconds
                        if seconds-a['motionSince'] >= POLICY['motionDwellSeconds']:
                            a['active'] = True
                    else:
                        a['motionSince'] = None
                    if a['active'] and seconds-a['lastMotion'] > POLICY['motionFreshSeconds']:
                        self.reset_side(side)
                        reason = 'exerciseEvidenceExpired'
                    else:
                        reason = 'exerciseMotionObserved' if a['active'] else 'waitingForPullingMotion'
                        if a['active']:
                            active.append(side)
                    result['arms'][side] = dict(rise=rise, flexion=flexion)
            result['arms'].setdefault(side, {}).update(reason=reason)
        return dict(result, eligible=bool(active), eligibleSides=active, epoch=self.epoch,
                    sideEpochs=self.side_epochs.copy(), reason='exerciseMotionObserved' if active else 'waitingForExerciseMotion')


def decide(frames, temporal, mode):
    if not mode.startswith('motion-'):
        return initial.decide(frames, temporal, mode)
    adapted = []
    for f in frames:
        exercise = f['exercise']
        # Any observed arm discontinuity purges temporal support. A released
        # hand cannot inherit the exercising arm's permission or an older crop.
        epoch = (exercise['epoch'], tuple((s, exercise['sideEpochs'][s]) for s in exercise['eligibleSides']))
        adapted.append(dict(f, exercise=dict(exercise, epoch=epoch),
                            hands=[h for h in f['hands'] if h['side'] in exercise['eligibleSides']]))
    return initial.decide(adapted, temporal, mode)
