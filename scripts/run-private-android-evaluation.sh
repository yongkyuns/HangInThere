#!/usr/bin/env bash
# Authorized private media: raw diagnostics remain plaintext only on the ephemeral runner.
set -euo pipefail
umask 077
: "${PRIVATE_WORK:?Private evidence directory must be explicit}"
mkdir -p "$PRIVATE_WORK/evidence/private-evaluation"
set +e
./gradlew :android:app:connectedDebugAndroidTest --no-build-cache \
  -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.hanginthere.android.ImportedRecordingEvaluationTest \
  --console=plain > "$PRIVATE_WORK/evidence/instrumentation.log" 2>&1
test_status=$?
adb pull /sdcard/Android/data/dev.hanginthere.android/files/private-evaluation/. \
  "$PRIVATE_WORK/evidence/private-evaluation/" > "$PRIVATE_WORK/evidence/collection.log" 2>&1
collection_status=$?
adb logcat -d -v threadtime > "$PRIVATE_WORK/evidence/logcat.txt" 2>&1
set -e
printf '%s\n' "$test_status" > "$PRIVATE_WORK/evidence/instrumentation-exit-status.txt"
printf '%s\n' "$collection_status" > "$PRIVATE_WORK/evidence/collection-exit-status.txt"
if [[ "$test_status" -ne 0 ]]; then
  echo 'Authorized recording evaluation failed; detailed evidence will be encrypted.'
  exit "$test_status"
fi
if [[ "$collection_status" -ne 0 ]]; then
  echo 'Authorized recording evidence collection failed.'
  exit "$collection_status"
fi
