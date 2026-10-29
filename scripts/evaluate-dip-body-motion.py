#!/usr/bin/env python3
"""Local, development-only rail-free dip experiment. Never uploads media.

Usage: evaluate-dip-body-motion.py VIDEO REFERENCE_JSON NEW_OUTPUT_DIRECTORY
Reference: videoSHA256, expectedCycles, returnWindows, zeroCountWindows
with name/start/end. Labels are used by scoring/slicing only, never the detector.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

import temporal_native_replay

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__)
    video, reference_path, output = [Path(x).resolve() for x in sys.argv[1:]]
    reference = json.loads(reference_path.read_text())
    assert digest(video) == reference["videoSHA256"], "Reference belongs to different media"
    output.mkdir(parents=True, exist_ok=False)
    shutil.copy2(reference_path, output / "reference.json")
    package = output / "exporter"
    sources = package / "Sources/dip-body-export"
    hashes = temporal_native_replay.snapshot_compilation_sources(ROOT, sources)
    (sources / "PublicWorkoutReplay.swift").unlink()
    driver = ROOT / "Evaluation/DipBodyMotionExport.swift"
    shutil.copy2(driver, sources / driver.name)
    for name in ["Evaluation/DipBodyMotionExport.swift", "Evaluation/android/DipBodyMotionExperiment.kt",
                 "Evaluation/androidTests/DipBodyMotionExperimentTests.kt", "scripts/evaluate-dip-body-motion.py"]:
        hashes[name] = digest(ROOT / name)
    env = dict(os.environ)
    env.update(PLATFORM_NAME="macosx", ARCHS=os.uname().machine, CONFIGURATION="Debug",
               WORKOUT_FRAMEWORK=str(ROOT / "build/WorkoutCore/macosx/Debug"),
               OBSERVED_RAIL_PACKAGE=str(ROOT / "native/apple"),
               GRADLE_OPTS=env.get("GRADLE_OPTS", "") + " -Dorg.gradle.workers.max=2 -Dorg.gradle.parallel=false")

    def run(args, log):
        with (output / log).open("w") as stream:
            subprocess.run([str(x) for x in args], cwd=ROOT, env=env, stdout=stream,
                           stderr=subprocess.STDOUT, check=True)

    def verify_sources():
        assert all(digest(ROOT / name) == sha for name, sha in hashes.items()), "Sources changed during evaluation"

    (package / "Package.swift").write_text('''// swift-tools-version: 6.0
import PackageDescription
import Foundation
let env = ProcessInfo.processInfo.environment
let framework = env["WORKOUT_FRAMEWORK"]!
let package = Package(name: "DipBodyExport", platforms: [.macOS(.v15)],
 dependencies: [.package(path: env["OBSERVED_RAIL_PACKAGE"]!)], targets: [
 .executableTarget(name: "dip-body-export", dependencies: [.product(name: "ObservedRailPixels", package: "apple")],
 swiftSettings: [.unsafeFlags(["-F",framework])],
 linkerSettings: [.unsafeFlags(["-F",framework,"-framework","WorkoutCore","-lc++"])])])
''')
    print("Building the native observation exporter", flush=True)
    run([ROOT / "scripts/build-workout-core.sh"], "framework.log")
    verify_sources()
    framework_hash = digest(Path(env["WORKOUT_FRAMEWORK"]) / "WorkoutCore.framework/WorkoutCore")
    run(["swift", "build", "--package-path", package, "--configuration", "release", "--jobs", "2"], "exporter-build.log")
    binary = package / ".build/release/dip-body-export"
    run([binary, video, output / "observations"], "export.log")
    for kind in ["workout", "setup"]:
        if not (ROOT / f"build/{kind}-oracle/oracle.json").exists():
            run([sys.executable, ROOT / f"scripts/prepare-{kind}-oracle.py"], f"{kind}-oracle.log")
    env.update(DIP_BODY_POSES=str(output / "observations/poses.jsonl"),
               DIP_BODY_REPORT=str(output / "results.json"), DIP_BODY_REFERENCE=str(output / "reference.json"))
    print("Running body-motion controls and all source observations", flush=True)
    run([ROOT / "gradlew", "-Pworkout.appleOnly=true", ":shared:jvmTest", "--rerun", "--tests",
         "dev.hanginthere.evaluation.DipBodyMotionExperimentTests", "--max-workers=2", "--no-parallel", "--console=plain"], "tests.log")
    results = json.loads((output / "results.json").read_text())
    export = json.loads((output / "observations/export.json").read_text())
    variants = results["variants"]
    rows = variants["original"]
    events = [r["event"] for r in rows if r.get("event")]
    pending = list(enumerate(reference["returnWindows"]))
    matches, extras = [], []
    for event in events:
        candidates = [(i, window) for i, window in pending if window[0] <= event["counted"] <= window[1]]
        if len(candidates) == 1:
            item = candidates[0]; pending.remove(item)
            matches.append({"rep": item[0] + 1, **event})
        else:
            extras.append(event)
    controls = []
    for window in reference["zeroCountWindows"]:
        isolated = variants["control_" + window["name"]]
        whole_events = [e for e in events if window["start"] <= e["counted"] <= window["end"]]
        assert isolated, "Control window has no source frames"
        controls.append({**window, "frames": len(isolated), "isolatedCycles": isolated[-1]["cycles"],
                         "wholeVideoCycles": len(whole_events)})
    no_legs = variants["noLowerBody"]
    same_without_legs = [(r["phase"], r["cycles"], r["reason"], r.get("event")) for r in rows] == [
        (r["phase"], r["cycles"], r["reason"], r.get("event")) for r in no_legs]
    verify_sources()
    assert digest(video) == reference["videoSHA256"], "Media changed during evaluation"
    assert digest(Path(env["WORKOUT_FRAMEWORK"]) / "WorkoutCore.framework/WorkoutCore") == framework_hash
    summary = dict(method=results["method"], version=results["version"], frames=len(rows),
        sourceClockVerified=export["sourceClockVerified"] and export["frames"] == len(rows),
        expectedCycles=reference["expectedCycles"], observedCycles=rows[-1]["cycles"], matches=matches,
        missedReferenceWindows=[i + 1 for i, _ in pending], extras=extras, zeroCountControls=controls,
        identicalWithoutLowerBody=same_without_legs, sourceSHA256=hashes,
        videoSHA256=digest(video), observationsSHA256=digest(output / "observations/poses.jsonl"),
        referenceSHA256=digest(output / "reference.json"), exporterSHA256=digest(binary), frameworkSHA256=framework_hash,
        scope=results["scope"])
    summary["developmentCheckPassed"] = (summary["sourceClockVerified"] and not pending and not extras
        and len(matches) == reference["expectedCycles"] and same_without_legs
        and all(c["isolatedCycles"] == c["wholeVideoCycles"] == 0 for c in controls))
    (output / "summary.json").write_text(json.dumps(summary, indent=2, sort_keys=True) + "\n")
    print(json.dumps({k: summary[k] for k in ["observedCycles", "expectedCycles", "identicalWithoutLowerBody", "developmentCheckPassed"]}))
    if not summary["developmentCheckPassed"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
