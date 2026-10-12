#!/usr/bin/env bash
# Xcode build phase: compile the production shared engine for the selected SDK.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CONFIG="${CONFIGURATION:-Debug}"
SDK="${PLATFORM_NAME:-iphonesimulator}"
ARCHITECTURES="${ARCHS:-$(uname -m)}"
case "$CONFIG" in Debug|Release) ;; *) echo "Unsupported configuration: $CONFIG" >&2; exit 2;; esac
case "$SDK" in iphoneos|iphonesimulator|macosx) ;; *) echo "Unsupported SDK: $SDK" >&2; exit 2;; esac
DEST="build/WorkoutCore/$SDK/$CONFIG"
mkdir -p "$DEST"
frameworks=()
for arch in $ARCHITECTURES; do
  case "$SDK:$arch" in
    iphoneos:arm64) target=IosArm64; directory=iosArm64;;
    iphonesimulator:arm64) target=IosSimulatorArm64; directory=iosSimulatorArm64;;
    iphonesimulator:x86_64) target=IosX64; directory=iosX64;;
    macosx:arm64) target=MacosArm64; directory=macosArm64;;
    macosx:x86_64) target=MacosX64; directory=macosX64;;
    *) echo "Unsupported SDK/architecture: $SDK/$arch" >&2; exit 2;;
  esac
  ./gradlew -Pworkout.appleOnly=true ":shared:link${CONFIG}Framework${target}" --console=plain
  type="$(printf '%s' "$CONFIG" | tr '[:upper:]' '[:lower:]')Framework"
  frameworks+=("shared/build/bin/$directory/$type/WorkoutCore.framework")
done
# Preserve the generated header/module map; combine static binaries for a fat simulator build.
rm -rf "$DEST/WorkoutCore.framework"
cp -R "${frameworks[0]}" "$DEST/WorkoutCore.framework"
if [[ ${#frameworks[@]} -gt 1 ]]; then
  binaries=(); for framework in "${frameworks[@]}"; do binaries+=("$framework/WorkoutCore"); done
  xcrun lipo -create "${binaries[@]}" -output "$DEST/WorkoutCore.framework/WorkoutCore"
fi
