import importlib.util
import math
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("mediapipe_eval", ROOT / "scripts/mediapipe_eval.py")
mod = importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

class Point:
    def __init__(self, x, y, visibility=1.0, presence=1.0):
        self.x, self.y = x, y
        self.visibility, self.presence = visibility, presence

class MediaPipeMappingTests(unittest.TestCase):
    def test_common_arm_points_map_to_pixels(self):
        points = [Point(0, 0) for _ in range(33)]
        points[11] = Point(.25, .50, .9, .8)
        points[13] = Point(.50, .25, .7, .95)
        points[15] = Point(.75, .10, .6, .4)
        person = mod.person_from_landmarks(points, 800, 400)
        by_joint = {x["joint"]: x for x in person["landmarks"]}
        self.assertEqual(by_joint["leftShoulder"]["position"], {"x": 200.0, "y": 200.0})
        self.assertEqual(by_joint["leftElbow"]["position"], {"x": 400.0, "y": 100.0})
        self.assertEqual(by_joint["leftWrist"]["position"], {"x": 600.0, "y": 40.0})
        self.assertAlmostEqual(by_joint["leftShoulder"]["confidence"], .8)
        self.assertAlmostEqual(by_joint["leftWrist"]["confidence"], .4)

    def test_confidence_is_conservative_and_finite(self):
        self.assertEqual(mod.usable_confidence(Point(0, 0, .9, .2)), .2)
        self.assertEqual(mod.usable_confidence(Point(0, 0, -1, 2)), 0.0)
        p = Point(0, 0); p.visibility = math.nan; p.presence = .7
        self.assertEqual(mod.usable_confidence(p), .7)

    def test_outside_points_are_not_clamped(self):
        points = [Point(0, 0) for _ in range(33)]
        points[15] = Point(1.2, -.1, .8, .8)
        person = mod.person_from_landmarks(points, 100, 200)
        wrist = next(x for x in person["landmarks"] if x["joint"] == "leftWrist")
        self.assertEqual(wrist["position"], {"x": 120.0, "y": -20.0})

    def test_mapping_does_not_invent_neck_or_root(self):
        points = [Point(.5, .5) for _ in range(33)]
        joints = {x["joint"] for x in mod.person_from_landmarks(points, 100, 100)["landmarks"]}
        self.assertNotIn("neck", joints)
        self.assertNotIn("root", joints)

if __name__ == "__main__":
    unittest.main()