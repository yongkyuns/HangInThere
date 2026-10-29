import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch
import torch

ROOT=Path(__file__).absolute().parents[2]
def module(name,file):
    spec=importlib.util.spec_from_file_location(name,ROOT/'scripts'/file);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
learning=module('reviewed_grip','train-reviewed-grip.py')
prepare=module('reviewed_crops','prepare-reviewed-grip.py')
reviews=module('patch_reviews','apply-grip-patch-review.py')
selection=module('test_selection','select-reviewed-grip-test.py')


class ReviewedGripTests(unittest.TestCase):
    def test_face_evidence_cannot_reverse_torso_direction(self):
        torch.manual_seed(2)
        body=torch.rand(1000,3).softmax(1)
        missing=torch.zeros(1000,6);missing[:,5]=1
        visible=missing.clone();visible[:,0]=1
        self.assertTrue(torch.equal(learning.supported_view(body,missing),body))
        self.assertTrue(torch.equal(learning.supported_view(body,visible).argmax(1),body.argmax(1)))

    def test_expanded_crop_recovers_contact_away_from_elbow(self):
        old=[45,80,70,105]
        box=prepare.expanded_hand_box(old,[58,180],[58,95],(200,200))
        self.assertLess(box[1],75)
        self.assertTrue(box[0]<=old[0] and box[1]<=old[1] and box[2]>=old[2] and box[3]>=old[3])
        self.assertEqual(box[2]-box[0],box[3]-box[1])

    def test_torso_crop_leaves_head_out(self):
        row=dict(context=dict(status='ready',personIndex=0),pose=dict(people=[dict(landmarks=[
            dict(joint=j,position=dict(x=x,y=y)) for j,x,y in [('leftShoulder',40,80),('rightShoulder',80,80),('nose',60,50)]])]))
        box=prepare.torso_box(row)
        self.assertGreater(box[1],50)
        self.assertGreater(box[3],120)

    def test_grip_loss_does_not_change_orientation_parameters(self):
        torch.set_num_threads(2)
        model=learning.ReviewedGrip(pretrained=False).eval()
        h=torch.rand(1,3,128,128);c=torch.rand_like(h);f=torch.tensor([[1.,.2,.2,0.,0.,1.]])
        logits,_=model.grip_logits(h,c,f)
        torch.nn.functional.cross_entropy(logits,torch.tensor([1])).backward()
        self.assertTrue(any(p.grad is not None for p in model.hand.parameters()))
        self.assertTrue(all(p.grad is None for p in model.torso.parameters()))
        with torch.no_grad():scores,_=model(h,c,torch.zeros_like(f))
        self.assertTrue(torch.equal(scores,torch.full((1,2),.5)))

    def test_test_data_rejected_before_model_construction(self):
        with tempfile.TemporaryDirectory() as d:
            args=SimpleNamespace(output=Path(d),input=Path(d)/'test')
            with patch.object(learning,'load',return_value=[dict(annotation=dict(split='test'))]),patch.object(learning,'ReviewedGrip') as constructor:
                with self.assertRaisesRegex(AssertionError,'Test manifest'):learning.train(args)
                constructor.assert_not_called()

    def test_unverified_reference_does_not_remove_inference_pixels(self):
        row=dict(caseId='c',recordingId='s',imageSHA256='f',pose=dict(timestamp=dict(value=1,timescale=1)),annotation=dict(split='test'),
                 patches=[dict(status='patchReady',reviewId='r',side='left',patchSHA256='p',box=[0,0,20,20])])
        label=dict(reviewId='r',caseId='c',recordingId='s',imageSHA256='f',timestamp=row['pose']['timestamp'],split='test',side='left',patchSHA256='p',box=[0,0,20,20],grip='exclude',view='front')
        out=reviews.apply([row],dict(patches=[label]))[0]
        self.assertEqual(out['patches'][0]['status'],'patchReady')
        self.assertFalse(out['patches'][0]['referenceEligible'])
        label['recordingId']='foreign'
        with self.assertRaises(AssertionError):reviews.apply([row],dict(patches=[label]))

    def test_short_test_clip_never_reuses_tail_for_later_sample_times(self):
        rows=[dict(caseId='clip',recordingId='source',imageSHA256=str(t),pose=dict(timestamp=dict(value=t,timescale=4))) for t in range(1,14)]
        sources=dict(sources=[dict(id='clip',sha256='source',sourceGroup='video:one',grip='underhand',view='front',reason='manual')])
        selected,coverage=selection.select(rows,sources,[2,4,6,8])
        self.assertEqual(len(selected),1)
        self.assertEqual(selected[0]['pose']['timestamp']['value'],8)
        self.assertEqual(sum(c['status']=='beyondSourceEnd' for c in coverage),3)
        self.assertEqual(selected[0]['annotation']['split'],'test')

    def test_per_frame_test_view_does_not_rewrite_recording_reference(self):
        row=dict(caseId='c',recordingId='s',imageSHA256='f',pose=dict(timestamp=dict(value=1,timescale=1)),
                 annotation=dict(split='test',viewpoint='front'),patches=[dict(status='patchReady',reviewId='r',side='left',patchSHA256='p',box=[0,0,20,20])])
        label=dict(reviewId='r',caseId='c',recordingId='s',imageSHA256='f',timestamp=row['pose']['timestamp'],split='test',side='left',patchSHA256='p',box=[0,0,20,20],grip='overhand',view='rear')
        out=reviews.apply([row],dict(patches=[label]))[0]
        self.assertEqual(out['annotation']['viewpoint'],'front')
        self.assertEqual(out['reviewedViewpoint'],'rear')


if __name__=='__main__':unittest.main()
