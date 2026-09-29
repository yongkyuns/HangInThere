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
            "evidence_class": "field",
            "population_eligible": True,
            "source_group": "field-session-001",
            "participant_group": "participant-001",
            "expected_movements": 10,
            "observed_movements": 9,
            "partial_attempts": 1,
            "interrupted_attempts": 1,
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
        self.assertEqual(metrics["exact_count_sessions"], 0)
        self.assertEqual(metrics["missed_movements"], 1)
        self.assertEqual(metrics["extra_movements"], 0)
        self.assertEqual(metrics["rep_recall"], 0.9)
        self.assertEqual(metrics["tracking_coverage_weighted"], 0.9)
        self.assertEqual(metrics["sets_with_interruptions"], 1)
        self.assertEqual(metrics["bar_setup_success_fraction"], 1.0)
        self.assertEqual(metrics["bar_setup_mean_attempts"], 2)
        self.assertEqual(metrics["false_camera_interruptions"], 1)
        self.assertEqual(metrics["camera_event_detection_fraction"], 1.0)

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
