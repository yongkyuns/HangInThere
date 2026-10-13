#!/usr/bin/env bash
# Compile and run the actual Apple FFI against real Swift counter observations.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
PLATFORM_NAME=macosx ARCHS="$(uname -m)" CONFIGURATION=Debug scripts/build-workout-core.sh
FRAMEWORK="$ROOT/build/WorkoutCore/macosx/Debug"
mkdir -p build/workout-analysis
swiftc -swift-version 6 -parse-as-library -O -F "$FRAMEWORK" -framework WorkoutCore -lc++ \
  ios/HangInThere/Analysis/*.swift ios/HangInThere/App/WorkoutAnalysisAdapter.swift \
  ios/HangInThere/App/WorkoutProgression.swift ios/HangInThere/App/WorkoutGripReview.swift \
  Evaluation/WorkoutAnalysisBridgeCheck.swift -o build/workout-analysis/apple-bridge-check
build/workout-analysis/apple-bridge-check "$@"
