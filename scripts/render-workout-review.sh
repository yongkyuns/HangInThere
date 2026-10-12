#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p build/workout-analysis
PLATFORM_NAME=macosx ARCHS="$(uname -m)" CONFIGURATION=Debug scripts/build-workout-core.sh
FRAMEWORK="$ROOT/build/WorkoutCore/macosx/Debug"
xcrun swiftc -swift-version 6 -O -parse-as-library -target "$(uname -m)-apple-macos14.0" \
  -F "$FRAMEWORK" -framework WorkoutCore -lc++ \
  ios/HangInThere/Analysis/*.swift ios/HangInThere/App/WorkoutAnalysisAdapter.swift \
  ios/HangInThere/UI/WorkoutReviewView.swift ios/HangInThere/UI/WorkoutPracticeView.swift \
  ios/HangInThere/UI/LiveWorkoutOverlay.swift ios/HangInThere/UI/ExerciseProgressView.swift \
  Evaluation/WorkoutReviewRender.swift -o build/workout-analysis/render-review
build/workout-analysis/render-review
