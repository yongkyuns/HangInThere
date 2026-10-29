import importlib.util
from pathlib import Path
from types import SimpleNamespace
import tempfile
import unittest
from unittest.mock import patch

import torch

ROOT = Path(__file__).absolute().parents[2]
SPEC = importlib.util.spec_from_file_location('grip_components', ROOT / 'scripts/train-grip-components.py')
learning = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(learning)
HEAD_SPEC = importlib.util.spec_from_file_location('head_context', ROOT / 'scripts/prepare-grip-head-context.py')
head_context = importlib.util.module_from_spec(HEAD_SPEC)
HEAD_SPEC.loader.exec_module(head_context)


class ComponentTests(unittest.TestCase):
    def test_head_crop_uses_selected_athlete_without_requiring_a_face(self):
        def person(cx):
            return dict(landmarks=[dict(joint=j, position=dict(x=x, y=100))
                                   for j, x in [('leftShoulder', cx-20), ('rightShoulder', cx+20)]])
        row = dict(pose=dict(people=[person(50), person(150)]), context=dict(personIndex=1))
        box = head_context.head_box(row)
        self.assertEqual((box[0]+box[2])/2, 150)
        self.assertEqual(box[2]-box[0], box[3]-box[1])
        self.assertLess(box[1], 100)

    def test_appearance_target_requires_reviewed_front_or_rear(self):
        for view, grip, expected in [('front', 'overhand', 0), ('front', 'underhand', 1),
                                     ('rear', 'overhand', 1), ('rear', 'underhand', 0),
                                     ('oblique', 'underhand', None), (None, 'overhand', None)]:
            self.assertEqual(learning.appearance_target(dict(viewpoint=view, label=grip)), expected)

    def test_missing_face_adds_no_rear_vote_and_face_can_overcome_wrong_body(self):
        body = torch.tensor([[.01, .98, .01]])
        face = torch.tensor([[.98, .01, .01]])
        missing = torch.tensor([[0., 0., 0., 0., 0., 1.]])
        self.assertTrue(torch.equal(learning.orientation_fusion(body, face, missing), body))
        present = missing.clone()
        present[0, 0] = 1
        self.assertEqual(int(learning.orientation_fusion(body, face, present).argmax(1)), 0)

    def test_appearance_swap_reverses_grip_and_missing_context_abstains(self):
        appearance = torch.tensor([[.99, .01], [.01, .99]])
        orientation = torch.tensor([[.99, .01, 0.], [.99, .01, 0.]])
        score = learning.fuse(appearance, orientation, torch.ones(2))
        self.assertEqual(score.argmax(1).tolist(), [0, 1])
        self.assertTrue(torch.equal(learning.fuse(appearance, orientation, torch.zeros(2)), torch.full((2, 2), .5)))

    def test_component_losses_do_not_train_the_other_task(self):
        torch.set_num_threads(2)
        model = learning.Components(pretrained=False).eval()
        image = torch.rand(1, 3, 128, 128)
        torch.nn.functional.cross_entropy(model.appearance(image), torch.tensor([0])).backward()
        self.assertTrue(any(p.grad is not None for p in model.appearance.parameters()))
        self.assertTrue(all(p.grad is None for p in model.body.parameters()))
        self.assertTrue(all(p.grad is None for p in model.face.parameters()))
        model.zero_grad(set_to_none=True)
        torch.nn.functional.cross_entropy(model.body(image), torch.tensor([0])).backward()
        self.assertTrue(all(p.grad is None for p in model.appearance.parameters()))

    def test_test_manifest_rejected_before_model_creation(self):
        with tempfile.TemporaryDirectory() as d:
            args = SimpleNamespace(output=Path(d), input=Path(d)/'test.jsonl', patch_review=None)
            with patch.object(learning.base, 'load', return_value=[{'annotation': {'split': 'test'}}]), \
                 patch.object(learning, 'Components') as constructor:
                with self.assertRaisesRegex(AssertionError, 'Test data'):
                    learning.train(args)
                constructor.assert_not_called()


if __name__ == '__main__':
    unittest.main()
