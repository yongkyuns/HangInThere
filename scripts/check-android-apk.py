#!/usr/bin/env python3
"""Check the packaged production model and JNI ABIs before emulator evaluation."""
import argparse,hashlib,json,struct,zipfile
from pathlib import Path
MODEL_SHA='64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b'
ABIS=('arm64-v8a','armeabi-v7a','x86','x86_64')
HDR_LIBRARIES=('hanginthere_hdr','avcodec','avformat','avfilter','avutil','swscale')
def load_alignments(data):
    assert data[:4]==b'\x7fELF' and data[5]==1,'Expected little-endian Android ELF'
    if data[4]==2:
        offset=struct.unpack_from('<Q',data,32)[0];size,count=struct.unpack_from('<HH',data,54);alignment=48;fmt='<Q'
    else:
        assert data[4]==1
        offset=struct.unpack_from('<I',data,28)[0];size,count=struct.unpack_from('<HH',data,42);alignment=28;fmt='<I'
    return [struct.unpack_from(fmt,data,offset+i*size+alignment)[0] for i in range(count)
            if struct.unpack_from('<I',data,offset+i*size)[0]==1]
def check(apk):
    with zipfile.ZipFile(apk) as z:
        assert 'classes.dex' in z.namelist(),'No compiled production code'
        assert hashlib.sha256(z.read('assets/pose_landmarker_heavy.task')).hexdigest()==MODEL_SHA,'Wrong packaged pose model'
        native={}
        hdr={}
        for abi in ABIS:
            path=f'lib/{abi}/libmediapipe_tasks_vision_jni.so'
            assert path in z.namelist(),f'MediaPipe JNI missing for {abi}; real inference cannot run'
            assert f'lib/{abi}/libopencv_java4.so' in z.namelist(),f'OpenCV JNI missing for {abi}'
            data=z.read(path);alignments=load_alignments(data)
            assert alignments and min(alignments)>=16384,f'MediaPipe {abi} is not16KiB aligned'
            native[abi]=dict(sha256=hashlib.sha256(data).hexdigest(),loadAlignments=alignments)
            hdr[abi]={}
            for library in HDR_LIBRARIES:
                path=f'lib/{abi}/lib{library}.so'
                assert path in z.namelist(),f'HDR decoder library missing for {abi}: {library}'
                data=z.read(path);alignments=load_alignments(data)
                assert alignments and min(alignments)>=16384,f'HDR {abi}/{library} is not16KiB aligned'
                hdr[abi][library]=dict(sha256=hashlib.sha256(data).hexdigest(),loadAlignments=alignments)
        for notice in ('hdr-notice.txt','ffmpeg-LGPL-2.1.txt','zimg-WTFPL.txt'):
            assert f'assets/licenses/{notice}' in z.namelist(),f'HDR license missing: {notice}'
    return dict(apkSha256=hashlib.sha256(apk.read_bytes()).hexdigest(),modelSha256=MODEL_SHA,nativePoseLibraries=native,nativeHdrLibraries=hdr)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('apk',type=Path);p.add_argument('--output',type=Path);a=p.parse_args()
    r=check(a.apk)
    if a.output:a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(r,indent=2)+'\n')
    print('Packaged pose model, OpenCV, HDR decoder and all4 native ABIs verified (16KiB aligned).')
