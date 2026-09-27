#!/usr/bin/env python3
"""Inspect an official Penn Action archive; do not infer permission or run a model.

Read members explicitly, never extractall. Keep only a deterministic sample of
pull-up sequences locally. Public review output is limited to metadata, native
release text and three low-resolution annotated thumbnails per selected clip.
"""
from __future__ import annotations
import argparse
from collections import Counter
import io
from pathlib import Path, PurePosixPath
import re
import tarfile

import evaluation as ev

SOURCE = 'https://www.cis.upenn.edu/~kostas/Penn_Action.tar.gz'


def member_path(member):
    path = PurePosixPath(member.name)
    ev.require(not path.is_absolute() and '..' not in path.parts, 'Unsafe archive path')
    return path


def read_member(archive, member, maximum):
    ev.require(member.isfile() and 0 <= member.size <= maximum, 'Invalid archive member')
    with archive.extractfile(member) as stream:
        data = stream.read(maximum + 1)
    ev.require(len(data) == member.size, 'Truncated archive member')
    return data


def choose_sequences(rows, count):
    """Equally spaced IDs in each original split, chosen before inference."""
    ev.require(count >= 2 and count % 2 == 0, 'Use a positive even sample count')
    selected = []
    for split in (-1, 1):
        candidates = sorted(r['id'] for r in rows if r['train'] == split)
        wanted = count // 2
        ev.require(len(candidates) >= wanted, 'Not enough target sequences in a native split')
        selected += [candidates[round(i * (len(candidates) - 1) / max(1, wanted - 1))] for i in range(wanted)]
    ev.require(len(set(selected)) == count, 'Selection duplicated an ID')
    return sorted(selected)


def inspect(source, root, public, count):
    from scipy.io import loadmat
    from PIL import Image, ImageDraw
    root.mkdir(parents=True, exist_ok=False)
    public.mkdir(parents=True, exist_ok=False)
    rows, labels, texts = [], {}, {}
    with tarfile.open(source, 'r|gz') as archive:
        for member in archive:
            path = member_path(member)
            if not member.isfile():
                continue
            if len(path.parts) >= 2 and path.parts[-2] == 'labels' and re.fullmatch(r'\d{4}\.mat', path.name):
                data = read_member(archive, member, 2_000_000)
                raw = loadmat(io.BytesIO(data), simplify_cells=True)
                raw = raw.get('annotation', raw)
                identifier = path.stem
                ev.require(identifier not in labels, 'Duplicate annotation ID')
                labels[identifier] = data
                rows.append({'id': identifier, 'action': str(raw['action']),
                             'train': int(raw['train']), 'nframes': int(raw['nframes']),
                             'dimensions': raw['dimensions'].tolist(),
                             'view': str(raw.get('pose', 'unspecified'))})
            elif member.size < 262144 and (re.search(r'readme|license|licence|terms|copying', path.name, re.I)
                                          or path.suffix == '.m' and 'tools' in path.parts):
                texts[str(path)] = read_member(archive, member, 262144).decode('utf-8', errors='replace')
    targets = [r for r in rows if r['action'] == 'pull_ups']
    selected = choose_sequences(targets, count)
    (root / 'labels').mkdir()
    for identifier in selected:
        (root / 'labels' / (identifier + '.mat')).write_bytes(labels[identifier])
    extracted = Counter()
    with tarfile.open(source, 'r|gz') as archive:
        for member in archive:
            path = member_path(member)
            if not member.isfile() or len(path.parts) < 3:
                continue
            if path.parts[-3] != 'frames' or path.parts[-2] not in selected or not re.fullmatch(r'\d{6}\.jpg', path.name):
                continue
            destination = root / 'frames' / path.parts[-2] / path.name
            destination.parent.mkdir(parents=True, exist_ok=True)
            ev.require(not destination.exists(), 'Duplicate frame path')
            destination.write_bytes(read_member(archive, member, 8_000_000))
            extracted[path.parts[-2]] += 1
    sampled = []
    limb_pairs = [(1, 3), (3, 5), (2, 4), (4, 6), (7, 9), (9, 11), (8, 10), (10, 12)]
    for identifier in selected:
        row = next(r for r in targets if r['id'] == identifier)
        ev.require(extracted[identifier] == row['nframes'], 'Native frame count mismatch')
        raw = loadmat(root / 'labels' / (identifier + '.mat'), simplify_cells=True)
        raw = raw.get('annotation', raw)
        chosen = sorted({0, row['nframes'] // 2, row['nframes'] - 1})
        sheet = Image.new('RGB', (400 * len(chosen), 340), 'white')
        frame_records = []
        for column, index in enumerate(chosen):
            path = root / 'frames' / identifier / f'{index + 1:06d}.jpg'
            with Image.open(path) as original:
                original.load()
                image = original.convert('RGB')
                width, height = image.size
                scale = min(400 / width, 300 / height)
                image = image.resize((round(width * scale), round(height * scale)))
            # Review overlay uses raw native values; it does NOT adjudicate the
            # zero/one-based origin or change any native labels toward a model.
            draw = ImageDraw.Draw(image)
            points = [(float(raw['x'][index, j]), float(raw['y'][index, j])) for j in range(13)]
            visible = [bool(raw['visibility'][index, j]) for j in range(13)]
            for a, b in limb_pairs:
                if visible[a] and visible[b]:
                    draw.line([tuple(v * scale for v in points[a]), tuple(v * scale for v in points[b])], fill='cyan', width=2)
            for j, ((x, y), v) in enumerate(zip(points, visible)):
                if v:
                    draw.ellipse((x * scale - 3, y * scale - 3, x * scale + 3, y * scale + 3), outline='red', width=2)
                    draw.text((x * scale + 4, y * scale), str(j), fill='yellow', stroke_width=1, stroke_fill='black')
            sheet.paste(image, (400 * column, 28))
            ImageDraw.Draw(sheet).text((400 * column + 5, 5), f'{identifier} / native frame {index + 1} / {width}x{height}', fill='black')
            frame_records.append({'frame_index': index, 'image_sha256': ev.digest(path),
                                  'width': width, 'height': height, 'points': points, 'visibility': visible})
        sheet.save(public / (identifier + '-review.jpg'), quality=85)
        sampled.append({**row, 'native_sha256': ev.digest(root / 'labels' / (identifier + '.mat')),
                        'review_frames': frame_records})
    report = {'schema_version': 1, 'scope': 'native format and visual-review acquisition; not permission approval or model evaluation',
              'source_url': SOURCE, 'source_sha256': ev.digest(source), 'source_bytes': source.stat().st_size,
              'sequences': len(rows), 'counts_by_action': dict(Counter(r['action'] for r in rows)),
              'target_sequences': len(targets), 'target_frames': sum(r['nframes'] for r in targets),
              'selection': 'six equally spaced IDs in each native split, chosen before inference',
              'selected': sampled, 'native_release_text': texts,
              'media_redistribution': 'No full videos, image sequences or archive exported; diagnostic thumbnails only.'}
    ev.write_json(public / 'acquisition.json', report)
    print(f"Penn native acquisition: {len(rows)} sequences; {len(targets)} pull-up sequences; {len(selected)} selected; {sum(extracted.values())} selected images", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('archive', type=Path)
    parser.add_argument('--root', type=Path, required=True)
    parser.add_argument('--review-output', type=Path, required=True)
    parser.add_argument('--count', type=int, default=12)
    args = parser.parse_args()
    inspect(args.archive, args.root, args.review_output, args.count)


if __name__ == '__main__':
    main()
