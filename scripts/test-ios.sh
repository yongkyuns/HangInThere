#!/usr/bin/env bash
# Run from any working directory. Uses the selected Xcode (DEVELOPER_DIR or xcode-select).
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
mkdir -p build
xcodebuild -version | tee build/toolchain.txt
xcrun swift --version | tee -a build/toolchain.txt
uname -m | tee -a build/toolchain.txt

# Compile a real device target, but do not sign, install, archive, or publish it.
xcodebuild build \
  -project HangInThere.xcodeproj -scheme HangInThere -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee build/device-build.txt

python3 scripts/prepare-fixtures.py --verify-only
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
# Xcode refuses to overwrite an existing result bundle. Delete this output only.
rm -rf build/SimulatorTests.xcresult
xcodebuild test \
  -project HangInThere.xcodeproj -scheme HangInThere -configuration Debug \
  -destination "platform=iOS Simulator,id=${SIMULATOR_UDID}" \
  -derivedDataPath build/DerivedData -resultBundlePath build/SimulatorTests.xcresult \
  -parallel-testing-enabled NO 2>&1 | tee build/simulator-tests.txt