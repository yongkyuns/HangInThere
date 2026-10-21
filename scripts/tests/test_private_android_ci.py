import importlib.util
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('private_android_ci', Path(__file__).parents[1]/'private-android-ci.py')
CI = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CI)


class PrivateEvidenceTests(unittest.TestCase):
    def archive(self, path, extras=(), asset='recording_a.mp4'):
        manifest = {'schemaVersion': 1, 'cases': [{'id': 'recording_a_left', 'asset': asset}]}
        with tarfile.open(path, 'w:') as archive:
            for name, payload in [('manifest.json', json.dumps(manifest).encode()), (asset, b'neutral asset'), *extras]:
                member = tarfile.TarInfo(name)
                member.size = len(payload)
                archive.addfile(member, io.BytesIO(payload))

    def test_exact_manifest_asset_set_extracts_without_overwriting(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.archive(root/'input.tar')
            CI.unpack(root/'input.tar', root/'output')
            self.assertEqual((root/'output/recording_a.mp4').read_bytes(), b'neutral asset')
            with self.assertRaises(ValueError):
                CI.unpack(root/'input.tar', root/'output')

    def test_traversal_unlisted_assets_and_duplicate_entries_fail_before_extract(self):
        for name in ('../outside', '/absolute', 'raw-person-name.mov', 'recording_b.mp4', 'manifest.json'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as directory:
                root = Path(directory)
                self.archive(root/'input.tar', [(name, b'excluded')])
                with self.assertRaises(ValueError):
                    CI.unpack(root/'input.tar', root/'output')
                self.assertFalse((root/'output').exists())

    def test_symbolic_links_are_never_extracted(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.archive(root/'input.tar')
            with tarfile.open(root/'input.tar', 'a:') as archive:
                member = tarfile.TarInfo('recording_b.mp4')
                member.type = tarfile.SYMTYPE
                member.linkname = '/private/input-key'
                archive.addfile(member)
            with self.assertRaises(ValueError):
                CI.unpack(root/'input.tar', root/'output')

    def test_missing_report_is_explicit_failure(self):
        self.assertEqual(CI.summary('/nonexistent/private/report.json'), {'status': 'missing_native_report', 'cases': []})

    def test_public_summary_omits_arbitrary_nested_private_strings(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'report.json'
            report = {'manifestError': None, 'failures': [], 'deviceFingerprint': 'private-device',
                      'manifest': {'videoPath': '/private/name/movie.mov', 'cases': [
                          {'id': 'recording_a_left', 'expectedMovements': 2, 'frames': 690}]},
                      'cases': [{'id': 'recording_a_left', 'expectedMovements': 2, 'frames': 690,
                                 'sha256': 'private-source-hash', 'events': [{'private': 'trace'}],
                                 'automatic': {'observedMovements': 2, 'functionalParity': True,
                                               'error': None, 'privateDetails': '/private/name', 'sourcePts': [12345],
                                               'replayOutcome': 'complete', 'countingSourceEnded': True, 'countingSourceFrames': 690},
                                 'manualFixedEdgeCounter': {'observedMovements': 2, 'functionalParity': True, 'frames': 690}}]}
            path.write_text(json.dumps(report))
            result = CI.summary(path, 0, 0)
            self.assertEqual(result['status'], 'passed')
            self.assertEqual(result['cases'][0]['automaticMovements'], 2)
            self.assertNotIn('private', json.dumps(result))
            self.assertNotIn('sourcePts', json.dumps(result))
            report['cases'][0]['automatic']['functionalParity'] = False
            path.write_text(json.dumps(report))
            self.assertEqual(CI.summary(path, 0, 0)['status'], 'failed')

    def test_claimed_success_without_counts_coverage_or_exit_status_cannot_pass(self):
        base = {'manifestError': None, 'failures': [], 'manifest': {'cases': [
            {'id': 'recording_a_left', 'expectedMovements': 2, 'frames': 690}]},
            'cases': [{'id': 'recording_a_left', 'expectedMovements': 2, 'frames': 690,
                       'automatic': {'observedMovements': 2, 'functionalParity': True,
                                     'replayOutcome': 'complete', 'countingSourceEnded': True, 'countingSourceFrames': 690},
                       'manualFixedEdgeCounter': {'observedMovements': 2, 'functionalParity': True, 'frames': 690}}]}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'report.json'
            path.write_text(json.dumps(base))
            self.assertEqual(CI.summary(path, 0, 0)['status'], 'passed')
            for statuses in ((None, 0), (0, None), (1, 0), (0, 1)):
                self.assertEqual(CI.summary(path, *statuses)['status'], 'failed')
            for variant in ('wrong_count', 'missing_count', 'case_error', 'missing_case', 'duplicate_case', 'partial_frames'):
                report = json.loads(json.dumps(base))
                case = report['cases'][0]
                if variant == 'wrong_count':
                    case['automatic']['observedMovements'] = 0
                elif variant == 'missing_count':
                    del case['manualFixedEdgeCounter']['observedMovements']
                elif variant == 'case_error':
                    case['error'] = 'private exception'
                elif variant == 'missing_case':
                    report['manifest']['cases'].append({'id': 'recording_b_left', 'expectedMovements': 4, 'frames': 1124})
                elif variant == 'duplicate_case':
                    report['cases'].append(case)
                else:
                    case['automatic']['countingSourceFrames'] = 689
                path.write_text(json.dumps(report))
                with self.subTest(variant=variant):
                    self.assertEqual(CI.summary(path, 0, 0)['status'], 'failed')

    def test_unsafe_case_names_do_not_reach_public_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'report.json'
            path.write_text(json.dumps({'cases': [{'id': '/private/person-name'}]}))
            with self.assertRaises(ValueError):
                CI.summary(path)

    def test_empty_report_cannot_be_a_pass(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)/'report.json'
            path.write_text('{}')
            self.assertEqual(CI.summary(path)['status'], 'failed')


if __name__ == '__main__':
    unittest.main()
