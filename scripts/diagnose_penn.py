#!/usr/bin/env python3
"""Isolated investigation: retain original data with mismatched metadata for private review.

This is NOT the strict intake path, an approved manifest, or a successful native
conversion. The original stager/importer still reject unreviewed geometry.
"""
from pathlib import Path
from collections import Counter
import shutil
from PIL import Image
import evaluation as ev
import import_poses as imp
import stage_penn as intake

archive = Path('Data/external/Penn_Action.tar.gz')
root = Path('Data/local/penn-intake')
pin = 'e3e41bd99deb7b3beb9785f78b209de272bb0fa60ff4432ffa88118907a797de'
acquisition = intake.download(archive, pin)
inventory = intake.inspect_archive(archive)
selected = [r for r in inventory['annotations'] if r['action'] in imp.PENN_PULL_UP_ACTIONS][:6]
assert [r['sequence_id'] for r in selected] == ['1149', '1150', '1151', '1152', '1153', '1154']
assert not root.exists()
root.mkdir(parents=True)
wanted = {r['sequence_id']: r for r in selected}
for tar, member, path in intake.members(archive):
    parsed = intake.native_path(path)
    if parsed and parsed[1] in wanted:
        kind, identifier, frame = parsed
        target = root / ('labels/' + identifier + '.mat' if kind == 'label' else f'frames/{identifier}/{frame:06d}.jpg')
    elif 'tools' in path.parts and path.suffix == '.m' and member.size < 100000:
        target = root / 'native-tools' / path.name
    else:
        continue
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open('xb') as output:
        shutil.copyfileobj(tar.extractfile(member), output)
    assert target.stat().st_size == member.size
clips, geometry = [], []
for row in selected:
    identifier = row['sequence_id']
    label = root / 'labels' / f'{identifier}.mat'
    assert ev.digest(label) == row['native_annotation_sha256']
    files, sizes, formats, exif = [], Counter(), Counter(), Counter()
    for frame in range(1, row['frames'] + 1):
        p = root / 'frames' / identifier / f'{frame:06d}.jpg'
        with Image.open(p) as image:
            image.load()
            sizes[f'{image.width}x{image.height}'] += 1
            formats[image.format] += 1
            exif[str(image.getexif().get(274, 1))] += 1
        files.append({'path': p.relative_to(root).as_posix(), 'sha256': ev.digest(p)})
    geometry.append({'id': identifier, 'native_width': row['width'], 'native_height': row['height'],
                     'actual_size_counts': dict(sizes), 'formats': dict(formats), 'exif': dict(exif)})
    clips.append({'id': f'penn_{identifier}', 'dataset': 'Penn Action', 'exercise': 'pull_up',
                  'split': 'unassigned', 'source_group': 'penn_unresolved_sources', 'subject_group': None,
                  'rights': {'status': 'pending', 'evidence': '', 'public_outputs': False},
                  'media': {'kind': 'images', 'expected_frames': row['frames'], 'files': files},
                  'native_annotations': {'format': 'penn_action_mat', 'path': label.relative_to(root).as_posix(),
                                         'sha256': ev.digest(label)},
                  'annotation_review': {'independently_reviewed': False, 'provenance': '', 'pixel_origin': None}})
assert ev.digest(archive) == pin
manifest = {'schema_version': 1, 'confidence_threshold': 0.3, 'clips': clips}
ev.validate_manifest(manifest, root)
ev.write_json(root / 'review.json', manifest)
ev.write_json(root / 'acquisition.json', acquisition)
report = {'schema_version': 1, 'status': 'geometry_diagnostic_not_validated_intake',
          'archive_sha256': pin, 'selected_sequences': len(clips), 'selected_frames': sum(len(c['media']['files']) for c in clips),
          'geometry': geometry, 'native_annotations_inspected': len(inventory['annotations']),
          'model_inference': 'not_run', 'permissions': 'pending', 'native_import': 'not_run'}
ev.write_json(root / 'intake-report.json', report)
print(report, flush=True)
