import importlib.util
from pathlib import Path
import unittest

PATH = Path(__file__).resolve().parents[1] / "evaluate-binary-grip-holdout.py"
spec = importlib.util.spec_from_file_location("grip_holdout", PATH)
scorer = importlib.util.module_from_spec(spec); spec.loader.exec_module(scorer)


class BinaryGripHoldoutTests(unittest.TestCase):
    def test_missing_hands_remain_in_the_denominator(self):
        r = scorer.metrics([("underhand", ["unknown", "unknown"]), ("overhand", ["overhand", "unknown"])])
        self.assertEqual(r["expectedHands"], 4); self.assertEqual(r["unknown"], 3)
        self.assertEqual(r["correctAmongAllHands"], .25)
        self.assertEqual(r["coverage"], .25); self.assertEqual(r["accuracyAmongPredictions"], 1)

    def test_wrong_orientation_and_neutral_are_not_counted_as_abstentions(self):
        r = scorer.metrics([("underhand", ["overhand", "neutral"])])
        self.assertEqual(r["wrong"], 2); self.assertEqual(r["unknown"], 0)

    def test_public_source_and_training_leakage_are_rejected(self):
        c = dict(status="binaryLabel", sourceGroup="youtube:one", recordingId="a", split="test")
        with self.assertRaises(ValueError):
            scorer.check_separation([c], [dict(recordingId="a")])
        with self.assertRaises(ValueError):
            scorer.check_separation([c, dict(c, recordingId="b", split="train")], [])

    def test_duplicate_decisions_and_invalid_classes_fail(self):
        row = dict(recordingId="a", observation=dict(seconds=1))
        with self.assertRaises(ValueError): scorer.indexed([row, row])
        with self.assertRaises(ValueError): scorer.metrics([("overhand", ["fake", "unknown"])])


if __name__ == "__main__": unittest.main()
