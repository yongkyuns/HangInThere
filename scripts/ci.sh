#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
xcodebuild -version
xcodebuild -showsdks
uname -m
xcrun simctl list devices available --json > build/simulators.json
SIMULATOR_UDID=$(python3 - <<'PY'
import json
with open('build/simulators.json') as f:
    devices = json.load(f)['devices']
choices = [d for runtime, ds in devices.items() if runtime.endswith('iOS-18-5')
           for d in ds if d['isAvailable'] and d['name'].startswith('iPhone')]
if not choices:
    raise SystemExit('Pinned iOS 18.5 simulator missing. Review the runner image; do not silently change test OS.')
print(sorted(choices, key=lambda d: d['name'])[0]['udid'])
PY
)
echo "Simulator destination: $SIMULATOR_UDID"
status=0
xcodebuild test -project HangInThere.xcodeproj -scheme HangInThere \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" -destination-timeout 180 \
  -parallel-testing-enabled NO -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 120 -maximum-test-execution-time-allowance 120 \
  -resultBundlePath build/SimulatorTests.xcresult ONLY_ACTIVE_ARCH=YES \
  > build/simulator.log 2>&1 || status=$?
# Keep the full log in artifacts, and actionable diagnostics in the job output.
grep -E 'error:|warning:|Test (Case|Suite)|Executed|\*\* TEST|Testing (started|failed)' build/simulator.log || true
if [ "$status" -ne 0 ]; then tail -80 build/simulator.log; fi
exit "$status"
