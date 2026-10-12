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
  -project ios/HangInThere.xcodeproj -scheme HangInThere -configuration Release \
  -destination 'generic/platform=iOS' -derivedDataPath build/DerivedData \
  CODE_SIGNING_ALLOWED=NO 2>&1 | tee build/device-build.txt
fi

# Mechanics generates raw MOVs and bundles a small pinned synthetic H.264 file;
# it does not download human/model media. Vision still requires the real corpus.
if [[ "$SUITE" != mechanics ]]; then
  python3 scripts/prepare-fixtures.py --verify-only
  python3 scripts/prepare-video-corpus.py --verify-only
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
requested = os.environ.get('HANGINTHERE_TEST_SIMULATOR_UDID')
if requested:
    candidates = [d for d in candidates if d['udid'] == requested]
if not candidates:
    sys.exit('No available iPhone simulator for the selected SDK and requested destination. Install that runtime in Xcode; see build/simulators.json.')
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
simulator_options=(
  -project ios/HangInThere.xcodeproj -scheme HangInThere -configuration Debug
  -destination "platform=iOS Simulator,id=${SIMULATOR_UDID}"
  -derivedDataPath build/DerivedData)
test_command=(xcodebuild test "${simulator_options[@]}" -resultBundlePath "$RESULT_BUNDLE"
  -parallel-testing-enabled NO)
case "$SUITE" in
  mechanics) test_command+=(
    -skip-testing:HangInThereTests/VisionSmokeTests
    -skip-testing:HangInThereTests/RealVideoCorpusTests
  ) ;;
  vision) test_command+=(
    -only-testing:HangInThereTests/VisionSmokeTests
    -only-testing:HangInThereTests/RealVideoCorpusTests
  ) ;;
esac
# Opt-in diagnostic sampling of the fresh synthetic-only mechanics test host.
# sample records stacks, not image/pose payloads. Wait for the actual replay
# test-entry PID, since Xcode first launches a separate discovery host. Stop
# the watcher and sampler on every exit; preserve the original test exit status.
REPLAY_SAMPLER_PID=''
stop_replay_sampler() {
  if [[ -n "$REPLAY_SAMPLER_PID" ]]; then
    # The watcher forwards SIGINT to sample to finalize a partial call graph.
    kill -TERM "$REPLAY_SAMPLER_PID" 2>/dev/null || true
    wait "$REPLAY_SAMPLER_PID" 2>/dev/null || true
  fi
}
trap stop_replay_sampler EXIT
if [[ "$SUITE" == mechanics && "${HANGINTHERE_REPLAY_THREAD_PROFILE:-0}" == 1 ]]; then
  # Prevent a previous run's PID or profile from being mistaken for this run.
  : > "$TEST_LOG"
  rm -f build/replay-native-threads.txt build/replay-native-sampler.txt
  # Compilation can outlast the watcher's bounded PID wait. Build first, then
  # watch the same selection without spending its PID timeout on compilation
  # or cold simulator launch. The EXIT trap bounds the pre-test watcher lifetime.
  xcodebuild build-for-testing "${simulator_options[@]}" 2>&1 \
    | tee build/simulator-mechanics-build.txt
  test_command[1]=test-without-building
  python3 scripts/profile-replay.py --wait-for-test-start \
    --replay-test "${HANGINTHERE_REPLAY_PROFILE_TEST:-automatic}" --log "$ROOT/$TEST_LOG" \
    --output "$ROOT/build/replay-native-threads.txt" \
    > "$ROOT/build/replay-native-sampler.txt" 2>&1 &
  REPLAY_SAMPLER_PID=$!
fi
"${test_command[@]}" 2>&1 | tee "$TEST_LOG"
# xcodebuild can succeed with an unmatched filter. Zero selected tests is not
# qualification. Keep pipefail above so any actual test failure stays a failure.
if ! grep -Eq 'Test run with [1-9][0-9]* tests' "$TEST_LOG"; then
  echo "No nonzero Swift Testing run recorded for $SUITE; refusing an empty green check." >&2
  exit 1
fi
