import copy
import importlib.util
import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "session_qualification.py"
SPEC = importlib.util.spec_from_file_location("session_qualification", MODULE_PATH)
session_qualification = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(session_qualification)
FIXTURE = ROOT / "Evaluation" / "fixtures" / "session-qualification-seed.json"


class SessionQualificationTests(unittest.TestCase):
    def test_all_overhead_recovery_policies_preserve_evidence_and_reject_mixed_pairs(self):
        for count_version, boundary in ((1, 5), (2, 6), (3, 7), (4, 8)):
            manifest = json.loads(FIXTURE.read_text())
            item = manifest["sessions"][0]
            item.update(exercise="pull_up", counter_method="overheadPullUpRelativeMotion",
                        counter_policy_version=count_version, boundary_policy_version=boundary,
                        evidence_class="development", population_eligible=False)
            summary = session_qualification.analyze(manifest)
            self.assertEqual(summary["population_eligible_sessions"]["session_count"], 0)
            self.assertEqual(summary["all_sessions"]["boundary_policy_version_counts"][str(boundary)], 1)
            for wrong in (None, 1, 2, 3, 4, 5, 6, 7, 8, 99):
                if wrong == boundary:
                    continue
                invalid = copy.deepcopy(manifest)
                invalid["sessions"][0]["boundary_policy_version"] = wrong
                with self.subTest(count_version=count_version, wrong_boundary=wrong):
                    with self.assertRaises(session_qualification.SessionError):
                        session_qualification.analyze(invalid)
            for wrong in ({"counter_method": None}, {"counter_method": "bilateralPullUpRelativeMotion"},
                          {"exercise": "dip"}, {"counter_policy_version": 99}):
                invalid = copy.deepcopy(manifest)
                invalid["sessions"][0].update(wrong)
                with self.subTest(count_version=count_version, wrong=wrong):
                    with self.assertRaises(session_qualification.SessionError):
                        session_qualification.analyze(invalid)

    def test_overhead_method_preserves_development_scope_and_rejects_wrong_pairs(self):
        manifest=json.loads(FIXTURE.read_text())
        item=manifest["sessions"][0]
        item.update(exercise="pull_up",counter_method="overheadPullUpRelativeMotion",counter_policy_version=1,
                    boundary_policy_version=5,evidence_class="development",population_eligible=False)
        report=session_qualification.analyze(manifest)
        self.assertEqual(report["all_sessions"]["counter_method_counts"]["overheadPullUpRelativeMotion"],1)
        self.assertEqual(report["population_eligible_sessions"]["session_count"],0)
        for change in ({"boundary_policy_version":None},{"boundary_policy_version":2},{"boundary_policy_version":4},
                       {"counter_policy_version":2},{"counter_method":"bilateralPullUpRelativeMotion"},{"exercise":"dip"}):
            invalid=copy.deepcopy(manifest);invalid["sessions"][0].update(change)
            with self.subTest(change=change),self.assertRaises(session_qualification.SessionError):
                session_qualification.analyze(invalid)

    def test_torso_dip_method_is_separate_without_promoting_development_evidence(self):
        manifest = json.loads(FIXTURE.read_text())
        for index, method in enumerate(("bilateralDipRelativeMotion", "bilateralTorsoDipRelativeMotion")):
            manifest["sessions"][index].update(
                exercise="dip", counter_method=method, counter_policy_version=index+1,
                boundary_policy_version=3, evidence_class="development", population_eligible=False)
        report = session_qualification.analyze(manifest)
        self.assertEqual(report["all_sessions"]["counter_method_counts"], {
            "bilateralDipRelativeMotion": 1, "bilateralTorsoDipRelativeMotion": 1, "legacyExerciseCounter": 1})
        self.assertEqual(report["all_sessions"]["boundary_policy_version_counts"], {"3": 2, "unrecorded": 1})
        self.assertEqual(report["population_eligible_sessions"]["session_count"], 0)
        self.assertEqual(report["all_sessions"]["counter_policy_version_counts"]["2"], 1)
        historical = copy.deepcopy(manifest)
        historical["sessions"][1]["counter_policy_version"] = 1
        self.assertEqual(session_qualification.analyze(historical)["all_sessions"]["counter_method_counts"],
                         report["all_sessions"]["counter_method_counts"])
        for change in (
            {"counter_policy_version": 3}, {"boundary_policy_version": 5},
            {"exercise": "pull_up"}, {"counter_method": "experimentalBilateralTorsoDipMotion"},
        ):
            invalid = copy.deepcopy(manifest)
            invalid["sessions"][1].update(change)
            with self.subTest(change=change), self.assertRaises(session_qualification.SessionError):
                session_qualification.analyze(invalid)

    def test_large_support_exit_boundary4_is_a_separate_supported_development_cohort(self):
        manifest=json.loads(FIXTURE.read_text())
        for index,boundary in enumerate((3,4)):
            manifest["sessions"][index].update(exercise="dip",counter_method="bilateralTorsoDipRelativeMotion",
                counter_policy_version=2,boundary_policy_version=boundary,evidence_class="development",population_eligible=False)
        report=session_qualification.analyze(manifest)
        self.assertEqual(report["all_sessions"]["boundary_policy_version_counts"],{"3":1,"4":1,"unrecorded":1})
        self.assertEqual(report["population_eligible_sessions"]["session_count"],0)
        for change in ({"counter_policy_version":1},{"counter_method":"bilateralDipRelativeMotion","counter_policy_version":1},
                       {"counter_method":"bilateralBodyDipMotion","counter_policy_version":1},
                       {"exercise":"pull_up","counter_method":"bilateralPullUpRelativeMotion","counter_policy_version":1}):
            invalid=copy.deepcopy(manifest);invalid["sessions"][1].update(change)
            with self.subTest(change=change),self.assertRaises(session_qualification.SessionError):
                session_qualification.analyze(invalid)

    def test_pullup_motion_is_separate_and_disengagement_is_not_an_interruption(self):
        manifest=json.loads(FIXTURE.read_text())
        item=manifest["sessions"][2]
        item.update(exercise="pull_up",counter_method="bilateralPullUpRelativeMotion",counter_policy_version=1,end_reason="disengaged")
        report=session_qualification.analyze(manifest)["all_sessions"]
        self.assertEqual(report["counter_method_counts"]["bilateralPullUpRelativeMotion"],1)
        base=report["set_end_interruptions"]
        item["end_reason"]="trackingLost"
        self.assertEqual(session_qualification.analyze(manifest)["all_sessions"]["set_end_interruptions"],base+1)
        item["counter_policy_version"]=99
        with self.assertRaises(session_qualification.SessionError):session_qualification.analyze(manifest)
        item.update(counter_policy_version=1,exercise="dip")
        with self.assertRaises(session_qualification.SessionError):session_qualification.analyze(manifest)
    def test_relative_dip_boundary3_is_separate_and_rejects_legacy_method(self):
        manifest=json.loads(FIXTURE.read_text());item=manifest["sessions"][0]
        item.update(exercise="dip",counter_method="bilateralDipRelativeMotion",counter_policy_version=1,boundary_policy_version=3)
        report=session_qualification.analyze(manifest)["all_sessions"]
        self.assertEqual(report["boundary_policy_version_counts"],{"3":1,"unrecorded":2})
        self.assertEqual(report["counter_method_counts"]["bilateralDipRelativeMotion"],1)
        for method in (None,"bilateralBodyDipMotion","bilateralDipImageMotion"):
            item["counter_method"]=method
            with self.assertRaises(session_qualification.SessionError):session_qualification.analyze(manifest)

    def test_boundary_policy_versions_are_not_conflated(self):
        manifest=json.loads(FIXTURE.read_text())
        manifest["sessions"][0]["boundary_policy_version"]=2
        self.assertEqual(session_qualification.analyze(manifest)["all_sessions"]["boundary_policy_version_counts"],{"2":1,"unrecorded":2})
        manifest["sessions"][0]["boundary_policy_version"]=99
        with self.assertRaises(session_qualification.SessionError):session_qualification.analyze(manifest)
    def test_methods_are_reported_separately_even_when_versions_match(self):
        manifest = json.loads(FIXTURE.read_text())
        manifest["sessions"][0].update(counter_method="bilateralBodyDipMotion",counter_policy_version=1)
        manifest["sessions"][1].update(counter_method="bilateralDipImageMotion",counter_policy_version=1)
        result = session_qualification.analyze(manifest)["all_sessions"]
        self.assertEqual(result["counter_method_counts"],{
            "bilateralBodyDipMotion":1,"bilateralDipImageMotion":1,"legacyExerciseCounter":1})
        manifest["sessions"][0]["counter_policy_version"] = 8
        with self.assertRaises(session_qualification.SessionError): session_qualification.analyze(manifest)

    def setUp(self):
        self.manifest = json.loads(FIXTURE.read_text(encoding="utf-8"))

    def test_seed_is_descriptive_and_excludes_population_claim(self):
        report = session_qualification.analyze(self.manifest)
        all_metrics = report["all_sessions"]
        field_metrics = report["population_eligible_sessions"]
        self.assertEqual(all_metrics["session_count"], 3)
        self.assertEqual(all_metrics["expected_movements"], 15)
        self.assertEqual(all_metrics["observed_movements"], 15)
        self.assertEqual(all_metrics["exact_count_fraction"], 1.0)
        self.assertEqual(all_metrics["rep_recall"], 1.0)
        self.assertEqual(all_metrics["extra_movements"], 0)
        self.assertAlmostEqual(all_metrics["tracking_coverage_weighted"], 422 / 439)
        self.assertEqual(field_metrics["session_count"], 0)
        self.assertEqual(field_metrics["participant_group_count"], 0)
        self.assertEqual(field_metrics["source_group_count"], 0)
        self.assertIsNone(field_metrics["exact_count_fraction"])
        self.assertEqual(report["evidence_class_counts"], {
            "development": 2,
            "heldout_consumed": 1,
        })

    def test_field_session_drives_product_metrics(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["sessions"].append({
            "id": "field_session_001",
            "exercise": "pull_up",
            "counter_policy_version": 6,
            "tracking_side": "left",
            "evidence_class": "field",
            "population_eligible": True,
            "reviewed_without_runtime_output": True,
            "source_group": "field-session-001",
            "participant_group": "participant-001",
            "expected_movements": 10,
            "observed_movements": 9,
            "partial_attempts": 1,
            "interrupted_attempts": 1,
            "end_reason": "phoneMoved",
            "analyzed_frames": 200,
            "usable_tracking_frames": 180,
            "bar_setup": {"required": True, "succeeded": True, "attempts": 2},
            "camera_stability": {
                "false_interruptions": 1,
                "deliberate_events": 2,
                "detected_deliberate_events": 2,
            },
            "tags": ["field", "indoor", "crowded"],
        })
        report = session_qualification.analyze(manifest)
        metrics = report["population_eligible_sessions"]
        self.assertEqual(metrics["session_count"], 1)
        self.assertEqual(metrics["counter_policy_version_counts"], {"6": 1})
        self.assertEqual(metrics["tracking_side_counts"], {"left": 1})
        self.assertEqual(metrics["participant_group_count"], 1)
        self.assertEqual(metrics["source_group_count"], 1)
        self.assertEqual(metrics["exact_count_sessions"], 0)
        self.assertEqual(metrics["missed_movements"], 1)
        self.assertEqual(metrics["extra_movements"], 0)
        self.assertEqual(metrics["rep_recall"], 0.9)
        self.assertEqual(metrics["tracking_coverage_weighted"], 0.9)
        self.assertEqual(metrics["sets_with_interruptions"], 1)
        self.assertEqual(metrics["set_end_interruptions"], 1)
        self.assertEqual(metrics["end_reason_counts"], {"phoneMoved": 1})
        self.assertEqual(metrics["bar_setup_success_fraction"], 1.0)
        self.assertEqual(metrics["bar_setup_mean_attempts"], 2)
        self.assertEqual(metrics["false_camera_interruptions"], 1)
        self.assertEqual(metrics["camera_event_detection_fraction"], 1.0)

    def test_private_content_keys_are_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["sessions"][0]["location"] = "private place"
        with self.assertRaises(session_qualification.SessionError):
            session_qualification.analyze(manifest)

    def test_population_eligible_requires_independent_review(self):
        manifest = copy.deepcopy(self.manifest)
        session = copy.deepcopy(manifest["sessions"][0])
        session.update({
            "id": "field_session_unreviewed",
            "exercise": "dip",
            "evidence_class": "field",
            "population_eligible": True,
            "reviewed_without_runtime_output": False,
            "source_group": "field-source-unreviewed",
            "participant_group": "participant-unreviewed",
        })
        manifest["sessions"].append(session)
        with self.assertRaises(session_qualification.SessionError):
            session_qualification.analyze(manifest)

    def test_population_eligible_requires_field_evidence(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["sessions"][0]["population_eligible"] = True
        with self.assertRaises(session_qualification.SessionError):
            session_qualification.analyze(manifest)

    def test_invalid_tracking_count_is_rejected(self):
        manifest = copy.deepcopy(self.manifest)
        manifest["sessions"][0]["usable_tracking_frames"] = 301
        with self.assertRaises(session_qualification.SessionError):
            session_qualification.analyze(manifest)

    def test_required_bar_setup_requires_an_attempt(self):
        manifest = copy.deepcopy(self.manifest)
        session = manifest["sessions"][0]
        session["bar_setup"] = {"required": True, "succeeded": False, "attempts": 0}
        with self.assertRaises(session_qualification.SessionError):
            session_qualification.analyze(manifest)


if __name__ == "__main__":
    unittest.main()
