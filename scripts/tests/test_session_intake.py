import copy
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "session_intake.py"
SPEC = importlib.util.spec_from_file_location("session_intake", MODULE_PATH)
session_intake = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(session_intake)


def review():
    return {
        "schema_version": 1,
        "id": "field_session_001",
        "exercise": "dip",
        "evidence_class": "field",
        "population_eligible": True,
        "reviewed_without_runtime_output": True,
        "source_group": "field-source-001",
        "participant_group": "participant-001",
        "expected_movements": 10,
        "bar_setup": {"required": True, "succeeded": True, "attempts": 2},
        "camera_stability": {
            "false_interruptions": 0,
            "deliberate_events": 1,
            "detected_deliberate_events": 1,
        },
        "tags": ["field", "indoor", "side-view"],
    }


def report():
    return {
        "schemaVersion": 1,
        "runtime": {},
        "visionLatency": {},
        "sceneRegistrationLatency": {},
        "stability": {},
        "thresholds": {
            "orientationDegrees": 1.5,
            "sceneTranslationFraction": 0.008,
            "sceneScaleFraction": 0.012,
        },
        "counterPolicyVersion": 6,
        "exercise": "dip",
        "side": "right",
        "observedMovements": 10,
        "partialAttempts": 1,
        "interruptedAttempts": 0,
        "setAnalyzedFrames": 200,
        "setUsableTrackingFrames": 190,
        "trackingCoverage": 0.95,
        "setPhase": "finished",
        "setEndReason": "manual",
        "omittedSamples": 0,
        "samples": [],
    }


class SessionIntakeTests(unittest.TestCase):
    def test_all_overhead_recovery_policies_keep_literal_boundary_pairs(self):
        truth = review()
        truth.update(exercise="pull_up", evidence_class="development", population_eligible=False,
                     reviewed_without_runtime_output=False)
        for count_version, boundary in ((1, 5), (2, 6), (3, 7), (4, 8)):
            runtime = report()
            runtime.update(exercise="pullUp", countMethod="overheadPullUpRelativeMotion",
                           counterPolicyVersion=count_version, boundaryPolicyVersion=boundary)
            session = session_intake.build_session(truth, runtime)
            self.assertEqual(session["counter_policy_version"], count_version)
            self.assertEqual(session["boundary_policy_version"], boundary)
            self.assertFalse(session["population_eligible"])
            for wrong in (None, 1, 2, 3, 4, 5, 6, 7, 8, 99):
                if wrong == boundary:
                    continue
                with self.subTest(count_version=count_version, wrong_boundary=wrong):
                    with self.assertRaises(session_intake.IntakeError):
                        session_intake.build_session(truth, {**runtime, "boundaryPolicyVersion": wrong})
            for wrong in ({"countMethod": None}, {"countMethod": "bilateralPullUpRelativeMotion"},
                          {"exercise": "dip"}, {"counterPolicyVersion": 99}):
                with self.subTest(count_version=count_version, wrong=wrong):
                    with self.assertRaises(session_intake.IntakeError):
                        session_intake.build_session(truth, {**runtime, **wrong})

    def test_overhead_method_requires_its_literal_count_and_boundary_pair(self):
        truth=review();truth.update(exercise="pull_up",evidence_class="development",population_eligible=False,
                                    reviewed_without_runtime_output=False)
        runtime=report();runtime.update(exercise="pullUp",countMethod="overheadPullUpRelativeMotion",counterPolicyVersion=1,boundaryPolicyVersion=5)
        session=session_intake.build_session(truth,runtime)
        self.assertEqual(session["counter_method"],"overheadPullUpRelativeMotion")
        self.assertEqual(session["boundary_policy_version"],5)
        self.assertFalse(session["population_eligible"])
        for change in ({"boundaryPolicyVersion":None},{"boundaryPolicyVersion":2},{"boundaryPolicyVersion":4},
                       {"counterPolicyVersion":2},{"countMethod":"bilateralPullUpRelativeMotion"},{"exercise":"dip"}):
            with self.subTest(change=change),self.assertRaises(session_intake.IntakeError):
                session_intake.build_session(truth,{**runtime,**change})

    def test_torso_dip_method_preserves_provenance_and_development_evidence(self):
        truth = review()
        truth.update(evidence_class="development", population_eligible=False,
                     reviewed_without_runtime_output=False)
        runtime = report()
        runtime.update(counterPolicyVersion=2, countMethod="bilateralTorsoDipRelativeMotion",
                       boundaryPolicyVersion=3, setEndReason="disengaged")
        session = session_intake.build_session(truth, runtime)
        self.assertEqual(session["counter_method"], "bilateralTorsoDipRelativeMotion")
        self.assertEqual(session["counter_policy_version"], 2)
        self.assertEqual(session["boundary_policy_version"], 3)
        self.assertEqual(session["end_reason"], "disengaged")
        self.assertEqual(session["evidence_class"], "development")
        self.assertFalse(session["population_eligible"])
        legacy = session_intake.build_session(truth, {**runtime, "counterPolicyVersion": 1})
        self.assertEqual(legacy["counter_policy_version"], 1)
        for change in (
            {"counterPolicyVersion": 3}, {"boundaryPolicyVersion": 5},
            {"exercise": "pullUp"}, {"countMethod": "experimentalBilateralTorsoDipMotion"},
        ):
            invalid = {**runtime, **change}
            with self.subTest(change=change), self.assertRaises(session_intake.IntakeError):
                session_intake.build_session(truth, invalid)

    def test_large_torso_support_exit_boundary4_preserves_history_and_development_scope(self):
        truth=review();truth.update(evidence_class="development",population_eligible=False,
                                    reviewed_without_runtime_output=False)
        runtime=report();runtime.update(exercise="dip",countMethod="bilateralTorsoDipRelativeMotion",
                                       counterPolicyVersion=2,boundaryPolicyVersion=4,setEndReason="disengaged")
        session=session_intake.build_session(truth,runtime)
        self.assertEqual(session["boundary_policy_version"],4)
        self.assertFalse(session["population_eligible"])
        self.assertEqual(session["evidence_class"],"development")
        self.assertEqual(session_intake.build_session(truth,{**runtime,"boundaryPolicyVersion":3})["boundary_policy_version"],3)
        for change in ({"counterPolicyVersion":1},{"countMethod":"bilateralDipRelativeMotion","counterPolicyVersion":1},
                       {"countMethod":"bilateralBodyDipMotion","counterPolicyVersion":1},
                       {"exercise":"pullUp","countMethod":"bilateralPullUpRelativeMotion","counterPolicyVersion":1}):
            with self.subTest(change=change),self.assertRaises(session_intake.IntakeError):
                session_intake.build_session(truth,{**runtime,**change})

    def test_pullup_motion_method_and_natural_or_tracking_end_survive_intake(self):
        truth=review();truth["exercise"]="pull_up"
        runtime=report();runtime.update(exercise="pullUp",counterPolicyVersion=1,countMethod="bilateralPullUpRelativeMotion",boundaryPolicyVersion=2)
        for reason in ("disengaged","trackingLost"):
            runtime["setEndReason"]=reason
            session=session_intake.build_session(truth,runtime)
            self.assertEqual(session["counter_method"],"bilateralPullUpRelativeMotion")
            self.assertEqual(session["end_reason"],reason)
        runtime["counterPolicyVersion"]=99
        with self.assertRaises(session_intake.IntakeError):session_intake.build_session(truth,runtime)
        runtime.update(counterPolicyVersion=1,exercise="dip")
        with self.assertRaises(session_intake.IntakeError):session_intake.build_session(review(),runtime)
    def test_relative_dip_v3_lifecycle_survives_intake_without_changing_legacy_support(self):
        runtime=report();runtime.update(counterPolicyVersion=1,countMethod="bilateralDipRelativeMotion",boundaryPolicyVersion=3)
        session=session_intake.build_session(review(),runtime)
        self.assertEqual(session["counter_method"],"bilateralDipRelativeMotion")
        self.assertEqual(session["boundary_policy_version"],3)
        for method in (None,"bilateralBodyDipMotion","bilateralDipImageMotion"):
            runtime["countMethod"]=method
            with self.assertRaises(session_intake.IntakeError):session_intake.build_session(review(),runtime)

    def test_boundary_policy_is_preserved_and_unknown_versions_rejected(self):
        runtime=report();runtime["boundaryPolicyVersion"]=2
        self.assertEqual(session_intake.build_session(review(),runtime)["boundary_policy_version"],2)
        runtime["boundaryPolicyVersion"]=99
        with self.assertRaises(session_intake.IntakeError):session_intake.build_session(review(),runtime)
    def test_body_method_provenance_is_kept_and_wrong_versions_are_rejected(self):
        data = report();data.update(counterPolicyVersion=1,countMethod="bilateralBodyDipMotion")
        result = session_intake.build_session(review(),data)
        self.assertEqual(result["counter_method"], "bilateralBodyDipMotion")
        data["counterPolicyVersion"] = 8
        with self.assertRaises(session_intake.IntakeError): session_intake.build_session(review(),data)

    def test_builds_sanitized_session_from_independent_truth_and_runtime(self):
        session = session_intake.build_session(review(), report())
        self.assertEqual(session["expected_movements"], 10)
        self.assertEqual(session["counter_policy_version"], 6)
        self.assertEqual(session["tracking_side"], "right")
        self.assertEqual(session["observed_movements"], 10)
        self.assertEqual(session["partial_attempts"], 1)
        self.assertEqual(session["interrupted_attempts"], 0)
        self.assertEqual(session["end_reason"], "manual")
        self.assertEqual(session["analyzed_frames"], 200)
        self.assertEqual(session["usable_tracking_frames"], 190)
        self.assertEqual(session["bar_setup"]["attempts"], 2)
        self.assertTrue(session["reviewed_without_runtime_output"])
        self.assertNotIn("samples", session)
        self.assertNotIn("runtime", session)
        self.assertNotIn("thresholds", session)

    def test_review_exercise_must_match_runtime(self):
        r = review()
        r["exercise"] = "pull_up"
        with self.assertRaises(session_intake.IntakeError):
            session_intake.build_session(r, report())

    def test_population_evidence_requires_independent_review(self):
        r = review()
        r["reviewed_without_runtime_output"] = False
        with self.assertRaises(session_intake.IntakeError):
            session_intake.build_session(r, report())

    def test_tracking_coverage_must_match_set_frame_counts(self):
        runtime = report()
        runtime["trackingCoverage"] = 0.90
        with self.assertRaises(session_intake.IntakeError):
            session_intake.build_session(review(), runtime)

    def test_legacy_device_report_without_set_counters_is_rejected(self):
        runtime = report()
        runtime.pop("setAnalyzedFrames")
        with self.assertRaises(session_intake.IntakeError):
            session_intake.build_session(review(), runtime)

    def test_session_intake_requires_finished_runtime(self):
        runtime = report()
        runtime["setPhase"] = "running"
        with self.assertRaises(session_intake.IntakeError):
            session_intake.build_session(review(), runtime)

    def test_finished_runtime_requires_explicit_end_reason(self):
        runtime = report()
        runtime["setEndReason"] = None
        with self.assertRaises(session_intake.IntakeError):
            session_intake.build_session(review(), runtime)

    def test_duplicate_ids_across_pairs_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            review_path = root / "review.json"
            report_path = root / "report.json"
            review_path.write_text(json.dumps(review()), encoding="utf-8")
            report_path.write_text(json.dumps(report()), encoding="utf-8")
            with self.assertRaises(session_intake.IntakeError):
                session_intake.build_manifest([
                    (review_path, report_path),
                    (review_path, report_path),
                ])


if __name__ == "__main__":
    unittest.main()
