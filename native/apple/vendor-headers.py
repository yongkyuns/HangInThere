#!/usr/bin/env python3
"""Regenerate only the exact OpenCV 4.5.3 headers needed by the iOS C ABI.

The pinned MediaPipeTasksCommon 0.10.21 binary already embeds this OpenCV.
Do not link another OpenCV into the iOS app. This developer-only command uses
Xcode's real device/simulator preprocessors to retain the transitive header set.
It never changes the source archive or downloads unverified build inputs.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[2]
ARCHIVE_SHA256 = "a61e7a4618d353140c857f25843f39b2abe5f451b018aab1604ef0bc34cd23d5"
COMMON_SHA256 = "1cb1eed8e9eb7868a93fa3c3ce3a341e352620158f29f9799800376f651bd004"
# Public base.hpp needs a module-availability header. Only these two guaranteed
# modules are consumed here. No C++ ABI/build-option macros are invented.
MODULES = """// Adapter module availability; not the SDK's complete module inventory.
// Both modules are present in pinned MediaPipeTasksCommon 0.10.21 / OpenCV 4.5.3.
#define HAVE_OPENCV_CORE
#define HAVE_OPENCV_IMGPROC
"""


def digest(data):
    return hashlib.sha256(data).hexdigest()


def generate(archive):
    if digest(archive.read_bytes()) != ARCHIVE_SHA256:
        raise RuntimeError("OpenCV source checksum mismatch")
    destination = ROOT / "native/apple/Sources/CObservedRailPixels/Vendor/OpenCV453"
    with zipfile.ZipFile(archive) as source, tempfile.TemporaryDirectory() as temp:
        work = Path(temp)
        include = work / "include"
        for info in source.infolist():
            for prefix in ("opencv-4.5.3/modules/core/include/", "opencv-4.5.3/modules/imgproc/include/"):
                if info.filename.startswith(prefix) and not info.is_dir():
                    target = include / info.filename[len(prefix):]
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(source.read(info))
        (include / "opencv2/opencv_modules.hpp").write_text(MODULES)
        retained = set()
        targets = [("iphoneos", "arm64-apple-ios17.0"),
                   ("iphonesimulator", "x86_64-apple-ios17.0-simulator"),
                   ("iphonesimulator", "arm64-apple-ios17.0-simulator")]
        for sdk, target in targets:
            sdk_path = subprocess.check_output(["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True).strip()
            dependencies = work / (target + ".d")
            subprocess.run(["xcrun", "clang++", "-std=c++17", "-target", target, "-isysroot", sdk_path,
                "-I", str(include), "-I", str(ROOT / "native/apple/Sources/CObservedRailPixels/include"),
                "-MMD", "-MF", str(dependencies), "-fsyntax-only",
                str(ROOT / "native/apple/Sources/CObservedRailPixels/ObservedRailPixels.cpp")], check=True)
            for name in shlex.split(dependencies.read_text().replace("\\\n", " ")):
                path = Path(name)
                if path.is_file() and path.is_relative_to(include):
                    retained.add(path.relative_to(include))
        if not retained or Path("opencv2/core/version.hpp") not in retained:
            raise RuntimeError("OpenCV dependencies are incomplete")
        manifest = {}
        for relative in sorted(retained):
            target = destination / "include" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            data = (include / relative).read_bytes()
            target.write_bytes(data)
            manifest[str(relative)] = digest(data)
        # Only stale vendored header files may be removed by this regeneration.
        for path in (destination / "include").rglob("*"):
            if path.is_file() and path.relative_to(destination / "include") not in retained:
                path.unlink()
        (destination / "LICENSE").write_bytes(source.read("opencv-4.5.3/LICENSE"))
        lock = dict(source="https://github.com/opencv/opencv/archive/refs/tags/4.5.3.zip",
                    sourceSHA256=ARCHIVE_SHA256, mediaPipeTasksCommonVersion="0.10.21",
                    mediaPipeTasksCommonArchiveSHA256=COMMON_SHA256, headerVersion="4.5.3",
                    generatedModuleAvailability=["core", "imgproc"], targets=[t for _, t in targets], headers=manifest)
        (destination / "source-lock.json").write_text(json.dumps(lock, indent=2, sort_keys=True) + "\n")
        # Preserve each complete initial notice, including the BSD conditions
        # and disclaimer retained by several upstream public headers.
        notices = {}
        for relative in sorted(retained):
            text = (include / relative).read_text()
            if text.startswith("/*"):
                notice = text[:text.index("*/") + 2]
                notices.setdefault(notice, []).append(str(relative))
        resource = ROOT / "native/apple/Sources/ObservedRailPixels/Resources/OpenCV-HEADER-NOTICES"
        resource.write_text("OpenCV 4.5.3 public header notices\n\n" + "\n\n".join(
            "Headers: " + ", ".join(names) + "\n" + notice for notice, names in notices.items()) + "\n")
        print(f"Vendored {len(retained)} pinned transitive headers ({sum((include / p).stat().st_size for p in retained)} bytes).")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--archive", type=Path, default=ROOT / "build/opencv-sdk/downloads/opencv-4.5.3-source.zip")
    generate(parser.parse_args().archive.resolve())
