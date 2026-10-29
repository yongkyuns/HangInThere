"""Localization accounting must not credit duplicates, ambiguous owners or empty crops."""
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('localization_scoring_test', Path(__file__).resolve().parents[1]/'score-grip-localization-v6.py')
score = importlib.util.module_from_spec(spec)
spec.loader.exec_module(score)


class LocalizationScoringTests(unittest.TestCase):
    def item(self, id, frame='f1'):
        return dict(reviewId=id, frameId=frame, recordingId='video')

    def test_duplicate_outputs_cannot_inflate_coverage(self):
        result = score.metrics([self.item('p1'), self.item('p2')], {'f1':dict(expectedHands=['h1'])}, {'p1':'h1','p2':'h1'})
        self.assertEqual(result['coveredCompleteHands'], 1)
        self.assertEqual(result['counts']['duplicateHandAccept'], 1)

    def test_unknown_ownership_and_partial_hands_are_not_correct(self):
        items = [self.item(f'p{i}') for i in range(4)]
        result = score.metrics(items, {'f1':dict(expectedHands=['h1'])}, dict(p0='a',p1='p',p2='n',p3='w'))
        self.assertEqual(result['coveredCompleteHands'], 0)
        self.assertEqual(result['counts']['ambiguousOwnership'], 1)
        self.assertEqual(result['counts']['wrongPerson'], 1)

    def test_empty_frames_stay_in_source_denominator(self):
        result = score.metrics([], {'f1':dict(expectedHands=['h1']), 'f2':dict(expectedHands=[])}, {})
        self.assertEqual(result['sourceFrames'], 2)
        self.assertEqual(result['expectedCompleteVisibleHands'], 1)
        self.assertEqual(result['completeHandCoverage'], 0.)

    def test_cannot_credit_a_hand_without_a_source_reference(self):
        with self.assertRaises(AssertionError):
            score.metrics([self.item('p1')], {'f1':dict(expectedHands=['h1'])}, {'p1':'h2'})


if __name__ == '__main__':
    unittest.main()
