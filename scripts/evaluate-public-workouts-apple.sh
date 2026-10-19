#!/usr/bin/env bash
# Real production Apple replay + common analytics. Host evidence, not device timing.
set -euo pipefail
[[ $# == 3 || $# == 5 ]] || { echo 'Usage: evaluate-public-workouts-apple.sh MANIFEST ROOT OUTPUT [SHARD_INDEX SHARD_COUNT]' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
FRAMEWORK="$ROOT/build/WorkoutCore/macosx/Debug"
export WORKOUT_FRAMEWORK="$FRAMEWORK"
export OBSERVED_RAIL_PACKAGE="$ROOT/native/apple"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
python3 - "$ROOT" "$FRAMEWORK" "$WORK/source-snapshot.json" <<'PY'
import json, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1]); sys.path.insert(0, str(root/'scripts'))
import evaluation
paths = [p for folder in ['Analysis', 'Capture', 'Pose'] for p in sorted((root/'ios/HangInThere'/folder).glob('*.swift'))]
paths += [root/p for p in ['ios/HangInThere/App/WorkoutAnalysisAdapter.swift',
    'ios/HangInThere/App/WorkoutProgression.swift', 'ios/HangInThere/App/WorkoutGripReview.swift',
    'ios/HangInThere/App/ReplayController.swift', 'ios/HangInThere/UI/QualificationReportDocument.swift',
    'Evaluation/PublicWorkoutReplay.swift', 'Evaluation/PublicReplayMeasurements.swift',
    'scripts/evaluate-public-workouts-apple.sh']]
paths += [p for p in (root/'shared/src').rglob('*') if p.is_file()]
paths += [root/p for p in ['build.gradle.kts', 'settings.gradle.kts', 'gradle.properties',
    'shared/build.gradle.kts', 'gradlew', 'gradle/wrapper/gradle-wrapper.jar',
    'gradle/wrapper/gradle-wrapper.properties', 'scripts/build-workout-core.sh']]
paths += [p for p in (root/'native/apple').rglob('*')
          if p.is_file() and not {'.build', '.swiftpm', '__pycache__'}.intersection(p.parts)
          and p.name != '.DS_Store']
# Build the unmodified application sources in one executable target. SwiftPM
# resolves the same native pixel package as the app and the host test harness;
# direct swiftc compilation cannot resolve its C++/OpenCV module dependency.
sources = pathlib.Path(sys.argv[3]).parent/'Sources/public-replay'
sources.mkdir(parents=True)
swift_sources = [p for p in paths if p.suffix == '.swift' and root/'native/apple' not in p.parents]
assert len({p.name for p in swift_sources}) == len(swift_sources), 'Duplicate source names'
for p in swift_sources:
    (sources/p.name).write_bytes(p.read_bytes())
snapshot = {'source_revision': subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),
    'source_dirty': bool(subprocess.check_output(['git','-C',str(root),'status','--porcelain','--untracked-files=no'],text=True).strip()),
    'source_sha256': {str(p.relative_to(root)):evaluation.digest(p) for p in paths},
    'toolchain': subprocess.check_output(['xcrun','swift','--version'],text=True).strip()}
pathlib.Path(sys.argv[3]).write_text(json.dumps(snapshot))
PY
PLATFORM_NAME=macosx ARCHS="$(uname -m)" CONFIGURATION=Debug "$ROOT/scripts/build-workout-core.sh"
python3 - "$ROOT" "$FRAMEWORK" "$WORK/source-snapshot.json" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1]); sys.path.insert(0, str(root/'scripts'))
import evaluation
path=pathlib.Path(sys.argv[3]); snapshot=json.loads(path.read_text())
assert all(evaluation.digest(root/p)==sha for p,sha in snapshot['source_sha256'].items()), 'Sources changed during native framework build'
framework=pathlib.Path(sys.argv[2])/'WorkoutCore.framework/WorkoutCore'
snapshot['source_sha256'][str(framework.relative_to(root))]=evaluation.digest(framework)
path.write_text(json.dumps(snapshot))
PY
cat > "$WORK/Package.swift" <<'SWIFT'
// swift-tools-version: 6.0
import PackageDescription
import Foundation
let framework = ProcessInfo.processInfo.environment["WORKOUT_FRAMEWORK"]!
let pixels = ProcessInfo.processInfo.environment["OBSERVED_RAIL_PACKAGE"]!
let package = Package(name: "PublicWorkoutReplay", platforms: [.macOS(.v15)],
    dependencies: [.package(path: pixels)], targets: [
        .executableTarget(name: "public-replay", dependencies: [
            .product(name: "ObservedRailPixels", package: "apple")
        ], swiftSettings: [.unsafeFlags(["-F", framework])],
        linkerSettings: [.unsafeFlags(["-F", framework, "-framework", "WorkoutCore", "-lc++"])])
    ])
SWIFT
swift build --package-path "$WORK" --configuration release --product public-replay
REPLAY_BINARY="$(swift build --package-path "$WORK" --configuration release --show-bin-path)/public-replay"
"$REPLAY_BINARY" "$1" "$2" "$3" "$(git -C "$ROOT" rev-parse HEAD)" "${4:-0}" "${5:-1}"
python3 - "$ROOT" "$WORK/source-snapshot.json" "$REPLAY_BINARY" "$3" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1]);sys.path.insert(0,str(root/'scripts'))
import evaluation
snapshot=json.loads(pathlib.Path(sys.argv[2]).read_text())
assert all(evaluation.digest(root/p)==sha for p,sha in snapshot['source_sha256'].items()), 'Compiled sources changed during evaluation'
report=pathlib.Path(sys.argv[4]);assert json.loads(report.read_text())['source_revision']==snapshot['source_revision'], 'App revision changed during evaluation'
snapshot.update(executable_sha256=evaluation.digest(sys.argv[3]),native_report_sha256=evaluation.digest(report))
with pathlib.Path(str(report)+'.provenance.json').open('x') as stream:json.dump(snapshot,stream,indent=2,sort_keys=True);stream.write('\n')
PY
