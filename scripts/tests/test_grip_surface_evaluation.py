"""Do not confuse forced surface choices with usable accepted evidence."""
from collections import Counter
import importlib.util
from pathlib import Path
import unittest


class SurfaceEvaluationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec=importlib.util.spec_from_file_location('surface_eval_test',Path(__file__).resolve().parents[1]/'evaluate-grip-surface-v2.py')
        cls.m=importlib.util.module_from_spec(spec);spec.loader.exec_module(cls.m)

    def test_clear_reference_without_confidence_is_raw_correct_but_unknown(self):
        c=Counter();self.m.count(c,1,[.1,.7,.2],.95)
        self.assertEqual(c['forcedKnownCorrect'],1);self.assertEqual(c['unknown'],1)
        self.assertEqual(c['acceptedCorrect'],0)

    def test_visually_unclear_reference_is_not_given_accuracy_credit(self):
        c=Counter();self.m.count(c,-1,[.02,.96,.02],.9)
        self.assertEqual(c['acceptedUnverifiable'],1);self.assertEqual(c['known'],0)
        self.assertEqual(c['forcedKnownCorrect'],0)

    def test_visibility_can_reject_a_high_surface_score(self):
        c=Counter();self.m.count(c,0,[.98,.01,.01],.2)
        self.assertEqual(c['unknown'],1);self.assertEqual(c['acceptedCorrect'],0)


if __name__=='__main__':unittest.main()
