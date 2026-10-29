#!/usr/bin/env python3
"""Retain all remaining unseen recordings from the already pinned public archive."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tarfile


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    root = Path(__file__).resolve().parents[1]
    spec = importlib.util.spec_from_file_location('v6_inventory', root/'scripts/acquire-grip-temporal-v5.py')
    inventory = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(inventory)
    seen, pins = inventory.prior_ids(root)
    original = json.loads((root/'build/grip-temporal-v5/fresh-intake.json').read_text())
    archive = root/'Data/external/grip-temporal-v5/k700_val_454.tar.gz'
    assert sha(archive) == original['archiveSHA256']
    output = root/'build/grip-localization-v6'
    output.mkdir(parents=True, exist_ok=True)
    assert not (output/'fresh-intake.json').exists()
    cache = root/'Data/external/grip-localization-v6'
    cache.mkdir(parents=True, exist_ok=True)
    cases = []
    with tarfile.open(archive) as tar:
        members = [m for m in tar.getmembers() if m.isfile() and re.fullmatch(r'pull ups/[A-Za-z0-9_-]{11}_\d+_\d+\.mp4', m.name)]
        members.sort(key=lambda m: hashlib.sha256(m.name.encode()).hexdigest())
        for member in members:
            video_id = Path(member.name).stem.rsplit('_', 2)[0]
            if video_id in seen:
                continue
            seen.add(video_id)
            assert 0 < member.size < 25_000_000
            payload = tar.extractfile(member).read()
            path = cache/Path(member.name).name
            with path.open('xb') as stream:
                stream.write(payload)
            cases.append(dict(id='k700val_'+path.stem, sourceGroup='youtube:'+video_id, videoId=video_id,
                              path=str(path), sha256=sha(path), bytes=len(payload), sourceURL=original['sourceURL'], archiveMember=member.name))
    assert 0 < len(cases) <= 8, 'Bounded archive remainder; do not silently expand'
    result = dict(scope='Localization test only; all remaining uninventoried recordings, no screening or replacements',
                  sourceURL=original['sourceURL'], archiveSHA256=original['archiveSHA256'], cases=cases,
                  selection='All remaining recording IDs, hash-ordered before pixels or hand inference',
                  excludedRecordingIds=sorted(seen - {c['videoId'] for c in cases}), priorInventorySHA256=pins, scriptSHA256=sha(__file__))
    (output/'fresh-intake.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(dict(videos=len(cases), newVideoBytes=sum(c['bytes'] for c in cases), networkDownloads=0)))


if __name__ == '__main__':
    main()
