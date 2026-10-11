#!/usr/bin/env python3
"""Run the strict synthetic native probe; a corrupt original remains a failure."""
import argparse
import json
import os
from pathlib import Path
import platform
import signal
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ('quadrants-h264-vfr-portrait.mp4', 'quadrants-h264-4x4-vfr-portrait.mp4')


class Cancelled(RuntimeError):
    def __init__(self, signum):
        super().__init__(f'Decoder diagnosis cancelled by signal {signum}')
        self.exit_code = 128 + signum


def cancel(signum, _frame):
    raise Cancelled(signum)


def run(command, *, timeout=60, check=True):
    process = subprocess.Popen(command, text=True, stdout=subprocess.PIPE,
                               stderr=subprocess.PIPE, start_new_session=True)
    try:
        stdout, stderr = process.communicate(timeout=timeout)
        result = subprocess.CompletedProcess(command, process.returncode, stdout, stderr)
    except BaseException as error:
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            process.communicate(timeout=5)
        except subprocess.TimeoutExpired:
            raise RuntimeError('Child did not terminate after bounded cancellation') from error
        if isinstance(error, subprocess.TimeoutExpired):
            raise RuntimeError(f'Timed out after {timeout}s: {" ".join(command[:3])}') from None
        raise
    finally:
        process.stdout.close()
        process.stderr.close()
    if check and result.returncode:
        raise RuntimeError(f'Command failed ({result.returncode}): {command[0]}\n{result.stderr}')
    return result


def select_simulator(devices, sdk_version, runtime=None):
    wanted = runtime or 'com.apple.CoreSimulator.SimRuntime.iOS-' + sdk_version.replace('.', '-')
    for identifier, items in devices['devices'].items():
        if identifier != wanted and not identifier.startswith(wanted + '-'):
            continue
        for item in items:
            if item.get('isAvailable') and item['name'].startswith('iPhone'):
                return identifier, item['deviceTypeIdentifier']
    raise ValueError(f'No available iPhone simulator for {wanted}')


def validate_report(report, exit_code):
    cases = report.get('cases', [])
    expected = {(name, hardware) for name in FIXTURES for hardware in (True, False)}
    actual = [(case.get('fixture'), case.get('hardware_allowed')) for case in cases]
    if report.get('schema_version') != 1 or len(actual) != 4 or set(actual) != expected:
        raise ValueError('Missing, duplicated or unexpected native decoder cases')
    if any(not isinstance(case.get('failures'), list) for case in cases):
        raise ValueError('Missing native failure records')
    passed = all(not case['failures'] for case in cases)
    for case in cases:
        if not case['failures'] and (
            len(case.get('frames', [])) != 4 or case.get('decode_statuses') != [0] * 4
            or case.get('reader_completed') is not True
            or case.get('finish_status') != 0 or case.get('wait_status') != 0
        ):
            raise ValueError('Passing native case lacks complete frame/decode/drain evidence')
    if report.get('passed') is not passed or exit_code != (0 if passed else 1):
        raise ValueError('Native result and process exit status disagree')
    return passed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--simulator', action='store_true', help='Create and clean up an isolated SDK-matched simulator')
    parser.add_argument('--runtime', help='Explicit installed simulator runtime identifier for local SDK/runtime comparisons')
    args = parser.parse_args()
    if args.runtime and not args.simulator:
        parser.error('--runtime requires --simulator')
    if platform.system() != 'Darwin':
        parser.error('Apple frameworks require macOS')
    signal.signal(signal.SIGTERM, cancel)
    signal.signal(signal.SIGINT, cancel)
    target = 'simulator' if args.simulator else 'macOS'
    directory = ROOT / 'build/apple-decoder' / target
    directory.mkdir(parents=True, exist_ok=True)
    # Remove only our report, so a failed new invocation cannot leave old success.
    (directory / 'report.json').unlink(missing_ok=True)
    (directory / 'driver-error.txt').unlink(missing_ok=True)
    simulator = None
    exit_code = 1
    try:
        sdk = 'iphonesimulator' if args.simulator else 'macosx'
        sdk_path = run(['xcrun', '--sdk', sdk, '--show-sdk-path']).stdout.strip()
        sdk_version = run(['xcrun', '--sdk', sdk, '--show-sdk-version']).stdout.strip()
        provenance = {'source_commit': run(['git', '-C', str(ROOT), 'rev-parse', 'HEAD']).stdout.strip(),
                      'tracked_changes': bool(run(['git', '-C', str(ROOT), 'diff', 'HEAD', '--name-only']).stdout.strip()),
                      'platform': target, 'architecture': platform.machine(), 'sdk_version': sdk_version,
                      'xcode': run(['xcodebuild', '-version']).stdout.strip(),
                      'swift': run(['xcrun', 'swiftc', '--version']).stdout.strip()}
        if args.simulator:
            devices = json.loads(run(['xcrun', 'simctl', 'list', 'devices', 'available', '--json']).stdout)
            runtime, device_type = select_simulator(devices, sdk_version, args.runtime)
            provenance['runtime'] = runtime
            simulator = run(['xcrun', 'simctl', 'create', 'HangInThere Decoder Diagnosis', device_type, runtime]).stdout.strip()
        (directory / 'toolchain.json').write_text(json.dumps(provenance, indent=2) + '\n')
        triple = platform.machine() + ('-apple-ios17.0-simulator' if args.simulator else '-apple-macosx14.0')
        executable = directory / 'probe'
        run(['xcrun', '--sdk', sdk, 'swiftc', '-swift-version', '6', '-warnings-as-errors',
             '-parse-as-library', '-sdk', sdk_path, '-target', triple,
             str(ROOT / 'scripts/diagnose-apple-decoder.swift'), '-o', str(executable)])
        command = [str(executable), str(ROOT / 'ios/HangInThereTests/Fixtures/decoder')]
        if simulator:
            run(['xcrun', 'simctl', 'boot', simulator])
            run(['xcrun', 'simctl', 'bootstatus', simulator, '-b'], timeout=120)
            command = ['xcrun', 'simctl', 'spawn', simulator, *command]
        result = run(command, check=False)
        (directory / 'probe-stderr.txt').write_text(result.stderr)
        report = json.loads(result.stdout)
        (directory / 'report.json').write_text(json.dumps(report, indent=2) + '\n')
        passed = validate_report(report, result.returncode)
        for case in report['cases']:
            print(f"{case['fixture']} hardware_allowed={case['hardware_allowed']}: "
                  f"{len(case.get('frames', []))} frames, {len(case['failures'])} failures")
        print(f'Native decoder qualification {"passed" if passed else "FAILED"}: {directory / "report.json"}')
        exit_code = 0 if passed else 1
    except (RuntimeError, ValueError, OSError) as error:
        (directory / 'driver-error.txt').write_text(str(error) + '\n')
        print(str(error), file=sys.stderr)
        if isinstance(error, Cancelled):
            exit_code = error.exit_code
    finally:
        if simulator:
            # A second cancellation must not interrupt this bounded cleanup.
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            # Only this invocation's newly created device is touched. Shutdown
            # also terminates a probe whose native API did not return in time.
            for action in ('shutdown', 'delete'):
                try:
                    result = run(['xcrun', 'simctl', action, simulator], check=False)
                    if result.returncode:
                        print(f'Simulator {action} failed: {result.stderr}', file=sys.stderr)
                        exit_code = 1
                except (RuntimeError, OSError) as error:
                    print(str(error), file=sys.stderr)
                    exit_code = 1
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
