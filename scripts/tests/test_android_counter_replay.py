import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace
import unittest
from unittest.mock import patch

SPEC=importlib.util.spec_from_file_location('counter_replay',Path(__file__).parents[1]/'compare-android-observations.py')
REPLAY=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(REPLAY)

class NativeCounterReplayTests(unittest.TestCase):
    def evidence(self):
        event=dict(outcome='movement',sourceSeconds=1.25,reason='barReferencedTop;chinClearanceNotMeasured')
        expected=dict(observedMovements=1,events=[event])
        result=dict(summary=dict(observedMovements=1),events=[dict(event)])
        case=dict(id='public',exercise='pullUp',side='left',referenceEdge=dict(a=dict(x=100,y=20),b=dict(x=300,y=25)))
        pose=dict(timestamp=dict(value=1_250_000,timescale=1_000_000),imageSize=dict(width=640,height=360),
            people=[dict(landmarks=[dict(joint='leftWrist',position=dict(x=120.25,y=20.5),confidence=.35)])])
        return case,[pose],expected,result

    def test_original_coordinates_scores_timestamps_and_reference_reach_swift(self):
        case,poses,expected,result=self.evidence()
        with patch.object(REPLAY.subprocess,'run',return_value=SimpleNamespace(stdout=json.dumps(result))) as run:
            REPLAY.replay(Path('/counter'),case,poses,expected)
        args,kwargs=run.call_args
        self.assertEqual(args[0],['/counter','pullUp','left','100','20','300','25'])
        row=json.loads(kwargs['input']);self.assertEqual(row,dict(poses[0],frameIndex=0,timebase='source_pts'))
        self.assertTrue(kwargs['check'])
        self.assertNotIn('frameIndex',poses[0])

    def test_counts_event_reasons_and_source_times_must_match(self):
        for key,value in [('outcome','partial'),('reason','different'),('sourceSeconds',1.26)]:
            case,poses,expected,result=self.evidence();result['events'][0][key]=value
            with patch.object(REPLAY.subprocess,'run',return_value=SimpleNamespace(stdout=json.dumps(result))):
                with self.assertRaises(AssertionError):REPLAY.replay(Path('/counter'),case,poses,expected)
        case,poses,expected,result=self.evidence();result['summary']['observedMovements']=0
        with patch.object(REPLAY.subprocess,'run',return_value=SimpleNamespace(stdout=json.dumps(result))):
            with self.assertRaisesRegex(AssertionError,'policy mismatch'):REPLAY.replay(Path('/counter'),case,poses,expected)

    def test_missing_terminal_event_is_rejected(self):
        case,poses,expected,result=self.evidence();result['events']=[]
        with patch.object(REPLAY.subprocess,'run',return_value=SimpleNamespace(stdout=json.dumps(result))):
            with self.assertRaisesRegex(AssertionError,'event count'):REPLAY.replay(Path('/counter'),case,poses,expected)
