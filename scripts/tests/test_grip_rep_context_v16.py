import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('rep_context', Path(__file__).resolve().parents[1]/'grip-rep-context-v16.py')
policy = importlib.util.module_from_spec(spec)
spec.loader.exec_module(policy)


class RepContextTests(unittest.TestCase):
    def frame(self, t, active=True, phase='outbound', epoch=1):
        return dict(seconds=t, trackId=1, sourceEpoch=1, phase=phase, released=False,
                    imageSHA256=str(t), supportBySide=dict(left=True, right=True),
                    exercise=dict(eligible=active, epoch=epoch, startedAt=0. if active else None, reason='active' if active else 'setup'),
                    bodyView=dict(supported=True, view='front'))

    def rgb(self, f):
        return dict(seconds=f['seconds'], contextReady=True, view=[.95, .025, .025],
                    hands=[dict(side='left', surface=[.95, .025, .025], visible=.95, neutralByView=[.01]*3)])

    def test_setup_cannot_supply_support_and_release_revokes_retained_label(self):
        fs = [self.frame(0., False), self.frame(1.), self.frame(2.), self.frame(2.25, phase='ready'), self.frame(2.5, False)]
        rgb = {f['imageSHA256']: self.rgb(f) for f in fs[:3]}
        r = policy.decide(fs, rgb)
        self.assertEqual([s['label'] for s in r['states']], ['unknown', 'unknown', 'overhand', 'overhand', 'unknown'])
        self.assertFalse(r['states'][3]['observed'])

    def test_no_contact_or_extra_wrist_height_input_is_required(self):
        fs = [self.frame(0.), self.frame(1.)]
        rgb = {f['imageSHA256']: self.rgb(f) for f in fs}
        a = policy.decide(fs, rgb)
        self.assertEqual(a['decisions'][0]['label'], 'overhand')
        for r in rgb.values():
            r['hands'][0].update(contact=0., raised=False)
        self.assertEqual(a, policy.decide(fs, rgb))

    def test_low_readability_and_low_surface_confidence_still_abstain(self):
        for kind in ['visible', 'surface']:
            fs = [self.frame(0.), self.frame(1.)]
            rgb = {f['imageSHA256']: self.rgb(f) for f in fs}
            for r in rgb.values():
                r['hands'][0][kind] = .79 if kind == 'visible' else [.79, .1, .11]
            self.assertFalse(policy.decide(fs, rgb)['decisions'])

    def test_track_change_and_regrasp_cannot_join_one_vote_each(self):
        for field in ['sourceEpoch', 'exerciseEpoch']:
            fs = [self.frame(0.), self.frame(1.)]
            if field == 'sourceEpoch': fs[1][field] = 2
            else: fs[1]['exercise']['epoch'] = 2
            rgb = {f['imageSHA256']: self.rgb(f) for f in fs}
            self.assertFalse(policy.decide(fs, rgb)['decisions'])

    def test_conflicting_current_hand_revokes_earlier_confirmation(self):
        fs = [self.frame(0.), self.frame(1.), self.frame(2.)]
        rgb = {f['imageSHA256']: self.rgb(f) for f in fs}
        rgb['2.0']['hands'][0]['surface'] = [.01, .98, .01]
        r = policy.decide(fs, rgb)
        self.assertEqual(r['states'][1]['label'], 'overhand')
        self.assertEqual(r['states'][2]['label'], 'unknown')

    def test_oblique_or_missing_torso_clears_confirmation(self):
        fs = [self.frame(0.), self.frame(1.), self.frame(2.)]
        rgb = {f['imageSHA256']: self.rgb(f) for f in fs}
        fs[-1]['bodyView']['supported'] = False
        self.assertEqual(policy.decide(fs, rgb)['states'][-1]['label'], 'unknown')

    def test_released_hand_cannot_borrow_the_other_arms_support(self):
        fs = [self.frame(0.), self.frame(1.)]
        for f in fs: f['supportBySide']['left'] = False
        rgb = {f['imageSHA256']: self.rgb(f) for f in fs}
        self.assertFalse(policy.decide(fs, rgb)['decisions'])

    def test_duplicate_pixels_and_two_hands_are_not_two_votes(self):
        fs = [self.frame(0.), self.frame(.25)]
        fs[1]['imageSHA256'] = fs[0]['imageSHA256']
        rgb = {fs[0]['imageSHA256']: self.rgb(fs[0])}
        # The same source image cannot have a new presentation time.
        with self.assertRaises(AssertionError): policy.decide(fs, rgb)
        fs = [self.frame(0.)]; rgb = {'0.0': self.rgb(fs[0])}
        rgb['0.0']['hands'].append(dict(rgb['0.0']['hands'][0], side='right'))
        self.assertFalse(policy.decide(fs, rgb)['decisions'])

    def test_reference_labels_do_not_enter_decisions(self):
        fs = [self.frame(0.), self.frame(1.)]
        rgb = {f['imageSHA256']: self.rgb(f) for f in fs}
        expected = policy.decide(fs, rgb)
        for f in fs: f.update(referenceGrip='underhand', referenceView='rear')
        self.assertEqual(expected, policy.decide(fs, rgb))


if __name__ == '__main__': unittest.main()
