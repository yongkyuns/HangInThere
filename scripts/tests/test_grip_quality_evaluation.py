"""Count unsafe accepts separately from anatomical correctness."""
import importlib.util
from pathlib import Path
import unittest

import torch


class QualityEvaluationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('quality_eval_tests', Path(__file__).resolve().parents[1] / 'evaluate-grip-quality-v3.py')
        cls.m = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.m)

    def value(self, surface, visible=.95, contact=.95):
        return torch.tensor(surface), torch.tensor([1-visible, visible]), torch.tensor([1-contact, contact])

    def test_correct_surface_still_needs_independent_quality_evidence(self):
        values = [self.value([.95, .03, .02], visible=.6), self.value([.95, .03, .02], contact=.6)]
        refs = [dict(surface='palm', contact='closed')] * 2
        metrics = self.m.gate_metrics(values, refs)
        self.assertEqual(metrics['fullQualityGate']['rawCorrect'], 2)
        self.assertEqual(metrics['fullQualityGate']['rejected'], 2)
        self.assertEqual(metrics['appearanceGate']['accepted'], 1)

    def test_known_wrong_unclear_and_open_accepts_are_not_successes(self):
        values = [self.value([.95, .03, .02])] * 4
        refs = [dict(surface='palm', contact='closed'), dict(surface='dorsum', contact='closed'),
                dict(surface='unclear', contact='closed'), dict(surface='palm', contact='open')]
        metrics = self.m.gate_metrics(values, refs)['fullQualityGate']
        self.assertEqual(metrics['accepted'], 4)
        self.assertEqual(metrics['acceptedUsableCorrect'], 1)
        self.assertEqual(metrics['acceptedUnsafe'], 3)
        self.assertEqual(metrics['acceptedNonContact'], 1)
        self.assertEqual(metrics['acceptedUnverifiable'], 1)

    def test_identity_unverified_is_not_counted_as_usable_hand(self):
        metrics = self.m.gate_metrics([self.value([.95, .03, .02])], [dict(surface='palm', contact='closed', qualityEligible=False)])
        self.assertEqual(metrics['contact']['masked'], 1)
        self.assertEqual(metrics['fullQualityGate']['acceptedUnsafe'], 1)
        self.assertEqual(metrics['fullQualityGate']['acceptedIdentityUnverified'], 1)


if __name__ == '__main__':
    unittest.main()
