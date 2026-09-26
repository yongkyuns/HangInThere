#!/bin/bash
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p build
xcodebuild -version
xcodebuild -showsdks
uname -m
export SIMULATOR_RUNTIME="${SIMULATOR_RUNTIME:-iOS-26-2}"
xcrun simctl list devices available --json > build/simulators.json
SIMULATOR_UDID=$(python3 - <<'PY'
import json
import os
with open('build/simulators.json') as f:
    devices = json.load(f)['devices']
runtime_suffix = os.environ['SIMULATOR_RUNTIME']
choices = [d for runtime, ds in devices.items() if runtime.endswith(runtime_suffix)
           for d in ds if d['isAvailable'] and d['name'].startswith('iPhone')]
if not choices:
    raise SystemExit(f'Pinned {runtime_suffix} simulator missing. Review the runner image; do not silently change test OS.')
print(sorted(choices, key=lambda d: d['name'])[0]['udid'])
PY
)
echo "Simulator destination: $SIMULATOR_UDID ($SIMULATOR_RUNTIME)"
status=0
xcodebuild test -project HangInThere.xcodeproj -scheme HangInThere \
  -destination "platform=iOS Simulator,id=$SIMULATOR_UDID" -destination-timeout 180 \
  -parallel-testing-enabled NO -test-timeouts-enabled YES \
  -default-test-execution-time-allowance 120 -maximum-test-execution-time-allowance 120 \
  -resultBundlePath build/SimulatorTests.xcresult ONLY_ACTIVE_ARCH=YES \
  > build/simulator.log 2>&1 || status=$?
grep -E 'error:|warning:|Test (Case|Suite)|Executed|\*\* TEST|Testing (started|failed)|Missing weights|espresso' build/simulator.log || true
if [ "$status" -ne 0 ]; then tail -80 build/simulator.log; fi
exit "$status"
