#!/usr/bin/env python3
"""Compare rendering on a committed source copy; native failures stay fatal."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import uuid

ROOT = Path(__file__).resolve().parents[1]
READER = 'ios/HangInThere/Capture/VideoReplayReader.swift'
RUNNERS = ('scripts/test-ios.sh', 'scripts/profile-replay.py')
BUILD_INPUTS = (
    'scripts/build-workout-core.sh', 'scripts/prepare-grip-sdk.sh',
    'gradlew', 'gradle.properties', 'build.gradle.kts', 'settings.gradle.kts',
    'gradle/wrapper/gradle-wrapper.jar', 'gradle/wrapper/gradle-wrapper.properties',
    'shared/build.gradle.kts',
    'models/grip/hand_landmarker.task', 'models/grip/pose_landmarker_lite.task',
)
SOURCE_TREES = ('ios', 'shared/src/commonMain')
SIMULATOR_INVENTORY_TIMEOUT = 180
CONTEXT = b'CIContext(options: [.cacheIntermediates: false])'
SOFTWARE = b'CIContext(options: [.cacheIntermediates: false, .useSoftwareRenderer: true])'
spec = importlib.util.spec_from_file_location('native_tools', ROOT / 'scripts/diagnose-apple-decoder.py')
native = importlib.util.module_from_spec(spec)
spec.loader.exec_module(native)


def snapshot(root, destination, renderer):
    """Copy committed native sources and their explicit production build inputs."""
    if renderer not in ('automatic', 'software'):
        raise ValueError('Unknown renderer')
    commit = native.run(['git', '-C', str(root), 'rev-parse', 'HEAD']).stdout.strip()
    # Git, not filesystem recursion, supplies the allow-list. Untracked files,
    # ignored build outputs and Data/local never enter the variant checkout.
    tree = subprocess.run(['git', '-C', str(root), 'ls-tree', '-r', '-z', commit,
                           '--', *SOURCE_TREES, *RUNNERS, *BUILD_INPUTS],
                          capture_output=True, check=True, timeout=30)
    original = {}
    for record in tree.stdout.split(b'\0'):
        if not record:
            continue
        attributes, name = record.split(b'\t', 1)
        mode, kind, _oid = attributes.split()
        path = name.decode('utf-8')
        if (mode not in (b'100644', b'100755') or kind != b'blob'
                or not (any(path.startswith(tree + '/') for tree in SOURCE_TREES)
                        or path in RUNNERS or path in BUILD_INPUTS)
                or '..' in Path(path).parts):
            raise ValueError(f'Unsupported snapshot entry: {path}')
        data = subprocess.run(['git', '-C', str(root), 'show', f'{commit}:{path}'],
                              capture_output=True, check=True, timeout=30).stdout
        output = destination / path
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_bytes(data)
        output.chmod(0o755 if mode == b'100755' else 0o644)
        original[path] = hashlib.sha256(data).hexdigest()
    missing = sorted(set((READER, *RUNNERS, *BUILD_INPUTS)) - original.keys())
    if missing:
        raise ValueError(f'Committed snapshot lacks required build inputs: {missing}')
    if not any(path.startswith('shared/src/commonMain/') and path.endswith('.kt') for path in original):
        raise ValueError('Committed snapshot lacks shared production Kotlin sources')
    reader = destination / READER
    baseline = reader.read_bytes()
    if baseline.count(CONTEXT) != 1:
        raise ValueError('Context implementation changed; refusing an ambiguous experiment')
    if renderer == 'software':
        reader.write_bytes(baseline.replace(CONTEXT, SOFTWARE))
    expected = dict(original)
    expected[READER] = hashlib.sha256(reader.read_bytes()).hexdigest()
    return {'source_commit': commit, 'renderer_requested': renderer,
            'modified_files': [READER] if renderer == 'software' else [],
            'baseline_sha256': original, 'variant_sha256': expected}


def verify_snapshot(directory, expected):
    changed = [path for path, digest in expected.items()
               if not (directory / path).is_file()
               or hashlib.sha256((directory / path).read_bytes()).hexdigest() != digest]
    if changed:
        raise ValueError(f'Unexpected source changes: {changed}')


def preserve_results(checkout, directory):
    # Mechanics contains synthetic fixtures only. Do not widen this to the
    # checkout, signing files, app containers or human/private media.
    for name in ('Simulator-mechanics.xcresult', 'simulator-mechanics-tests.txt',
                 'device-build.txt', 'toolchain.txt', 'simulator-mechanics-build.txt'):
        source = checkout / 'build' / name
        if source.is_dir():
            shutil.copytree(source, directory / name, symlinks=True)
        elif source.is_file():
            shutil.copy2(source, directory / name)


def recover_created_simulators(name, runtime, device_type):
    inventory = json.loads(native.run(['xcrun', 'simctl', 'list', 'devices', '--json'], timeout=20).stdout)
    devices = inventory.get('devices', {}).get(runtime, [])
    identifiers = []
    for device in devices:
        if device.get('name') == name and device.get('deviceTypeIdentifier') == device_type:
            identifier = device['udid']
            uuid.UUID(identifier)
            identifiers.append(identifier)
    return identifiers


def simulator_state(identifier):
    inventory = json.loads(native.run(['xcrun', 'simctl', 'list', 'devices', '--json'], timeout=20).stdout)
    matches = [device for devices in inventory['devices'].values() for device in devices
               if device['udid'] == identifier]
    if len(matches) > 1:
        raise ValueError('Ambiguous owned simulator state')
    return matches[0]['state'] if matches else None


def cleanup_owned_simulator(identifier):
    records, succeeded = [], True
    for action in ('shutdown', 'delete'):
        record = {'action': action}
        try:
            result = native.run(['xcrun', 'simctl', action, identifier], check=False)
            record['exit_code'] = result.returncode
            if result.returncode:
                # Xcode can shut down or remove this device before cleanup.
                # Confirm the target's state instead of interpreting stderr;
                # an unknown state or failed deletion must still fail the job.
                state = simulator_state(identifier)
                record['state_after'] = state
                released = state is None or (action == 'shutdown' and state == 'Shutdown')
                record['already_released'] = released
                if not released:
                    print(f'Owned simulator {action} failed: {result.stderr}', file=sys.stderr)
                    succeeded = False
        except (RuntimeError, ValueError, KeyError, OSError) as error:
            record['error'] = str(error)
            print(str(error), file=sys.stderr)
            succeeded = False
        records.append(record)
    return succeeded, {'owned_simulator': identifier, 'actions': records}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--renderer', choices=('automatic', 'software'), required=True)
    parser.add_argument('--prepare-only', action='store_true', help='Verify the source copy without running native tests')
    args = parser.parse_args()
    invocation = str(uuid.uuid4())
    directory = ROOT / 'build/apple-replay-render' / args.renderer / invocation
    directory.mkdir(parents=True)
    simulator = None
    native_exit_code = None
    creation_attempted = False
    simulator_name = f'iPhone HangInThere {args.renderer} diagnosis {invocation}'
    exit_code = 1
    signal.signal(signal.SIGTERM, native.cancel)
    signal.signal(signal.SIGINT, native.cancel)
    with tempfile.TemporaryDirectory(prefix='hanginthere-render-') as work:
        checkout = Path(work)
        try:
            provenance = snapshot(ROOT, checkout, args.renderer)
            provenance['scope'] = ('Source-copy Core Image option experiment; eligible frames use the unchanged '
                                   'bitmap renderer in both variants. No renderer execution or latency repair qualification')
            provenance['simulator_inventory_timeout_seconds'] = SIMULATOR_INVENTORY_TIMEOUT
            (directory / 'source.json').write_text(json.dumps(provenance, indent=2) + '\n')
            verify_snapshot(checkout, provenance['variant_sha256'])
            if args.prepare_only:
                exit_code = 0
            else:
                sdk = native.run(['xcrun', '--sdk', 'iphonesimulator', '--show-sdk-version']).stdout.strip()
                # Cold CoreSimulator startup can exceed the generic 60s command
                # limit before any device exists. Bound this read-only setup
                # separately; do not retry tests or choose another destination.
                devices = json.loads(native.run(['xcrun', 'simctl', 'list', 'devices', 'available', '--json'],
                                                timeout=SIMULATOR_INVENTORY_TIMEOUT).stdout)
                runtime, device_type = native.select_simulator(devices, sdk)
                provenance.update(sdk_version=sdk, runtime=runtime, owned_simulator_name=simulator_name,
                                  xcode=native.run(['xcodebuild', '-version']).stdout.strip())
                (directory / 'source.json').write_text(json.dumps(provenance, indent=2) + '\n')
                creation_attempted = True
                created = native.run(['xcrun', 'simctl', 'create', simulator_name, device_type, runtime]).stdout.strip()
                uuid.UUID(created)  # Empty/malformed output must never fall back to another destination.
                simulator = created
                provenance['owned_simulator'] = simulator
                (directory / 'source.json').write_text(json.dumps(provenance, indent=2) + '\n')
                # The runner validates the explicitly selected destination and
                # lets Xcode manage cold launch. Sampling stays disabled to avoid
                # adding that observer's startup/collection overhead to a trial.
                command = ['/usr/bin/env', f'HANGINTHERE_TEST_SIMULATOR_UDID={simulator}',
                           'HANGINTHERE_REPLAY_THREAD_PROFILE=0',
                           'bash', str(checkout / 'scripts/test-ios.sh'), 'mechanics']
                result = native.run(command, timeout=25 * 60, check=False)
                (directory / 'runner-stdout.txt').write_text(result.stdout)
                (directory / 'runner-stderr.txt').write_text(result.stderr)
                native_exit_code = result.returncode
                verify_snapshot(checkout, provenance['variant_sha256'])
                exit_code = result.returncode
        except (RuntimeError, ValueError, OSError, subprocess.SubprocessError) as error:
            (directory / 'driver-error.txt').write_text(str(error) + '\n')
            print(str(error), file=sys.stderr)
            if isinstance(error, native.Cancelled):
                exit_code = error.exit_code
        finally:
            signal.signal(signal.SIGTERM, signal.SIG_IGN)
            signal.signal(signal.SIGINT, signal.SIG_IGN)
            try:
                preserve_results(checkout, directory)
            except OSError as error:
                print(f'Result preservation failed: {error}', file=sys.stderr)
                exit_code = 1
            targets = [simulator] if simulator else []
            if creation_attempted and not targets:
                # CoreSimulator can allocate a device before the CLI returns
                # its UUID. Recover only our exact unique name/runtime/type;
                # never use a generic test-device name or a global shutdown.
                try:
                    targets = recover_created_simulators(simulator_name, runtime, device_type)
                    (directory / 'ownership-recovery.json').write_text(json.dumps({'owned_simulators': targets}) + '\n')
                except (RuntimeError, ValueError, OSError) as error:
                    print(f'Owned simulator recovery failed: {error}', file=sys.stderr)
                    exit_code = 1
            cleanup = []
            for owned in targets:
                succeeded, record = cleanup_owned_simulator(owned)
                cleanup.append(record)
                if not succeeded:
                    exit_code = 1
            (directory / 'cleanup.json').write_text(json.dumps(cleanup, indent=2) + '\n')
    (directory / 'result.json').write_text(json.dumps({'driver_exit_code': exit_code,
        'native_tests_exit_code': native_exit_code,
        'prepare_only': args.prepare_only, 'renderer_requested': args.renderer}) + '\n')
    print(f'Render comparison {args.renderer}: exit={exit_code}; evidence={directory}')
    return exit_code


if __name__ == '__main__':
    sys.exit(main())
