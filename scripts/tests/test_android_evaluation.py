import importlib.util
import copy
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


class AndroidMatchingInputsTests(unittest.TestCase):
    def reports(self):
        native = AndroidEvaluationEvidenceTests().report()
        native.update(sdk=29, modelSha256='a' * 64, poseArtifactSha256='b' * 64)
        for index, case in enumerate(native['cases']): case['sha256'] = str(index) * 64
        held = AndroidSetupEvidenceTests().report()
        held.update(apiLevel=29, modelSha256='a' * 64, backendAarSha256='b' * 64)
        for index, case in enumerate(held['cases']):
            case.update(sha256=str(index + 3) * 64, expectedFrames=72)
        other_native = copy.deepcopy(native); other_native['sdk'] = 35
        other_held = copy.deepcopy(held); other_held['apiLevel'] = 35
        return [(native, held), (other_native, other_held)]

    def check(self, reports):
        with tempfile.TemporaryDirectory() as directory:
            paths = []
            for index, (native, held) in enumerate(reports):
                folder = Path(directory) / str(index); folder.mkdir()
                (folder / 'report.json').write_text(json.dumps(native))
                (folder / 'setup-report.json').write_text(json.dumps(held))
                paths.append(folder / 'report.json')
            CHECK.matching_inputs(paths)

    def test_identical_media_passes_without_requiring_identical_predictions(self):
        reports = self.reports()
        reports[1][0]['cases'][2]['observedMovements'] = 0
        self.check(reports)

    def test_changed_dynamic_derivative_rejects_even_when_counts_match(self):
        reports = self.reports(); reports[1][0]['cases'][2]['sha256'] = 'f' * 64
        with self.assertRaisesRegex(AssertionError, 'video inputs differ'): self.check(reports)

    def test_changed_held_setup_derivative_rejects(self):
        reports = self.reports(); reports[1][1]['cases'][1]['sha256'] = 'f' * 64
        with self.assertRaisesRegex(AssertionError, 'video inputs differ'): self.check(reports)

    def test_empty_checksum_cannot_certify_identical_inputs(self):
        reports = self.reports()
        for native, _ in reports: native['cases'][2]['sha256'] = ''
        with self.assertRaisesRegex(AssertionError, 'Missing video checksum'): self.check(reports)

    def test_two_results_from_one_api_cannot_certify_the_matrix(self):
        reports = self.reports(); reports[1][0]['sdk'] = 29
        with self.assertRaisesRegex(AssertionError, 'distinct API'): self.check(reports)

    def test_setup_report_must_belong_to_its_native_api(self):
        reports = self.reports(); reports[0][1]['apiLevel'], reports[1][1]['apiLevel'] = 35, 29
        with self.assertRaisesRegex(AssertionError, 'Setup/native API mismatch'): self.check(reports)

    def test_matching_videos_cannot_hide_different_model_inputs(self):
        reports = self.reports(); reports[1][0]['modelSha256'] = 'c' * 64
        with self.assertRaisesRegex(AssertionError, 'Model/runtime inputs differ'): self.check(reports)

    def test_missing_or_duplicate_setup_input_rejects(self):
        for duplicate in (False, True):
            reports = self.reports()
            if duplicate: reports[1][1]['cases'][1] = reports[1][1]['cases'][0]
            else: reports[1][1]['cases'].pop()
            with self.assertRaisesRegex(AssertionError, 'Missing or duplicate setup'): self.check(reports)

if __name__=='__main__':unittest.main()
