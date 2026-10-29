"""Independent surface semantics must not become grip/orientation labels."""
import importlib.util
from pathlib import Path
import unittest

try:
    import torch
    HAVE_TORCH = True
except ImportError:
    HAVE_TORCH = False


@unittest.skipUnless(HAVE_TORCH, 'Research training environment required')
class SurfaceSemanticsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        spec = importlib.util.spec_from_file_location('surface_v2_tests', Path(__file__).resolve().parents[1]/'train-grip-surface-v2.py')
        cls.m = importlib.util.module_from_spec(spec); spec.loader.exec_module(cls.m)
        spec = importlib.util.spec_from_file_location('fusion_v2_tests', Path(__file__).resolve().parents[1]/'train-grip-fusion-v2.py')
        cls.fusion = importlib.util.module_from_spec(spec); spec.loader.exec_module(cls.fusion)

    def items(self, refs):
        return [(dict(), dict(threewayReview=ref)) for ref in refs]

    def test_unclear_supervises_visibility_without_anatomical_surface(self):
        s, v, c = self.m.targets(self.items([dict(surface='unclear', contact='closed')]))
        self.assertEqual(s.tolist(), [-100])
        self.assertEqual(v.tolist(), [0])
        self.assertEqual(c.tolist(), [1])

    def test_open_palm_remains_visible_and_teaches_noncontact(self):
        s, v, c = self.m.targets(self.items([dict(surface='palm', contact='open')]))
        self.assertEqual(s.tolist(), [0])
        self.assertEqual(v.tolist(), [1])
        self.assertEqual(c.tolist(), [0])

    def test_identity_ambiguity_does_not_become_a_local_contact_negative(self):
        s, v, c = self.m.targets(self.items([dict(surface='dorsum', contact='closed', qualityEligible=False)]))
        self.assertEqual(s.tolist(), [1])
        self.assertEqual(v.tolist(), [1])
        self.assertEqual(c.tolist(), [-100])

    def test_grip_and_view_changes_cannot_change_surface_targets(self):
        refs = [dict(surface='side', contact='closed', grip=g, view=view)
                for g in ['overhand', 'underhand', 'neutral'] for view in ['front', 'rear', 'oblique']]
        s, v, c = self.m.targets(self.items(refs))
        self.assertEqual(s.tolist(), [2]*9)
        self.assertEqual(v.tolist(), [1]*9)

    def test_view_cannot_change_neutral_probability(self):
        views = torch.eye(3)
        binary = torch.tensor([[.2, -.1]]).repeat(3, 1)
        neutral = torch.tensor([[-1., 2.]]).repeat(3, 1)
        surfaces = torch.tensor([[.8, .1, .1]]).repeat(3, 1)
        result = self.fusion.combine(binary, neutral, surfaces, views, 2.)
        self.assertTrue(torch.equal(result[:, 2], result[0, 2].repeat(3)))
        self.assertTrue(torch.allclose(result.sum(1), torch.ones(3)))

    def test_orientation_alone_cannot_create_direction_evidence(self):
        views = torch.eye(3)
        result = self.fusion.combine(torch.zeros(3, 2), torch.zeros(3, 2), torch.full((3, 3), 1/3), views, 2.)
        self.assertTrue(torch.equal(result, result[0].repeat(3, 1)))

    def test_palm_direction_respects_front_rear_geometry(self):
        result = self.fusion.combine(torch.zeros(2, 2), torch.tensor([[0., -4.]]).repeat(2, 1),
                                    torch.tensor([[1., 0., 0.]]).repeat(2, 1), torch.eye(3)[:2], 2.)
        self.assertGreater(result[0, 0], result[0, 1])
        self.assertLess(result[1, 0], result[1, 1])


if __name__ == '__main__': unittest.main()
