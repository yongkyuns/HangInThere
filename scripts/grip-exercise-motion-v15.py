#!/usr/bin/env python3
"""Causal, image-independent pull-up engagement; no rep counting or grip inputs.

Deliberately scoped to an upright camera and a tracked athlete with both arms
and hips observed. A reach/overhead pose alone cannot establish exercise.
"""
import math


POLICY = dict(jointConfidence=.3, maximumGapSeconds=.4, baselineSeconds=.3,
              baselineSamples=3, extensionDegrees=150., minimumArmDegrees=140.,
              flexionDegrees=25., bentDegrees=140., riseTorsoFraction=.15,
              hipRiseFraction=.5, wristDriftTorsoFraction=.20,
              overheadTorsoFraction=.15, motionDwellSeconds=.12,
              motionFreshSeconds=3., maximumScaleRatio=1.33)


def point(person, name):
    for p in person.get('landmarks', []):
        if p['joint'] == name and math.isfinite(p['confidence']) and p['confidence'] >= POLICY['jointConfidence']:
            xy = [p['position'][k] for k in ['x', 'y']]
            if all(math.isfinite(v) for v in xy):
                return xy
    return None


def angle(a, b, c):
    ab, cb = [a[i]-b[i] for i in range(2)], [c[i]-b[i] for i in range(2)]
    length = math.hypot(*ab)*math.hypot(*cb)
    return math.degrees(math.acos(max(-1., min(1., sum(x*y for x, y in zip(ab, cb))/length)))) if length > 1 else None


def measure(person):
    if person is None:
        return None
    pts = {s+j: point(person, s+j) for s in ['left', 'right'] for j in ['Shoulder', 'Elbow', 'Wrist', 'Hip']}
    if any(p is None for p in pts.values()):
        return None
    shoulder = [sum(pts[s+'Shoulder'][i] for s in ['left', 'right'])/2 for i in range(2)]
    hip = [sum(pts[s+'Hip'][i] for s in ['left', 'right'])/2 for i in range(2)]
    scale = math.dist(shoulder, hip)
    if scale < 8 or hip[1]-shoulder[1] < scale*.65:
        return None  # Rotated camera / unusable torso is outside this policy.
    angles = [angle(*(pts[s+j] for j in ['Shoulder', 'Elbow', 'Wrist'])) for s in ['left', 'right']]
    if any(a is None for a in angles):
        return None
    wrists = [pts[s+'Wrist'] for s in ['left', 'right']]
    overhead = all(pts[s+'Wrist'][1] < pts[s+'Shoulder'][1]+POLICY['overheadTorsoFraction']*scale for s in ['left', 'right'])
    return dict(shoulder=shoulder, hip=hip, scale=scale, angles=angles,
                degrees=sum(angles)/2, wrists=wrists, overhead=overhead)


class ExerciseGate:
    def __init__(self):
        self.last = None
        self.track = None
        self.epoch = 0
        self.clear()

    def clear(self):
        self.anchor = None
        self.baseline_since = None
        self.baseline_samples = 0
        self.baseline_ready = False
        self.motion_since = None
        self.active = False
        self.last_motion = None
        self.epoch += 1

    def update(self, seconds, track, person):
        result = dict(seconds=seconds, eligible=False, epoch=self.epoch, reason='waitingForAthlete')
        if not math.isfinite(seconds) or self.last is not None and seconds <= self.last:
            self.clear()  # Keep high-water fence; rejected older frames earn no dwell.
            return dict(result, epoch=self.epoch, reason='invalidSourceTime')
        gap = self.last is not None and seconds-self.last > POLICY['maximumGapSeconds']
        self.last = seconds
        if track != self.track or gap:
            self.clear()
        self.track = track
        m = measure(person) if track is not None else None
        if m is None or not m['overhead']:
            self.clear()
            return dict(result, epoch=self.epoch, reason='bodyUnavailable' if m is None else 'releasedOrNotOverhead')
        result.update(epoch=self.epoch, degrees=m['degrees'])
        if self.anchor is not None:
            a = self.anchor
            ratio = m['scale']/a['scale']
            drift = max(math.dist(x, y) for x, y in zip(m['wrists'], a['wrists']))/a['scale']
            if not 1/POLICY['maximumScaleRatio'] <= ratio <= POLICY['maximumScaleRatio'] or drift > POLICY['wristDriftTorsoFraction']:
                self.clear()
                return dict(result, epoch=self.epoch, reason='handsMovedOrCameraChanged', wristDrift=drift)
        if self.anchor is None:
            if m['degrees'] < POLICY['extensionDegrees'] or min(m['angles']) < POLICY['minimumArmDegrees']:
                return dict(result, epoch=self.epoch, reason='waitingForExtendedPosture')
            self.anchor = m
            self.baseline_since = seconds
            self.baseline_samples = 1
            return dict(result, epoch=self.epoch, reason='establishingBaseline')
        a = self.anchor
        if not self.baseline_ready:
            if m['degrees'] < POLICY['extensionDegrees'] or min(m['angles']) < POLICY['minimumArmDegrees']:
                self.clear()
                return dict(result, epoch=self.epoch, reason='extensionDwellBroken')
            self.baseline_samples += 1
            self.baseline_ready = (self.baseline_samples >= POLICY['baselineSamples'] and
                                   seconds-self.baseline_since >= POLICY['baselineSeconds'])
            return dict(result, reason='waitingForPullingMotion' if self.baseline_ready else 'establishingBaseline')
        rise = (a['shoulder'][1]-m['shoulder'][1])/a['scale']
        hip_rise = (a['hip'][1]-m['hip'][1])/a['scale']
        flexion = a['degrees']-m['degrees']
        moving = (rise >= POLICY['riseTorsoFraction'] and hip_rise >= POLICY['hipRiseFraction']*POLICY['riseTorsoFraction']
                  and flexion >= POLICY['flexionDegrees'] and m['degrees'] <= POLICY['bentDegrees'])
        result.update(rise=rise, hipRise=hip_rise, flexion=flexion)
        if moving:
            self.motion_since = seconds if self.motion_since is None else self.motion_since
            self.last_motion = seconds
            if seconds-self.motion_since >= POLICY['motionDwellSeconds']:
                self.active = True
        else:
            self.motion_since = None
        if self.active and seconds-self.last_motion > POLICY['motionFreshSeconds']:
            self.clear()
            return dict(result, epoch=self.epoch, reason='exerciseEvidenceExpired')
        return dict(result, eligible=self.active, reason='exerciseMotionObserved' if self.active else 'waitingForPullingMotion')


def decide(frames, temporal, mode):
    """Current eligible RGB is mandatory; release/epoch changes purge all votes."""
    assert mode in ['image-contact', 'no-contact', 'motion-and-contact', 'motion-no-contact']
    require_motion = mode.startswith('motion-')
    require_contact = mode in ['image-contact', 'motion-and-contact']
    bank, views, decisions, states = [], [], [], []
    previous_identity = None
    seen_images = set()
    last = None
    for f in frames:
        t = f['seconds']
        if last is not None and t <= last:
            raise ValueError('Non-increasing source time')
        last = t
        identity = (f['trackId'], f['exercise']['epoch']) if require_motion else (f['trackId'],)
        if identity != previous_identity:
            bank, views, seen_images = [], [], set()
        previous_identity = identity
        views = [v for v in views if t-v['seconds'] <= 2.]
        # One image per source second; arithmetic mean cannot amplify confidence.
        if f.get('contextReady') and not any(math.floor(v['seconds']) == math.floor(t) for v in views):
            views.append(f)
        view = temporal.stable_view(views)
        current_oblique = f.get('contextReady') and f['view'][2] >= .8
        eligible = f['trackId'] is not None and not current_oblique and max(view[:2]) >= .8
        if require_motion:
            eligible &= f['exercise']['eligible']
        label, reason = 'unknown', 'exerciseOrViewUnavailable'
        if not eligible:
            bank.clear()
        else:
            value, conflict = temporal.frame_evidence(f, view, require_contact)
            bank = [v for v in bank if t-v[0] <= 2.]
            if conflict or value is None:
                bank.clear()
                reason = 'conflictingHands' if conflict else 'handEvidenceUnavailable'
            else:
                if f['imageSHA256'] not in seen_images and not any(math.floor(v[0]) == math.floor(t) for v in bank):
                    bank.append((t, value))
                    seen_images.add(f['imageSHA256'])
                labels = {v['label'] for _, v in bank}
                enough = len(bank) >= 2 and bank[-1][0]-bank[0][0] >= 1.
                if len(labels) == 1 and enough and value['label'] in labels:
                    label = value['label']
                    decisions.append(dict(seconds=t, label=label, trackId=f['trackId'], epoch=f['exercise']['epoch'],
                                          score=sum(v['score'] for _, v in bank)/len(bank)))
                    reason = 'consistentCurrentEvidence'
                elif len(labels) > 1:
                    bank.clear()
                    reason = 'conflictingTemporalEvidence'
                else:
                    reason = 'waitingForSecondEligibleImage'
        states.append(dict(seconds=t, label=label, reason=reason))
    labels = {d['label'] for d in decisions}
    tracks = {d['trackId'] for d in decisions}
    return dict(label=next(iter(labels)) if len(labels) == 1 and len(tracks) == 1 else 'unknown',
                decisions=decisions, states=states)
