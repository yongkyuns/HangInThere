import copy
import importlib.util
from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).resolve().parents[2] / 'scripts'
sys.path.insert(0, str(SCRIPTS))
spec = importlib.util.spec_from_file_location('temporal', SCRIPTS / 'score-temporal.py')
t = importlib.util.module_from_spec(spec); spec.loader.exec_module(t)


def fixture():
    clip = {'id': 'sequence', 'exercise': 'pull_up',
            'media': {'kind': 'video', 'expected_frames': 41, 'files': [{'path': 'v.mp4', 'sha256': 'a' * 64}]}}
    ref = {'schema_version': 1, 'id': 'sequence', 'exercise': 'pullUp', 'side': 'left',
           'event_definition': 'observed_start_to_top', 'media_sha256': 'a' * 64,
           'reviewed_without_counter_output': True, 'provenance': 'original synthetic reference',
           'form_verification': 'unverified', 'counter_policy_version': 2,
           'bar_reference_edge': [10.0, 20.0, 100.0, 20.0],
           'bar_reference_provenance': 'synthetic fixed edge selected without pose',
           'frame_pts_seconds': [i / 10 for i in range(41)], 'span_seconds': [0, 4.1],
           'events': [[1., 1.1], [3., 3.1]], 'tolerance_seconds': .2, 'ungradable_intervals': []}
    events = [{'outcome': 'movement', 'sourceSeconds': 1.1, 'reason': 'barReferencedTop;chinClearanceNotMeasured'},
              {'outcome': 'movement', 'sourceSeconds': 3.1, 'reason': 'barReferencedTop;chinClearanceNotMeasured'}]
    counter = {'frames': 41, 'events': events, 'referenceEdge': {'a': {'x': 10.0, 'y': 20.0}, 'b': {'x': 100.0, 'y': 20.0}}, 'summary': {'phase': 'finished', 'formVerification': 'unverified',
               'exercise': 'pullUp', 'side': 'left', 'policyVersion': 2,
               'observedMovements': 2, 'partialAttempts': 0, 'interruptedAttempts': 0}}
    obs = [{'frameIndex': i, 'timebase': 'source_pts', 'timestamp': {'value': i, 'timescale': 10}}
           for i in range(41)]
    status = {'status': 'processed', 'frames': 41}
    return ref, clip, status, counter, obs, copy.deepcopy(status)


class TemporalEventTests(unittest.TestCase):
    def setUp(self): self.ref = fixture()[0]
    def score(self, events): return t.score_events(self.ref, events)
    def test_exact_matches(self):
        m = self.score([1.05, 3.05]); self.assertEqual((m['true_positives'], m['false_positives'], m['false_negatives']), (2, 0, 0))
        self.assertEqual(m['precision'], 1); self.assertEqual(m['recall'], 1)
    def test_duplicate_is_false_positive(self):
        m = self.score([1.05, 1.05, 3.05]); self.assertEqual(m['false_positives'], 1)
        self.assertEqual(m['precision'], 2 / 3)
    def test_no_predictions_are_not_success(self):
        m = self.score([]); self.assertEqual(m['false_negatives'], 2); self.assertIsNone(m['precision']); self.assertEqual(m['recall'], 0)
    def test_matching_count_can_have_wrong_events(self):
        m = self.score([.2, 2.]); self.assertTrue(m['exact_count']); self.assertEqual(m['false_negatives'], 2); self.assertEqual(m['false_positives'], 2)
    def test_pause_false_positive_is_not_filtered(self):
        m = self.score([1.05, 2., 3.05]); self.assertEqual(m['false_positive_indices'], [1])
    def test_missed_reference_not_dropped(self): self.assertEqual(self.score([1.05])['missed_reference_indices'], [1])
    def test_uncertainty_window_distance(self):
        m = self.score([.9, 3.2]); errors = [v['signed_distance_to_window_seconds'] for v in m['matches']]
        self.assertAlmostEqual(errors[0], -.1); self.assertAlmostEqual(errors[1], .1)
    def test_tolerance_boundary(self): self.assertEqual(self.score([.8, 3.3])['true_positives'], 2)
    def test_empty_negative_reference(self):
        self.ref['events'] = []
        m = self.score([]); self.assertTrue(m['exact_count']); self.assertIsNone(m['f1']); self.assertIsNone(m['recall'])
        m = self.score([2.]); self.assertEqual(m['false_positives'], 1); self.assertEqual(m['precision'], 0)
    def test_ungradable_keeps_excluded_counts_and_time(self):
        self.ref['ungradable_intervals'] = [{'seconds': [0, .5], 'reason': 'initial effort cropped'}]
        m = self.score([.2, 1.05, 3.05]); self.assertEqual(m['ignored_prediction_indices'], [0])
        self.assertEqual(m['predicted_events_total'], 3); self.assertEqual(m['predicted_events_scored'], 2)
        self.assertAlmostEqual(m['scored_time_fraction'], 3.6 / 4.1)
    def test_ungradable_end_is_exclusive(self):
        self.ref['ungradable_intervals'] = [{'seconds': [0, .5], 'reason': 'censored'}]
        self.assertEqual(self.score([.5])['false_positives'], 1)
    def test_reordered_events_rejected(self):
        with self.assertRaises(ValueError): self.score([3.1, 1.1])
    def test_nonfinite_events_rejected(self):
        for v in (float('nan'), float('inf'), -1., 4.1):
            with self.subTest(v=v), self.assertRaises(ValueError): self.score([v])
    def test_overlapping_reference_windows_rejected(self):
        self.ref['events'] = [[1, 2], [1.5, 2.5]]
        with self.assertRaises(ValueError): self.score([1.6])
    def test_one_prediction_cannot_match_two_references(self):
        self.ref['events'] = [[1, 1.1], [1.3, 1.4]]
        m = self.score([1.2]); self.assertEqual(m['true_positives'], 1); self.assertEqual(m['false_negatives'], 1)
    def test_eof_missing_return_is_not_a_prediction(self):
        self.ref['exercise'] = 'dip'; self.ref['event_definition'] = 'observed_top_bottom_top'
        m = self.score([1.05]); self.assertEqual(m['false_negatives'], 1)


class TemporalIntegrityTests(unittest.TestCase):
    def test_complete_stream(self): t.validate_run(*fixture())
    def reject(self, change):
        args = fixture(); change(*args)
        with self.assertRaises((ValueError, KeyError, TypeError)): t.validate_run(*args)
    def test_truncated_counter(self): self.reject(lambda r,c,p,k,o,f: k.update(frames=40))
    def test_missing_pose_frame(self): self.reject(lambda r,c,p,k,o,f: o.pop())
    def test_failed_completion(self): self.reject(lambda r,c,p,k,o,f: f.update(status='engine_failure'))
    def test_wrong_media(self): self.reject(lambda r,c,p,k,o,f: r.update(media_sha256='b'*64))
    def test_wrong_side(self): self.reject(lambda r,c,p,k,o,f: k['summary'].update(side='right'))
    def test_wrong_policy(self): self.reject(lambda r,c,p,k,o,f: k['summary'].update(policyVersion=1))
    def test_wrong_bar_reference(self): self.reject(lambda r,c,p,k,o,f: k['referenceEdge']['a'].update(x=11.0))
    def test_missing_bar_provenance(self): self.reject(lambda r,c,p,k,o,f: r.update(bar_reference_provenance=''))
    def test_wrong_clock(self): self.reject(lambda r,c,p,k,o,f: o[3].update(timebase='frame_index'))
    def test_guessed_pts(self): self.reject(lambda r,c,p,k,o,f: o[3]['timestamp'].update(value=5))
    def test_summary_disagrees_with_events(self): self.reject(lambda r,c,p,k,o,f: k['summary'].update(observedMovements=3))
    def test_events_must_be_bound_to_frames(self): self.reject(lambda r,c,p,k,o,f: k['events'][0].update(sourceSeconds=1.155))
    def test_unfinished_counter(self): self.reject(lambda r,c,p,k,o,f: k['summary'].update(phase='returning'))
    def test_cannot_promote_form(self): self.reject(lambda r,c,p,k,o,f: r.update(form_verification='valid'))
    def test_model_generated_reference_rejected(self): self.reject(lambda r,c,p,k,o,f: r.update(reviewed_without_counter_output=False))
    def test_ungradable_cannot_erase_reference_event(self):
        self.reject(lambda r,c,p,k,o,f: r.update(ungradable_intervals=[{'seconds': [.8, 1.8], 'reason': 'bad model output'}]))
    def test_duplicate_reference_time_rejected(self): self.reject(lambda r,c,p,k,o,f: r['frame_pts_seconds'].__setitem__(2, .1))
    def test_invalid_event_outcome(self): self.reject(lambda r,c,p,k,o,f: k['events'][0].update(outcome='checked'))



# Preparation contracts use synthetic local files; no public media or inference.
class TemporalPreparationTests(unittest.TestCase):
    def setUp(self):
        import tempfile
        self.temp = tempfile.TemporaryDirectory(); self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        s = importlib.util.spec_from_file_location('temporal_prepare', SCRIPTS / 'prepare-temporal-pilot.py')
        self.prep = importlib.util.module_from_spec(s); s.loader.exec_module(self.prep)
        self.row = {'url': 'https://upload.wikimedia.org/example.mp4', 'bytes': 3,
                    'source_sha256': __import__('hashlib').sha256(b'abc').hexdigest()}
    def test_verified_cache_only(self):
        path = self.root / (self.row['source_sha256'] + '.mp4'); path.write_bytes(b'abc')
        self.assertEqual(self.prep.source_file(self.row, self.root, False), path)
    def test_missing_cache_needs_explicit_fetch(self):
        with self.assertRaises(ValueError): self.prep.source_file(self.row, self.root, False)
    def test_changed_cache_is_not_overwritten(self):
        path = self.root / (self.row['source_sha256'] + '.mp4'); path.write_bytes(b'bad')
        with self.assertRaises(ValueError): self.prep.source_file(self.row, self.root, True)
        self.assertEqual(path.read_bytes(), b'bad')
    def test_unreviewed_host_rejected(self):
        self.row['url'] = 'https://example.com/movie.mp4'
        with self.assertRaises(ValueError): self.prep.source_file(self.row, self.root, True)
    def test_credentials_not_allowed(self):
        self.row['url'] = 'https://user:secret@upload.wikimedia.org/movie.mp4'
        with self.assertRaises(ValueError): self.prep.source_file(self.row, self.root, True)
    def test_unbounded_source_rejected(self):
        self.row['bytes'] = 700_000_001
        with self.assertRaises(ValueError): self.prep.source_file(self.row, self.root, True)
    def test_nonmonotonic_probe_rejected(self):
        from unittest.mock import patch
        import json
        output = {'frames': [{'best_effort_timestamp_time': '0'}, {'best_effort_timestamp_time': '0'}],
                  'streams': [{'width': 640, 'height': 480, 'sample_aspect_ratio': '1:1'}]}
        with patch.object(self.prep, 'command', return_value=json.dumps(output)), self.assertRaises(ValueError): self.prep.probe('not-read')
    def test_probe_preserves_variable_pts(self):
        from unittest.mock import patch
        import json
        output = {'frames': [{'best_effort_timestamp_time': x} for x in ['0', '.033', '.077']],
                  'streams': [{'width': 640, 'height': 480, 'sample_aspect_ratio': '1:1'}]}
        with patch.object(self.prep, 'command', return_value=json.dumps(output)):
            self.assertEqual(self.prep.probe('not-read'), ([0, .033, .077], [640, 480]))
    def test_probe_accepts_omitted_square_pixel_metadata(self):
        from unittest.mock import patch
        import json
        output = {'frames': [{'best_effort_timestamp_time': x} for x in ['0', '.04']],
                  'streams': [{'width': 1080, 'height': 1920}]}
        with patch.object(self.prep, 'command', return_value=json.dumps(output)):
            self.assertEqual(self.prep.probe('not-read'), ([0, .04], [1080, 1920]))

    def test_probe_rejects_explicit_non_square_pixels(self):
        from unittest.mock import patch
        import json
        output = {'frames': [{'best_effort_timestamp_time': x} for x in ['0', '.04']],
                  'streams': [{'width': 720, 'height': 480, 'sample_aspect_ratio': '8:9'}]}
        with patch.object(self.prep, 'command', return_value=json.dumps(output)), self.assertRaises(ValueError):
            self.prep.probe('not-read')


if __name__ == '__main__': unittest.main()