import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location('android_check', Path(__file__).parents[1] / 'check-android-evaluation.py')
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)

class AndroidEvaluationEvidenceTests(unittest.TestCase):
    def report(self):
        return {'policyVersion': 8, 'failures': [], 'cases': [
            {'id': name, 'frames': frames, 'singlePersonFrames': frames,
             'functionalParity': True, 'observedMovements': count, 'expectedMovements': count,
             'poseLatencyMillis': {'samples': frames, 'p95': 100}, 'peakPssKb': 200000,
             'productionReplay': {'functionalParity': True, 'peakPssKb': 200000,
                 'pipelineWithDisplayCopyLatencyMillis': {'samples': frames+4, 'p95': 150}}}
            for name, frames, count in [('pullup_smoke', 40, 1), ('dip_controlled_pexels', 300, 9)]]}

    def check(self, report):
        with tempfile.TemporaryDirectory() as directory:
            path=Path(directory)/'report.json'; path.write_text(json.dumps(report))
            CHECK.native(path)

    def test_complete_native_evidence_passes(self):
        self.check(self.report())

    def test_empty_fixture_suite_cannot_pass(self):
        r=self.report();r['cases']=[]
        with self.assertRaises(AssertionError):self.check(r)

    def test_fixed_edge_success_cannot_hide_auto_failure(self):
        r=self.report();r['cases'][0]['productionReplay']['functionalParity']=False
        with self.assertRaises(AssertionError):self.check(r)

    def test_model_only_budget_cannot_hide_slow_production_pipeline(self):
        r=self.report();r['cases'][0]['productionReplay']['pipelineWithDisplayCopyLatencyMillis']['p95']=2600
        with self.assertRaises(AssertionError):self.check(r)

    def test_unbounded_production_memory_fails(self):
        r=self.report();r['cases'][0]['productionReplay']['peakPssKb']=1_048_576
        with self.assertRaises(AssertionError):self.check(r)

    def test_missing_frame_samples_fail(self):
        r=self.report();r['cases'][0]['poseLatencyMillis']['samples']=39
        with self.assertRaises(AssertionError):self.check(r)

    def test_native_failure_is_not_treated_as_missing_success(self):
        r=self.report();r['failures']=['pose error']
        with self.assertRaises(AssertionError):self.check(r)

if __name__=='__main__':unittest.main()
