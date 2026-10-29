import copy
import importlib.util
from pathlib import Path
import unittest
from collections import Counter

ROOT=Path(__file__).absolute().parents[2]


def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file)
    m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


assembly=module('threeway_review_tests','assemble-threeway-grip.py')
training=module('threeway_train_tests','train-threeway-grip.py')
geometry=module('threeway_crop_tests','prepare-threeway-crops.py')
evaluation=module('threeway_eval_tests','evaluate-threeway-grip.py')


class ThreewayGripTests(unittest.TestCase):
    def row(self,split='train'):
        return dict(caseId='clip',recordingId='video',imageSHA256='pixels',benchmarkFrameId='B1',
            annotation=dict(split=split,start=0,end=10,label='neutral',viewpoint='front'),
            pose=dict(timestamp=dict(value=2,timescale=1)),
            patches=[dict(status='patchReady',reviewId='H1',patchSHA256='hand',side='left',box=[0,0,24,24])])

    def notes(self,surface='palm',contact='closed'):
        return dict(patches={'H1':dict(surface=surface,contact=contact)})

    def test_neutral_does_not_derive_a_side_surface_label(self):
        frames,patches=assembly.bind([self.row()],self.notes(),self.notes())
        self.assertEqual(patches[0]['grip'],'neutral')
        self.assertEqual(patches[0]['surface'],'palm')

    def test_surface_conflict_cannot_silently_enter_training(self):
        with self.assertRaisesRegex(AssertionError,'adjudicated'):
            assembly.bind([self.row()],self.notes(),self.notes('dorsum'))

    def test_missing_second_review_is_rejected(self):
        with self.assertRaisesRegex(AssertionError,'two explicit'):
            assembly.bind([self.row()],self.notes(),dict(patches={}))

    def test_manual_localization_cannot_enter_validation(self):
        row=self.row('validation');row['patches'][0]['origin']='manualReviewedTrainingCrop'
        with self.assertRaisesRegex(AssertionError,'held-out'):
            assembly.bind([row],self.notes(),self.notes())

    def test_binary_control_keeps_neutral_appearance_but_masks_grip(self):
        ref=dict(grip='neutral',surface='side',contact='closed',qualityEligible=True)
        items=[({},dict(threewayReview=ref))]
        binary=training.targets(items,2);ternary=training.targets(items,3)
        self.assertEqual(binary[0].tolist(),[-100]);self.assertEqual(ternary[0].tolist(),[2])
        self.assertEqual(binary[1].tolist(),ternary[1].tolist())

    def test_local_contact_does_not_learn_target_identity(self):
        ref=dict(grip='exclude',surface='dorsum',contact='invalid',qualityEligible=False)
        result=training.targets([({},dict(threewayReview=ref))],3)
        self.assertEqual(result[2].tolist(),[-100])

    def test_fallback_does_not_fabricate_a_missing_wrist(self):
        person=dict(landmarks=[dict(joint='leftElbow',confidence=.9,position=dict(x=30,y=60))])
        self.assertIsNone(geometry.geometry(person,'left',(100,100)))

    def test_competing_people_do_not_get_an_arbitrary_fallback(self):
        person=dict(landmarks=[dict(joint='leftWrist',confidence=.9,position=dict(x=20,y=20)),
                               dict(joint='leftShoulder',confidence=.9,position=dict(x=20,y=70))])
        row=dict(pose=dict(people=[person,copy.deepcopy(person)]),patchPlan={})
        self.assertIsNone(geometry.choose_person(row))

    def test_binary_head_cannot_get_credit_for_neutral_reference(self):
        result=Counter();evaluation.metrics(result,[.95,.05,0.],[.01,.99],'neutral')
        self.assertEqual(result['acceptedWrong'],1);self.assertEqual(result['rawCorrect'],0)

    def test_wrong_target_is_not_correct_when_grip_name_happens_to_match(self):
        result=Counter();evaluation.metrics(result,[.95,.03,.02],[.01,.99],'overhand',valid=False)
        self.assertEqual(result['acceptedWrong'],1);self.assertEqual(result['invalidTargetCrop'],1)

    def test_contact_rejection_keeps_raw_choice_separate_from_accepted(self):
        result=Counter();evaluation.metrics(result,[.95,.03,.02],[.7,.3],'overhand')
        self.assertEqual(result['rawCorrect'],1);self.assertEqual(result['acceptedCorrect'],0)
        self.assertEqual(result['unknown'],1)

    def test_absent_orientation_is_not_argmax_credit_for_first_class(self):
        result=Counter();evaluation.metrics(result,[.95,.03,.02],[.01,.99],'overhand',orientation=False)
        self.assertEqual(result['unknown'],1);self.assertEqual(result['rawCorrect'],0)


if __name__=='__main__':unittest.main()
