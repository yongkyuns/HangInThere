#!/usr/bin/env bash
# Official iOS binary release with both arm64 and x86_64 simulator support.
# Archives are checksum-pinned; SDK binaries stay in ignored build outputs.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/build/grip-sdk"
mkdir -p "$DEST/downloads"
for DOMAIN in Common Vision; do
  case "$DOMAIN" in
    Common) HASH=1cb1eed8e9eb7868a93fa3c3ce3a341e352620158f29f9799800376f651bd004 ;;
    Vision) HASH=5191fca73bcb9883770c5b987d812b15fcfbffd0bcd46d64a91adf5e89dda91f ;;
  esac
  ARCHIVE="$DEST/downloads/MediaPipeTasks$DOMAIN-0.10.21.tar.gz"
  if [[ ! -f "$ARCHIVE" ]]; then
    curl --fail --location --retry 3 --silent --show-error \
      "https://dl.google.com/cpdc/20250131-063213/MediaPipeTasks$DOMAIN-0.10.21.tar.gz" -o "$ARCHIVE.tmp"
    mv "$ARCHIVE.tmp" "$ARCHIVE"
  fi
  echo "$HASH  $ARCHIVE" | shasum -a 256 -c - >/dev/null
  if [[ ! -f "$DEST/$DOMAIN/.extracted-$HASH" ]]; then
    mkdir -p "$DEST/$DOMAIN"
    tar -xzf "$ARCHIVE" -C "$DEST/$DOMAIN"
    touch "$DEST/$DOMAIN/.extracted-$HASH"
  fi
  for PLATFORM in iphoneos iphonesimulator; do
    if [[ "$PLATFORM" == iphoneos ]]; then SLICE=ios-arm64; GRAPH=device; else SLICE=ios-arm64_x86_64-simulator; GRAPH=simulator; fi
    mkdir -p "$DEST/$PLATFORM"
    ln -sfn "$DEST/$DOMAIN/frameworks/MediaPipeTasks$DOMAIN.xcframework/$SLICE/MediaPipeTasks$DOMAIN.framework" "$DEST/$PLATFORM/MediaPipeTasks$DOMAIN.framework"
    if [[ "$DOMAIN" == Common ]]; then
      ln -sfn "$DEST/Common/frameworks/graph_libraries/libMediaPipeTasksCommon_${GRAPH}_graph.a" "$DEST/$PLATFORM/graphs.a"
    fi
  done
done
