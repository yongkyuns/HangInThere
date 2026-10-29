#!/usr/bin/env python3
"""Describe body-scale placement targets on exposed data; no fitting or test decoding.

This measures pose landmarks, not hand extent. Readability refs describe the
development sample; passing body scale does not prove hand visibility or grip.
"""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
import math
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def measure(pose):
    size = pose['imageSize']
    if len(pose['people']) != 1:
        return None
    points = pose['people'][0]['landmarks']
    if len({p['joint'] for p in points}) != len(points):
        return None
    by_joint = {p['joint']: p for p in points}
    names = ['leftShoulder', 'rightShoulder', 'leftHip', 'rightHip']
    if any(name not in by_joint for name in names):
        return None
    values = [by_joint[n] for n in names]
    if any(not .5 <= p['confidence'] <= 1 or
           not 0 <= p['position']['x'] <= size['width'] or
           not 0 <= p['position']['y'] <= size['height'] for p in values):
        return None
    a, b, c, d = [p['position'] for p in values]
    short = min(size.values())
    return dict(shoulderFraction=math.hypot(a['x']-b['x'], a['y']-b['y'])/short,
                torsoFraction=math.hypot((a['x']+b['x']-c['x']-d['x'])/2,
                                         (a['y']+b['y']-c['y']-d['y'])/2)/short)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, default=ROOT/'build/grip-capture-v1/body-scale-audit.json')
    args = parser.parse_args()
    assert not args.output.exists()
    source = ROOT/'build/grip-anatomy-v11/fitting-restored-row-metadata.jsonl'
    policy = ROOT/'shared/src/commonMain/kotlin/dev/hanginthere/core/WorkoutGripCapture.kt'
    code = policy.read_text()
    minimum_shoulder = float(re.search(r'const val minimumShoulderFraction = ([.\d]+)', code)[1])
    minimum_torso = float(re.search(r'const val minimumTorsoFraction = ([.\d]+)', code)[1])
    rows = [json.loads(line) for line in source.read_text().splitlines()]
    counts = defaultdict(Counter)
    groups = defaultdict(lambda: defaultdict(set))
    examples = []
    for row in rows:
        measured = measure(row['pose'])
        split = row['annotation']['split']
        assert split in ['train', 'validation']
        group = row['annotation'].get('sourceGroup') or row['recordingId']
        for patch in row['patches']:
            if patch['status'] != 'patchReady':
                continue
            ref = patch['threewayReview']
            counts[split]['allPatches'] += 1
            relevant = ref['view'] in ['front', 'rear'] and ref['contact'] == 'closed'
            clear = ref['surface'] in ['palm', 'dorsum', 'side']
            counts[split]['reviewedSupportedClosed'] += int(relevant)
            if not relevant:
                continue
            category = 'readable' if clear else 'unclearOrMixed'
            counts[split][category+'All'] += 1
            if measured is None:
                counts[split][category+'PrimaryBodyUnavailable'] += 1
                continue
            counts[split][category+'BodyMeasurable'] += 1
            small = measured['shoulderFraction'] < minimum_shoulder and measured['torsoFraction'] < minimum_torso
            condition = 'Small' if small else 'ScaleSufficient'
            counts[split][category+condition] += 1
            groups[split][category+condition].add(group)
            examples.append(dict(id=ref['anatomyReviewId'], split=split, sourceGroup=group,
                recordingId=row['recordingId'], sourceImageSHA256=row['imageSHA256'],
                patchSHA256=patch['patchSHA256'], category=category,
                bodyScaleSmall=small, **measured))
    report = dict(qualification=__doc__, inputSHA256=sha(source), policySHA256=sha(policy), scriptSHA256=sha(Path(__file__)),
        thresholds=dict(minimumShoulderFraction=minimum_shoulder, minimumTorsoFraction=minimum_torso,
            smallRule='Both dimensions must be below target. Framing clipping/view/visibility handled separately in live policy.'),
        thresholdStatus='Initial engineering placement target, not a validated grip recognition cutoff or physical distance.',
        findings={split: dict(counts=counts[split], sourceGroups={k: len(v) for k, v in groups[split].items()}) for split in counts},
        limitations=['Public videos vary in resolution, compression, view and subject; no controlled phone-distance sweep.',
            'This audit uses primary 2D pose only. Live guidance can also use associated auxiliary hips; no claim of full live-admission equivalence.',
            'Large-body unclear crops remain; body scale cannot establish hand readability.',
            'Readable small-body examples also remain; conservative placement guidance can reject them.',
            'Repeated frames/patches are correlated; these are patch inventories, not independent success rates.'],
        examples=examples, modelTrained=False, finalReserveInspected=False)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2)+'\n')
    print(json.dumps(report['findings'], indent=2))


if __name__ == '__main__':
    main()
