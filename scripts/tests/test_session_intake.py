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
    def test_builds_sanitized_session_from_independent_truth_and_runtime(self):
        session = session_intake.build_session(review(), report())
        self.assertEqual(session["expected_movements"], 10)
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
