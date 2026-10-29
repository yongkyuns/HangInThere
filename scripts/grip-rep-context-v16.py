#!/usr/bin/env python3
"""RGB hand decisions under the exact counter's context; no pose detector here."""
import math


def hand_evidence(hand, view):
    # No contact score or wrist-above-shoulder heuristic. Current wrist/support
    # association and exercise state come from the shared counter adapter.
    surface, visible = hand['surface'], hand['visible']
    if not all(math.isfinite(x) for x in surface+[visible]+hand['neutralByView']):
        return None
    if visible < .8 or max(surface) < .8:
        return None
    neutral = min(hand['neutralByView']) >= .8
    binary = None
    if max(surface[:2]) >= .8:
        binary = 'overhand' if (view == 'front') == (surface[0] >= .8) else 'underhand'
    if neutral and binary:
        return None
    if neutral:
        return dict(label='neutral', score=min(visible, max(surface), min(hand['neutralByView'])))
    if binary:
        return dict(label=binary, score=min(visible, max(surface[:2])))
    return None


def decide(frames, rgb_by_hash, mode='body-view'):
    assert mode in ['body-view', 'rgb-view']
    states, decisions = [], []
    identity, label = None, 'unknown'
    bank, seen, buckets = [], set(), set()
    last_time = None

    def clear():
        nonlocal label, bank, seen, buckets
        label, bank, seen, buckets = 'unknown', [], set(), set()

    for f in frames:
        t = f['seconds']
        if last_time is not None and t <= last_time:
            raise ValueError('Non-increasing source time')
        last_time = t
        exercise = f['exercise']
        key = (f['sourceEpoch'], exercise['epoch'], f['trackId'])
        if key != identity:
            clear()
        identity = key
        reason, observed = 'retainedWithinExercise', False
        if not exercise['eligible'] or f['released'] or f['trackId'] is None:
            clear()
            reason = exercise['reason']
        elif f['imageSHA256'] in rgb_by_hash:
            rgb = rgb_by_hash[f['imageSHA256']]
            assert rgb['seconds'] == t
            view = None
            if mode == 'body-view':
                if f['bodyView']['supported']:
                    view = f['bodyView']['view']
            elif rgb['contextReady'] and max(rgb['view'][:2]) >= .8:
                view = 'front' if rgb['view'][0] >= .8 else 'rear'
            if view is None:
                clear()
                reason = 'torsoViewUnavailable'
            elif view != (bank[-1]['view'] if bank else view):
                clear()
                reason = 'torsoViewChanged'
            else:
                candidates = [hand_evidence(h, view) for h in rgb['hands']
                              if f['supportBySide'].get(h['side'], False) and t >= exercise['startedAt']]
                candidates = [v for v in candidates if v is not None]
                values = {v['label'] for v in candidates}
                if len(values) > 1:
                    clear()
                    reason = 'conflictingHands'
                elif candidates:
                    value = max(candidates, key=lambda v: v['score'])
                    if label != 'unknown' and label != value['label'] or bank and bank[-1]['label'] != value['label']:
                        clear()
                    # Two hands and duplicate RGB are not independent votes.
                    bucket = math.floor(t)
                    if f['imageSHA256'] not in seen and bucket not in buckets:
                        bank.append(dict(value, seconds=t, view=view))
                        seen.add(f['imageSHA256']); buckets.add(bucket)
                    if len(bank) >= 2 and bank[-1]['seconds']-bank[0]['seconds'] >= 1.:
                        label = value['label']
                        observed = True
                        decisions.append(dict(seconds=t, label=label, score=sum(v['score'] for v in bank)/len(bank),
                                              sourceEpoch=f['sourceEpoch'], exerciseEpoch=exercise['epoch']))
                        reason = 'currentHandEvidence'
                    else:
                        reason = 'waitingForSecondHandImage'
                else:
                    reason = 'retainedWithoutNewHandEvidence' if label != 'unknown' else 'handEvidenceUnavailable'
        states.append(dict(seconds=t, imageSHA256=f['imageSHA256'], label=label, reason=reason, observed=observed))
    return dict(states=states, decisions=decisions)
