import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

RUNNER=Path(__file__).parents[1]/'run-android-evaluation.sh'

class EvaluationCollectionTests(unittest.TestCase):
    def run_collection(self, failed=False, crop=False):
        with tempfile.TemporaryDirectory() as folder:
            root=Path(folder); commands=root/'bin';commands.mkdir()
            device=root/'device-evaluation';device.mkdir()
            private=root/'private-evaluation';private.mkdir();(private/'evidence').write_text('preserve')
            output=root/'build/android-evaluation';output.mkdir(parents=True)
            for directory in [device,output]:
                (directory/'pose-crop-report.json').write_text('stale')
                (directory/'pullup_smoke-pose-crops.jsonl').write_text('stale')
            attribution=root/'android/app/src/androidTest/assets/generated/ATTRIBUTION.txt'
            attribution.parent.mkdir(parents=True);attribution.write_text('public attribution')
            adb='''#!/usr/bin/env python3
import os,sys,shutil
from pathlib import Path
a=sys.argv[1:]; device=Path(os.environ['MOCK_DEVICE_EVALUATION'])
if a[:3]==['shell','rm','-rf']:
 assert a[3]=='/sdcard/Android/data/dev.hanginthere.android/files/evaluation'
 shutil.rmtree(device);device.mkdir()
elif a[0]=='pull':
 for f in device.iterdir(): shutil.copy(f,Path(a[-1])/f.name)
elif a[0]=='logcat': print('current log')
elif a[0]=='exec-out': sys.stdout.buffer.write(b'current screenshot')
elif a[:2]==['shell','getprop']: print('emulator')
'''
            gradle='''#!/usr/bin/env python3
import os,sys,json
from pathlib import Path
Path('arguments.json').write_text(json.dumps(sys.argv[1:]))
if os.environ['MOCK_FAILED']=='true': sys.exit(7)
(Path(os.environ['MOCK_DEVICE_EVALUATION'])/'report.json').write_text('current')
'''
            for path,text in [(commands/'adb',adb),(root/'gradlew',gradle),
                (commands/'java','#!/bin/sh\necho java17\n'),(commands/'git','#!/bin/sh\necho current-head\n')]:
                path.write_text(text);path.chmod(0o755)
            env=dict(os.environ,PATH=str(commands)+os.pathsep+os.environ['PATH'],MOCK_DEVICE_EVALUATION=str(device),
                MOCK_FAILED=str(failed).lower(),POSE_CROP_DIAGNOSTIC=str(crop).lower(),POSE_MODE_DIAGNOSTIC='false')
            result=subprocess.run(['bash',str(RUNNER.resolve())],cwd=root,env=env,capture_output=True,text=True)
            self.assertEqual(result.returncode,7 if failed else 0,result.stderr)
            self.assertFalse((device/'pose-crop-report.json').exists())
            self.assertFalse((output/'pose-crop-report.json').exists())
            self.assertFalse((output/'pullup_smoke-pose-crops.jsonl').exists())
            self.assertEqual((private/'evidence').read_text(),'preserve')
            self.assertEqual((output/'ATTRIBUTION.txt').read_text(),'public attribution')
            self.assertEqual((output/'report.json').exists(),not failed)
            arguments=json.loads((root/'arguments.json').read_text())
            self.assertEqual('-Pandroid.testInstrumentationRunnerArguments.poseCropDiagnostic=true' in arguments,crop)

    def test_old_crop_outputs_do_not_enter_a_non_opt_in_run(self): self.run_collection()
    def test_failed_crop_run_cannot_reuse_previous_evidence(self): self.run_collection(failed=True,crop=True)
    def test_successful_crop_run_passes_explicit_instrumentation_argument(self): self.run_collection(crop=True)
