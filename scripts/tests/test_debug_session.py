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


class DebugSessionTests(unittest.TestCase):
    def make_bundle(self, root: Path):
        video = root / "video.mov"
        qualification = root / "qualification.json"
        video.write_bytes(b"private-video-bytes")
        qualification.write_text('{"schemaVersion":1}\n', encoding="utf-8")

        def record(path: Path):
            data = path.read_bytes()
            return {
                "name": path.name,
                "sha256": hashlib.sha256(data).hexdigest(),
                "bytes": len(data),
            }

        session = {
            "schemaVersion": 1,
            "sessionID": "00000000-0000-0000-0000-000000000001",
            "scope": "test",
            "appVersion": "1.0",
            "appBuild": "1",
            "video": record(video),
            "qualification": record(qualification),
            "capture": {
                "durationSeconds": 10.0,
                "appendedSamples": 100,
                "droppedQueueSamples": 0,
                "droppedWriterSamples": 0,
            },
            "workout": {
                "counterPolicyVersion": 6,
                "exercise": "dip",
                "side": "right",
                "phase": "finished",
                "endReason": "manual",
                "observedMovements": 3,
                "partialAttempts": 0,
                "interruptedAttempts": 0,
                "trackingCoverage": 0.95,
                "setStartCaptureSeconds": 1.0,
                "setEndCaptureSeconds": 9.0,
                "movementCaptureSeconds": [3.0, 5.0, 8.0],
            },
            "barReference": {
                "role": "rightDipRail",
                "method": "manualEdge",
                "referenceEdge": {
                    "ax": 10.0, "ay": 20.0, "bx": 100.0, "by": 20.0
                },
                "oppositeEdge": None,
                "imageWidth": 720.0,
                "imageHeight": 1280.0,
                "confirmationCaptureSeconds": 0.5,
            },
        }
        path = root / "session.json"
        path.write_text(json.dumps(session), encoding="utf-8")
        return path, session

    def test_prepare_validates_hashes_and_builds_private_local_manifest(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            session_path, session = self.make_bundle(root)
            output = root / "evaluation.json"
            identifier = debug_session.prepare(session_path, root, output)
            self.assertEqual(
                identifier,
                "debug_00000000000000000000000000000001",
            )
            manifest = json.loads(output.read_text(encoding="utf-8"))
            clip = manifest["clips"][0]
            self.assertEqual(clip["exercise"], "parallel_bar_dip")
            self.assertEqual(clip["split"], "unassigned")
            self.assertFalse(clip["rights"]["public_outputs"])
            self.assertEqual(
                clip["media"]["files"][0]["sha256"],
                session["video"]["sha256"],
            )

    def test_changed_video_bytes_are_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            session_path, _ = self.make_bundle(root)
            (root / "video.mov").write_bytes(b"changed")
            with self.assertRaises(debug_session.DebugSessionError):
                debug_session.prepare(
                    session_path, root, root / "evaluation.json"
                )

    def test_path_traversal_in_file_record_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            session_path, session = self.make_bundle(root)
            session["video"]["name"] = "../video.mov"
            session_path.write_text(json.dumps(session), encoding="utf-8")
            with self.assertRaises(debug_session.DebugSessionError):
                debug_session.prepare(
                    session_path, root, root / "evaluation.json"
                )

    def test_unfinished_set_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            session_path, session = self.make_bundle(root)
            session["workout"]["phase"] = "running"
            session_path.write_text(json.dumps(session), encoding="utf-8")
            with self.assertRaises(debug_session.DebugSessionError):
                debug_session.prepare(
                    session_path, root, root / "evaluation.json"
                )

    def test_missing_bar_reference_is_rejected(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            session_path, session = self.make_bundle(root)
            session["barReference"] = None
            session_path.write_text(json.dumps(session), encoding="utf-8")
            with self.assertRaises(debug_session.DebugSessionError):
                debug_session.prepare(
                    session_path, root, root / "evaluation.json"
                )


if __name__ == "__main__":
    unittest.main()
