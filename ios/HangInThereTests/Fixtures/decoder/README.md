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
