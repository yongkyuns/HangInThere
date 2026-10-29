#!/usr/bin/env bash
# Focused native DTO/FFI checks after building the macOS WorkoutCore framework.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
export WORKOUT_FRAMEWORK="$ROOT/build/WorkoutCore/macosx/Debug"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/Sources/HangInThere" "$WORK/Tests/HangInThereTests"
cp "$ROOT"/ios/HangInThere/Analysis/*.swift "$WORK/Sources/HangInThere/"
cp "$ROOT"/ios/HangInThere/App/{WorkoutAnalysisAdapter,WorkoutProgression,WorkoutGripReview,WorkoutRepPresentation}.swift "$WORK/Sources/HangInThere/"
cp "$ROOT"/ios/HangInThere/Capture/{CountedSetVideoRecorder,WorkoutKeyframes}.swift "$WORK/Sources/HangInThere/"
cp "$ROOT/ios/HangInThereTests/Core/BodyDipWorkoutNativeTests.swift" "$WORK/Tests/HangInThereTests/"
cat > "$WORK/Package.swift" <<'SWIFT'
// swift-tools-version: 6.0
import PackageDescription
import Foundation
let framework = ProcessInfo.processInfo.environment["WORKOUT_FRAMEWORK"]!
let package = Package(name: "BodyDipNative", platforms: [.macOS(.v14)], targets: [
    .target(name: "HangInThere", swiftSettings: [.unsafeFlags(["-F", framework])],
        linkerSettings: [.unsafeFlags(["-F", framework, "-framework", "WorkoutCore", "-lc++"])]),
    .testTarget(name: "HangInThereTests", dependencies: ["HangInThere"],
        swiftSettings: [.unsafeFlags(["-F", framework])])
])
SWIFT
swift test --package-path "$WORK"
