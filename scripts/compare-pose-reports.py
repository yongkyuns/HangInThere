#!/usr/bin/env python3
"""Compare completed, provenance-matched reports without ranking the backends."""
from __future__ import annotations
import argparse
from collections import Counter
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parent))
import evaluation as ev


def indexed(report):
    rows = report['clips']
    ev.require(isinstance(rows, list) and rows, 'Empty report')
    ids = [x['id'] for x in rows]
    ev.require(len(ids) == len(set(ids)), 'Duplicate clip ID')
    ev.require(report['counts_by_status'] == dict(Counter(x['status'] for x in rows)),
               'Report status summary differs from clips')
    ev.require(all(x['status'] == 'processed' for x in rows), 'Incomplete evaluation')
    return {x['id']: x for x in rows}


def metric(summary):
    total, count = summary['reference_count'], summary['measured_count']
    ev.require(ev.integer(total) and ev.integer(count) and count <= total, 'Invalid metric counts')
    ev.require(summary['coverage'] == (count / total if total else None), 'Invalid coverage')
    for name in ('mean', 'p95'):
        value = summary[name]
        ev.require((ev.number(value) and value >= 0) if count else value is None, 'Invalid metric value')
    return {k: summary[k] for k in ('reference_count', 'measured_count', 'coverage', 'mean', 'p95')}


def describe(row):
    pose = row['pose_metrics']
    joints = {j: metric(value) for j, value in sorted(pose['joint_pixels'].items())}
    total = sum(x['reference_count'] for x in joints.values())
    count = sum(x['measured_count'] for x in joints.values())
    mean = sum(x['mean'] * x['measured_count'] for x in joints.values() if x['measured_count']) / count if count else None
    return {'backend': row['backend'], 'processing_ms': row['processing_ms'], 'joint_pixels': joints,
            # A pooled p95 cannot be obtained by averaging joint p95 values.
            'joint_summary': {'reference_count': total, 'measured_count': count,
                              'coverage': count / total if total else None, 'mean': mean},
            'elbow_degrees': {side: metric(pose['elbow_degrees'][side]) for side in ('left', 'right')},
            'ambiguous_annotated_frames': pose['ambiguous_person_frames'],
            'missing_person_annotated_frames': pose['missing_person_frames']}


def compare(vision, mp):
    ev.require(vision['manifest_sha256'] == mp['manifest_sha256'], 'Different manifest')
    ev.require(vision.get('source_commit') and vision['source_commit'] == mp.get('source_commit'),
               'Different or absent source revisions')
    ev.require(vision['source_files_sha256']['scripts/evaluation.py'] ==
               mp['source_files_sha256']['scripts/evaluation.py'], 'Different scoring code')
    ev.require(vision['confidence_threshold'] == mp['confidence_threshold'], 'Different scorer thresholds')
    va, mb = indexed(vision), indexed(mp)
    ev.require(set(va) == set(mb), 'Different clips')
    clips = []
    for cid in sorted(va):
        v, m = va[cid], mb[cid]
        for key in ('dataset', 'exercise', 'split', 'source_group', 'subject_group',
                    'frames', 'media_sha256', 'annotation_sha256'):
            ev.require(key in v and key in m and v[key] == m[key], f'{cid}: mismatched {key}')
        ev.require(ev.integer(v['frames'], 1) and v['media_sha256'] and v['annotation_sha256'],
                   f'{cid}: missing completed-input provenance')
        for row in (v, m):
            ev.require(row['pose_metrics'].get('status') == 'measured_2d_only', 'Missing reviewed labels')
            ev.require(row['pose_metrics']['person_policy'] == 'exactly_one_prediction_no_reference_based_selection',
                       'Different person-selection policy')
        vd, md = describe(v), describe(m)
        ev.require(set(vd['joint_pixels']) == set(md['joint_pixels']), 'Different reference joints')
        for field in ('joint_pixels', 'elbow_degrees'):
            for key in vd[field]:
                ev.require(vd[field][key]['reference_count'] == md[field][key]['reference_count'],
                           'Different reference support')
        clips.append({'id': cid, 'frames': v['frames'], 'media_sha256': v['media_sha256'],
                      'annotation_sha256': v['annotation_sha256'],
                      'reference_points': vd['joint_summary']['reference_count'], 'vision': vd, 'mediapipe': md})
    return {'schema_version': 2, 'manifest_sha256': vision['manifest_sha256'],
            'source_commit': vision['source_commit'], 'confidence_threshold': vision['confidence_threshold'],
            'timing_comparable': False,
            'scope': 'Same reviewed input files and labels; measured support may differ. Confidence is not cross-model calibrated. No speedup, winner, rep, form or 3D claim.',
            'clips': clips}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('vision', type=Path)
    parser.add_argument('mediapipe', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    try:
        result = compare(ev.read_json(args.vision), ev.read_json(args.mediapipe))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        ev.write_json(args.output, result)
    except (ValueError, KeyError, TypeError, OSError) as error:
        print(f'Comparison rejected ({type(error).__name__}); reports must be complete and matched.', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
