#!/usr/bin/env python3
"""Acquire bounded novel groups from the publisher's pull-up-only K600 archive."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import tarfile
import urllib.request
import argparse

ROOT=Path(__file__).resolve().parents[1]
URL='https://s3.amazonaws.com/kinetics/600/train/pull%20ups.tar.gz'
BYTES=906854188


def sha(p):return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publisher-split',choices=['train','val'],default='train')
    args=parser.parse_args()
    url=URL if args.publisher_split=='train' else 'https://s3.amazonaws.com/kinetics/600/val/pull%20ups.tar.gz'
    expected_bytes=BYTES if args.publisher_split=='train' else 69201871
    spec=importlib.util.spec_from_file_location('prior_inventory',ROOT/'scripts/acquire-grip-temporal-v5.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    excluded,inventories=module.prior_ids(ROOT)
    out=ROOT/'build/grip-front-rear-v9';cache=ROOT/'Data/external/grip-front-rear-v9'
    if args.publisher_split!='train':out=out/('k600-'+args.publisher_split);cache=cache/('k600-'+args.publisher_split)
    out.mkdir(parents=True,exist_ok=True)
    cache.mkdir(parents=True,exist_ok=True)
    assert not (out/'new-intake.json').exists()
    archive=cache/'pull-ups-k600.tar.gz'
    if not archive.exists():
        partial=cache/'pull-ups-k600.partial'
        assert not partial.exists()
        with urllib.request.urlopen(url,timeout=45) as response, partial.open('xb') as f:
            assert int(response.headers['Content-Length'])==expected_bytes
            count=0
            while chunk:=response.read(4*1024*1024):
                count+=len(chunk);assert count<=expected_bytes;f.write(chunk)
        assert count==expected_bytes;partial.rename(archive)
    assert archive.stat().st_size==expected_bytes
    archive_hash=sha(archive)
    with tarfile.open(archive,'r:gz') as tar:
        members=[x for x in tar.getmembers() if x.isfile() and re.fullmatch(r'(?:\./)?(?:pull ups/)?[A-Za-z0-9_-]{11}_\d+_\d+\.mp4',x.name)]
        assert members,'No recognized video members; inspect archive layout before declaring it exhausted'
        names=sorted((x.name for x in members),key=lambda n:hashlib.sha256(n.encode()).hexdigest())
    novel=[];seen=set(excluded)
    for name in names:
        ident=Path(name).stem.rsplit('_',2)[0]
        if ident not in seen:novel.append(name);seen.add(ident)
    assert novel,'No novel groups; do not reassign prior tests'
    # All new fitting sources are validation supplements. Existing training is
    # already covered. Final reserve assigned by member hash, never opened.
    reserve_count=min(24,max(1,len(novel)//5))
    reserve=novel[-reserve_count:]
    development=novel[:-reserve_count][:96]
    freeze=dict(qualification='New publisher groups only; existing training/test groups excluded. New validation-development cohort and untouched final reserve assigned before any source pixels.',
                publisherURL=url,archiveSHA256=archive_hash,archiveBytes=expected_bytes,priorInventorySHA256=inventories,
                excludedRecordingIds=sorted(excluded),novelMembers=len(novel),developmentMembers=development,
                untouchedFinalReserveMembers=reserve,unusedNovelMembers=[n for n in novel if n not in development+reserve])
    (out/'source-role-freeze.json').write_text(json.dumps(freeze,indent=2)+'\n')
    cases=[];reserved=[];wanted=set(development+reserve)
    with tarfile.open(archive,'r|gz') as tar:
        for member in tar:
            if member.name not in wanted:continue
            assert 0<member.size<25_000_000
            data=tar.extractfile(member).read();digest=hashlib.sha256(data).hexdigest()
            record=dict(id='k600_'+Path(member.name).stem,sourceGroup='youtube:'+Path(member.name).stem.rsplit('_',2)[0],
                        archiveMember=member.name,sha256=digest,bytes=len(data),sourceURL=url)
            if member.name in reserve:
                # Store original bytes without decoding/viewing; entire group
                # stays final-test-only until a future model freeze.
                dest=cache/'untouched-final-reserve'/Path(member.name).name;dest.parent.mkdir(exist_ok=True)
                record['split']='futureTest';reserved.append(record)
            else:
                dest=cache/Path(member.name).name;record['split']='validation';cases.append(record)
            with dest.open('xb') as f:f.write(data)
            assert sha(dest)==digest;record['path']=str(dest)
    assert len(cases)==len(development) and len(reserved)==len(reserve)
    (out/'new-intake.json').write_text(json.dumps(dict(role='validationDevelopmentCandidates',cases=cases,
        sourceRoleFreezeSHA256=sha(out/'source-role-freeze.json')),indent=2)+'\n')
    (out/'untouched-final-reserve.json').write_text(json.dumps(dict(role='futureTest',sources=reserved,
        qualification='Originals retained; no frame extraction, labels or model inference permitted before future freeze'),indent=2)+'\n')
    # Full archive is a reproducible duplicate once selected originals/hash and
    # complete roles are retained. Never delete shared earlier dataset caches.
    archive.unlink()
    (out/'archive-cleanup.json').write_text(json.dumps(dict(path=str(archive),removedBytes=expected_bytes,
        sha256=archive_hash,publisherURL=url,reason='Selected originals and untouched reserve retained; no need for duplicate publisher archive'),indent=2)+'\n')
    print(json.dumps(dict(novelGroups=len(novel),validationCandidates=len(cases),untouchedReserve=len(reserved),duplicateArchiveRemovedBytes=expected_bytes)),flush=True)


if __name__=='__main__':main()
