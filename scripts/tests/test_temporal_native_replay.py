import copy
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import temporal_native_replay as replay


def fixture():
    pts = [i / 10 for i in range(7)]
    ref = dict(schema_version=1, id="dip", exercise="dip", side="right", event_definition="observed_top_bottom_top",
        media_sha256="e" * 64, frame_pts_seconds=pts, span_seconds=[0, .7], events=[[.4, .5]],
        ungradable_intervals=[], tolerance_seconds=.05, form_verification="unverified",
        reviewed_without_counter_output=True, provenance="authored test", bar_reference_edge=None, counter_policy_version=8)
    reference = dict(schema_version=1, manifest_sha256="d" * 64, clips=[ref])
    case = dict(id="dip", media=dict(path="videos/dip.mp4", sha256="e" * 64), frames=7,
        source_pts_seconds=pts, source_span_seconds=[0, .7], selected_exercise="dip", tracking_side="right")
    manifest = dict(schema_version=1, scope="public_repetition_diagnostic", held_out_qualification=False,
        source_reference_sha256="a" * 64, source_manifest_sha256="d" * 64, cases=[case])
    quality = dict(schema_version=1, exercise="dip", unique_source_frames=7, people_count_histogram={"1": 7},
        single_person_frames=7, both_arm_frames=7, apparatus_search_frames=7, starting_pose_candidate_frames=7,
        longest_starting_pose_candidate_seconds=.6, left_arm_status_counts={"available": 7}, right_arm_status_counts={"available": 7})
    row = dict(id="dip", sha256="e" * 64, selected_exercise="dip", tracking_side="right", status="processed",
        frames=7, source_clock_verified=True, acceptance_seconds=[.4], completed_seconds=[.6],
        progress_evidence=dict(schema_version=1, source_prefix_verified=True, observed_source_end_seconds=.6), pose_quality=quality)
    native = dict(schema_version=1, platform="apple_host", backend="Apple Vision 2D", source_revision="c" * 40,
        manifest_sha256="b" * 64, counter_policy_version=8, request_revision=1, progress_evidence_version=1,
        replay_configuration_version=1, cases=[row])
    return reference, manifest, native


class TemporalNativeReplayTests(unittest.TestCase):
    def setUp(self):
        self.reference, self.manifest, self.native = fixture()

    def score(self):
        return replay.score(self.reference, self.manifest, self.native, "b" * 64, "a" * 64)

    def test_selected_dip_uses_unchanged_reference_events(self):
        report = self.score()
        self.assertTrue(report["all_events_matched"])
        self.assertEqual(report["clips"][0]["event_metrics"]["true_positives"], 1)
        self.assertEqual(report["clips"][0]["completed_cycle_seconds"], [.6])
        self.assertIn("not live Auto", report["scope"])

    def test_exercise_and_side_misrouting_are_rejected(self):
        for field, value in [("selected_exercise", "pullUp"), ("tracking_side", "left")]:
            with self.subTest(field=field):
                before = copy.deepcopy(self.native)
                self.native["cases"][0][field] = value
                with self.assertRaises(ValueError): self.score()
                self.native = before

    def test_unavailable_setup_keeps_all_misses_and_is_not_a_successful_zero(self):
        row = self.native["cases"][0]
        row.update(status="setupUnavailable", acceptance_seconds=[], completed_seconds=[])
        report = self.score()
        self.assertTrue(report["all_inputs_processed"])
        self.assertFalse(report["all_events_matched"])
        self.assertEqual(report["clips"][0]["event_metrics"]["false_negatives"], 1)
        self.reference["clips"][0]["events"] = []
        self.assertFalse(self.score()["all_events_matched"], "No setup cannot qualify a negative capture")
        row["status"] = "processed"
        self.assertTrue(self.score()["all_events_matched"])

    def test_unavailable_setup_cannot_publish_reps(self):
        self.native["cases"][0]["status"] = "setupUnavailable"
        with self.assertRaises(ValueError): self.score()

    def test_interrupted_prefix_cannot_publish_a_future_return_or_claim_success(self):
        row = self.native["cases"][0]
        row.update(status="interrupted", frames=5, source_clock_verified=False)
        row["progress_evidence"]["observed_source_end_seconds"] = .4
        with self.assertRaises(ValueError): self.score()
        row["completed_seconds"] = []
        report = self.score()
        self.assertFalse(report["all_inputs_processed"])
        self.assertFalse(report["all_events_matched"])
        self.assertEqual(report["clips"][0]["event_metrics"]["true_positives"], 1)

    def test_binding_frame_clock_and_pipeline_mutations_fail(self):
        mutations = [lambda n: n.update(manifest_sha256="f" * 64),
            lambda n: n.update(counter_policy_version=9),
            lambda n: n.update(platform="android_emulator"),
            lambda n: n["cases"][0].update(sha256="f" * 64),
            lambda n: n["cases"][0].update(frames=6),
            lambda n: n.update(cases=[])]
        for mutate in mutations:
            self.native = fixture()[2]; mutate(self.native)
            with self.assertRaises(ValueError): self.score()

    def test_raw_quality_cannot_invent_arm_visibility_or_omit_people(self):
        mutations = [lambda q: q.update(people_count_histogram={"1": 6}),
            lambda q: q.update(both_arm_frames=8),
            lambda q: q.update(single_person_frames=5),
            lambda q: q.update(apparatus_search_frames=3),
            lambda q: q.update(right_arm_status_counts={"available": 6}),
            lambda q: q.update(longest_starting_pose_candidate_seconds=10)]
        for mutate in mutations:
            self.native = fixture()[2]; mutate(self.native["cases"][0]["pose_quality"])
            with self.assertRaises(ValueError): self.score()

    def test_raw_quality_requires_full_coverage_and_compatible_arm_statuses(self):
        mutations = [lambda q: q.update(unique_source_frames=0, people_count_histogram={},
                single_person_frames=0, both_arm_frames=0, apparatus_search_frames=0,
                starting_pose_candidate_frames=0, left_arm_status_counts={}, right_arm_status_counts={}),
            lambda q: q.update(left_arm_status_counts={"missingJoint": 7}, right_arm_status_counts={"multiplePeople": 7}),
            lambda q: q.update(both_arm_frames=0, apparatus_search_frames=0, starting_pose_candidate_frames=0,
                longest_starting_pose_candidate_seconds=0),
            lambda q: q.update(starting_pose_candidate_frames=1)]
        for mutate in mutations:
            with self.subTest(mutation=mutations.index(mutate)):
                self.native = fixture()[2]; mutate(self.native["cases"][0]["pose_quality"])
                with self.assertRaises(ValueError): self.score()

    def test_interrupted_raw_observations_must_cover_the_verified_prefix(self):
        row = self.native["cases"][0]
        row.update(status="interrupted", frames=5, source_clock_verified=False, completed_seconds=[])
        row["progress_evidence"]["observed_source_end_seconds"] = .4
        self.assertFalse(self.score()["all_inputs_processed"], "Acquisition may have observed beyond the counting prefix")
        row["pose_quality"].update(unique_source_frames=4, people_count_histogram={"1": 4},
            single_person_frames=4, both_arm_frames=4, apparatus_search_frames=4,
            starting_pose_candidate_frames=4, left_arm_status_counts={"available": 4}, right_arm_status_counts={"available": 4})
        with self.assertRaises(ValueError): self.score()

    def test_compiled_provenance_requires_complete_current_checkout_inputs(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for name in replay.REPLAY_SOURCE_FILES:
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("authored source fixture: " + name)
            native = root / "native.json"; replay.ev.write_json(native, self.native)
            hashes = {name: replay.ev.digest(root / name) for name in replay.REPLAY_SOURCE_FILES}
            hashes[replay.REPLAY_FRAMEWORK] = "f" * 64
            provenance = dict(source_revision=self.native["source_revision"], source_dirty=True,
                native_report_sha256=replay.ev.digest(native), executable_sha256="f" * 64, source_sha256=hashes)
            sidecar = Path(str(native) + ".provenance.json")
            replay.ev.write_json(sidecar, provenance)
            evidence = replay.compiled_provenance(native, self.native, root)
            self.assertTrue(evidence["source_dirty"])
            self.assertEqual(evidence["provenance_sha256"], replay.ev.digest(sidecar))
            for invalid in [{"unrelated.txt": "a" * 64},
                            {k: v for k, v in hashes.items() if k != replay.REPLAY_FRAMEWORK},
                            dict(hashes, **{"../outside.swift": "a" * 64}),
                            dict(hashes, **{"Evaluation//PublicWorkoutReplay.swift": "a" * 64}),
                            dict(hashes, **{"Evaluation/PublicWorkoutReplay.swift": "a" * 64})]:
                provenance["source_sha256"] = invalid; replay.ev.write_json(sidecar, provenance)
                with self.assertRaises(ValueError): replay.compiled_provenance(native, self.native, root)

    def test_preparation_preserves_media_pts_labels_and_explicit_dip_selection(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "source"; source.mkdir()
            (source / "dip.mp4").write_bytes(b"authored non-video bytes; media preflight mocked only")
            sha = replay.ev.digest(source / "dip.mp4")
            clip = dict(id="dip", exercise="parallel_bar_dip", media=dict(kind="video", expected_frames=7,
                files=[dict(path="dip.mp4", sha256=sha)]))
            original = source / "manifest.json"; replay.ev.write_json(original, dict(schema_version=1, clips=[clip]))
            reference = copy.deepcopy(self.reference)
            reference["manifest_sha256"] = replay.ev.digest(original)
            reference["clips"][0]["media_sha256"] = sha
            labels = source / "reference.json"; replay.ev.write_json(labels, reference)
            before = labels.read_bytes()
            with patch.object(replay.ev, "validate_manifest", return_value=[clip]), patch.object(replay.ev, "preflight", return_value="ready"):
                result = replay.prepare(labels, original, source, root / "prepared")
            self.assertEqual(result["cases"][0]["selected_exercise"], "dip")
            self.assertEqual(result["cases"][0]["tracking_side"], "right")
            self.assertEqual(result["cases"][0]["source_pts_seconds"], self.reference["clips"][0]["frame_pts_seconds"])
            self.assertEqual((root / "prepared/videos/dip.mp4").read_bytes(), (source / "dip.mp4").read_bytes())
            self.assertEqual(labels.read_bytes(), before)

    def test_strict_cli_keeps_accuracy_failure_and_verifies_compiled_report_binding(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            labels, manifest, native = root / "reference.json", root / "manifest.json", root / "native.json"
            replay.ev.write_json(labels, self.reference)
            self.manifest["source_reference_sha256"] = replay.ev.digest(labels)
            replay.ev.write_json(manifest, self.manifest)
            self.native["manifest_sha256"] = replay.ev.digest(manifest)
            self.native["cases"][0].update(status="setupUnavailable", acceptance_seconds=[], completed_seconds=[])
            replay.ev.write_json(native, self.native)
            provenance = dict(source_revision=self.native["source_revision"], source_dirty=True,
                native_report_sha256=replay.ev.digest(native), executable_sha256="f" * 64,
                source_sha256={name: replay.ev.digest(Path(__file__).resolve().parents[2] / name)
                    for name in replay.REPLAY_SOURCE_FILES})
            repository = Path(__file__).resolve().parents[2]
            provenance["source_sha256"].update({str(p.relative_to(repository)): replay.ev.digest(p)
                for folder in ["Analysis", "Capture", "Pose"]
                for p in (repository / "ios/HangInThere" / folder).glob("*.swift")})
            provenance["source_sha256"][replay.REPLAY_FRAMEWORK] = "f" * 64
            replay.ev.write_json(Path(str(native) + ".provenance.json"), provenance)
            args = ["temporal_native_replay", "score", "--reference", str(labels), "--manifest", str(manifest),
                "--native", str(native), "--output", str(root / "result.json"), "--require-exact-events"]
            with patch.object(replay.sys, "argv", args), patch.object(replay.sys, "stdout", io.StringIO()):
                self.assertEqual(replay.main(), 3)
            result = replay.ev.read_json(root / "result.json")
            self.assertFalse(result["all_events_matched"])
            self.assertTrue(result["compiled_native_provenance"]["source_dirty"])
            # A modified report cannot reuse the original compiled-run binding.
            self.native["cases"][0]["automatic_bar_state"] = "tampered"
            replay.ev.write_json(native, self.native)
            args[args.index("--output") + 1] = str(root / "tampered.json")
            with patch.object(replay.sys, "argv", args), patch.object(replay.sys, "stderr", io.StringIO()):
                self.assertEqual(replay.main(), 2)
            self.assertFalse((root / "tampered.json").exists())


if __name__ == "__main__":
    unittest.main()
