#!/usr/bin/env python3
"""Verify the private pinned iOS OpenCV ABI before linking the app.

This checks archived binary identity, matching headers, and every OpenCV symbol
actually required by the C ABI object on device and both simulator architectures.
It does not establish runtime/pixel or performance parity; app integration tests
must exercise the actual linked implementation as well.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import tarfile
import tempfile

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = ROOT / "native/apple"
VENDOR = PACKAGE / "Sources/CObservedRailPixels/Vendor/OpenCV453"


def stream_digest(stream):
    value = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b""):
        value.update(chunk)
    return value.hexdigest()


def digest(path):
    with path.open("rb") as stream:
        return stream_digest(stream)


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def verify(sdk_root):
    lock = json.loads((VENDOR / "source-lock.json").read_text())
    archive = sdk_root / "downloads" / f"MediaPipeTasksCommon-{lock['mediaPipeTasksCommonVersion']}.tar.gz"
    require(digest(archive) == lock["mediaPipeTasksCommonArchiveSHA256"], "Unqualified MediaPipe Common archive")
    for name, expected in lock["headers"].items():
        require(digest(VENDOR / "include" / name) == expected, f"Changed vendored OpenCV header: {name}")
    source = PACKAGE / "Sources/CObservedRailPixels/ObservedRailPixels.cpp"
    results = []
    with tarfile.open(archive, "r:gz") as bundle, tempfile.TemporaryDirectory() as temp:
        targets = [("iphoneos", "arm64", "arm64-apple-ios17.0", "ios-arm64"),
                   ("iphonesimulator", "x86_64", "x86_64-apple-ios17.0-simulator", "ios-arm64_x86_64-simulator"),
                   ("iphonesimulator", "arm64", "arm64-apple-ios17.0-simulator", "ios-arm64_x86_64-simulator")]
        for sdk, architecture, target, slice_name in targets:
            relative = f"frameworks/MediaPipeTasksCommon.xcframework/{slice_name}/MediaPipeTasksCommon.framework/MediaPipeTasksCommon"
            binary = sdk_root / "Common" / relative
            # Framework binary can be a symlink into Versions/A. Resolve the
            # corresponding archived file, never trust only the extraction marker.
            members = [member for member in bundle.getmembers() if member.name.endswith(relative)]
            require(len(members) == 1, f"Missing/ambiguous archived Common slice: {slice_name}")
            member = members[0]
            archived = bundle.extractfile(member)
            require(archived is not None, f"Unreadable archived Common slice: {slice_name}")
            expected_binary = stream_digest(archived)
            require(digest(binary) == expected_binary, f"Changed Common binary: {slice_name}")
            sdk_path = subprocess.check_output(["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True).strip()
            obj = Path(temp) / (target + ".o")
            subprocess.run(["xcrun", "clang++", "-std=c++17", "-target", target, "-isysroot", sdk_path,
                "-I", str(VENDOR / "include"), "-I", str(PACKAGE / "Sources/CObservedRailPixels/include"),
                "-c", str(source), "-o", str(obj)], check=True)
            undefined = subprocess.check_output(["xcrun", "nm", "-u", str(obj)], text=True)
            required = {line.split()[-1] for line in undefined.splitlines() if "2cv" in line}
            require(required, "No OpenCV references found in compiled pixel adapter")
            defined = subprocess.check_output(["xcrun", "nm", "-arch", architecture, "-gU", str(binary)], text=True, stderr=subprocess.DEVNULL)
            exports = {line.split()[-1] for line in defined.splitlines() if line.split()}
            missing = required - exports
            require(not missing, f"Missing OpenCV ABI symbols for {target}: {sorted(missing)}")
            results.append(dict(target=target, binarySHA256=expected_binary, requiredSymbols=sorted(required)))
    report = dict(qualifiedArchiveSHA256=lock["mediaPipeTasksCommonArchiveSHA256"], headerVersion=lock["headerVersion"],
                  adapterSHA256=digest(source), targets=results, scope="Compile-time version and required-symbol checks; not runtime pixel or performance qualification")
    (sdk_root / "rail-pixel-abi-check.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Pinned iOS rail-pixel ABI: {len(results)} architecture checks passed.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("sdk_root", type=Path, nargs="?", default=ROOT / "build/grip-sdk")
    verify(parser.parse_args().sdk_root.resolve())
