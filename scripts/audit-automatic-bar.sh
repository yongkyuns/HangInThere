#!/usr/bin/env bash
# Evaluate an explicitly supplied local clip with the production Apple backends.
# The JSON contains image geometry; keep outputs private for private footage.
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo 'Automatic bar audit requires macOS.' >&2; exit 1; }
[[ $# == 2 ]] || { echo 'Usage: audit-automatic-bar.sh VIDEO OUTPUT.json' >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
xcrun swiftc -Onone -swift-version 6 -parse-as-library \
  "$ROOT"/ios/HangInThere/Analysis/*.swift \
  "$ROOT/ios/HangInThere/Capture/VideoReplayReader.swift" \
  "$ROOT/ios/HangInThere/Capture/BitmapFrameRenderer.swift" \
  "$ROOT/ios/HangInThere/Pose/VisionPoseEstimator.swift" \
  "$ROOT/ios/HangInThere/Pose/VisionBarDetector.swift" \
  "$ROOT/ios/HangInThere/Pose/VisionStaticSceneRegistration.swift" \
  "$ROOT/Evaluation/AutomaticBarAudit.swift" -o "$WORK/automatic-bar-audit"
"$WORK/automatic-bar-audit" "$1" "$2"
