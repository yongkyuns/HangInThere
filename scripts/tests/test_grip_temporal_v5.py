"""Behavioral regressions for correlated observations and grip/orientation semantics."""
import copy
import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location('grip_temporal_test', Path(__file__).resolve().parents[1] / 'grip-temporal-v5.py')
temporal = importlib.util.module_from_spec(spec)
spec.loader.exec_module(temporal)


class TemporalGripTests(unittest.TestCase):
    def frame(self, seconds, *, surface=None, contact=.95, view=None, track=1, image=None):
        return dict(seconds=seconds, trackId=track, imageSHA256=image or str(seconds), contextReady=True,
                    view=view or [.95, .025, .025], hands=[dict(side='left', raised=True,
                    surface=surface or [.95, .025, .025], visible=.95, contact=contact, neutralByView=[.1, .1, .1])])

    def person(self, x, raised=True):
        return dict(landmarks=[dict(joint=joint, confidence=.95, position=dict(x=xx, y=y)) for joint, xx, y in
                              [('leftShoulder', x-20, 100), ('rightShoulder', x+20, 100),
                               ('leftWrist', x-30, 30 if raised else 150), ('rightWrist', x+30, 30 if raised else 150)]])

    def test_front_rear_invert_binary_without_treating_missing_face_as_rear(self):
        hand = self.frame(.5)['hands'][0]
        self.assertEqual(temporal.evidence(hand, [.95, .025, .025])['label'], 'overhand')
        self.assertEqual(temporal.evidence(hand, [.025, .95, .025])['label'], 'underhand')
        self.assertIsNone(temporal.evidence(hand, [1/3]*3))

    def test_side_surface_is_not_sufficient_for_neutral_grip(self):
        hand = self.frame(.5, surface=[.025, .025, .95])['hands'][0]
        self.assertIsNone(temporal.evidence(hand, [.95, .025, .025]))
        hand['neutralByView'] = [.95, .95, .95]
        self.assertEqual(temporal.evidence(hand, [1/3]*3)['label'], 'neutral')
        hand['neutralByView'][1] = .2
        self.assertIsNone(temporal.evidence(hand, [1/3]*3))

    def test_duplicate_images_and_two_hands_do_not_create_temporal_support(self):
        frame = self.frame(.5)
        frame['hands'].append(dict(frame['hands'][0], side='right'))
        self.assertEqual(temporal.decide([frame])['label'], 'unknown')
        self.assertEqual(temporal.decide([frame, self.frame(1.5, image='0.5')])['label'], 'unknown')

    def test_nearby_samples_in_same_second_do_not_accumulate(self):
        frames = [self.frame(.1 + .1*i) for i in range(8)]
        self.assertEqual(temporal.decide(frames)['label'], 'unknown')

    def test_same_evidence_does_not_grow_confidence_with_sample_count(self):
        short = temporal.decide([self.frame(.5), self.frame(1.5)])
        long = temporal.decide([self.frame(i+.5) for i in range(8)])
        self.assertEqual(short['label'], 'overhand')
        self.assertAlmostEqual(short['decisionEvents'][-1]['score'], long['decisionEvents'][-1]['score'])

    def test_subject_changes_cannot_join_one_sample_each(self):
        self.assertEqual(temporal.decide([self.frame(.5), self.frame(1.5, track=2)])['label'], 'unknown')

    def test_conflicting_hands_cannot_be_hidden_by_selecting_best_hand(self):
        a = self.frame(.5)
        a['hands'].append(dict(a['hands'][0], side='right', surface=[.025, .95, .025]))
        self.assertEqual(temporal.decide([a, self.frame(1.5), self.frame(2.5)])['label'], 'unknown')

    def test_contact_is_optional_ablation_without_lowering_quality_thresholds(self):
        frames = [self.frame(.5, contact=.1), self.frame(1.5, contact=.1)]
        self.assertEqual(temporal.decide(frames)['label'], 'overhand')
        self.assertEqual(temporal.decide(frames, 'temporal-contact-support')['label'], 'unknown')
        frames[0]['hands'][0]['contact'] = .95
        self.assertEqual(temporal.decide(frames, 'temporal-contact-support')['label'], 'overhand')
        frames[0]['hands'][0]['visible'] = .79
        self.assertEqual(temporal.decide(frames)['label'], 'unknown')

    def test_closed_fist_below_shoulders_is_not_grip_evidence(self):
        frames = [self.frame(.5), self.frame(1.5)]
        for f in frames:
            f['hands'][0]['raised'] = False
        self.assertEqual(temporal.decide(frames)['label'], 'unknown')

    def test_tracker_follows_geometry_when_people_reorder(self):
        tracker = temporal.SubjectTracker()
        athlete, bystander = self.person(100), self.person(400, False)
        tracker.update([athlete, bystander], .1)
        tracked = tracker.update([bystander, self.person(102)], .4)
        self.assertEqual(tracked['status'], 'tracked')
        self.assertEqual(tracked['personIndex'], 1)
        self.assertEqual(tracked['trackId'], 1)

    def test_tracker_rejects_competing_overhead_athletes(self):
        result = temporal.SubjectTracker().update([self.person(100), self.person(400)], .1)
        self.assertIsNone(result['trackId'])

    def test_tracker_resets_after_gap_and_never_uses_person_order_as_identity(self):
        tracker = temporal.SubjectTracker()
        tracker.update([self.person(100)], .1)
        tracker.update([self.person(100)], .4)
        result = tracker.update([self.person(400)], 2.)
        self.assertEqual(result['status'], 'acquiring')
        self.assertEqual(result['trackId'], 2)

    def test_labels_and_face_cues_do_not_enter_decision(self):
        frames = [self.frame(.5), self.frame(1.5)]
        before = temporal.decide(frames)
        changed = copy.deepcopy(frames)
        for f in changed:
            f['annotation'] = dict(label='underhand')
            f['faceCues'] = [1., 0., 0., 0., 0., 1.]
        self.assertEqual(before, temporal.decide(changed))


if __name__ == '__main__':
    unittest.main()
