"""Synthetic failure-path checks for an evaluation probe, not video accuracy."""
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from pushup_feasibility import PushUpProbe, measure


def sample(elbow=156, shoulder=100, knee=170, body=True):
    m = dict(elbow=elbow, shoulderY=shoulder, arm=200, wristX=80, wristY=280, knee=knee)
    if body:
        m.update(body=170, axis=15)
    return m


class ProbeTests(unittest.TestCase):
    def setUp(self):
        self.probe, self.time = PushUpProbe(), 0

    def feed(self, left, frames=10, right=None):
        for _ in range(frames):
            self.probe.step(dict(t=self.time, left=left, right=right or {}))
            self.time += .05

    def test_setup_partial_motion_and_kneeling_do_not_count(self):
        self.feed(sample(knee=90), 30)
        self.assertEqual(self.probe.state, 'waiting')
        self.feed(sample(), 20)
        self.feed(sample(elbow=125, shoulder=130))
        self.feed(sample(), 20)
        self.assertEqual(self.probe.events, [])
        self.feed(sample(elbow=80, shoulder=180))
        self.feed(sample(elbow=145), 20)  # Observed return near the calibrated top.
        self.assertEqual(len(self.probe.events), 1)
        self.feed(sample(knee=90), 20)
        self.assertEqual(self.probe.state, 'waiting')
        self.assertEqual(len(self.probe.events), 1)

    def test_missing_near_arm_cannot_borrow_a_far_arm_return(self):
        self.feed(sample(), 20)
        self.feed(sample(elbow=80, shoulder=180))
        self.feed({}, 10, right=sample())
        self.feed(sample(), 20)
        self.assertEqual(self.probe.events, [])

    def test_brief_body_gap_preserves_phase_but_cannot_supply_an_endpoint(self):
        self.feed(sample(), 20)
        self.feed(sample(elbow=80, shoulder=180))
        self.feed(sample(body=False), 5)
        self.assertEqual(self.probe.events, [])
        self.assertEqual(self.probe.state, 'lowered')
        self.feed(sample())
        self.assertEqual(len(self.probe.events), 1)

    def test_prolonged_body_loss_and_source_gap_cancel_pending_rep(self):
        for source_gap in (False, True):
            with self.subTest(source_gap=source_gap):
                self.setUp()
                self.feed(sample(), 20)
                self.feed(sample(elbow=80, shoulder=180))
                if source_gap:
                    self.time += 2
                else:
                    self.feed(sample(body=False), 25)
                self.feed(sample(), 20)
                self.assertEqual(self.probe.events, [])

    def test_pose_measurement_does_not_accept_offscreen_or_duplicate_joints(self):
        points = [dict(joint='left' + joint, position=dict(x=x, y=y), confidence=.9)
                  for joint, x, y in [('Shoulder', 100, 100), ('Elbow', 100, 200), ('Wrist', 200, 200)]]
        pose = dict(imageSize=dict(width=640, height=480), people=[dict(landmarks=points)])
        self.assertAlmostEqual(measure(pose, 'left')['elbow'], 90)
        points.append(points[0].copy())
        self.assertNotIn('elbow', measure(pose, 'left'))
        points.pop()
        points[-1]['position']['x'] = 641
        self.assertNotIn('elbow', measure(pose, 'left'))
        points[-1]['position']['x'] = 200
        pose['people'].append(pose['people'][0])
        self.assertEqual(measure(pose, 'left'), {})


if __name__ == '__main__':
    unittest.main()
