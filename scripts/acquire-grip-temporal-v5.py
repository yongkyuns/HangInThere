#!/usr/bin/env python3
"""Bounded fresh-video intake, selected by filename hash before seeing pixels."""
import hashlib
import json
from pathlib import Path
import re
import tarfile
import urllib.request


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def prior_ids(root):
    """Exclude all existing dataset/video groups, including exposed rejected videos."""
    ids, inputs = set(), {}
    for path in sorted((root / 'Evaluation/fixtures').glob('*.json')):
        raw = path.read_text()
        inputs[str(path.relative_to(root))] = sha(path)
        ids.update(re.findall(r'youtube:([A-Za-z0-9_-]{11})(?![A-Za-z0-9_-])', raw))
        ids.update(re.findall(r'"(?:videoId|youtube_id|youtubeId)"\s*:\s*"([A-Za-z0-9_-]{11})"', raw))
        ids.update(re.findall(r'([A-Za-z0-9_-]{11})_\d{6}_\d{6}\.mp4', raw))
    for path in (root / 'Data/external').rglob('*.mp4'):
        match = re.fullmatch(r'([A-Za-z0-9_-]{11})_\d+_\d+\.mp4', path.name)
        if match:
            ids.add(match[1])
    return ids, inputs


def main():
    root = Path(__file__).resolve().parents[1]
    out = root / 'build/grip-temporal-v5'
    out.mkdir(parents=True, exist_ok=True)
    assert not (out / 'fresh-intake.json').exists()
    # Inventory before extracting any new source. The same YouTube recording
    # is excluded even when another segment has different encoded bytes.
    excluded, inventories = prior_ids(root)
    cache = root / 'Data/external/grip-temporal-v5'
    cache.mkdir(parents=True, exist_ok=True)
    url = 'https://s3.amazonaws.com/kinetics/700_2020/val/k700_val_454.tar.gz'
    archive = cache / 'k700_val_454.tar.gz'
    if not archive.exists():
        partial = archive.with_suffix('.partial')
        with urllib.request.urlopen(url, timeout=45) as response, partial.open('xb') as stream:
            assert int(response.headers['Content-Length']) == 61091021
            size = 0
            while chunk := response.read(2 * 1024 * 1024):
                size += len(chunk)
                assert size <= 61091021
                stream.write(chunk)
        assert size == 61091021
        partial.rename(archive)
    assert archive.stat().st_size == 61091021
    cases, rejected = [], []
    with tarfile.open(archive, 'r:gz') as tar:
        members = [m for m in tar.getmembers() if m.isfile() and re.fullmatch(r'pull ups/[A-Za-z0-9_-]{11}_\d+_\d+\.mp4', m.name)]
        members.sort(key=lambda m: hashlib.sha256(m.name.encode()).hexdigest())
        seen = set(excluded)
        for member in members:
            video_id = Path(member.name).stem.rsplit('_', 2)[0]
            if video_id in seen:
                rejected.append(dict(member=member.name, reason='Previously inventoried recording or duplicate segment'))
                continue
            seen.add(video_id)
            assert 0 < member.size < 25_000_000
            payload = tar.extractfile(member).read()
            target = cache / Path(member.name).name
            with target.open('xb') as f:
                f.write(payload)
            cases.append(dict(id='k700val_' + target.stem, sourceGroup='youtube:' + video_id, videoId=video_id,
                              path=str(target), sha256=sha(target), bytes=len(payload), sourceURL=url,
                              archiveMember=member.name, split='test'))
            if len(cases) == 16:
                break
    assert cases, 'No genuinely fresh recordings in this archive'
    result = dict(scope='Fresh video-group test only; no training, threshold selection or source-pixel screening',
                  selection='First up to 16 previously uninventoried recordings ordered by archive-member SHA256, before pixels or predictions. No replacements for difficult or missing evidence.',
                  sourceURL=url, archiveSHA256=sha(archive), archiveBytes=archive.stat().st_size,
                  excludedRecordingIds=sorted(excluded), priorInventorySHA256=inventories, skipped=rejected,
                  availablePullUpClips=len(members), cases=cases, scriptSHA256=sha(__file__))
    (out / 'fresh-intake.json').write_text(json.dumps(result, indent=2) + '\n')
    print(json.dumps(dict(freshVideos=len(cases), previouslyKnownRecordings=len(excluded), skipped=len(rejected), archiveBytes=result['archiveBytes'])))


if __name__ == '__main__':
    main()
