#!/usr/bin/env python3
"""Native extraction with explicit per-video failures retained in the inventory."""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess

spec=importlib.util.spec_from_file_location('native_prepare',Path(__file__).with_name('prepare-public-grip-expansion.py'))
prepare=importlib.util.module_from_spec(spec);spec.loader.exec_module(prepare)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ['intake','exporter','output']:p.add_argument(name,type=Path)
    a=p.parse_args();root=a.output.absolute();root.mkdir(parents=True,exist_ok=True)
    for name in ['frames','logs']:(root/name).mkdir(exist_ok=True)
    receipts=[]
    for case in sorted(json.loads(a.intake.read_text())['cases'],key=lambda c:c['id']):
        video=Path(case['path']);assert prepare.sha(video)==case['sha256'] and video.stat().st_size==case['bytes']
        frames=root/'frames'/case['id'];meta=frames/'intake.json';receipt=dict(case,framesDirectory=str(frames))
        if not meta.exists() and frames.exists():
            receipt.update(status='nativeRejected',reason='Previous incomplete native export; original failure log and pixels retained')
        else:
            if not meta.exists():
                with (root/'logs'/(case['id']+'.log')).open('w') as log:
                    process=subprocess.run([str(a.exporter.absolute()),str(video),str(frames)],stdout=log,stderr=subprocess.STDOUT)
                if process.returncode:
                    receipt.update(status='nativeRejected',reason='Native decoder or pose export rejected source',exitCode=process.returncode)
            if meta.exists():
                intake=json.loads(meta.read_text());clock=prepare.clock(video);hashes=prepare.pixel_hashes(video)
                assert intake['videoSHA256']==case['sha256'] and intake['decodedFrames']==len(clock)
                receipt.update(status='ready',recordingId=case['sha256'],decodedFrames=len(clock),samples=intake['samples'],
                    losslessYUVVerified=True,sourcePTSVerified=True,decodedFrameHashesSHA256=hashlib.sha256(json.dumps(hashes).encode()).hexdigest())
        receipts.append(receipt)
        (root/'prepared-threeway.json').write_text(json.dumps(dict(scope='Unlabelled native observations; failures retained, no replacement videos chosen',cases=receipts),indent=2)+'\n')
        print(case['id'],receipt['status'],flush=True)
    with (root/'frame-plan-input.jsonl').open('w') as out:
        for case in receipts:
            if case['status']!='ready':continue
            for line in (Path(case['framesDirectory'])/'frames.jsonl').read_text().splitlines():
                row=json.loads(line);row.update(caseId=case['id'],framesDirectory=case['framesDirectory'],originalVideoSHA256=case['sha256'],sourceGroup=case['sourceGroup'])
                out.write(json.dumps(row)+'\n')


if __name__=='__main__':main()
