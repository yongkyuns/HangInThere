import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import workout_benchmark as b

ROOT = Path(__file__).resolve().parents[2]


class WorkoutBenchmarkTests(unittest.TestCase):
    def setUp(self):
        self.labels = json.loads((ROOT / 'Evaluation/fixtures/workout-benchmark-synthetic.json').read_text())
        self.predictions = json.loads((ROOT / 'Evaluation/fixtures/workout-predictions-synthetic.json').read_text())
        self.lock = b.freeze(self.labels)

    def report(self):
        return b.score(self.lock, self.predictions, 'all')

    def refreeze(self):
        self.lock = b.freeze(self.labels)
        self.predictions['benchmark_sha256'] = self.lock['benchmark_sha256']

    def test_synthetic_contract_is_separate_from_accuracy_qualification(self):
        r = self.report()
        self.assertEqual(r['evidence_class'], 'synthetic_contract')
        self.assertIn('no strict-form or release verdict', r['scope'])
        self.assertEqual((r['participant_groups'], r['source_groups']), (4, 4))
        self.assertEqual(r['summary']['completed_cycles']['true_positives'], 3)
        self.assertEqual(r['summary']['exact_set_count_fraction'], 1)
        self.assertEqual(r['summary']['phase_agreement_all_labelled'], 1)
        self.assertEqual(r['summary']['phase_boundaries']['true_positives'], 12)
        self.assertEqual(r['summary']['phase_boundaries']['matched_error_seconds']['p95'], 0)
        self.assertEqual(r['by_exercise']['dip']['completed_cycles']['true_positives'], 1)
        self.assertEqual(r['by_context']['camera_view']['oblique']['sessions'], 1)
        self.assertEqual(r['scorer_sha256'], b.SCORER_SHA256)

    def test_default_test_split_requires_exact_test_membership(self):
        with self.assertRaises(b.BenchmarkError): b.score(self.lock, self.predictions)
        self.predictions['sessions'] = self.predictions['sessions'][2:]
        r = b.score(self.lock, self.predictions)
        self.assertEqual(r['summary']['sessions'], 2)
        self.assertEqual(r['summary']['completed_cycles']['true_positives'], 1)
        self.assertEqual(r['split'], 'test')

    def test_one_workout_can_contain_sequential_pullup_and_dip_sets(self):
        ref = self.labels['sessions'][0]; pred = self.predictions['sessions'][0]
        second = copy.deepcopy(self.labels['sessions'][1]['sets'][0]); second['id'] = 'second_set'
        for key in ('seconds', 'start_window', 'end_window'):
            second[key] = [t + 10 for t in second[key]]
        second['completed_rep_windows'] = [[t + 10 for t in window] for window in second['completed_rep_windows']]
        for phase in second['phases']: phase['seconds'] = [t + 10 for t in phase['seconds']]
        ref['sets'].append(second); ref['span_seconds'] = [0, 20]; ref['context']['apparatus'] = 'mixed'
        self.refreeze()
        pred['span_seconds'] = [0, 20]; pred['observed_intervals'] = [[0, 20]]
        pred['sets'].append(dict(exercise='dip', seconds=[11, 18], end_kind='disengaged', completed_rep_seconds=[16], phases=copy.deepcopy(second['phases'])))
        report = self.report()
        self.assertEqual(report['summary']['completed_cycles']['true_positives'], 4)
        self.assertEqual(report['summary']['exact_set_count_fraction'], 1)
        self.assertEqual(report['summary']['phase_agreement_all_labelled'], 1)
        self.assertEqual(report['by_exercise']['dip']['completed_cycles']['true_positives'], 2)

    def test_elapsed_metrics_are_invariant_to_large_source_clock_offset(self):
        before = self.report()['summary']
        for row in self.labels['sessions']:
            row['span_seconds'] = [t + 1e9 for t in row['span_seconds']]
            for item in row['sets']:
                for key in ('seconds', 'start_window', 'end_window'):
                    item[key] = [t + 1e9 for t in item[key]]
                item['completed_rep_windows'] = [[t + 1e9 for t in w] for w in item['completed_rep_windows']]
                for phase in item['phases']: phase['seconds'] = [t + 1e9 for t in phase['seconds']]
        self.refreeze()
        for row in self.predictions['sessions']:
            row['span_seconds'] = [t + 1e9 for t in row['span_seconds']]
            row['observed_intervals'] = [[t + 1e9 for t in w] for w in row['observed_intervals']]
            for item in row['sets']:
                item['seconds'] = [t + 1e9 for t in item['seconds']]
                item['completed_rep_seconds'] = [t + 1e9 for t in item['completed_rep_seconds']]
                for phase in item['phases']: phase['seconds'] = [t + 1e9 for t in phase['seconds']]
        after = self.report()['summary']
        for key in ('observed_seconds', 'correct_phase_seconds', 'phase_agreement_all_labelled'):
            self.assertAlmostEqual(before[key], after[key])

    def test_same_total_count_cannot_hide_wrong_timestamps(self):
        self.predictions['sessions'][0]['sets'][0]['completed_rep_seconds'] = [2]
        r = self.report()['summary']
        self.assertEqual(r['exact_set_count_fraction'], 1)
        self.assertEqual(r['completed_cycles']['false_positives'], 1)
        self.assertEqual(r['completed_cycles']['false_negatives'], 1)

    def test_duplicates_remain_false_positives(self):
        self.predictions['sessions'][0]['sets'][0]['completed_rep_seconds'] = [6, 6]
        r = self.report()['summary']
        self.assertEqual(r['completed_cycles']['precision'], .75)
        self.assertEqual(r['exact_set_count_fraction'], 2 / 3)

    def test_wrong_exercise_cannot_match_a_completed_cycle_or_parent(self):
        self.predictions['sessions'][0]['sets'][0]['exercise'] = 'dip'
        report = self.report(); r = report['summary']
        self.assertEqual(r['completed_cycles']['true_positives'], 2)
        self.assertEqual((r['completed_cycles']['false_positives'], r['completed_cycles']['false_negatives']), (1, 1))
        self.assertEqual((r['extra_sets'], r['missed_sets']), (1, 1))
        self.assertAlmostEqual(r['phase_agreement_all_labelled'], 2 / 3)
        self.assertEqual(report['by_exercise']['dip']['phase_boundaries']['false_positives'], 4)

    def test_extra_set_on_negative_footage_is_a_false_start_and_end(self):
        self.predictions['sessions'][-1]['sets'] = [{'exercise': 'dip', 'seconds': [2, 4], 'end_kind': 'disengaged', 'completed_rep_seconds': [], 'phases': []}]
        r = self.report()['summary']
        self.assertEqual(r['automatic_starts']['false_positives'], 1)
        self.assertEqual(r['automatic_ends']['false_positives'], 1)
        self.assertEqual(r['extra_sets'], 1)

    def test_duplicated_parent_cannot_duplicate_phase_credit(self):
        sets = self.predictions['sessions'][0]['sets']
        sets.append(copy.deepcopy(sets[0]))
        r = self.report()['summary']
        self.assertEqual(r['extra_sets'], 1)
        self.assertEqual(r['automatic_starts']['false_positives'], 1)
        self.assertEqual(r['phase_output_coverage'], 1)
        self.assertEqual(r['correct_phase_seconds'], r['labelled_phase_seconds'])

    def test_false_split_is_visible_even_if_global_rep_count_matches(self):
        row = self.predictions['sessions'][0]
        row['sets'] = [dict(exercise='pullUp', seconds=[1, 4], end_kind='trackingLost', completed_rep_seconds=[], phases=[]),
                       dict(exercise='pullUp', seconds=[4, 8], end_kind='disengaged', completed_rep_seconds=[6], phases=[])]
        r = self.report()['summary']
        self.assertEqual(r['completed_cycles']['true_positives'], 3)
        self.assertEqual(r['exact_set_count_fraction'], 2 / 3)
        self.assertEqual(r['automatic_starts']['false_positives'], 1)
        self.assertEqual(r['automatic_ends']['false_positives'], 1)

    def test_source_outage_never_earns_hold_time(self):
        self.predictions['sessions'][0]['observed_intervals'] = [[0, 2], [3, 10]]
        row = self.report()['sessions'][0]
        self.assertEqual(row['hold_duration']['hang']['reference_seconds'], 4)
        self.assertEqual(row['hold_duration']['hang']['predicted_credited_seconds'], 3)
        self.assertEqual(row['hold_duration']['hang']['signed_error_seconds'], -1)
        self.assertEqual(row['phase_agreement_when_reported'], 1)
        self.assertAlmostEqual(row['phase_agreement_all_labelled'], 6 / 7)
        self.assertAlmostEqual(row['observation_coverage'], .9)

    def test_unobserved_phase_cannot_make_partial_coverage_look_complete(self):
        self.predictions['sessions'][0]['sets'][0]['phases'] = [{'phase': 'unobserved', 'seconds': [1, 8]}]
        row = self.report()['sessions'][0]
        self.assertEqual(row['phase_output_coverage'], 0)
        self.assertEqual(row['phase_agreement_all_labelled'], 0)
        self.assertIsNone(row['phase_agreement_when_reported'])

    def test_unlabelled_phases_are_unavailable_instead_of_false_detections(self):
        self.labels['sessions'][0]['sets'][0]['phases'] = []
        self.refreeze()
        row = self.report()['sessions'][0]
        self.assertIsNone(row['phase_agreement_all_labelled'])
        metrics = row['phase_boundaries'].values()
        self.assertEqual(sum(m['false_positives'] for m in metrics), 0)
        self.assertEqual(sum(len(m['ignored_prediction_indices']) for m in metrics), 4)

    def test_processing_failure_is_retained_as_missed_evidence(self):
        self.predictions['sessions'][0].update(status='failed', sets=[], observed_intervals=[])
        r = self.report()['summary']
        self.assertEqual(r['failed_sessions'], 1)
        self.assertEqual(r['completed_cycles']['recall'], 2 / 3)
        self.assertEqual(r['missed_sets'], 1)
        self.assertEqual(r['hold_duration']['hang']['absolute_error_seconds']['mean'], 2)

    def test_manual_stop_does_not_claim_an_automatic_end(self):
        self.predictions['sessions'][0]['sets'][0]['end_kind'] = 'manual'
        r = self.report()['summary']['automatic_ends']
        self.assertEqual((r['true_positives'], r['false_negatives']), (2, 1))

    def test_censored_partial_has_no_completed_rep_or_invented_end(self):
        ref = self.labels['sessions'][0]['sets'][0]
        ref.update(end_window=None, completed_rep_windows=[])
        self.refreeze()
        pred = self.predictions['sessions'][0]['sets'][0]
        pred.update(end_kind='sourceEnd', completed_rep_seconds=[])
        row = self.report()['sessions'][0]
        self.assertEqual(row['completed_cycles']['reference_events'], 0)
        self.assertEqual(row['automatic_ends']['reference_events'], 0)
        self.assertEqual(row['exact_count_sets'], 1)

    def test_ungradable_time_is_explicit_and_cannot_qualify_full_set_counts(self):
        self.labels['sessions'][0]['ungradable_intervals'] = [[1.5, 2]]
        self.refreeze()
        self.predictions['sessions'][0]['sets'][0]['completed_rep_seconds'].insert(0, 1.75)
        row = self.report()['sessions'][0]
        self.assertEqual(row['count_gradable_sets'], 0)
        self.assertEqual(row['completed_cycles']['by_exercise']['pullUp']['ignored_prediction_indices'], [0])
        self.assertEqual(row['source_gradable_seconds'], 9.5)
        self.assertEqual(row['hold_duration']['hang']['reference_seconds'], 3.5)

    def test_shifted_phase_boundaries_measure_error_without_backdating(self):
        phases = self.predictions['sessions'][0]['sets'][0]['phases']
        phases[0]['seconds'][1] = 3.2; phases[1]['seconds'][0] = 3.2
        row = self.report()['sessions'][0]
        metric = row['phase_boundaries']['pullUp:hang->pulling']
        self.assertEqual(metric['true_positives'], 1)
        self.assertAlmostEqual(metric['matched_timing_error_seconds']['mean'], .2)
        self.assertAlmostEqual(row['correct_phase_seconds'], 6.8)

    def test_unsupported_discrete_outputs_are_rejected_instead_of_matching(self):
        for intervals in ([], [[0, .9], [1.1, 10]], [[0, 2.9], [3.1, 10]],
                          [[0, 5.9], [6.1, 10]], [[0, 7.9], [8.1, 10]]):
            prediction = copy.deepcopy(self.predictions)
            prediction['sessions'][0]['observed_intervals'] = intervals
            with self.assertRaisesRegex(b.BenchmarkError, 'observed source support'):
                b.score(self.lock, prediction, 'all')

    def test_isolated_observed_points_support_events_without_earning_time(self):
        self.predictions['sessions'][0]['observed_intervals'] = [[t, t] for t in (1, 3, 4, 4.5, 6, 8)]
        row = self.report()['sessions'][0]
        self.assertEqual(row['completed_cycles']['true_positives'], 1)
        self.assertEqual(row['observed_seconds'], 0)
        self.assertEqual(row['hold_duration']['hang']['predicted_credited_seconds'], 0)
        self.assertEqual(row['phase_output_coverage'], 0)

    def test_tracking_loss_cannot_match_positive_disengagement(self):
        self.predictions['sessions'][0]['observed_intervals'] = [[0, 7.9], [8.1, 10]]
        self.predictions['sessions'][0]['sets'][0]['end_kind'] = 'trackingLost'
        metric = self.report()['sessions'][0]['automatic_ends']
        self.assertEqual((metric['true_positives'], metric['false_positives'], metric['false_negatives']), (0, 1, 1))
        self.assertEqual(metric['false_positive_indices'], [0])
        self.assertEqual(metric['predicted_end_events'][0]['kind'], 'trackingLost')

    def test_frozen_end_grace_reports_latency_and_rejects_premature_stop(self):
        pred = self.predictions['sessions'][0]['sets'][0]
        pred['seconds'][1] = 9
        metric = self.report()['sessions'][0]['automatic_ends']
        self.assertEqual(metric['true_positives'], 1)
        self.assertAlmostEqual(metric['matched_timing_error_seconds']['mean'], .95)
        pred.update(seconds=[1, 7.4], phases=[])
        metric = self.report()['sessions'][0]['automatic_ends']
        self.assertEqual((metric['false_positives'], metric['false_negatives']), (1, 1))

    def test_correct_end_remains_matchable_after_a_tracking_loss_duplicate(self):
        pred = copy.deepcopy(self.predictions['sessions'][0]['sets'][0])
        pred.update(end_kind='trackingLost', seconds=[1, 7.9])
        pred['phases'] = []
        self.predictions['sessions'][0]['sets'].insert(0, pred)
        metric = self.report()['sessions'][0]['automatic_ends']
        self.assertEqual((metric['true_positives'], metric['false_positives'], metric['false_negatives']), (1, 1, 0))
        self.assertEqual(metric['matches'][0]['prediction_index'], 1)
        self.assertEqual(metric['false_positive_indices'], [0])

    def test_split_leakage_rejected_by_participant_source_and_capture(self):
        for key in ('participant_group', 'source_group', 'capture_sha256'):
            with self.subTest(key=key):
                labels = copy.deepcopy(self.labels)
                labels['sessions'][1][key] = labels['sessions'][0][key]
                with self.assertRaisesRegex(b.BenchmarkError, 'leakage'): b.freeze(labels)

    def test_field_labels_need_two_independent_adjudicated_reviewers(self):
        self.labels['evidence_class'] = 'independently_reviewed_sessions'
        with self.assertRaises(b.BenchmarkError): b.freeze(self.labels)
        for row in self.labels['sessions']:
            row['review'].update(without_runtime_output=True, adjudicated=True, reviewer_groups=['reviewer_a', 'reviewer_b'])
        self.refreeze()
        self.assertEqual(self.report()['evidence_class'], 'independently_reviewed_sessions')

    def test_frozen_annotations_splits_and_tolerances_cannot_change(self):
        for mutate in (lambda x: x['benchmark'].update(phase_tolerance_seconds=.5),
                       lambda x: x['benchmark']['sessions'][0].update(split='test'),
                       lambda x: x['benchmark']['sessions'][0]['sets'][0].update(completed_rep_windows=[])):
            lock = copy.deepcopy(self.lock); mutate(lock)
            with self.assertRaisesRegex(b.BenchmarkError, 'Frozen'): b.score(lock, self.predictions, 'all')

    def test_run_cannot_change_capture_span_definition_or_benchmark_binding(self):
        mutations = [lambda x: x.update(benchmark_sha256='f' * 64),
                     lambda x: x.update(completed_cycle_definition='accepted_at_peak'),
                     lambda x: x.update(timebase='nominal_fps'),
                     lambda x: x['sessions'][0].update(capture_sha256='f' * 64),
                     lambda x: x['sessions'][0].update(span_seconds=[0, 9])]
        for mutate in mutations:
            prediction = copy.deepcopy(self.predictions); mutate(prediction)
            with self.assertRaises(b.BenchmarkError): b.score(self.lock, prediction, 'all')

    def test_missing_negative_duplicate_or_failed_measurements_are_rejected(self):
        mutations = [lambda x: x['sessions'].pop(), lambda x: x['sessions'].append(copy.deepcopy(x['sessions'][0])),
                     lambda x: x['sessions'][0].update(status='failed')]
        for mutate in mutations:
            prediction = copy.deepcopy(self.predictions); mutate(prediction)
            with self.assertRaises(b.BenchmarkError): b.score(self.lock, prediction, 'all')

    def test_private_payloads_unknown_fields_and_boolean_versions_are_rejected(self):
        for key in ('video', 'landmarks', 'deviceId', 'confidence'):
            prediction = copy.deepcopy(self.predictions); prediction['sessions'][0][key] = 'private'
            with self.assertRaises(b.BenchmarkError): b.score(self.lock, prediction, 'all')
        self.predictions['run']['counter_policy_version'] = True
        with self.assertRaises(b.BenchmarkError): self.report()

    def test_invalid_nan_intervals_and_overlapping_phases_are_rejected(self):
        for intervals in ([[0, float('nan')]], [[0, 3], [2, 4]], [[2, 4], [0, 1]], [[0, 11]]):
            prediction = copy.deepcopy(self.predictions); prediction['sessions'][0]['observed_intervals'] = intervals
            with self.assertRaises(b.BenchmarkError): b.score(self.lock, prediction, 'all')
        self.predictions['sessions'][0]['sets'][0]['phases'][1]['seconds'][0] = 2
        with self.assertRaises(b.BenchmarkError): self.report()

    def test_score_does_not_mutate_immutable_inputs(self):
        before = copy.deepcopy((self.lock, self.predictions)); self.report()
        self.assertEqual((self.lock, self.predictions), before)

    def test_lock_cli_cannot_overwrite_and_score_requires_full_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); lock = root / 'lock.json'; result = root / 'report.json'
            tool = ROOT / 'scripts/workout_benchmark.py'
            command = [sys.executable, str(tool), 'freeze', str(ROOT / 'Evaluation/fixtures/workout-benchmark-synthetic.json'), '--output', str(lock)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 2)
            command = [sys.executable, str(tool), 'score', str(lock), str(ROOT / 'Evaluation/fixtures/workout-predictions-synthetic.json'), '--split', 'all', '--output', str(result)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 0)
            self.assertEqual(json.loads(result.read_text())['summary']['sessions'], 4)

    def test_cli_rejects_duplicate_json_keys_and_input_overwrite(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); annotations = root / 'annotations.json'; lock = root / 'lock.json'; predictions = root / 'predictions.json'
            annotations.write_text('{"schema_version":true,"schema_version":1}')
            tool = ROOT / 'scripts/workout_benchmark.py'
            command = [sys.executable, str(tool), 'freeze', str(annotations), '--output', str(lock)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 2)
            self.assertFalse(lock.exists())
            lock.write_text(json.dumps(self.lock)); predictions.write_text(json.dumps(self.predictions))
            before = lock.read_bytes()
            command = [sys.executable, str(tool), 'score', str(lock), str(predictions), '--split', 'all', '--output', str(lock)]
            self.assertEqual(subprocess.run(command, capture_output=True).returncode, 2)
            self.assertEqual(lock.read_bytes(), before)


if __name__ == '__main__':
    unittest.main()
