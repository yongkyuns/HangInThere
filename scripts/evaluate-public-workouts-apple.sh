#!/usr/bin/env bash
# Real production Apple replay + common analytics. Host evidence, not device timing.
set -euo pipefail
[[ $# == 3 || $# == 5 ]] || { echo 'Usage: evaluate-public-workouts-apple.sh MANIFEST ROOT OUTPUT [SHARD_INDEX SHARD_COUNT]' >&2; exit 2; }
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PLATFORM_NAME=macosx ARCHS="$(uname -m)" CONFIGURATION=Debug "$ROOT/scripts/build-workout-core.sh"
FRAMEWORK="$ROOT/build/WorkoutCore/macosx/Debug"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
python3 - "$ROOT" "$FRAMEWORK" "$WORK/source-snapshot.json" <<'PY'
import json, pathlib, subprocess, sys
root = pathlib.Path(sys.argv[1]); sys.path.insert(0, str(root/'scripts'))
import evaluation
paths = [p for folder in ['Analysis', 'Capture', 'Pose'] for p in sorted((root/'ios/HangInThere'/folder).glob('*.swift'))]
paths += [root/p for p in ['ios/HangInThere/App/WorkoutAnalysisAdapter.swift',
    'ios/HangInThere/App/WorkoutProgression.swift', 'ios/HangInThere/App/WorkoutGripReview.swift',
    'ios/HangInThere/App/ReplayController.swift', 'ios/HangInThere/UI/QualificationReportDocument.swift',
    'Evaluation/PublicWorkoutReplay.swift', 'scripts/evaluate-public-workouts-apple.sh']]
paths += [pathlib.Path(sys.argv[2])/'WorkoutCore.framework/WorkoutCore']
snapshot = {'source_revision': subprocess.check_output(['git','-C',str(root),'rev-parse','HEAD'],text=True).strip(),
    'source_sha256': {str(p.relative_to(root)):evaluation.digest(p) for p in paths},
    'toolchain': subprocess.check_output(['xcrun','swift','--version'],text=True).strip()}
pathlib.Path(sys.argv[3]).write_text(json.dumps(snapshot))
PY
xcrun swiftc -O -swift-version 6 -parse-as-library -target "$(uname -m)-apple-macosx15.0" \
  -F "$FRAMEWORK" -framework WorkoutCore -lc++ \
  "$ROOT"/ios/HangInThere/Analysis/*.swift "$ROOT"/ios/HangInThere/Capture/*.swift \
  "$ROOT"/ios/HangInThere/Pose/*.swift \
  "$ROOT/ios/HangInThere/App/WorkoutAnalysisAdapter.swift" \
  "$ROOT/ios/HangInThere/App/WorkoutProgression.swift" \
  "$ROOT/ios/HangInThere/App/WorkoutGripReview.swift" \
  "$ROOT/ios/HangInThere/App/ReplayController.swift" \
  "$ROOT/ios/HangInThere/UI/QualificationReportDocument.swift" \
  "$ROOT/Evaluation/PublicWorkoutReplay.swift" -o "$WORK/public-replay"
"$WORK/public-replay" "$1" "$2" "$3" "$(git -C "$ROOT" rev-parse HEAD)" "${4:-0}" "${5:-1}"
python3 - "$ROOT" "$WORK/source-snapshot.json" "$WORK/public-replay" "$3" <<'PY'
import json, pathlib, sys
root=pathlib.Path(sys.argv[1]);sys.path.insert(0,str(root/'scripts'))
import evaluation
snapshot=json.loads(pathlib.Path(sys.argv[2]).read_text())
assert all(evaluation.digest(root/p)==sha for p,sha in snapshot['source_sha256'].items()), 'Compiled sources changed during evaluation'
report=pathlib.Path(sys.argv[4]);assert json.loads(report.read_text())['source_revision']==snapshot['source_revision'], 'App revision changed during evaluation'
snapshot.update(executable_sha256=evaluation.digest(sys.argv[3]),native_report_sha256=evaluation.digest(report))
with pathlib.Path(str(report)+'.provenance.json').open('x') as stream:json.dump(snapshot,stream,indent=2,sort_keys=True);stream.write('\n')
PY
