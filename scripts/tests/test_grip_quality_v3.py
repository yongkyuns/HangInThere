"""Quality training must preserve anatomy, label masks and fixed confidence gates."""
import importlib.util
from pathlib import Path
import unittest

import torch


class QualityRepairTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('quality_v3_tests', Path(__file__).resolve().parents[1] / 'train-grip-quality-v3.py')
        cls.m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.m)
        torch.set_num_threads(2)

    def items(self, groups):
        return [(dict(recordingId=g, annotation=dict(sourceGroup=g)), {}) for g in groups]

    def test_each_quality_head_balances_its_own_classes_and_video_groups(self):
        items = self.items(['a', 'a', 'b', 'c', 'c', 'c'])
        labels = torch.tensor([1, 1, 1, 0, 0, 0])
        weights = self.m.quality_weights(items, labels, True)
        self.assertAlmostEqual(float(weights[labels == 1].sum()), .5)
        self.assertAlmostEqual(float(weights[labels == 0].sum()), .5)
        self.assertAlmostEqual(float(weights[:2].sum()), float(weights[2]))

    def test_natural_video_calibration_does_not_force_half_positive(self):
        weights = self.m.quality_weights(self.items(['a', 'b', 'b', 'c']), torch.tensor([1, 0, 0, 0]), False)
        self.assertAlmostEqual(float(weights[0]), 1 / 3, places=6)
        self.assertAlmostEqual(float(weights[1:3].sum()), 1 / 3, places=6)

    def test_unknown_contact_target_is_masked_in_weights_and_loss(self):
        labels = torch.tensor([1, 0, -100])
        weights = self.m.quality_weights(self.items(['a', 'b', 'c']), labels, True)
        logits = torch.tensor([[0., 2.], [2., 0.], [100., -100.]])
        self.assertEqual(float(weights[2]), 0.)
        expected = torch.nn.functional.cross_entropy(logits[:2], labels[:2])
        self.assertAlmostEqual(float(self.m.weighted_loss(logits, labels, weights)), float(expected))

    def test_affine_calibration_cannot_reverse_example_ranking(self):
        logits = torch.tensor([[0., -2.], [0., -1.], [0., 1.], [0., 2.]])
        labels = torch.tensor([0, 0, 1, 1])
        slope, bias = self.m.fit_calibration(logits, labels, torch.full((4,), .25))
        prob = self.m.calibrated_binary(logits, slope, bias)[:, 1]
        self.assertGreater(slope, 0.)
        self.assertTrue(bool((prob[1:] > prob[:-1]).all()))

    def test_quality_heads_cannot_change_anatomical_output(self):
        torch.manual_seed(25)
        original = self.m.surface.SurfaceModel(False).eval()
        images = torch.rand(2, 3, 128, 128)
        with torch.inference_mode():
            expected = original(images)[0]
            for multiscale in [False, True]:
                model = self.m.QualityModel(original, multiscale).eval()
                model.slope.fill_(2.)
                model.bias.fill_(-3.)
                actual = model(images)[0]
                self.assertTrue(torch.equal(expected, actual))
                self.assertTrue(all(not p.requires_grad for p in model.appearance.parameters()))

    def test_quality_can_accept_positive_and_reject_negative_at_original_threshold(self):
        # These are authored logits, not claims about learned model accuracy.
        prob = self.m.calibrated_binary(torch.tensor([[0., 3.], [0., -3.]]), 1., 0.)
        self.assertGreater(float(prob[0, 1]), .8)
        self.assertLess(float(prob[1, 1]), .2)
        self.assertTrue(torch.allclose(prob.sum(1), torch.ones(2)))

    def test_majority_prior_cannot_turn_negative_into_accepted_contact(self):
        logits = torch.tensor([[0., 2.], [1., 0.]])
        labels = torch.tensor([1, 0])
        guard = self.m.guarded_calibration(logits, labels, .000001, 2.)
        self.assertTrue(guard['rejected'])
        self.assertEqual(guard['rawFalseAccepts'], 0)
        self.assertEqual(guard['proposedFalseAccepts'], 1)
        self.assertEqual((guard['slope'], guard['bias']), (1., 0.))

    def test_safe_calibration_can_sharpen_evidence_without_accepting_negatives(self):
        logits = torch.tensor([[0., 1.], [1., 0.]])
        guard = self.m.guarded_calibration(logits, torch.tensor([1, 0]), 2., 0.)
        self.assertFalse(guard['rejected'])
        self.assertEqual((guard['slope'], guard['bias']), (2., 0.))


if __name__ == '__main__':
    unittest.main()
