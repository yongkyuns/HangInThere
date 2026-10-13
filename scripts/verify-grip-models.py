#!/usr/bin/env python3
"""Verify the production model inputs; no private footage is read."""
import hashlib
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1] / 'models/grip'
manifest = json.loads((root / 'manifest.json').read_text())
assert manifest['schemaVersion'] == 1
assert manifest['coordinateConvention'] == 'cameraRightDownAway_v1'
assert {m['file'] for m in manifest['models']} == {'hand_landmarker.task', 'pose_landmarker_lite.task'}
for model in manifest['models']:
    data = (root / model['file']).read_bytes()
    assert len(data) == model['bytes'], model['file']
    assert hashlib.sha256(data).hexdigest() == model['sha256'], model['file']
print('Grip model checksums verified')
