#!/usr/bin/env python3
"""Prepare the pinned real-video diversity corpus for Apple Vision qualification.

Normal mode requires exact source_bytes + source_sha256 pins for every downloaded
source. --discover is intentionally non-qualifying: it downloads sources and prints
the exact pins that must be reviewed and committed before normal preparation can
succeed.
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
SPEC_PATH = ROOT / "HangInThereTests/Fixtures/corpus.json"
DESTINATION = ROOT / "HangInThereTests/Fixtures/generated/corpus"
SOURCE_ROOT = ROOT / "Data/external/corpus"
EXISTING_FIXTURE_ROOT = ROOT / "HangInThereTests/Fixtures/generated"


def digest(path: Path) -> str:
    hasher = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            hasher.update(block)
    return hasher.hexdigest()


def run(command):
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    if result.returncode:
        raise RuntimeError("{} failed:\n{}".format(command[0], result.stderr[-6000:]))
    return result.stdout


def source_path(case):
    suffix = Path(urllib.request.urlparse(case["download_url"]).path).suffix or ".bin"
    return SOURCE_ROOT / "{}{}".format(case["id"], suffix)


def acquire(case, path, allow_unpinned):
    expected_bytes = case.get("source_bytes")
    expected_sha256 = case.get("source_sha256")
    if not allow_unpinned and (not expected_bytes or not expected_sha256):
        raise ValueError(
            "{} has no reviewed integrity pin; run --discover, review, then commit pins.".format(
                case["id"]
            )
        )

    if path.exists():
        if allow_unpinned:
            return
        verify_source(case, path)
        return

    path.parent.mkdir(parents=True, exist_ok=True)
    partial = path.with_suffix(path.suffix + ".part")
    request = urllib.request.Request(
        case["download_url"],
        headers={
            "User-Agent": "HangInThere-CorpusPreparation/1.0 (https://github.com/yongkyuns/HangInThere)"
        },
    )
    try:
        with urllib.request.urlopen(request, timeout=120) as response, partial.open("wb") as output:
            total = 0
            while True:
                block = response.read(1024 * 1024)
                if not block:
                    break
                total += len(block)
                if expected_bytes and total > expected_bytes:
                    raise ValueError("{} download exceeded pinned byte count.".format(case["id"]))
                output.write(block)
        os.replace(partial, path)
        if not allow_unpinned:
            verify_source(case, path)
    finally:
        partial.unlink(missing_ok=True)


def verify_source(case, path):
    expected_bytes = case.get("source_bytes")
    expected_sha256 = case.get("source_sha256")
    if not expected_bytes or not expected_sha256:
        raise ValueError("{} is missing source integrity pins.".format(case["id"]))
    actual_bytes = path.stat().st_size
    actual_sha256 = digest(path)
    if actual_bytes != expected_bytes or actual_sha256 != expected_sha256:
        raise ValueError(
            "{} source integrity mismatch: bytes={} sha256={}".format(
                case["id"], actual_bytes, actual_sha256
            )
        )


def discover(spec):
    discovered = []
    for case in spec["cases"]:
        if case["source_kind"] != "download":
            continue
        path = source_path(case)
        acquire(case, path, allow_unpinned=True)
        discovered.append(
            {
                "id": case["id"],
                "source_bytes": path.stat().st_size,
                "source_sha256": digest(path),
            }
        )
    print(json.dumps({"status": "NON-QUALIFYING PIN DISCOVERY", "cases": discovered}, indent=2))


def require_tools():
    for tool in ("ffmpeg", "ffprobe"):
        if not shutil.which(tool):
            raise RuntimeError(
                "{} is missing. On macOS install the test-only dependency with: brew install ffmpeg".format(
                    tool
                )
            )


def frame_timestamps(video):
    probe = json.loads(
        run(
            [
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_frames",
                "-show_entries",
                "frame=best_effort_timestamp_time",
                "-of",
                "json",
                str(video),
            ]
        )
    )
    return [float(frame["best_effort_timestamp_time"]) for frame in probe["frames"]]


def video_geometry(video):
    probe = json.loads(
        run(
            [
                "ffprobe",
                "-v",
                "error",
                "-select_streams",
                "v:0",
                "-show_entries",
                "stream=width,height",
                "-of",
                "json",
                str(video),
            ]
        )
    )
    stream = probe["streams"][0]
    return {"width": int(stream["width"]), "height": int(stream["height"])}


def create_contact_sheet(video, output):
    run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-i",
            str(video),
            "-vf",
            "fps=0.5,scale=240:-2:flags=lanczos,tile=4x4:padding=2:margin=2",
            "-frames:v",
            "1",
            str(output),
        ]
    )


def prepare_download_case(case, work):
    path = source_path(case)
    acquire(case, path, allow_unpinned=False)
    verify_source(case, path)
    recipe = case["recipe"]
    video = work / "{}.mp4".format(case["id"])
    contact = work / "{}-contact.jpg".format(case["id"])

    run(
        [
            "ffmpeg",
            "-v",
            "error",
            "-y",
            "-ss",
            str(recipe["source_start_seconds"]),
            "-i",
            str(path),
            "-t",
            str(recipe["duration_seconds"]),
            "-map",
            "0:v:0",
            "-vf",
            "fps={},scale={m}:{m}:force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1".format(
                recipe["frames_per_second"], m=recipe["max_long_edge"]
            ),
            "-an",
            "-c:v",
            "libx264",
            "-preset",
            "fast",
            "-crf",
            "18",
            "-pix_fmt",
            "yuv420p",
            "-movflags",
            "+faststart",
            str(video),
        ]
    )

    timestamps = frame_timestamps(video)
    expected_count = int(recipe["duration_seconds"] * recipe["frames_per_second"])
    if len(timestamps) != expected_count:
        raise ValueError(
            "{} prepared frame count {} != expected {}".format(
                case["id"], len(timestamps), expected_count
            )
        )
    if not timestamps or abs(timestamps[0]) > 1e-6:
        raise ValueError("{} prepared timeline does not start at zero.".format(case["id"]))
    if any(not math.isfinite(value) for value in timestamps):
        raise ValueError("{} prepared timeline contains invalid values.".format(case["id"]))
    if any(b <= a for a, b in zip(timestamps, timestamps[1:])):
        raise ValueError("{} prepared timeline is not strictly increasing.".format(case["id"]))

    create_contact_sheet(video, contact)
    return {
        "id": case["id"],
        "tier": case["tier"],
        "exercise": case["exercise"],
        "source_sha256": case["source_sha256"],
        "source_bytes": case["source_bytes"],
        "video_sha256": digest(video),
        "contact_sha256": digest(contact),
        "frame_count": len(timestamps),
        "frame_pts_seconds": timestamps,
        "geometry": video_geometry(video),
        "recipe": recipe,
    }


def prepare_existing_source_case(case, work):
    path = (ROOT / case["source_path"]).resolve()
    root = ROOT.resolve()
    if root not in path.parents:
        raise ValueError("{} existing source escapes repository root.".format(case["id"]))
    if not path.exists():
        raise ValueError(
            "{} requires the already-verified Iwakuni source. Run python3 scripts/prepare-fixtures.py first.".format(
                case["id"]
            )
        )
    if path.stat().st_size != case["source_bytes"] or digest(path) != case["source_sha256"]:
        raise ValueError("{} existing source integrity mismatch.".format(case["id"]))

    recipe = case["recipe"]
    video = work / "{}.mp4".format(case["id"])
    contact = work / "{}-contact.jpg".format(case["id"])
    run(
        [
            "ffmpeg", "-v", "error", "-y",
            "-ss", str(recipe["source_start_seconds"]),
            "-i", str(path),
            "-t", str(recipe["duration_seconds"]),
            "-map", "0:v:0",
            "-vf",
            "fps={},scale={m}:{m}:force_original_aspect_ratio=decrease:force_divisible_by=2,setsar=1".format(
                recipe["frames_per_second"], m=recipe["max_long_edge"]
            ),
            "-an", "-c:v", "libx264", "-preset", "fast", "-crf", "18",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", str(video),
        ]
    )
    timestamps = frame_timestamps(video)
    expected_count = int(recipe["duration_seconds"] * recipe["frames_per_second"])
    if len(timestamps) != expected_count:
        raise ValueError(
            "{} prepared frame count {} != expected {}".format(
                case["id"], len(timestamps), expected_count
            )
        )
    create_contact_sheet(video, contact)
    return {
        "id": case["id"],
        "tier": case["tier"],
        "exercise": case["exercise"],
        "source_sha256": case["source_sha256"],
        "source_bytes": case["source_bytes"],
        "video_sha256": digest(video),
        "contact_sha256": digest(contact),
        "frame_count": len(timestamps),
        "frame_pts_seconds": timestamps,
        "geometry": video_geometry(video),
        "recipe": recipe,
    }


def prepare_existing_case(case, work):
    source = (EXISTING_FIXTURE_ROOT / case["prepared_video"]).resolve()
    root = EXISTING_FIXTURE_ROOT.resolve()
    if root not in source.parents:
        raise ValueError("{} existing fixture escapes generated fixture root.".format(case["id"]))
    if not source.exists():
        raise ValueError(
            "{} requires the existing fixture. Run python3 scripts/prepare-fixtures.py first.".format(
                case["id"]
            )
        )
    contact = work / "{}-contact.jpg".format(case["id"])
    create_contact_sheet(source, contact)
    timestamps = frame_timestamps(source)
    return {
        "id": case["id"],
        "tier": case["tier"],
        "exercise": case["exercise"],
        "existing_video": str(source.relative_to(ROOT)),
        "video_sha256": digest(source),
        "contact_sha256": digest(contact),
        "frame_count": len(timestamps),
        "frame_pts_seconds": timestamps,
        "geometry": video_geometry(source),
    }


def prepare(spec):
    require_tools()
    DESTINATION.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(
        dir=DESTINATION.parent, prefix="corpus-prepare-"
    ) as directory:
        work = Path(directory)
        prepared = []
        for case in spec["cases"]:
            if case["source_kind"] == "download":
                prepared.append(prepare_download_case(case, work))
            elif case["source_kind"] == "existing_fixture":
                prepared.append(prepare_existing_case(case, work))
            elif case["source_kind"] == "existing_source":
                prepared.append(prepare_existing_source_case(case, work))
            else:
                raise ValueError("Unknown source_kind for {}".format(case["id"]))

        metadata = {
            "schema_version": 1,
            "scope": spec["scope"],
            "ffmpeg_version": run(["ffmpeg", "-version"]).splitlines()[0],
            "cases": prepared,
        }
        (work / "corpus-prepared.json").write_text(
            json.dumps(metadata, indent=2) + "\n", encoding="utf-8"
        )
        if DESTINATION.exists():
            shutil.rmtree(DESTINATION)
        os.replace(work, DESTINATION)
        return metadata


def verify_prepared(spec):
    metadata = json.loads((DESTINATION / "corpus-prepared.json").read_text())
    by_id = {case["id"]: case for case in metadata["cases"]}
    for case in spec["cases"]:
        prepared = by_id.get(case["id"])
        if not prepared:
            raise ValueError("Prepared corpus is missing {}.".format(case["id"]))
        if case["source_kind"] == "download":
            verify_source(case, source_path(case))
            video = DESTINATION / "{}.mp4".format(case["id"])
        elif case["source_kind"] == "existing_source":
            source = (ROOT / case["source_path"]).resolve()
            if source.stat().st_size != case["source_bytes"] or digest(source) != case["source_sha256"]:
                raise ValueError("{} existing source integrity mismatch.".format(case["id"]))
            video = DESTINATION / "{}.mp4".format(case["id"])
        else:
            video = (EXISTING_FIXTURE_ROOT / case["prepared_video"]).resolve()
        contact = DESTINATION / "{}-contact.jpg".format(case["id"])
        if digest(video) != prepared["video_sha256"]:
            raise ValueError("{} prepared video checksum mismatch.".format(case["id"]))
        if digest(contact) != prepared["contact_sha256"]:
            raise ValueError("{} contact sheet checksum mismatch.".format(case["id"]))
        if frame_timestamps(video) != prepared["frame_pts_seconds"]:
            raise ValueError("{} prepared timestamps changed.".format(case["id"]))
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--discover",
        action="store_true",
        help="Download unpinned sources and print exact source pins; NOT qualification.",
    )
    parser.add_argument(
        "--verify-only",
        action="store_true",
        help="Verify source pins and already-generated corpus without downloading/encoding.",
    )
    args = parser.parse_args()
    if args.discover and args.verify_only:
        parser.error("--discover and --verify-only are mutually exclusive")

    spec = json.loads(SPEC_PATH.read_text(encoding="utf-8"))
    try:
        if args.discover:
            discover(spec)
            return 0
        if args.verify_only:
            metadata = verify_prepared(spec)
        else:
            metadata = prepare(spec)
        print(
            json.dumps(
                {
                    "status": "corpus integrity checked",
                    "cases": [
                        {
                            "id": case["id"],
                            "frame_count": case["frame_count"],
                            "video_sha256": case["video_sha256"],
                        }
                        for case in metadata["cases"]
                    ],
                },
                indent=2,
            )
        )
        return 0
    except (OSError, ValueError, KeyError, RuntimeError, json.JSONDecodeError) as error:
        print("Corpus preparation failed: {}".format(error), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
