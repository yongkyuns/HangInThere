import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = ROOT / "scripts" / "debug_session.py"
SPEC = importlib.util.spec_from_file_location("debug_session", MODULE_PATH)
debug_session = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(debug_session)

EVALUATION_PATH = ROOT / "scripts" / "evaluation.py"
EVALUATION_SPEC = importlib.util.spec_from_file_location("evaluation", EVALUATION_PATH)
evaluation = importlib.util.module_from_spec(EVALUATION_SPEC)
assert EVALUATION_SPEC.loader is not None
EVALUATION_SPEC.loader.exec_module(evaluation)


class DebugSessionTests(unittest.TestCase):
    def make_package(self, root: Path) -> Path:
        package = root / "capture.hangdebug"
        package.mkdir()
        video = b"fake-movie-bytes"
        session = {
            "schemaVersion": 1,
            "counterPolicyVersion": 6,
            "exercise": "dip",
            "side": "right",
            "capture": {
                "backend": "AVCaptureMovieFileOutput",
                "includesSetup": True,
                "audioRecorded": False,
                "durationSeconds": 6.5,
                "firstAnalyzedSourceSeconds": 12.0,
                "lastAnalyzedSourceSeconds": 18.0,
            },
            "set": {
                "phase": "finished",
                "endReason": "manual",
                "observedMovements": 3,
                "partialAttempts": 1,
                "interruptedAttempts": 0,
                "analyzedFrames": 180,
                "usableTrackingFrames": 171,
                "trackingCoverage": 0.95,
                "firstSourceSeconds": 12.5,
                "lastSourceSeconds": 17.5,
            },
            "barReference": {
                "role": "rightDipRail",
                "sourceSeconds": 12.25,
            },
        }
        qualification = {
            "schemaVersion": 1,
            "counterPolicyVersion": 6,
            "exercise": "dip",
            "side": "right",
            "setPhase": "finished",
            "setEndReason": "manual",
            "observedMovements": 3,
            "partialAttempts": 1,
            "interruptedAttempts": 0,
            "setAnalyzedFrames": 180,
            "setUsableTrackingFrames": 171,
            "trackingCoverage": 0.95,
        }
        (package / "video.mov").write_bytes(video)
        (package / "session.json").write_text(
            json.dumps(session) + "\n", encoding="utf-8"
        )
        (package / "qualification.json").write_text(
            json.dumps(qualification) + "\n", encoding="utf-8"
        )

        self.refresh_hashes(package)
        return package

    def refresh_hashes(self, package: Path) -> None:
        def spec(path: Path):
            data = path.read_bytes()
            return {
                "sha256": hashlib.sha256(data).hexdigest(),
                "bytes": len(data),
            }

        hashes = {
            "schemaVersion": 1,
            "files": {
                name: spec(package / name)
                for name in ("video.mov", "session.json", "qualification.json")
            },
        }
        (package / "hashes.json").write_text(
            json.dumps(hashes) + "\n", encoding="utf-8"
        )

    def test_verify_checks_hashes_and_exposes_session_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            report = debug_session.verify(package)
            self.assertEqual(report["session"]["counter_policy_version"], 6)
            self.assertEqual(report["session"]["exercise"], "dip")
            self.assertEqual(report["session"]["side"], "right")
            self.assertTrue(report["session"]["bar_reference_present"])
            self.assertEqual(report["qualification"]["observed_movements"], 3)

    def test_semantically_inconsistent_metadata_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            qualification_path = package / "qualification.json"
            qualification = json.loads(qualification_path.read_text(encoding="utf-8"))
            qualification["observedMovements"] = 2
            qualification_path.write_text(
                json.dumps(qualification) + "\n", encoding="utf-8"
            )
            self.refresh_hashes(package)

            with self.assertRaisesRegex(
                debug_session.DebugSessionError,
                "observedMovements disagrees",
            ):
                debug_session.verify(package)

    def test_capture_contract_rejects_audio(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            session_path = package / "session.json"
            session = json.loads(session_path.read_text(encoding="utf-8"))
            session["capture"]["audioRecorded"] = True
            session_path.write_text(json.dumps(session) + "\n", encoding="utf-8")
            self.refresh_hashes(package)

            with self.assertRaisesRegex(
                debug_session.DebugSessionError,
                "must not contain audio",
            ):
                debug_session.verify(package)

    def test_out_of_capture_set_timestamps_are_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            session_path = package / "session.json"
            session = json.loads(session_path.read_text(encoding="utf-8"))
            session["set"]["firstSourceSeconds"] = 11.5
            session_path.write_text(json.dumps(session) + "\n", encoding="utf-8")
            self.refresh_hashes(package)

            with self.assertRaisesRegex(
                debug_session.DebugSessionError,
                "outside recorded capture anchors",
            ):
                debug_session.verify(package)

    def test_tampered_video_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            (package / "video.mov").write_bytes(b"tampered")
            with self.assertRaises(debug_session.DebugSessionError):
                debug_session.verify(package)

    def test_manifest_is_private_and_uses_pinned_video(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            report = debug_session.verify(package)
            manifest = debug_session.evaluation_manifest(
                package,
                report,
                "Participant consented to private local evaluation.",
            )
            clip = manifest["clips"][0]
            self.assertEqual(clip["exercise"], "parallel_bar_dip")
            self.assertEqual(clip["split"], "unassigned")
            self.assertFalse(clip["rights"]["public_outputs"])
            self.assertEqual(clip["media"]["files"][0]["path"], "video.mov")
            self.assertEqual(
                clip["media"]["files"][0]["sha256"],
                report["files"]["video.mov"]["sha256"],
            )
            self.assertEqual(clip["debug_session"]["counter_policy_version"], 6)
            self.assertEqual(clip["debug_session"]["tracking_side"], "right")
            self.assertEqual(
                clip["debug_session"]["set"]["observedMovements"],
                3,
            )

    def test_generated_manifest_passes_production_evaluator_contract(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            report = debug_session.verify(package)
            manifest = debug_session.evaluation_manifest(
                package,
                report,
                "Private local qualification capture.",
            )
            clips = evaluation.validate_manifest(manifest, package)
            self.assertEqual(len(clips), 1)
            self.assertEqual(clips[0]["exercise"], "parallel_bar_dip")
            self.assertEqual(
                evaluation.preflight(clips[0], package, public_output=False),
                "ready",
            )

    def test_manifest_requires_explicit_rights_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            report = debug_session.verify(package)
            with self.assertRaises(debug_session.DebugSessionError):
                debug_session.evaluation_manifest(package, report, "   ")


if __name__ == "__main__":
    unittest.main()
