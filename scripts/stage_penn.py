#!/usr/bin/env python3
"""Stage original Penn Action pull-up sequences for review, not model evaluation.

Reads an existing publisher archive or explicitly downloads it. Inventory covers
all recognized native annotations; only the selected sequences are extracted.
Original images/labels remain unchanged. Rights, subject identity, pixel origin,
and independent label review are NEVER inferred from an archive or a filename.
"""
from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import io
from pathlib import Path, PurePosixPath
import shutil
import sys
import tarfile
import tempfile
import urllib.request

import evaluation as ev
import import_poses as imp

SOURCE_PAGE = 'https://dreamdragon.github.io/PennAction/'
SOURCE_URL = 'https://www.cis.upenn.edu/~kostas/Penn_Action.tar.gz'
GIB = 1024 ** 3
MAX_DOWNLOAD = 12 * GIB
MAX_EXPANDED = 40 * GIB
MAX_LABEL = 8 * 1024 ** 2
MAX_IMAGE = 16 * 1024 ** 2
MAX_MEMBERS = 1_000_000


def download(destination: Path, expected_sha256: str | None = None) -> dict:
    """No overwrite, archive execution, credentials, or automatic URL fallback."""
    ev.require(not destination.exists(), 'Download destination already exists')
    destination.parent.mkdir(parents=True, exist_ok=True)
    request = urllib.request.Request(SOURCE_URL, headers={'User-Agent': 'HangInThere-NativeIntake/1.0'})
    partial = None
    try:
        with tempfile.NamedTemporaryFile(dir=destination.parent, prefix='.penn-', delete=False) as output:
            partial = Path(output.name)
            total = 0
            with urllib.request.urlopen(request, timeout=90) as response:
                ev.require(response.geturl().startswith('https://'), 'Refusing insecure download redirect')
                length = response.headers.get('Content-Length')
                ev.require(length is None or 0 < int(length) <= MAX_DOWNLOAD, 'Publisher archive exceeds size limit')
                while block := response.read(1024 ** 2):
                    total += len(block)
                    ev.require(total <= MAX_DOWNLOAD, 'Download exceeds size limit')
                    output.write(block)
            ev.require(total > 0, 'Empty archive response')
            ev.require(length is None or total == int(length), 'Truncated publisher response')
        actual = ev.digest(partial)
        if expected_sha256:
            ev.require(actual == expected_sha256, 'Archive SHA-256 differs from supplied pin')
        # A separate exclusive destination prevents silently replacing local data.
        created = False
        try:
            with destination.open('xb') as output, partial.open('rb') as source:
                created = True
                shutil.copyfileobj(source, output)
        except BaseException:
            if created:
                destination.unlink(missing_ok=True)
            raise
        return {'requested_url': SOURCE_URL, 'resolved_url': response.geturl(),
                'bytes': total, 'sha256': actual,
                'pin_status': 'matched_supplied_pin' if expected_sha256 else 'locally_recorded_not_publisher_authenticated'}
    finally:
        if partial:
            partial.unlink(missing_ok=True)


def members(archive: Path):
    """Stream a tar without extractall; reject unsafe/ambiguous archive members."""
    total = 0
    seen = set()
    with tarfile.open(archive, mode='r|*') as tar:
        for index, item in enumerate(tar):
            ev.require(index < MAX_MEMBERS, 'Archive member limit exceeded')
            path = PurePosixPath(item.name)
            ev.require(not path.is_absolute() and '..' not in path.parts and '\\' not in item.name,
                       'Unsafe archive path')
            ev.require(item.isdir() or item.isfile() and not item.issparse(), 'Archive links/special files are not supported')
            name = str(path)
            ev.require(name not in seen, 'Duplicate archive path')
            seen.add(name)
            ev.require(item.size >= 0, 'Negative member size')
            total += item.size
            ev.require(total <= MAX_EXPANDED, 'Expanded archive size limit exceeded')
            if item.isfile():
                yield tar, item, path


def native_path(path: PurePosixPath):
    parts = path.parts
    if parts and parts[0] == 'Penn_Action':
        parts = parts[1:]
    if len(parts) == 2 and parts[0] == 'labels' and parts[1].endswith('.mat'):
        identifier = parts[1][:-4]
        ev.require(len(identifier) == 4 and identifier.isascii() and identifier.isdigit(), 'Unexpected native label name')
        return 'label', identifier, None
    if len(parts) == 3 and parts[0] == 'frames' and parts[2].endswith('.jpg'):
        identifier, frame = parts[1], parts[2][:-4]
        ev.require(len(identifier) == 4 and identifier.isascii() and identifier.isdigit()
                   and len(frame) == 6 and frame.isascii() and frame.isdigit() and int(frame) > 0,
                   'Unexpected native frame name')
        return 'image', identifier, int(frame)
    return None


def inspect_archive(archive: Path) -> dict:
    rows, counts, frame_names = {}, Counter(), {}
    for tar, item, path in members(archive):
        parsed = native_path(path)
        if parsed is None:
            continue
        kind, identifier, frame = parsed
        if kind == 'image':
            ev.require(item.size <= MAX_IMAGE, 'Native image member exceeds size limit')
            frames = frame_names.setdefault(identifier, set())
            ev.require(frame not in frames, 'Duplicate logical native frame across archive roots')
            frames.add(frame)
            continue
        ev.require(identifier not in rows, 'Duplicate logical native annotation across archive roots')
        ev.require(item.size <= MAX_LABEL, 'Native annotation member exceeds size limit')
        stream = tar.extractfile(item)
        ev.require(stream is not None, 'Unreadable native annotation')
        data = stream.read(MAX_LABEL + 1)
        ev.require(len(data) == item.size, 'Truncated native annotation')
        coordinates, visibility, info = imp.penn_arrays(io.BytesIO(data))
        counts[info['action']] += 1
        rows[identifier] = {'sequence_id': identifier, 'action': info['action'],
                            'native_split': info['native_split'], 'frames': len(coordinates),
                            'width': info['width'], 'height': info['height'],
                            'native_annotation_sha256': hashlib.sha256(data).hexdigest(),
                            'visible_limb_references': int(visibility[:, list(imp.PENN)].sum())}
    ev.require(rows, 'No supported Penn native annotations found in archive')
    ev.require(set(frame_names) <= set(rows), 'Original images exist without a matching native annotation')
    for identifier, row in rows.items():
        ev.require(frame_names.get(identifier) == set(range(1, row['frames'] + 1)),
                   f'{identifier}: incomplete/noncontiguous original image sequence')
    return {'annotations': [rows[k] for k in sorted(rows)], 'counts_by_action': dict(sorted(counts.items()))}


def stage(archive: Path, output: Path, limit: int = 6, expected_sha256: str | None = None) -> dict:
    from PIL import Image
    ev.require(type(limit) is int and limit >= 0, 'Limit must be nonnegative; 0 explicitly selects all pull-ups')
    ev.require(archive.is_file(), 'Archive is missing')
    ev.require(not output.exists(), 'Output directory already exists')
    ev.require(0 < archive.stat().st_size <= MAX_DOWNLOAD, 'Archive file exceeds size limit or is empty')
    checksum = ev.digest(archive)
    if expected_sha256:
        ev.require(checksum == expected_sha256, 'Archive SHA-256 differs from supplied pin')
    inventory = inspect_archive(archive)
    candidates = [r for r in inventory['annotations'] if r['action'] == 'pull_ups']
    ev.require(candidates, 'No native pull_ups annotations found')
    selected = candidates[:limit] if limit else candidates
    wanted = {r['sequence_id']: r for r in selected}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent, prefix='.penn-stage-') as directory:
        work = Path(directory)
        for tar, item, path in members(archive):
            parsed = native_path(path)
            if parsed is None or parsed[1] not in wanted:
                continue
            kind, identifier, frame = parsed
            relative = Path('labels') / f'{identifier}.mat' if kind == 'label' else Path('frames') / identifier / f'{frame:06d}.jpg'
            target = work / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            source = tar.extractfile(item)
            ev.require(source is not None, 'Unreadable selected archive member')
            with target.open('xb') as destination:
                shutil.copyfileobj(source, destination)
            ev.require(target.stat().st_size == item.size, 'Truncated selected member')
        clips = []
        for row in selected:
            identifier = row['sequence_id']
            files = []
            for index in range(1, row['frames'] + 1):
                image_path = work / 'frames' / identifier / f'{index:06d}.jpg'
                with Image.open(image_path) as image:
                    ev.require(image.format == 'JPEG' and image.size == (row['width'], row['height']),
                               f'{identifier}/{index}: original image format/dimensions disagree with native label')
                    ev.require(image.getexif().get(274, 1) == 1, 'Original EXIF orientation needs a separate review')
                    image.load()
                files.append({'path': image_path.relative_to(work).as_posix(), 'sha256': ev.digest(image_path)})
            label = work / 'labels' / f'{identifier}.mat'
            ev.require(ev.digest(label) == row['native_annotation_sha256'], 'Native label changed between passes')
            clips.append({'id': f'penn_{identifier}', 'dataset': 'Penn Action', 'exercise': 'pull_up',
                          'split': 'unassigned', 'source_group': 'penn_unresolved_sources', 'subject_group': None,
                          'native_sequence_id': identifier, 'native_split': row['native_split'],
                          'rights': {'status': 'pending', 'evidence': '', 'public_outputs': False},
                          'media': {'kind': 'images', 'expected_frames': row['frames'], 'files': files},
                          'native_annotations': {'format': 'penn_action_mat', 'path': f'labels/{identifier}.mat',
                                                 'sha256': row['native_annotation_sha256']},
                          'annotation_review': {'independently_reviewed': False, 'provenance': '',
                                                'pixel_origin': None, 'endpoint_frames': []}})
        ev.require(ev.digest(archive) == checksum, 'Archive changed during intake')
        report = {'schema_version': 1, 'status': 'staged_for_review_not_evaluated',
                  'source_page': SOURCE_PAGE, 'publisher_archive_url': SOURCE_URL,
                  'archive_sha256': checksum, 'archive_bytes': archive.stat().st_size,
                  'checksum_scope': 'matched_supplied_pin' if expected_sha256 else 'local_snapshot_not_publisher_authenticated',
                  'source_authenticity': 'not_inferred_from_filename_or_local_hash',
                  'intake_script_sha256': ev.digest(__file__),
                  'annotation_reader_sha256': ev.digest(imp.__file__),
                  'counts_by_action': inventory['counts_by_action'],
                  'native_annotations_inspected': len(inventory['annotations']),
                  'pull_up_candidates': len(candidates), 'selected_sequences': len(selected),
                  'selected_frames': sum(r['frames'] for r in selected),
                  'selection': 'native sequence ID ascending; limit=0 means all; no inference-based selection',
                  'selected_sequence_ids': list(wanted),
                  'not_selected_sequence_ids': [r['sequence_id'] for r in candidates if r['sequence_id'] not in wanted],
                  'annotations': inventory['annotations'],
                  'model_inference': 'not_run', 'permissions': 'pending_per_corpus_review',
                  'native_import': 'not_run', 'source_subject_disjointness': 'unresolved'}
        manifest = {'schema_version': 1, 'confidence_threshold': 0.3, 'clips': clips}
        ev.validate_manifest(manifest, work)
        ev.write_json(work / 'review.json', manifest)
        ev.write_json(work / 'intake-report.json', report)
        # Rename a complete staged directory, never overwrite an existing corpus.
        ev.require(not output.exists(), 'Output appeared during intake')
        work.rename(output)
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True, help='Existing original Penn tar archive, or destination for --fetch')
    parser.add_argument('--fetch', action='store_true', help='Explicitly download the publisher archive; no overwrite')
    parser.add_argument('--output', type=Path, required=True, help='New local corpus directory; never published automatically')
    parser.add_argument('--limit', type=int, default=6, help='First N pull-up IDs; 0 selects all (default: 6)')
    parser.add_argument('--sha256', help='Optional independently established/repeated-run archive pin')
    args = parser.parse_args()
    try:
        ev.require(args.limit >= 0, 'Limit must be nonnegative')
        ev.require(not args.output.exists(), 'Output directory already exists')
        if args.sha256:
            import re
            ev.require(re.fullmatch('[a-f0-9]{64}', args.sha256) is not None, 'Expected 64 lowercase SHA-256 hex characters')
        acquisition = download(args.archive, args.sha256) if args.fetch else None
        report = stage(args.archive, args.output, args.limit, args.sha256)
        if acquisition:
            ev.write_json(args.output / 'acquisition.json', acquisition)
        print(f"Staged {report['selected_sequences']}/{report['pull_up_candidates']} native pull-up sequences; "
              f"{report['selected_frames']} images. Review is pending; no model was evaluated.")
        return 0
    except (OSError, ValueError, KeyError, TypeError, tarfile.TarError, ImportError) as error:
        print(f'Native intake failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
