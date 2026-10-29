#!/usr/bin/env python3
"""Run local, serial native/SDK/shared feature extraction after frozen review.

Usage: extract-public-grip.py INPUT_ROOT ANNOTATIONS OUTPUT_ROOT EXPORTER PYTHON
Existing native frame exports may be reused only after their video pin matches.
Stages produce fresh files; no classifier predictions enter annotations.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    inputs, annotations, output, exporter = [Path(p).resolve() for p in sys.argv[1:5]]
    # Keep the venv executable path: resolving its symlink bypasses pyvenv.cfg.
    python = Path(sys.argv[5]).absolute()
    manifest = json.loads((inputs / "manifest.json").read_text())
    review = json.loads(annotations.read_text())
    cases = {c["id"]: c for c in manifest["cases"]}
    assert set(cases) == {c["id"] for c in review["cases"]}
    env = dict(os.environ)
    env.update(OMP_NUM_THREADS="2", OPENBLAS_NUM_THREADS="2")

    def run(args, log, extra=None):
        with log.open("w") as stream:
            subprocess.run([str(x) for x in args], cwd=ROOT, env=env | (extra or {}),
                           stdout=stream, stderr=subprocess.STDOUT, check=True)

    for annotation in review["cases"]:
        c = cases[annotation["id"]]
        video = inputs / c["media"]["path"]
        assert hashlib.sha256(video.read_bytes()).hexdigest() == c["media"]["sha256"] == annotation["recordingId"]
        if annotation["status"] != "binaryLabel":
            continue
        directory = output / c["id"]; directory.mkdir(parents=True, exist_ok=True)
        frames = directory / "frames"
        if frames.exists():
            intake = json.loads((frames / "intake.json").read_text())
            assert intake["videoSHA256"] == annotation["recordingId"]
        else:
            run([exporter, video, frames], directory / "export.log")
        run([python, ROOT / "scripts/extract-binary-grip-observations.py", "body", frames, directory / "body.jsonl"], directory / "body.log")
        for mode, source, target in [("plan", "body", "planned"), ("features", "hands", "features")]:
            extra = dict(BINARY_GRIP_MODE=mode, BINARY_GRIP_INPUT=str(directory / f"{source}.jsonl"),
                         BINARY_GRIP_OUTPUT=str(directory / f"{target}.jsonl"))
            run([ROOT / "gradlew", "-Pworkout.appleOnly=true", ":shared:jvmTest", "--rerun", "--tests",
                 "*BinaryGripLearningPipelineTests", "--max-workers=2", "--no-parallel", "--console=plain"], directory / f"{mode}.log", extra)
            if mode == "plan":
                run([python, ROOT / "scripts/extract-binary-grip-observations.py", "hands", directory / "planned.jsonl",
                     frames, directory / "hands.jsonl"], directory / "hands.log")
        print("Extracted", c["id"], annotation["split"], flush=True)


if __name__ == "__main__":
    main()
