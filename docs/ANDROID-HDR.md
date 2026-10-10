# Android HDR imports

The Android replay facade uses a small software decoder for HEVC Main10 with
explicit BT.2020 HLG or PQ metadata, including Dolby Vision profile 8's compatible
HLG base layer. Other Dolby profiles are excluded. SDR continues through the
existing MediaExtractor/MediaCodec adapter. This capability does not change the
camera, pose engine, apparatus policy or movement counter.

The reader streams a duplicated seekable AssetFileDescriptor, respects its
offset/length, selects the first video track, drains delayed decoder frames, and
preserves strictly increasing source PTS truncated to Android microseconds.
Opening a fresh reader rewinds acquisition/counting to zero. The replay owner
serializes calls and closes the reader on cancellation; cancellation is checked
before and after each native frame, inside native packet/AVIO reads and container
opening, and before publishing pixels. Decoder work uses two codec workers and
one filter worker. MOV headers supply metadata directly without probe decoding. A source
may be up to 4096 pixels on an axis and 4096×2160 in area; larger inputs fail
explicitly. No imported video or frame is cached by this adapter.

Every decoded frame must actually be 10-bit 4:2:0 with declared BT.2020
non-constant-luminance matrix, HLG/PQ transfer and limited/full range. A filter
scales the encoded pixels to at most 1280, decodes the transfer into linear float
with a 100-nit reference, applies Hable with a fixed normalized 1000-nit peak, and
converts to 8-bit limited-range SDR BT.709. Every output frame's color metadata
and PTS are verified. The existing CanonicalPixels float YUV conversion then
rotates the image once into upright, unmirrored coordinates. This declared
mapping matches the private evaluation preparation recipe; it is not claimed to
match Apple display tone mapping or the pixels of lossy SDR derivatives.

## Dependency build and redistribution

`scripts/build-android-hdr.py` builds unmodified, checksum-pinned sources:

| Library | Source | SHA256 |
| --- | --- | --- |
| FFmpeg 8.1.3 | https://ffmpeg.org/releases/ffmpeg-8.1.3.tar.xz | `7138d28c96d9d3e3af4ee3d8cad72741f8ffb40da90c1112235dea3ecd3178a3` |
| zimg 3.0.6 | https://github.com/sekrit-twc/zimg/archive/refs/tags/release-3.0.6.tar.gz | `be89390f13a5c9b2388ce0f44a5e89364a20c1c57ce46d382b1fcc3967057577` |

The FFmpeg archive's release signature was verified with the project's published
key fingerprint `FCF986EA15E6E293A5644F10B4322F04D67658D8` before pinning.
Only MOV demuxing, HEVC decoding/parsing, and the necessary color filters are
enabled. Network protocols, programs, encoders, GPL and nonfree components are
disabled. LGPLv2.1+ FFmpeg libraries remain separate shared libraries; WTFPL zimg
is linked into the filter library. The JNI wrapper uses those public APIs.

Use Python 3.12+, make, autoconf, automake, libtool and pkg-config on Linux/macOS.
Android Gradle installs NDK **28.2.13676358** and CMake **3.22.1** through the SDK;
accept their SDK licenses using sdkmanager. CMake invokes the pinned build for
each Android ABI. All four existing ABIs are preserved, and every packaged ELF
must have at least 16 KiB load alignment. Builds are keyed by recipe, ABI and NDK;
private media never enters dependency builds or their output.

For a host decoder check, run `python3 scripts/test-hdr-decoder.py`. For Android,
run the ordinary Gradle assemble/lint/unit/instrumentation commands. Sources and
logs remain under ignored `build/android-hdr`. CI uploads the **exact unmodified
source archives**, wrapper, license texts, build recipe and per-ABI provenance in
`android-hdr-source-api-*` beside the corresponding APK artifact. Preserve this
source companion when redistributing an APK. License notices are packaged in
the application and available from Settings → Open-source licenses. Replacement
of the shared libraries and reverse engineering for debugging modifications
are permitted; this repository imposes no restrictive EULA.

## Qualification

Authored HLG/PQ Main10 VFR fixtures in `android/app/src/androidTest/assets/hdr`
protect independent reference colors, source PTS, B-frame draining, repeated
open/rewind, upright rotation, in-flight cancellation, oversized-header rejection,
chromatic conversion, resizing and descriptor sub-file boundaries.
The sources and reference observations have SHA256 provenance; their authoring
recipe is `scripts/author-hdr-fixtures.py`. Reference pixels came from the FFmpeg
9.0.2 CLI, separately from the production 8.1.3 reader, with a small integer
rounding tolerance. These fixtures contain neutral/colored panels, not human footage.

Host decoder contracts are separate from API29/API35 JNI tests and the
authorized full-original phone recording evaluation. Full-source counts and
performance must be reported from that native evaluation before claiming phone
video parity. Emulator throughput does not qualify physical-device thermals,
battery consumption or sustained live-camera frame rates.
