import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('oracle_v7',Path(__file__).parents[1]/'evaluate-grip-oracle-v7.py')
m=importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class OracleDiagnosticTests(unittest.TestCase):
    def test_border_crop_contains_real_hand_without_synthetic_pixels(self):
        self.assertEqual(m.crop_box([0,0,20,30],(100,60),2.0),[0,0,60,60])
        box=m.crop_box([80,35,100,60],(100,60),1.6)
        self.assertEqual(box,[60,20,100,60])

    def test_correct_class_does_not_bypass_contact_gate(self):
        self.assertEqual(m.decision([.02,.94,.04],.79,True),dict(raw='underhand',accepted='unknown'))
        self.assertEqual(m.decision([.02,.94,.04],.81,True)['accepted'],'underhand')

    def test_missing_orientation_stays_unknown_even_with_confident_hand(self):
        self.assertEqual(m.decision([.01,.98,.01],.99,False),dict(raw='unknown',accepted='unknown'))

    def test_clipped_hand_cannot_be_credited_for_matching_activity_label(self):
        records=[dict(gripReference='underhand',handReference=dict(status='sourceClipped'),
                      modes=dict(a=dict(raw='underhand',accepted='underhand'))),
                 dict(gripReference='underhand',handReference=dict(status='complete'),
                      modes=dict(a=dict(raw='unknown',accepted='unknown')))]
        counts=m.measure(records,'a')['total']
        self.assertEqual(counts['expectedSlots'],2)
        self.assertEqual(counts['completeSourceHands'],1)
        self.assertEqual(counts['acceptedWrongOrInvalid'],1)
        self.assertEqual(counts.get('acceptedCorrect',0),0)
        self.assertEqual(counts['unknown'],1)


if __name__=='__main__':unittest.main()
