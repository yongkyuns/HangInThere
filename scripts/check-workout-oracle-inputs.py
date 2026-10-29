#!/usr/bin/env python3
"""Exercise real Gradle invalidation for Swift oracle inputs and parity exports."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def check(workout, setup, output):
    output.mkdir(parents=True, exist_ok=False)
    environment = os.environ.copy()
    # Keep this test's evidence independent of the primary wire-contract export.
    environment.pop("PUBLIC_REPLAY_PROGRESS_REPORT", None)
    for name, source in [("WORKOUT_ORACLE", workout), ("SETUP_ORACLE", setup)]:
        target = output / (name.lower() + ".json")
        shutil.copyfile(source, target)
        environment[name] = str(target)
    counts = {"WORKOUT_PARITY_REPORT": len(json.loads(workout.read_text())["cases"]),
              "SETUP_PARITY_REPORT": len(json.loads(setup.read_text())["cases"])}
    steps = []

    def run(name, cached=False):
        log = output / (name + ".log")
        with log.open("x") as stream:
            result = subprocess.run([str(ROOT / "gradlew"), "-Pworkout.appleOnly=true", ":shared:jvmTest",
                                     "--console=plain", "--no-build-cache"], cwd=ROOT,
                                    env=environment, stdout=stream, stderr=subprocess.STDOUT, check=False)
        if result.returncode:
            raise RuntimeError(f"Gradle oracle check failed: {log}")
        text = log.read_text()
        assert ("> Task :shared:jvmTest UP-TO-DATE" in text) == cached, name
        for key, count in counts.items():
            report = json.loads(Path(environment[key]).read_text())
            assert report["cases"] == count, (name, key, "Stale or missing parity cases")
        steps.append(dict(name=name, reused_result=cached))

    for key in counts:
        environment[key] = str(output / (key.lower() + ".json"))
    run("initial")
    run("unchanged", cached=True)
    for key in ["WORKOUT_ORACLE", "SETUP_ORACLE"]:
        # A byte change preserves the actual cases but must invalidate the task.
        with Path(environment[key]).open("a") as stream:
            stream.write("\n")
        run("changed-" + key.lower())
    for key in counts:
        environment[key] = str(output / ("moved-" + key.lower() + ".json"))
        run("changed-" + key.lower())
        Path(environment[key]).unlink()
        run("missing-" + key.lower())
    report = dict(scope=__doc__, passed=True, steps=steps,
                  source_sha256={str(path): hashlib.sha256(path.read_bytes()).hexdigest() for path in [workout, setup]})
    (output / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(f"Passed {len(steps)} real Gradle oracle input/output checks: {output / 'report.json'}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--workout", type=Path, default=ROOT / "build/workout-oracle/oracle.json")
    parser.add_argument("--setup", type=Path, default=ROOT / "build/setup-oracle/oracle.json")
    parser.add_argument("--output", type=Path, default=ROOT / "build/workout-oracle-input-check")
    args = parser.parse_args()
    check(args.workout.resolve(), args.setup.resolve(), args.output.resolve())
