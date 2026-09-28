#!/usr/bin/env python3
"""Prepare source-separated object-detection data for the bar-only Create ML prototype.

This script never reads pose observations. Bounding boxes are frozen in
Evaluation/fixtures/bar-object-prototype.json and describe coarse apparatus regions,
not precision bar edges.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.parse
import urllib.request

from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SPEC = ROOT / "Evaluation/fixtures/bar-object-prototype.json"
ALLOWED_HOSTS = {"d34w7g4gy10iej.cloudfront.net"}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        while block := stream.read(1024 * 1024):
            h.update(block)
    return h.hexdigest()


def read_json(path: Path):
    with path.open() as stream:
        return json.load(stream)


def fetch_pinned(row: dict, cache: Path, fetch: bool) -> Path:
    parsed = urllib.parse.urlparse(row["url"])
    require(parsed.scheme == "https" and parsed.hostname in ALLOWED_HOSTS and not parsed.query,
            "Unapproved pull-up source URL")
    expected_bytes = int(row["bytes"])
    require(0 < expected_bytes <= 250_000_000, "Pull-up source exceeds bound")
    path = cache / f'{row["sha256"]}.mp4'
    if not path.exists():
        require(fetch, "Missing pinned pull-up source; enable --fetch")
        cache.mkdir(parents=True, exist_ok=True)
        with tempfile.NamedTemporaryFile(dir=cache, delete=False) as tmp:
            temp_path = Path(tmp.name)
        try:
            req = urllib.request.Request(row["url"], headers={
                "User-Agent": "HangInThere/0.1 bar-object research"
            })
            total = 0
            with urllib.request.urlopen(req, timeout=120) as response, temp_path.open("wb") as out:
                while block := response.read(1024 * 1024):
                    total += len(block)
                    require(total <= expected_bytes, "Pull-up source grew beyond pin")
                    out.write(block)
            require(total == expected_bytes, "Pull-up source byte count changed")
            require(digest(temp_path) == row["sha256"], "Pull-up source checksum changed")
            temp_path.replace(path)
        finally:
            temp_path.unlink(missing_ok=True)
    require(path.stat().st_size == expected_bytes and digest(path) == row["sha256"],
            "Cached pull-up source does not match pin")
    return path


def probe_video(path: Path) -> tuple[int, int]:
    raw = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height", "-of", "json", str(path)
    ], text=True)
    streams = json.loads(raw).get("streams", [])
    require(len(streams) == 1, f"Expected one video stream: {path}")
    return int(streams[0]["width"]), int(streams[0]["height"])


def sample_video(path: Path, destination: Path, fps: int, width: int) -> list[Path]:
    destination.mkdir(parents=True, exist_ok=False)
    subprocess.run([
        "ffmpeg", "-v", "error", "-i", str(path),
        "-vf", f"fps={fps},scale={width}:-2:flags=lanczos",
        "-q:v", "3", "-start_number", "0", str(destination / "%04d.jpg")
    ], check=True)
    frames = sorted(destination.glob("*.jpg"))
    require(frames, f"No sampled frames from {path}")
    return frames


def scale_box(box: list[float], sx: float, sy: float) -> list[float]:
    x1, y1, x2, y2 = map(float, box)
    return [x1 * sx, y1 * sy, x2 * sx, y2 * sy]


def validate_box(box: list[float], width: int, height: int) -> None:
    x1, y1, x2, y2 = box
    require(0 <= x1 < x2 <= width and 0 <= y1 < y2 <= height,
            f"Bounding box outside image: {box} vs {(width, height)}")


def annotation(label: str, box: list[float]) -> dict:
    x1, y1, x2, y2 = box
    return {
        "label": label,
        "coordinates": {
            "x": (x1 + x2) / 2.0,
            "y": (y1 + y2) / 2.0,
            "width": x2 - x1,
            "height": y2 - y1,
        },
    }


def add_record(records: list[dict], label: str, image_path: Path,
               boxes: list[list[float]], width: int, height: int) -> None:
    for box in boxes:
        validate_box(box, width, height)
    records.append({
        "imagefilename": image_path.name,
        "annotation": [annotation(label, box) for box in boxes],
    })


def clean_resize(source: Path, destination: Path, width: int) -> tuple[int, int, float]:
    with Image.open(source) as raw:
        upright = ImageOps.exif_transpose(raw).convert("RGB")
        source_width, source_height = upright.size
        scale = width / source_width
        height = round(source_height * scale)
        resized = upright.resize((width, height), Image.Resampling.LANCZOS)
        clean = Image.frombytes("RGB", resized.size, resized.tobytes())
        clean.save(destination, format="JPEG", quality=94, subsampling=0)
    return width, height, scale


def copy_training_frames(label: str, source_id: str, frames: list[Path],
                         boxes: list[list[float]], train: Path,
                         records: list[dict]) -> int:
    count = 0
    for frame in frames:
        with Image.open(frame) as image:
            width, height = image.size
        out = train / f"{source_id}_{frame.stem}.jpg"
        shutil.copyfile(frame, out)
        add_record(records, label, out, boxes, width, height)
        count += 1
    return count


def prepare(spec_path: Path, temporal_root: Path, source_root: Path,
            cache: Path, output: Path, fetch: bool = False) -> dict:
    spec = read_json(spec_path)
    require(spec.get("schema_version") == 1, "Unsupported object prototype schema")
    label = spec["label"]
    target_width = int(spec["image_width"])
    require(label == "grip_bar" and target_width == 640, "Unexpected prototype policy")
    require(not output.exists(), "Refuse to overwrite object-prototype output")
    train = output / "train"
    test = output / "test"
    train.mkdir(parents=True)
    test.mkdir()

    train_records: list[dict] = []
    test_records: list[dict] = []
    groups: dict[str, int] = {}

    # Separate public-domain DVIDS pull-up source, pinned by bytes and SHA-256.
    pullup_row = spec["training"]["pullup_dvids"]
    pullup_source = fetch_pinned(pullup_row, cache, fetch)
    require(probe_video(pullup_source) == (1920, 1080), "DVIDS pull-up geometry changed")
    sampled = sample_video(pullup_source, output / "_pullup_samples",
                           int(pullup_row["sample_fps"]), target_width)
    require(tuple(pullup_row["output_size"]) == (640, 360), "Unexpected pull-up sample geometry")
    groups["pullup_dvids"] = copy_training_frames(
        label, "pullup_dvids", sampled,
        [[float(v) for v in box] for box in pullup_row["boxes"]], train, train_records)

    # Two continuous dip shots already byte-pinned by the temporal fixture.
    for source_id in ("dip_rear", "dip_side"):
        row = spec["training"][source_id]
        video = temporal_root / f"{source_id}.mp4"
        require(video.is_file(), f"Missing prepared temporal clip: {video}")
        input_size = tuple(row["input_size"])
        require(probe_video(video) == input_size, f"{source_id} geometry changed")
        samples = sample_video(video, output / f"_{source_id}_samples",
                               int(row["sample_fps"]), target_width)
        with Image.open(samples[0]) as image:
            out_width, out_height = image.size
        sx, sy = out_width / input_size[0], out_height / input_size[1]
        boxes = [scale_box(box, sx, sy) for box in row["boxes"]]
        groups[source_id] = copy_training_frames(
            label, source_id, samples, boxes, train, train_records)

    # Independent source groups are held out from all Create ML training/validation.
    for row in spec["testing"]:
        source = source_root / row["relative_path"]
        require(source.is_file(), f"Missing held-out source: {source}")
        with Image.open(source) as raw:
            actual = ImageOps.exif_transpose(raw).size
        require(tuple(row["input_size"]) == actual, f'Held-out source geometry changed: {row["id"]}')
        out = test / f'{row["id"]}.jpg'
        out_width, out_height, scale = clean_resize(source, out, target_width)
        boxes = [scale_box(box, scale, scale) for box in row["boxes"]]
        add_record(test_records, label, out, boxes, out_width, out_height)

    # Exactly one JSON annotation file lives in each Create ML data-source directory.
    (train / "annotations.json").write_text(json.dumps(train_records, indent=2, sort_keys=True) + "\n")
    (test / "annotations.json").write_text(json.dumps(test_records, indent=2, sort_keys=True) + "\n")

    # Temporary sampled directories are not part of the Create ML inputs/artifacts.
    for path in output.glob("_*_samples"):
        shutil.rmtree(path)

    manifest = {
        "schema_version": 1,
        "label": label,
        "training_images": len(train_records),
        "training_objects": sum(len(r["annotation"]) for r in train_records),
        "training_source_groups": groups,
        "test_images": len(test_records),
        "test_objects": sum(len(r["annotation"]) for r in test_records),
        "test_source_groups": [r["source_group"] for r in spec["testing"]],
        "pose_inputs": False,
        "annotation_policy": "coarse apparatus boxes frozen before model training; no pose-derived geometry",
        "pullup_source_sha256": pullup_row["sha256"],
        "images": {
            "train": {p.name: digest(p) for p in sorted(train.glob("*.jpg"))},
            "test": {p.name: digest(p) for p in sorted(test.glob("*.jpg"))},
        },
    }
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    return manifest


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--spec", type=Path, default=DEFAULT_SPEC)
    parser.add_argument("--temporal-root", type=Path, required=True)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--cache", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--fetch", action="store_true")
    args = parser.parse_args()
    manifest = prepare(args.spec, args.temporal_root, args.source_root,
                       args.cache, args.output, args.fetch)
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()