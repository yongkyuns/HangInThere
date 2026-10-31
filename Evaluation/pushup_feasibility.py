"""Side-view push-up feasibility probe, deliberately outside the app.

Consumes full-source pose exports from BodyPosePixelStream plus MediaPipe Heavy.
No source labels, expected counts or time windows enter the counter. These are
development recordings, not a holdout. This does not run athlete association,
camera registration or the native Android pipeline.
"""
import argparse
from collections import Counter
import hashlib
import json
import math
from pathlib import Path


def angle(a, b, c):
    u, v = (a[0] - b[0], a[1] - b[1]), (c[0] - b[0], c[1] - b[1])
    length = math.hypot(*u) * math.hypot(*v)
    return math.degrees(math.acos(max(-1, min(1, sum(x*y for x, y in zip(u, v))/length)))) if length else None


def measure(pose, side):
    # Same current-frame arm floor/confidence policy as ArmMeasurement v1;
    # this Python diagnostic is not a shared Kotlin runtime parity test.
    if len(pose['people']) != 1:
        return {}
    width, height = (pose['imageSize'][key] for key in ('width', 'height'))
    if not all(math.isfinite(v) and v > 0 for v in (width, height)):
        return {}
    points = pose['people'][0]['landmarks']
    floor = .02 * min(width, height)

    def point(joint):
        matches = [p for p in points if p['joint'] == side + joint]
        if len(matches) != 1:
            return None
        p = matches[0]
        x, y, confidence = p['position']['x'], p['position']['y'], p['confidence']
        if not all(math.isfinite(v) for v in (x, y, confidence)):
            return None
        return (x, y) if 0 <= x <= width and 0 <= y <= height and .3 <= confidence <= 1 else None

    def chain(a, b, c):
        return all(p is not None for p in (a, b, c)) and min(math.dist(a, b), math.dist(b, c)) >= floor

    shoulder, elbow, wrist, hip, knee, ankle = [point(j) for j in
        ('Shoulder', 'Elbow', 'Wrist', 'Hip', 'Knee', 'Ankle')]
    result = {}
    if chain(shoulder, elbow, wrist):
        result.update(elbow=angle(shoulder, elbow, wrist), shoulderY=shoulder[1],
                      arm=math.dist(shoulder, elbow) + math.dist(elbow, wrist),
                      wristX=wrist[0], wristY=wrist[1])
    if chain(shoulder, hip, ankle):
        result.update(body=angle(shoulder, hip, ankle), axis=math.degrees(math.atan2(
            abs(ankle[1] - shoulder[1]), abs(ankle[0] - shoulder[0]))))
    if chain(hip, knee, ankle):
        result['knee'] = angle(hip, knee, ankle)
    return result


class PushUpProbe:
    """One fixed arm per set; observed descent and observed return required.

    Ready: 0.6 s straight-leg plank, elbow >=150°, body >=150°, axis <=30°.
    Lower: elbow <=110° and shoulder descent >=0.2 initial projected arm lengths.
    Return: within 15° of calibrated top and shoulder within 0.15 arm lengths.
    Each endpoint requires 0.12 s of current, usable measurements. Wrist must
    remain within 0.3 initial arm lengths of the calibrated anchor. Missing
    endpoints never count; short lower-body gaps preserve only cycle state.
    These are exploratory counting conditions, not strict form/depth standards.
    """
    def __init__(self, fixed_return=False, strict_body_gap=False):
        self.fixed_return, self.strict_body_gap = fixed_return, strict_body_gap
        self.state, self.side, self.since = 'waiting', None, None
        self.candidates, self.anchor = {}, None
        self.last_arm = self.last_body = None
        self.previous_time = None
        self.events, self.transitions = [], []
        self.blocked = Counter()

    @staticmethod
    def plank(m):
        return m.get('body', 0) >= 150 and m.get('axis', 90) <= 30

    def change(self, t, state, reason):
        self.state, self.since = state, None
        self.transitions.append(dict(time=t, state=state, reason=reason, side=self.side))
        if state == 'waiting':
            self.side, self.candidates = None, {}

    def step(self, frame):
        t = frame['t']
        if not math.isfinite(t) or (self.previous_time is not None and t <= self.previous_time):
            raise ValueError('Source timestamps must be finite and strictly increasing')
        gap = 0 if self.previous_time is None else t - self.previous_time
        self.previous_time = t
        if gap > .35:
            self.change(t, 'waiting', 'source_gap')
        if self.state == 'waiting':
            for side in ('left', 'right'):
                m = frame[side]
                ready = m.get('elbow', 0) >= 150 and self.plank(m) and m.get('knee', 0) >= 150
                if not ready:
                    self.candidates.pop(side, None)
                    continue
                start, samples = self.candidates.setdefault(side, (t, []))
                samples.append(m)
                if t - start >= .6:
                    self.side = side
                    self.anchor = {k: sum(s[k] for s in samples)/len(samples) for k in
                                   ('elbow', 'shoulderY', 'arm', 'wristX', 'wristY')}
                    self.last_arm = self.last_body = t
                    self.candidates = {}
                    self.change(t, 'top', 'stable_straight_leg_plank')
                    return
            return
        m = frame[self.side]
        arm, body = 'elbow' in m, self.plank(m)
        if arm:
            self.last_arm = t
        if body:
            self.last_body = t
        if ('body' in m and not body) or m.get('knee', 180) < 140:
            self.change(t, 'waiting', 'observed_non_plank_or_kneeling')
            return
        body_timeout = .35 if self.strict_body_gap else 1.0
        if t - self.last_arm > .35 or t - self.last_body > body_timeout:
            self.change(t, 'waiting', 'lost_arm_or_lower_body')
            return
        if not arm or not body:
            self.blocked['missing_current_arm_or_plank'] += 1
            self.since = None
            return
        drop = (m['shoulderY'] - self.anchor['shoulderY'])/self.anchor['arm']
        wrist = math.hypot(m['wristX'] - self.anchor['wristX'],
                           m['wristY'] - self.anchor['wristY'])/self.anchor['arm']
        if wrist > .3:
            self.blocked['wrist_displacement'] += 1
            self.since = None
            return
        top_angle = 150 if self.fixed_return else self.anchor['elbow'] - 15
        endpoint = (m['elbow'] <= 110 and drop >= .2) if self.state == 'top' else (
            m['elbow'] >= top_angle and drop <= .15)
        if not endpoint:
            self.since = None
        else:
            if self.since is None:
                self.since = t
            if t - self.since >= .12:
                if self.state == 'top':
                    self.change(t, 'lowered', 'observed_bend_and_shoulder_descent')
                else:
                    self.events.append(t)
                    self.change(t, 'top', 'observed_complete_return')


def run(root):
    labels_path = root/'manual-reference.json'
    labels = json.loads(labels_path.read_text())['clips']
    outputs, all_metrics = [], []
    for label in labels:
        rows = [json.loads(line) for line in (root/'observations'/label['id']/'poses.jsonl').open()]
        export = json.loads((root/'observations'/label['id']/'export.json').read_text())
        assert export['videoSHA256'] == label['videoSHA256']
        assert export['frames'] == len(rows) and export['sourceClockVerified']
        for engine, key in (('Vision', 'visionPose'), ('MediaPipe', 'mediaPipePose')):
            frames = []
            probe, baseline = PushUpProbe(), PushUpProbe(fixed_return=True, strict_body_gap=True)
            for row in rows:
                pose = row[key]
                frame = dict(t=pose['timestamp']['value']/pose['timestamp']['timescale'],
                             **{s: measure(pose, s) for s in ('left', 'right')})
                frames.append(frame)
                probe.step(frame)
                baseline.step(frame)
            # Labels are used only for post-hoc scoring/coverage, never step().
            start, end = label['zeroCountWindows'][0]['end'], label['zeroCountWindows'][1]['start']
            active = [f for f in frames if start <= f['t'] < end]
            coverage = {s: {k: sum(k in f[s] for f in active)/len(active) for k in
                           ('elbow', 'body', 'knee')} for s in ('left', 'right')}
            matches = [sum(a <= t <= b for t in probe.events) for a, b in label['returnWindows']]
            false_counts = {w['name']: sum(w['start'] <= t <= w['end'] for t in probe.events)
                            for w in label['zeroCountWindows']}
            outputs.append(dict(clip=label['id'], engine=engine, frames=len(frames),
                events=probe.events, transitions=probe.transitions, blocked=dict(probe.blocked),
                expected=label['expectedCycles'], referenceWindowMatches=matches,
                zeroWindowCounts=false_counts, activeCoverage=coverage,
                fixed150AndStrictBodyGapEvents=baseline.events))
            all_metrics.append(dict(clip=label['id'], engine=engine, frames=frames))
    report = dict(scope='Exposed development clips; single-person Mac component diagnostic. '
                  'No production/native-device, strict-form or holdout qualification.',
                  labelsSHA256=hashlib.sha256(labels_path.read_bytes()).hexdigest(), results=outputs)
    (root/'cycle-results.json').write_text(json.dumps(report, indent=2))
    (root/'metrics.json').write_text(json.dumps(all_metrics, separators=(',', ':')))
    for row in outputs:
        print(row['clip'], row['engine'], 'returns:', [round(t, 3) for t in row['events']],
              'setup/release:', row['zeroWindowCounts'])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('evidence', type=Path)
    run(parser.parse_args().evidence)
