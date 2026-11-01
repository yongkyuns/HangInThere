#!/usr/bin/env python3
"""Generate Android exercise drawables from the canonical iOS SVG artwork."""
from pathlib import Path
import argparse
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NAMES = {"PullUp": "pull_up", "Dip": "dip", "PushUp": "push_up", "SitUp": "sit_up", "LegRaise": "leg_raise", "LyingLegRaise": "lying_leg_raise",
         "Bench": "bench", "Curl": "curl", "Squat": "squat", "Row": "row", "Other": "other"}


def drawable(name):
    source = ROOT / f"ios/HangInThere/Assets.xcassets/Exercise{name}.imageset/icon.svg"
    svg = ET.parse(source).getroot()
    lines = ['<?xml version="1.0" encoding="utf-8"?>',
             f'<!-- Generated from Exercise{name}.imageset/icon.svg; run scripts/generate-exercise-icons.py. -->',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android" android:width="64dp" android:height="64dp" android:viewportWidth="64" android:viewportHeight="64">']
    for path in svg:
        if path.tag.rsplit('}', 1)[-1] != 'path':
            raise ValueError(f"Unsupported SVG element in {source}")
        attrs = {'fillColor': '#FF000000' if path.get('fill') != 'none' else '@android:color/transparent',
                 'pathData': path.attrib['d']}
        if path.get('stroke'):
            attrs.update(strokeColor='#FF000000', strokeWidth=path.attrib['stroke-width'],
                         strokeLineCap=path.get('stroke-linecap', 'round'), strokeLineJoin=path.get('stroke-linejoin', 'round'))
        if path.get('opacity'):
            attrs['strokeAlpha' if path.get('stroke') else 'fillAlpha'] = path.attrib['opacity']
        lines.append('    <path ' + ' '.join(f'android:{key}="{value}"' for key, value in attrs.items()) + '/>')
    return '\n'.join(lines + ['</vector>', ''])


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true', help='Check parity without rewriting files')
    args = parser.parse_args()
    for name, resource in NAMES.items():
        target = ROOT / f'android/app/src/main/res/drawable/exercise_{resource}.xml'
        content = drawable(name)
        if args.check:
            if target.read_text() != content:
                parser.exit(1, f'{target} differs from its SVG source\n')
        else:
            target.write_text(content)
    print(f'{len(NAMES)} exercise drawables {"verified" if args.check else "generated"}.')
