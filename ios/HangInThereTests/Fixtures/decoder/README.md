# Synthetic compressed decoder fixture

`quadrants-h264-vfr-portrait.mp4` is an original 1,704-byte synthetic fixture,
committed only to the test bundle. It contains no people or private recording.
It complements the runtime uncompressed MOV fixtures and unchanged public human
corpus. No runtime encoder or download is required by the mechanics CI job.

The native production reader, with an explicit sentinel pose estimator, must
decode all four timestamps (0, 0.10, 0.23, 0.41 seconds), produce 96×160 pixels,
and preserve every quadrant after the counterclockwise 90-degree display matrix:
green/yellow on top, red/blue below. BT.709 primaries, transfer, matrix and limited
range are explicit. The test verifies SHA-256 from the committed
JSON and the native H.264 format description. This is decoder evidence only,
not Vision, exercise accuracy, HDR support or performance qualification.

The following recipe produced the frozen file with the FFmpeg version recorded
in the JSON. Run from this directory to reproduce into a separate directory;
encoder versions can change bytes, so replacing the fixture and integrity pin
requires reviewing the decoded pixels and timestamps together.

```sh
mkdir -p /tmp/hanginthere-decoder-fixture
ffmpeg -v error -y -f lavfi -i 'color=black:s=160x96:r=100' \
  -vf "drawbox=x=0:y=0:w=80:h=48:color=0xF00000:t=fill,drawbox=x=80:y=0:w=80:h=48:color=0x00F000:t=fill,drawbox=x=0:y=48:w=80:h=48:color=0x0000F0:t=fill,drawbox=x=80:y=48:w=80:h=48:color=0xF0F000:t=fill,scale=out_color_matrix=bt709:out_range=tv,setparams=color_primaries=bt709:color_trc=bt709:colorspace=bt709:range=limited,settb=1/100,setpts='if(eq(N,0),0,if(eq(N,1),10,if(eq(N,2),23,41)))'" \
  -frames:v 4 -fps_mode vfr -enc_time_base 1:100 -an \
  -c:v libx264 -bf 0 -crf 18 -pix_fmt yuv420p -video_track_timescale 100 -movflags +write_colr \
  /tmp/hanginthere-decoder-fixture/encoded.mp4
ffmpeg -v error -y -display_rotation 90 \
  -i /tmp/hanginthere-decoder-fixture/encoded.mp4 -map 0:v:0 -c copy \
  -video_track_timescale 100 /tmp/hanginthere-decoder-fixture/portrait.mp4
ffprobe -v error -select_streams v:0 -show_streams -show_frames \
  -show_entries 'stream=codec_name,width,height,color_range,color_space,color_transfer,color_primaries:stream_side_data=rotation:frame=best_effort_timestamp_time' \
  -of json /tmp/hanginthere-decoder-fixture/portrait.mp4
shasum -a 256 /tmp/hanginthere-decoder-fixture/portrait.mp4
```

## Native decoder diagnosis

`quadrants-h264-4x4-vfr-portrait.mp4` is a separately pinned, 1,698-byte
synthetic control. It uses the same recipe above, adding `-x264-params 8x8dct=0`
to the first encoding command. Its JSON records the separate hash and encoder
setting. The original fixture, integrity pin and production-reader assertions
are unchanged. A passing control cannot qualify the original.

The control's rotated colors, dimensions and four source PTS match the original.
Independent FFmpeg decoding of **each frame** from both files yields these
unrotated center samples in limited-range I420, in red/green/blue/yellow order:

| Quadrant | Y | Cb | Cr |
| --- | --- | --- | --- |
| Red | 59 | 103 | 232 |
| Green | 162 | 47 | 33 |
| Blue | 30 | 232 | 118 |
| Yellow | 207 | 23 | 137 |

Reproduce those reference pixels without applying the display rotation:

```sh
ffmpeg -v error -noautorotate -i portrait.mp4 -pix_fmt yuv420p \
  -fps_mode passthrough -f rawvideo reference.yuv
```

Each 160×96 I420 frame has a 15,360-byte Y plane followed by two 3,840-byte
chroma planes. Source points are `(40,24)`, `(120,24)`, `(40,72)`, `(120,72)`;
chroma coordinates are half those values.
Both complete four-frame reference streams are byte-identical: 92,160 bytes,
SHA-256 `492133e1c84e4abe2c10b3bf80a4d80b601c67ac296a652c9b88d4434a020731`.

Run `python3 scripts/diagnose-apple-decoder.py` from the repository to check both
files using direct VideoToolbox with hardware allowed and explicitly disabled.
Add `--simulator` for an isolated SDK-matched simulator. A local comparison may
explicitly select an installed runtime with
`--runtime com.apple.CoreSimulator.SimRuntime.iOS-18-6`; the report records that
runtime separately from the SDK. The runner creates and removes only its own
simulator and bounds native calls. Reports are under `build/apple-decoder/`.

The probe checks both pins, all compressed-picture submissions, reader/drain
completion, every callback's native pixel format/planes/strides and lock status,
frame count, original PTS/order and all twelve channel samples per frame
(±2 levels). It records actual hardware-property query status/value; an
unsupported optional property stays unknown. Timing-only zero-payload reader
samples are counted separately and never submitted as pictures. The probe has
no Core Image, rotation, pose inference or app fallback. It exits nonzero for
corrupt pixels even if the control passes.

Local macOS 15.5/Xcode 16.4 evidence: hardware decoding preserves both fixtures;
hardware-disabled decoding corrupts the original's green/yellow Cr values to
255 on all four frames, while the control passes. A fresh iOS 18.6 simulator
(explicitly selected with the local 18.5 SDK) reproduces those eight Cr failures
with hardware allowed or disabled; both control configurations pass. Every
picture's PTS, reader/decode/callback/drain status and dimensions pass in all
four simulator cases. The simulator hardware-use query is unsupported and is
recorded as unknown. Disabling 8×8 transforms also
changes coefficients and other coding decisions, so this narrows the trigger
without identifying a faulty decoder algorithm or establishing a workaround.
The optional `native_decoder_diagnostic` CI dispatch checks the same pair on
native Mac and simulator without changing the required production-reader gate.
