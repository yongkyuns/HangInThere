#!/usr/bin/env python3
"""Prespecified source-time sampling after complete-sequence subject acquisition."""
import argparse
from collections import defaultdict
import hashlib
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def seconds(row):
    t = row['pose']['timestamp']
    return t['value'] / t['timescale']


def main():
    p = argparse.ArgumentParser(description=__doc__)
    for name in ['plans', 'roles', 'output']:
        p.add_argument(name, type=Path)
    a = p.parse_args()
    assert not a.output.exists()
    review = json.loads(a.roles.read_text())
    groups = defaultdict(list)
    for line in a.plans.read_text().splitlines():
        r = json.loads(line)
        groups[r['caseId']].append(r)
    result, coverage = [], []
    for source in review['sources']:
        if not source.get('selected', True):
            continue
        assert source['grip'] in ['overhand', 'underhand', 'neutral']
        group = groups.get(source['id'], [])
        seen = set()
        for t in review['frameSampling']['seconds']:
            if not group or t > max(map(seconds, group)) + .125:
                coverage.append(dict(caseId=source['id'], requested=t, status='beyondSourceEnd'))
                continue
            r = min(group, key=lambda r: abs(seconds(r) - t))
            assert abs(seconds(r) - t) <= .26
            assert r['imageSHA256'] not in seen
            seen.add(r['imageSHA256'])
            assert r['recordingId'] == source['sha256']
            r['annotation'] = dict(recordingId=source['sha256'], sourceGroup=source['sourceGroup'],
                                   subjectId=source.get('subjectId'), split=source['split'],
                                   label=source['grip'], viewpoint=source['view'], start=source['start'],
                                   end=source['end'], evidence=source['reason'])
            r['requestedSeconds'] = t
            result.append(r)
            coverage.append(dict(caseId=source['id'], requested=t, status='selected', imageSHA256=r['imageSHA256']))
    a.output.write_text(''.join(json.dumps(r) + '\n' for r in result))
    a.output.with_suffix('.receipt.json').write_text(json.dumps(dict(plansSHA256=sha(a.plans),
        rolesSHA256=sha(a.roles), outputSHA256=sha(a.output), coverage=coverage), indent=2) + '\n')
    print(json.dumps(dict(frames=len(result), requested=len(coverage))))


if __name__ == '__main__':
    main()
