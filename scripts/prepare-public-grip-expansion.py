#!/usr/bin/env python3
"""Prepare local native frame exports of the public UCF pull-up inventory.

The old AVI codec is copied losslessly to H.264 for AVFoundation. Verify every
decoded YUV frame and source presentation time before exporting original-sized
native Apple 2D observations. This stage has no grip labels or model predictions.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess


def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()


def clock(path):
    data=json.loads(subprocess.check_output(["ffprobe","-v","error","-select_streams","v:0","-show_frames",
        "-show_entries","frame=best_effort_timestamp_time","-of","json",str(path)]))
    return [float(f["best_effort_timestamp_time"]) for f in data["frames"]]


def pixel_hashes(path):
    text=subprocess.check_output(["ffmpeg","-v","error","-i",str(path),"-an","-pix_fmt","yuv420p","-f","framemd5","-"],text=True)
    return [s.split(",")[-1].strip() for s in text.splitlines() if s and not s.startswith("#")]


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("intake",type=Path);parser.add_argument("exporter",type=Path);parser.add_argument("output",type=Path)
    parser.add_argument("--native-media",action="store_true",help="Input MP4s are already native-decodable; preserve their original bytes")
    args=parser.parse_args();root=args.output.absolute();root.mkdir(parents=True,exist_ok=True)
    for directory in ["decoded","frames","logs"]:(root/directory).mkdir(exist_ok=True)
    cases=json.loads(args.intake.read_text())["cases"];receipts=[]
    for case in sorted(cases,key=lambda c:c["id"]):
        video=Path(case["path"]);assert sha(video)==case["sha256"] and video.stat().st_size==case["bytes"]
        decoded=video if args.native_media else root/"decoded"/(case["id"]+".mp4")
        if not args.native_media and not decoded.exists():
            subprocess.run(["ffmpeg","-v","error","-i",str(video),"-c:v","libx264","-threads","2","-crf","0",
                "-preset","fast","-pix_fmt","yuv420p","-an","-fps_mode","passthrough",str(decoded)],check=True)
        a=clock(video);b=a if args.native_media else clock(decoded)
        assert len(a)==len(b) and all(abs(x-y)<1e-6 for x,y in zip(a,b)),"Changed source frame clock"
        hashes=pixel_hashes(video)
        if not args.native_media:assert hashes==pixel_hashes(decoded),"Lossless copy changed decoded YUV pixels"
        frames=root/"frames"/case["id"]
        if not (frames/"intake.json").exists():
            with (root/"logs"/(case["id"]+".log")).open("w") as log:
                subprocess.run([str(args.exporter.absolute()),str(decoded),str(frames)],stdout=log,stderr=subprocess.STDOUT,check=True)
        intake=json.loads((frames/"intake.json").read_text())
        assert intake["videoSHA256"]==sha(decoded) and intake["decodedFrames"]==len(a)
        receipt=dict(case,recordingId=intake["videoSHA256"],nativeDecodeCopy=str(decoded),framesDirectory=str(frames),
            decodedFrames=len(a),samples=intake["samples"],losslessYUVVerified=True,sourcePTSVerified=True,
            decodedFrameHashesSHA256=hashlib.sha256(json.dumps(hashes).encode()).hexdigest())
        receipts.append(receipt)
        (root/"prepared.json").write_text(json.dumps(dict(scope="unlabelled native frames; original and decode-copy identity retained",cases=receipts),indent=2)+"\n")
        print(case["id"],intake["samples"],"native samples; lossless pixels and clock verified",flush=True)
    with (root/"frame-plan-input.jsonl").open("w") as output:
        for case in receipts:
            for line in (Path(case["framesDirectory"])/"frames.jsonl").read_text().splitlines():
                row=json.loads(line)
                row.update(caseId=case["id"],framesDirectory=case["framesDirectory"],
                    originalVideoSHA256=case["sha256"],sourceGroup=case["sourceGroup"])
                output.write(json.dumps(row)+"\n")


if __name__=="__main__":main()
