#!/usr/bin/env bash
# Qualify the exact non-UI app pipeline against macOS Apple frameworks.
# This is host integration evidence, NOT iPhone/simulator performance or UI evidence.
set -euo pipefail
[[ "$(uname -s)" == Darwin ]] || { echo 'Apple host tests require macOS.' >&2; exit 1; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
python3 "$ROOT/scripts/prepare-fixtures.py" --verify-only
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/Sources/HangInThere" "$WORK/Tests/HangInThereTests"
cp "$ROOT"/HangInThere/Analysis/*.swift "$WORK/Sources/HangInThere/"
cp "$ROOT"/HangInThere/Capture/*.swift "$WORK/Sources/HangInThere/"
cp "$ROOT"/HangInThere/Pose/*.swift "$WORK/Sources/HangInThere/"
cp "$ROOT/HangInThere/App/ReplayController.swift" "$WORK/Sources/HangInThere/"
cp "$ROOT"/HangInThereTests/Core/*.swift "$WORK/Tests/HangInThereTests/"
cp "$ROOT"/HangInThereTests/Integration/*.swift "$WORK/Tests/HangInThereTests/"
cp -R "$ROOT/HangInThereTests/Fixtures" "$WORK/Tests/HangInThereTests/Fixtures"
cat > "$WORK/Package.swift" <<'SWIFT'
// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "HangInThereAppleHostTests", platforms: [.macOS(.v14)], targets: [
    .target(name: "HangInThere"),
    .testTarget(name: "HangInThereTests", dependencies: ["HangInThere"],
                resources: [.copy("Fixtures")])
])
SWIFT
mkdir -p "$ROOT/build"
{
  printf 'Target: native macOS; source: %s\n' "$(git -C "$ROOT" rev-parse HEAD)"
  sw_vers
  xcrun swift --version
  swift test --package-path "$WORK" --no-parallel
} 2>&1 | tee "$ROOT/build/apple-host-tests.txt"
