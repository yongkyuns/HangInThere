#!/usr/bin/env python3
"""Confirmed-view baseline. Readability is a disclosed ablation, never relabeled confidence."""
import math


def evidence(hand, view, require_readability):
    if view not in ['front', 'rear']:
        return None
    surface, neutral = hand['surface'], hand['neutralByView']
    if not all(math.isfinite(v) and 0 <= v <= 1 for v in surface+neutral):
        return None
    visible = hand.get('visible')
    if require_readability and (visible is None or not math.isfinite(visible) or visible < .8):
        return None
    if max(surface) < .8:
        return None
    binary = None
    if max(surface[:2]) >= .8:
        binary = 'overhand' if (view == 'front') == (surface[0] >= .8) else 'underhand'
    is_neutral = min(neutral) >= .8
    if binary and is_neutral:
        return None
    label = 'neutral' if is_neutral else binary
    if label is None:
        return None
    # Preserve raw classifier evidence; never replace its confidence with 1.0.
    score = min(max(surface), min(neutral)) if is_neutral else max(surface[:2])
    return dict(label=label, classifierScore=score, readability=visible,
                acceptanceScore=min(score, visible) if require_readability else score)


def decide(frames, rgb_by_hash, confirmed_view, require_readability):
    if confirmed_view not in ['front', 'rear']:
        confirmed_view = None
    label, identity, bank, seen, buckets = 'unknown', None, [], set(), set()
    states, decisions = [], []
    last = None

    def clear():
        nonlocal label, bank, seen, buckets
        label, bank, seen, buckets = 'unknown', [], set(), set()

    for f in frames:
        t = f['seconds']
        if not math.isfinite(t) or last is not None and t <= last:
            raise ValueError('Invalid source clock')
        last = t
        context = f['exercise']
        key = (f['sourceEpoch'], context['epoch'], f['trackId'])
        if key != identity:
            clear()
        identity = key
        reason, observed = 'retainedWithinExercise', False
        if not confirmed_view or not context['eligible'] or f['released'] or f['trackId'] is None:
            clear()
            reason = 'confirmedViewRequired' if not confirmed_view else context['reason']
        elif f['imageSHA256'] in rgb_by_hash:
            rgb = rgb_by_hash[f['imageSHA256']]
            assert rgb['seconds'] == t
            candidates = [evidence(h, confirmed_view, require_readability) for h in rgb['hands']
                          if f['supportBySide'].get(h['side'], False) and t >= context['startedAt']]
            candidates = [c for c in candidates if c is not None]
            labels = {c['label'] for c in candidates}
            if len(labels) > 1:
                clear(); reason = 'conflictingHands'
            elif candidates:
                value = max(candidates, key=lambda c: c['acceptanceScore'])
                if bank and bank[-1]['label'] != value['label']:
                    clear()
                bucket = math.floor(t)
                if f['imageSHA256'] not in seen and bucket not in buckets:
                    bank.append(dict(value, seconds=t)); seen.add(f['imageSHA256']); buckets.add(bucket)
                if len(bank) >= 2 and bank[-1]['seconds']-bank[0]['seconds'] >= 1.:
                    label, observed, reason = value['label'], True, 'currentHandEvidence'
                    decisions.append(dict(value, seconds=t, sourceEpoch=f['sourceEpoch'], exerciseEpoch=context['epoch']))
                else:
                    reason = 'waitingForSecondImage'
            else:
                reason = 'retainedWithoutNewEvidence' if label != 'unknown' else 'handEvidenceUnavailable'
        states.append(dict(seconds=t, imageSHA256=f['imageSHA256'], label=label, reason=reason, observed=observed))
    return dict(states=states, decisions=decisions)
