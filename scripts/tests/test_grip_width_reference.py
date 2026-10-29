import importlib.util
import math
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('width_reference',Path(__file__).resolve().parents[1]/'grip-width-reference.py')
reference=importlib.util.module_from_spec(spec);spec.loader.exec_module(reference)


class WidthReferenceTests(unittest.TestCase):
    def points(self,span):
        return dict(leftShoulder=[100,200,100,200],rightShoulder=[200,200,200,200],
            leftWrist=[150-span/2,100,150-span/2,100],rightWrist=[150+span/2,100,150+span/2,100])

    def test_known_geometry_uses_frozen_bands(self):
        for span,expected in [(60,'narrow'),(100,'shoulderWidth'),(160,'wide')]:
            bounds=reference.ratio_interval(self.points(span),[[0,100],[300,100]],[300,300])
            self.assertEqual(bounds,(span/100,span/100));self.assertEqual(reference.band(bounds),expected)

    def test_uncertainty_cannot_be_hidden_by_center_points(self):
        boxes=self.points(85);boxes['leftWrist']=[105,98,110,102];boxes['rightWrist']=[190,98,195,102]
        interval=reference.ratio_interval(boxes,[[0,100],[300,100]],[300,300])
        self.assertEqual(interval,(.8,.9));self.assertEqual(reference.band(interval),'uncertain')

    def test_shoulder_uncertainty_propagates_into_denominator(self):
        boxes=self.points(100);boxes['leftShoulder']=[90,200,110,200];boxes['rightShoulder']=[190,200,210,200]
        low,high=reference.ratio_interval(boxes,[[0,100],[300,100]],[300,300])
        self.assertAlmostEqual(low,100/120);self.assertAlmostEqual(high,100/80)
        self.assertEqual(reference.band((low,high)),'uncertain')

    def test_vertical_support_projects_wrist_distance(self):
        boxes=self.points(100)
        self.assertEqual(reference.ratio_interval(boxes,[[50,0],[50,300]],[300,300]),(0,0))
        self.assertEqual(reference.band((0,0)),'uncertain')

    def test_source_scaling_preserves_ratio(self):
        points=self.points(100);scaled={k:[v*2 for v in b] for k,b in points.items()}
        self.assertEqual(reference.ratio_interval(points,[[0,100],[300,100]],[300,300]),
            reference.ratio_interval(scaled,[[0,200],[600,200]],[600,600]))

    def test_invalid_or_unbounded_regions_are_rejected(self):
        boxes=self.points(100);boxes['rightShoulder']=boxes['leftShoulder']
        with self.assertRaises(ValueError):reference.ratio_interval(boxes,[[0,100],[300,100]],[300,300])
        with self.assertRaises(ValueError):reference.ratio_interval(self.points(100),[[0,100],[0,100]],[300,300])
        boxes=self.points(100);boxes['leftWrist']=[-1,0,1,2]
        with self.assertRaises(ValueError):reference.ratio_interval(boxes,[[0,100],[300,100]],[300,300])
        self.assertEqual(reference.band((.9,math.inf)),'uncertain')

if __name__=='__main__':unittest.main()
