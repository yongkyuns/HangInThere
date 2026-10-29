#!/usr/bin/env python3
"""Prepare unlabeled source views for visual grip review before inference.

Usage: prepare-public-grip-review.py PREPARED_INPUT_ROOT NEW_OUTPUT_DIRECTORY
Uses all manifest cases, three fixed positions in each published exercise window,
verified originals, ffmpeg and Pillow. No classifier is invoked.
"""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from PIL import Image, ImageDraw


def main():
    base, output = [Path(p).resolve() for p in sys.argv[1:]]
    manifest = json.loads((base / "manifest.json").read_text())
    output.mkdir(parents=True, exist_ok=False)
    index = []
    for n, case in enumerate(manifest["cases"]):
        assert re.fullmatch(r"[A-Za-z0-9_-]+", case["id"])
        video = base / case["media"]["path"]
        assert hashlib.sha256(video.read_bytes()).hexdigest() == case["media"]["sha256"]
        start, end = case["labels"]["count_window_seconds"]
        times = [start + (end-start)*fraction for fraction in [.2, .5, .8]]
        names = []
        for i, time in enumerate(times):
            target = output / f'{case["id"]}-{i}.jpg'
            subprocess.run(["ffmpeg", "-v", "error", "-ss", str(time), "-i", str(video), "-frames:v", "1", "-vf",
                "scale=720:720:force_original_aspect_ratio=decrease", "-q:v", "2", str(target)], check=True)
            names.append(target.name)
        index.append(dict(index=n, id=case["id"], sha256=case["media"]["sha256"],
                          source_group=case["source_group"], times=times, images=names))
    for start in range(0, len(index), 6):
        sheet = Image.new("RGB", (1200, 1800), "#eeeeee"); draw = ImageDraw.Draw(sheet)
        for j, row in enumerate(index[start:start+6]):
            for k, name in enumerate(row["images"]):
                with Image.open(output/name) as original:
                    image = original.copy()
                image.thumbnail((400, 270)); x=k*400; y=j*300
                sheet.paste(image, (x+(400-image.width)//2, y))
                draw.text((x+4, y+273), f'{row["index"]} {row["id"]} {row["times"][k]:.2f}s', fill="black")
        sheet.save(output/f"sheet-{start//6:02d}.jpg")
    (output/"index.json").write_text(json.dumps(index, indent=2)+"\n")
    print(f"{len(index)} originals verified; unlabeled review views prepared")


if __name__ == "__main__":
    main()
