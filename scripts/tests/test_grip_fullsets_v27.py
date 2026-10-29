import importlib.util
from pathlib import Path
import unittest
s=importlib.util.spec_from_file_location('fullsets',Path(__file__).resolve().parents[1]/'evaluate-grip-fullsets-v27.py')
m=importlib.util.module_from_spec(s);s.loader.exec_module(m)
class AccountingTests(unittest.TestCase):
    def frame(self,t,epoch=1,moves=0):return dict(seconds=t,sourceEpoch=epoch,trackId=1,exercise=dict(epoch=1),movements=moves)
    def state(self,t,label):return dict(seconds=t,label=label)
    def test_no_credit_across_unknown(self):
        fs=[self.frame(t) for t in [0,.1,.2,.3]]
        ss=[self.state(t,v) for t,v in zip([0,.1,.2,.3],['overhand','unknown','overhand','overhand'])]
        self.assertAlmostEqual(m.credit(fs,ss,0,.3)['overhand'],.1)
    def test_identity_reset_does_not_bridge(self):
        self.assertEqual(m.credit([self.frame(0),self.frame(.1,2)],[self.state(0,'overhand'),self.state(.1,'overhand')],0,1),{})
    def test_source_time_clipping(self):
        result=m.credit([self.frame(0),self.frame(.2)],[self.state(0,'underhand'),self.state(.2,'underhand')],.05,.15)
        self.assertAlmostEqual(result['underhand'],.1)
    def test_reset_counter_events_are_not_negative(self):
        events=m.new_movements([self.frame(0,moves=2),self.frame(.1,moves=2),self.frame(.2,2,0),self.frame(.3,2,1)])
        self.assertEqual([e['count'] for e in events],[2,1])
    def test_half_open_boundaries(self):
        self.assertTrue(m.inside(1,[1,2]));self.assertFalse(m.inside(2,[1,2]))
    def test_acceptance_alone_is_not_complete(self):
        self.assertFalse(m.complete(dict(acceptedAt=1,returnedAt=None,closeReason='sourceEnded')))
        self.assertTrue(m.complete(dict(acceptedAt=1,returnedAt=2,closeReason='returned')))
if __name__=='__main__':unittest.main()
