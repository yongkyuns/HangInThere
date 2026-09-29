"""Synthetic preparation contracts; actual source inference runs separately in CI."""
import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'scripts'))
import evaluation as ev
spec = importlib.util.spec_from_file_location('source_pilot', ROOT / 'scripts/prepare-source-pilot.py')
mod = importlib.util.module_from_spec(spec); spec.loader.exec_module(mod)


class SourcePilotTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name); self.cache = self.root / 'cache'; self.cache.mkdir()
        self.output = self.root / 'output'; self.recipe = self.root / 'recipe.json'
        self.path = self.cache / 'fixture.jpg'
        Image.new('RGB', (100, 200), (20, 80, 100)).save(self.path)
        self.row = {'id': 'fixture', 'title': 'Original synthetic fixture', 'kind': 'image',
                    'url': 'https://upload.wikimedia.org/fixture.jpg', 'bytes': self.path.stat().st_size,
                    'sha256': ev.digest(self.path), 'source_size': [100, 200], 'image_size': [100, 200],
                    'expected_exif': 1, 'orientation': 'exif', 'source_group': 'synthetic',
                    'exercise': 'bench_dip', 'credit': 'Test authors', 'page': 'test-source',
                    'license': 'original-test', 'license_url': 'test-license',
                    'labels': [{'frame_index': 0, 'points': {'leftShoulder': [30, 40]}}]}
        self.data = {'schema_version': 1, 'scope': 'Synthetic contract, not inference',
                     'annotation_provenance': 'Synthetic coordinates', 'changes': 'Test derivative',
                     'sources': [self.row]}
        self.command = patch.object(mod, 'command', return_value='synthetic ffmpeg version\n')
        self.command.start(); self.addCleanup(self.command.stop)

    def prepare(self):
        ev.write_json(self.recipe, self.data)
        mod.prepare(self.cache, self.output, spec_path=self.recipe)
        return ev.read_json(self.output / 'manifest.json')

    def test_bound_references_and_attribution_preserve_category_and_unknown_subject(self):
        manifest = self.prepare(); row = manifest['clips'][0]
        self.assertEqual((row['exercise'], row['split'], row['subject_group']), ('bench_dip', 'unassigned', None))
        self.assertEqual(row['media']['expected_frames'], 1)
        ref = ev.read_json(ev.asset_path(self.output, row['annotations']))
        self.assertEqual(ref['media_sha256'], [row['media']['files'][0]['sha256']])
        self.assertEqual(ref['source_sha256'], self.row['sha256'])
        self.assertNotIn('timestamp_seconds', ref['frames'][0])
        self.assertEqual(ref['frames'][0]['points'], self.row['labels'][0]['points'])
        self.assertIn('Test authors', (self.output / 'ATTRIBUTION.txt').read_text())
        self.assertEqual(ev.preflight(row, self.output, True), 'ready')

    def test_wrong_cache_is_not_overwritten(self):
        self.path.write_bytes(b'corrupted')
        with self.assertRaisesRegex(ValueError, 'Wrong original'): self.prepare()
        self.assertEqual(self.path.read_bytes(), b'corrupted')
        self.assertFalse((self.output / 'manifest.json').exists())

    def test_missing_file_does_not_fetch_implicitly(self):
        self.path.unlink()
        with patch.object(mod.urllib.request, 'urlopen') as remote:
            with self.assertRaisesRegex(ValueError, 'Missing source'): self.prepare()
            remote.assert_not_called()

    def test_fetch_rejects_unapproved_host_before_network(self):
        self.path.unlink(); self.row['url'] = 'https://example.com/fixture.jpg'
        with patch.object(mod.urllib.request, 'urlopen') as remote:
            with self.assertRaisesRegex(ValueError, 'Unapproved source'): mod.original(self.row, self.cache, True)
            remote.assert_not_called()

    def test_incomplete_second_source_does_not_publish_smaller_manifest(self):
        self.data['sources'].append({**copy.deepcopy(self.row), 'id': 'missing'})
        with self.assertRaisesRegex(ValueError, 'Missing source'): self.prepare()
        self.assertFalse((self.output / 'manifest.json').exists())

    def test_duplicate_or_path_ids_rejected(self):
        self.data['sources'].append(copy.deepcopy(self.row))
        with self.assertRaisesRegex(ValueError, 'Invalid/duplicate'): self.prepare()
        self.data['sources'].pop(); self.row['id'] = '../escape'
        with self.assertRaisesRegex(ValueError, 'Invalid/duplicate'): self.prepare()

    def test_dimensions_and_labels_must_match_review(self):
        self.row['image_size'] = [100, 100]
        with self.assertRaisesRegex(ValueError, 'resize geometry'): self.prepare()

    def test_outside_reference_rejected(self):
        self.row['labels'][0]['points']['leftShoulder'] = [300, 40]
        with self.assertRaises(ValueError): self.prepare()
        self.assertFalse((self.output / 'manifest.json').exists())

    def test_explicit_raw_exif_override_is_not_applied_twice(self):
        image = Image.new('RGB', (100, 200), (100, 50, 30)); exif = Image.Exif(); exif[274] = 8
        image.save(self.path, exif=exif)
        self.row.update(bytes=self.path.stat().st_size, sha256=ev.digest(self.path), expected_exif=8,
                        orientation='raw-reviewed')
        row = self.prepare()['clips'][0]
        with Image.open(ev.asset_path(self.output, row['media']['files'][0])) as result:
            self.assertEqual(result.size, (100, 200)); self.assertEqual(result.getexif().get(274, 1), 1)

    def test_changed_exif_fails(self):
        self.row['expected_exif'] = 8
        with self.assertRaisesRegex(ValueError, 'EXIF orientation changed'): self.prepare()

    def test_video_selection_preserves_real_source_times_without_inventing_fps(self):
        self.row.update(kind='video', source_frame_count=3, source_frame_indices=[0, 2],
                        source_pts_seconds=[0.1, 0.27])
        def execute(args):
            if args[0] == 'ffprobe':
                return json.dumps({'frames': [{'best_effort_timestamp_time': t} for t in [.1, .18, .27]]})
            if '-vf' in args:
                directory = Path(args[-1]).parent
                for i in range(2): Image.new('RGB', (100, 200)).save(directory / f'{i:04d}.png')
            return 'synthetic ffmpeg version\n'
        with patch.object(mod, 'command', side_effect=execute): manifest = self.prepare()
        ref = ev.read_json(ev.asset_path(self.output, manifest['clips'][0]['annotations']))
        self.assertEqual(ref['source_frame_indices'], [0, 2]); self.assertEqual(ref['source_pts_seconds'], [.1, .27])
        self.assertEqual(manifest['clips'][0]['media']['kind'], 'images')
        self.assertNotIn('timestamp_seconds', ref['frames'][0])

    def test_video_pts_mismatch_fails_before_decode(self):
        self.row.update(kind='video', source_frame_count=1, source_frame_indices=[0], source_pts_seconds=[2])
        with patch.object(mod, 'command', return_value='{"frames":[{"best_effort_timestamp_time":"1"}]}') as cmd:
            with self.assertRaisesRegex(ValueError, 'frame/PTS mismatch'): self.prepare()
            self.assertEqual(cmd.call_count, 1)
        self.assertFalse((self.output / 'manifest.json').exists())

    def test_existing_output_is_not_overwritten(self):
        self.prepare()
        with self.assertRaises(FileExistsError): self.prepare()
