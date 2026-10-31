#!/usr/bin/env bash
# Native Swift core/transport contracts against the production shared framework.
# A disposable harness avoids adding another production package.
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo 'Native core tests require macOS; use :shared:jvmTest on other hosts.' >&2; exit 1; }
export TERM="${TERM:-dumb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM_NAME=macosx ARCHS="$(uname -m)" CONFIGURATION=Debug "$ROOT/scripts/build-workout-core.sh"
export WORKOUT_FRAMEWORK="$ROOT/build/WorkoutCore/macosx/Debug"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/Sources/HangInThere" "$WORK/Tests/HangInThereTests"
cp "$ROOT"/ios/HangInThere/Analysis/*.swift "$WORK/Sources/HangInThere/"
cp "$ROOT"/ios/HangInThere/App/{WorkoutAnalysisAdapter,WorkoutProgression,WorkoutGripReview,WorkoutRepPresentation,WorkoutCoaching,WorkoutReminders,WorkoutLogging}.swift "$WORK/Sources/HangInThere/"
cp "$ROOT"/ios/HangInThere/Capture/{CountedSetVideoRecorder,WorkoutKeyframes}.swift "$WORK/Sources/HangInThere/"
cp "$ROOT"/ios/HangInThereTests/Core/*.swift "$WORK/Tests/HangInThereTests/"
cat > "$WORK/Package.swift" <<'SWIFT'
// swift-tools-version: 6.0
import PackageDescription
import Foundation
let framework = ProcessInfo.processInfo.environment["WORKOUT_FRAMEWORK"]!
let package = Package(name: "HangInThereCoreTests", platforms: [.macOS(.v14)], targets: [
    .target(name: "HangInThere", swiftSettings: [.unsafeFlags(["-F", framework])],
            linkerSettings: [.unsafeFlags(["-F", framework, "-framework", "WorkoutCore", "-lc++"])]),
    .testTarget(name: "HangInThereTests", dependencies: ["HangInThere"],
                swiftSettings: [.unsafeFlags(["-F", framework])])
])
SWIFT
swift test --package-path "$WORK"
