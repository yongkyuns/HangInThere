#!/usr/bin/env python3
"""Frozen, source-screened public push-up baseline. Media remains outside Git."""
import argparse
from collections import defaultdict
import csv
import hashlib
import io
import json
from pathlib import Path
import re
import tarfile
import urllib.request

try:
    from . import public_workout_datasets as data, temporal_events
except ImportError:
    import public_workout_datasets as data
    import temporal_events


def validate(spec):
    assert spec['scope'] == 'public_pushup_benchmark_v1'
    cases = spec['cases']
    assert cases and len({c['id'] for c in cases}) == len(cases)
    assert len({c['sha256'] for c in cases}) == len(cases), 'Duplicate media'
    assert all(re.fullmatch(r'[A-Za-z0-9_-]+', c['id']) and
               re.fullmatch(r'[A-Za-z0-9_-]+\.mp4', c['source_name']) for c in cases)
    groups = defaultdict(set)
    for c in cases:
        assert c['role'] in ['development', 'test'] and c['count'] > 0
        groups[c['source_group']].add(c['role'])
    assert all(len(roles) == 1 for roles in groups.values()), 'Source family crosses roles'
    screened = {c['id'] for c in spec['screening'] if c['included']}
    assert screened == {c['id'] for c in cases}, 'Screening and selected sources differ'


def prepare(spec_path, cache, output, fetch=False):
    spec = json.loads(spec_path.read_text()); validate(spec)
    assert not output.exists(), 'Use a new output directory'
    cache.mkdir(parents=True, exist_ok=True)
    sources = {s['id']: data.pinned_source(s, cache, fetch) for s in spec['sources']}
    authored = {r['name']: r for r in csv.DictReader(io.StringIO(sources['repcount_train'].decode('utf-8-sig')))}
    mirror = {}
    cases = []
    for c in spec['cases']:
        path = cache / c['source_name']
        if not path.exists() and fetch:
            if c['dataset'] == 'countix':
                archive = spec['countix_archive']
                archive_path = cache/'k700_val_461.tar.gz'
                if not archive_path.exists():
                    archive_path.write_bytes(data.fetch(archive['url'], archive['bytes']))
                blob = archive_path.read_bytes()
                assert hashlib.sha256(blob).hexdigest() == archive['sha256']
                with tarfile.open(fileobj=io.BytesIO(blob)) as t:
                    member = t.getmember(c['archive_member']); assert member.isfile() and member.size == c['bytes']
                    path.write_bytes(t.extractfile(member).read(c['bytes']+1))
            else:
                # Evaluation-only optional dependency; no app or CI dependency.
                import lance
                with urllib.request.urlopen('https://huggingface.co/api/datasets/lmms-lab-eval/repcounta-lance', timeout=30) as f:
                    assert json.load(f)['sha'] == spec['repcount_mirror_revision'], 'Mirror revision changed'
                split = c['mirror_split']
                if split not in mirror:
                    mirror[split] = lance.dataset('hf://datasets/lmms-lab-eval/repcounta-lance/data/'+split+'.lance')
                ds = mirror[split]
                row = ds.scanner(columns=['source_name', 'count', 'cycle_bounds_json'],
                    filter="source_name = '"+c['source_name']+"'", with_row_id=True).to_table().to_pylist()
                assert len(row) == 1 and row[0]['count'] == c['count']
                assert json.loads(row[0]['cycle_bounds_json']) == [v for pair in c['cycle_frames'] for v in pair]
                path.write_bytes(ds.take_blobs('video_blob', ids=[row[0]['_rowid']])[0].read(c['bytes']+1))
        assert path.is_file() and path.stat().st_size == c['bytes']
        assert hashlib.sha256(path.read_bytes()).hexdigest() == c['sha256'], 'Media hash differs'
        if c['dataset'] == 'repcount_a':
            row = authored[c['source_name']]
            assert row['type'] in ['push_up', 'pushups'] and int(row['count']) == c['count']
            frames = [int(float(row['L'+str(i)])) for i in range(1, 2*c['count']+1)]
            assert frames == [v for pair in c['cycle_frames'] for v in pair], 'Author labels differ'
        else:
            with tarfile.open(fileobj=io.BytesIO(sources['countix_metadata'])) as t:
                csv_bytes = t.extractfile('countix/countix_val.csv').read()
            rows = csv.DictReader(io.StringIO(csv_bytes.decode()))
            assert any(r['video_id'] == c['video_id'] and r['class'] == 'push up' and int(r['count']) == c['count'] and
                       [float(r[k]) for k in ['kinetics_start', 'kinetics_end']] == c['kinetics_seconds'] and
                       [float(r[k]) for k in ['repetition_start', 'repetition_end']] == c['repetition_seconds'] for r in rows)
        pts, _, end = data.source_clock(path)
        assert len(pts) == c['expected_frames']
        labels = data.timed_labels(c, pts, end)
        cases.append(dict(c, path=str(path.resolve()), labels=labels, source_span_seconds=[pts[0], end]))
    output.mkdir(parents=True)
    manifest = dict(schema_version=1, scope=spec['scope'], spec_sha256=data.ev.digest(spec_path),
                    camera_ready_assumed=True, cases=cases)
    data.write_new(output/'manifest.json', manifest)
    data.write_new(output/'replay.json', dict(cases=[{k: c[k] for k in ['id', 'path', 'sha256']} for c in cases]))
    return manifest


def score(manifest, reports):
    """Missing/failed sources remain failures; clip-count agreement is not form accuracy."""
    assert len({r['id'] for r in reports}) == len(reports), 'Duplicate report'
    by_id = {r['id']: r for r in reports}
    assert set(by_id) <= {c['id'] for c in manifest['cases']}, 'Unknown report'
    rows = []
    for c in manifest['cases']:
        r = by_id.get(c['id'], {})
        if r: assert r['sourceSHA256'] == c['sha256'], 'Report source mismatch'
        complete = r.get('status') == 'completed' and r.get('frames') == c['expected_frames']
        a,b = c['labels']['count_window_seconds']
        events = [p['returnedAt'] for p in r.get('events', [])]
        assert events == sorted(events) and all(c['source_span_seconds'][0] <= t < c['source_span_seconds'][1] for t in events)
        predicted = sum(a <= t <= b for t in events)
        expected = c['count']
        ready = r.get('firstReadyAt')
        row = dict(id=c['id'], role=c['role'], view=c['view'], dataset=c['dataset'],
            status='completed' if complete else r.get('status', 'missing'), expected=expected, predicted=predicted,
            source_frame_count_matches=r.get('frames') == c['expected_frames'],
            exact_count=complete and predicted == expected, undercount=max(0, expected-predicted),
            overcount=max(0, predicted-expected), absolute_error=abs(expected-predicted),
            reached_ready=ready is not None, first_ready_seconds=ready,
            ready_delay_from_label_start_seconds=max(0, ready-a) if ready is not None else None,
            outside_labelled_window=sum(t < a or t > b for t in events),
            cues=r.get('cues', {}), counted_sets=len(r.get('sets', [])))
        # Annotation ends may mark a different phase; do not call this rep precision/recall.
        ends = c['labels']['cycle_end_seconds']
        if ends:
            row['published_cycle_end_diagnostic'] = temporal_events.match_events(c['source_span_seconds'],
                [[t,t] for t in ends], [], .5, events)
        rows.append(row)
    def aggregate(items):
        return dict(videos=len(items), completed_sources=sum(r['status']=='completed' for r in items),
            exact_videos=sum(r['exact_count'] for r in items), ready_videos=sum(r['reached_ready'] for r in items),
            expected=sum(r['expected'] for r in items), predicted=sum(r['predicted'] for r in items),
            undercount=sum(r['undercount'] for r in items), overcount=sum(r['overcount'] for r in items),
            mean_absolute_count_error=sum(r['absolute_error'] for r in items)/len(items) if items else None)
    return dict(scope='Published clip-count agreement; camera.ready assumed, no form/set-boundary/device qualification',
        summary=aggregate(rows), by_role={v:aggregate([r for r in rows if r['role']==v]) for v in ['development','test']},
        by_view={v:aggregate([r for r in rows if r['view']==v]) for v in sorted({r['view'] for r in rows})}, cases=rows)


def main():
    p = argparse.ArgumentParser(); commands = p.add_subparsers(dest='command', required=True)
    prep = commands.add_parser('prepare'); prep.add_argument('spec', type=Path); prep.add_argument('cache', type=Path); prep.add_argument('output', type=Path); prep.add_argument('--fetch', action='store_true')
    scoring = commands.add_parser('score'); scoring.add_argument('manifest', type=Path); scoring.add_argument('reports', type=Path, nargs='+'); scoring.add_argument('--output', type=Path, required=True)
    a = p.parse_args()
    if a.command == 'prepare': prepare(a.spec, a.cache, a.output, a.fetch)
    else:
        manifest = json.loads(a.manifest.read_text())
        reports = [r for path in a.reports for r in json.loads(path.read_text())]
        data.write_new(a.output, score(manifest, reports))

if __name__ == '__main__': main()
