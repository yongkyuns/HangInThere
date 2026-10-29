import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('controlled', Path(__file__).resolve().parents[1]/'grip-controlled-v17.py')
p = importlib.util.module_from_spec(spec); spec.loader.exec_module(p)


class ControlledGripTests(unittest.TestCase):
    def hand(self):
        return dict(side='left', surface=[.95, .03, .02], neutralByView=[.01]*3, visible=.2)

    def frame(self, t, active=True, epoch=1):
        return dict(seconds=t, imageSHA256=str(t), sourceEpoch=1, trackId=1, released=False,
                    supportBySide=dict(left=True,right=True),
                    exercise=dict(eligible=active, startedAt=0., epoch=epoch, reason='exercise' if active else 'setupOrRelease'))

    def test_readability_ablation_keeps_raw_anatomy_confidence(self):
        h = self.hand()
        self.assertIsNone(p.evidence(h,'front',True))
        self.assertEqual(p.evidence(h,'front',False)['classifierScore'], .95)
        h['surface'] = [.79,.11,.1]
        self.assertIsNone(p.evidence(h,'front',False))

    def test_missing_readability_is_not_an_input_to_omitted_head(self):
        h = self.hand(); del h['visible']
        self.assertEqual(p.evidence(h,'front',False)['label'],'overhand')
        self.assertIsNone(p.evidence(h,'front',True))

    def test_confirmed_view_is_explicit_not_inferred_from_grip_label(self):
        self.assertEqual(p.evidence(self.hand(),'rear',False)['label'],'underhand')
        self.assertIsNone(p.evidence(self.hand(),'oblique',False))
        self.assertIsNone(p.evidence(self.hand(),'unknown',False))

    def test_side_surface_alone_does_not_mean_neutral(self):
        h=self.hand();h['surface']=[.01,.01,.98]
        self.assertIsNone(p.evidence(h,'front',False))
        h['neutralByView']=[.95]*3
        self.assertEqual(p.evidence(h,'front',False)['label'],'neutral')

    def test_setup_and_release_do_not_accumulate_or_retain_labels(self):
        fs=[self.frame(0.,False),self.frame(1.),self.frame(2.),self.frame(2.25,False)]
        rgb={f['imageSHA256']:dict(seconds=f['seconds'],hands=[self.hand()]) for f in fs}
        states=p.decide(fs,rgb,'front',False)['states']
        self.assertEqual([s['label'] for s in states],['unknown','unknown','overhand','unknown'])

    def test_target_change_or_regrasp_cannot_join_two_single_votes(self):
        fs=[self.frame(0.),self.frame(1.,epoch=2)]
        rgb={f['imageSHA256']:dict(seconds=f['seconds'],hands=[self.hand()]) for f in fs}
        self.assertFalse(p.decide(fs,rgb,'front',False)['decisions'])

    def test_same_frame_two_hands_are_one_vote_and_conflicts_abstain(self):
        fs=[self.frame(0.)];rgb={'0.0':dict(seconds=0.,hands=[self.hand(),dict(self.hand(),side='right')])}
        self.assertFalse(p.decide(fs,rgb,'front',False)['decisions'])
        fs.append(self.frame(1.));rgb['1.0']=copy.deepcopy(rgb['0.0']);rgb['1.0']['seconds']=1.
        rgb['1.0']['hands'][1]['surface']=[.01,.98,.01]
        self.assertEqual(p.decide(fs,rgb,'front',False)['states'][-1]['label'],'unknown')

    def test_contact_and_reference_labels_never_change_decisions(self):
        fs=[self.frame(0.),self.frame(1.)];rgb={f['imageSHA256']:dict(seconds=f['seconds'],hands=[self.hand()]) for f in fs}
        expected=p.decide(fs,rgb,'front',False)
        for f in fs:f['referenceGrip']='underhand'
        for r in rgb.values():r['hands'][0].update(contact=0.,raised=False)
        self.assertEqual(expected,p.decide(fs,rgb,'front',False))


if __name__=='__main__':unittest.main()
