#!/usr/bin/env python3
"""Conservative offline temporal experiment. Scores are evidence, not probabilities of correctness.

This module reads no labels, annotations, review files or source names to make
decisions. One tracked athlete, one observation per second; no likelihood
multiplication. Neutral needs its own frozen image-head evidence for all views.
"""
import math
from collections import defaultdict


GRIPS = ('overhand', 'underhand', 'neutral')
THRESHOLD = .8


def point(person, joint, threshold=.3):
    for p in person['landmarks']:
        if p['joint'] == joint and p['confidence'] >= threshold:
            xy = (p['position']['x'], p['position']['y'])
            if all(math.isfinite(v) for v in xy):
                return xy
    return None


def descriptor(person):
    a, b = point(person, 'leftShoulder'), point(person, 'rightShoulder')
    if a is None or b is None or math.dist(a, b) < 8:
        return None
    center = ((a[0] + b[0]) / 2, (a[1] + b[1]) / 2)
    span = math.dist(a, b)
    raised = sum(point(person, side + 'Wrist') is not None and
                 point(person, side + 'Wrist')[1] < point(person, side + 'Shoulder')[1] - .3 * span
                 for side in ['left', 'right'])
    return dict(center=center, span=span, raised=raised)


class SubjectTracker:
    """Shoulder continuity with overhead acquisition; never trust person list order."""
    def __init__(self):
        self.anchor = None
        self.last_time = None
        self.track = 0
        self.hits = 0

    def update(self, people, seconds):
        if self.last_time is not None and seconds <= self.last_time:
            raise ValueError('Non-monotonic source time')
        options = [(i, descriptor(person)) for i, person in enumerate(people)]
        options = [(i, d) for i, d in options if d is not None]
        match = None
        if self.anchor is not None and seconds - self.last_time <= 1.0:
            candidates = []
            for i, d in options:
                ratio = d['span'] / self.anchor['span']
                distance = math.dist(d['center'], self.anchor['center']) / max(d['span'], self.anchor['span'])
                if .5 <= ratio <= 2 and distance <= 1.25:
                    candidates.append((distance + abs(math.log(ratio)) * .25, i, d))
            candidates.sort(key=lambda item: item[0])
            if candidates and (len(candidates) == 1 or candidates[1][0] - candidates[0][0] >= .25):
                match = candidates[0][1:]
            # A nearby ambiguous/different athlete cannot inherit the bank.
            if match is None:
                self.anchor = None
                self.hits = 0
                return dict(status='ambiguousOrLost', personIndex=None, trackId=None)
        if match is None:
            overhead = [(i, d) for i, d in options if d['raised'] > 0]
            best = max((d['raised'] for _, d in overhead), default=0)
            overhead = [(i, d) for i, d in overhead if d['raised'] == best]
            if len(overhead) != 1:
                self.anchor = None
                self.hits = 0
                return dict(status='noUniqueOverheadAthlete', personIndex=None, trackId=None)
            match = overhead[0]
            self.track += 1
            self.hits = 0
        index, self.anchor = match
        self.hits += 1
        self.last_time = seconds
        return dict(status='tracked' if self.hits >= 2 else 'acquiring', personIndex=index,
                    trackId=self.track, shoulderCenter=self.anchor['center'], shoulderSpan=self.anchor['span'])


def stable_view(frames):
    ready = [r['view'] for r in frames if r.get('contextReady')]
    if not ready:
        return [1 / 3] * 3
    # Arithmetic mean cannot grow confidence beyond its input observations.
    return [sum(v[i] for v in ready) / len(ready) for i in range(3)]


def evidence(hand, view):
    """No side=>neutral shortcut; no inference from a missing face."""
    if not hand.get('raised') or hand['visible'] < THRESHOLD or max(hand['surface']) < THRESHOLD:
        return None
    surface = hand['surface']
    # Marginalize nothing into neutral: it must remain strong for every
    # supplied view of the same RGB patch, including oblique.
    neutral = min(hand['neutralByView'])
    binary = None
    if max(view[:2]) >= THRESHOLD and max(surface[:2]) >= THRESHOLD:
        front = view[0] >= THRESHOLD
        palm = surface[0] >= THRESHOLD
        binary = 'overhand' if front == palm else 'underhand'
    if neutral >= THRESHOLD and binary is not None:
        return None  # Strong incompatible evidence; never choose by priority.
    if neutral >= THRESHOLD:
        return dict(label='neutral', score=min(neutral, hand['visible'], max(surface)))
    if binary is not None:
        return dict(label=binary, score=min(max(view[:2]), max(surface[:2]), hand['visible']))
    return None


def frame_evidence(frame, view, require_contact):
    observations = []
    for hand in frame['hands']:
        value = evidence(hand, view)
        if value is not None and (not require_contact or hand['contact'] >= THRESHOLD):
            observations.append(dict(value, contact=hand['contact'], side=hand['side']))
    if not observations:
        return None, False
    if len({v['label'] for v in observations}) != 1:
        return None, True
    # Both hands in one source frame are correlated, never two votes.
    return max(observations, key=lambda x: x['score']), False


def decide(frames, mode='temporal-no-contact'):
    if mode not in ['frame-gated', 'temporal-contact-support', 'temporal-no-contact']:
        raise ValueError(mode)
    ordered = sorted(frames, key=lambda x: x['seconds'])
    if len({f['seconds'] for f in ordered}) != len(ordered):
        raise ValueError('Duplicate source timestamps')
    bank = {}
    first = None
    decisions, events = [], []
    previous_track = None
    for frame in ordered:
        track = frame.get('trackId')
        if track is None:
            bank.clear()
            previous_track = None
            continue
        if previous_track is not None and track != previous_track:
            bank.clear()
            events.append(dict(seconds=frame['seconds'], type='subjectReset'))
        previous_track = track
        bucket = math.floor(frame['seconds'])
        # Only the first scheduled source sample in a second is evidence.
        # Duplicate frames/timestamps never improve support or confidence.
        if bucket not in bank and frame['imageSHA256'] not in {f['imageSHA256'] for f in bank.values()}:
            bank[bucket] = frame
        bank = {b: f for b, f in bank.items() if frame['seconds'] - f['seconds'] <= 8.0}
        history = list(bank.values())
        if mode == 'frame-gated':
            value, conflict = frame_evidence(frame, frame['view'], True)
            label = value['label'] if value else None
            score = value['score'] if value else None
        else:
            view = stable_view(history)
            votes, conflict = [], False
            for f in history:
                value, bad = frame_evidence(f, view, False)
                conflict |= bad
                if value:
                    votes.append((f, value))
            labels = {v['label'] for _, v in votes}
            conflict |= len(labels) > 1
            enough = len(votes) >= 2 and votes[-1][0]['seconds'] - votes[0][0]['seconds'] >= 1.0
            contact = any(v['contact'] >= THRESHOLD for _, v in votes)
            label = next(iter(labels)) if enough and len(labels) == 1 and not conflict and (mode != 'temporal-contact-support' or contact) else None
            score = sum(v['score'] for _, v in votes) / len(votes) if label else None
        if conflict:
            events.append(dict(seconds=frame['seconds'], type='conflictingHandsOrGripEvidence'))
        if label:
            first = frame['seconds'] if first is None else first
            decisions.append(dict(seconds=frame['seconds'], label=label, score=score, trackId=track))
    # Multiple athletes, different grip labels, or later contradictory evidence
    # cannot be hidden by taking the first successful frame of the clip.
    labels = {d['label'] for d in decisions}
    tracks = {d['trackId'] for d in decisions}
    conflict = any(e['type'] in ['conflictingHandsOrGripEvidence', 'subjectReset'] for e in events)
    final = next(iter(labels)) if len(labels) == 1 and len(tracks) == 1 and not conflict else 'unknown'
    return dict(label=final, firstDecisionSeconds=first, observations=len(ordered), decisionEvents=decisions,
                diagnostics=events, qualification='Uncalibrated evidence agreement; not independent votes or population accuracy')
