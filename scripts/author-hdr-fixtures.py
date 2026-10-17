#!/usr/bin/env python3
"""Optional authoring recipe. CI uses the committed, hashed Main10 panel fixtures."""
import argparse
import array
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PTS = [0, 110000, 230000, 400000, 710000, 960000]
TIMING = r'setpts=if(eq(N\,0)\,0\,if(eq(N\,1)\,110\,if(eq(N\,2)\,230\,if(eq(N\,3)\,400\,if(eq(N\,4)\,710\,960)))))'
MAPPING = ('setsar=1,zscale=transfer=linear:npl=100,format=gbrpf32le,'
           'tonemap=tonemap=hable:desat=0:peak=10,'
           'zscale=primaries=bt709:transfer=bt709:matrix=bt709:range=limited,format=yuv420p')


def version(binary):
    return subprocess.check_output([binary, '-version'], text=True).splitlines()[0]


def author(name, width, height, colors, transfer, ffmpeg, reference, ffprobe):
    work = ROOT / 'build/hdr-fixture-authoring'; work.mkdir(parents=True, exist_ok=True)
    output = ROOT / 'android/app/src/androidTest/assets/hdr'; output.mkdir(parents=True, exist_ok=True)
    values = []
    for plane in range(3):
        divisor = 1 if plane == 0 else 2
        columns, rows = width // divisor, height // divisor
        values.extend(colors[min(2, x * 3 // columns)][plane] for y in range(rows) for x in range(columns))
    samples = array.array('H', values)
    if sys.byteorder != 'little': samples.byteswap()
    raw = work / (name + '.yuv'); raw.write_bytes(samples.tobytes() * len(PTS))
    encoded = work / (name + '.mp4'); target = output / (name + '.mp4')
    trc = 'arib-std-b67' if transfer == 18 else 'smpte2084'
    codec = f'qp=4:pools=1:frame-threads=1:wpp=0:bframes=2:log-level=error:colorprim=9:transfer={transfer}:colormatrix=9'
    subprocess.run([ffmpeg, '-v', 'error', '-y', '-f', 'rawvideo', '-pixel_format', 'yuv420p10le',
                    '-video_size', f'{width}x{height}', '-framerate', '1000', '-i', str(raw), '-vf', TIMING,
                    '-fps_mode', 'passthrough', '-c:v', 'libx265', '-x265-params', codec, '-profile:v', 'main10',
                    '-pix_fmt', 'yuv420p10le', '-color_primaries', 'bt2020', '-color_trc', trc,
                    '-colorspace', 'bt2020nc', '-color_range', 'tv', '-video_track_timescale', '1000', str(encoded)], check=True)
    # Current FFmpeg ignores the old rotate metadata command during remux.
    # Write the display matrix explicitly, and verify the container independently.
    subprocess.run([ffmpeg, '-v', 'error', '-y', '-display_rotation:v:0', '90', '-i', str(encoded),
                    '-map', '0:v:0', '-c', 'copy', str(target)], check=True)
    streams = json.loads(subprocess.check_output([ffprobe, '-v', 'error', '-select_streams', 'v:0',
        '-show_entries', 'stream_side_data=rotation', '-of', 'json', str(target)], text=True))['streams']
    rotations = [entry['rotation'] for stream in streams for entry in stream.get('side_data_list', []) if 'rotation' in entry]
    assert rotations == [90], 'Authored source must carry a counterclockwise 90-degree display matrix'
    factor = min(1.0, 1280 / max(width, height))
    out_width, out_height = int(width * factor / 2) * 2, int(height * factor / 2) * 2
    data = subprocess.check_output([reference, '-v', 'error', '-noautorotate', '-i', str(target), '-vf',
                                   f'scale={out_width}:{out_height}:flags=lanczos,' + MAPPING,
                                   '-frames:v', '1', '-f', 'rawvideo', '-pix_fmt', 'yuv420p', 'pipe:1'])
    area = out_width * out_height
    positions = (20, 64, 108) if width == 128 else (200, 640, 1080)
    yuv = [[data[out_height // 2 * out_width + x],
            data[area + out_height // 4 * out_width // 2 + x // 2],
            data[area * 5 // 4 + out_height // 4 * out_width // 2 + x // 2]] for x in positions]
    target.with_suffix('.json').write_text(json.dumps(dict(
        sha256=hashlib.sha256(target.read_bytes()).hexdigest(), pts=PTS, yuv=yuv,
        sourceWidth=width, sourceHeight=height, outputWidth=out_width, outputHeight=out_height, rotation=270,
        encoderVersion=version(ffmpeg), referenceDecoderVersion=version(reference),
        scope='Authored decoder-contract panels, not movement labels or phone footage',
        sourceRecipe='scripts/author-hdr-fixtures.py'), indent=2) + '\n')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ffmpeg', default='ffmpeg', help='Encoder with libx265')
    parser.add_argument('--reference-ffmpeg', default='ffmpeg', help='Independent reference CLI with zscale')
    parser.add_argument('--ffprobe', default='ffprobe', help='Independent container rotation verification')
    args = parser.parse_args()
    neutral = [(64, 512, 512), (502, 512, 512), (940, 512, 512)]
    for name, transfer in [('hlg', 18), ('pq', 16)]:
        author(name, 128, 96, neutral, transfer, args.ffmpeg, args.reference_ffmpeg, args.ffprobe)
    author('color-hlg', 1600, 96, [(259, 498, 799), (398, 342, 277), (255, 774, 460)], 18, args.ffmpeg, args.reference_ffmpeg, args.ffprobe)
