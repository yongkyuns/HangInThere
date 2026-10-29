#!/usr/bin/env python3
"""Prune unreferenced native RGB exports, retaining every reviewed/model input.

Original videos and all pose/clock metadata remain. Removed PNG paths and hashes
are inventoried for regeneration from the pinned native exporter into a fresh
directory. Default is inspection; never run concurrently with native export.
"""
import argparse
import hashlib
import json
from pathlib import Path
import shutil


def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('native',type=Path);p.add_argument('references',nargs='+',type=Path)
    p.add_argument('--apply',action='store_true');p.add_argument('--receipt',required=True,type=Path)
    a=p.parse_args();repo=Path(__file__).resolve().parents[1];root=a.native.absolute()
    assert root.resolve()==root and root.is_relative_to(repo/'build') and (root/'prepared-threeway.json').is_file()
    assert not a.receipt.exists(),'Preserve earlier prune receipt'
    keep={}
    for path in a.references:
        for line in path.read_text().splitlines():
            row=json.loads(line);image=(Path(row['framesDirectory'])/row['image']).absolute()
            if image.is_relative_to(root):keep[image]=row['imageSHA256']
    assert keep,'No reviewed RGB references bound to this native directory'
    for path,digest in keep.items():assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==digest
    inventory=json.loads((root/'prepared-threeway.json').read_text());candidates=[]
    for case in inventory['cases']:
        if case['status']!='ready':continue
        folder=Path(case['framesDirectory']).absolute();assert folder.resolve()==folder and folder.is_relative_to(root/'frames')
        for path in sorted(folder.glob('frame-*.png')):
            assert not path.is_symlink()
            if path in keep:continue
            candidates.append(dict(path=str(path),sha256=hashlib.sha256(path.read_bytes()).hexdigest(),bytes=path.stat().st_size,recordingId=case['sha256']))
    before=shutil.disk_usage(repo).free
    if a.apply:
        for c in candidates:Path(c['path']).unlink()
    after=shutil.disk_usage(repo).free
    for path,digest in keep.items():assert hashlib.sha256(path.read_bytes()).hexdigest()==digest
    receipt=dict(mode='removed' if a.apply else 'inspection',native=str(root),references={str(f):hashlib.sha256(f.read_bytes()).hexdigest() for f in a.references},
        preservedFrames=len(keep),removedFrames=len(candidates) if a.apply else 0,estimatedBytes=sum(c['bytes'] for c in candidates),freeBytesChange=after-before,
        qualification='Unreferenced reproducible PNG cache only. Pinned original videos, native clocks/poses, all reviewed frames, patches and models retained. Regenerate earlier unsampled times into a fresh export directory.',inventory=candidates)
    a.receipt.write_text(json.dumps(receipt,indent=2)+'\n');print(json.dumps({k:receipt[k] for k in ['mode','preservedFrames','removedFrames','estimatedBytes','freeBytesChange']}))


if __name__=='__main__':main()
