#!/usr/bin/env python3
"""Hand-first RGB extraction experiment; no grip labels or wrist-only fallback.

Face rectangles are spatial ownership evidence only. A unique face is a proxy,
not proof that every nearby hand belongs to it; qualification measures errors.
"""
import math
from collections import defaultdict

import numpy as np
from PIL import Image


def point(person, joint):
    for p in person['landmarks']:
        if p['joint'] == joint and p['confidence'] >= .3:
            v = [p['position']['x'], p['position']['y']]
            return v if all(math.isfinite(x) for x in v) else None
    return None


def rect(cx, cy, width, height, size):
    w, h = size
    x0, y0 = max(0, math.floor(cx-width/2)), max(0, math.floor(cy-height/2))
    x1, y1 = min(w, math.ceil(cx+width/2)), min(h, math.ceil(cy+height/2))
    return [x0, y0, x1, y1] if x1-x0 >= 16 and y1-y0 >= 16 else None


def primary(row):
    people = row['pose']['people']
    # Native face confidence is a proposal score, not calibrated ownership.
    # It cannot authorize a hand alone: current hand geometry AND repeated
    # source-bound face agreement are required by the face-only path.
    faces = [f for f in row['localizationFaces']['faces'] if f['confidence'] >= .5 and
             min(f['box'][2]-f['box'][0], f['box'][3]-f['box'][1]) >= min(row['pose']['imageSize'].values())*.15]
    candidates = []
    for index, person in enumerate(people):
        shoulders = [point(person, s+'Shoulder') for s in ['left', 'right']]
        head = [point(person, k) for k in ['nose', 'leftEye', 'rightEye', 'leftEar', 'rightEar']]
        head = [p for p in head if p is not None]
        if head:
            center = np.mean(head, axis=0).tolist()
        elif all(p is not None for p in shoulders):
            center = np.mean(shoulders, axis=0).tolist()
        else:
            continue
        span = math.dist(*shoulders) if all(p is not None for p in shoulders) else min(row['pose']['imageSize'].values())*.15
        if span < 8:
            continue
        limit = center[1]+(.2*span if head else -.15*span)
        wrists = {s: point(person, s+'Wrist') for s in ['left', 'right']}
        supported = {s: p for s, p in wrists.items() if p is not None and p[1] <= limit}
        if supported:
            candidates.append(dict(kind='body', index=index, center=center, scale=span, wrists=supported, limit=limit))
    if len(candidates) == 1:
        return candidates[0], 'uniqueOverheadBody'
    if len(candidates) > 1:
        return None, 'competingAthletes'
    # This branch is deliberately independent of body acquisition. Competing
    # bodies/faces suppress it; absence of a face supplies no ownership.
    if len(people) <= 1 and len(faces) == 1:
        x0, y0, x1, y1 = faces[0]['box']
        return dict(kind='face', index=None, center=[(x0+x1)/2, (y0+y1)/2], confirmed=False,
                    scale=max(x1-x0, y1-y0), wrists={}, limit=(y0+y1)/2), 'singleFaceProxy'
    return None, 'ownershipUnavailable'


def regions(size, owner, previous):
    w, h = size
    candidates = [[0, 0, w, h]]
    if owner is not None and owner['wrists']:
        for wrist in owner['wrists'].values():
            candidates.append(rect(*wrist, min(w, owner['scale']*2), min(h, owner['scale']*2), size))
    else:
        candidates += [[0, 0, math.ceil(w*.65), math.ceil(h*.70)],
                       [math.floor(w*.35), 0, w, math.ceil(h*.70)]]
    if previous:
        p = previous[0]
        b = p['box']
        candidates.append(rect((b[0]+b[2])/2, (b[1]+b[3])/2, (b[2]-b[0])*2, (b[3]-b[1])*2, size))
    result = []
    for box in candidates:
        if box is not None and box not in result:
            result.append(box)
    return result[:4]


def candidate(points, size, roi):
    points = np.asarray(points, dtype=float)
    if points.shape != (21, 2) or not np.isfinite(points).all():
        return None
    lo, hi = points.min(0), points.max(0)
    extent = float(max(hi-lo))
    if extent < 16:
        return None
    # Border contact cannot certify a complete hand. A wider proposal may
    # recover ROI clipping, but source-edge clipping must remain unavailable.
    if any(lo[i] < roi[i]+2 or hi[i] > roi[i+2]-2 for i in [0, 1]):
        return None
    wrist = points[0]
    away = wrist-points[9]
    length = float(np.linalg.norm(away))
    forearm = wrist+away/max(length, 1)*extent*.25
    lo, hi = np.minimum(lo, forearm), np.maximum(hi, forearm)
    side = math.ceil(max(hi-lo)+extent*.4)
    if side > min(size):
        return None
    center = (lo+hi)/2
    # Keep a square inside the source, while preserving all detected points.
    x = max(0, min(math.floor(center[0]-side/2), size[0]-side))
    y = max(0, min(math.floor(center[1]-side/2), size[1]-side))
    box = [x, y, x+side, y+side]
    if np.any(points < np.array(box[:2])) or np.any(points > np.array(box[2:])):
        return None
    return dict(box=box, wrist=wrist.tolist(), landmarkBox=[*points.min(0).tolist(), *points.max(0).tolist()],
                points=points.tolist(), extent=extent, roi=roi)


def iou(a, b):
    overlap = max(0, min(a[2], b[2])-max(a[0], b[0]))*max(0, min(a[3], b[3])-max(a[1], b[1]))
    union = (a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-overlap
    return overlap/union if union else 0.


def deduplicate(candidates):
    result = []
    for c in sorted(candidates, key=lambda c: c['extent'], reverse=True):
        if not any(math.dist(c['wrist'], p['wrist']) <= .3*max(c['extent'], p['extent']) or iou(c['box'], p['box']) > .5 for p in result):
            result.append(c)
    return result


def ownership(hand, owner, row, previous):
    if owner is None or hand['wrist'][1] > owner['limit']:
        return None
    wrist = hand['wrist']
    if owner['kind'] == 'body':
        distances = sorted((math.dist(wrist, p), side) for side, p in owner['wrists'].items())
        if not distances or distances[0][0] > max(16, owner['scale']*.65):
            return None
        if len(distances) > 1 and distances[1][0]-distances[0][0] < owner['scale']*.2:
            return None
        for i, person in enumerate(row['pose']['people']):
            if i == owner['index']:
                continue
            other = [point(person, s+'Wrist') for s in ['left', 'right']]
            if any(p is not None and math.dist(wrist, p) <= distances[0][0]+owner['scale']*.2 for p in other):
                return None
        return dict(method='observedBodyWrist', slot=distances[0][1], distance=distances[0][0])
    # Body missing: a unique actual face and local overhead geometry may
    # propose a subject hand. This hypothesis is explicitly measured, not gold.
    dx, dy = wrist[0]-owner['center'][0], wrist[1]-owner['center'][1]
    if not owner.get('confirmed') or abs(dx) > owner['scale']*2 or not -3*owner['scale'] <= dy <= 0:
        return None
    if len(row['pose']['people']) > 1:
        return None
    # A visible wrist must lie between the head and the detected hand. This
    # rejects an unrelated sideways/downward hand near a face rectangle.
    digits = np.mean(np.asarray(hand['points'])[[5, 9, 13, 17]], axis=0)
    if math.dist(digits, owner['center']) <= math.dist(wrist, owner['center'])+hand['extent']*.1:
        return None
    return dict(method='singleFaceProxy', slot='imageLeft' if dx < 0 else 'imageRight', distance=math.hypot(dx, dy))


def sharpness(image, box):
    gray = np.asarray(image.crop(box).convert('L').resize((64, 64), Image.Resampling.BILINEAR), dtype=float)
    lap = gray[1:-1, :-2]+gray[1:-1, 2:]+gray[:-2, 1:-1]+gray[2:, 1:-1]-4*gray[1:-1, 1:-1]
    return float(lap.var())


def select_clearer(rows):
    """One best actual owned crop per slot/track/second; no score accumulation."""
    grouped = defaultdict(list)
    for r in rows:
        for p in r['patches']:
            if p['status'] == 'patchReady':
                grouped[r['recordingId'], r['trackId'], math.floor(r['seconds']), p['owner']['slot']].append((r, p))
    selected = []
    for entries in grouped.values():
        r, p = max(entries, key=lambda item: (item[1]['sharpness'], -item[0]['seconds']))
        selected.append(dict(recordingId=r['recordingId'], trackId=r['trackId'], seconds=r['seconds'],
                             availableAtSeconds=max(r['seconds'] for r, _ in entries), imageSHA256=r['imageSHA256'],
                             candidateId=p['candidateId'], patch=p['patch'], patchSHA256=p['patchSHA256'], sharpness=p['sharpness']))
    return sorted(selected, key=lambda p: (p['recordingId'], p['seconds'], p['candidateId']))
