#!/usr/bin/env python3
"""Add an automatic hand/bar crop while preserving the original hand pixels."""
import argparse
import hashlib
import json
import math
from pathlib import Path

from PIL import Image


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def contact_box(box, size):
    width, height = size
    old_side = max(box[2] - box[0], box[3] - box[1])
    side = min(min(size), math.ceil(max(old_side, min(old_side * 1.6, min(size) * .5))))
    cx, cy = (box[0] + box[2]) / 2, (box[1] + box[3]) / 2
    x = min(max(0, math.floor(cx - side / 2)), width - side)
    y = min(max(0, math.floor(cy - side / 2)), height - side)
    return [x, y, x + side, y + side]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    assert not (args.output / 'rows.jsonl').exists()
    args.output.mkdir(parents=True, exist_ok=True)
    rows = [json.loads(line) for line in args.input.read_text().splitlines()]
    count = 0
    for row in rows:
        source = Path(row['framesDirectory']) / row['image']
        assert sha(source) == row['imageSHA256']
        with Image.open(source) as image:
            image = image.convert('RGB')
            for patch in row['patches']:
                if patch['status'] != 'patchReady':
                    continue
                assert sha(patch['patch']) == patch['patchSHA256']
                box = contact_box(patch['box'], image.size)
                output = args.output / (row['recordingId'] + '-' + row['imageSHA256'] + '-' + patch['side'] + '.png')
                image.crop(box).resize((192, 192), Image.Resampling.BILINEAR).save(output)
                patch['contactCrop'] = dict(image=str(output.absolute()), sha256=sha(output), box=box, size=192,
                                            geometry='Symmetric 1.6x original hand box; cap half short frame dimension without shrinking original box; clipped inside source', revision=4)
                count += 1
    (args.output / 'rows.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
    (args.output / 'receipt.json').write_text(json.dumps(dict(inputSHA256=sha(args.input), outputSHA256=sha(args.output / 'rows.jsonl'), scriptSHA256=sha(__file__), crops=count), indent=2) + '\n')
    print(json.dumps(dict(frames=len(rows), crops=count)))


if __name__ == '__main__':
    main()
