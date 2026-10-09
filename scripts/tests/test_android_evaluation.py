import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('android_check', Path(__file__).parents[1] / 'check-android-evaluation.py')
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)

class AndroidEvaluationEvidenceTests(unittest.TestCase):
    def report(self):
        return {'policyVersion': 8, 'failures': [], 'accuracyFailures': [], 'cases': [
            {'id': name, 'qualificationRole': role, 'frames': frames, 'singlePersonFrames': frames,
             'functionalParity': True, 'fixedEdgeMovementParity': True, 'accuracyFailures': [],
             'observedMovements': count, 'expectedMovements': count,
             'poseLatencyMillis': {'samples': frames, 'p95': 100}, 'peakPssKb': 200000,
             'productionReplay': {'functionalParity': True, 'replayOutcome': 'complete',
                 'observedMovements': count, 'singlePersonFrames': frames+4, 'decodedFrames': frames+4,
                 'countingSourceEnded': True, 'countingSourceFrames': frames,
                 'peakPssKb': 200000, 'pipelineWithDisplayCopyLatencyMillis': {'samples': frames+4, 'p95': 150}}}
            for name, role, frames, count in [('pullup_margin', 'positive', 108, 1),
                ('pullup_smoke', 'diagnostic', 40, 1), ('dip_controlled_pexels', 'diagnostic', 300, 9)]]}

    def check(self, report):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'report.json'; path.write_text(json.dumps(report))
            CHECK.native(path)

    def test_complete_native_evidence_passes(self):
        self.check(self.report())

    def test_empty_fixture_suite_cannot_pass(self):
        r=self.report();r['cases']=[]
        with self.assertRaises(AssertionError):self.check(r)

    def test_fixed_edge_success_cannot_hide_auto_failure(self):
        r=self.report();r['cases'][0]['productionReplay']['functionalParity']=False
        with self.assertRaises(AssertionError):self.check(r)

    def test_model_only_budget_cannot_hide_slow_production_pipeline(self):
        r=self.report();r['cases'][0]['productionReplay']['pipelineWithDisplayCopyLatencyMillis']['p95']=2600
        with self.assertRaises(AssertionError):self.check(r)

    def test_unbounded_production_memory_fails(self):
        r=self.report();r['cases'][0]['productionReplay']['peakPssKb']=1_048_576
        with self.assertRaises(AssertionError):self.check(r)

    def test_missing_frame_samples_fail(self):
        r=self.report();r['cases'][0]['poseLatencyMillis']['samples']=39
        with self.assertRaises(AssertionError):self.check(r)

    def test_native_failure_is_not_treated_as_missing_success(self):
        r=self.report();r['failures']=['pose error']
        with self.assertRaises(AssertionError):self.check(r)

    def failing_diagnostic(self):
        report = self.report(); case = report['cases'][1]
        case.update(observedMovements=0, functionalParity=False, fixedEdgeMovementParity=False,
                    accuracyFailures=['original fixed-edge count failure', 'original Auto failure'])
        case['productionReplay'].update(observedMovements=0, functionalParity=False, replayOutcome='setupUnavailable')
        report['accuracyFailures'] = case['accuracyFailures'][:]
        return report

    def test_diagnostic_failures_remain_visible_without_hiding_native_success(self):
        report = self.failing_diagnostic(); self.check(report)
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'report.json'; path.write_text(json.dumps(report))
            with self.assertRaises(AssertionError): CHECK.accuracy(path)

    def test_diagnostic_role_cannot_hide_native_exception(self):
        report = self.failing_diagnostic()
        report['cases'][1]['productionReplay']['error'] = 'decoder failure'
        with self.assertRaises(AssertionError): self.check(report)

    def test_diagnostic_role_cannot_hide_slow_pipeline(self):
        report = self.failing_diagnostic()
        report['cases'][1]['productionReplay']['pipelineWithDisplayCopyLatencyMillis']['p95'] = 2600
        with self.assertRaises(AssertionError): self.check(report)

    def test_original_labels_cannot_be_replaced_with_zero(self):
        report = self.failing_diagnostic(); report['cases'][1]['expectedMovements'] = 0
        with self.assertRaises(AssertionError): self.check(report)

    def test_positive_cannot_be_reclassified_after_failure(self):
        report = self.report(); report['cases'][0]['qualificationRole'] = 'diagnostic'
        with self.assertRaises(AssertionError): self.check(report)

    def test_successful_count_before_interruption_is_not_complete(self):
        report = self.report(); report['cases'][0]['productionReplay']['replayOutcome'] = 'interrupted'
        with self.assertRaises(AssertionError): self.check(report)

    def test_missing_explicit_accuracy_evidence_fails(self):
        report = self.failing_diagnostic(); report['accuracyFailures'] = []
        with self.assertRaises(AssertionError): self.check(report)


class AndroidSetupEvidenceTests(unittest.TestCase):
    def report(self):
        return {'schemaVersion': 1, 'failures': [], 'cases': [
            {'id': id, 'pass': True, 'decodedFrames': 72, 'selectedExercise': exercise,
             'acquiredActualContours': True, 'finalPhase': 'running', 'observedMovements': 0,
             'countdownValues': [3, 2, 1], 'firstCountdownSeconds': 1.1, 'firstRunningSeconds': 4.1}
            for id, exercise in [('pullup_held_start', 'pullUp'), ('dip_held_start', 'dip')]]}

    def check(self, report):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'setup-report.json';path.write_text(json.dumps(report))
            CHECK.setup(path)

    def test_complete_real_pixel_setup_evidence_passes(self):
        self.check(self.report())

    def test_missing_setup_case_fails(self):
        r=self.report();r['cases'].pop()
        with self.assertRaises(AssertionError):self.check(r)

    def test_pose_only_setup_without_contours_fails(self):
        r=self.report();r['cases'][0]['acquiredActualContours']=False
        with self.assertRaises(AssertionError):self.check(r)

    def test_countdown_cannot_skip_source_dwell(self):
        r=self.report();r['cases'][0]['firstRunningSeconds']=1.5
        with self.assertRaises(AssertionError):self.check(r)

    def test_static_setup_cannot_earn_a_rep(self):
        r=self.report();r['cases'][0]['observedMovements']=1
        with self.assertRaises(AssertionError):self.check(r)

if __name__=='__main__':unittest.main()
