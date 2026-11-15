"""Integration with the unchanged legacy evidence validator and event matcher."""
import copy
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import temporal_native_replay as legacy
from scripts import temporal_policy9_replay as policy9
from scripts.tests.test_temporal_native_replay import fixture


class TemporalPolicy9ReplayTests(unittest.TestCase):
    def setUp(self):
        self.reference, self.manifest, self.native = fixture()
        self.native["counter_policy_version"] = 9

    def score(self):
        return policy9.score(self.reference, self.manifest, self.native, "b" * 64, "a" * 64)

    def test_policy_and_reference_identities_remain_distinct(self):
        before = copy.deepcopy((self.reference, self.manifest, self.native))
        result = self.score()
        self.assertEqual(result["counter_policy_version"], 9)
        self.assertEqual(result["reference_counter_policy_version"], 8)
        self.assertEqual(result["replay_configuration_version"], 1)
        self.assertIn("not live Auto", result["scope"])
        self.assertEqual((self.reference, self.manifest, self.native), before)

    def test_same_authored_events_use_the_same_matcher_not_new_tolerances(self):
        native8 = copy.deepcopy(self.native)
        native8["counter_policy_version"] = 8
        baseline = legacy.score(self.reference, self.manifest, native8, "b" * 64, "a" * 64)
        current = self.score()
        self.assertEqual(current["clips"], baseline["clips"])
        self.assertEqual(current["all_events_matched"], baseline["all_events_matched"])
        self.assertEqual(current["all_inputs_processed"], baseline["all_inputs_processed"])

    def test_legacy_and_candidate_entrypoints_still_reject_policy9(self):
        for scorer in [legacy.score, legacy.score_candidate]:
            with self.assertRaises(ValueError):
                scorer(self.reference, self.manifest, self.native, "b" * 64, "a" * 64)

    def test_reference_policy_cannot_be_relabelled(self):
        self.reference["clips"][0]["counter_policy_version"] = 9
        with self.assertRaises(ValueError):
            self.score()

    def test_missing_setup_remains_an_accuracy_failure_with_all_original_misses(self):
        self.native["cases"][0].update(status="setupUnavailable", acceptance_seconds=[], completed_seconds=[])
        report = self.score()
        self.assertTrue(report["all_inputs_processed"])
        self.assertFalse(report["all_events_matched"])
        self.assertEqual(report["clips"][0]["event_metrics"]["false_negatives"], 1)
        self.reference["clips"][0]["events"] = []
        self.assertFalse(self.score()["all_events_matched"])

    def test_changed_source_bindings_frames_routing_and_visibility_fail(self):
        mutations = [lambda n: n.update(manifest_sha256="f" * 64),
                     lambda n: n.update(platform="android_emulator"),
                     lambda n: n.update(cases=[]),
                     lambda n: n["cases"][0].update(sha256="f" * 64),
                     lambda n: n["cases"][0].update(frames=6),
                     lambda n: n["cases"][0].update(tracking_side="left"),
                     lambda n: n["cases"][0].update(selected_exercise="pullUp"),
                     lambda n: n["cases"][0].update(source_clock_verified=False),
                     lambda n: n["cases"][0]["pose_quality"].update(both_arm_frames=8),
                     lambda n: n["cases"][0]["progress_evidence"].update(source_prefix_verified=False)]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)):
                self.native = fixture()[2]
                self.native["counter_policy_version"] = 9
                mutate(self.native)
                with self.assertRaises(ValueError):
                    self.score()

    def test_extra_or_missed_events_do_not_qualify(self):
        for times in [[], [.1, .4]]:
            self.native["cases"][0]["acceptance_seconds"] = times
            self.assertFalse(self.score()["all_events_matched"])

    def test_cli_keeps_fatal_accuracy_and_provenance_failures(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            reference, manifest, native = (root / name for name in ["reference.json", "manifest.json", "native.json"])
            legacy.ev.write_json(reference, self.reference)
            self.manifest["source_reference_sha256"] = legacy.ev.digest(reference)
            legacy.ev.write_json(manifest, self.manifest)
            self.native["manifest_sha256"] = legacy.ev.digest(manifest)
            self.native["cases"][0].update(status="setupUnavailable", acceptance_seconds=[], completed_seconds=[])
            legacy.ev.write_json(native, self.native)
            output = root / "strict.json"
            args = ["--reference", str(reference), "--manifest", str(manifest), "--native", str(native),
                    "--output", str(output), "--require-exact-events"]
            # The authored fixture has no compiled executable. Only its provenance
            # provider is mocked; all source/frame/event scoring remains real.
            with patch.object(legacy, "compiled_provenance", return_value={"synthetic_test": True}) as provenance:
                self.assertEqual(policy9.main(args), 3)
                provenance.assert_called_once()
            self.assertFalse(legacy.ev.read_json(output)["all_events_matched"])
            output.unlink()
            with patch.object(legacy, "compiled_provenance", side_effect=ValueError("Changed compiled source")):
                self.assertEqual(policy9.main(args), 2)
            self.assertFalse(output.exists())


if __name__ == "__main__":
    unittest.main()
