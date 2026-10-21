"""Encoding profiles for test media; never used by either app's decoder.

Reproducibility applies within one FFmpeg/libx264 build. Library upgrades still
need fresh qualification; source pins and reviewed timelines do not change.
"""


def input_options(reproducible=False):
    return ['-filter_threads', '1', '-threads:v', '1', '-bitexact'] if reproducible else []


def output_options(reproducible=False):
    return ['-threads:v', '1', '-bitexact', '-x264-params',
            'cpu-independent=1:lookahead-threads=1'] if reproducible else []


def scale_flags(reproducible=False, algorithm='lanczos'):
    return algorithm + '+accurate_rnd+bitexact' if reproducible else algorithm


def provenance(reproducible=False):
    return {'profile': 'reproducible-h264-v1' if reproducible else 'host-default',
            'input_options': input_options(reproducible),
            'output_options': output_options(reproducible),
            'lanczos_flags': scale_flags(reproducible)}
