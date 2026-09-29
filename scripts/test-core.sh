#!/usr/bin/env bash
# Exercise the exact framework-free app sources and test files on Linux or macOS.
# A disposable SwiftPM harness avoids adding a second project/package to the app.
set -euo pipefail
export TERM="${TERM:-dumb}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/Sources/HangInThere" "$WORK/Tests/HangInThereTests"
cp "$ROOT"/HangInThere/Analysis/*.swift "$WORK/Sources/HangInThere/"
cp "$ROOT"/HangInThereTests/Core/*.swift "$WORK/Tests/HangInThereTests/"
cat > "$WORK/Package.swift" <<'SWIFT'
// swift-tools-version: 6.0
import PackageDescription
let package = Package(name: "HangInThereCoreTests", platforms: [.macOS(.v13)], targets: [
    .target(name: "HangInThere"),
    .testTarget(name: "HangInThereTests", dependencies: ["HangInThere"])
])
SWIFT
swift test --package-path "$WORK"