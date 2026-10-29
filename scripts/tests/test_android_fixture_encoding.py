"""Real encoding regression: host CPU selection must not change test inputs."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPTS = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location('temporal_encoding', SCRIPTS / 'prepare-temporal-pilot.py')
PREPARE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(PREPARE)


def synthetic_source(root):
    source = root / 'source.mp4'
    subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i',
        'testsrc2=size=320x180:rate=25:duration=0.72', '-vf', r'setpts=PTS+if(gte(N\,5)\,2\,0)',
        '-fps_mode', 'passthrough', '-enc_time_base', '1:25', '-c:v', 'libx264', str(source)],
        check=True, timeout=30)
    return source


@unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'), 'Requires real test-only FFmpeg tools')
class AndroidFixtureEncodingTests(unittest.TestCase):
    def test_native_and_scalar_cpu_encode_identical_vfr_media(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            source = synthetic_source(root)
            source_hash = hashlib.sha256(source.read_bytes()).hexdigest()
            pts, size = PREPARE.probe(source)
            self.assertEqual(len(pts), 18)
            self.assertAlmostEqual(pts[5] - pts[4], 0.12)
            cache = root / 'cache'; cache.mkdir()
            source.rename(cache / (source_hash + '.mp4'))
            recipe = {'schema_version': 1, 'counter_policy_version': 8, 'tolerance_seconds': 0.35,
                'scope': 'Authored moving test pattern; no human or accuracy claim',
                'annotation_provenance': 'Original synthetic encoding test', 'sources': [{
                    'id': 'encoding_test', 'url': 'https://upload.wikimedia.org/encoding-test.mp4',
                    'source_sha256': source_hash, 'bytes': (cache / (source_hash + '.mp4')).stat().st_size,
                    'source_frames': len(pts), 'source_size': size, 'frame_range': [2, 16],
                    'first_source_pts': pts[2], 'last_source_pts': pts[15], 'end_source_seconds': pts[16],
                    'encoder_time_base': '1:25', 'exercise': 'pullUp', 'side': 'left',
                    'source_group': 'authored-encoding-test', 'title': 'Authored moving pattern',
                    'credit': 'HangInThere test', 'page': 'https://example.com/encoding-test',
                    'license': 'CC0', 'license_url': 'https://creativecommons.org/publicdomain/zero/1.0/',
                    'events_source_seconds': [], 'ungradable_source_intervals': [], 'review_notes': 'No human labels'}]}
            frozen_recipe = copy.deepcopy(recipe)
            recipe_file = root / 'recipe.json'; recipe_file.write_text(json.dumps(recipe))
            command = PREPARE.command
            def scalar_command(args):
                if args[0] == 'ffmpeg' and '-i' in args:
                    args = [args[0], '-cpuflags', '0', '-cpucount', '1', *args[1:]]
                return command(args)
            PREPARE.prepare(cache, root / 'native', spec_path=recipe_file, reproducible=True)
            with patch.object(PREPARE, 'command', side_effect=scalar_command):
                PREPARE.prepare(cache, root / 'scalar', spec_path=recipe_file, reproducible=True)
            native = root / 'native/encoding_test.mp4'; scalar = root / 'scalar/encoding_test.mp4'
            self.assertEqual(hashlib.sha256(native.read_bytes()).hexdigest(), hashlib.sha256(scalar.read_bytes()).hexdigest())
            expected = [value - pts[2] for value in pts[2:16]]
            for output in ('native', 'scalar'):
                reference = json.loads((root / output / 'reference.json').read_text())['clips'][0]
                self.assertEqual(reference['source_pts_seconds'], pts[2:16])
                for observed, original in zip(reference['frame_pts_seconds'], expected):
                    self.assertAlmostEqual(observed, original, places=6)
                self.assertEqual(len(reference['frame_pts_seconds']), 14)
                self.assertEqual(reference['events'], [])
                self.assertEqual(reference['source_sha256'], source_hash)
            self.assertEqual(json.loads(recipe_file.read_text()), frozen_recipe)
            self.assertEqual(hashlib.sha256((cache / (source_hash + '.mp4')).read_bytes()).hexdigest(), source_hash)
            android_spec = importlib.util.spec_from_file_location('android_selected_assets', SCRIPTS / 'prepare-android-fixtures.py')
            android = importlib.util.module_from_spec(android_spec); android_spec.loader.exec_module(android)
            assets = root / 'selected-assets'
            export = android.selected_pose_assets(root / 'native', assets)
            self.assertEqual(len(export['clips']), 1)
            self.assertEqual(export['clips'][0]['expectedFrames'], 14)
            self.assertEqual((assets / 'encoding_test.mp4').read_bytes(), native.read_bytes())
            self.assertEqual(export['clips'][0]['sourcePtsMicroseconds'], android.timestamp_evidence(native)['sourcePtsMicroseconds'])
            self.assertNotIn('events', export['clips'][0])
            self.assertNotIn('referenceEdge', export['clips'][0])
            self.assertEqual((assets / 'ATTRIBUTION.txt').read_text(), (root / 'native/ATTRIBUTION.txt').read_text())
            # A shortened reference cannot silently drop its source. Existing
            # owned outputs survive failed input validation.
            reference_file = root / 'native/reference.json'
            reference = json.loads(reference_file.read_text()); reference['clips'] = []
            reference_file.write_text(json.dumps(reference))
            with self.assertRaises(AssertionError): android.selected_pose_assets(root / 'native', assets)
            self.assertEqual((assets / 'encoding_test.mp4').read_bytes(), native.read_bytes())

    def test_android_smoke_preparation_preserves_existing_ios_fixture_bytes(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); source = synthetic_source(root)
            android_spec = importlib.util.spec_from_file_location('android_isolation', SCRIPTS / 'prepare-android-fixtures.py')
            android = importlib.util.module_from_spec(android_spec); android_spec.loader.exec_module(android)
            smoke = android.module('prepare-fixtures')
            ios = root / 'ios/HangInThereTests/Fixtures/generated'; ios.mkdir(parents=True)
            before = {'pullup-smoke.mp4': b'existing Apple media', 'pullup-smoke.png': b'existing Apple still',
                      'prepared.json': b'existing Apple provenance'}
            for name, contents in before.items(): (ios / name).write_bytes(contents)
            spec = {'source_id': 'authored-encoding-test', 'source_bytes': source.stat().st_size,
                'source_sha1': hashlib.sha1(source.read_bytes()).hexdigest(),
                'recipe': {'source_start_seconds': 0, 'duration_seconds': .6, 'frames_per_second': 10,
                           'width': 160, 'still_seconds': .2}}
            with patch.object(android, 'ROOT', root), patch.object(smoke, 'DESTINATION', ios):
                destination = android.prepare_smoke(smoke, source, spec)
            self.assertEqual(destination, root / 'build/android-fixtures/smoke')
            self.assertTrue((destination / 'pullup-smoke.mp4').is_file())
            metadata = json.loads((destination / 'prepared.json').read_text())
            self.assertEqual(len(metadata['frame_pts_seconds']), 6)
            self.assertEqual(metadata['source_sha1'], spec['source_sha1'])
            self.assertEqual({file.name: file.read_bytes() for file in ios.iterdir()}, before)


if __name__ == '__main__':
    unittest.main()
