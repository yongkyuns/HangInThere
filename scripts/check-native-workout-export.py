#!/usr/bin/env python3
"""Check shipping Apple FFI normalization with the frozen Python scorer.

Authored interoperability contract only. Labels here are not independent athlete
observations and this result cannot qualify motion accuracy or automatic setup.
"""
import argparse
import copy
import json
from pathlib import Path
from workout_benchmark import freeze, score

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('session', type=Path)
args = parser.parse_args()
row = json.loads(args.session.read_text())
assert row['id'] == 'native_authored' and row['capture_sha256'] == 'a' * 64
assert row['span_seconds'] == [0, 4] and row['status'] == 'processed'
assert row['observed_intervals'] == [[0, .75], [2, 2.75]]
assert [s['exercise'] for s in row['sets']] == ['pullUp', 'dip']
assert [s['completed_rep_seconds'] for s in row['sets']] == [[.75], [2.75]]
assert [s['seconds'] for s in row['sets']] == [[0, .75], [2, 2.75]]
sets = []
for i, measured in enumerate(row['sets']):
    expected = copy.deepcopy(measured)
    expected.update(id=f'set_{i}', start_window=[measured['seconds'][0]] * 2, end_window=None,
        completed_rep_windows=[[t, t] for t in measured.pop('completed_rep_seconds')])
    expected.pop('completed_rep_seconds')
    expected.pop('end_kind')
    sets.append(expected)
labels = dict(schema_version=1, id='native_authored_contract', evidence_class='synthetic_contract',
    timebase='source_pts_seconds', completed_cycle_definition='start_extreme_return', event_tolerance_seconds=.01,
    phase_tolerance_seconds=.01, automatic_end_grace_seconds=0, sessions=[dict(id=row['id'], participant_group='contract_actor', source_group='contract_capture',
        capture_sha256=row['capture_sha256'], split='test', context=dict(device_family='host', camera_view='front', lighting='bright', assistance='unassisted', apparatus='mixed'),
        review=dict(without_runtime_output=False, reviewer_groups=['contract_author'], adjudicated=False), span_seconds=row['span_seconds'],
        ungradable_intervals=[], sets=sets)])
# Keep the native event arrays intact; no calendar times or corrections are used.
row = json.loads(args.session.read_text())
lock = freeze(labels)
predictions = dict(schema_version=1, timebase='source_pts_seconds', completed_cycle_definition='start_extreme_return',
    benchmark_sha256=lock['benchmark_sha256'], run=dict(id='native_authored_contract', platform='ios', backend='authored',
        source_revision='a'*40, counter_policy_version=8, activity_metric_version=2, rep_metric_version=1, capture_mode='automatic'), sessions=[row])
result = score(lock, predictions)
assert result['summary']['completed_cycles']['true_positives'] == 2
assert result['summary']['completed_cycles']['false_positives'] == 0
assert result['summary']['completed_cycles']['false_negatives'] == 0
assert result['summary']['exact_set_count_fraction'] == 1
assert result['evidence_class'] == 'synthetic_contract'
print('Shipping Apple FFI source normalization/scorer interoperability passes (authored contract only).')
