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
            "capture": {"backend": "AVCaptureMovieFileOutput"},
            "set": {"observedMovements": 3},
            "barReference": {"role": "rightDipRail"},
        }
        qualification = {
            "schemaVersion": 1,
            "setPhase": "finished",
            "setEndReason": "manual",
            "observedMovements": 3,
        }
        (package / "video.mov").write_bytes(video)
        (package / "session.json").write_text(
            json.dumps(session) + "\n", encoding="utf-8"
        )
        (package / "qualification.json").write_text(
            json.dumps(qualification) + "\n", encoding="utf-8"
        )

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
        return package

    def test_verify_checks_hashes_and_exposes_session_provenance(self):
        with tempfile.TemporaryDirectory() as directory:
            package = self.make_package(Path(directory))
            report = debug_session.verify(package)
            self.assertEqual(report["session"]["counter_policy_version"], 6)
            self.assertEqual(report["session"]["exercise"], "dip")
            self.assertEqual(report["session"]["side"], "right")
            self.assertTrue(report["session"]["bar_reference_present"])
            self.assertEqual(report["qualification"]["observed_movements"], 3)

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
