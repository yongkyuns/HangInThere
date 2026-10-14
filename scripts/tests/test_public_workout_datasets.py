import copy
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

from scripts import public_workout_datasets as public


def countix_archive(train="", val="", test=""):
    common = "video_id,kinetics_start,kinetics_end,repetition_start,repetition_end,count"
    data = io.BytesIO()
    with tarfile.open(fileobj=data, mode="w:gz") as archive:
        for split, rows in [("train", train), ("val", val), ("test", test)]:
            header = "class," + common if split != "test" else common
            payload = (header + "\n" + rows).encode()
            item = tarfile.TarInfo(f"countix/countix_{split}.csv"); item.size = len(payload)
            archive.addfile(item, io.BytesIO(payload))
    return data.getvalue()


def repcount_csv(row="pull_up,stu1_1.mp4,2,0,2,2,5,\n"):
    return ("type,name,count,L1,L2,L3,L4,L5\n" + row).encode()


def corpus():
    return dict(schema_version=1, scope="public_repetition_diagnostic", held_out_qualification=False,
        boundary_tolerance_seconds=.5, cases=[dict(id="a", dataset="repcount_a", published_split="train",
        media=dict(path="videos/a.mp4", sha256="a" * 64), frames=6, source_span_seconds=[0, 3],
        labels=dict(expected_count=2, count_window_seconds=[0, 3], cycle_end_seconds=[1, 2]))])


def native(status="processed", accepted=None, completed=None):
    return dict(schema_version=1, manifest_sha256="b" * 64, source_revision="c" * 40,
        platform="apple_host", backend="Apple Vision 2D", exercise_selection="dataset selected", cases=[dict(
        id="a", sha256="a" * 64, status=status, frames=6, source_clock_verified=True,
        selected_exercise="pullUp", acceptance_seconds=[.6, 1.6] if accepted is None else accepted,
        completed_seconds=[1, 2] if completed is None else completed)])


class PublicDatasetLabelsTests(unittest.TestCase):
    def test_countix_keeps_two_segments_in_one_source_and_absolute_origin(self):
        report, rows = public.countix_rows(countix_archive(train=
            "pull ups,abcdefghijk,20,30,20,24,2\npull ups,abcdefghijk,20,30,26,30,2\n"))
        self.assertEqual(len(rows), 2)
        self.assertEqual(rows[1]["repetition_seconds"], [26, 30])
        self.assertEqual(report["selected_counts"], {"pull_up": 2})

    def test_unclassified_test_does_not_become_negative_or_inferred_pullup(self):
        report, rows = public.countix_rows(countix_archive(test="abcdefghijk,0,10,0,9,3\n"))
        self.assertEqual(rows, [])
        self.assertEqual(report["unclassified_rows"], 1)

    def test_duplicate_period_is_rejected(self):
        row = "pull ups,abcdefghijk,0,10,0,9,3\n"
        with self.assertRaises(ValueError): public.countix_rows(countix_archive(val=row * 2))

    def test_invalid_countix_values(self):
        for bounds in ["0,10,0,12,2", "0,10,nan,9,2", "0,10,5,5,2", "0,10,0,9,2.5", "0,10,0,9,0"]:
            with self.subTest(bounds=bounds), self.assertRaises(ValueError):
                public.countix_rows(countix_archive(val="pull ups,abcdefghijk," + bounds + "\n"))

    def test_author_frame_pairs_and_split_scheme_preserved(self):
        row = public.repcount_rows(repcount_csv(), "train")[0]
        self.assertEqual(row["cycle_frames"], [[0, 2], [2, 5]])
        self.assertEqual(row["split_scheme"], "author_open_set")
        self.assertEqual(row["split"], "train")

    def test_bad_repcount_markers_or_source_path_are_rejected(self):
        for row in ["pull_up,../x.mp4,2,0,2,2,5,\n", "pull_up,stu1.mp4,2,0,2.5,2,5,\n",
                    "pull_up,stu1.mp4,2,0,3,2,5,\n", "pull_up,stu1.mp4,1,0,2,2,5,\n",
                    "pull_up,stu1.mp4,2,0,2,2,5,6\n"]:
            with self.subTest(row=row), self.assertRaises(ValueError): public.repcount_rows(repcount_csv(row), "train")

    def test_author_source_cannot_cross_splits(self):
        with self.assertRaises(ValueError):
            public.catalog(countix_archive(), {"train": repcount_csv(), "test": repcount_csv()})

    def test_repcount_markers_use_variable_source_pts_not_fps(self):
        row = public.repcount_rows(repcount_csv(), "train")[0]
        labels = public.timed_labels(row, [0, .1, .9, 1.1, 1.5, 2.8], 3)
        self.assertEqual(labels["cycle_end_seconds"], [.9, 2.8])

    def test_native_frame_marker_past_eof_fails(self):
        with self.assertRaises(ValueError):
            public.timed_labels(public.repcount_rows(repcount_csv(), "train")[0], [0, .1, .9], 1)

    def test_countix_absolute_to_actual_container_clock(self):
        labels = public.timed_labels(dict(dataset="countix", count=2,
            kinetics_seconds=[20, 30], repetition_seconds=[21, 24]), [5, 6, 8], 15)
        self.assertEqual(labels["count_window_seconds"], [6, 9])
        self.assertIsNone(labels["cycle_end_seconds"])

    def test_shortened_countix_video_is_not_silently_relabelled(self):
        with self.assertRaises(ValueError):
            public.timed_labels(dict(dataset="countix", count=2, kinetics_seconds=[20, 30],
                repetition_seconds=[21, 30]), [0, 1, 8], 9)

    def test_source_clock_uses_rational_ticks_and_android_truncation(self):
        probe = dict(streams=[dict(time_base="1/3", duration_ts=4)],
                     frames=[dict(best_effort_timestamp=v) for v in [0, 1, 3]])
        with patch.object(public.subprocess, "check_output", return_value=json.dumps(probe)):
            seconds, micros, end = public.source_clock(Path("test.mp4"))
        self.assertEqual(seconds, [0, 1 / 3, 1])
        self.assertEqual(micros, [0, 333333, 1000000])
        self.assertEqual(end, 4 / 3)

    def test_nonmonotonic_source_ticks_are_rejected(self):
        probe = dict(streams=[dict(time_base="1/30", duration_ts=4)],
                     frames=[dict(best_effort_timestamp=v) for v in [0, 1, 1]])
        with patch.object(public.subprocess, "check_output", return_value=json.dumps(probe)), self.assertRaises(ValueError):
            public.source_clock(Path("test.mp4"))


class PublicDatasetScoreTests(unittest.TestCase):
    def score(self, result=None, manifest=None):
        return public.score(manifest or corpus(), result or native(), "b" * 64)

    def test_actual_completed_cycles_and_timing_are_scored(self):
        report = self.score()
        self.assertTrue(report["all_exact_completed_counts"])
        self.assertEqual(report["cases"][0]["cycle_end_timing_diagnostic"]["true_positives"], 2)
        self.assertIn("no strict-form", report["scope"])

    def test_accepted_but_unreturned_attempt_is_not_a_completed_rep(self):
        report = self.score(native(completed=[1]))
        self.assertEqual(report["summary"]["acceptance_seconds"]["exact_clips"], 1)
        self.assertEqual(report["summary"]["completed_seconds"]["exact_clips"], 0)
        self.assertEqual(report["cases"][0]["cycle_end_timing_diagnostic"]["false_negatives"], 1)

    def test_countix_only_scores_annotated_segment_and_no_invented_boundaries(self):
        manifest = corpus(); case = manifest["cases"][0]; case["dataset"] = "countix"
        case["labels"].update(count_window_seconds=[1, 2], expected_count=1, cycle_end_seconds=None)
        report = self.score(native(completed=[.5, 1.5, 2.5]), manifest)
        self.assertTrue(report["all_exact_completed_counts"])
        self.assertIsNone(report["cases"][0]["cycle_end_timing_diagnostic"])

    def test_setup_unavailable_retains_full_corpus_denominator_and_misses(self):
        report = self.score(native(status="setupUnavailable", accepted=[], completed=[]))
        self.assertTrue(report["all_inputs_processed"])
        self.assertFalse(report["all_exact_completed_counts"])
        self.assertEqual(report["summary"]["completed_seconds"]["mean_absolute_error"], 2)

    def test_interrupted_replay_preserves_partial_measurements_without_exact_claim(self):
        result = native(status="interrupted"); result["cases"][0].update(frames=4, source_clock_verified=False)
        report = self.score(result)
        self.assertFalse(report["all_inputs_processed"])
        self.assertFalse(report["all_exact_completed_counts"])
        self.assertEqual(report["cases"][0]["metrics"]["completed_seconds"]["observed"], 2)

    def test_failed_run_cannot_smuggle_measurements(self):
        with self.assertRaises(ValueError): self.score(native(status="failed"))

    def test_wrong_exercise_does_not_pass_even_if_count_matches(self):
        result = native(); result["cases"][0]["selected_exercise"] = "dip"
        self.assertFalse(self.score(result)["all_exact_completed_counts"])

    def test_frame_clock_or_media_mismatch_is_rejected(self):
        for key, value in [("frames", 5), ("source_clock_verified", False), ("sha256", "d" * 64)]:
            result = native(); result["cases"][0][key] = value
            with self.subTest(key=key), self.assertRaises(ValueError): self.score(result)

    def test_stale_manifest_and_omitted_or_duplicate_results_are_rejected(self):
        for mutate in [lambda r:r.update(manifest_sha256="d" * 64), lambda r:r.update(cases=[]),
                       lambda r:r["cases"].append(copy.deepcopy(r["cases"][0]))]:
            result = native(); mutate(result)
            with self.assertRaises(ValueError): self.score(result)

    def test_nan_reordered_or_post_eof_events_are_rejected(self):
        for events in [[float("nan")], [2, 1], [3]]:
            with self.subTest(events=events), self.assertRaises(ValueError): self.score(native(completed=events))

    def test_duplicate_detections_remain_false_positives(self):
        report = self.score(native(completed=[1, 1, 2]))
        self.assertEqual(report["cases"][0]["cycle_end_timing_diagnostic"]["false_positives"], 1)

    def test_cannot_upgrade_to_heldout_or_physical_evidence(self):
        manifest = corpus(); manifest["held_out_qualification"] = True
        with self.assertRaises(ValueError): self.score(manifest=manifest)
        result = native(); result["platform"] = "physical_ios"
        with self.assertRaises(ValueError): self.score(result)


class PublicDatasetArtifactTests(unittest.TestCase):
    def test_immutable_report_output(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "report.json"; public.write_new(path, {"first": 1})
            with self.assertRaises(FileExistsError): public.write_new(path, {"second": 2})
            self.assertEqual(json.loads(path.read_text()), {"first": 1})

    def test_mirror_alias_is_not_countix_and_author_mismatch_is_not_accepted(self):
        case = dict(id="a", source_name="stu1.mp4", count=1, cycle_frames=[[0, 2]], bytes=3, sha256="a" * 64,
                    mirror=dict(split="validation", row_index=0, revision="b" * 40))
        preview = dict(rows=[dict(row_idx=0, truncated_cells=[], row=dict(source_name="stu1.mp4", action_type="pull_up",
            split="validation", count=2, cycle_bounds_json="[0,2,2,4]", video_size_bytes=3))])
        with tempfile.TemporaryDirectory() as directory, patch.object(public, "fetch", return_value=json.dumps(preview).encode()):
            with self.assertRaises(ValueError): public.mirror_video(case, Path(directory), True)

    def test_byte_pins_are_checked_before_existing_cache_is_read(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory); (path / "source").write_bytes(b"abc")
            with self.assertRaises(ValueError):
                public.pinned_source(dict(id="source", bytes=2, sha256="a" * 64), path, False)


if __name__ == "__main__":
    unittest.main()
