import importlib.util, json
from pathlib import Path
import unittest

SCRIPT=Path(__file__).resolve().parents[2]/'scripts'/'vision-contour-benchmark.py'
spec=importlib.util.spec_from_file_location('vision_bar',SCRIPT); m=importlib.util.module_from_spec(spec); spec.loader.exec_module(m)

class VisionContourBenchmarkTests(unittest.TestCase):
    def test_rdp_reduces_straight_chain(self):
        pts=[[0,0],[2,.1],[4,-.1],[6,0]]
        reduced=m.rdp(pts,.25)
        self.assertEqual(len(reduced),2)
    def test_guided_fragment_merge_bridges_internal_occlusion(self):
        region=[0,0,240,70]
        contours=[[[5,20],[90,27]],[[132,31],[180,35]],[[188,36],[235,40]]]
        cs=m.candidates(contours,region)
        self.assertTrue(cs)
        self.assertGreater(cs[0]['length'],220)
    def test_crop_border_is_not_bar(self):
        region=[0,0,120,40]
        contours=[[[0,0],[120,0]],[[0,40],[120,40]]]
        self.assertEqual(m.candidates(contours,region),[])
    def test_first_rank_does_not_use_reference_labels(self):
        # The extraction API takes only contours + region; labels enter only scoring.
        self.assertEqual(m.candidates.__code__.co_argcount,2)
    def test_existing_three_image_dump_recovers_clear_pullup(self):
        p=Path('/mnt/data/hit-bar-recovery/contours/contours.json')
        if not p.exists(): self.skipTest('developer-only retained contour probe absent')
        rows=json.loads(p.read_text()); row=next(x for x in rows if x['id']=='pullup_video_clear')
        cs=m.candidates(row['contours'],row['region']); self.assertTrue(cs)
        refs=[[380,114],[420,108],[465,102]]
        errors=[m.point_segment_distance(p,cs[0]['a'],cs[0]['b']) for p in refs]
        self.assertLess(sum(errors)/len(errors),2.0)

if __name__=='__main__': unittest.main()