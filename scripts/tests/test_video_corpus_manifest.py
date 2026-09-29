import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "HangInThereTests" / "Fixtures" / "corpus.json"
RESULT = ROOT / "HangInThereTests" / "Fixtures" / "dip-heldout-result.json"


class VideoCorpusManifestTests(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.cases = self.manifest["cases"]
        self.dip_result = json.loads(RESULT.read_text(encoding="utf-8"))

    def test_every_case_has_unique_id_and_reviewed_expectations(self):
        ids = [case["id"] for case in self.cases]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreaterEqual(len(ids), 6)
        for case in self.cases:
            self.assertIn(case["tier"], {"count-qualified", "tracking-qualified", "stress-coverage"})
            self.assertIn("visual_review", case)
            expectation = case["expectation"]
            self.assertGreater(expectation["minimum_people_fraction"], 0)
            self.assertLessEqual(expectation["minimum_people_fraction"], 1)
            self.assertGreater(expectation["minimum_any_arm_fraction"], 0)
            self.assertLessEqual(expectation["minimum_any_arm_fraction"], 1)
            if "minimum_multiple_people_frames" in expectation:
                self.assertIsInstance(expectation["minimum_multiple_people_frames"], int)
                self.assertGreater(expectation["minimum_multiple_people_frames"], 0)

    def test_downloaded_sources_are_integrity_pinned(self):
        for case in self.cases:
            if case["source_kind"] != "download":
                continue
            self.assertIsInstance(case["source_bytes"], int)
            self.assertGreater(case["source_bytes"], 0)
            sha = case["source_sha256"]
            self.assertEqual(len(sha), 64)
            int(sha, 16)
            self.assertTrue(case["download_url"].startswith("https://"))
            self.assertIn("license", case)
            self.assertIn("credit", case)

    def test_generated_derivatives_have_explicit_frame_count_pins(self):
        for case in self.cases:
            recipe = case.get("recipe")
            if recipe is not None:
                self.assertIsInstance(recipe.get("expected_frame_count"), int)
                self.assertGreater(recipe["expected_frame_count"], 0)
                self.assertGreater(recipe["frames_per_second"], 0)
                self.assertGreater(recipe["duration_seconds"], 0)

    def test_count_qualified_corpus_retains_independent_pullup_views(self):
        count_cases = [case for case in self.cases if case["tier"] == "count-qualified"]
        self.assertGreaterEqual(len(count_cases), 2)
        ids = {case["id"] for case in count_cases}
        self.assertIn("iwakuni-standard-rear-oblique", ids)
        self.assertIn("fitnessscape-standard-indoor", ids)

        indoor = next(case for case in count_cases if case["id"] == "fitnessscape-standard-indoor")
        self.assertEqual(indoor["count_expectation"]["expected_observed_movements"], 1)
        self.assertEqual(indoor["count_expectation"]["expected_partial_attempts"], 0)
        self.assertEqual(len(indoor["count_expectation"]["bar_reference_edge"]), 4)

        self.assertFalse(
            any(case["exercise"] == "dip" and case["tier"] == "count-qualified" for case in self.cases),
            "Failed held-out dip footage must not remain in the passing corpus.",
        )

    def test_crowded_pullup_exercises_real_multi_person_safety(self):
        case = next(case for case in self.cases if case["id"] == "yokota-crowded-pullup")
        self.assertEqual(case["tier"], "stress-coverage")
        self.assertEqual(case["recipe"]["expected_frame_count"], 20)
        self.assertGreaterEqual(case["expectation"]["minimum_multiple_people_frames"], 1)
        self.assertIn("multi-person", case["environment_tags"])
        self.assertIn("foreground-occlusion", case["environment_tags"])
        self.assertNotIn("count_expectation", case)

    def test_consumed_dip_holdout_is_recorded_without_relaxation(self):
        gap = self.manifest["known_gap"]
        self.assertEqual(gap["exercise"], "dip")
        status = gap["status"].lower()
        self.assertIn("not held-out-qualified", status)
        self.assertIn("9/9", status)
        self.assertIn("0/3", status)
        self.assertIn("consumed", status)
        self.assertIn("new untouched held-out", status)
        self.assertNotIn("chair", " ".join(case["id"] for case in self.cases).lower())

        r = self.dip_result
        self.assertEqual(r["status"], "failed-heldout-qualification")
        self.assertEqual(r["tested_commit"], "c8b5f9068804839fed13e922c96f8090abc389c8")
        self.assertEqual(r["counter_policy_version"], 4)
        self.assertEqual(r["prepared_derivative"]["frame_count"], 27)
        self.assertEqual(r["locked_expectation"]["expected_observed_movements"], 3)
        self.assertEqual(r["observed"]["observed_movements"], 0)
        self.assertEqual(r["observed"]["partial_attempts"], 0)
        self.assertEqual(r["observed"]["interrupted_attempts"], 1)
        self.assertLess(r["observed"]["people_fraction"], r["locked_expectation"]["minimum_people_fraction"])
        self.assertLess(r["observed"]["any_arm_fraction"], r["locked_expectation"]["minimum_any_arm_fraction"])
        self.assertIn("Do not change counter policy v4", r["policy"])


if __name__ == "__main__":
    unittest.main()
