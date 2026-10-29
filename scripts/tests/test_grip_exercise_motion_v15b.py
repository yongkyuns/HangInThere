import importlib.util
from pathlib import Path
import unittest


def module(name, path):
    s = importlib.util.spec_from_file_location(name, path)
    m = importlib.util.module_from_spec(s)
    s.loader.exec_module(m)
    return m


ROOT = Path(__file__).resolve().parents[1]
base = module('base_tests', Path(__file__).with_name('test_grip_exercise_motion_v15.py'))
motion = module('motion_b', ROOT/'grip-exercise-motion-v15b.py')


class PerArmTests(unittest.TestCase):
    def engage(self, gate, missing=None):
        result = None
        for i in range(5):
            p = base.person(45 if i >= 3 else 0, i >= 3)
            if missing:
                p['landmarks'] = [x for x in p['landmarks'] if not missing(x['joint'])]
            result = gate.update(i*.25, 1, p)
        return result

    def test_one_arm_and_no_hips_can_establish_observed_exercise(self):
        r = self.engage(motion.ExerciseGate(), lambda j: j.startswith('right') or 'Hip' in j)
        self.assertEqual(r['eligibleSides'], ['left'])

    def test_reaching_and_static_overhead_hold_do_not_activate(self):
        for reach in [False, True]:
            g = motion.ExerciseGate()
            for i in range(12):
                r = g.update(i*.25, 1, base.person(wrist_shift=i*15 if reach else 0))
                self.assertFalse(r['eligible'])

    def test_losing_one_arm_releases_its_permission_immediately(self):
        g = motion.ExerciseGate()
        self.assertTrue(self.engage(g)['eligible'])
        p = base.person(45, True)
        p['landmarks'] = [x for x in p['landmarks'] if not x['joint'].startswith('left')]
        self.assertEqual(g.update(1.25, 1, p)['eligibleSides'], ['right'])
        self.assertFalse(g.update(1.5, 1, None)['eligible'])

    def test_source_gap_or_target_change_requires_new_baseline(self):
        for t, track in [(2., 1), (1.25, 2)]:
            g = motion.ExerciseGate()
            self.engage(g)
            self.assertFalse(g.update(t, track, base.person(45, True))['eligible'])

    def test_other_arm_cannot_admit_a_reaching_hand(self):
        helper = base.ExerciseMotionTests()
        frames = [helper.frame(1.), helper.frame(2.)]
        for f in frames:
            f['exercise'].update(eligibleSides=['right'], sideEpochs=dict(left=0, right=0))
        self.assertEqual(motion.decide(frames, base.temporal, 'motion-no-contact')['label'], 'unknown')

    def test_release_and_reacquire_cannot_join_old_support(self):
        helper = base.ExerciseMotionTests()
        frames = [helper.frame(1.), helper.frame(2.)]
        for i, f in enumerate(frames):
            f['exercise'].update(eligibleSides=['left'], sideEpochs=dict(left=i, right=0))
        self.assertEqual(motion.decide(frames, base.temporal, 'motion-no-contact')['label'], 'unknown')

    def test_absent_other_arm_does_not_purge_continuous_observed_arm(self):
        helper = base.ExerciseMotionTests()
        frames = [helper.frame(1.), helper.frame(2.)]
        for i, f in enumerate(frames):
            f['exercise'].update(eligibleSides=['left'], sideEpochs=dict(left=0, right=i))
        self.assertEqual(motion.decide(frames, base.temporal, 'motion-no-contact')['label'], 'overhand')


if __name__ == '__main__':
    unittest.main()
