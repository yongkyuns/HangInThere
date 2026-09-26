#!/usr/bin/env python3
"""Prepare a pinned, public-domain source for tests; never substitute fake media.

Requires ffmpeg/ffprobe on the development host, not in the iOS app. The original
source hash is published by Wikimedia Commons. Derived SHA-256 values and exact
frame timestamps are recorded because ffmpeg versions can change encoded bytes.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import math
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SPEC_PATH = ROOT / "HangInThereTests/Fixtures/source.json"
DESTINATION = ROOT / "HangInThereTests/Fixtures/generated"


def digest(path: Path, algorithm: str = "sha256") -> str:
    h = hashlib.new(algorithm)
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def verify_source(path: Path, spec: dict) -> None:
    if path.stat().st_size != spec["source_bytes"] or digest(path, "sha1") != spec["source_sha1"]:
        raise ValueError(f"Source integrity mismatch: {path}. Do not update the pin without reviewing the source.")


def acquire(spec: dict, path: Path) -> None:
    if path.exists():
        verify_source(path, spec)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(".part")
    request = urllib.request.Request(spec["download_url"], headers={
        "User-Agent": "HangInThere-FixturePreparation/1.0 (https://github.com/yongkyuns/HangInThere)"
    })
    try:
        with urllib.request.urlopen(request, timeout=90) as response, partial.open("wb") as output:
            total = 0
            while block := response.read(1024 * 1024):
                total += len(block)
                if total > spec["source_bytes"]:
                    raise ValueError("Download exceeded the pinned source size.")
                output.write(block)
        verify_source(partial, spec)
        os.replace(partial, path)
    finally:
        partial.unlink(missing_ok=True)


def run(command: list[str]) -> str:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError(f"{command[0]} failed:\n{result.stderr[-6000:]}")
    return result.stdout


def prepare(source: Path, destination: Path, spec: dict) -> dict:
    verify_source(source, spec)
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            raise RuntimeError(f"{tool} is missing. On macOS install the test-only dependency with: brew install ffmpeg")
    destination.parent.mkdir(parents=True, exist_ok=True)
    recipe = spec["recipe"]
    with tempfile.TemporaryDirectory(dir=destination.parent, prefix="prepare-") as directory:
        work = Path(directory)
        video = work / "pullup-smoke.mp4"
        still = work / "pullup-smoke.png"
        command = [
            "ffmpeg", "-v", "error", "-y", "-ss", str(recipe["source_start_seconds"]),
            "-i", str(source), "-t", str(recipe["duration_seconds"]), "-map", "0:v:0",
            "-vf", f"fps={recipe['frames_per_second']},scale={recipe['width']}:-2:flags=lanczos,setsar=1",
            "-an", "-c:v", "libx264", "-preset", "fast", "-crf", "18", "-pix_fmt", "yuv420p",
            "-movflags", "+faststart", str(video)
        ]
        run(command)
        run(["ffmpeg", "-v", "error", "-y", "-ss", str(recipe["still_seconds"]),
             "-i", str(video), "-frames:v", "1", "-update", "1", str(still)])
        probe = json.loads(run([
            "ffprobe", "-v", "error", "-select_streams", "v:0", "-show_frames",
            "-show_entries", "frame=best_effort_timestamp_time", "-of", "json", str(video)
        ]))
        timestamps = [float(frame["best_effort_timestamp_time"]) for frame in probe["frames"]]
        expected_count = recipe["duration_seconds"] * recipe["frames_per_second"]
        if len(timestamps) != expected_count or not all(math.isfinite(t) for t in timestamps):
            raise ValueError("Prepared clip has an unexpected frame count or invalid time values.")
        if not timestamps or abs(timestamps[0]) > 1e-6 or any(b <= a for a, b in zip(timestamps, timestamps[1:])):
            raise ValueError("Prepared clip has an unexpected timeline.")
        metadata = {
            "schema_version": 1,
            "scope": "decoder/backend smoke only; no rep or joint ground-truth labels",
            "source_id": spec["source_id"],
            "source_sha1": spec["source_sha1"],
            "source_sha256": digest(source),
            "video_sha256": digest(video),
            "image_sha256": digest(still),
            "recipe": recipe,
            "ffmpeg_version": run(["ffmpeg", "-version"]).splitlines()[0],
            "frame_pts_seconds": timestamps
        }
        (work / "prepared.json").write_text(json.dumps(metadata, indent=2) + "\n")
        # Replace the complete derived set only after preparation succeeds.
        if destination.exists():
            shutil.rmtree(destination)
        os.replace(work, destination)
        return metadata


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, help="Already downloaded source; must match the committed integrity pin.")
    parser.add_argument("--verify-only", action="store_true", help="Check the generated files without downloading or encoding.")
    args = parser.parse_args()
    spec = json.loads(SPEC_PATH.read_text())
    try:
        if args.verify_only:
            metadata = json.loads((DESTINATION / "prepared.json").read_text())
            if metadata["source_sha1"] != spec["source_sha1"] or metadata["recipe"] != spec["recipe"]:
                raise ValueError("Generated fixtures use a different source or recipe.")
            if digest(DESTINATION / "pullup-smoke.mp4") != metadata["video_sha256"]:
                raise ValueError("Prepared video checksum mismatch.")
            if digest(DESTINATION / "pullup-smoke.png") != metadata["image_sha256"]:
                raise ValueError("Prepared image checksum mismatch.")
        else:
            source = args.source or ROOT / "Data/external/p0-pullup-source.webm"
            if not args.source:
                acquire(spec, source)
            metadata = prepare(source, DESTINATION, spec)
        print(json.dumps({"status": "fixture integrity checked", "video_sha256": metadata["video_sha256"],
                          "frames": len(metadata["frame_pts_seconds"])}, indent=2))
        return 0
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print(f"Fixture preparation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())