import copy
import io
import json
from pathlib import Path
import sys
import tarfile
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "scripts"))
import evaluation as ev
import inventory as inv


class EvaluationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        (self.root / "clip.mp4").write_bytes(b"synthetic test bytes, not footage")
        self.clip = {"id": "sample", "dataset": "synthetic", "exercise": "pull_up", "split": "test",
                     "source_group": "original-video", "subject_group": "subject-1",
                     "rights": {"status": "approved", "evidence": "original synthetic test", "public_outputs": True},
                     "media": {"kind": "video", "files": [{"path": "clip.mp4", "sha256": ev.digest(self.root / "clip.mp4")}]}}
        self.manifest = {"schema_version": 1, "clips": [self.clip]}
        self.reference = {"schema_version": 1, "coordinates": "upright_pixels_top_left", "independently_reviewed": True,
                          "provenance": "synthetic scoring test, NOT real-image accuracy", "media_sha256": [self.clip["media"]["files"][0]["sha256"]],
                          "frames": [{"frame_index": 0, "width": 640, "height": 480, "scale_pixels": 100, "endpoint": True,
                                      "points": {"leftShoulder": [100, 100], "leftElbow": [100, 200], "leftWrist": [200, 200]}}]}
        self.prediction = {"frameIndex": 0, "timebase": "source_pts", "timestamp": {"value": 0, "timescale": 100},
                           "imageSize": {"width": 320, "height": 240}, "backend": "Apple Vision 2D", "requestRevision": 1,
                           "processingMilliseconds": 1.0, "people": [{"landmarks": [
                               {"joint": key, "position": {"x": xy[0]/2, "y": xy[1]/2}, "confidence": 0.9}
                               for key, xy in self.reference["frames"][0]["points"].items()]}]}

    def tearDown(self):
        self.temp.cleanup()

    def test_valid_manifest_and_approved_hash(self):
        ev.validate_manifest(self.manifest, self.root)
        self.assertEqual(ev.preflight(self.clip, self.root), "ready")

    def test_pending_permission_does_not_inspect_missing_file(self):
        self.clip["rights"]["status"] = "pending"
        (self.root / "clip.mp4").unlink()
        self.assertEqual(ev.preflight(self.clip, self.root), "not_approved")

    def test_missing_and_wrong_hash_are_not_success(self):
        (self.root / "clip.mp4").write_bytes(b"changed")
        self.assertEqual(ev.preflight(self.clip, self.root), "integrity_failure")
        (self.root / "clip.mp4").unlink()
        self.assertEqual(ev.preflight(self.clip, self.root), "missing_input")

    def test_private_output_is_not_releasable(self):
        self.clip["rights"]["public_outputs"] = False
        self.assertEqual(ev.preflight(self.clip, self.root, True), "outputs_not_approved")

    def test_empty_corpus_duplicate_id_invalid_confidence(self):
        for manifest in ({"schema_version": 1, "clips": []},
                         {"schema_version": 1, "clips": [self.clip, self.clip]},
                         {**self.manifest, "confidence_threshold": float("nan")}):
            with self.assertRaises(ValueError):
                ev.validate_manifest(manifest, self.root)

    def test_unknown_subject_cannot_claim_test_split(self):
        self.clip["subject_group"] = None
        with self.assertRaises(ValueError):
            ev.validate_manifest(self.manifest, self.root)
        self.clip["split"] = "unassigned"
        ev.validate_manifest(self.manifest, self.root)

    def test_split_leakage_by_source_subject_or_media(self):
        for field in ("source_group", "subject_group", "media"):
            other = copy.deepcopy(self.clip)
            other.update(id="other", split="development", source_group="other", subject_group="other")
            other["media"]["files"][0]["sha256"] = "f" * 64
            other[field] = copy.deepcopy(self.clip[field])
            with self.assertRaises(ValueError):
                ev.validate_manifest({"schema_version": 1, "clips": [self.clip, other]}, self.root)

    def test_paths_absolute_parent_and_symlink_escape(self):
        for name in ("/etc/passwd", "../outside"):
            with self.assertRaises(ValueError):
                ev.asset_path(self.root, {"path": name, "sha256": "a" * 64})
        (self.root / "escape").symlink_to("/etc")
        with self.assertRaises(ValueError):
            ev.asset_path(self.root, {"path": "escape/passwd", "sha256": "a" * 64})

    def test_reject_model_labels_and_mismatched_media(self):
        ev.validate_reference(self.reference, self.clip)
        for field, value in (("independently_reviewed", False), ("media_sha256", ["f" * 64]),
                             ("coordinates", "normalized")):
            changed = {**self.reference, field: value}
            with self.assertRaises(ValueError):
                ev.validate_reference(changed, self.clip)

    def test_scaled_predictions_have_zero_pixel_and_angle_error(self):
        report = ev.score_pose(self.reference, [self.prediction])
        self.assertEqual(report["joint_pixels"]["leftElbow"]["mean"], 0)
        self.assertEqual(report["elbow_degrees"]["left"]["mean"], 0)
        self.assertEqual(report["endpoint_joint_pixels"]["measured_count"], 3)

    def test_known_pixel_error_and_normalized_denominator(self):
        self.prediction["people"][0]["landmarks"][0]["position"]["x"] += 5
        report = ev.score_pose(self.reference, [self.prediction])
        self.assertAlmostEqual(report["joint_pixels"]["leftShoulder"]["mean"], 10)
        self.assertAlmostEqual(report["normalized_joint_error"]["mean"], 0.1 / 3)

    def test_missing_and_ambiguous_people_reduce_coverage(self):
        for people in ([], self.prediction["people"] * 2):
            report = ev.score_pose(self.reference, [{**self.prediction, "people": people}])
            self.assertEqual(report["joint_pixels"]["leftElbow"]["coverage"], 0)
            self.assertIsNone(report["joint_pixels"]["leftElbow"]["mean"])
            self.assertEqual(report["elbow_degrees"]["left"]["reference_count"], 1)
            self.assertIsNone(report["elbow_degrees"]["left"]["mean"])

    def test_low_confidence_is_missing_not_zero_error(self):
        self.prediction["people"][0]["landmarks"][1]["confidence"] = 0
        report = ev.score_pose(self.reference, [self.prediction])
        self.assertEqual(report["joint_pixels"]["leftElbow"]["coverage"], 0)
        self.assertEqual(report["elbow_degrees"]["left"]["coverage"], 0)

    def test_wrong_aspect_and_reference_time_rejected(self):
        self.prediction["imageSize"] = {"width": 240, "height": 320}
        with self.assertRaises(ValueError):
            ev.score_pose(self.reference, [self.prediction])
        self.prediction["imageSize"] = {"width": 320, "height": 240}
        self.reference["frames"][0]["timestamp_seconds"] = 0.1
        with self.assertRaises(ValueError):
            ev.score_pose(self.reference, [self.prediction])

    def test_missing_or_duplicate_labelled_frame_fails(self):
        with self.assertRaises(ValueError):
            ev.score_pose(self.reference, [])
        with self.assertRaises(ValueError):
            ev.score_pose(self.reference, [self.prediction, self.prediction])

    def test_stream_rejects_invented_image_times_and_reordered_frames(self):
        path = self.root / "predictions.jsonl"
        path.write_text(json.dumps(self.prediction) + "\n")
        with self.assertRaises(ValueError):
            list(ev.observations(path, "images"))
        self.prediction["frameIndex"] = 1
        path.write_text(json.dumps(self.prediction) + "\n")
        with self.assertRaises(ValueError):
            list(ev.observations(path, "video"))

    def test_nonfinite_and_duplicate_json_keys_rejected(self):
        path = self.root / "input.json"
        for value in ('{"x": NaN}', '{"x":1,"x":2}'):
            path.write_text(value)
            with self.assertRaises(ValueError):
                ev.read_json(path)

    def test_runner_does_not_call_engine_for_unapproved_clip(self):
        self.clip["rights"]["status"] = "pending"
        manifest = self.root / "manifest.json"
        ev.write_json(manifest, self.manifest)
        engine = self.root / "engine"
        engine.write_text("unused placeholder used only in this routing test")
        out = self.root / "out"
        status = ev.run_batch(manifest, self.root, out, engine)
        self.assertEqual(status, 2)
        report = ev.read_json(out / "report.json")
        self.assertEqual(report["clips"][0]["status"], "not_approved")
        self.assertEqual(report["clips"][0]["rep_metrics"]["status"], "not_implemented")
        with self.assertRaises(FileExistsError):
            ev.run_batch(manifest, self.root, out, engine)


class InventoryTests(unittest.TestCase):
    def test_haa4d_inventory_separates_bench_and_unverified_dip(self):
        report = inv.inventory("haa4d", b"class_name,videoname,length\npull_ups,p1,60\nbench_dip,b1,20\ndips,d1,30\nother,o1,99\n")
        self.assertEqual(report["metadata_rows"], 4)
        self.assertEqual(report["selected_counts"], {"pull_up": 1, "bench_dip": 1, "dip_unverified": 1})
        self.assertEqual(report["selected_frames"]["pull_up"], 60)
        self.assertEqual(report["sections"]["all_data.csv"]["selected"][0]["media_status"], "not_acquired")

    def test_bad_metadata_headers_fail(self):
        with self.assertRaises(ValueError):
            inv.inventory("haa4d", b"unrelated,fields\nx,y\n")

    def test_countix_archive_metadata_only(self):
        buf = io.BytesIO()
        with tarfile.open(fileobj=buf, mode="w:gz") as archive:
            for split in ("train", "val", "test"):
                data = b"video_id,class,kinetics_start,kinetics_end,repetition_start,repetition_end,count\nabc,pull ups,0,10,1,9,3\n"
                info = tarfile.TarInfo("countix/countix_" + split + ".csv")
                info.size = len(data)
                archive.addfile(info, io.BytesIO(data))
        report = inv.inventory("countix", buf.getvalue())
        self.assertEqual(report["selected_counts"], {"pull_up": 3})
        self.assertEqual(report["metadata_rows"], 3)
        self.assertEqual(len(report["sections"]), 3)


if __name__ == "__main__":
    unittest.main()
