#!/usr/bin/env python3
"""Build the pinned, LGPL-only MOV/HEVC HDR reader. No prebuilt codec binaries."""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
NDK_VERSION = '28.2.13676358'
SOURCES = {
    'ffmpeg': ('8.1.3', 'https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz',
               '7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3'),
    'zimg': ('3.0.6', 'https://github.com/sekrit-twc/zimg/archive/refs/tags/release-3.0.6.tar.gz',
             'be89390f13a5c9b2388ce0f44a5e89364a20c1c57ce46d382b1fcc3967057577'),
}
ABIS = {
    'arm64-v8a': ('aarch64', 'aarch64-linux-android'),
    'armeabi-v7a': ('arm', 'armv7a-linux-androideabi'),
    'x86_64': ('x86_64', 'x86_64-linux-android'),
    'x86': ('x86', 'i686-linux-android'),
}
LIBRARIES = ('avcodec', 'avformat', 'avfilter', 'avutil', 'swscale')


def sources(output):
    directory = output / 'sources'
    directory.mkdir(parents=True, exist_ok=True)
    with (directory / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        paths = {}
        for name, (version, url, checksum) in SOURCES.items():
            archive = directory / (name + ('.tar.xz' if name == 'ffmpeg' else '.tar.gz'))
            if not archive.exists():
                temporary = archive.with_suffix('.download')
                with urllib.request.urlopen(url, timeout=60) as response, temporary.open('wb') as target:
                    shutil.copyfileobj(response, target)
                temporary.replace(archive)
            if hashlib.sha256(archive.read_bytes()).hexdigest() != checksum:
                raise RuntimeError(f'{name} source checksum mismatch')
            path = directory / (f'ffmpeg-{version}' if name == 'ffmpeg' else f'zimg-release-{version}')
            if not path.exists():
                with tarfile.open(archive) as package:
                    package.extractall(directory, filter='data')
            paths[name] = path
        return paths


def run(command, cwd, env, log):
    log.write((' '.join(map(str, command)) + '\n').encode()); log.flush()
    subprocess.run(list(map(str, command)), cwd=cwd, env=env, stdout=log, stderr=log, check=True)


def build(output, abi, ndk=None):
    src = sources(output)
    target = output / abi
    target.mkdir(parents=True, exist_ok=True)
    fingerprint = hashlib.sha256((Path(__file__).read_text() + abi + str(ndk)).encode()).hexdigest()
    with (target / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        stamp = target / 'complete.json'
        if stamp.exists() and json.loads(stamp.read_text())['fingerprint'] == fingerprint:
            return target / 'install'
        install = target / 'install'
        for path in (install, target / 'zimg', target / 'ffmpeg'):
            if path.exists(): shutil.rmtree(path)
        env = os.environ.copy()
        common = ['--prefix=' + str(install)]
        ffmpeg = []
        if not ndk and os.uname().sysname == 'Darwin':
            env['STL_LIBS'] = '-lc++'
        if ndk:
            revision = next(line.split('=')[1].strip() for line in (ndk / 'source.properties').read_text().splitlines()
                            if line.startswith('Pkg.Revision'))
            if revision != NDK_VERSION:
                raise RuntimeError(f'Expected NDK {NDK_VERSION}, found {revision}')
            arch, triple = ABIS[abi]
            host = 'darwin-x86_64' if os.uname().sysname == 'Darwin' else 'linux-x86_64'
            toolchain = ndk / 'toolchains/llvm/prebuilt' / host
            tools = toolchain / 'bin'
            env.update(CC=str(tools / (triple + '26-clang')), CXX=str(tools / (triple + '26-clang++')),
                       AR=str(tools / 'llvm-ar'), RANLIB=str(tools / 'llvm-ranlib'), STRIP=str(tools / 'llvm-strip'),
                       LDFLAGS='-Wl,-z,max-page-size=16384', CFLAGS='-fPIC', CXXFLAGS='-fPIC')
            env['STL_LIBS'] = '-lc++_shared'
            common += ['--host=' + triple.replace('armv7a', 'arm')]
            ffmpeg = ['--enable-cross-compile', '--target-os=android', '--arch=' + arch,
                      '--cc=' + env['CC'], '--cxx=' + env['CXX'], '--ar=' + env['AR'], '--ranlib=' + env['RANLIB'],
                      '--strip=' + env['STRIP'], '--sysroot=' + str(toolchain / 'sysroot'),
                      '--extra-ldflags=-Wl,-z,max-page-size=16384', '--extra-libs=-lc++_shared']
        env['PKG_CONFIG_LIBDIR'] = str(install / 'lib/pkgconfig')
        env['PKG_CONFIG_PATH'] = env['PKG_CONFIG_LIBDIR']
        try:
            with (target / 'build.log').open('wb') as log:
                # Autotools generation edits source files, so serialize that once.
                with (output / 'sources/.autogen-lock').open('w') as autogen:
                    fcntl.flock(autogen, fcntl.LOCK_EX)
                    if not (src['zimg'] / 'configure').exists():
                        run(['sh', 'autogen.sh'], src['zimg'], env, log)
                zbuild = target / 'zimg'; zbuild.mkdir(exist_ok=True)
                run([src['zimg'] / 'configure', *common, '--disable-shared', '--enable-static', '--with-pic', '--disable-testapp'], zbuild, env, log)
                run(['make', '-j2'], zbuild, env, log); run(['make', 'install'], zbuild, env, log)
                fbuild = target / 'ffmpeg'; fbuild.mkdir(exist_ok=True)
                run([src['ffmpeg'] / 'configure', '--prefix=' + str(install), *ffmpeg,
                     '--disable-everything', '--disable-autodetect', '--disable-programs', '--disable-doc',
                     '--disable-debug', '--disable-network', '--disable-avdevice', '--disable-swresample',
                     '--disable-static', '--enable-shared', '--enable-pic', '--enable-pthreads',
                     '--disable-x86asm', '--pkg-config-flags=--static', '--enable-libzimg', '--enable-decoder=hevc', '--enable-parser=hevc',
                     '--enable-demuxer=mov', '--enable-filter=buffer,buffersink,scale,setsar,zscale,format,tonemap,setparams'],
                    fbuild, env, log)
                configuration = (fbuild / 'config.h').read_text()
                for forbidden in ('CONFIG_GPL', 'CONFIG_NONFREE', 'CONFIG_VERSION3'):
                    if f'#define {forbidden} 0' not in configuration:
                        raise RuntimeError(f'Unexpected FFmpeg licensing configuration: {forbidden}')
                run(['make', '-j2'], fbuild, env, log); run(['make', 'install'], fbuild, env, log)
            if ndk:
                packaged = target / 'jni'; packaged.mkdir(exist_ok=True)
                for name in LIBRARIES:
                    shutil.copyfile(install / f'lib/lib{name}.so', packaged / f'lib{name}.so')
            stamp.write_text(json.dumps(dict(fingerprint=fingerprint, abi=abi, ndkVersion=NDK_VERSION if ndk else None,
                                            sources={name: dict(version=v, url=u, sha256=s) for name, (v, u, s) in SOURCES.items()}), indent=2) + '\n')
        except subprocess.CalledProcessError as error:
            raise RuntimeError(f'HDR dependency build failed; see {target / "build.log"}') from error
        return install


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--ndk', type=Path)
    parser.add_argument('--abi', choices=[*ABIS, 'host'], required=True)
    parser.add_argument('--output', type=Path, default=ROOT / 'build/android-hdr')
    args = parser.parse_args()
    if (args.abi == 'host') != (args.ndk is None):
        parser.error('Android builds require --ndk; host builds omit it')
    print(build(args.output.resolve(), args.abi, args.ndk.resolve() if args.ndk else None))
