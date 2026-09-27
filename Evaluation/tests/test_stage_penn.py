"""Original synthetic archive bytes test intake, never native dataset accuracy."""
import hashlib
import io
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import numpy as np
from PIL import Image
from scipy.io import savemat

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import evaluation as ev
import import_poses as imp
import stage_penn as intake


def jpeg(size=(96, 64), seed=0):
    buffer = io.BytesIO()
    Image.new('RGB', size, (seed, 20, 40)).save(buffer, format='JPEG')
    return buffer.getvalue()


def label(action='pull_ups', count=2, nested=False, malformed=False):
    buffer = io.BytesIO()
    data = dict(action=action, nframes=count, dimensions=[64, 96, count], train=-1,
                x=np.full((count, 13), 24.), y=np.full((count, 13), 32.),
                visibility=np.ones((count, 13), bool))
    if malformed:
        data['nframes'] = count + 1
    savemat(buffer, {'annotation': data} if nested else data)
    return buffer.getvalue()


class IntakeTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        self.archive = self.root / 'penn.tar.gz'
        self.output = self.root / 'corpus'
        self.files = {}
        for identifier, action in [('0001', 'pull_ups'), ('0002', 'push_ups'), ('0003', 'pull_ups')]:
            self.files[f'Penn_Action/labels/{identifier}.mat'] = label(action, nested=identifier == '0003')
            for frame in (1, 2):
                self.files[f'Penn_Action/frames/{identifier}/{frame:06d}.jpg'] = jpeg(seed=frame)

    def tearDown(self):
        self.tmp.cleanup()

    def write_archive(self, extra=None, reverse=False):
        items = list(self.files.items())
        if reverse:
            items.reverse()
        with tarfile.open(self.archive, 'w:gz') as tar:
            for name, data in items:
                entry = tarfile.TarInfo(name); entry.size = len(data)
                tar.addfile(entry, io.BytesIO(data))
            for entry, data in extra or []:
                tar.addfile(entry, io.BytesIO(data))

    def rejected(self, expected):
        with self.assertRaisesRegex((ValueError, tarfile.TarError), expected):
            intake.stage(self.archive, self.output)
        self.assertFalse(self.output.exists())
        self.assertEqual(list(self.root.glob('.penn-stage-*')), [])

    def test_synthetic_native_archive_and_reviewed_import_round_trip(self):
        self.write_archive(reverse=True)
        original = ev.digest(self.archive)
        report = intake.stage(self.archive, self.output, limit=0, expected_sha256=original)
        self.assertEqual(report['pull_up_candidates'], 2)
        self.assertEqual(report['native_annotations_inspected'], 3)
        self.assertEqual(report['selected_frames'], 4)
        self.assertEqual(report['counts_by_action'], {'pull_ups': 2, 'push_ups': 1})
        self.assertEqual(ev.digest(self.archive), original)
        draft = ev.read_json(self.output / 'review.json')
        self.assertEqual([c['id'] for c in draft['clips']], ['penn_0001', 'penn_0003'])
        self.assertEqual([ev.preflight(c, self.output) for c in draft['clips']], ['not_approved'] * 2)
        for clip in draft['clips']:
            self.assertIsNone(clip['subject_group'])
            self.assertEqual(clip['split'], 'unassigned')
            self.assertIsNone(clip['annotation_review']['pixel_origin'])
            self.assertFalse(clip['annotation_review']['independently_reviewed'])
            self.assertEqual(clip['native_split'], 'penn_train_flag:-1')
            # Original bytes are preserved, not re-encoded for the runner.
            for item in clip['media']['files']:
                self.assertEqual((self.output / item['path']).read_bytes(), self.files['Penn_Action/' + item['path']])
            clip['rights'] = dict(status='approved', evidence='original synthetic test bytes', public_outputs=False)
            clip['annotation_review'] = dict(independently_reviewed=True, pixel_origin=0, provenance='synthetic coordinates')
        ev.write_json(self.output / 'synthetic-review.json', draft)
        self.assertEqual(imp.convert_manifest(self.output / 'synthetic-review.json', self.output, self.output / 'converted'), 0)
        self.assertTrue((self.output / 'converted/manifest.json').is_file())
        self.assertFalse((self.output / 'frames/0002').exists())

    def test_limit_is_explicit_and_unselected_candidates_are_recorded(self):
        self.write_archive()
        report = intake.stage(self.archive, self.output, limit=1)
        self.assertEqual(report['selected_sequence_ids'], ['0001'])
        self.assertEqual(report['not_selected_sequence_ids'], ['0003'])
        self.assertEqual(report['selected_sequences'], 1)
        self.assertFalse((self.output / 'frames/0003').exists())

    def test_unreviewed_intake_cannot_become_a_scored_corpus(self):
        self.write_archive()
        intake.stage(self.archive, self.output)
        self.assertEqual(imp.convert_manifest(self.output / 'review.json', self.output, self.output / 'converted'), 2)
        self.assertFalse((self.output / 'converted/manifest.json').exists())

    def test_rootless_publisher_layout_is_supported(self):
        self.files = {k.removeprefix('Penn_Action/'): v for k, v in self.files.items()}
        self.write_archive()
        self.assertEqual(intake.stage(self.archive, self.output)['selected_sequences'], 2)

    def test_pin_mismatch_and_existing_output_are_not_overwritten(self):
        self.write_archive()
        with self.assertRaisesRegex(ValueError, 'SHA-256'):
            intake.stage(self.archive, self.output, expected_sha256='0' * 64)
        self.output.mkdir(); sentinel = self.output / 'keep'; sentinel.write_text('keep')
        with self.assertRaisesRegex(ValueError, 'already exists'):
            intake.stage(self.archive, self.output)
        self.assertEqual(sentinel.read_text(), 'keep')

    def test_missing_frame_or_label_rejects_corpus(self):
        for name in ('Penn_Action/frames/0001/000002.jpg', 'Penn_Action/labels/0002.mat'):
            with self.subTest(name=name):
                data = self.files.pop(name)
                self.write_archive(); self.rejected('incomplete|matching native')
                self.files[name] = data

    def test_noncontiguous_frames_are_rejected(self):
        self.files['Penn_Action/frames/0001/000009.jpg'] = self.files.pop('Penn_Action/frames/0001/000002.jpg')
        self.write_archive(); self.rejected('noncontiguous')

    def test_malformed_annotation_does_not_silently_reduce_inventory(self):
        self.files['Penn_Action/labels/0001.mat'] = label(malformed=True)
        self.write_archive(); self.rejected('dimensions')

    def test_duplicate_logical_sequences_across_roots_are_rejected(self):
        self.files['labels/0001.mat'] = self.files['Penn_Action/labels/0001.mat']
        self.write_archive(); self.rejected('Duplicate logical')

    def test_traversal_absolute_and_duplicate_paths_are_rejected(self):
        for name in ('../outside', '/absolute', 'a\\b', 'Penn_Action/labels/0001.mat'):
            with self.subTest(name=name):
                entry = tarfile.TarInfo(name); entry.size = 1
                self.write_archive(extra=[(entry, b'x')]); self.rejected('Unsafe|Duplicate')

    def test_links_and_special_members_are_rejected(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.FIFOTYPE):
            with self.subTest(kind=kind):
                entry = tarfile.TarInfo('unsafe'); entry.type = kind; entry.linkname = '../../outside'
                self.write_archive(extra=[(entry, b'')]); self.rejected('links/special')

    def test_dimensions_or_broken_jpeg_prevent_publishing_stage(self):
        name = 'Penn_Action/frames/0001/000001.jpg'
        for data in (jpeg(size=(64, 96)), b'not an image'):
            with self.subTest(data=data[:15]):
                self.files[name] = data; self.write_archive()
                with self.assertRaises((ValueError, OSError)):
                    intake.stage(self.archive, self.output)
                self.assertFalse(self.output.exists())
                self.assertEqual(list(self.root.glob('.penn-stage-*')), [])

    def test_archive_and_member_size_budgets_are_enforced(self):
        self.write_archive()
        for bound in ('MAX_DOWNLOAD', 'MAX_EXPANDED', 'MAX_LABEL', 'MAX_IMAGE', 'MAX_MEMBERS'):
            with self.subTest(bound=bound), patch.object(intake, bound, 1):
                self.rejected('limit')

    def test_wrong_or_empty_archive_does_not_claim_native_coverage(self):
        self.files = {'README.txt': b'no dataset here'}
        self.write_archive(); self.rejected('No supported')


class DownloadTests(unittest.TestCase):
    class Response(io.BytesIO):
        def __init__(self, data=b'data', length='4', url=intake.SOURCE_URL):
            super().__init__(data); self.headers = {'Content-Length': length}; self.url = url
        def geturl(self):
            return self.url

    def test_download_records_exact_bytes_without_inventing_publisher_digest(self):
        with tempfile.TemporaryDirectory() as d, patch.object(intake.urllib.request, 'urlopen', return_value=self.Response()):
            path = Path(d) / 'archive'
            report = intake.download(path)
            self.assertEqual(path.read_bytes(), b'data')
            self.assertEqual(report['sha256'], hashlib.sha256(b'data').hexdigest())
            self.assertEqual(report['pin_status'], 'locally_recorded_not_publisher_authenticated')
            self.assertEqual(list(Path(d).glob('.penn-*')), [])

    def test_network_failure_does_not_leave_files_or_fake_success(self):
        with tempfile.TemporaryDirectory() as d, patch.object(intake.urllib.request, 'urlopen', side_effect=OSError('unavailable')):
            path = Path(d) / 'archive'
            with self.assertRaises(OSError):
                intake.download(path)
            self.assertEqual(list(Path(d).iterdir()), [])

    def test_insecure_truncated_oversized_and_mismatched_downloads_are_rejected(self):
        cases = [(self.Response(url='http://insecure'), None), (self.Response(length='5'), None),
                 (self.Response(length=str(intake.MAX_DOWNLOAD + 1)), None), (self.Response(), '0' * 64)]
        for response, pin in cases:
            with self.subTest(pin=pin), tempfile.TemporaryDirectory() as d, patch.object(intake.urllib.request, 'urlopen', return_value=response):
                path = Path(d) / 'archive'
                with self.assertRaises(ValueError):
                    intake.download(path, pin)
                self.assertEqual(list(Path(d).iterdir()), [])

    def test_existing_download_is_never_replaced(self):
        with tempfile.TemporaryDirectory() as d, patch.object(intake.urllib.request, 'urlopen') as call:
            path = Path(d) / 'archive'; path.write_bytes(b'keep')
            with self.assertRaises(ValueError):
                intake.download(path)
            call.assert_not_called(); self.assertEqual(path.read_bytes(), b'keep')


if __name__ == '__main__':
    unittest.main()
