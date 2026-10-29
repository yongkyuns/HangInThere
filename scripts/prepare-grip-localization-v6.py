#!/usr/bin/env python3
"""Fixed regression sources and matched-clock fresh source/baseline preparation."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('mode', choices=['regression', 'fresh'])
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    assert not args.output.exists()
    rows = [json.loads(l) for l in args.input.read_text().splitlines()]
    selected, missing = [], []
    if args.mode == 'regression':
        # Fixed exposed diagnostics: source-edge truncation, clear hands,
        # coach, bystanders, cropped body, competing athletes and edits.
        cases = {'0vMCq_b2nFo': [5.5, 6.5, 7.5], 'dUbqRIerwMU': [2.5, 4.5, 7.5],
                 'VLhgkbuEO1U': [3.5, 5.5, 7.5], 'Gjw2SxfDoaM': [1.5, 5.5, 7.5],
                 'b8MNl0akNk4': [.5, 2.5, 4.5], 'x0ikjxF1t58': [1.5, 4.5, 7.5],
                 'ENMZ3yxq1dM': [6.5, 7.5, 8.5], 'bWcwPwQr4CU': [.5, 2.5, 6.5]}
        wanted = {}
        for case, times in cases.items():
            for t in times:
                found = [r for r in rows if f'_{case}_' in r['caseId'] and r['requestedSeconds'] == t]
                assert len(found) == 1
                wanted[found[0]['imageSHA256']] = f'R{len(wanted)+1:03d}'
        # Preserve the original sampled prefixes for real temporal ownership.
        # Only the 24 predeclared frames receive localization accuracy scores.
        selected = [r for r in rows if any(f'_{case}_' in r['caseId'] for case in cases)]
        for i, row in enumerate(selected):
            row['evaluationFrame'] = row['imageSHA256'] in wanted
            row['localizationFrameId'] = wanted.get(row['imageSHA256'], f'W{i+1:03d}')
    else:
        spec = importlib.util.spec_from_file_location('v6_baseline_tracking', Path(__file__).with_name('grip-temporal-v5.py'))
        previous = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(previous)
        groups = {}
        for r in rows:
            groups.setdefault(r['recordingId'], []).append(r)
        for group in groups.values():
            group.sort(key=lambda r: r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale'])
            tracker = previous.SubjectTracker()
            for row in group:
                t = row['pose']['timestamp']
                row['subjectTrack'] = tracker.update(row['pose']['people'], t['value']/t['timescale'])
                plan = copy.deepcopy(row['patchPlan'])
                row['originalTemporalPlan'] = plan
                track = row['subjectTrack']
                if track['status'] != 'tracked':
                    row['patchPlan'].update(personIndex=None, targets=[], status=track['status'])
                elif track['personIndex'] != plan.get('personIndex'):
                    row['patchPlan'].update(personIndex=track['personIndex'], targets=[], status='trackedFallbackOnly')
            end = group[-1]['pose']['timestamp']
            end = end['value']/end['timescale']
            seen = set()
            for request in [base+offset for base in [0, 2, 4, 6] for offset in [.25, .5, .75]]:
                if request > end+.125:
                    missing.append(dict(caseId=group[0]['caseId'], requested=request, status='beyondSourceEnd'))
                    continue
                row = min(group, key=lambda r: abs(r['pose']['timestamp']['value']/r['pose']['timestamp']['timescale']-request))
                assert row['imageSHA256'] not in seen
                seen.add(row['imageSHA256'])
                row['requestedSeconds'] = request
                row['annotation'] = dict(recordingId=row['recordingId'], sourceGroup=row['sourceGroup'], split='test', label='unknown', viewpoint='unknown',
                    start=0, end=end, evidence='Unlabelled localization source; no grip labels enter extraction')
                selected.append(row)
    if args.mode == 'fresh':
        for i, r in enumerate(selected):
            r['evaluationFrame'] = True
            r['localizationFrameId'] = f'F{i+1:03d}'
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(''.join(json.dumps(r)+'\n' for r in selected))
    args.output.with_suffix('.receipt.json').write_text(json.dumps(dict(mode=args.mode, inputSHA256=sha(args.input), outputSHA256=sha(args.output),
        scriptSHA256=sha(__file__), frames=len(selected), requestedTimes=[base+offset for base in [0, 2, 4, 6] for offset in [.25, .5, .75]] if args.mode == 'fresh' else cases,
        missing=missing), indent=2)+'\n')
    print(json.dumps(dict(frames=len(selected), missing=len(missing))))


if __name__ == '__main__':
    main()
