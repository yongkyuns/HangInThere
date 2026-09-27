#!/usr/bin/env python3
"""Convert reviewed Penn Action MAT / HAA4D 2D NPY labels, without guessing rights.

Input: an evaluation manifest whose image clips have native_annotations and an
annotation_review. Output: pinned references + manifest only when all clips pass.
Optional host dependencies are in Evaluation/import-requirements.txt; none ship
in the app. No downloads, pose inference, visibility inference, or resampling.
"""
from __future__ import annotations
import argparse
from collections import Counter
from pathlib import Path
import sys

import evaluation as ev

# Penn's published 1-based list; head (index 0 here) is not Vision's nose.
PENN = {i: j for i, j in enumerate([
    None, 'leftShoulder', 'rightShoulder', 'leftElbow', 'rightElbow',
    'leftWrist', 'rightWrist', 'leftHip', 'rightHip', 'leftKnee',
    'rightKnee', 'leftAnkle', 'rightAnkle']) if j}
# The publisher archive uses 'pullup'; its README lists 'pull_ups'.
# Keep native strings in provenance and admit only these two verified literals.
PENN_PULL_UP_ACTIONS = frozenset({'pullup', 'pull_ups'})

# HAA4D's author-defined ordering. Hand != wrist; spine/neck conventions also
# differ. Use only common limb joints, not lifted/normalized 3D coordinates.
HAA = {1: 'rightHip', 2: 'rightKnee', 3: 'rightAnkle', 4: 'leftHip',
       5: 'leftKnee', 6: 'leftAnkle', 11: 'leftShoulder', 12: 'leftElbow',
       14: 'rightShoulder', 15: 'rightElbow'}
SOURCES = {
    'penn_action_mat': 'https://dreamdragon.github.io/PennAction/',
    'haa4d_npy': 'https://github.com/Morris88826/HAA4D/blob/0b15333a277e8fdf42b6dd6916f7a46cef389b96/libs/skeleton.py',
}


def numeric_array(value):
    import numpy as np
    array = np.asarray(value)
    ev.require(array.dtype.kind in 'biuf', 'Expected a real numeric array')
    return array


def penn_arrays(path):
    import numpy as np
    from scipy.io import loadmat
    data = loadmat(path, simplify_cells=True)
    data = data.get('annotation', data)
    ev.require(isinstance(data, dict), 'Expected named Penn annotation fields')
    n = numeric_array(data['nframes'])
    ev.require(n.size == 1 and np.isfinite(n).all() and float(n) >= 1
               and float(n).is_integer(), 'Invalid Penn frame count')
    count = int(n)
    dimensions = numeric_array(data['dimensions']).reshape(-1)
    ev.require(dimensions.shape == (3,) and np.isfinite(dimensions).all()
               and (dimensions > 0).all() and (dimensions == np.floor(dimensions)).all()
               and dimensions[2] == count, 'Invalid Penn dimensions')
    arrays = [numeric_array(data[k]) for k in ('x', 'y', 'visibility')]
    # scipy squeezes a one-frame sequence, but never silently transpose arrays.
    arrays = [a.reshape(1, 13) if count == 1 and a.shape == (13,) else a for a in arrays]
    ev.require(all(a.shape == (count, 13) for a in arrays), 'Expected Penn [frames,13] arrays')
    x, y, visibility = arrays
    ev.require(np.isin(visibility, [0, 1]).all(), 'Penn visibility must be binary')
    train = numeric_array(data['train'])
    ev.require(train.size == 1 and float(train) in (-1, 1), 'Unknown Penn train/test flag')
    return np.stack([x, y], axis=-1), visibility.astype(bool), {
        'action': str(data['action']), 'native_split': f'penn_train_flag:{int(train)}',
        'width': int(dimensions[1]), 'height': int(dimensions[0]), 'mapping': PENN,
    }


def haa_arrays(path, review):
    import numpy as np
    coordinates = numeric_array(np.load(path, allow_pickle=False))
    ev.require(coordinates.ndim == 3 and coordinates.shape[0] > 0
               and coordinates.shape[1:] == (17, 2), 'Expected raw HAA4D [frames,17,2], not 3D')
    entries = review.get('visible_frames')
    ev.require(isinstance(entries, list) and entries, 'HAA4D needs reviewed visibility; its exporter drops the flags')
    visibility = np.zeros(coordinates.shape[:2], dtype=bool)
    seen = set()
    for entry in entries:
        frame = entry['frame_index']
        ev.require(ev.integer(frame) and frame < len(coordinates) and frame not in seen, 'Invalid/duplicate visibility frame')
        seen.add(frame)
        joints = entry['visible_native_joints']
        ev.require(isinstance(joints, list) and all(ev.integer(j) and j < 17 for j in joints)
                   and len(set(joints)) == len(joints), 'Invalid/duplicate visible joint index')
        visibility[frame, joints] = True
    return coordinates, visibility, {'mapping': HAA, 'reviewed_frames': len(seen)}


def convert_clip(clip, root, output, review_sha256):
    from PIL import Image
    import numpy as np
    ev.require(clip['media']['kind'] == 'images', 'Native labels require ordered original images, not a guessed video FPS')
    native = clip.get('native_annotations', {})
    kind = native.get('format')
    ev.require(kind in SOURCES, 'Unknown native annotation format')
    label_path = ev.asset_path(root, native)
    ev.require(label_path.is_file() and ev.digest(label_path) == native['sha256'], 'Native annotation integrity failure')
    review = clip.get('annotation_review', {})
    ev.require(review.get('independently_reviewed') is True and isinstance(review.get('provenance'), str)
               and review['provenance'].strip(), 'Independent annotation review is required')
    origin = review.get('pixel_origin')
    ev.require(type(origin) is int and origin in (0, 1), 'Explicit reviewed pixel origin 0 or 1 required')
    if kind == 'penn_action_mat':
        coordinates, visible, info = penn_arrays(label_path)
        ev.require(info['action'] in PENN_PULL_UP_ACTIONS and clip['exercise'] == 'pull_up', 'This Penn importer selects pull_ups only')
    else:
        coordinates, visible, info = haa_arrays(label_path, review)
    files = clip['media']['files']
    ev.require(len(files) == len(coordinates), 'Images and native label rows differ')
    ev.require(clip['media'].get('expected_frames', len(files)) == len(files), 'Declared frame count differs from native sequence')
    expected_names = [f'{i + 1:06d}.jpg' if kind == 'penn_action_mat' else f'{i + 1:04d}.png' for i in range(len(files))]
    paths = [ev.asset_path(root, f) for f in files]
    ev.require([p.name for p in paths] == expected_names and len({p.parent for p in paths}) == 1,
               'Images must retain original one-based contiguous filenames and order')
    extension = '.jpg' if kind == 'penn_action_mat' else '.png'
    actual = {p.name for p in paths[0].parent.iterdir() if p.suffix.lower() == extension}
    ev.require(actual == set(expected_names), 'Extra or missing original sequence images')
    endpoints = review.get('endpoint_frames', [])
    ev.require(isinstance(endpoints, list) and len(set(endpoints)) == len(endpoints)
               and all(ev.integer(i) and i < len(files) for i in endpoints), 'Invalid endpoint indices')
    frames = []
    excluded = Counter()
    for index, path in enumerate(paths):
        with Image.open(path) as image:
            ev.require(image.getexif().get(274, 1) == 1, 'Original label/image EXIF transform needs review; no automatic rotation')
            width, height = image.size
            image.load()
        if kind == 'penn_action_mat':
            ev.require((width, height) == (info['width'], info['height']), 'Penn dimensions differ from actual image')
        points = {}
        for joint, name in info['mapping'].items():
            if not visible[index, joint]:
                excluded['hidden_or_unreviewed_joint'] += 1
                continue
            xy = coordinates[index, joint].astype(float) - origin
            ev.require(np.isfinite(xy).all() and 0 <= xy[0] < width and 0 <= xy[1] < height,
                       'Visible joint is nonfinite/outside the image; review rather than clamp it')
            points[name] = xy.tolist()
        if not points:
            excluded['frames_without_reviewed_points'] += 1
            ev.require(index not in endpoints, 'Endpoint has no visible reviewed points')
            continue
        frame = {'frame_index': index, 'width': width, 'height': height, 'points': points}
        if index in endpoints:
            frame['endpoint'] = True
        frames.append(frame)
    ev.require(frames, 'No visible common joints to score')
    reference = {'schema_version': 1, 'coordinates': 'upright_pixels_top_left', 'independently_reviewed': True,
                 'provenance': review['provenance'], 'media_sha256': [f['sha256'] for f in files], 'frames': frames,
                 'native_annotation_sha256': native['sha256'], 'conversion_review_sha256': review_sha256,
                 'conversion': {'version': 1, 'format': kind, 'pixel_origin': origin,
                                'joint_order_source': SOURCES[kind], 'native_split': info.get('native_split'),
                                'native_action': info.get('action'),
                                'omitted_native_joints': sorted(set(range(coordinates.shape[1])) - set(info['mapping'])),
                                'exclusions': dict(excluded)}}
    ev.validate_reference(reference, clip)
    destination = output / (clip['id'] + '.json')
    ev.write_json(destination, reference)
    converted = {k: v for k, v in clip.items() if k not in ('native_annotations', 'annotation_review', 'annotations')}
    converted['annotations'] = {'path': str(destination.relative_to(root)), 'sha256': ev.digest(destination)}
    converted['native_split'] = info.get('native_split', clip.get('native_split'))
    return converted, {'id': clip['id'], 'status': 'converted', 'images': len(files),
                       'labelled_frames': len(frames), 'native_annotation_sha256': native['sha256'],
                       'review_sha256': review_sha256, 'exclusions': dict(excluded),
                       'native_split': converted['native_split']}


def convert_manifest(path, root, output):
    root, output = root.resolve(), output.resolve()
    ev.require(output.is_relative_to(root) and output != root, 'Output must be a new child directory of data root')
    manifest = ev.read_json(path)
    clips = ev.validate_manifest(manifest, root)
    ev.require(all(not c.get('annotations') for c in clips), 'Input already contains converted annotations')
    output.mkdir(parents=True, exist_ok=False)
    converted, records = [], []
    review_sha = ev.digest(path)
    for clip in clips:
        status = ev.preflight(clip, root)
        if status != 'ready':
            records.append({'id': clip['id'], 'status': status})
            continue
        try:
            result, record = convert_clip(clip, root, output, review_sha)
            # Recheck inputs after parsing and before making a runnable manifest.
            ev.require(ev.preflight(clip, root) == 'ready' and ev.digest(ev.asset_path(root, clip['native_annotations']))
                       == clip['native_annotations']['sha256'], 'Inputs changed during conversion')
            converted.append(result)
            records.append(record)
        except (OSError, ValueError, KeyError, TypeError) as error:
            records.append({'id': clip['id'], 'status': 'conversion_rejected', 'reason': str(error)})
    ev.require(ev.digest(path) == review_sha, 'Review changed during conversion')
    report = {'schema_version': 1, 'review_sha256': review_sha, 'importer_sha256': ev.digest(__file__),
              'counts_by_status': dict(Counter(r['status'] for r in records)), 'clips': records,
              'scope': 'annotation conversion only; not model evaluation, new permissions, or training'}
    ev.write_json(output / 'import-report.json', report)
    if len(converted) != len(clips):
        return 2  # No runnable partial corpus silently excludes troublesome clips.
    result = {**manifest, 'clips': converted}
    ev.validate_manifest(result, root)
    ev.write_json(output / 'manifest.json', result)
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('review_manifest', type=Path)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        return convert_manifest(args.review_manifest, args.root, args.output)
    except ImportError:
        print('Install the optional host importer dependencies from Evaluation/import-requirements.txt.', file=sys.stderr)
        return 2
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f'Import rejected: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
