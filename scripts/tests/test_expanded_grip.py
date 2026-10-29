import importlib.util
from pathlib import Path
import unittest
from unittest.mock import patch
import numpy as np
from collections import Counter

ROOT=Path(__file__).absolute().parents[2]
def module(name,file):
    s=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
training=module('expanded_training','train-expanded-grip.py')
scoring=module('expanded_scoring','evaluate-expanded-grip.py')


class ExpandedGripTests(unittest.TestCase):
    def row(self,split='train',accepted=False):
        return dict(manualViewReview=True,reviewedViewpoint='front',annotation=dict(split=split),
            patches=[dict(status='patchReady',origin='manualReviewedTrainingCrop',manualAcceptanceRequired=not accepted)])

    def test_manual_crop_cannot_enter_validation(self):
        with patch.object(training.base,'load',return_value=[self.row('validation',True)]):
            with self.assertRaisesRegex(AssertionError,'held-out'):training.load(Path('unused'))

    def test_unreviewed_training_proposal_cannot_enter_fitting(self):
        with patch.object(training.base,'load',return_value=[self.row()]):
            with self.assertRaisesRegex(AssertionError,'Unreviewed'):training.load(Path('unused'))

    def test_corrected_direct_target_overrides_provisional_source_label(self):
        r=dict(annotation=dict(label='underhand'))
        p=dict(manualReview=dict(grip='overhand',view='rear'))
        self.assertEqual(training.items([(r,p)])[0]['target'],0)
        self.assertEqual(training.reviewed_views([(r,p)]).tolist(),[[0.,1.,0.]])

    def test_wrong_person_crop_is_not_correct_because_grip_name_matches(self):
        m=Counter();scoring.add(m,[.95,.05],'overhand',valid=False)
        self.assertEqual(m['acceptedWrong'],1);self.assertEqual(m['rawCorrect'],0)

    def test_missing_and_tied_inputs_remain_in_unknown_denominator(self):
        m=Counter();scoring.add(m,None,'underhand');scoring.add(m,[.5,.5],'underhand')
        self.assertEqual(m['expectedHands'],2);self.assertEqual(m['unknown'],2)
        self.assertEqual(m['acceptedCorrect']+m['acceptedWrong'],0)


if __name__=='__main__':unittest.main()
