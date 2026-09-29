#!/usr/bin/env python3
"""Prepare byte-bound, time-preserving real-video temporal diagnostics.

All source choices, native frame ranges, arm selections and event annotations
come from a recipe frozen before running the counter, never model predictions.
"""
from __future__ import annotations
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request

import evaluation as ev

SPEC = ev.ROOT / 'Evaluation/fixtures/temporal-pilot.json'
ALLOWED_HOSTS = {'upload.wikimedia.org', 'd34w7g4gy10iej.cloudfront.net', 'www.pexels.com'}


def command(args):
    return subprocess.check_output(args, text=True)


def probe(path):
    result = json.loads(command(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
                                 '-show_frames', '-show_streams', '-show_entries',
                                 'stream=width,height,sample_aspect_ratio:frame=best_effort_timestamp_time',
                                 '-of', 'json', str(path)]))
    pts = [float(f['best_effort_timestamp_time']) for f in result['frames']]
    ev.require(pts and all(ev.number(t) and t >= 0 for t in pts)
               and all(a < b for a, b in zip(pts, pts[1:])), 'Source needs monotonic presentation timestamps')
    stream = result['streams'][0]
    ev.require(stream.get('sample_aspect_ratio') in (None, '0:1', '1:1', 'N/A'), 'Unsupported pixel aspect ratio')
    return pts, [stream['width'], stream['height']]


def source_file(row, cache, fetch):
    url = urllib.parse.urlparse(row['url'])
    ev.require(url.scheme == 'https' and url.hostname in ALLOWED_HOSTS and not url.username and not url.query,
               'Unapproved temporal source URL')
    ev.require(ev.integer(row['bytes'], 1) and row['bytes'] <= 700_000_000, 'Source exceeds bound')
    path = cache / (row['source_sha256'] + Path(url.path).suffix)
    if not path.exists():
        ev.require(fetch, 'Missing pinned original; enable --fetch')
        cache.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=cache) as tmp:
            headers = {'User-Agent': 'Mozilla/5.0 HangInThere/0.1 test-only temporal evaluation'}
            if url.hostname == 'www.pexels.com': headers['Referer'] = row['page']
            req = urllib.request.Request(row['url'], headers=headers)
            with urllib.request.urlopen(req, timeout=120) as response:
                total = 0
                while block := response.read(1024 * 1024):
                    total += len(block); ev.require(total <= row['bytes'], 'Source grew beyond pin'); tmp.write(block)
            tmp.flush()
            ev.require(total == row['bytes'] and ev.digest(Path(tmp.name)) == row['source_sha256'], 'Source checksum mismatch')
            # Verified bounded original; never replace an existing cached source.
            with path.open('xb') as out, Path(tmp.name).open('rb') as source:
                while block := source.read(1024 * 1024): out.write(block)
    ev.require(path.stat().st_size == row['bytes'] and ev.digest(path) == row['source_sha256'], 'Wrong cached source')
    return path


def prepare(cache, output, fetch=False, spec_path=SPEC, only=None):
    spec = ev.read_json(spec_path)
    ev.require(spec['schema_version'] == 1 and spec['sources'], 'Missing temporal recipe')
    rows = spec['sources']
    if only:
        requested = set(only)
        ev.require(len(requested) == len(only), 'Duplicate temporal --only ID')
        rows = [row for row in rows if row['id'] in requested]
        ev.require({row['id'] for row in rows} == requested, 'Unknown temporal --only ID')
    output.mkdir(parents=True, exist_ok=False)
    clips, refs, credits = [], [], []
    for row in rows:
        source = source_file(row, cache, fetch)
        pts, size = probe(source)
        ev.require(len(pts) == row['source_frames'] and size == row['source_size'], 'Native source geometry/count changed')
        first, end = row['frame_range']
        ev.require(ev.integer(first) and ev.integer(end, 1) and first < end <= len(pts), 'Invalid continuous frame range')
        selected = pts[first:end]
        ev.require(abs(selected[0] - row['first_source_pts']) < 1e-5
                   and abs(selected[-1] - row['last_source_pts']) < 1e-5, 'Native range PTS mismatch')
        ev.require(row['id'].replace('_', '').isalnum(), 'Invalid temporal clip ID')
        video = output / (row['id'] + '.mp4')
        # Decode all original frames; select an explicit contiguous native range,
        # no interpolation, temporal resampling, stabilization or person crop.
        # Encode using the reviewed stream's nominal time base. Input millisecond
        # PTS may quantize by <0.5 ms; both PTS arrays and the maximum error survive.
        # The counter consumes decoded MP4 PTS, never an invented frame-index clock.
        command(['ffmpeg', '-v', 'error', '-i', str(source), '-map', '0:v:0', '-vf',
                 f'trim=start_frame={first}:end_frame={end},setpts=PTS-STARTPTS,scale=960:-2:flags=lanczos',
                 '-an', '-c:v', 'libx264', '-crf', '18', '-preset', 'veryfast',
                 '-fps_mode', 'passthrough', '-enc_time_base', row['encoder_time_base'], '-video_track_timescale', '90000', str(video)])
        derived, derived_size = probe(video)
        ev.require(len(derived) == len(selected) and all(abs(a - (b - selected[0])) <= .0005 for a, b in zip(derived, selected)),
                   'Transcode changed frame count or timing')
        ev.require(ev.digest(source) == row['source_sha256'], 'Original changed during preparation')
        credit = f"{row['id']}: {row['title']}\n{row['credit']}\n{row['page']}\n{row['license']}\n{row['license_url']}\nChanged: selected contiguous frames, silent H264 resize; no subject cropping, no added/removed interior frames. Test-only evaluation, no endorsement.\n"
        if row['license'].startswith('PD-'):
            credit += 'The appearance of U.S. Department of War (DoW) visual information does not imply or constitute DoW endorsement.\n'
        credits.append(credit)
        clip = {'id': row['id'], 'dataset': 'temporal-pilot-v1', 'exercise': {'pullUp': 'pull_up', 'dip': 'parallel_bar_dip'}[row['exercise']],
                'split': 'unassigned', 'source_group': row['source_group'], 'subject_group': None,
                'rights': {'status': 'approved', 'evidence': credit, 'public_outputs': True},
                'media': {'kind': 'video', 'files': [{'path': video.name, 'sha256': ev.digest(video)}], 'expected_frames': len(derived)}}
        offset = selected[0]
        refs.append({'schema_version': 1, 'id': row['id'], 'exercise': row['exercise'], 'side': row['side'],
                     'event_definition': {'pullUp': 'observed_start_to_top', 'dip': 'observed_top_bottom_top'}[row['exercise']],
                     'counter_policy_version': 3, 'reviewed_without_counter_output': True,
                     'provenance': spec['annotation_provenance'], 'form_verification': 'unverified',
                     'source_sha256': row['source_sha256'], 'recipe_sha256': ev.digest(spec_path),
                     'media_sha256': clip['media']['files'][0]['sha256'], 'source_frame_range': [first, end],
                     'source_pts_seconds': selected, 'frame_pts_seconds': derived,
                     'max_pts_quantization_seconds': max(abs(a - (b - selected[0])) for a, b in zip(derived, selected)),
                     'span_seconds': [derived[0], row['end_source_seconds'] - offset],
                     'events': [[a - offset, b - offset] for a, b in row['events_source_seconds']],
                     'bar_reference_edge': (
                         [row['bar_reference_edge_source'][0] * derived_size[0] / size[0],
                          row['bar_reference_edge_source'][1] * derived_size[1] / size[1],
                          row['bar_reference_edge_source'][2] * derived_size[0] / size[0],
                          row['bar_reference_edge_source'][3] * derived_size[1] / size[1]]
                         if row.get('bar_reference_edge_source') is not None else row.get('bar_reference_edge')
                     ),
                     'bar_reference_provenance': row.get('bar_reference_provenance'),
                     'ungradable_intervals': [{'seconds': [x - offset for x in u['seconds']], 'reason': u['reason']}
                                              for u in row['ungradable_source_intervals']],
                     'tolerance_seconds': spec['tolerance_seconds'], 'review_notes': row['review_notes']})
        clips.append(clip)
    manifest = {'schema_version': 1, 'clips': clips}
    ev.validate_manifest(manifest, output)
    score_spec = importlib.util.spec_from_file_location('score_temporal', Path(__file__).with_name('score-temporal.py'))
    scorer = importlib.util.module_from_spec(score_spec); score_spec.loader.exec_module(scorer)
    for ref, clip in zip(refs, clips): scorer.reference_check(ref, clip)
    ev.write_json(output / 'manifest.json', manifest)
    ev.write_json(output / 'reference.json', {'schema_version': 1, 'manifest_sha256': ev.digest(output / 'manifest.json'), 'clips': refs})
    (output / 'ATTRIBUTION.txt').write_text('\n'.join(credits))
    ev.write_json(output / 'prepared.json', {'recipe_sha256': ev.digest(spec_path), 'scope': spec['scope'],
                                           'ffmpeg': command(['ffmpeg', '-version']).splitlines()[0],
                                           'clips': len(clips), 'frames': sum(len(r['frame_pts_seconds']) for r in refs)})


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--cache', type=Path, required=True); p.add_argument('--output', type=Path, required=True)
    p.add_argument('--fetch', action='store_true')
    p.add_argument('--only', action='append', default=[], help='Prepare only this frozen clip ID; repeatable')
    a = p.parse_args()
    try: prepare(a.cache.resolve(), a.output.resolve(), a.fetch, only=a.only or None)
    except (ValueError, KeyError, OSError, subprocess.CalledProcessError) as error:
        print(f'Temporal preparation incomplete: {error}', file=sys.stderr); raise SystemExit(2)