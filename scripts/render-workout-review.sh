#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p build/workout-analysis
xcrun swiftc -swift-version 6 -O -parse-as-library -target "$(uname -m)-apple-macos14.0" \
  ios/HangInThere/Analysis/*.swift ios/HangInThere/UI/WorkoutReviewView.swift \
  Evaluation/WorkoutReviewRender.swift -o build/workout-analysis/render-review
build/workout-analysis/render-review
