#!/usr/bin/env python3
"""Bounded detector-only crop proposals and separate subject association."""
import os
os.environ.setdefault('OMP_NUM_THREADS', '2')
import argparse
from collections import Counter, defaultdict
import hashlib
import importlib.util
import json
from pathlib import Path
import time

import mediapipe as mp
import numpy as np
from PIL import Image

spec = importlib.util.spec_from_file_location('v6_localization', Path(__file__).with_name('grip-localization-v6.py'))
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['input', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    assert not (args.output/'rows.jsonl').exists()
    root = Path(__file__).resolve().parents[1]
    model = root/'models/grip/hand_landmarker.task'
    expected = next(m['sha256'] for m in json.loads((root/'models/grip/manifest.json').read_text())['models'] if m['file'] == model.name)
    assert sha(model) == expected
    options = mp.tasks.vision.HandLandmarkerOptions(base_options=mp.tasks.BaseOptions(model_asset_path=str(model), delegate=mp.tasks.BaseOptions.Delegate.CPU),
             num_hands=4, min_hand_detection_confidence=.5, min_hand_presence_confidence=.5)
    rows = [json.loads(l) for l in args.input.read_text().splitlines()]
    rows.sort(key=lambda r: (r['recordingId'], r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']))
    output, counts = [], Counter()
    previous, anchor, track, last_time, old_pixels, face_hits = [], None, 0, None, None, 0
    source = None
    start = time.perf_counter()
    with mp.tasks.vision.HandLandmarker.create_from_options(options) as sdk:
        for row in rows:
            path = Path(row['framesDirectory'])/row['image']
            assert sha(path) == row['imageSHA256']
            image = Image.open(path).convert('RGB')
            assert image.size == (row['pose']['imageSize']['width'], row['pose']['imageSize']['height'])
            assert row['localizationFaces']['sourceSHA256'] == row['recordingId'] and row['localizationFaces']['imageSHA256'] == row['imageSHA256']
            ts = row['pose']['timestamp']
            seconds = ts['value']/ts['timescale']
            owner, status = policy.primary(row)
            pixels = np.asarray(image.resize((32, 32)), dtype=float)/255
            cut = old_pixels is not None and float(np.mean(np.abs(pixels-old_pixels))) > .25
            reset = source != row['recordingId'] or cut or (last_time is not None and seconds-last_time > 1.25)
            if owner is not None and anchor is not None:
                reset |= owner['kind'] != anchor['kind'] or math_distance(owner['center'], anchor['center']) > max(owner['scale'], anchor['scale'])*1.25
            if reset:
                previous, anchor = [], None
                track += 1
                face_hits = 0
            if owner is None:
                previous, anchor = [], None
                face_hits = 0
            elif anchor is None:
                track += 1
            if owner is not None and owner['kind'] == 'face':
                face_hits = face_hits+1 if anchor is not None and anchor['kind'] == 'face' and math_distance(owner['center'], anchor['center']) <= max(owner['scale'], anchor['scale'])*.5 else 1
                owner['confirmed'] = face_hits >= 2
                if not owner['confirmed']:
                    status = 'faceProxyAcquiring'
            else:
                face_hits = 0
            source, last_time, old_pixels = row['recordingId'], seconds, pixels
            requests = policy.regions(image.size, owner, previous)
            detected = []
            for roi in requests:
                x0, y0, x1, y1 = roi
                sample = np.ascontiguousarray(np.asarray(image)[y0:y1, x0:x1])
                result = sdk.detect(mp.Image(image_format=mp.ImageFormat.SRGB, data=sample))
                counts['handModelCalls'] += 1
                for hand in result.hand_landmarks:
                    pts = [[x0+p.x*(x1-x0), y0+p.y*(y1-y0)] for p in hand]
                    c = policy.candidate(pts, image.size, roi)
                    if c is not None:
                        detected.append(c)
            candidates = policy.deduplicate(detected)
            accepted = []
            for c in candidates:
                owned = policy.ownership(c, owner, row, previous)
                if owned is not None:
                    c['owner'] = owned
                    accepted.append(c)
            conflicts = {i for i, c in enumerate(accepted) for j, d in enumerate(accepted) if i != j and
                         (c['owner']['slot'] == d['owner']['slot'] or policy.iou(c['box'], d['box']) > .35)}
            for i in sorted(conflicts, reverse=True):
                accepted.pop(i)
                counts['ambiguousHandRejected'] += 1
            # Persist no previous pixels as current evidence: continuity only
            # supplies the next search region. Every emitted crop is detected now.
            previous, anchor = accepted, owner
            out = dict(caseId=row['caseId'], recordingId=row['recordingId'], image=row['image'], imageSHA256=row['imageSHA256'],
                       framesDirectory=row['framesDirectory'], seconds=seconds, timestamp=ts, sourceGroup=row['sourceGroup'],
                       trackId=track if owner is not None else None, primaryStatus=status, owner=owner,
                       detectorCalls=len(requests), proposedHands=len(candidates), patches=[])
            counts['frames'] += 1
            counts['unownedProposals'] += len(candidates)-len(accepted)
            for i, c in enumerate(accepted):
                candidate_id = f"{row['recordingId'][:12]}-{row['imageSHA256'][:12]}-{i}"
                target = args.output/(candidate_id+'.png')
                image.crop(c['box']).resize((128, 128), Image.Resampling.BILINEAR).save(target)
                out['patches'].append(dict(c, status='patchReady', candidateId=candidate_id, patch=str(target.absolute()),
                    patchSHA256=sha(target), sharpness=policy.sharpness(image, c['box']), origin='detectedHandOnly'))
                counts['patchReady'] += 1
            output.append(out)
    (args.output/'rows.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in output))
    selected = policy.select_clearer(output)
    (args.output/'selected.jsonl').write_text(''.join(json.dumps(r)+'\n' for r in selected))
    (args.output/'receipt.json').write_text(json.dumps(dict(inputSHA256=sha(args.input), modelSHA256=expected, scriptSHA256=sha(__file__),
        policySHA256=sha(policy.__file__), counts=dict(counts), selectedCrops=len(selected), seconds=time.perf_counter()-start,
        scope='Offline localization; no grip training or body-wrist-only output crops. Face-only ownership is a measured proxy, not proof.'), indent=2)+'\n')
    print(json.dumps(dict(counts=counts, selectedCrops=len(selected))))


def math_distance(a, b):
    return float(np.linalg.norm(np.array(a)-b))


if __name__ == '__main__':
    main()
