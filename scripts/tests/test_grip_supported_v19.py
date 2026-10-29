import importlib.util
from pathlib import Path
import unittest

spec=importlib.util.spec_from_file_location('supported',Path(__file__).resolve().parents[1]/'evaluate-grip-supported-v19.py')
p=importlib.util.module_from_spec(spec);spec.loader.exec_module(p)


class SupportedAccountingTests(unittest.TestCase):
    def inputs(self):
        case=dict(id='test',role='separateSource',supported=True,grip='overhand',activeWindow=[1,3],releaseStart=4,fullCycleWindows=[[1,3]])
        frames=[dict(sourceEpoch=1,movements=0,exercise=dict(eligible=t in [1,2,3])) for t in range(5)]
        states=[dict(seconds=t,label='overhand' if t==2 else 'unknown',observed=t==2) for t in range(5)]
        return case,frames,states

    def test_missing_images_stay_in_original_schedule_coverage(self):
        c,f,s=self.inputs();r=p.summarize(c,f,s,[0,1,2,3,4])
        self.assertEqual(r['originalExerciseScheduledFrames'],3)
        self.assertEqual(r['exerciseLabelCoverage'],1/3)

    def test_exclusion_never_becomes_a_correct_or_unknown_test_video(self):
        c,f,s=self.inputs();c['supported']=False
        self.assertEqual(p.summarize(c,f,s,[0,1,2,3,4])['outcome'],'excluded')

    def test_unknown_references_never_earn_correctness(self):
        c,f,s=self.inputs();c['grip']='unknown'
        for state in s:state.update(label='unknown',observed=False)
        r=p.summarize(c,f,s,[0,1,2,3,4])
        self.assertEqual(r['outcome'],'unverifiable');self.assertIsNone(r['firstCorrectSeconds'])
        self.assertFalse(r['cycles'][0]['correctVisible'])

    def test_wrong_label_not_hidden_by_later_correct_label(self):
        c,f,s=self.inputs();s[1].update(label='underhand',observed=True)
        self.assertEqual(p.summarize(c,f,s,[0,1,2,3,4])['outcome'],'wrongOrInconsistent')

    def test_release_labels_and_new_movements_are_counted_separately(self):
        c,f,s=self.inputs();s[4].update(label='overhand',observed=False);f[4]['movements']=1
        r=p.summarize(c,f,s,[0,1,2,3,4])['negativeControls']
        self.assertEqual(r['visibleLabels'],1);self.assertEqual(r['newMovements'],1)


if __name__=='__main__':unittest.main()
