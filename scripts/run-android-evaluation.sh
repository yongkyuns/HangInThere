#!/usr/bin/env bash
set -euo pipefail
# Public generated diagnostics only. Never mix reports/traces from a previous
# opt-in experiment into a normal or failed run. Private outputs and workout
# history are different directories and are not touched.
python3 - <<'PY'
from pathlib import Path
import shutil
output=Path('build/android-evaluation')
if output.exists(): shutil.rmtree(output)
output.mkdir(parents=True)
PY
adb shell rm -rf /sdcard/Android/data/dev.hanginthere.android/files/evaluation
cp android/app/src/androidTest/assets/generated/ATTRIBUTION.txt build/android-evaluation/ATTRIBUTION.txt
if [[ "${SELECTED_POSE_DIAGNOSTIC:-false}" == "true" ]]; then
  cp android/app/src/androidTest/assets/generated/selected-poses/ATTRIBUTION.txt build/android-evaluation/selected-pose-ATTRIBUTION.txt
fi
# Retain diagnostics even when an accuracy assertion fails. No media/test APK uploads.
instrumentation_arguments=("--console=plain")
if [[ "${POSE_MODE_DIAGNOSTIC:-false}" == "true" ]]; then
  instrumentation_arguments+=("-Pandroid.testInstrumentationRunnerArguments.poseModeDiagnostic=true")
fi
if [[ "${POSE_CROP_DIAGNOSTIC:-false}" == "true" ]]; then
  instrumentation_arguments+=("-Pandroid.testInstrumentationRunnerArguments.poseCropDiagnostic=true")
fi
if [[ "${SELECTED_POSE_DIAGNOSTIC:-false}" == "true" ]]; then
  instrumentation_arguments+=("-Pandroid.testInstrumentationRunnerArguments.selectedPoseDiagnostic=true")
fi
set +e
./gradlew :android:app:connectedDebugAndroidTest -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true "${instrumentation_arguments[@]}"
test_status=$?
set -e
adb pull /sdcard/Android/data/dev.hanginthere.android/files/evaluation/. build/android-evaluation/ || true
adb logcat -d -v threadtime > build/android-evaluation/logcat.txt
# Diagnostic collection must not overwrite the instrumentation failure status.
adb shell pm grant dev.hanginthere.android android.permission.CAMERA || true
adb shell am force-stop dev.hanginthere.android || true
adb shell am start -n dev.hanginthere.android/.MainActivity || true
adb exec-out screencap -p > build/android-evaluation/live-screen.png || true
python3 - <<'PY'
import json,subprocess
from pathlib import Path
def cmd(*args):return subprocess.check_output(args,text=True).strip()
p=Path('build/android-evaluation/provenance.json')
p.write_text(json.dumps(dict(commit=cmd('git','rev-parse','HEAD'),
    java=cmd('java','--version'),
    emulatorFingerprint=cmd('adb','shell','getprop','ro.build.fingerprint'),
    emulatorHardware=cmd('adb','shell','getprop','ro.hardware'),
    host=cmd('uname','-a')),indent=2)+'\n')
PY
exit "$test_status"
