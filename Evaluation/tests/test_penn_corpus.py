"""Archive ingestion contracts use original synthetic fixtures, not Penn data."""
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

import numpy as np
from PIL import Image
from scipy.io import savemat

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'scripts'))
import penn_corpus as pc


class PennCorpusTests(unittest.TestCase):
    def test_selection_is_order_independent_and_balanced(self):
        rows = [{'id': f'{i:04}', 'train': -1 if i % 2 else 1} for i in range(30)]
        chosen = pc.choose_sequences(rows, 12)
        self.assertEqual(chosen, pc.choose_sequences(rows[::-1], 12))
        self.assertEqual(6, sum(int(x) % 2 for x in chosen))
        self.assertIn('0000', chosen)
        self.assertIn('0029', chosen)

    def test_selection_rejects_insufficient_or_odd_sample(self):
        rows = [{'id': '0001', 'train': -1}, {'id': '0002', 'train': 1}]
        for count in (0, 1, 3, 4):
            with self.subTest(count=count), self.assertRaises(ValueError):
                pc.choose_sequences(rows, count)

    def test_paths_cannot_escape_root(self):
        for name in ('/abs', '../outside', 'Penn/frames/../../../outside'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                pc.member_path(tarfile.TarInfo(name))
        self.assertEqual('000001.jpg', pc.member_path(tarfile.TarInfo('./Penn/frames/0012/000001.jpg')).name)

    @staticmethod
    def archive(path, missing=False, duplicate=False):
        def add(archive, name, data):
            info = tarfile.TarInfo(name)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
        with tarfile.open(path, 'w:gz') as archive:
            for identifier, split, action in (('0001', -1, 'pull_ups'), ('0002', 1, 'pull_ups'), ('0003', 1, 'other')):
                labels = io.BytesIO()
                savemat(labels, {'x': np.full((3, 13), 20.0), 'y': np.full((3, 13), 30.0),
                                 'visibility': np.ones((3, 13)), 'nframes': 3,
                                 'dimensions': [60, 80, 3], 'action': action, 'train': split})
                add(archive, f'Penn_Action/labels/{identifier}.mat', labels.getvalue())
                for i in range(1, 4):
                    if missing and identifier == '0001' and i == 3:
                        continue
                    data = io.BytesIO()
                    Image.new('RGB', (80, 60), 'gray').save(data, format='JPEG')
                    name = f'Penn_Action/frames/{identifier}/{i:06}.jpg'
                    add(archive, name, data.getvalue())
                    if duplicate and identifier == '0001' and i == 1:
                        add(archive, name, data.getvalue())
            add(archive, 'Penn_Action/README', b'Original synthetic test release, not Penn media.')

    def test_native_archive_keeps_only_selected_frames(self):
        with tempfile.TemporaryDirectory() as work:
            work = Path(work)
            self.archive(work / 'test.tar.gz')
            before = pc.ev.digest(work / 'test.tar.gz')
            pc.inspect(work / 'test.tar.gz', work / 'data', work / 'review', 2)
            report = json.loads((work / 'review/acquisition.json').read_text())
            self.assertEqual(before, pc.ev.digest(work / 'test.tar.gz'))
            self.assertEqual(3, report['sequences'])
            self.assertEqual(2, report['target_sequences'])
            self.assertEqual(6, len(list((work / 'data/frames').rglob('*.jpg'))))
            self.assertFalse((work / 'data/frames/0003').exists())
            self.assertEqual(2, len(list((work / 'review').glob('*-review.jpg'))))
            self.assertFalse(list((work / 'review').rglob('*.mat')))

    def test_missing_or_duplicate_frames_fail(self):
        for option in ('missing', 'duplicate'):
            with self.subTest(option=option), tempfile.TemporaryDirectory() as work:
                work = Path(work)
                self.archive(work / 'test.tar.gz', **{option: True})
                with self.assertRaises(ValueError):
                    pc.inspect(work / 'test.tar.gz', work / 'data', work / 'review', 2)


if __name__ == '__main__':
    unittest.main()
