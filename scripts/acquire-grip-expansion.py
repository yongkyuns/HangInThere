#!/usr/bin/env python3
"""Acquire only relevant public research videos, with pinned source provenance.

Kinetics candidates are selected by member-name SHA256 before grip review. UCF
uses HTTP ZIP ranges to avoid downloading unrelated action classes. These are
action labels, not grip labels or a grant of redistribution rights.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import tarfile
import urllib.request

KINETICS_URL="https://s3.amazonaws.com/kinetics/700_2020/train/k700_train_454.tar.gz"
KINETICS_BYTES=995566837
KINETICS_SHA="5b082c056828aeb5e3e4e8e7a3abe0262e7cbadf1b8d97e6cef89d274573f9d9"
UCF_URL="https://huggingface.co/datasets/quchenyuan/UCF101-ZIP/resolve/04d4e5ca1dc93606cb58752b0c08331e598743a4/UCF-101.zip"


def digest(path):
    h=hashlib.sha256()
    with path.open("rb") as f:
        while chunk:=f.read(8*1024*1024):h.update(chunk)
    return h.hexdigest()


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("dataset",choices=["kinetics","ucf101"])
    parser.add_argument("cache",type=Path);parser.add_argument("output",type=Path)
    parser.add_argument("--fetch",action="store_true")
    parser.add_argument("--offset",type=int,default=0)
    parser.add_argument("--count",type=int,default=100)
    args=parser.parse_args()
    assert args.offset>=0 and args.count>0
    args.cache.mkdir(parents=True,exist_ok=True);rows=[]
    if args.dataset=="kinetics":
        archive=args.cache/"k700_train_454.tar.gz"
        if not archive.exists():
            assert args.fetch,"Missing publisher archive; pass --fetch"
            partial=archive.with_suffix(".partial")
            with urllib.request.urlopen(KINETICS_URL,timeout=60) as response,partial.open("xb") as f:
                count=0
                while chunk:=response.read(8*1024*1024):
                    count+=len(chunk);assert count<=KINETICS_BYTES;f.write(chunk)
            assert count==KINETICS_BYTES and digest(partial)==KINETICS_SHA
            partial.rename(archive)
        assert archive.stat().st_size==KINETICS_BYTES and digest(archive)==KINETICS_SHA
        with tarfile.open(archive,"r:gz") as tar:
            members=[m for m in tar.getmembers() if m.isfile() and re.fullmatch(r"pull ups/[A-Za-z0-9_-]+_\d+_\d+\.mp4",m.name)]
            members.sort(key=lambda m:hashlib.sha256(m.name.encode()).hexdigest())
        selected_members=members[args.offset:args.offset+args.count]
        assert len(selected_members)==args.count
        selected={m.name for m in selected_members};found={}
        # Stream once in archive order. Seeking backwards through gzip for each
        # hash-sorted member would repeatedly decompress the whole archive.
        with tarfile.open(archive,"r|gz") as tar:
            for info in tar:
                if info.name not in selected:continue
                assert 0<info.size<=25_000_000
                payload=tar.extractfile(info).read();path=args.cache/Path(info.name).name
                if not path.exists():path.write_bytes(payload)
                assert path.read_bytes()==payload
                video_id=path.stem.rsplit("_",2)[0]
                found[info.name]=dict(id="k700train_"+path.stem,sourceGroup="youtube:"+video_id,videoId=video_id,
                    path=str(path.absolute()),sourceURL=KINETICS_URL,archiveMember=info.name,bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest())
        assert set(found)==selected
        rows=[found[m.name] for m in selected_members]
        output=dict(sourceURL=KINETICS_URL,archiveSHA256=KINETICS_SHA,archiveBytes=KINETICS_BYTES,
            availablePullUpClips=len(members),selection=f"{args.count} by SHA256 of archive member name starting at offset {args.offset}, before grip labels or classifier inference",cases=rows)
    else:
        assert args.fetch,"ZIP range inspection needs public network access; pass --fetch"
        import remotezip
        with remotezip.RemoteZip(UCF_URL,timeout=45) as archive:
            members=[m for m in archive.infolist() if re.search(r"/v_PullUps_g\d+_c\d+\.avi$",m.filename)]
            assert len(members)==100 and sum(m.file_size for m in members)<100_000_000
            for info in members:
                path=args.cache/Path(info.filename).name
                assert 0<info.file_size<=10_000_000
                payload=archive.read(info)
                if not path.exists():path.write_bytes(payload)
                assert path.read_bytes()==payload and len(payload)==info.file_size
                rows.append(dict(id=path.stem,sourceGroup="ucf101:PullUps:"+re.search(r"_g(\d+)_",path.name)[1],path=str(path.absolute()),
                    sourceURL=UCF_URL,archiveMember=info.filename,crc32=info.CRC,bytes=len(payload),sha256=hashlib.sha256(payload).hexdigest()))
        output=dict(sourceURL=UCF_URL,cases=rows)
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x") as f:json.dump(output,f,indent=2);f.write("\n")
    print(json.dumps(dict(candidates=len(rows),output=str(args.output))))


if __name__=="__main__":main()
