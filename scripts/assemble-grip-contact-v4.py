#!/usr/bin/env python3
"""Bind explicit contact reviews; inherit only already reviewed closed examples."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def bind(rows, notes):
    required = {p['contactReviewId'] for r in rows for p in r['patches'] if p['status'] == 'patchReady' and p.get('contactReviewRequired')}
    assert required == set(notes['first']) == set(notes['second']), 'Every required crop needs two direct reviews'
    refs = []
    seen = set()
    for row in rows:
        for patch in row['patches']:
            if patch['status'] != 'patchReady':
                continue
            key = patch['contactReviewId']
            if key in required:
                first, second = notes['first'][key], notes['second'][key]
                conflict = first != second
                decision = notes.get('adjudications', {}).get(key)
                assert not conflict or decision, 'Review disagreement requires direct visual adjudication: ' + key
                label = decision['contact'] if decision else first
                eligible = label != 'uncertain'
                method = 'Two direct reviews of actual tight/wider RGB; disagreements visually adjudicated'
            else:
                inherited = patch['contactReference']
                assert inherited['contact'] == 'closed' or not inherited['qualityEligible']
                label, eligible = inherited['contact'], inherited['qualityEligible']
                first = second = decision = None
                conflict = False
                method = 'Inherited prior direct contact review; original hand pixels retained inside wider crop. Original masked identities remain masked.'
            assert label in ['closed', 'open', 'invalid', 'uncertain']
            identity = row['recordingId'], row['imageSHA256'], patch['side']
            assert identity not in seen, 'Duplicate fitting/test hand sample'
            seen.add(identity)
            wide = patch['contactCrop']
            ref = dict(reviewId=key, caseId=row['caseId'], recordingId=row['recordingId'], imageSHA256=row['imageSHA256'],
                       patchSHA256=patch['patchSHA256'], contactSHA256=wide['sha256'], box=wide['box'], side=patch['side'],
                       split=row['annotation']['split'], contact=label, qualityEligible=eligible, method=method,
                       first=first, second=second, conflict=conflict, adjudication=decision)
            if key in notes.get('surfaces', {}):
                ref['surface'] = notes['surfaces'][key]
            patch['contactReference'] = ref
            refs.append(ref)
    return refs


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--inputs', nargs='+', type=Path, required=True)
    parser.add_argument('--notes', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--review', type=Path, required=True)
    args = parser.parse_args()
    assert not args.output.exists() and not args.review.exists()
    notes = json.loads(args.notes.read_text())
    assert all(notes['inputs'][str(path)] == sha(path) for path in args.inputs)
    rows = [json.loads(line) for path in args.inputs for line in path.read_text().splitlines()]
    refs = bind(rows, notes)
    args.output.write_text(''.join(json.dumps(r) + '\n' for r in rows))
    args.review.write_text(json.dumps(dict(schemaVersion=4, qualification='Agent-reviewed contact references, not independent human gold labels; surface and exercise labels never decide contact',
        inputs={str(path): sha(path) for path in args.inputs}, notesSHA256=sha(args.notes), outputSHA256=sha(args.output), patches=refs), indent=2) + '\n')
    print(json.dumps(dict(frames=len(rows), crops=len(refs), labels=dict(Counter(r['contact'] for r in refs)), masked=sum(not r['qualityEligible'] for r in refs))))


if __name__ == '__main__':
    main()
