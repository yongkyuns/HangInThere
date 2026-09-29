#!/usr/bin/env python3
"""Download and checksum-pin the official MediaPipe Heavy pose task model."""
from __future__ import annotations
import argparse, hashlib, json, os
from pathlib import Path
import urllib.request

URL = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/1/pose_landmarker_heavy.task"
SHA256 = "64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b"
MODEL_ID = "pose_landmarker_heavy/float16/1"


def digest(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--metadata", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if not args.output.exists() or digest(args.output) != SHA256:
        temporary = args.output.with_suffix(args.output.suffix + ".tmp")
        try:
            request = urllib.request.Request(URL, headers={"User-Agent": "HangInThere-evaluation/1"})
            with urllib.request.urlopen(request, timeout=120) as response, temporary.open("wb") as target:
                while block := response.read(1024 * 1024):
                    target.write(block)
            actual = digest(temporary)
            if actual != SHA256:
                raise SystemExit(f"MediaPipe model checksum mismatch: {actual}")
            os.replace(temporary, args.output)
        finally:
            if temporary.exists(): temporary.unlink()
    metadata = {"model_id": MODEL_ID, "url": URL, "sha256": SHA256, "bytes": args.output.stat().st_size}
    args.metadata.parent.mkdir(parents=True, exist_ok=True)
    args.metadata.write_text(json.dumps(metadata, indent=2, sort_keys=True) + "\n")
    print(json.dumps(metadata, sort_keys=True))

if __name__ == "__main__":
    main()