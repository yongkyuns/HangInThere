#!/usr/bin/env python3
"""Freeze additional train/validation source roles before hand extraction."""
import hashlib
import json
from collections import Counter, defaultdict
from pathlib import Path


def main():
    root = Path('build/grip-threeway')
    notes = json.loads((root / 'source-selection-notes.json').read_text())
    pools = [('previousTrainingCandidates', notes['olderNeutral'], Path('build/grip-expanded/training-intake.json')),
             ('additionalTrainingCandidates', notes['additional'], root / 'additional-training-intake.json')]
    sources = []
    for origin, entries, path in pools:
        intake = json.loads(path.read_text())
        for number, grip, view, reason, *window in entries:
            c = dict(intake['cases'][number - 1], origin=origin, sourceReviewId=f'D{number:03d}',
                     grip=grip, view=view, reason=reason, start=window[0] if window else 0,
                     end=window[1] if window else 20, selected=True)
            if origin == 'previousTrainingCandidates' and number in [120, 122]:
                c['subjectId'] = 'pink-gym-neutral-pair'
            sources.append(c)
    groups = defaultdict(list)
    for c in sources:
        groups[(c['grip'], c.get('subjectId', c['sourceGroup']))].append(c)
    for grip in ['underhand', 'neutral']:
        names = sorted([k for k in groups if k[0] == grip], key=lambda k: hashlib.sha256(k[1].encode()).hexdigest())
        validation = set(names[:max(4, round(len(names) * .23))])
        for k in names:
            for c in groups[k]:
                c['split'] = 'validation' if k in validation else 'train'
    reserve = json.loads(Path('Evaluation/fixtures/grip-threeway-test-reserve-v1.json').read_text())
    assert not {c['sourceGroup'] for c in sources} & {c['sourceGroup'] for c in reserve['sources']}
    output = root / 'selected-intake.json'
    assert not output.exists()
    output.write_text(json.dumps(dict(role='trainingCandidates', cases=sources), indent=2) + '\n')
    review = dict(schemaVersion=1,
                  scope='Provisional source grip/view; direct hand surface and grip pixels require two review passes before fitting',
                  frameSampling=dict(seconds=[2, 4]), sources=sources,
                  additionalCandidateReview=dict(pages=[f'source-review-{p:02}.png' for p in range(1, 21)], candidateVideos=120),
                  olderNeutralCandidateReview=dict(pages=[f'neutral-candidate-review-{p:02}.png' for p in range(1, 5)], candidateVideos=20))
    Path('Evaluation/fixtures/grip-threeway-source-roles-v1.json').write_text(json.dumps(review, indent=2) + '\n')
    print(Counter((c['grip'], c['split']) for c in sources))


if __name__ == '__main__':
    main()
