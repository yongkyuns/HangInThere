"""Probe routing and failure preservation; no native decoder is mocked as passing."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('apple_decoder_diagnosis', ROOT / 'scripts/diagnose-apple-decoder.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


def complete_report():
    return {'schema_version': 1, 'passed': True, 'cases': [
        {'fixture': name, 'hardware_allowed': hardware, 'failures': [],
         'frames': [{}] * 4, 'decode_statuses': [0] * 4, 'reader_completed': True,
         'finish_status': 0, 'wait_status': 0}
        for name in probe.FIXTURES for hardware in (True, False)]}


class NativeResultContracts(unittest.TestCase):
    def test_complete_success_requires_zero_exit(self):
        self.assertTrue(probe.validate_report(complete_report(), 0))
        with self.assertRaises(ValueError):
            probe.validate_report(complete_report(), 1)

    def test_pixel_failure_remains_failed_with_passing_control(self):
        report = complete_report()
        report['cases'][1]['failures'] = ['Frame 0: Cr 255 != 33']
        report['passed'] = False
        self.assertFalse(probe.validate_report(report, 1))
        with self.assertRaises(ValueError):
            probe.validate_report(report, 0)

    def test_empty_missing_duplicate_and_unexpected_cases_cannot_pass(self):
        original = complete_report()
        alternatives = [[], original['cases'][:-1], [original['cases'][0]] * 4]
        unknown = copy.deepcopy(original['cases'])
        unknown[0]['fixture'] = 'unapproved.mp4'
        alternatives.append(unknown)
        for cases in alternatives:
            with self.subTest(cases=cases), self.assertRaises(ValueError):
                probe.validate_report(dict(original, cases=cases), 0)

    def test_missing_frames_or_native_completion_cannot_pass(self):
        for field, value in [('frames', []), ('decode_statuses', [0] * 3), ('decode_statuses', [0, -12902, 0, 0]),
                             ('reader_completed', False), ('finish_status', -12903), ('wait_status', None)]:
            report = complete_report()
            report['cases'][0][field] = value
            with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                probe.validate_report(report, 0)

    def test_failure_records_and_summary_are_required(self):
        report = complete_report()
        del report['cases'][0]['failures']
        with self.assertRaises(ValueError):
            probe.validate_report(report, 0)
        report = complete_report()
        report['passed'] = False
        with self.assertRaises(ValueError):
            probe.validate_report(report, 0)

    def test_hung_child_process_is_bounded(self):
        with self.assertRaisesRegex(RuntimeError, 'Timed out'):
            probe.run([sys.executable, '-c', 'import time; time.sleep(10)'], timeout=0.05)


class RuntimeSelection(unittest.TestCase):
    devices = {'devices': {
        'com.apple.CoreSimulator.SimRuntime.iOS-18-6': [
            {'isAvailable': True, 'name': 'iPhone 16 Pro', 'deviceTypeIdentifier': 'phone-type'}],
        'com.apple.CoreSimulator.SimRuntime.iOS-18-5': [
            {'isAvailable': False, 'name': 'iPhone unavailable', 'deviceTypeIdentifier': 'unavailable'},
            {'isAvailable': True, 'name': 'iPad', 'deviceTypeIdentifier': 'tablet'}]}}

    def test_sdk_mismatch_requires_explicit_override(self):
        with self.assertRaisesRegex(ValueError, 'No available iPhone'):
            probe.select_simulator(self.devices, '18.5')
        self.assertEqual(probe.select_simulator(self.devices, '18.5', 'com.apple.CoreSimulator.SimRuntime.iOS-18-6'),
                         ('com.apple.CoreSimulator.SimRuntime.iOS-18-6', 'phone-type'))

    def test_matching_sdk_and_patch_runtime_are_selected(self):
        self.assertEqual(probe.select_simulator(self.devices, '18.6')[1], 'phone-type')
        devices = copy.deepcopy(self.devices)
        devices['devices']['com.apple.CoreSimulator.SimRuntime.iOS-18-6-1'] = devices['devices'].pop('com.apple.CoreSimulator.SimRuntime.iOS-18-6')
        self.assertEqual(probe.select_simulator(devices, '18.6')[0], 'com.apple.CoreSimulator.SimRuntime.iOS-18-6-1')


class Cancellation(unittest.TestCase):
    def test_sigterm_and_sigint_kill_and_reap_child(self):
        for signum in (signal.SIGTERM, signal.SIGINT):
            with self.subTest(signum=signum), tempfile.TemporaryDirectory() as work:
                marker = Path(work) / 'child-pid'
                child = f'import os,time; from pathlib import Path; Path({str(marker)!r}).write_text(str(os.getpid())); time.sleep(10)'
                wrapper = (
                    'import importlib.util,signal,sys; '
                    f's=importlib.util.spec_from_file_location("probe", {str(ROOT / "scripts/diagnose-apple-decoder.py")!r}); '
                    'p=importlib.util.module_from_spec(s); s.loader.exec_module(p); '
                    'signal.signal(signal.SIGTERM,p.cancel); signal.signal(signal.SIGINT,p.cancel)\n'
                    f'try: p.run([sys.executable,"-c",{child!r}],timeout=15)\n'
                    'except p.Cancelled as error: sys.exit(error.exit_code)\n')
                parent = subprocess.Popen([sys.executable, '-c', wrapper], stdout=subprocess.PIPE, stderr=subprocess.PIPE)
                try:
                    deadline = time.monotonic() + 5
                    while not marker.exists() and time.monotonic() < deadline:
                        time.sleep(0.01)
                    self.assertTrue(marker.exists(), 'Child did not start')
                    child_pid = int(marker.read_text())
                    parent.send_signal(signum)
                    stdout, stderr = parent.communicate(timeout=5)
                    self.assertEqual(parent.returncode, 128 + signum, stderr.decode())
                    with self.assertRaises(ProcessLookupError):
                        os.kill(child_pid, 0)
                finally:
                    if parent.poll() is None:
                        parent.kill(); parent.communicate(timeout=5)
                    if marker.exists():
                        try: os.kill(int(marker.read_text()), signal.SIGKILL)
                        except ProcessLookupError: pass

    def test_cancellation_cleans_only_new_simulator_and_keeps_failure(self):
        commands = []
        def fake_run(command, **_kwargs):
            commands.append(command)
            text = ''
            if '--show-sdk-path' in command: text = '/test-sdk'
            elif '--show-sdk-version' in command: text = '18.6'
            elif command[:4] == ['xcrun', 'simctl', 'list', 'devices']: text = json.dumps(RuntimeSelection.devices)
            elif command[:3] == ['xcrun', 'simctl', 'create']: text = 'NEW-DEVICE'
            elif command[:3] == ['xcrun', 'simctl', 'bootstatus']: raise probe.Cancelled(signal.SIGTERM)
            return subprocess.CompletedProcess(command, 0, text, '')
        with tempfile.TemporaryDirectory() as work, patch.object(probe, 'ROOT', Path(work)), \
             patch.object(probe.platform, 'system', return_value='Darwin'), patch.object(probe, 'run', side_effect=fake_run), \
             patch.object(probe.signal, 'signal'), patch.object(sys, 'argv', ['probe', '--simulator']):
            self.assertEqual(probe.main(), 143)
            self.assertFalse((Path(work) / 'build/apple-decoder/simulator/report.json').exists())
        cleanup = [command for command in commands if command[:3] in (
            ['xcrun', 'simctl', 'shutdown'], ['xcrun', 'simctl', 'delete'])]
        self.assertEqual(cleanup, [['xcrun', 'simctl', 'shutdown', 'NEW-DEVICE'],
                                   ['xcrun', 'simctl', 'delete', 'NEW-DEVICE']])


if __name__ == '__main__':
    unittest.main()
