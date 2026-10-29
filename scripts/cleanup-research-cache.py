#!/usr/bin/env python3
"""Inspect or remove an explicit set of reproducible, repo-local compiler caches.

Defaults to inspection. Never removes datasets, frames, patches, models, virtual
environments, compiled products, dependency artifacts, labels or evaluation logs.
Run between jobs, when compiler processes are idle.
"""
import argparse
import json
from pathlib import Path
import shutil


ROOT = Path(__file__).resolve().parents[1]
CACHES = [
    'build/grip-patches/swift-cache',
    'build/grip-supervised/swift-module-cache',
]
for exporter in ['grip-training', 'public-grip-training']:
    for name in ['SDKStatCaches.noindex', 'SDKExplicitPrecompiledModules',
                 'ModuleCache.noindex', 'CompilationCache.noindex',
                 'Intermediates.noindex']:
        CACHES.append(f'build/{exporter}/exporter/.build/out/{name}')
for derived in ['build/phone-evaluation/DerivedData', 'build/DerivedData']:
    for name in ['SDKStatCaches.noindex', 'SDKExplicitPrecompiledModules',
                 'ModuleCache.noindex', 'CompilationCache.noindex',
                 'Build/Intermediates.noindex']:
        CACHES.append(derived+'/'+name)


def allocated(path):
    """Reported blocks are an estimate; APFS clones/snapshots can share blocks."""
    total = path.lstat().st_blocks * 512
    for child in path.rglob('*'):
        total += child.lstat().st_blocks * 512
    return total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apply', action='store_true', help='Remove listed caches; compilers must be idle')
    args = parser.parse_args()
    before = shutil.disk_usage(ROOT).free
    rows = []
    for relative in CACHES:
        path = ROOT/relative
        if not path.exists() and not path.is_symlink():
            continue
        # Do not follow relocated/symlinked cache directories outside the repo.
        if path.is_symlink() or path.resolve() != path.absolute():
            raise RuntimeError('Refusing symlinked cache path: '+str(path))
        assert path.is_dir() and path.is_relative_to(ROOT/'build')
        rows.append(dict(path=relative, estimatedAllocatedBytes=allocated(path)))
    if args.apply:
        for row in rows:
            shutil.rmtree(ROOT/row['path'])
    after = shutil.disk_usage(ROOT).free
    print(json.dumps(dict(mode='removed' if args.apply else 'inspection', caches=rows,
                         estimatedCacheBytes=sum(r['estimatedAllocatedBytes'] for r in rows),
                         freeBytesBefore=before, freeBytesAfter=after,
                         freeBytesChange=after-before), indent=2))


if __name__ == '__main__':
    main()
