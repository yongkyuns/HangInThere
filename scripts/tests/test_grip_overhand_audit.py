import importlib.util
from pathlib import Path
import tempfile
import unittest

from PIL import Image

ROOT = Path(__file__).absolute().parents[2]
SPEC = importlib.util.spec_from_file_location('overhand_audit', ROOT/'scripts/audit-overhand-regressions.py')
audit = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(audit)


class OverhandAuditTests(unittest.TestCase):
    def prediction(self, view, appearance, orientation):
        return dict(expected='overhand', argmax='underhand', orientationExpected=view,
                    appearanceScores=appearance, orientationScores=orientation,
                    context=dict(faceCues=[0, 0, 0, 0, 0, 1]))

    def test_correct_hand_wrong_orientation_is_isolated(self):
        p = self.prediction('front', [.99, .01], [.05, .9, .05])
        result = audit.attribution(p)
        self.assertEqual(result['category'], 'orientation')
        self.assertGreater(result['oracleViewGrip'][0], .9)
        self.assertLess(result['oracleHandGrip'][0], .5)

    def test_correct_orientation_wrong_hand_is_isolated(self):
        p = self.prediction('rear', [.99, .01], [.05, .9, .05])
        result = audit.attribution(p)
        self.assertEqual(result['category'], 'appearance')
        self.assertGreater(result['oracleHandGrip'][0], .9)
        self.assertLess(result['oracleViewGrip'][0], .5)

    def test_fusion_direction_is_not_the_three_way_argmax(self):
        p = self.prediction('rear', [.01, .99], [.3, .1, .6])
        result = audit.attribution(p)
        self.assertEqual(result['category'], 'orientation')
        self.assertEqual(result['fusionDirection'], 'front')

    def test_oblique_has_no_binary_appearance_reference(self):
        p = self.prediction('oblique', [.01, .99], [.9, .05, .05])
        result = audit.attribution(p)
        self.assertEqual(result['category'], 'oblique')
        self.assertIsNone(result['expectedAppearance'])
        self.assertEqual(result['oracleViewGrip'], [.5, .5])

    def test_cancelling_errors_are_not_attributed_as_raw_failures(self):
        p = self.prediction('front', [.01, .99], [.05, .9, .05])
        with self.assertRaises(AssertionError):
            audit.attribution(p)

    def test_a_valid_hash_cannot_hide_wrong_crop_pixels(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            frame = Image.new('RGB', (100, 100), '#a02c70')
            source = root/'frame.png'; frame.save(source)
            hand = root/'hand.png'; head = root/'head.png'
            box = [5, 10, 35, 40]
            frame.crop(box).resize((128, 128), Image.Resampling.BILINEAR).save(hand)
            frame.crop(box).resize((128, 128), Image.Resampling.BILINEAR).save(head)
            row = dict(framesDirectory=str(root), image=source.name, imageSHA256=audit.sha(source),
                       context=dict(image=str(head), sha256=audit.sha(head), box=box))
            patch = dict(patch=str(hand), patchSHA256=audit.sha(hand), box=box)
            audit.verify_crop(row, patch)
            Image.new('RGB', (128, 128), 'black').save(hand)
            patch['patchSHA256'] = audit.sha(hand)
            with self.assertRaises(AssertionError):
                audit.verify_crop(row, patch)


if __name__ == '__main__':
    unittest.main()
