"""Original synthetic MAT/NPY fixtures exercise conversion, not real pose accuracy."""
import copy
from pathlib import Path
import sys
import tempfile
import unittest

import numpy as np
from PIL import Image
from scipy.io import savemat

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import evaluation as ev
import import_poses as imp


class ImportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.labels = self.root / 'labels.mat'
        self.data = dict(action='pull_ups', nframes=2, dimensions=[64, 96, 2], train=-1,
                         x=np.tile(np.arange(13) + 10., (2, 1)),
                         y=np.tile(np.arange(13) + 20., (2, 1)), visibility=np.ones((2, 13), bool))
        savemat(self.labels, self.data)
        images = self.root / 'images'; images.mkdir()
        for i in range(2):
            Image.new('RGB', (96, 64), (20 + i, 0, 0)).save(images / f'{i + 1:06d}.jpg')
        files = [{'path': str(p.relative_to(self.root)), 'sha256': ev.digest(p)} for p in sorted(images.iterdir())]
        self.clip = dict(id='penn_synthetic', dataset='synthetic-MAT-not-Penn-footage', exercise='pull_up',
                         split='unassigned', source_group='synthetic-source', subject_group=None,
                         rights=dict(status='approved', evidence='original test pixels', public_outputs=True),
                         media=dict(kind='images', files=files, expected_frames=2),
                         native_annotations=dict(format='penn_action_mat', path='labels.mat', sha256=ev.digest(self.labels)),
                         annotation_review=dict(independently_reviewed=True, provenance='synthetic contract fixture', pixel_origin=0))
        self.path = self.root / 'review.json'
        self.out = self.root / 'converted'

    def tearDown(self):
        self.temp.cleanup()

    def run_import(self, clips=None):
        ev.write_json(self.path, dict(schema_version=1, clips=clips or [self.clip]))
        return imp.convert_manifest(self.path, self.root, self.out)

    def change_mat(self, nested=False):
        savemat(self.labels, {'annotation': self.data} if nested else self.data)
        self.clip['native_annotations']['sha256'] = ev.digest(self.labels)

    def reference(self):
        return ev.read_json(self.out / (self.clip['id'] + '.json'))

    def make_haa(self):
        for i, f in enumerate(self.clip['media']['files']):
            old = self.root / f['path']; new = old.with_name(f'{i + 1:04d}.png')
            with Image.open(old) as image:
                image.save(new)
            old.unlink()
            f.update(path=str(new.relative_to(self.root)), sha256=ev.digest(new))
        self.labels = self.root / 'labels.npy'
        coordinates = np.zeros((2, 17, 2)); coordinates[..., 0] = 30; coordinates[..., 1] = 40
        np.save(self.labels, coordinates)
        self.clip['native_annotations'] = dict(format='haa4d_npy', path='labels.npy', sha256=ev.digest(self.labels))
        self.clip['annotation_review']['visible_frames'] = [dict(frame_index=1, visible_native_joints=[11, 12, 13, 14, 15, 16])]
        return coordinates

    def test_penn_joint_order_head_omission_and_native_flag(self):
        self.assertEqual(self.run_import(), 0)
        r = self.reference()
        p = r['frames'][0]['points']
        self.assertEqual(p['leftWrist'], [15, 25])
        self.assertEqual(p['rightWrist'], [16, 26])
        self.assertNotIn('nose', p)
        self.assertEqual(len(p), 12)
        self.assertEqual(r['conversion']['native_split'], 'penn_train_flag:-1')
        m = ev.read_json(self.out / 'manifest.json'); ev.validate_manifest(m, self.root)
        self.assertEqual(ev.preflight(m['clips'][0], self.root), 'ready')
        self.assertEqual(r['conversion_review_sha256'], ev.digest(self.path))

    def test_actual_native_pullup_spelling_is_preserved(self):
        self.data['action'] = 'pullup'; self.change_mat()
        self.assertEqual(self.run_import(), 0)
        self.assertEqual(self.reference()['conversion']['native_action'], 'pullup')

    def test_other_native_actions_are_not_promoted(self):
        self.data['action'] = 'pushup'; self.change_mat()
        self.assertEqual(self.run_import(), 2)
        self.assertFalse((self.out / 'manifest.json').exists())

    def test_nested_mat_and_one_based_pixel_origin(self):
        self.change_mat(nested=True)
        self.clip['annotation_review']['pixel_origin'] = 1
        self.assertEqual(self.run_import(), 0)
        self.assertEqual(self.reference()['frames'][0]['points']['leftShoulder'], [10, 20])

    def test_hidden_nonfinite_is_omitted_not_scored(self):
        self.data['visibility'][0, 3] = False
        self.data['x'][0, 3] = np.nan
        self.change_mat()
        self.assertEqual(self.run_import(), 0)
        self.assertNotIn('leftElbow', self.reference()['frames'][0]['points'])

    def test_visible_outside_image_is_rejected_not_clamped(self):
        self.data['x'][0, 3] = 96
        self.change_mat()
        self.assertEqual(self.run_import(), 2)
        self.assertFalse((self.out / 'manifest.json').exists())

    def test_frame_count_mismatch(self):
        self.data['nframes'] = 3; self.change_mat()
        self.assertEqual(self.run_import(), 2)

    def test_transposed_native_arrays_not_silently_fixed(self):
        self.data['x'] = self.data['x'].T; self.change_mat()
        self.assertEqual(self.run_import(), 2)

    def test_expected_frame_count_is_not_overridden(self):
        self.clip['media']['expected_frames'] = 3
        with self.assertRaises(ValueError):
            self.run_import()

    def test_unreviewed_labels_are_not_promoted(self):
        self.clip['annotation_review']['independently_reviewed'] = False
        self.assertEqual(self.run_import(), 2)

    def test_nonbinary_visibility_rejected(self):
        self.data['visibility'] = np.full((2, 13), .8); self.change_mat()
        self.assertEqual(self.run_import(), 2)

    def test_native_hash_mismatch(self):
        self.clip['native_annotations']['sha256'] = '0' * 64
        self.assertEqual(self.run_import(), 2)

    def test_unreviewed_or_implicit_origin_is_not_certified(self):
        del self.clip['annotation_review']['pixel_origin']
        self.assertEqual(self.run_import(), 2)

    def test_unapproved_media_is_never_converted(self):
        self.clip['rights']['status'] = 'pending'
        self.labels.unlink()
        self.assertEqual(self.run_import(), 2)
        self.assertEqual(ev.read_json(self.out / 'import-report.json')['clips'][0]['status'], 'not_approved')

    def test_original_image_order_is_required(self):
        self.clip['media']['files'].reverse()
        self.assertEqual(self.run_import(), 2)

    def test_extra_native_image_is_rejected(self):
        Image.new('RGB', (96, 64)).save(self.root / 'images/000003.jpg')
        self.assertEqual(self.run_import(), 2)

    def test_actual_dimensions_and_exif_are_not_ignored(self):
        p = self.root / self.clip['media']['files'][0]['path']
        im = Image.new('RGB', (64, 96)); exif = Image.Exif(); exif[274] = 6
        im.save(p, exif=exif)
        self.clip['media']['files'][0]['sha256'] = ev.digest(p)
        self.assertEqual(self.run_import(), 2)

    def test_haa_requires_visibility_and_omits_hands(self):
        self.make_haa()
        self.assertEqual(self.run_import(), 0)
        r = self.reference()
        self.assertEqual([f['frame_index'] for f in r['frames']], [1])
        self.assertEqual(set(r['frames'][0]['points']), {'leftShoulder', 'rightShoulder', 'leftElbow', 'rightElbow'})
        self.assertIn(13, r['conversion']['omitted_native_joints'])
        self.assertIn(16, r['conversion']['omitted_native_joints'])

    def test_haa_missing_visibility_does_not_assume_all_visible(self):
        self.make_haa(); del self.clip['annotation_review']['visible_frames']
        self.assertEqual(self.run_import(), 2)

    def test_lifted_3d_is_rejected(self):
        self.make_haa(); np.save(self.labels, np.zeros((2, 17, 3)))
        self.clip['native_annotations']['sha256'] = ev.digest(self.labels)
        self.assertEqual(self.run_import(), 2)

    def test_object_array_never_unpickled(self):
        self.make_haa(); np.save(self.labels, np.array([{'unsafe': 'object'}], dtype=object))
        self.clip['native_annotations']['sha256'] = ev.digest(self.labels)
        self.assertEqual(self.run_import(), 2)

    def test_haa_duplicate_review_frame_rejected(self):
        self.make_haa(); self.clip['annotation_review']['visible_frames'] *= 2
        self.assertEqual(self.run_import(), 2)

    def test_endpoint_needs_reviewed_visible_points(self):
        self.make_haa(); self.clip['annotation_review']['endpoint_frames'] = [0]
        self.assertEqual(self.run_import(), 2)

    def test_failed_clip_does_not_silently_shrink_corpus(self):
        other = copy.deepcopy(self.clip); other['id'] = 'blocked'
        other['rights']['status'] = 'denied'
        self.assertEqual(self.run_import([self.clip, other]), 2)
        self.assertFalse((self.out / 'manifest.json').exists())
        self.assertEqual(len(ev.read_json(self.out / 'import-report.json')['clips']), 2)

    def test_source_bytes_unchanged_and_output_not_overwritten(self):
        before = ev.digest(self.labels)
        self.assertEqual(self.run_import(), 0)
        self.assertEqual(ev.digest(self.labels), before)
        with self.assertRaises(FileExistsError):
            self.run_import()


if __name__ == '__main__':
    unittest.main()
