#!/usr/bin/env bash
# No new app target/package or dependency; use the production decoder and estimator.
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo 'Vision batch inference requires macOS. Inventory/scoring tests also run on Linux.' >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM_NAME=macosx ARCHS="$(uname -m)" CONFIGURATION=Debug "$ROOT/scripts/build-workout-core.sh"
FRAMEWORK="$ROOT/build/WorkoutCore/macosx/Debug"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
xcrun swiftc -O -F "$FRAMEWORK" -framework WorkoutCore -lc++ -swift-version 6 -parse-as-library \
  "$ROOT"/ios/HangInThere/Analysis/*.swift \
  "$ROOT/ios/HangInThere/Capture/VideoReplayReader.swift" \
  "$ROOT/ios/HangInThere/Capture/BitmapFrameRenderer.swift" \
  "$ROOT/ios/HangInThere/Pose/VisionPoseEstimator.swift" \
  "$ROOT/Evaluation/PoseBatch.swift" -o "$WORK/pose-batch"
python3 "$ROOT/scripts/evaluation.py" run "$@" --engine "$WORK/pose-batch"
