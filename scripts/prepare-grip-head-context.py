#!/usr/bin/env python3
"""Make an athlete-bound head crop at original resolution for view learning."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
from PIL import Image

SPEC = importlib.util.spec_from_file_location('context_base', Path(__file__).with_name('train-grip-context.py'))
base = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(base)


def head_box(row):
    person = row['pose']['people'][row['context']['personIndex']]
    points = {p['joint']: p['position'] for p in person['landmarks']}
    left, right = points['leftShoulder'], points['rightShoulder']
    span = ((left['x']-right['x'])**2 + (left['y']-right['y'])**2)**.5
    assert span > 0
    cx = (left['x']+right['x'])/2
    cy = (left['y']+right['y'])/2 - .675*span
    # Square enclosing the existing face-association head region; no face
    # detection is required, so back-of-head pixels are retained too.
    half = .825*span
    return [round(cx-half), round(cy-half), round(cx+half), round(cy+half)]


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('input', type=Path)
    p.add_argument('output', type=Path)
    p.add_argument('images', type=Path)
    args = p.parse_args()
    assert not args.output.exists(), 'Preserve prepared manifests'
    args.images.mkdir(parents=True, exist_ok=True)
    rows = base.load(args.input)
    count = 0
    for row in rows:
        if row['context']['status'] != 'ready':
            continue
        original = row['context']
        path = Path(row['framesDirectory']) / row['image']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == row['imageSHA256']
        box = head_box(row)
        target = args.images / (row['recordingId'] + '-' + row['imageSHA256'] + '.png')
        with Image.open(path) as image:
            image.convert('RGB').crop(box).resize((128, 128), Image.Resampling.BILINEAR).save(target)
        row['torsoContext'] = original
        row['context'] = dict(original, image=str(target.absolute()), sha256=hashlib.sha256(target.read_bytes()).hexdigest(),
                              box=box, geometry='shoulder-anchored head square, side=1.65 shoulder spans, centerY=-.675 span')
        count += 1
    args.output.write_text(''.join(json.dumps(r)+'\n' for r in rows))
    print(json.dumps(dict(rows=len(rows), headCrops=count, inputSHA256=hashlib.sha256(args.input.read_bytes()).hexdigest(),
                          outputSHA256=hashlib.sha256(args.output.read_bytes()).hexdigest(),
                          scriptSHA256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest())))


if __name__ == '__main__':
    main()
