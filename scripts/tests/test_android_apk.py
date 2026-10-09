"""Protect the native packaging failure caught by emulator CI."""
import hashlib
import importlib.util
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import zipfile

SPEC = importlib.util.spec_from_file_location(
    "apk_check", Path(__file__).parents[1] / "check-android-apk.py")
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


def elf(bits=64, alignment=16384):
    data = bytearray(120)
    data[:6] = b"\x7fELF" + bytes([2 if bits == 64 else 1, 1])
    if bits == 64:
        struct.pack_into("<Q", data, 32, 64)
        struct.pack_into("<HH", data, 54, 56, 1)
        struct.pack_into("<I", data, 64, 1)
        struct.pack_into("<Q", data, 112, alignment)
    else:
        struct.pack_into("<I", data, 28, 52)
        struct.pack_into("<HH", data, 42, 32, 1)
        struct.pack_into("<I", data, 52, 1)
        struct.pack_into("<I", data, 80, alignment)
    return bytes(data)


class AndroidApkTests(unittest.TestCase):
    def entries(self):
        result = {"classes.dex": b"compiled", "assets/pose_landmarker_heavy.task": b"model"}
        for abi in CHECK.ABIS:
            result[f"lib/{abi}/libmediapipe_tasks_vision_jni.so"] = elf()
            result[f"lib/{abi}/libopencv_java4.so"] = b"opencv"
        return result

    def check(self, entries):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "app.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                for name, data in entries.items():
                    archive.writestr(name, data)
            with patch.object(CHECK, "MODEL_SHA", hashlib.sha256(b"model").hexdigest()):
                return CHECK.check(apk)

    def test_all_native_abis_and_model_are_required(self):
        self.assertEqual(set(self.check(self.entries())["nativePoseLibraries"]), set(CHECK.ABIS))

    def test_arm_only_runtime_is_rejected_before_emulator(self):
        entries = self.entries()
        del entries["lib/x86_64/libmediapipe_tasks_vision_jni.so"]
        with self.assertRaisesRegex(AssertionError, "JNI missing for x86_64"):
            self.check(entries)

    def test_missing_opencv_is_rejected(self):
        entries = self.entries()
        del entries["lib/arm64-v8a/libopencv_java4.so"]
        with self.assertRaisesRegex(AssertionError, "OpenCV JNI missing"):
            self.check(entries)

    def test_changed_model_is_rejected(self):
        entries = self.entries()
        entries["assets/pose_landmarker_heavy.task"] = b"another model"
        with self.assertRaisesRegex(AssertionError, "Wrong packaged pose model"):
            self.check(entries)

    def test_4k_native_alignment_is_rejected(self):
        entries = self.entries()
        entries["lib/arm64-v8a/libmediapipe_tasks_vision_jni.so"] = elf(alignment=4096)
        with self.assertRaisesRegex(AssertionError, "aligned"):
            self.check(entries)

    def test_32_and_64_bit_load_headers(self):
        for bits in (32, 64):
            self.assertEqual(CHECK.load_alignments(elf(bits)), [16384])


if __name__ == "__main__":
    unittest.main()
