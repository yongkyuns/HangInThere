#!/usr/bin/env python3
"""Prepare authorized private recordings locally; this script never uploads media.

The caller freezes apparatus references and movement labels before inference in an
ignored local input manifest. Each recording produces one anonymous silent asset,
shared by its left/right cases. Original filenames, paths and container metadata
are excluded from the export manifest. Encryption/transport belong to the caller.
"""
import argparse
from fractions import Fraction
import hashlib
import importlib.util
import json
import math
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SPEC = importlib.util.spec_from_file_location('android_fixture_timestamps', ROOT/'scripts/prepare-android-fixtures.py')
TIMESTAMPS = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(TIMESTAMPS)
SAFE_ID = re.compile(r'recording_[a-z][a-z0-9]{0,23}\Z')
MAXIMUM_SOURCE_BYTES = 700_000_000


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(path):
    hasher = hashlib.sha256()
    with Path(path).open('rb') as stream:
        while block := stream.read(1024*1024):
            hasher.update(block)
    return hasher.hexdigest()


def finite_number(value):
    return type(value) in (int, float) and math.isfinite(value)


def image_size(value):
    require(isinstance(value, dict) and set(value) == {'width', 'height'}, 'Invalid reference image size')
    require(all(finite_number(value[k]) and value[k] > 0 for k in ('width', 'height')), 'Invalid reference image dimensions')
    return value


def reference_edge(edge, size):
    require(isinstance(edge, dict) and set(edge) == {'a', 'b'}, 'Invalid apparatus edge')
    for point in edge.values():
        require(isinstance(point, dict) and set(point) == {'x', 'y'}, 'Invalid apparatus point')
        require(all(finite_number(point[k]) for k in ('x', 'y')), 'Non-finite apparatus point')
        require(0 <= point['x'] <= size['width'] and 0 <= point['y'] <= size['height'], 'Apparatus edge outside reference image')
    require(math.hypot(edge['b']['x']-edge['a']['x'], edge['b']['y']-edge['a']['y']) >= 24, 'Apparatus edge is too short')
    return edge


def validate_manifest(value):
    require(isinstance(value, dict) and value.get('schemaVersion') == 1, 'Unsupported private input schema')
    rows = value.get('recordings')
    require(isinstance(rows, list) and 1 <= len(rows) <= 8, 'Expected one to eight explicitly authorized recordings')
    identifiers = set()
    sources = set()
    for row in rows:
        require(isinstance(row, dict), 'Invalid recording')
        identifier = row.get('id')
        require(isinstance(identifier, str) and SAFE_ID.fullmatch(identifier), 'Use an anonymous recording identifier')
        require(identifier not in identifiers, 'Duplicate recording asset identifier')
        identifiers.add(identifier)
        require(row.get('exercise') in ('pullUp', 'dip'), 'Expected a concrete exercise')
        require(type(row.get('expectedMovements')) is int and 0 <= row['expectedMovements'] <= 1000, 'Invalid frozen movement label')
        require(isinstance(row.get('videoPath'), str) and Path(row['videoPath']).is_absolute(), 'Original video path must be explicit and absolute')
        path = Path(row['videoPath']).resolve(strict=True)
        require(path.is_file() and 0 < path.stat().st_size <= MAXIMUM_SOURCE_BYTES, 'Invalid or oversized original asset')
        require(path not in sources, 'Repeated source must share one recording asset and its side references')
        sources.add(path)
        size = image_size(row.get('referenceImageSize'))
        edges = row.get('referenceEdges')
        require(isinstance(edges, dict) and set(edges) == {'left', 'right'}, 'Freeze both anatomical side references explicitly')
        for edge in edges.values():
            reference_edge(edge, size)
        if 'expectedFrames' in row:
            require(type(row['expectedFrames']) is int and row['expectedFrames'] > 0, 'Invalid original frame count pin')
        if 'sourceSha256' in row:
            require(isinstance(row['sourceSha256'], str) and re.fullmatch('[0-9a-f]{64}', row['sourceSha256']), 'Invalid original integrity pin')
    return rows


def media_probe(path):
    return json.loads(subprocess.check_output([
        'ffprobe', '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(path)
    ], text=True))


def first_video(info):
    streams = [s for s in info['streams'] if s.get('codec_type') == 'video']
    require(len(streams) == 1, 'Expected exactly one source video stream')
    return streams[0]


def upright_size(stream):
    rotations = [d['rotation'] for d in stream.get('side_data_list', []) if 'rotation' in d]
    rotation = rotations[0] if rotations else float(stream.get('tags', {}).get('rotate', 0))
    require(finite_number(rotation) and abs(rotation/90-round(rotation/90)) < 1e-8, 'Unsupported non-cardinal display transform')
    width, height = stream['width'], stream['height']
    if int(round(rotation/90)) % 2:
        width, height = height, width
    return dict(width=width, height=height), rotation


def target_size(upright, maximum_dimension):
    factor = min(1, maximum_dimension/max(upright.values()))
    result = {key: int(math.floor(value*factor/2))*2 for key, value in upright.items()}
    require(min(result.values()) >= 16, 'Target image too small for apparatus evaluation')
    return result


def rational_pts(evidence):
    base = evidence['sourceTimeBase']
    time_base = Fraction(base['numerator'], base['denominator'])
    return [tick*time_base for tick in evidence['sourcePtsTicks']]


def verify_timeline(source, derivative):
    require(len(source['sourcePtsTicks']) == len(derivative['sourcePtsTicks']), 'Transcode dropped or duplicated source frames')
    require(rational_pts(source) == rational_pts(derivative), 'Transcode changed actual rational source PTS')
    require(source['sourcePtsMicroseconds'] == derivative['sourcePtsMicroseconds'], 'Native microsecond PTS differs after transcode')


def scaled_edge(edge, reference_size, actual_size):
    return {label: dict(x=point['x']*actual_size['width']/reference_size['width'],
                        y=point['y']*actual_size['height']/reference_size['height'])
            for label, point in edge.items()}


def pixel_filter(stream, size):
    resize = f"scale={size['width']}:{size['height']}:flags=lanczos"
    if stream.get('color_transfer') in ('arib-std-b67', 'smpte2084'):
        # Decode the declared HDR transfer into linear floating point before
        # compression; merely attaching SDR tags to HLG values is incorrect.
        require(stream.get('color_primaries') == 'bt2020', 'HDR source needs explicit BT2020 primaries')
        return (resize+',setsar=1,zscale=transfer=linear:npl=100,format=gbrpf32le,'
                'tonemap=tonemap=hable:desat=0:peak=10,'
                'zscale=primaries=bt709:transfer=bt709:matrix=bt709:range=limited,format=yuv420p,'
                'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=limited')
    return (resize+':out_color_matrix=bt709,setsar=1,format=yuv420p,'
            'setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=limited')


def transcode(source, target, size, source_evidence, stream, ffmpeg='ffmpeg'):
    filters = pixel_filter(stream, size)
    if stream.get('color_transfer') in ('arib-std-b67', 'smpte2084'):
        supported = subprocess.check_output([str(ffmpeg), '-hide_banner', '-filters'], text=True)
        require(' zscale ' in supported and ' tonemap ' in supported, 'HDR preparation requires FFmpeg zscale and tonemap; use a capable local binary or Linux CI')
    # An integral multiple of the original clock avoids quantizing rational PTS.
    timescale = math.lcm(source_evidence['sourceTimeBase']['denominator'], 90_000)
    require(timescale <= 2_147_483_647, 'Source timebase exceeds MP4 track capacity')
    args = [str(ffmpeg), '-v', 'error', '-nostdin', '-copyts', '-i', str(source), '-map', '0:v:0',
            '-an', '-map_metadata', '-1', '-map_chapters', '-1',
            '-vf', filters,
            '-c:v', 'libx264', '-preset', 'medium', '-crf', '18', '-pix_fmt', 'yuv420p',
            '-fps_mode:v', 'passthrough', '-enc_time_base:v', f'1:{timescale}',
            '-video_track_timescale', str(timescale), '-metadata:s:v:0', 'rotate=0',
            '-color_primaries', 'bt709', '-color_trc', 'bt709', '-colorspace', 'bt709', '-color_range', 'tv',
            '-movflags', '+faststart', '-y', str(target)]
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)
    return timescale, filters


def reject_tracked_location(path):
    """Keep accidental private input/output under a repository's ignore boundary."""
    try:
        relative = Path(path).resolve().relative_to(ROOT)
    except ValueError:
        return
    ignored = subprocess.run(['git', 'check-ignore', '--quiet', str(relative)], cwd=ROOT,
                             stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL).returncode == 0
    require(ignored, 'Private preparation input/output inside the repository must be ignored')


def prepare(manifest_path, output, maximum_dimension=1280, ffmpeg='ffmpeg'):
    manifest_path = Path(manifest_path).resolve(strict=True)
    output = Path(output).resolve()
    reject_tracked_location(manifest_path)
    reject_tracked_location(output)
    require(type(maximum_dimension) is int and 16 <= maximum_dimension <= 1280, 'Evaluation dimension must be within 16..1280')
    require(not output.exists(), 'Output already exists; choose a fresh ignored destination')
    input_bytes = manifest_path.read_bytes()
    rows = validate_manifest(json.loads(input_bytes))
    output.parent.mkdir(parents=True, exist_ok=True)
    cases, provenance = [], []
    with tempfile.TemporaryDirectory(prefix='.private-preparation-', dir=output.parent) as temporary:
        staging = Path(temporary)/'export'
        staging.mkdir()
        for row in rows:
            source = Path(row['videoPath']).resolve(strict=True)
            original_sha = digest(source)
            if 'sourceSha256' in row:
                require(original_sha == row['sourceSha256'], 'Original asset failed integrity pin')
            source_info = media_probe(source)
            stream = first_video(source_info)
            original_size, rotation = upright_size(stream)
            require(original_size == row['referenceImageSize'], 'Frozen reference image does not match upright source orientation')
            source_evidence = TIMESTAMPS.timestamp_evidence(source)
            require(source_evidence['sourcePtsTicks'][0] == 0, 'Private evaluation must preserve a source timeline beginning at zero')
            if 'expectedFrames' in row:
                require(len(source_evidence['sourcePtsTicks']) == row['expectedFrames'], 'Original frame count failed pin')
            size = target_size(original_size, maximum_dimension)
            asset = row['id']+'.mp4'
            derivative = staging/asset
            timescale, filters = transcode(source, derivative, size, source_evidence, stream, ffmpeg)
            derivative_evidence = TIMESTAMPS.timestamp_evidence(derivative)
            verify_timeline(source_evidence, derivative_evidence)
            derivative_info = media_probe(derivative)
            video = first_video(derivative_info)
            require(video['codec_name'] == 'h264' and video['pix_fmt'] == 'yuv420p', 'Derivative is not 8-bit H264 I420-compatible')
            require(all(video.get(k) == 'bt709' for k in ('color_transfer', 'color_primaries', 'color_space')), 'Derivative is not explicitly tagged SDR BT709')
            require(not any(s.get('codec_type') == 'audio' for s in derivative_info['streams']), 'Derivative contains audio')
            actual_size, output_rotation = upright_size(video)
            require(actual_size == size and output_rotation == 0, 'Derivative orientation or dimensions changed')
            require(digest(source) == original_sha, 'Original changed while preparation was running')
            derivative_sha = digest(derivative)
            for side in ('left', 'right'):
                edge = scaled_edge(row['referenceEdges'][side], row['referenceImageSize'], actual_size)
                reference_edge(edge, actual_size)
                cases.append(dict(id=row['id']+'_'+side, asset=asset, sha256=derivative_sha,
                    frames=len(derivative_evidence['sourcePtsTicks']),
                    sourcePtsMicroseconds=derivative_evidence['sourcePtsMicroseconds'],
                    expectedMovements=row['expectedMovements'], exercise=row['exercise'], side=side,
                    referenceEdge=edge, referenceImageSize=actual_size))
            provenance.append(dict(id=row['id'], originalSha256=original_sha,
                originalBytes=source.stat().st_size, derivativeSha256=derivative_sha,
                derivativeBytes=derivative.stat().st_size, originalCodec=stream['codec_name'],
                originalPixelFormat=stream.get('pix_fmt'), originalDisplayRotation=rotation,
                originalUprightImageSize=original_size, derivativeImageSize=actual_size,
                colorTransfer=stream.get('color_transfer'), colorPrimaries=stream.get('color_primaries'),
                originalTimestampEvidence=source_evidence, derivativeTimestampEvidence=derivative_evidence,
                encoderTrackTimescale=timescale, pixelFilter=filters,
                ffmpegVersion=subprocess.check_output([str(ffmpeg), '-version'], text=True).splitlines()[0]))
        export = dict(schemaVersion=1, cases=cases)
        (staging/'manifest.json').write_text(json.dumps(export, indent=2, allow_nan=False)+'\n')
        audit = dict(schemaVersion=1, inputManifestSha256=hashlib.sha256(input_bytes).hexdigest(),
            scope='Authorized private full-source evaluation preparation. No trimming, dropped, interpolated or repeated frames. Exact rational PTS and Android microsecond PTS verified. Silent upright lossy H264 resize and 10-to-8-bit quantization may change inference pixels; no Apple/Android pixel identity claim. HDR sources explicitly decoded to linear light (100-nit reference), Hable tone mapped with a fixed normalized 1000-nit input peak, and converted to limited-range SDR BT709. This declared reproducible mapping is not claimed to match Apple automatic display tone mapping.',
            recordings=provenance)
        (staging/'local-provenance.json').write_text(json.dumps(audit, indent=2, allow_nan=False)+'\n')
        require(manifest_path.read_bytes() == input_bytes, 'Frozen manifest changed during preparation')
        staging.rename(output)
    return export


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--manifest', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--maximum-dimension', type=int, default=1280)
    parser.add_argument('--ffmpeg', type=Path, default=Path('ffmpeg'))
    args = parser.parse_args()
    result = prepare(args.manifest, args.output, args.maximum_dimension, args.ffmpeg)
    assets = {case['asset']: case['frames'] for case in result['cases']}
    print(f'Prepared {len(assets)} anonymous assets, {sum(assets.values())} source frames, {len(result["cases"])} side cases. No upload performed.')

if __name__ == '__main__':
    main()
