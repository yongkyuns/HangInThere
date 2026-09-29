#!/usr/bin/env python3
"""Prepare a frozen, single-source visual-reference pilot; never a native dataset.

Exact source indices (not invented FPS) select disjoint source intervals. Labels
were frozen independently of Vision; byte pins bind the derived images to each
run. No download occurs here; the existing source preparation checks its rights.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys

import evaluation as ev

ROOT = Path(__file__).resolve().parents[1]
SPEC = ROOT / 'Evaluation/fixtures/public-pilot.json'


def run(command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def prepare(source, output):
    spec = ev.read_json(SPEC)
    ev.require(ev.digest(source) == spec['source_sha256'], 'Wrong original source for frozen visual labels')
    probe = json.loads(run(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_frames',
                           '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(source)]))
    pts = [float(f['best_effort_timestamp_time']) for f in probe['frames']]
    output.mkdir(parents=True, exist_ok=False)
    (output / 'labels').mkdir()
    clips = []
    for sequence in spec['sequences']:
        indices = sequence['source_frame_indices']
        ev.require(indices == sorted(set(indices)) and len(indices) == len(sequence['source_pts_seconds']), 'Bad frozen selection')
        ev.require(all(abs(pts[i] - t) < 1e-6 for i, t in zip(indices, sequence['source_pts_seconds'])), 'Source frame/PTS mismatch')
        directory = output / sequence['id']; directory.mkdir()
        select = '+'.join(f'eq(n\\,{i})' for i in indices)
        run(['ffmpeg', '-v', 'error', '-i', str(source), '-vf',
             f"select={select},scale={spec['image_width']}:{spec['image_height']}:flags=lanczos",
             '-fps_mode', 'passthrough', '-start_number', '0', str(directory / '%04d.png')])
        paths = sorted(directory.glob('*.png'))
        ev.require(len(paths) == len(indices), 'Missing or extra decoded images')
        files = [{'path': str(p.relative_to(output)), 'sha256': ev.digest(p)} for p in paths]
        reference = {'schema_version': 1, 'coordinates': 'upright_pixels_top_left', 'independently_reviewed': True,
                     'provenance': spec['annotation_provenance'], 'media_sha256': [f['sha256'] for f in files],
                     'source_sha256': spec['source_sha256'], 'label_definition_sha256': ev.digest(SPEC),
                     'source_frame_indices': indices, 'source_pts_seconds': sequence['source_pts_seconds'],
                     'frames': [{**frame, 'width': spec['image_width'], 'height': spec['image_height']}
                                for frame in sequence['labels']]}
        label_path = output / 'labels' / (sequence['id'] + '.json'); ev.write_json(label_path, reference)
        clip = {'id': sequence['id'], 'dataset': 'single-source-visual-pilot', 'exercise': 'pull_up', 'split': 'smoke',
                'source_group': spec['source_id'], 'subject_group': None,
                'rights': {'status': 'approved', 'evidence': spec['rights_evidence'], 'public_outputs': True},
                'media': {'kind': 'images', 'files': files, 'expected_frames': len(files)},
                'annotations': {'path': str(label_path.relative_to(output)), 'sha256': ev.digest(label_path)}}
        ev.validate_reference(reference, clip); clips.append(clip)
    manifest = {'schema_version': 1, 'confidence_threshold': .3, 'scope': spec['scope'], 'clips': clips}
    ev.validate_manifest(manifest, output)
    ev.write_json(output / 'manifest.json', manifest)
    ev.write_json(output / 'prepared.json', {'source_sha256': spec['source_sha256'], 'recipe_sha256': ev.digest(SPEC),
                  'ffmpeg_version': run(['ffmpeg', '-version']).splitlines()[0], 'scope': spec['scope'],
                  'sequences': len(clips), 'frames': sum(len(c['media']['files']) for c in clips)})


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        prepare(args.source.resolve(), args.output.resolve())
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f'Pilot preparation failed: {error}', file=sys.stderr)
        raise SystemExit(2)
