import importlib.util
import copy
import contextlib
import io
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

    def grip_report(self):
        report=self.report()
        for case in report['cases']:
            case['productionReplay']['acquisitionEvidence']={
                'frames':case['frames'], 'bothArmFrames':10, 'pullUpGripAssessmentVersion':1,
                'pullUpGripFrames':{'eligible':10, 'gripLandmarksUnavailable':case['frames']-10}}
        return report

    def test_complete_grip_evidence_and_unavailable_legacy_reports(self):
        self.check(self.grip_report())
        self.check(self.report())

    def test_grip_evidence_cannot_hide_frames_counts_or_unknown_statuses(self):
        mutations=[
            lambda e:e.pop('pullUpGripFrames'),
            lambda e:e.pop('pullUpGripAssessmentVersion'),
            lambda e:e.update(pullUpGripAssessmentVersion=2),
            lambda e:e.update(pullUpGripAssessmentVersion=True),
            lambda e:e['pullUpGripFrames'].update(eligible=9),
            lambda e:e['pullUpGripFrames'].update(eligible=-1),
            lambda e:e['pullUpGripFrames'].update(eligible=True),
            lambda e:e['pullUpGripFrames'].update(eligible=10.0),
            lambda e:e['pullUpGripFrames'].update(unrecognized=0),
            lambda e:e.update(pullUpGripFrames=[]),
            lambda e:e.update(frames=1000),
            lambda e:e.update(frames=True),
            lambda e:e.update(bothArmFrames=True),
            lambda e:e.update(bothArmFrames=9)]
        for mutation in mutations:
            with self.subTest(mutation=mutations.index(mutation)):
                report=self.grip_report();mutation(report['cases'][0]['productionReplay']['acquisitionEvidence'])
                with self.assertRaises(AssertionError):self.check(report)

    def test_valid_grip_diagnostics_do_not_clear_failed_accuracy(self):
        report=self.failing_diagnostic()
        report['cases'][1]['productionReplay']['acquisitionEvidence']=self.grip_report()['cases'][1]['productionReplay']['acquisitionEvidence']
        self.check(report)
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'report.json';path.write_text(json.dumps(report))
            with self.assertRaises(AssertionError):CHECK.accuracy(path)


class AndroidPoseModeEvidenceTests(unittest.TestCase):
    def evidence(self):
        baseline = AndroidEvaluationEvidenceTests().report()
        baseline.update(sdk=35, modelSha256='a' * 64, poseArtifactSha256='b' * 64)
        report = {key: baseline[key] for key in ('sdk', 'modelSha256', 'poseArtifactSha256', 'policyVersion')}
        report.update(schemaVersion=1, failures=[], cases=[])
        files = {}
        for index, native in enumerate(baseline['cases']):
            native.update(sha256=str(index) * 64, exercise='dip' if index == 2 else 'pullUp', side='left',
                          referenceEdge={'a': {'x': 2, 'y': 3}, 'b': {'x': 200, 'y': 3}})
            frames = native['frames']; identifier = native['id']
            pts = [i * 100_000 for i in range(frames)]
            # The experiment may still fail the original label; evidence collection is not an accuracy verdict.
            count = 1 if index == 0 else 0
            summaries = {name: dict(observedMovements=count, singlePersonFrames=frames,
                usableArmFrames=dict(left=frames, right=0),
                events=[dict(outcome='movement')] * count,
                inferenceLatencyMillis=dict(samples=frames, p95=100)) for name in ('video', 'image')}
            report['cases'].append(dict(id=identifier, sha256=native['sha256'], exercise=native['exercise'], side='left',
                frames=frames, expectedFrames=frames, expectedMovements=native['expectedMovements'],
                sourcePtsMicroseconds=pts, modes=summaries))
            source = [dict(timestamp=dict(value=value, timescale=1_000_000), imageSize=dict(width=640, height=480)) for value in pts]
            trace = []
            for i, pose in enumerate(source):
                modes = {}
                for name in ('video', 'image'):
                    backend = 'MediaPipe Heavy CPU IMAGE visibility/presence minimum' if name == 'image' else 'MediaPipe Heavy CPU visibility/presence minimum'
                    modes[name] = dict(pose=dict(**pose, people=[{}], backend=backend, requestRevision=1),
                        arms=[dict(side='left', estimate={}), dict(side='right', unavailableReason='lowConfidence')],
                        counter=dict(policyVersion=8, observedMovements=count if i == frames - 1 else 0), inferenceMillis=100)
                trace.append(dict(frameIndex=i, sourcePtsMicroseconds=pts[i], pixelSha256='c' * 64,
                    referenceEdge=native['referenceEdge'], modes=modes,
                    inferenceOrder=['video', 'image'] if i % 2 == 0 else ['image', 'video']))
            files[identifier + '-observations.jsonl'] = source
            files[identifier + '-pose-modes.jsonl'] = trace
        return baseline, report, files

    def check(self, change=lambda baseline, report, files: None):
        baseline, report, files = self.evidence()
        change(baseline, report, files)
        with tempfile.TemporaryDirectory() as directory, contextlib.redirect_stdout(io.StringIO()):
            folder = Path(directory)
            (folder / 'report.json').write_text(json.dumps(baseline))
            path = folder / 'pose-mode-report.json'; path.write_text(json.dumps(report))
            for name, rows in files.items():
                (folder / name).write_text(''.join(json.dumps(row) + '\n' for row in rows))
            CHECK.pose_modes(path)

    def test_complete_comparison_with_failed_original_counts_is_diagnostic_only(self):
        self.check()

    def test_original_label_cannot_be_relaxed(self):
        with self.assertRaisesRegex(AssertionError, 'source/label changed'):
            self.check(lambda baseline, report, files: report['cases'][2].update(expectedMovements=0))

    def test_incomplete_source_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'Incomplete'):
            self.check(lambda baseline, report, files: files['pullup_smoke-pose-modes.jsonl'].pop())

    def test_mode_must_use_original_pts_and_geometry(self):
        with self.assertRaisesRegex(AssertionError, 'geometry/PTS changed'):
            self.check(lambda baseline, report, files: files['pullup_smoke-pose-modes.jsonl'][0]['modes']['image']['pose'].update(timestamp=dict(value=1, timescale=1_000_000)))

    def test_missing_shared_pixel_evidence_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'pixel checksum'):
            self.check(lambda baseline, report, files: files['pullup_smoke-pose-modes.jsonl'][0].update(pixelSha256=''))

    def test_changed_video_or_model_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'video bytes differ'):
            self.check(lambda baseline, report, files: report['cases'][0].update(sha256='f' * 64))
        with self.assertRaisesRegex(AssertionError, 'inputs differ'):
            self.check(lambda baseline, report, files: report.update(modelSha256='f' * 64))

    def test_original_reference_edge_is_retained(self):
        with self.assertRaisesRegex(AssertionError, 'reference edge changed'):
            self.check(lambda baseline, report, files: files['pullup_smoke-pose-modes.jsonl'][0].update(referenceEdge={}))

    def test_native_failure_or_missing_mode_is_rejected(self):
        with self.assertRaisesRegex(AssertionError, 'native execution failed'):
            self.check(lambda baseline, report, files: report.update(failures=['model failed']))
        with self.assertRaisesRegex(AssertionError, 'Missing comparison mode'):
            self.check(lambda baseline, report, files: report['cases'][0]['modes'].pop('image'))

    def test_summary_cannot_hide_count_or_confidence_coverage(self):
        with self.assertRaisesRegex(AssertionError, 'count summary differs'):
            self.check(lambda baseline, report, files: report['cases'][2]['modes']['image'].update(observedMovements=9))
        with self.assertRaisesRegex(AssertionError, 'coverage summary differs'):
            self.check(lambda baseline, report, files: report['cases'][2]['modes']['image'].update(usableArmFrames=dict(left=300, right=300)))

    def test_missing_timing_or_count_event_is_rejected(self):
        with self.assertRaises(AssertionError):
            self.check(lambda baseline, report, files: report['cases'][0]['modes']['image']['inferenceLatencyMillis'].update(samples=1))
        with self.assertRaisesRegex(AssertionError, 'events incomplete'):
            self.check(lambda baseline, report, files: report['cases'][0]['modes']['image'].update(events=[]))


class AndroidCropEvidenceTests(unittest.TestCase):
    def evidence(self, skipped=False):
        baseline, report, files = AndroidPoseModeEvidenceTests().evidence()
        report.update(schemaVersion=2,regionRecipeVersion=1,paddingFraction=.15,minimumPaddingPixels=16)
        joints = ['leftShoulder','rightShoulder','leftHip','rightHip','nose','leftElbow','rightElbow','leftAnkle']
        positions = [(100,100),(300,100),(120,300),(280,300),(100,50),(80,150),(320,150),(200,400)]
        witness = [dict(joint=j,position=dict(x=x,y=y),confidence=.8) for j,(x,y) in zip(joints,positions)]
        for case in report['cases']:
            identifier = case['id']; frames = case['frames']
            summary=copy.deepcopy(case['modes']['image'])
            summary.update(skippedFrames=frames if skipped else 0)
            if skipped:
                summary.update(observedMovements=0,events=[],singlePersonFrames=0,usableArmFrames=dict(left=0,right=0),inferenceLatencyMillis=dict(samples=0))
            case['modes']['crop']=summary
            trace=files.pop(identifier+'-pose-modes.jsonl')
            for row in trace:
                row['inferenceOrder'].append('crop')
                row['modes']['video']['pose']['people'] = [dict(landmarks=[] if skipped else witness)]
                observation=copy.deepcopy(row['modes']['image'])
                observation.update(region=None,rawPose=None,cropPixelSha256=None,inferencePerformed=not skipped)
                if skipped:
                    observation.update(inferenceMillis=None,arms=[dict(side=s,unavailableReason='noPerson') for s in ['left','right']])
                    observation['pose'].update(people=[],backend='Diagnostic crop unavailable')
                    observation['counter']['observedMovements']=0
                else:
                    region=CHECK.crop_region(row['modes']['video']['pose'])
                    raw_point=dict(joint='leftWrist',position=dict(x=20.25,y=30.5),confidence=.4)
                    raw=dict(timestamp=row['modes']['video']['pose']['timestamp'],imageSize=dict(width=region['width'],height=region['height']),
                        backend='MediaPipe Heavy CPU IMAGE visibility/presence minimum',requestRevision=1,people=[dict(landmarks=[raw_point])])
                    mapped=dict(raw_point,position=dict(x=20.25+region['left'],y=30.5+region['top']))
                    observation.update(region=region,rawPose=raw,cropPixelSha256='d'*64)
                    observation['pose'].update(backend='MediaPipe Heavy CPU IMAGE person crop visibility/presence minimum',people=[dict(landmarks=[mapped])])
                row['modes']['crop']=observation
            files[identifier+'-pose-crops.jsonl']=trace
        return baseline,report,files

    def check(self, change=lambda b,r,f: None, skipped=False):
        baseline,report,files=self.evidence(skipped)
        change(baseline,report,files)
        with tempfile.TemporaryDirectory() as directory,contextlib.redirect_stdout(io.StringIO()):
            folder=Path(directory);(folder/'report.json').write_text(json.dumps(baseline))
            path=folder/'pose-crop-report.json';path.write_text(json.dumps(report))
            for name,rows in files.items(): (folder/name).write_text(''.join(json.dumps(row)+'\n' for row in rows))
            CHECK.pose_modes(path)

    def test_complete_cropped_and_explicitly_skipped_evidence_pass(self):
        self.check();self.check(skipped=True)

    def test_fixed_recipe_and_same_frame_witness_are_required(self):
        with self.assertRaisesRegex(AssertionError,'Frozen crop recipe'):
            self.check(lambda b,r,f:r.update(paddingFraction=.2))
        with self.assertRaisesRegex(AssertionError,'current VIDEO witness'):
            self.check(lambda b,r,f:f['pullup_smoke-pose-crops.jsonl'][0]['modes']['crop']['region'].update(left=0))

    def test_original_source_labels_and_pixels_remain_required(self):
        with self.assertRaisesRegex(AssertionError,'source/label'):
            self.check(lambda b,r,f:r['cases'][2].update(expectedMovements=0))
        with self.assertRaisesRegex(AssertionError,'pixel checksum'):
            self.check(lambda b,r,f:f['pullup_smoke-pose-crops.jsonl'][0]['modes']['crop'].update(cropPixelSha256=''))

    def test_coordinate_scores_and_people_cannot_change_in_restoration(self):
        for field,value in [('confidence',.9),('position',dict(x=0,y=0))]:
            with self.assertRaisesRegex(AssertionError,'restoration changed'):
                self.check(lambda b,r,f:f['pullup_smoke-pose-crops.jsonl'][0]['modes']['crop']['pose']['people'][0]['landmarks'][0].update({field:value}))
        with self.assertRaisesRegex(AssertionError,'restoration changed'):
            self.check(lambda b,r,f:f['pullup_smoke-pose-crops.jsonl'][0]['modes']['crop']['pose'].update(people=[]))

    def test_outside_crop_prediction_cannot_be_translated_into_source(self):
        with self.assertRaisesRegex(AssertionError,'restoration changed'):
            self.check(lambda b,r,f:f['pullup_smoke-pose-crops.jsonl'][0]['modes']['crop']['rawPose']['people'][0]['landmarks'][0]['position'].update(x=-1))

    def test_skipped_crop_cannot_claim_native_inference(self):
        with self.assertRaisesRegex(AssertionError,'claims inference'):
            self.check(lambda b,r,f:f['pullup_smoke-pose-crops.jsonl'][0]['modes']['crop'].update(inferenceMillis=100),skipped=True)
        with self.assertRaisesRegex(AssertionError,'skip summary'):
            self.check(lambda b,r,f:r['cases'][0]['modes']['crop'].update(skippedFrames=0),skipped=True)
        with self.assertRaisesRegex(AssertionError,'unperformed calls'):
            self.check(lambda b,r,f:r['cases'][0]['modes']['crop']['inferenceLatencyMillis'].update(samples=108),skipped=True)


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
