import copy
import unittest
import json
from pathlib import Path
from scripts import workout_benchmark as benchmark
from scripts import workout_release_readiness as module


class WorkoutReleaseReadinessTests(unittest.TestCase):
    def policy(self):
        return dict(schema_version=1, status="predeclared", benchmark_sha256="a" * 64, minimum_participants=2, minimum_sessions=2,
            supported_contexts=[dict(camera_view="front")], limits=dict(rep_precision=.95, rep_recall=.95, exact_set_count_fraction=.9,
                observation_coverage=.8, phase_agreement_all_labelled=.8, false_starts=0, false_ends=0, phase_boundary_p95_seconds=.2, hold_error_p95_seconds=.5),
            performance=dict(minimum_duration_seconds=600, minimum_device_families_per_platform=1, maximum_pipeline_p95_ms=100,
                maximum_drop_fraction=.1, maximum_battery_fraction_per_hour=.2, maximum_thermal_level="fair"))
    def score(self):
        root = Path(__file__).resolve().parents[2]
        labels = json.loads((root / 'Evaluation/fixtures/workout-benchmark-synthetic.json').read_text())
        predictions = json.loads((root / 'Evaluation/fixtures/workout-predictions-synthetic.json').read_text())
        row = benchmark.score(benchmark.freeze(labels), predictions, 'all')['sessions'][0]
        rows = [copy.deepcopy(row) for _ in range(2)]
        for i, item in enumerate(rows):
            item.update(id=f'test_{i}', participant_group=f'actor_{i}', source_group=f'capture_{i}', split='test')
            item['context']['camera_view'] = 'front'
        return dict(benchmark_sha256="a" * 64, evidence_class="independently_reviewed_sessions", split="test", participant_groups=2,
            source_groups=2, sessions=rows, summary=benchmark.aggregate(rows))
    def devices(self):
        return [dict(report_sha256=c * 64, platform=p, device_family=f"{p}-family", model_family="pinned", scope="physical_live", duration_seconds=600,
            pipeline_p95_ms=80, drop_fraction=.05, battery_fraction_per_hour=.1, maximum_thermal_level="nominal", camera_sensor_time_aligned=True,
            consent_recorded=True) for c, p in [("b", "ios"), ("c", "android")]]
    def test_predeclared_all_evidence_passes_without_claiming_coach_or_usability_review(self):
        result=module.assess(module.freeze(self.policy()), self.score(), self.devices())
        self.assertTrue(result["measurement_and_device_gates_pass"])
        self.assertIn("qualified_coach_review", result["remaining_external_reviews"])
    def test_synthetic_data_cannot_qualify_regardless_of_scores(self):
        score=self.score(); score["evidence_class"]="synthetic_contract"
        self.assertFalse(module.assess(module.freeze(self.policy()), score, self.devices())["measurement_and_device_gates_pass"])
    def test_emulator_and_missing_battery_never_establish_physical_parity(self):
        rows=self.devices(); rows[1]["scope"]="emulator"
        self.assertFalse(module.device_matrix(rows, self.policy()["performance"])["qualified"])
        rows=self.devices(); rows[0]["battery_fraction_per_hour"]=None
        self.assertFalse(module.device_matrix(rows, self.policy()["performance"])["qualified"])
    def test_lock_source_and_context_binding_cannot_be_changed_after_freeze(self):
        lock=module.freeze(self.policy()); lock["policy"]["limits"]["rep_recall"]=0
        with self.assertRaises(module.ReadinessError): module.assess(lock,self.score(),self.devices())
        score=self.score(); score["benchmark_sha256"]="d"*64
        with self.assertRaises(module.ReadinessError): module.assess(module.freeze(self.policy()),score,self.devices())
    def test_missing_metrics_and_unsupported_conditions_fail(self):
        score=self.score();
        for row in score["sessions"]:
            row["context"]["camera_view"] = "side"
            row["labelled_phase_seconds"] = 0
        score["summary"] = benchmark.aggregate(score["sessions"])
        result=module.assess(module.freeze(self.policy()),score,self.devices())
        self.assertIn("hold_error_p95_seconds",result["failures"]); self.assertIn("supported_context_coverage",result["failures"])
    def test_duplicate_or_content_bearing_reports_are_rejected(self):
        rows=self.devices(); rows[1]["report_sha256"]=rows[0]["report_sha256"]
        with self.assertRaises(module.ReadinessError): module.device_matrix(rows,self.policy()["performance"])
        rows=self.devices(); rows[0]["landmarks"]=[]
        with self.assertRaises(module.ReadinessError): module.device_matrix(rows,self.policy()["performance"])
    def test_draft_and_nonfinite_policies_cannot_be_frozen(self):
        policy=self.policy(); policy["status"]="draft"
        with self.assertRaises(module.ReadinessError): module.freeze(policy)
    def test_inconsistent_or_failed_session_evidence_cannot_pass(self):
        score=self.score(); score["summary"]["sessions"]=3
        with self.assertRaises(module.ReadinessError): module.assess(module.freeze(self.policy()), score, self.devices())
        score=self.score(); score["sessions"][0]["status"]="failed"; score["summary"]=benchmark.aggregate(score["sessions"])
        self.assertIn("failed_sessions",module.assess(module.freeze(self.policy()),score,self.devices())["failures"])
        policy=self.policy(); policy["limits"]["rep_recall"]=float('nan')
        with self.assertRaises(module.ReadinessError): module.freeze(policy)
    def test_bad_supported_subgroup_cannot_hide_behind_good_pooled_metrics(self):
        score=self.score()
        good = copy.deepcopy(score['sessions'])
        rows = [copy.deepcopy(good[i % 2]) for i in range(100)]
        for i, row in enumerate(rows):
            row.update(id=f'row_{i}', participant_group=f'actor_{i}', source_group=f'capture_{i}')
        for row in rows[-2:]:
            row['context']['camera_view'] = 'side'
            row['completed_cycles'].update(true_positives=0, false_negatives=1)
        score.update(sessions=rows, summary=benchmark.aggregate(rows), participant_groups=100, source_groups=100)
        policy=self.policy(); policy['supported_contexts'].append(dict(camera_view='side'))
        result=module.assess(module.freeze(policy),score,self.devices())
        self.assertGreater(result['metrics']['rep_recall'], .95)
        self.assertFalse(result['measurement_and_device_gates_pass'])
        self.assertIn('rep_recall', result['supported_contexts'][1]['failures'])
    def test_cohort_counts_and_pooled_summary_must_match_rows(self):
        score=self.score(); score['participant_groups']=1
        with self.assertRaises(module.ReadinessError): module.assess(module.freeze(self.policy()),score,self.devices())
        score=self.score(); score['summary']['completed_cycles']['precision']=.9
        with self.assertRaises(module.ReadinessError): module.assess(module.freeze(self.policy()),score,self.devices())


if __name__ == '__main__': unittest.main()
