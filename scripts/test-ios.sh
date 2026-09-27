#!/usr/bin/env bash
# Run from any working directory. Uses the selected Xcode (DEVELOPER_DIR or xcode-select).
set -euo pipefail
SUITE="${1:-all}"
if [[ $# -gt 1 ]] || [[ "$SUITE" != all && "$SUITE" != mechanics && "$SUITE" != vision ]]; then
  echo 'Usage: test-ios.sh [all|mechanics|vision]' >&2
  exit 2
fi
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p build
xcodebuild -version | tee build/toolchain.txt
xcrun swift --version | tee -a build/toolchain.txt
uname -m | tee -a build/toolchain.txt

# The mechanics job owns device compilation; the separate Vision job remains
# independently runnable. The default "all" still builds and tests everything.
if [[ "$SUITE" != vision ]]; then
# Compile a real device target, but do not sign, install, archive, or publish it.
xcodebuild build \
  -project HangInThere.xcodeproj -scheme HangInThere -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee build/device-build.txt
fi

# Decoder/controller tests generate their own video and never need model media.
if [[ "$SUITE" != mechanics ]]; then
  python3 scripts/prepare-fixtures.py --verify-only
fi
xcrun simctl list devices available --json > build/simulators.json
SDK_VERSION="$(xcrun --sdk iphonesimulator --show-sdk-version)"
export SDK_VERSION
SIMULATOR_UDID="$(python3 - <<'PY'
import json, os, sys
from pathlib import Path
version = '-'.join(os.environ['SDK_VERSION'].split('.')[:2])
wanted = 'com.apple.CoreSimulator.SimRuntime.iOS-' + version
items = json.loads(Path('build/simulators.json').read_text())['devices']
candidates = [d for runtime, devices in items.items()
              if runtime == wanted or runtime.startswith(wanted + '-')
              for d in devices if d.get('isAvailable') and d['name'].startswith('iPhone')]
if not candidates:
    sys.exit('No available iPhone simulator for the selected SDK. Install that runtime in Xcode; see build/simulators.json.')
candidates.sort(key=lambda d: (d['state'] != 'Booted', d['name']))
print(candidates[0]['udid'])
PY
)"
printf 'Simulator UDID: %s\n' "$SIMULATOR_UDID" | tee -a build/toolchain.txt
# Separate evidence bundles prevent one test partition from overwriting another.
RESULT_BUNDLE="build/Simulator-${SUITE}.xcresult"
TEST_LOG="build/simulator-${SUITE}-tests.txt"
# Xcode refuses to overwrite an existing result bundle. Delete this output only.
rm -rf "$RESULT_BUNDLE"
test_command=(xcodebuild test
  -project HangInThere.xcodeproj -scheme HangInThere -configuration Debug
  -destination "platform=iOS Simulator,id=${SIMULATOR_UDID}"
  -derivedDataPath build/DerivedData -resultBundlePath "$RESULT_BUNDLE"
  -parallel-testing-enabled NO)
case "$SUITE" in
  mechanics) test_command+=(-skip-testing:HangInThereTests/VisionSmokeTests) ;;
  vision) test_command+=(-only-testing:HangInThereTests/VisionSmokeTests) ;;
esac
"${test_command[@]}" 2>&1 | tee "$TEST_LOG"
# xcodebuild can succeed with an unmatched filter. Zero selected tests is not
# qualification. Keep pipefail above so any actual test failure stays a failure.
if ! grep -Eq 'Test run with [1-9][0-9]* tests' "$TEST_LOG"; then
  echo "No nonzero Swift Testing run recorded for $SUITE; refusing an empty green check." >&2
  exit 1
fi
