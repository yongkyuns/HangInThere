import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

ROOT=Path(__file__).absolute().parents[2]
def module(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m
prepare=module('context_prepare',ROOT/'scripts/prepare-grip-context.py')
learning=module('context_learning',ROOT/'scripts/train-grip-context.py')
correction=module('reference_correction',ROOT/'scripts/apply-grip-reference-corrections.py')

class ContextTests(unittest.TestCase):
    def row(self):
        points=[dict(joint=j,confidence=c,position=dict(x=x,y=y)) for j,c,x,y in [
            ('leftShoulder',.8,30,50),('rightShoulder',.8,70,50),('nose',.9,50,30),('leftEye',.7,45,25)]]
        return dict(recordingId='a',imageSHA256='b',annotation=dict(recordingId='a',split='train',label='overhand',start=0,end=1),
            pose=dict(imageSize=dict(width=100,height=100),people=[dict(landmarks=points)],timestamp=dict(value=0,timescale=1)),
            patchPlan=dict(personIndex=0,sourceId='a',timestamp=dict(value=0,timescale=1)),
            patches=[dict(side=side,status='missing') for side in ['left','right']])
    def test_no_face_is_missing_evidence_not_rear_label(self):
        r=self.row();r['pose']['people'][0]['landmarks']=r['pose']['people'][0]['landmarks'][:2]
        box,cues=prepare.context_geometry(r)
        self.assertEqual(cues,[0,0,0,0,0,1]);self.assertNotIn('viewpoint',r['annotation'])
        self.assertEqual(box[2]-box[0],box[3]-box[1])
    def test_only_selected_athlete_supplies_face(self):
        r=self.row();r['pose']['people'].append(copy.deepcopy(r['pose']['people'][0]))
        r['pose']['people'][0]['landmarks']=r['pose']['people'][0]['landmarks'][:2]
        self.assertEqual(prepare.context_geometry(r)[1][:5],[0]*5)
        r['patchPlan']['personIndex']=1;self.assertGreater(prepare.context_geometry(r)[1][0],.8)
        r['patchPlan']['personIndex']=None;self.assertIsNone(prepare.context_geometry(r))
    def test_context_pixels_clock_and_owner_bound(self):
        r=self.row()
        with tempfile.TemporaryDirectory() as d:
            p=Path(d)/'crop.png';p.write_bytes(b'original')
            r['context']=dict(status='ready',faceCues=[0]*5+[1],sourceId='a',timestamp=r['pose']['timestamp'],
                imageSHA256='b',personIndex=0,image=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest())
            manifest=Path(d)/'rows.jsonl'
            def load():
                manifest.write_text(json.dumps(r)+'\n');return learning.load(manifest)
            load()
            p.write_bytes(b'changed')
            with self.assertRaises(AssertionError):load()
            p.write_bytes(b'original');r['context']['sourceId']='foreign'
            with self.assertRaises(AssertionError):load()
    def test_context_is_an_actual_inference_input_and_features_train(self):
        import torch
        model=learning.ContextClassifier(context=True,pretrained=False).eval()
        self.assertTrue(all(p.requires_grad for p in model.features.parameters()))
        hand=torch.zeros(1,3,128,128);context=torch.ones_like(hand);cues=torch.tensor([[.9,.8,.8,.2,.2,1.]])
        # The fusion head has context and predicted orientation as real inputs.
        with torch.inference_mode():
            a=model(hand,context,cues)[0];b=model(hand,hand,torch.zeros_like(cues))[0]
        self.assertFalse(torch.equal(a,b))
    def test_verified_face_cannot_be_reassigned_to_another_source(self):
        r=self.row();r['context']=dict(status='unavailable',faceCues=[0]*6,
            faceDetector=dict(sourceId='foreign',timestamp=r['pose']['timestamp'],imageSHA256='b'))
        with tempfile.TemporaryDirectory() as d:
            manifest=Path(d)/'rows.jsonl';manifest.write_text(json.dumps(r)+'\n')
            with self.assertRaises(AssertionError):learning.load(manifest)
    def test_identical_hand_appearance_changes_grip_with_view_and_abstains_obliquely(self):
        import torch
        from types import SimpleNamespace
        fake=SimpleNamespace(embed=lambda x:torch.zeros(1,576),head=lambda x:torch.tensor([[.9,.1]]).log())
        hand=torch.zeros(1,3,128,128);cues=torch.tensor([[0.,0.,0.,0.,0.,1.]])
        scores=[]
        for view in [[1.,0.,0.],[0.,1.,0.],[0.,0.,1.]]:
            fake.orientation=lambda x:torch.tensor([view]).log()
            logit,_=learning.OrientedClassifier.logits(fake,hand,hand,cues)
            scores.append(logit.softmax(1)[0])
        self.assertGreater(scores[0][0],.8)
        self.assertGreater(scores[1][1],.8)
        self.assertAlmostEqual(float(scores[2][0]),.5)
    def test_reference_correction_preserves_pixels_and_rejects_training(self):
        r=self.row();r['annotation'].update(split='test',label='underhand',viewpoint='rear')
        e=dict(recordingId='a',previousLabel='underhand',previousViewpoint='rear',label='overhand',viewpoint='front')
        result=correction.apply([r],[e])[0]
        self.assertEqual(result['patches'],r['patches']);self.assertEqual(result['pose'],r['pose'])
        self.assertEqual(result['annotation']['label'],'overhand')
        r['annotation']['split']='train'
        with self.assertRaises(AssertionError):correction.apply([r],[e])

if __name__=='__main__':unittest.main()
