#!/usr/bin/env python3
"""Source-only review sheets; never loads poses, labels, models or predictions.

Selects actual decoded frame indices using the original PTS inventory. Originals
are hash checked before/after. Review outputs are immutable and not references
until a separate source-only annotation is frozen.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
from PIL import Image, ImageDraw, ImageFont, ImageOps


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def balanced_sum(terms):
    # FFmpeg limits expression recursion. A long left-associated sum of frame
    # selectors fails even for small review sheets; keep its depth logarithmic.
    if len(terms) == 1:
        return terms[0]
    middle = len(terms)//2
    return '('+balanced_sum(terms[:middle])+'+'+balanced_sum(terms[middle:])+')'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('intake', type=Path)
    parser.add_argument('output', type=Path)
    parser.add_argument('--only', nargs='+')
    parser.add_argument('--step', type=float)
    parser.add_argument('--window', type=float, nargs=2)
    args = parser.parse_args()
    intake_bytes = args.intake.read_bytes()
    intake_sha = hashlib.sha256(intake_bytes).hexdigest()
    rows = json.loads(intake_bytes)['cases']
    rows = [c for c in rows if c['status'] == 'acquired' and (not args.only or c['id'] in args.only)]
    assert rows and (not args.only or set(args.only) == {c['id'] for c in rows})
    assert args.step is None or args.step > 0
    args.output.mkdir(parents=True, exist_ok=True)
    with (args.output/'intake-snapshot.json').open('xb') as f:
        f.write(intake_bytes)
    font = ImageFont.load_default(size=17)
    for row in rows:
        source = Path(row['path'])
        assert sha(source) == row['sha256']
        target = args.output / row['id']
        target.mkdir(exist_ok=False)
        inventory = json.loads(subprocess.check_output(['ffprobe', '-v', 'error', '-select_streams', 'v:0',
            '-show_entries', 'frame=best_effort_timestamp_time', '-of', 'json', str(source)]))
        pts = [float(f['best_effort_timestamp_time']) for f in inventory['frames']]
        assert pts and all(b > a for a, b in zip(pts, pts[1:]))
        first, last = args.window or (pts[0], pts[-1])
        assert pts[0] <= first < last <= pts[-1]
        if args.step:
            times = [first+i*args.step for i in range(int((last-first)/args.step)+1)]
        else:
            times = [first, min(first+1, last), first+(last-first)*.25,
                     first+(last-first)*.5, first+(last-first)*.75, last]
        indices = sorted({min(range(len(pts)), key=lambda i:abs(pts[i]-t)) for t in times})
        selection = balanced_sum([f'eq(n\\,{index})' for index in indices])
        subprocess.run(['ffmpeg', '-v', 'error', '-i', str(source), '-vf', f'select={selection},scale=640:-2',
            '-fps_mode', 'vfr', '-threads', '2', str(target/'selected-%06d.png')], check=True)
        selected = sorted(target.glob('selected-*.png'))
        assert len(selected) == len(indices)
        entries = []
        for index, rendered in zip(indices, selected):
            path = target / f'frame-{index:06d}.png'
            rendered.rename(path)
            entries.append(dict(index=index, sourceSeconds=pts[index], path=str(path), sha256=sha(path)))
        for page in range((len(entries)+11)//12):
            batch = entries[page*12:(page+1)*12]
            sheet = Image.new('RGB', (1260, ((len(batch)+2)//3)*338+35), 'white')
            draw = ImageDraw.Draw(sheet)
            draw.text((8, 6), row['id']+' — source only', fill='black', font=font)
            for i, entry in enumerate(batch):
                x, y = (i%3)*420, (i//3)*338+35
                frame = ImageOps.contain(Image.open(entry['path']).convert('RGB'), (412, 302))
                sheet.paste(frame, (x+(420-frame.width)//2, y+28))
                draw.text((x+7, y+3), f"{entry['sourceSeconds']:.3f}s / frame {entry['index']}", fill='black', font=font)
            sheet.save(target/f'page-{page+1:03d}.png')
        assert sha(source) == row['sha256'] and args.intake.read_bytes() == intake_bytes
        with (target/'review-inventory.json').open('x') as f:
            json.dump(dict(sourceSHA256=row['sha256'], intakeSHA256=intake_sha,
                decodedFrames=len(pts), sourcePTS=pts, frames=entries,
                stage='Source-only images, no inference or automatic eligibility decision'), f, indent=2)
            f.write('\n')
        print(row['id'], len(pts), 'decoded frames;', len(entries), 'review images', flush=True)


if __name__ == '__main__':
    main()
