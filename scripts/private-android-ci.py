#!/usr/bin/env python3
"""Bound private CI extraction and publish only an explicit count/status whitelist.

Media/pose traces must be encrypted by the caller. This module does not accept
network URLs, store keys, upload inputs or turn an incomplete evaluation green.
"""
import argparse
import json
from pathlib import Path
import re
import tarfile

ASSET = re.compile(r'recording_[a-z][a-z0-9]{0,23}\.mp4\Z')
CASE = re.compile(r'recording_[a-z0-9_]{1,48}\Z')
MAX_BYTES = 1_000_000_000


def unpack(archive, destination):
    destination = Path(destination)
    if destination.exists():
        raise ValueError('Choose a fresh private asset directory')
    with tarfile.open(archive, 'r:') as stream:
        members = stream.getmembers()
        names = [row.name for row in members]
        if (not members or len(names) != len(set(names)) or 'manifest.json' not in names
                or any(not row.isfile() or not (row.name in ('manifest.json', 'provenance.json') or ASSET.fullmatch(row.name)) for row in members)
                or sum(row.size for row in members) > MAX_BYTES):
            raise ValueError('Invalid private asset archive')
        manifest = json.load(stream.extractfile('manifest.json'))
        cases = manifest.get('cases', [])
        if manifest.get('schemaVersion') != 1 or not cases or len(cases) > 16:
            raise ValueError('Invalid private manifest')
        identifiers = [row['id'] for row in cases]
        if (any(not CASE.fullmatch(identifier) for identifier in identifiers)
                or len(identifiers) != len(set(identifiers))
                or set(names) - {'manifest.json', 'provenance.json'} != {row['asset'] for row in cases}):
            raise ValueError('Private manifest and archive do not match')
        destination.mkdir(parents=True)
        for member in members:
            with stream.extractfile(member) as source, (destination/member.name).open('xb') as output:
                while block := source.read(1024*1024):
                    output.write(block)


def summary(report_path, instrumentation_status=None, collection_status=None):
    """Never copy arbitrary strings, metadata, source clocks or nested exceptions."""
    if not Path(report_path).is_file():
        return {'status': 'missing_native_report', 'cases': []}
    report = json.loads(Path(report_path).read_text())
    manifest = report.get('manifest', {}).get('cases', [])
    expected = {row.get('id'): row for row in manifest}
    rows = []
    for case in report.get('cases', []):
        identifier = case.get('id', '')
        if not isinstance(identifier, str) or not CASE.fullmatch(identifier):
            raise ValueError('Unsafe public summary identifier')
        def count(value):
            return value if type(value) is int and value >= 0 else None
        auto = case.get('automatic', {})
        fixed = case.get('manualFixedEdgeCounter', {})
        frozen = expected.get(identifier, {})
        expected_count = count(frozen.get('expectedMovements'))
        expected_frames = count(frozen.get('frames'))
        consistent = (case.get('error') is None and expected_count is not None
                      and expected_frames is not None and expected_frames > 0
                      and count(case.get('expectedMovements')) == expected_count
                      and count(case.get('frames')) == expected_frames)
        auto_pass = (consistent and auto.get('error') is None and auto.get('functionalParity') is True
                     and count(auto.get('observedMovements')) == expected_count
                     and auto.get('replayOutcome') == 'complete' and auto.get('countingSourceEnded') is True
                     and count(auto.get('countingSourceFrames')) == expected_frames)
        fixed_pass = (consistent and fixed.get('error') is None and fixed.get('functionalParity') is True
                      and count(fixed.get('observedMovements')) == expected_count
                      and count(fixed.get('frames')) == expected_frames)
        rows.append({'id': identifier, 'expectedMovements': count(case.get('expectedMovements')),
                     'automaticMovements': count(auto.get('observedMovements')),
                     'manualDiagnosticMovements': count(fixed.get('observedMovements')),
                     'automaticPassed': auto_pass,
                     'manualDiagnosticPassed': fixed_pass})
    seen = [row['id'] for row in rows]
    complete = bool(expected) and len(expected) == len(manifest) and len(seen) == len(set(seen)) and set(seen) == set(expected)
    passed = complete and instrumentation_status == 0 and collection_status == 0 and report.get('manifestError') is None and not report.get('failures') and all(
        row['automaticPassed'] and row['manualDiagnosticPassed'] for row in rows)
    return {'status': 'passed' if passed else 'failed', 'cases': rows}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('operation', choices=('unpack', 'summary'))
    parser.add_argument('source', type=Path)
    parser.add_argument('destination', type=Path)
    parser.add_argument('--instrumentation-status', type=Path)
    parser.add_argument('--collection-status', type=Path)
    args = parser.parse_args()
    if args.operation == 'unpack':
        unpack(args.source, args.destination)
    else:
        args.destination.parent.mkdir(parents=True, exist_ok=True)
        def status(path):
            try:
                return int(path.read_text().strip()) if path else None
            except (OSError, ValueError):
                return None
        try:
            result = summary(args.source, status(args.instrumentation_status), status(args.collection_status))
        except (ValueError, KeyError, TypeError, AttributeError):
            # Keep malformed source evidence available for encrypted diagnosis.
            # No arbitrary exception text belongs in a public summary or log.
            result = {'status': 'invalid_native_report', 'cases': []}
        args.destination.write_text(json.dumps(result, indent=2)+'\n')


if __name__ == '__main__':
    main()
