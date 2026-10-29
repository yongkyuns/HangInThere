import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('cohort_v8',Path(__file__).parents[1]/'audit-grip-cohort-v8.py')
m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)


def row(recording,group,split='train',surface='dorsum',contact='closed'):
    return dict(recordingId=recording,annotation=dict(split=split,sourceGroup=group),
                patches=[dict(status='patchReady',threewayReview=dict(grip='underhand',view='front',surface=surface,contact=contact))])


class CohortInventoryTests(unittest.TestCase):
    def test_multiple_angles_and_frames_do_not_create_independent_groups(self):
        cell=m.audit([row('a','athlete1'),row('a','athlete1'),row('b','athlete1')])[0]
        self.assertEqual(cell['readableClosedPatches'],3)
        self.assertEqual(cell['readableSourceGroups'],1)

    def test_unclear_and_open_hands_do_not_fill_readable_coverage(self):
        cell=m.audit([row('a','one',surface='unclear'),row('b','two',contact='open')])[0]
        self.assertEqual(cell['availablePatches'],2)
        self.assertEqual(cell['readableSourceGroups'],0)

    def test_source_group_cannot_cross_training_and_validation(self):
        with self.assertRaisesRegex(AssertionError,'crosses fitting roles'):
            m.audit([row('a','same'),row('b','same',split='validation')])


if __name__=='__main__':unittest.main()
