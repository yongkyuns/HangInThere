#!/usr/bin/env python3
"""Prepare the reviewed five-source pilot; no model inference or label fitting.

Original bytes, frame indices and presentation times are pinned. Both backends
consume the resulting identical upright PNGs. This is an unassigned diagnostic
corpus, not an independently adjudicated or subject-held-out accuracy benchmark.
"""
from __future__ import annotations
import argparse
import json
import re
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

from PIL import Image, ImageOps
import evaluation as ev

SPEC = Path(__file__).resolve().parents[1] / 'Evaluation/fixtures/source-pilot.json'


def command(args):
    return subprocess.run(args, check=True, capture_output=True, text=True).stdout


def original(row, cache, fetch):
    path = cache / (row['id'] + Path(urllib.parse.urlparse(row['url']).path).suffix)
    if not path.exists():
        ev.require(fetch, f"Missing source {row['id']}; use --fetch to acquire the reviewed original")
        url = urllib.parse.urlparse(row['url'])
        ev.require(url.scheme == 'https' and url.hostname == 'upload.wikimedia.org'
                   and not url.username and not url.query, 'Unapproved source URL')
        ev.require(ev.integer(row['bytes'], 1) and row['bytes'] <= 20_000_000, 'Invalid source size')
        cache.mkdir(parents=True, exist_ok=True)
        req = urllib.request.Request(row['url'], headers={
            'User-Agent': 'HangInThere/0.1 (https://github.com/yongkyuns/HangInThere; evaluation)'})
        # No unbounded retry or metadata API requests. HTTP errors are fatal.
        with tempfile.NamedTemporaryFile(dir=cache) as tmp:
            with urllib.request.urlopen(req, timeout=90) as response:
                data = response.read(row['bytes'] + 1)
            ev.require(len(data) == row['bytes'], 'Original source byte count changed')
            tmp.write(data); tmp.flush()
            ev.require(ev.digest(Path(tmp.name)) == row['sha256'], 'Original source checksum changed')
            path.write_bytes(data)
    ev.require(path.stat().st_size == row['bytes'] and ev.digest(path) == row['sha256'],
               f"Wrong original bytes for {row['id']}; refusing stale cache or changed source")
    return path


def render(row, source, directory):
    width, height = row['image_size']
    ev.require(all(ev.integer(d, 1) for d in (width, height)), 'Invalid output dimensions')
    if row['kind'] == 'image':
        with Image.open(source) as image:
            ev.require(image.getexif().get(274, 1) == row['expected_exif'], 'EXIF orientation changed')
            ev.require(row['orientation'] in ('exif', 'raw-reviewed'), 'Unreviewed orientation policy')
            upright = (ImageOps.exif_transpose(image) if row['orientation'] == 'exif' else image).convert('RGB')
            ev.require(list(upright.size) == row['source_size'], 'Original upright dimensions changed')
            scale = min(1, 960 / max(upright.size))
            ev.require([round(d * scale) for d in upright.size] == [width, height], 'Unsupported resize geometry')
            resized = upright.resize((width, height), Image.Resampling.LANCZOS)
            Image.frombytes('RGB', resized.size, resized.tobytes()).save(directory / '0000.png')
    else:
        ev.require(row['kind'] == 'video', 'Unsupported source kind')
        indices, expected_pts = row['source_frame_indices'], row['source_pts_seconds']
        ev.require(indices and all(ev.integer(i) for i in indices)
                   and indices == sorted(set(indices)) and len(indices) == len(expected_pts), 'Invalid frame selection')
        probe = json.loads(command(['ffprobe', '-v', 'error', '-select_streams', 'v:0', '-show_frames',
                                    '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(source)]))
        pts = [float(f['best_effort_timestamp_time']) for f in probe['frames']]
        ev.require(len(pts) == row['source_frame_count'], 'Source frame count changed')
        ev.require(all(i < len(pts) and ev.number(t) and abs(pts[i] - t) < 1e-6
                       for i, t in zip(indices, expected_pts)), 'Source frame/PTS mismatch')
        select = '+'.join(f'eq(n\\,{i})' for i in indices)
        command(['ffmpeg', '-v', 'error', '-i', str(source), '-vf',
                 f'select={select},scale={width}:{height}:flags=lanczos', '-fps_mode', 'passthrough',
                 '-start_number', '0', str(directory / '%04d.png')])
    paths = sorted(directory.glob('*.png'))
    expected = 1 if row['kind'] == 'image' else len(row['source_frame_indices'])
    ev.require(len(paths) == expected, 'Missing or extra derivative frames')
    for path in paths:
        with Image.open(path) as image:
            ev.require(image.size == (width, height) and image.mode == 'RGB'
                       and image.getexif().get(274, 1) == 1, 'Invalid upright RGB derivative')
    return paths


def prepare(cache, output, fetch=False, spec_path=SPEC):
    spec = ev.read_json(spec_path)
    ev.require(spec['schema_version'] == 1 and spec['sources'], 'Empty or unsupported source recipe')
    ids = [r['id'] for r in spec['sources']]
    ev.require(len(ids) == len(set(ids)) and all(isinstance(i, str) and re.fullmatch(r"[A-Za-z0-9_-]{1,100}", i) for i in ids), 'Invalid/duplicate source IDs')
    ev.require(all(r['exercise'] in ev.EXERCISES and r['labels'] for r in spec['sources']), 'Missing exercise or reviewed labels')
    output.mkdir(parents=True, exist_ok=False)
    (output / 'labels').mkdir()
    clips, credits = [], []
    for row in spec['sources']:
        source = original(row, cache, fetch)
        directory = output / row['id']; directory.mkdir()
        paths = render(row, source, directory)
        ev.require(ev.digest(source) == row['sha256'], 'Original changed during rendering')
        files = [{'path': str(p.relative_to(output)), 'sha256': ev.digest(p)} for p in paths]
        ref = {'schema_version': 1, 'coordinates': 'upright_pixels_top_left', 'independently_reviewed': True,
               'provenance': spec['annotation_provenance'], 'media_sha256': [f['sha256'] for f in files],
               'source_sha256': row['sha256'], 'label_definition_sha256': ev.digest(spec_path),
               'frames': [{**f, 'width': row['image_size'][0], 'height': row['image_size'][1]} for f in row['labels']]}
        if row['kind'] == 'video':
            ref.update(source_frame_indices=row['source_frame_indices'], source_pts_seconds=row['source_pts_seconds'])
        label_path = output / 'labels' / (row['id'] + '.json'); ev.write_json(label_path, ref)
        credit = f"{row['id']}: {row['title']}\n{row['credit']}\n{row['page']}\n{row['license']}\n{row['license_url']}\n{spec['changes']}\nNo endorsement or promotional use.\n"
        if row['license'] == 'PD-US-Marines':
            credit += 'The appearance of U.S. Department of War (DoW) visual information does not imply or constitute DoW endorsement.\n'
        credits.append(credit)
        clip = {'id': row['id'], 'dataset': 'reviewed-source-pilot-v1', 'exercise': row['exercise'],
                'split': 'unassigned', 'source_group': row['source_group'], 'subject_group': None,
                'rights': {'status': 'approved', 'evidence': credit, 'public_outputs': True},
                'media': {'kind': 'images', 'files': files, 'expected_frames': len(files)},
                'annotations': {'path': str(label_path.relative_to(output)), 'sha256': ev.digest(label_path)}}
        ev.validate_reference(ref, clip); clips.append(clip)
    manifest = {'schema_version': 1, 'confidence_threshold': .3, 'scope': spec['scope'], 'clips': clips}
    ev.validate_manifest(manifest, output)
    (output / 'ATTRIBUTION.txt').write_text('\n'.join(credits))
    ev.write_json(output / 'prepared.json', {'recipe_sha256': ev.digest(spec_path), 'scope': spec['scope'],
                  'original_sha256': {r['id']: r['sha256'] for r in spec['sources']},
                  'frames': sum(len(c['media']['files']) for c in clips), 'sequences': len(clips),
                  'ffmpeg_version': command(['ffmpeg', '-version']).splitlines()[0]})
    # A failed preparation never publishes a manifest for a smaller corpus.
    ev.write_json(output / 'manifest.json', manifest)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cache', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--fetch', action='store_true')
    args = parser.parse_args()
    try:
        prepare(args.cache.resolve(), args.output.resolve(), args.fetch)
    except (OSError, ValueError, KeyError, subprocess.CalledProcessError) as error:
        print(f'Source preparation failed: {error}', file=sys.stderr)
        raise SystemExit(2)
