#!/usr/bin/env python3
"""Compile and test the production decoder against pinned libraries on the host."""
import importlib.util
import os
import json
import hashlib
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]
for name in ('hlg', 'pq', 'color-hlg'):
    fixture = ROOT / f'android/app/src/androidTest/assets/hdr/{name}.mp4'
    assert hashlib.sha256(fixture.read_bytes()).hexdigest() == json.loads(fixture.with_suffix('.json').read_text())['sha256'], 'Authored HDR source checksum mismatch'
spec = importlib.util.spec_from_file_location('hdr_build', ROOT / 'scripts/build-android-hdr.py')
builder = importlib.util.module_from_spec(spec); spec.loader.exec_module(builder)
install = builder.build(ROOT / 'build/android-hdr', 'host')
env = os.environ.copy(); env['PKG_CONFIG_PATH'] = str(install / 'lib/pkgconfig')
flags = shlex.split(subprocess.check_output(['pkg-config', '--cflags', '--libs', 'libavcodec', 'libavformat', 'libavfilter', 'libavutil'], env=env, text=True))
output = ROOT / 'build/android-hdr/host/HdrDecoderTests'
subprocess.run([env.get('CXX', 'c++'), '-std=c++17', '-Wall', '-Wextra', '-Werror',
                '-I' + str(ROOT / 'android/app/src/main/cpp'),
                str(ROOT / 'android/app/src/main/cpp/HdrDecoder.cpp'),
                str(ROOT / 'android/app/src/test/cpp/HdrDecoderTests.cpp'), *flags,
                '-Wl,-rpath,' + str(install / 'lib'), '-o', str(output)], env=env, check=True)
subprocess.run([str(output), str(ROOT / 'android/app/src/androidTest/assets/hdr')], check=True)
