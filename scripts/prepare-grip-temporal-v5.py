#!/usr/bin/env python3
"""Track before sampling, and retain missing slots in an unlabelled test inventory."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path


def load_module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).with_name(filename))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


temporal = load_module('temporal_prepare', 'grip-temporal-v5.py')
fallback = load_module('temporal_fallback', 'prepare-threeway-crops.py')


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('stage', choices=['track', 'fallback'])
    parser.add_argument('input', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    target = args.output / 'rows.jsonl'
    assert not target.exists()
    rows = [json.loads(line) for line in args.input.read_text().splitlines()]
    coverage = []
    if args.stage == 'track':
        groups = {}
        for row in rows:
            groups.setdefault(row['recordingId'], []).append(row)
        selected = []
        for group in groups.values():
            group.sort(key=lambda r: r['pose']['timestamp']['value'] / r['pose']['timestamp']['timescale'])
            tracker = temporal.SubjectTracker()
            for row in group:
                t = row['pose']['timestamp']
                row['subjectTrack'] = tracker.update(row['pose']['people'], t['value'] / t['timescale'])
                plan = copy.deepcopy(row['patchPlan'])
                row['originalTemporalPlan'] = plan
                track = row['subjectTrack']
                if track['status'] != 'tracked':
                    row['patchPlan'].update(personIndex=None, targets=[], status=track['status'])
                elif track['personIndex'] != plan.get('personIndex'):
                    row['patchPlan'].update(personIndex=track['personIndex'], targets=[], status='trackedFallbackOnly')
            end = group[-1]['pose']['timestamp']
            end = end['value'] / end['timescale']
            for request in [i + .5 for i in range(9)]:
                if request > end + .125:
                    coverage.append(dict(caseId=group[0]['caseId'], requested=request, status='beyondSourceEnd'))
                    continue
                row = min(group, key=lambda r: abs(r['pose']['timestamp']['value'] / r['pose']['timestamp']['timescale'] - request))
                assert abs(row['pose']['timestamp']['value'] / row['pose']['timestamp']['timescale'] - request) <= .26
                row['requestedSeconds'] = request
                row['annotation'] = dict(recordingId=row['recordingId'], sourceGroup=row['sourceGroup'], split='test',
                                         label='unknown', viewpoint='unknown', start=0, end=end, evidence='Unlabelled; geometry and model inference never consume reference labels')
                selected.append(row)
                coverage.append(dict(caseId=row['caseId'], requested=request, status='selected', imageSHA256=row['imageSHA256']))
        rows = selected
    else:
        from PIL import Image
        for row in rows:
            track = row['subjectTrack']
            if track['status'] != 'tracked':
                for p in row['patches']:
                    p['status'] = 'noTrackedSubject'
                continue
            index = track['personIndex']
            assert row['patchPlan']['personIndex'] == index
            source = Path(row['framesDirectory']) / row['image']
            assert sha(source) == row['imageSHA256']
            with Image.open(source) as original:
                image = original.convert('RGB')
                for patch in row['patches']:
                    if patch['status'] == 'patchReady' or patch['status'] == 'overlappingHandPatches':
                        continue
                    geometry = fallback.geometry(row['pose']['people'][index], patch['side'], image.size)
                    if geometry is None:
                        continue
                    box, wrist = geometry
                    output = args.output / f"{row['recordingId']}-{row['imageSHA256']}-{patch['side']}.png"
                    image.crop(box).resize((128, 128), Image.Resampling.BILINEAR).save(output)
                    old = copy.deepcopy(patch)
                    patch.update(status='patchReady', origin='automaticWristFallback', box=box, detectedWrist=wrist,
                                 patch=str(output.absolute()), patchSHA256=sha(output), originalAutomatic=old,
                                 geometry='Existing observed-wrist crop, bound to continuous tracked athlete')
    target.write_text(''.join(json.dumps(r) + '\n' for r in rows))
    (args.output / 'receipt.json').write_text(json.dumps(dict(stage=args.stage, inputSHA256=sha(args.input), outputSHA256=sha(target),
        scriptSHA256=sha(__file__), frames=len(rows), coverage=coverage), indent=2) + '\n')
    print(json.dumps(dict(stage=args.stage, frames=len(rows), output=str(target))))


if __name__ == '__main__':
    main()
