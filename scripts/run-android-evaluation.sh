#!/usr/bin/env bash
set -euo pipefail
mkdir -p build/android-evaluation
# Retain diagnostics even when an accuracy assertion fails. No media/test APK uploads.
set +e
./gradlew :android:app:connectedDebugAndroidTest --console=plain
test_status=$?
set -e
adb pull /sdcard/Android/data/dev.hanginthere.android/files/evaluation/. build/android-evaluation/ || true
adb logcat -d -v threadtime > build/android-evaluation/logcat.txt
adb shell am start -n dev.hanginthere.android/.MainActivity
adb shell pm grant dev.hanginthere.android android.permission.CAMERA
adb shell am force-stop dev.hanginthere.android
adb shell am start -n dev.hanginthere.android/.MainActivity
adb exec-out screencap -p > build/android-evaluation/live-screen.png
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
