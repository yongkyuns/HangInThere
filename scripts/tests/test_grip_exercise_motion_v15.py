import copy
import importlib.util
from pathlib import Path
import unittest


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1]/filename)
    m = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(m)
    return m


motion = module('motion_test', 'grip-exercise-motion-v15.py')
temporal = module('temporal_test', 'grip-temporal-v5.py')


def person(rise=0, bent=False, wrist_shift=0):
    points = []
    for side, x in [('left', 100), ('right', 200)]:
        # Extended: shoulder-elbow-wrist aligned. Bent: torso rises under
        # fixed wrists and elbows move out, with coherent hip displacement.
        for joint, xy in [('Shoulder', (x, 200-rise)), ('Hip', (x, 300-rise)),
                          ('Elbow', (x+(-40 if side == 'left' else 40) if bent else x, 110-rise*.3)),
                          ('Wrist', (x+wrist_shift, 20))]:
            points.append(dict(joint=side+joint, confidence=.95, position=dict(zip(['x', 'y'], xy))))
    return dict(landmarks=points)


class ExerciseMotionTests(unittest.TestCase):
    def engage(self, gate):
        for t in [0., .25, .5]:
            self.assertFalse(gate.update(t, 1, person())['eligible'])
        gate.update(.75, 1, person(45, True))
        return gate.update(1., 1, person(45, True))

    def test_overhead_setup_hold_is_insufficient(self):
        g = motion.ExerciseGate()
        for i in range(30):
            self.assertFalse(g.update(i*.25, 1, person())['eligible'])

    def test_coherent_rise_and_flexion_activate_causally(self):
        self.assertTrue(self.engage(motion.ExerciseGate())['eligible'])

    def test_wrist_reach_does_not_activate(self):
        g = motion.ExerciseGate()
        for i in range(10):
            self.assertFalse(g.update(i*.25, 1, person(45 if i > 2 else 0, i > 2, i*15))['eligible'])

    def test_single_extended_frame_is_not_a_held_baseline(self):
        g = motion.ExerciseGate()
        g.update(0., 1, person())
        for i in range(1, 10):
            self.assertFalse(g.update(i*.25, 1, person(45, True))['eligible'])

    def test_arm_bending_without_torso_rise_is_insufficient(self):
        g = motion.ExerciseGate()
        for i in range(12):
            self.assertFalse(g.update(i*.25, 1, person(0, i > 2))['eligible'])

    def test_release_gap_track_change_and_missing_body_revoke_immediately(self):
        for seconds, track, body in [(1.25, 1, person(wrist_shift=80)), (2., 1, person()),
                                     (1.25, 2, person()), (1.25, 1, None)]:
            g = motion.ExerciseGate()
            self.engage(g)
            self.assertFalse(g.update(seconds, track, body)['eligible'])

    def test_regressed_clock_cannot_reacquire_an_old_timeline(self):
        g = motion.ExerciseGate()
        self.engage(g)
        for t in [.1, .2, .3, .4]:
            self.assertEqual(g.update(t, 1, person())['reason'], 'invalidSourceTime')
        self.assertFalse(g.update(1.25, 1, person())['eligible'])

    def test_missing_or_weak_hips_cannot_establish_coherent_body_rise(self):
        p = person()
        p['landmarks'] = [x for x in p['landmarks'] if 'Hip' not in x['joint']]
        self.assertIsNone(motion.measure(p))

    def frame(self, t, active=True, epoch=1, contact=.01):
        return dict(seconds=t, trackId=1, exercise=dict(eligible=active, epoch=epoch),
                    imageSHA256=str(t), contextReady=True, view=[.95, .025, .025],
                    hands=[dict(side='left', raised=True, surface=[.95, .025, .025], visible=.95,
                                contact=contact, neutralByView=[.01]*3)])

    def test_contact_is_not_an_input_to_new_decision(self):
        frames = [self.frame(1.), self.frame(2.)]
        a = motion.decide(frames, temporal, 'motion-no-contact')
        self.assertEqual(a['label'], 'overhand')
        for f in frames:
            f['hands'][0]['contact'] = .99
        self.assertEqual(a, motion.decide(frames, temporal, 'motion-no-contact'))

    def test_setup_cannot_bank_hand_evidence_or_leak_label_into_release(self):
        frames = [self.frame(0., False), self.frame(1., False), self.frame(2.),
                  self.frame(3.), self.frame(4., False, 2)]
        result = motion.decide(frames, temporal, 'motion-no-contact')
        self.assertEqual([d['seconds'] for d in result['decisions']], [3.])
        self.assertEqual(result['states'][-1]['label'], 'unknown')

    def test_readability_confidence_and_front_rear_requirements_remain(self):
        for kind in ['visible', 'surface', 'view']:
            frames = [self.frame(1.), self.frame(2.)]
            for f in frames:
                if kind == 'visible': f['hands'][0]['visible'] = .79
                elif kind == 'surface': f['hands'][0]['surface'] = [.79, .1, .11]
                else: f['view'] = [.1, .1, .8]
            self.assertEqual(motion.decide(frames, temporal, 'motion-no-contact')['label'], 'unknown')

    def test_duplicate_pixels_do_not_earn_a_second_vote(self):
        frames = [self.frame(1.), self.frame(2.)]
        frames[1]['imageSHA256'] = frames[0]['imageSHA256']
        self.assertEqual(motion.decide(frames, temporal, 'motion-no-contact')['label'], 'unknown')

    def test_labels_are_not_motion_inputs(self):
        a, b = motion.ExerciseGate(), motion.ExerciseGate()
        for i in range(8):
            p = person(45 if i > 2 else 0, i > 2)
            q = copy.deepcopy(p)
            q['reference'] = dict(grip='underhand', lifecycle='exercise')
            self.assertEqual(a.update(i*.25, 1, p), b.update(i*.25, 1, q))


if __name__ == '__main__':
    unittest.main()
