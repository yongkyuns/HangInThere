#!/usr/bin/env bash
# No source media, test APKs, pose traces or device identifiers are published.
set -euo pipefail
mkdir -p build/public-workout-android
[[ ! -e build/public-workout-android/report.json ]] || { echo 'Use a fresh public evaluation runner.' >&2; exit 2; }
PUBLIC_REVISION="$(git rev-parse HEAD)"
PUBLIC_RUN_ID="$(python3 -c 'import uuid; print(uuid.uuid4().hex)')"
set +e
./gradlew :android:app:connectedDebugAndroidTest --no-build-cache \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.hanginthere.android.PublicWorkoutDatasetEvaluationTest \
  -Pandroid.testInstrumentationRunnerArguments.publicSourceRevision="$PUBLIC_REVISION" \
  -Pandroid.testInstrumentationRunnerArguments.publicRunId="$PUBLIC_RUN_ID" \
  --console=plain > build/public-workout-android/instrumentation.log 2>&1
public_test_status=$?
adb pull "/sdcard/Android/data/dev.hanginthere.android/files/public-workout/$PUBLIC_RUN_ID/report.json" \
  build/public-workout-android/report.json > build/public-workout-android/collection.log 2>&1
public_collection_status=$?
set -e
if [[ "$public_collection_status" -eq 0 ]]; then
  python3 - "$PUBLIC_RUN_ID" "$PUBLIC_REVISION" <<'PY'
import json, pathlib, sys
r=json.loads(pathlib.Path('build/public-workout-android/report.json').read_text())
assert r['run_id']==sys.argv[1] and r['source_revision']==sys.argv[2], 'Collected a different public evaluation run'
PY
fi
[[ "$public_test_status" -eq 0 ]] || exit "$public_test_status"
[[ "$public_collection_status" -eq 0 ]] || exit "$public_collection_status"
