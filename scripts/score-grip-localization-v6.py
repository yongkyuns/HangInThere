#!/usr/bin/env python3
"""Count manually reviewed complete target hands, retaining every source frame."""
import argparse
from collections import Counter, defaultdict
import hashlib
import json
from pathlib import Path


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def metrics(items, references, codes):
    counts = Counter()
    found = set()
    per_video = defaultdict(Counter)
    for item in items:
        code = codes[item['reviewId']]
        ref = references[item['frameId']]
        counts['acceptedCrops'] += 1
        per_video[item['recordingId']]['acceptedCrops'] += 1
        if code.startswith('h'):
            assert code in ref['expectedHands']
            key = item['frameId'], code
            category = 'duplicateHandAccept' if key in found else 'correctCompleteTargetHand'
            found.add(key)
        else:
            category = dict(p='partialOrOccluded', n='notAHand', w='wrongPerson', a='ambiguousOwnership', o='notOverhead', u='unverifiable')[code]
        counts[category] += 1
        per_video[item['recordingId']][category] += 1
    expected = sum(len(r['expectedHands']) for r in references.values())
    return dict(sourceFrames=len(references), expectedCompleteVisibleHands=expected, coveredCompleteHands=len(found),
                completeHandCoverage=len(found)/expected if expected else None, counts=dict(counts), perVideo=dict(per_video))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['source_references', 'crop_index', 'crop_codes', 'freeze', 'output']:
        parser.add_argument(name, type=Path)
    args = parser.parse_args()
    assert not args.output.exists()
    frozen = json.loads(args.freeze.read_text())
    assert all(sha(path) == digest for path, digest in frozen['sha256'].items())
    refs = json.loads(args.source_references.read_text())
    index = json.loads(args.crop_index.read_text())
    labels = json.loads(args.crop_codes.read_text())
    assert labels['indexSHA256'] == sha(args.crop_index)
    assert labels['sourceReferencesSHA256'] == sha(args.source_references)
    assert set(labels['codes']) == {p['reviewId'] for p in index['patches']}
    assert all(sha(p['patch']) == p['patchSHA256'] and sha(p['source']) == p['imageSHA256'] for p in index['patches'])
    references = {r['frameId']: r for r in refs['frames']}
    result = {method: metrics([p for p in index['patches'] if p['method'] == method], references, labels['codes']) for method in ['baseline','candidate']}
    result.update(scope='Localization-only comparison on identical source frames. Agent pixel reviews, not independent human gold labels; no grip accuracy inference.',
        sourceReferencesSHA256=sha(args.source_references), cropIndexSHA256=sha(args.crop_index), cropCodesSHA256=sha(args.crop_codes), freezeSHA256=sha(args.freeze))
    args.output.write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps({k:{x:v[x] for x in ['sourceFrames','expectedCompleteVisibleHands','coveredCompleteHands','counts']} for k,v in result.items() if k in ['baseline','candidate']}, indent=2))


if __name__ == '__main__':
    main()
