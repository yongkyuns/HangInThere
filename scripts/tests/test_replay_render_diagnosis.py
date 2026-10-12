"""Snapshot/routing contracts; native frames and successful tests are not mocked."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
OWNED = '11000000-0000-0000-0000-000000000001'
spec = importlib.util.spec_from_file_location('render_probe', ROOT / 'scripts/diagnose-replay-render.py')
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)


class SnapshotContracts(unittest.TestCase):
    def setUp(self):
        self.work = tempfile.TemporaryDirectory()
        self.addCleanup(self.work.cleanup)
        self.root = Path(self.work.name) / 'repo'
        self.root.mkdir()
        self.copy = Path(self.work.name) / 'copy'
        self.copy.mkdir()
        subprocess.run(['git', 'init', '-q', str(self.root)], check=True, timeout=10)
        for path, data in {
            probe.READER: b'let context = ' + probe.CONTEXT + b'\n',
            probe.RUNNERS[0]: b'#!/bin/bash\nexit 65\n',
            probe.RUNNERS[1]: b'# profile helper\n',
            'ios/HangInThereTests/Fixtures/decoder/example.bin': b'\x00\xff\x02',
            'Data/local/private-example': b'never copy',
        }.items():
            output = self.root / path
            output.parent.mkdir(parents=True, exist_ok=True)
            output.write_bytes(data)
        (self.root / probe.RUNNERS[0]).chmod(0o755)
        self.commit()

    def commit(self):
        subprocess.run(['git', '-C', str(self.root), 'add', '.'], check=True, timeout=10)
        subprocess.run(['git', '-C', str(self.root), '-c', 'user.name=Test', '-c', 'user.email=test@example.invalid',
                        '-c', 'commit.gpgsign=false', 'commit', '-qm', 'fixture'], check=True, timeout=10)

    def test_default_copies_only_committed_allowlisted_bytes(self):
        (self.root / 'ios/ignored-example').write_text('untracked')
        reader = self.root / probe.READER
        reader.write_text('uncommitted must not enter the trial')
        report = probe.snapshot(self.root, self.copy, 'automatic')
        self.assertEqual(report['modified_files'], [])
        self.assertEqual(report['baseline_sha256'], report['variant_sha256'])
        self.assertEqual((self.copy / probe.READER).read_bytes(), b'let context = ' + probe.CONTEXT + b'\n')
        self.assertFalse((self.copy / 'Data').exists())
        self.assertFalse((self.copy / 'ios/ignored-example').exists())
        self.assertEqual((self.copy / 'ios/HangInThereTests/Fixtures/decoder/example.bin').read_bytes(), b'\x00\xff\x02')
        self.assertTrue((self.copy / probe.RUNNERS[0]).stat().st_mode & 0o100)
        probe.verify_snapshot(self.copy, report['variant_sha256'])

    def test_software_changes_exactly_one_context_option_and_leaves_root_untouched(self):
        before = (self.root / probe.READER).read_bytes()
        report = probe.snapshot(self.root, self.copy, 'software')
        self.assertEqual((self.root / probe.READER).read_bytes(), before)
        self.assertEqual(report['modified_files'], [probe.READER])
        changed = [p for p in report['baseline_sha256'] if report['baseline_sha256'][p] != report['variant_sha256'][p]]
        self.assertEqual(changed, [probe.READER])
        self.assertEqual((self.copy / probe.READER).read_bytes(), before.replace(probe.CONTEXT, probe.SOFTWARE))
        probe.verify_snapshot(self.copy, report['variant_sha256'])
        (self.copy / probe.RUNNERS[0]).write_text('unexpected selection change')
        with self.assertRaisesRegex(ValueError, 'Unexpected source changes'):
            probe.verify_snapshot(self.copy, report['variant_sha256'])

    def test_unknown_context_or_symlink_cannot_enter_a_trial(self):
        for data in (b'changed implementation', probe.CONTEXT + probe.CONTEXT):
            (self.root / probe.READER).write_bytes(data)
            self.commit()
            with self.assertRaisesRegex(ValueError, 'Context implementation changed'):
                probe.snapshot(self.root, self.copy, 'software')
        (self.root / 'ios/unsafe-link').symlink_to('../Data/local/private-example')
        self.commit()
        with self.assertRaisesRegex(ValueError, 'Unsupported snapshot entry'):
            probe.snapshot(self.root, self.copy, 'automatic')


class DriverContracts(unittest.TestCase):
    def test_cleanup_confirms_shutdown_or_deletion_without_hiding_other_errors(self):
        for state, expected in (('Shutdown', True), (None, True), ('Booted', False)):
            with self.subTest(state=state):
                def run(command, **kwargs):
                    status = 149 if 'shutdown' in command or state is None else 0
                    return subprocess.CompletedProcess(command, status, '', 'shutdown unavailable')
                with patch.object(probe.native, 'run', side_effect=run) as commands, \
                     patch.object(probe, 'simulator_state', return_value=state):
                    passed, record = probe.cleanup_owned_simulator(OWNED)
                self.assertEqual(passed, expected)
                self.assertEqual([call.args[0][2:] for call in commands.call_args_list],
                                 [['shutdown', OWNED], ['delete', OWNED]])
                self.assertEqual(record['actions'][0]['state_after'], state)
                self.assertEqual(record['actions'][0]['already_released'], expected)
        with patch.object(probe.native, 'run', return_value=subprocess.CompletedProcess([], 149, '', 'error')), \
             patch.object(probe, 'simulator_state', side_effect=RuntimeError('inventory unavailable')):
            passed, record = probe.cleanup_owned_simulator(OWNED)
        self.assertFalse(passed)
        self.assertTrue(all(action['error'] == 'inventory unavailable' for action in record['actions']))

    def test_native_failure_remains_recorded_and_propagated_with_idempotent_cleanup(self):
        for source_changed in (False, True):
            with self.subTest(source_changed=source_changed), tempfile.TemporaryDirectory() as work:
                root = Path(work)
                def run(command, **kwargs):
                    text, status = '', 0
                    if '--show-sdk-version' in command: text = '26.3'
                    elif command[:4] == ['xcrun', 'simctl', 'list', 'devices']: text = '{}'
                    elif command[:3] == ['xcrun', 'simctl', 'create']: text = OWNED
                    elif command[0] == '/usr/bin/env': status = 65
                    elif command[:3] == ['xcrun', 'simctl', 'shutdown']: status = 149
                    return subprocess.CompletedProcess(command, status, text, '')
                validation = [None, ValueError('Unexpected source changes')] if source_changed else [None, None]
                with patch.object(probe, 'ROOT', root), patch.object(probe, 'snapshot', return_value={'variant_sha256': {}}), \
                     patch.object(probe, 'verify_snapshot', side_effect=validation), \
                     patch.object(probe.native, 'run', side_effect=run), \
                     patch.object(probe.native, 'select_simulator', return_value=('runtime26.3', 'type')), \
                     patch.object(probe, 'simulator_state', return_value='Shutdown'), \
                     patch.object(probe.signal, 'signal'), patch.object(sys, 'argv', ['probe', '--renderer', 'automatic']):
                    self.assertEqual(probe.main(), 1 if source_changed else 65)
                report = json.loads(next(root.glob('build/apple-replay-render/automatic/*/result.json')).read_text())
                self.assertEqual(report['native_tests_exit_code'], 65)
                cleanup = json.loads(next(root.glob('build/apple-replay-render/automatic/*/cleanup.json')).read_text())
                self.assertTrue(cleanup[0]['actions'][0]['already_released'])

    def test_native_failure_and_owned_cleanup_survive_result_copy_failure(self):
        with tempfile.TemporaryDirectory() as work:
            root = Path(work)
            calls = []
            def run(command, **kwargs):
                calls.append((command, kwargs))
                text, status = '', 0
                if '--show-sdk-version' in command: text = '26.3'
                elif command[:4] == ['xcrun', 'simctl', 'list', 'devices']: text = '{}'
                elif command[:3] == ['xcrun', 'simctl', 'create']: text = OWNED
                elif command[0] == '/usr/bin/env': text, status = 'original H264 failed', 65
                return subprocess.CompletedProcess(command, status, text, '')
            with patch.object(probe, 'ROOT', root), patch.object(probe, 'snapshot', return_value={'variant_sha256': {}}), \
                 patch.object(probe, 'verify_snapshot'), patch.object(probe.native, 'run', side_effect=run), \
                 patch.object(probe.native, 'select_simulator', return_value=('runtime26.3', 'type')), \
                 patch.object(probe, 'preserve_results', side_effect=OSError('copy failed')), \
                 patch.object(probe.signal, 'signal'), \
                 patch.object(sys, 'argv', ['probe', '--renderer', 'software']):
                self.assertEqual(probe.main(), 1)
            report = json.loads(next(root.glob('build/apple-replay-render/software/*/result.json')).read_text())
            self.assertEqual(report['native_tests_exit_code'], 65)
            command, options = next(c for c in calls if c[0][0] == '/usr/bin/env')
            self.assertIn(f'HANGINTHERE_TEST_SIMULATOR_UDID={OWNED}', command)
            self.assertIn('HANGINTHERE_REPLAY_THREAD_PROFILE=0', command)
            self.assertEqual(command[-1], 'mechanics')
            self.assertEqual(options['timeout'], 25 * 60)
            cleanup = [c for c, _ in calls if c[:3] in (['xcrun', 'simctl', 'shutdown'], ['xcrun', 'simctl', 'delete'])]
            self.assertEqual(cleanup, [['xcrun', 'simctl', 'shutdown', OWNED], ['xcrun', 'simctl', 'delete', OWNED]])

    def test_cancelled_creation_recovers_only_its_exact_unique_device(self):
        with tempfile.TemporaryDirectory() as work:
            calls, created = [], []
            def run(command, **kwargs):
                calls.append(command)
                text = ''
                if '--show-sdk-version' in command: text = '26.3'
                elif command[:3] == ['xcrun', 'simctl', 'create']:
                    created.append(command[3])
                    raise probe.native.Cancelled(15)
                elif command[:4] == ['xcrun', 'simctl', 'list', 'devices']:
                    devices = [
                        {'name': created[0], 'deviceTypeIdentifier': 'type', 'udid': OWNED},
                        {'name': 'iPhone HangInThere software diagnosis', 'deviceTypeIdentifier': 'type', 'udid': 'UNRELATED'},
                        {'name': created[0], 'deviceTypeIdentifier': 'other', 'udid': 'OTHER-TYPE'},
                    ] if created else []
                    text = json.dumps({'devices': {'runtime26.3': devices,
                                                  'other-runtime': [{'name': created[0] if created else '', 'deviceTypeIdentifier': 'type', 'udid': 'OTHER-RUNTIME'}]}})
                return subprocess.CompletedProcess(command, 0, text, '')
            with patch.object(probe, 'ROOT', Path(work)), patch.object(probe, 'snapshot', return_value={'variant_sha256': {}}), \
                 patch.object(probe, 'verify_snapshot'), patch.object(probe.native, 'run', side_effect=run), \
                 patch.object(probe.native, 'select_simulator', return_value=('runtime26.3', 'type')), \
                 patch.object(probe.signal, 'signal'), patch.object(sys, 'argv', ['probe', '--renderer', 'software']):
                self.assertEqual(probe.main(), 143)
            self.assertTrue(created[0].startswith('iPhone HangInThere software diagnosis '))
            cleanup = [c for c in calls if c[:3] in (['xcrun', 'simctl', 'shutdown'], ['xcrun', 'simctl', 'delete'])]
            self.assertEqual(cleanup, [['xcrun', 'simctl', 'shutdown', OWNED], ['xcrun', 'simctl', 'delete', OWNED]])

    def test_prepare_only_never_claims_native_execution(self):
        with tempfile.TemporaryDirectory() as work, patch.object(probe, 'ROOT', Path(work)), \
             patch.object(probe, 'snapshot', return_value={'variant_sha256': {}}), \
             patch.object(probe, 'verify_snapshot'), patch.object(probe, 'preserve_results'), \
             patch.object(probe.native, 'run') as run, patch.object(probe.signal, 'signal'), \
             patch.object(sys, 'argv', ['probe', '--renderer', 'automatic', '--prepare-only']):
            self.assertEqual(probe.main(), 0)
            run.assert_not_called()
            report = json.loads(next(Path(work).glob('build/apple-replay-render/automatic/*/result.json')).read_text())
            self.assertIsNone(report['native_tests_exit_code'])
            self.assertTrue(report['prepare_only'])


if __name__ == '__main__':
    unittest.main()
