import importlib.util
from pathlib import Path
import unittest

import cv2
import numpy as np

SCRIPT = Path(__file__).resolve().parents[2] / 'scripts' / 'bar-method-benchmark.py'
spec = importlib.util.spec_from_file_location('bar_benchmark', SCRIPT)
bench = importlib.util.module_from_spec(spec); spec.loader.exec_module(bench)

class BarMethodBenchmarkTests(unittest.TestCase):
    def test_blank_region_has_no_lsd_pair(self):
        image=np.full((120,240,3),127,np.uint8)
        segs=bench.lsd_segments(image,[20,20,220,100])
        self.assertEqual(bench.pair_lines(bench.merge_segments(segs),[20,20,220,100]),[])

    def test_lsd_recovers_two_edge_bar_without_pose(self):
        image=np.full((120,240,3),230,np.uint8)
        cv2.rectangle(image,(30,48),(210,62),(20,20,20),-1)
        region=[20,30,220,80]
        pairs=bench.pair_lines(bench.merge_segments(bench.lsd_segments(image,region)),region)
        self.assertTrue(pairs)
        edge=bench.upper_edge(pairs[0],region)
        self.assertLess(bench.point_segment_distance([100,48],edge['a'],edge['b']),2.5)

    def test_hough_recovers_oblique_bar(self):
        image=np.full((160,240,3),225,np.uint8)
        cv2.line(image,(30,55),(210,80),(25,25,25),14)
        region=[20,30,220,105]
        pairs=bench.pair_lines(bench.merge_segments(bench.hough_segments(image,region)),region)
        self.assertTrue(pairs)

    def test_finite_segment_distance_penalizes_missing_span(self):
        a=np.array([10.,10.]); b=np.array([20.,10.])
        self.assertAlmostEqual(bench.point_segment_distance([15,12],a,b),2.0)
        self.assertGreater(bench.point_segment_distance([40,10],a,b),19.9)

    def test_merging_preserves_image_coordinates(self):
        merged=bench.merge_segments([[100,20,150,25],[151,25.1,190,29]])
        self.assertEqual(len(merged),1)
        pts=np.vstack([merged[0]['a'],merged[0]['b']])
        self.assertGreater(pts[:,0].min(),90)
        self.assertLess(pts[:,0].max(),200)

if __name__=='__main__': unittest.main()
