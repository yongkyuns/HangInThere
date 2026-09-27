"""Test shell routing/exit status with fake tools, NOT Apple-platform behavior."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]


class IOSRunnerTests(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name)
        (self.root / "scripts").mkdir()
        shutil.copy2(ROOT / "scripts/test-ios.sh", self.root / "scripts/test-ios.sh")
        (self.root / "scripts/prepare-fixtures.py").write_text(
            "from pathlib import Path\nPath('fixture-verified').touch()\n"
        )
        self.tools = self.root / "tools"
        self.tools.mkdir()
        self.log = self.root / "commands.jsonl"
        self.env = {**os.environ, "PATH": str(self.tools) + os.pathsep + os.environ["PATH"],
                    "COMMAND_LOG": str(self.log), "TEST_EXIT": "0", "TEST_COUNT": "46",
                    "BUILD_EXIT": "0", "NO_SIMULATOR": "0"}
        self.tool("xcodebuild", '''import json, os, sys
from pathlib import Path
args = sys.argv[1:]
with Path(os.environ['COMMAND_LOG']).open('a') as log:
    log.write(json.dumps(args) + '\\n')
if args[0] == 'test':
    print('Test run with ' + os.environ['TEST_COUNT'] + ' tests in 7 suites.')
    sys.exit(int(os.environ['TEST_EXIT']))
if args[0] == 'build':
    sys.exit(int(os.environ['BUILD_EXIT']))
print('Test-only Xcode driver')
''')
        self.tool("xcrun", '''import json, os, sys
args = sys.argv[1:]
if args[:2] == ['simctl', 'list']:
    devices = [] if os.environ['NO_SIMULATOR'] == '1' else [
        {'isAvailable': True, 'name': 'iPhone test fixture', 'state': 'Shutdown', 'udid': 'TEST-UDID'}]
    print(json.dumps({'devices': {'com.apple.CoreSimulator.SimRuntime.iOS-26-2': devices}}))
elif args[0] == '--sdk':
    print('26.2')
else:
    print('Test-only Swift driver')
''')

    def tool(self, name, code):
        path = self.tools / name
        path.write_text("#!/usr/bin/env python3\n" + code)
        path.chmod(0o755)

    def run_script(self, *args, **env):
        result = subprocess.run(
            ["bash", str(self.root / "scripts/test-ios.sh"), *args], cwd=self.root,
            env={**self.env, **env}, text=True, capture_output=True, timeout=20,
        )
        commands = [json.loads(line) for line in self.log.read_text().splitlines()] if self.log.exists() else []
        return result, commands

    def test_default_keeps_all_tests_and_device_build(self):
        result, commands = self.run_script()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c[0] for c in commands], ['-version', 'build', 'test'])
        self.assertIn('CODE_SIGNING_ALLOWED=NO', commands[1])
        self.assertFalse(any('only-testing:' in a or 'skip-testing:' in a for a in commands[-1]))
        self.assertIn('build/Simulator-all.xcresult', commands[-1])
        self.assertTrue((self.root / 'fixture-verified').exists())

    def test_mechanics_needs_no_model_media_and_retains_device_build(self):
        result, commands = self.run_script('mechanics')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c[0] for c in commands], ['-version', 'build', 'test'])
        self.assertIn('-skip-testing:HangInThereTests/VisionSmokeTests', commands[-1])
        self.assertIn('build/Simulator-mechanics.xcresult', commands[-1])
        self.assertFalse((self.root / 'fixture-verified').exists())

    def test_vision_is_explicit_and_retains_separate_evidence(self):
        result, commands = self.run_script('vision')
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual([c[0] for c in commands], ['-version', 'test'])
        self.assertIn('-only-testing:HangInThereTests/VisionSmokeTests', commands[-1])
        self.assertIn('build/Simulator-vision.xcresult', commands[-1])
        self.assertTrue((self.root / 'fixture-verified').exists())

    def test_vision_failure_is_not_swallowed_by_tee(self):
        result, _ = self.run_script('vision', TEST_EXIT='65')
        self.assertEqual(result.returncode, 65, result.stderr)

    def test_device_build_failure_stops_mechanical_job(self):
        result, commands = self.run_script('mechanics', BUILD_EXIT='65')
        self.assertEqual(result.returncode, 65, result.stderr)
        self.assertFalse(any(c[0] == 'test' for c in commands))

    def test_zero_selected_tests_is_a_failure(self):
        result, _ = self.run_script('vision', TEST_COUNT='0')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('refusing an empty green check', result.stderr)

    def test_invalid_selection_is_rejected_before_tool_execution(self):
        result, commands = self.run_script('other')
        self.assertEqual(result.returncode, 2)
        self.assertEqual(commands, [])

    def test_missing_matching_simulator_is_not_a_skipped_pass(self):
        result, commands = self.run_script('vision', NO_SIMULATOR='1')
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('No available iPhone simulator', result.stderr)
        self.assertFalse(any(c[0] == 'test' for c in commands))


if __name__ == '__main__':
    unittest.main()
