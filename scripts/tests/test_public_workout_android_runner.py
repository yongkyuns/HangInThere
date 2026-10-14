"""Exercise run-specific collection through the real shell entry point."""
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class PublicAndroidCollectionTests(unittest.TestCase):
    def run_fake(self, mode="correct", test_status=0):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory); binary = root / "bin"; binary.mkdir()
            (binary / "python3").symlink_to(sys.executable)
            git = binary / "git"; git.write_text("#!/bin/sh\nprintf '%s\\n' " + "c" * 40 + "\n"); git.chmod(0o755)
            gradle = root / "gradlew"
            gradle.write_text("""#!/usr/bin/env python3
import json, os, pathlib, sys
params={x.split('=',1)[0].removeprefix('-Pandroid.testInstrumentationRunnerArguments.'):x.split('=',1)[1] for x in sys.argv[1:] if x.startswith('-Pandroid.testInstrumentationRunnerArguments.')}
pathlib.Path('params.json').write_text(json.dumps(params))
raise SystemExit(int(os.environ['FAKE_TEST_STATUS']))
""")
            gradle.chmod(0o755)
            adb = binary / "adb"
            adb.write_text("""#!/usr/bin/env python3
import json, os, pathlib, sys
p=json.loads(pathlib.Path('params.json').read_text())
pathlib.Path('pull.json').write_text(json.dumps(sys.argv[1:]))
assert sys.argv[2].endswith('/'+p['publicRunId']+'/report.json')
if os.environ['FAKE_MODE']=='missing':raise SystemExit(1)
report={'run_id':p['publicRunId'],'source_revision':p['publicSourceRevision']}
if os.environ['FAKE_MODE']=='wrong_run':report['run_id']='0'*32
if os.environ['FAKE_MODE']=='wrong_revision':report['source_revision']='0'*40
pathlib.Path(sys.argv[3]).write_text(json.dumps(report))
""")
            adb.chmod(0o755)
            env = dict(os.environ, PATH=str(binary) + os.pathsep + os.environ["PATH"],
                       FAKE_MODE=mode, FAKE_TEST_STATUS=str(test_status))
            run = subprocess.run(["bash", str(ROOT / "scripts/run-public-workout-android.sh")],
                                 cwd=root, env=env, capture_output=True, text=True)
            params = json.loads((root / "params.json").read_text())
            exists = (root / "build/public-workout-android/report.json").is_file()
            return run.returncode, params, exists

    def test_fresh_nonce_is_passed_to_native_test_and_used_for_collection(self):
        status, params, exists = self.run_fake()
        self.assertEqual(status, 0)
        self.assertTrue(exists)
        self.assertRegex(params["publicRunId"], r"^[a-f0-9]{32}$")
        self.assertEqual(params["publicSourceRevision"], "c" * 40)

    def test_report_from_another_run_or_revision_is_rejected(self):
        for mode in ["wrong_run", "wrong_revision"]:
            with self.subTest(mode=mode): self.assertNotEqual(self.run_fake(mode)[0], 0)

    def test_failed_instrumentation_preserves_its_report_but_does_not_succeed(self):
        status, _, exists = self.run_fake(test_status=7)
        self.assertEqual(status, 7)
        self.assertTrue(exists)

    def test_missing_current_report_never_falls_back_to_old_device_path(self):
        status, _, exists = self.run_fake(mode="missing")
        self.assertNotEqual(status, 0)
        self.assertFalse(exists)


if __name__ == "__main__": unittest.main()
