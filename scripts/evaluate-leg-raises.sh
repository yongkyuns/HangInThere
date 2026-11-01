#!/usr/bin/env bash
# Public host replay, not live camera/device qualification. Media is supplied locally.
set -euo pipefail
[[ $# == 2 || $# == 3 ]] || { echo 'Usage: evaluate-leg-raises.sh MEDIA_DIRECTORY NEW_OUTPUT_DIRECTORY [lyingLegRaise]' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
EXERCISE="${3:-legRaise}"
case "$EXERCISE" in legRaise|lyingLegRaise) ;; *) echo "Unsupported exercise: $EXERCISE" >&2; exit 2;; esac
python3 - "$1" "$2" "$EXERCISE" <<'PY'
from pathlib import Path
import hashlib,json,sys
media=Path(sys.argv[1]).resolve();out=Path(sys.argv[2]).resolve()
fixture='public-lying-legraise-v1.json' if sys.argv[3]=='lyingLegRaise' else 'public-legraise-v1.json'
spec=json.loads((Path('Evaluation/fixtures')/fixture).read_text())
assert not out.exists(), 'Use a new output directory'
cases=[]
for c in spec['cases']:
 p=media/c['file']
 assert p.is_file() and p.stat().st_size==c['bytes'], c['id']
 assert hashlib.sha256(p.read_bytes()).hexdigest()==c['sha256'], c['id']
 cases.append(dict(id=c['id'],path=str(p),sha256=c['sha256'],exercise=sys.argv[3]))
out.mkdir(parents=True)
(out/'native.json').write_text(json.dumps(dict(cases=cases)))
PY
PLATFORM_NAME=macosx ARCHS="$(uname -m)" CONFIGURATION=Debug scripts/build-workout-core.sh
xcrun swiftc -swift-version 6 -O -parse-as-library \
  -F build/WorkoutCore/macosx/Debug -framework WorkoutCore -lc++ \
  ios/HangInThere/Analysis/*.swift ios/HangInThere/Pose/VisionPoseEstimator.swift \
  ios/HangInThere/Capture/VideoReplayReader.swift ios/HangInThere/Capture/BitmapFrameRenderer.swift \
  Evaluation/PushUpCoverageReplay.swift -o "$2/replay"
PUSHUP_COVERAGE_TRACE="$2/traces" "$2/replay" "$2/native.json" "$2/native-results.json"
python3 - "$2" <<'PY'
from pathlib import Path
import hashlib,json,sys
out=Path(sys.argv[1]).resolve()
cases=[dict(id=p.stem,trace=str(p),sha256=hashlib.sha256(p.read_bytes()).hexdigest()) for p in sorted((out/'traces').glob('*.jsonl'))]
(out/'traces.json').write_text(json.dumps(dict(cases=cases)))
PY
OUTPUT_ROOT="$(cd "$2" && pwd)"
if [[ "$EXERCISE" == lyingLegRaise ]]; then
  LYING_LEGRAISE_REPLAY_MANIFEST="$OUTPUT_ROOT/traces.json" LYING_LEGRAISE_REPLAY_OUTPUT="$OUTPUT_ROOT/jvm" \
    ./gradlew -Pworkout.appleOnly=true :shared:jvmTest --tests dev.hanginthere.core.LyingLegRaiseReplayTests --console=plain
else
  LEGRAISE_REPLAY_MANIFEST="$OUTPUT_ROOT/traces.json" LEGRAISE_REPLAY_OUTPUT="$OUTPUT_ROOT/jvm" \
    ./gradlew -Pworkout.appleOnly=true :shared:jvmTest --tests dev.hanginthere.core.LegRaiseReplayTests --console=plain
fi
python3 - "$2" <<'PY'
from pathlib import Path
import json,sys
out=Path(sys.argv[1])
native={r['id']:r['events'] for r in json.loads((out/'native-results.json').read_text())}
jvm={r['id']:r['events'] for r in json.loads((out/'jvm/report.json').read_text())}
assert native==jvm, 'Native/JVM event mismatch'
print('Native/JVM event parity verified for',len(native),'public sources.')
PY
