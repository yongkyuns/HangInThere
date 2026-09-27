"""Report binding tests use synthetic metrics, not accuracy benchmarks."""
import copy
import importlib.util
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('pose_comparison', ROOT / 'scripts/compare-pose-reports.py')
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)


class ReportComparisonTests(unittest.TestCase):
    def setUp(self):
        measure = dict(reference_count=2, measured_count=2, coverage=1.0, mean=4.0, p95=5.0)
        missing = dict(reference_count=0, measured_count=0, coverage=None, mean=None, p95=None)
        self.a = dict(manifest_sha256='a'*64, source_commit='b'*40, confidence_threshold=.3,
                      source_files_sha256={'scripts/evaluation.py': 'c'*64},
                      counts_by_status={'processed': 1}, clips=[dict(
                          id='clip', dataset='synthetic', exercise='pull_up', split='smoke',
                          source_group='source', subject_group=None, status='processed', frames=2,
                          media_sha256=['d'*64, 'e'*64], annotation_sha256='f'*64,
                          backend=['Apple Vision 2D', 1], processing_ms=measure,
                          pose_metrics=dict(status='measured_2d_only', joint_pixels={'leftElbow': measure},
                                            elbow_degrees={'left': missing, 'right': missing},
                                            ambiguous_person_frames=0, missing_person_frames=0,
                                            person_policy='exactly_one_prediction_no_reference_based_selection'))])
        self.b = copy.deepcopy(self.a)
        self.b['clips'][0]['backend'] = ['MediaPipe Pose Landmarker Heavy', 'pose_landmarker_heavy/float16/1']

    def test_matching_reports_are_descriptive_not_a_speed_ranking(self):
        out = mod.compare(self.a, self.b)
        self.assertEqual(out['clips'][0]['vision']['joint_summary']['mean'], 4)
        self.assertNotIn('p95', out['clips'][0]['vision']['joint_summary'])
        self.assertFalse(out['timing_comparable'])

    def test_different_measured_coverage_is_not_hidden(self):
        self.b['clips'][0]['pose_metrics']['joint_pixels']['leftElbow'].update(measured_count=1, coverage=.5)
        out = mod.compare(self.a, self.b)
        self.assertEqual(out['clips'][0]['mediapipe']['joint_summary']['coverage'], .5)

    def test_empty_and_duplicate_reports_rejected(self):
        for rows in ([], self.a['clips'] * 2):
            with self.subTest(rows=len(rows)), self.assertRaises(ValueError):
                a = copy.deepcopy(self.a); a['clips'] = rows
                mod.compare(a, self.b)

    def test_changed_global_provenance_rejected(self):
        for field in ('manifest_sha256', 'source_commit', 'confidence_threshold', 'source_files_sha256'):
            with self.subTest(field=field), self.assertRaises((ValueError, TypeError)):
                b = copy.deepcopy(self.b)
                b[field] = {'scripts/evaluation.py': 'different'} if field == 'source_files_sha256' else 'different'
                mod.compare(self.a, b)

    def test_changed_clip_provenance_rejected(self):
        for key in ('frames', 'media_sha256', 'annotation_sha256', 'source_group', 'subject_group', 'split'):
            with self.subTest(key=key), self.assertRaises(ValueError):
                b = copy.deepcopy(self.b); b['clips'][0][key] = 'different'
                mod.compare(self.a, b)

    def test_failed_clip_not_disguised_as_scored(self):
        self.b['clips'][0]['status'] = 'engine_failure'
        self.b['counts_by_status'] = {'engine_failure': 1}
        with self.assertRaises(ValueError): mod.compare(self.a, self.b)

    def test_different_eligible_reference_counts_rejected(self):
        self.b['clips'][0]['pose_metrics']['joint_pixels']['leftElbow'].update(reference_count=4, coverage=.5)
        with self.assertRaises(ValueError): mod.compare(self.a, self.b)

    def test_invalid_measured_count_or_coverage_rejected(self):
        for changes in (dict(measured_count=3), dict(coverage=0.1), dict(mean=float('nan'))):
            with self.subTest(changes=changes), self.assertRaises(ValueError):
                b = copy.deepcopy(self.b); b['clips'][0]['pose_metrics']['joint_pixels']['leftElbow'].update(changes)
                mod.compare(self.a, b)

    def test_legacy_unbound_mediapipe_report_not_qualified(self):
        del self.b['clips'][0]['annotation_sha256']
        with self.assertRaises(ValueError): mod.compare(self.a, self.b)


if __name__ == '__main__':
    unittest.main()
