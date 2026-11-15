"""Raw-counter v9 identity and unchanged evidence/event-scoring regressions.

Synthetic integration fixtures mock only native execution, not the validators,
source/media hashing, source clocks, reference checks or event matcher.
"""
import contextlib
import copy
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from Evaluation.tests.test_temporal import fixture, t


def current_fixture(exercise="pullUp"):
    ref, clip, pose, counter, observations, completion = fixture()
    ref["counter_policy_version"] = 8
    counter["summary"].update(policyVersion=9, armTracking="bothUsableArms")
    if exercise == "dip":
        ref.update(exercise="dip", event_definition="observed_top_bottom_top")
        clip["exercise"] = "parallel_bar_dip"
        counter["summary"].update(exercise="dip", armTracking="selectedGripWithAvailableArms")
    return ref, clip, pose, counter, observations, completion


class TemporalCounterPolicy9Tests(unittest.TestCase):
    def test_legacy_exact_policy_contract_remains_the_default(self):
        t.validate_run(*fixture())
        with self.assertRaisesRegex(ValueError, "Counter policy changed"):
            t.validate_run(*current_fixture())

    def test_both_production_methods_preserve_original_evidence(self):
        for exercise in ["pullUp", "dip"]:
            args = current_fixture(exercise)
            original = copy.deepcopy(args)
            t.validate_run(*args, production_policy9=True)
            self.assertEqual(args, original)
            self.assertEqual(args[0]["counter_policy_version"], 8)
            self.assertEqual(args[3]["summary"]["policyVersion"], 9)

    def test_wrong_or_malformed_execution_policies_fail(self):
        for version in [8, 10, 1, -1, 0, True, False, 9.0, "9", None]:
            with self.subTest(version=repr(version)):
                args = current_fixture()
                args[3]["summary"]["policyVersion"] = version
                with self.assertRaises(ValueError):
                    t.validate_run(*args, production_policy9=True)
        args = current_fixture()
        del args[3]["summary"]["policyVersion"]
        with self.assertRaises(ValueError):
            t.validate_run(*args, production_policy9=True)

    def test_relabelled_unknown_and_malformed_reference_policies_fail(self):
        for version in [9, 7, 1, 0, True, 8.0, "8", None]:
            with self.subTest(version=repr(version)):
                args = current_fixture()
                args[0]["counter_policy_version"] = version
                with self.assertRaises(ValueError):
                    t.validate_run(*args, production_policy9=True)
        args = current_fixture()
        del args[0]["counter_policy_version"]
        with self.assertRaises(ValueError):
            t.validate_run(*args, production_policy9=True)

    def test_wrong_candidate_and_missing_methods_fail(self):
        for exercise in ["pullUp", "dip"]:
            expected = current_fixture(exercise)[3]["summary"]["armTracking"]
            for method in [None, "", "bilateralDipImageMotion", "bothUsableArms", "selectedGripWithAvailableArms"]:
                if method == expected:
                    continue
                with self.subTest(exercise=exercise, method=method):
                    args = current_fixture(exercise)
                    args[3]["summary"]["armTracking"] = method
                    with self.assertRaises(ValueError):
                        t.validate_run(*args, production_policy9=True)
            args = current_fixture(exercise)
            del args[3]["summary"]["armTracking"]
            with self.assertRaises(ValueError):
                t.validate_run(*args, production_policy9=True)

    def test_other_exercises_are_not_an_implicit_policy_override(self):
        ref, _, _, counter, _, _ = current_fixture()
        for exercise in ["pushUp", "automatic", None]:
            ref["exercise"] = exercise
            with self.assertRaises(ValueError):
                t.validate_counter_policy(ref, counter, production_policy9=True)

    def test_all_original_evidence_gates_still_reject_corruption(self):
        mutations = [
            lambda r,c,p,k,o,f: k.update(frames=40),
            lambda r,c,p,k,o,f: o.pop(),
            lambda r,c,p,k,o,f: f.update(status="engine_failure"),
            lambda r,c,p,k,o,f: p.update(status="failed"),
            lambda r,c,p,k,o,f: r.update(media_sha256="b" * 64),
            lambda r,c,p,k,o,f: k["summary"].update(side="right"),
            lambda r,c,p,k,o,f: k["summary"].update(exercise="dip"),
            lambda r,c,p,k,o,f: k["referenceEdge"]["a"].update(x=11.0),
            lambda r,c,p,k,o,f: r.update(bar_reference_provenance=""),
            lambda r,c,p,k,o,f: r.pop("bar_reference_image_size"),
            lambda r,c,p,k,o,f: o[3].update(timebase="frame_index"),
            lambda r,c,p,k,o,f: o[3]["timestamp"].update(value=5),
            lambda r,c,p,k,o,f: k["summary"].update(observedMovements=3),
            lambda r,c,p,k,o,f: k["events"][0].update(sourceSeconds=1.155),
            lambda r,c,p,k,o,f: k["summary"].update(phase="returning"),
            lambda r,c,p,k,o,f: k["summary"].update(formVerification="valid"),
            lambda r,c,p,k,o,f: r.update(form_verification="valid"),
            lambda r,c,p,k,o,f: r.update(reviewed_without_counter_output=False),
            lambda r,c,p,k,o,f: r.update(ungradable_intervals=[{"seconds": [.8, 1.8], "reason": "erase miss"}]),
            lambda r,c,p,k,o,f: r["frame_pts_seconds"].__setitem__(2, .1),
            lambda r,c,p,k,o,f: k["events"][0].update(outcome="checked"),
        ]
        for index, change in enumerate(mutations):
            with self.subTest(index=index):
                args = current_fixture()
                change(*args)
                with self.assertRaises((ValueError, KeyError, TypeError)):
                    t.validate_run(*args, production_policy9=True)

    def test_event_scoring_and_tolerances_do_not_depend_on_counter_identity(self):
        args = current_fixture()
        t.validate_run(*args, production_policy9=True)
        original = fixture()[0]
        for times in [[], [1.1], [1.1, 3.1], [1.1, 2.0, 3.1], [.2, 2.0]]:
            self.assertEqual(t.score_events(args[0], times), t.score_events(original, times))


class TemporalCounterPolicy9IntegrationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.reference_path = self.root / "reference.json"
        self.manifest_path = self.root / "manifest.json"
        self.pose_output = self.root / "poses"
        self.output = self.root / "counts"
        ref, clip, status, self.counter, observations, completion = current_fixture()
        (self.root / "v.mp4").write_bytes(b"Authored synthetic fixture, not video or inference")
        clip.update(dataset="synthetic", split="development", source_group="synthetic-source",
                    subject_group="synthetic-subject", rights={"status": "approved", "evidence": "authored test", "public_outputs": True})
        clip["media"]["files"][0]["sha256"] = t.ev.digest(self.root / "v.mp4")
        ref["media_sha256"] = clip["media"]["files"][0]["sha256"]
        t.ev.write_json(self.manifest_path, {"schema_version": 1, "clips": [clip]})
        t.ev.write_json(self.reference_path, {"schema_version": 1,
                        "manifest_sha256": t.ev.digest(self.manifest_path), "clips": [ref]})
        directory = self.pose_output / clip["id"]
        directory.mkdir(parents=True)
        self.observation_path = directory / "observations.jsonl"
        for row in observations:
            row.update(backend="Apple Vision 2D", requestRevision=1, processingMilliseconds=1.0, people=[])
        self.observation_path.write_text("".join(json.dumps(row) + "\n" for row in observations))
        t.ev.write_json(directory / "completion.json", completion)
        source_path = "ios/HangInThere/Analysis/ExerciseCounter.swift"
        self.counter.update(input_sha256=t.ev.digest(self.observation_path), source_revision="c" * 40,
                            source_sha256={source_path: t.ev.digest(t.ev.ROOT / source_path)})
        status.update(id=clip["id"], media_sha256=[ref["media_sha256"]], observations_sha256=t.ev.digest(self.observation_path))
        t.ev.write_json(self.pose_output / "report.json", {"manifest_sha256": t.ev.digest(self.manifest_path),
                        "source_commit": "c" * 40, "source_files_sha256": self.counter["source_sha256"], "clips": [status]})
        self.reference_bytes = self.reference_path.read_bytes()
        self.manifest_bytes = self.manifest_path.read_bytes()

    def execute_fixture_counter(self, command, *, check):
        self.assertTrue(check)
        self.assertEqual(Path(command[0]), t.ev.ROOT / "scripts/count-replay.sh")
        self.assertEqual(Path(command[1]), self.observation_path)
        self.assertEqual(command[2:4], ["pullUp", "left"])
        t.ev.write_json(Path(command[4]), self.counter)

    def run_cli(self, *, strict=False, current=True):
        args = [str(self.reference_path), "--manifest", str(self.manifest_path), "--root", str(self.root),
                "--pose-output", str(self.pose_output), "--output", str(self.output), "--public-output"]
        if strict:
            args.append("--require-exact-events")
        if current:
            args.append("--production-policy9")
        with patch.object(t.subprocess, "run", side_effect=self.execute_fixture_counter), \
                contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            return t.main(args)

    def test_full_scoring_records_both_identities_without_rewriting_inputs(self):
        self.assertEqual(self.run_cli(strict=True), 0)
        report = t.ev.read_json(self.output / "report.json")
        self.assertEqual(report["counter_policy_version"], 9)
        self.assertEqual(report["reference_counter_policy_version"], 8)
        self.assertEqual(report["counter_policy_contract"], "production_v9_against_frozen_v8")
        self.assertEqual(report["reference_sha256"], t.ev.digest(self.reference_path))
        self.assertTrue(report["all_events_matched"])
        self.assertEqual(report["clips"][0]["counter_summary"]["policyVersion"], 9)
        self.assertEqual(self.reference_path.read_bytes(), self.reference_bytes)
        self.assertEqual(self.manifest_path.read_bytes(), self.manifest_bytes)

    def test_zero_movements_preserve_all_misses_and_fatal_strict_exit(self):
        self.counter["events"] = []
        self.counter["summary"]["observedMovements"] = 0
        self.assertEqual(self.run_cli(strict=True), 3)
        report = t.ev.read_json(self.output / "report.json")
        self.assertFalse(report["all_events_matched"])
        self.assertEqual(report["clips"][0]["event_metrics"]["false_negatives"], 2)
        self.assertEqual(self.reference_path.read_bytes(), self.reference_bytes)

    def test_extra_event_is_still_fatal(self):
        self.counter["events"].insert(1, {"outcome": "movement", "sourceSeconds": 2.0, "reason": "synthetic extra"})
        self.counter["summary"]["observedMovements"] = 3
        self.assertEqual(self.run_cli(strict=True), 3)
        self.assertEqual(t.ev.read_json(self.output / "report.json")["clips"][0]["event_metrics"]["false_positives"], 1)

    def test_diagnostic_exit_does_not_claim_accuracy(self):
        self.counter["events"] = []
        self.counter["summary"]["observedMovements"] = 0
        self.assertEqual(self.run_cli(), 0)
        self.assertFalse(t.ev.read_json(self.output / "report.json")["all_events_matched"])

    def test_default_cli_still_rejects_policy_mismatch(self):
        self.assertEqual(self.run_cli(current=False), 2)
        self.assertFalse((self.output / "report.json").exists())

    def test_changed_counter_source_is_fatal(self):
        self.counter["source_sha256"]["ios/HangInThere/Analysis/ExerciseCounter.swift"] = "f" * 64
        self.assertEqual(self.run_cli(), 2)
        self.assertFalse((self.output / "report.json").exists())

    def test_changed_pose_counter_revision_is_fatal(self):
        self.counter["source_revision"] = "d" * 40
        self.assertEqual(self.run_cli(), 2)

    def test_changed_observations_are_fatal(self):
        self.observation_path.write_text(self.observation_path.read_text() + "\n")
        self.assertEqual(self.run_cli(), 2)

    def test_changed_media_is_fatal(self):
        (self.root / "v.mp4").write_bytes(b"modified")
        self.assertEqual(self.run_cli(), 2)

    def test_existing_output_is_never_overwritten(self):
        self.assertEqual(self.run_cli(), 0)
        before = (self.output / "report.json").read_bytes()
        self.assertEqual(self.run_cli(), 2)
        self.assertEqual((self.output / "report.json").read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
