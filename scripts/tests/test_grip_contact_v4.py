"""Contact crop geometry, loss semantics, label masking and frozen outputs."""
import importlib.util
from pathlib import Path
import unittest

import torch


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / filename)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


class ContactRepairTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.train = module('contact_training_test', 'train-grip-contact-v4.py')
        cls.crop = module('contact_crop_test', 'prepare-grip-contact-v4.py')
        cls.assemble = module('contact_binding_test', 'assemble-grip-contact-v4.py')
        torch.set_num_threads(2)

    def items(self):
        return [(dict(recordingId=g, annotation=dict(sourceGroup=g)), dict(contactReference=dict(contact=label, qualityEligible=eligible)))
                for g, label, eligible in [('a', 'closed', True), ('a', 'closed', True), ('b', 'closed', True),
                                           ('c', 'open', True), ('d', 'invalid', True), ('e', 'uncertain', False)]]

    def test_wider_crop_preserves_hand_and_remains_inside_frame(self):
        for box in [[0, 0, 40, 40], [600, 320, 640, 360], [200, 140, 260, 200]]:
            wide = self.crop.contact_box(box, (640, 360))
            self.assertLessEqual(wide[0], box[0])
            self.assertLessEqual(wide[1], box[1])
            self.assertGreaterEqual(wide[2], box[2])
            self.assertGreaterEqual(wide[3], box[3])
            self.assertTrue(0 <= wide[0] < wide[2] <= 640)
            self.assertTrue(0 <= wide[1] < wide[3] <= 360)
            self.assertEqual(wide[2] - wide[0], wide[3] - wide[1])

    def test_cap_does_not_shrink_an_existing_large_hand_crop(self):
        wide = self.crop.contact_box([20, 20, 250, 250], (640, 360))
        self.assertGreaterEqual(wide[2] - wide[0], 230)

    def test_open_and_invalid_each_receive_their_own_negative_mass(self):
        weights = self.train.training_weights(self.items())
        self.assertAlmostEqual(float(weights[:3].sum()), .5)
        self.assertAlmostEqual(float(weights[3]), .25)
        self.assertAlmostEqual(float(weights[4]), .25)
        self.assertEqual(float(weights[5]), 0.)
        self.assertAlmostEqual(float(weights[:2].sum()), float(weights[2]))

    def test_uncertain_is_neither_contact_positive_nor_negative(self):
        self.assertEqual(self.train.targets(self.items()).tolist(), [1, 1, 1, 0, 0, -100])

    def test_validation_partition_has_disjoint_groups_and_each_negative_subtype(self):
        items = []
        for i in range(8):
            for label in ['closed', 'open', 'invalid']:
                items.append((dict(recordingId=str(i), annotation=dict(sourceGroup=str(i))), dict(contactReference=dict(contact=label, qualityEligible=True))))
        selected = self.train.validation_partition(items, 3106)
        self.assertEqual(selected, self.train.validation_partition(items, 3106))
        self.assertEqual(len(selected), 4)
        for in_selection in [True, False]:
            labels = {p['contactReference']['contact'] for r, p in items if (r['recordingId'] in selected) == in_selection}
            self.assertEqual(labels, {'closed', 'open', 'invalid'})

    def test_both_contact_variants_preserve_anatomy_and_visibility(self):
        torch.manual_seed(17)
        quality = self.train.quality.QualityModel(self.train.quality.surface.SurfaceModel(False), True).eval()
        tight, wide = torch.rand(2, 3, 128, 128), torch.rand(2, 3, 192, 192)
        with torch.inference_mode():
            original = quality(tight)
            for variant in ['tight-control', 'tight-wide-frozen']:
                candidate = self.train.ContactModel(quality, variant).eval()
                output = candidate(tight, wide)
                changed_wide = candidate(tight, 1 - wide)
                for index in [0, 1]:
                    self.assertTrue(torch.equal(original[index], output[index]))
                    self.assertTrue(torch.equal(original[index], changed_wide[index]))
                self.assertTrue(all(not p.requires_grad for p in candidate.quality.parameters()))

    def review_rows(self):
        return [dict(caseId='source', recordingId='video', imageSHA256='frame', annotation=dict(split='train'),
                     patches=[dict(status='patchReady', contactReviewId='A1', contactReviewRequired=True, side='left',
                                   patchSHA256='tight', contactCrop=dict(sha256='wide', box=[0, 0, 10, 10]))])]

    def test_review_conflicts_cannot_silently_enter_training(self):
        notes = dict(first=dict(A1='closed'), second=dict(A1='open'))
        with self.assertRaisesRegex(AssertionError, 'adjudication'):
            self.assemble.bind(self.review_rows(), notes)

    def test_visually_unresolved_contact_is_masked_after_adjudication(self):
        notes = dict(first=dict(A1='closed'), second=dict(A1='open'), adjudications=dict(A1=dict(contact='uncertain', reason='Hidden fingers')))
        refs = self.assemble.bind(self.review_rows(), notes)
        self.assertFalse(refs[0]['qualityEligible'])
        self.assertEqual(refs[0]['contact'], 'uncertain')
        self.assertTrue(refs[0]['conflict'])

    def test_negative_wider_input_requires_new_direct_review(self):
        rows = self.review_rows()
        p = rows[0]['patches'][0]
        p['contactReviewRequired'] = False
        p['contactReference'] = dict(contact='invalid', qualityEligible=True)
        with self.assertRaises(AssertionError):
            self.assemble.bind(rows, dict(first={}, second={}))


if __name__ == '__main__':
    unittest.main()
