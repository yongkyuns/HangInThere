import json
from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[2]
MANIFEST = ROOT / "HangInThereTests" / "Fixtures" / "corpus.json"


class VideoCorpusManifestTests(unittest.TestCase):
    def setUp(self):
        self.manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
        self.cases = self.manifest["cases"]

    def test_every_case_has_unique_id_and_reviewed_expectations(self):
        ids = [case["id"] for case in self.cases]
        self.assertEqual(len(ids), len(set(ids)))
        self.assertGreaterEqual(len(ids), 5)
        for case in self.cases:
            self.assertIn(case["tier"], {"count-qualified", "tracking-qualified", "stress-coverage"})
            self.assertIn("visual_review", case)
            expectation = case["expectation"]
            self.assertGreater(expectation["minimum_people_fraction"], 0)
            self.assertLessEqual(expectation["minimum_people_fraction"], 1)
            self.assertGreater(expectation["minimum_any_arm_fraction"], 0)
            self.assertLessEqual(expectation["minimum_any_arm_fraction"], 1)

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

    def test_count_qualified_corpus_has_two_distinct_views(self):
        count_cases = [case for case in self.cases if case["tier"] == "count-qualified"]
        self.assertGreaterEqual(len(count_cases), 2)
        ids = {case["id"] for case in count_cases}
        self.assertIn("iwakuni-standard-rear-oblique", ids)
        indoor = next(case for case in count_cases if case["id"] == "fitnessscape-standard-indoor")
        self.assertEqual(indoor["count_expectation"]["expected_observed_movements"], 1)
        self.assertEqual(len(indoor["count_expectation"]["bar_reference_edge"]), 4)

    def test_dip_gap_is_explicit_not_silently_substituted(self):
        gap = self.manifest["known_gap"]
        self.assertEqual(gap["exercise"], "dip")
        self.assertIn("parallel-bar", gap["status"])
        self.assertNotIn("chair", " ".join(case["id"] for case in self.cases).lower())


if __name__ == "__main__":
    unittest.main()
