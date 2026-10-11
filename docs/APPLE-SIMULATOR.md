# Apple simulator qualification

The simulator gate remains unresolved. Keep native Mac inference, simulator
mechanics, simulator inference and physical-device qualification separate.
Successful reruns alone do not establish a repair for intermittent stalls.

## Failure paths and repair scope

| Failure | Evidence | Status |
| --- | --- | --- |
| Synthetic fixture authoring | `AVAssetWriter` startup/finalization can time out before the app reader runs | Test fixtures now write raw BGRA QuickTime containers directly; actual native decoding remains under test |
| Body-pose model initialization | Vision Code 9 reports missing `cnn_human_pose.espresso.weights` on tested runtimes | Unresolved; failures remain fatal |
| Compressed decoding | The immutable H264 fixture has incorrect green/yellow chroma in native VideoToolbox output, before Core Image rendering | Unresolved; original fixture and color assertions remain intact |
| Replay latency | Hosted native reading or rendering has exceeded the existing replay deadlines | Unresolved; removing fixture authoring does not repair a blocked production reader/render call |

Raw fixture generation runs off the presentation actor with a ten-second budget
including queue time, cooperative cancellation, owned-file cleanup and limits of
512 frames, 4 MiB per frame and 64 MiB of samples. It preserves exact rational PTS,
the track transform and every original quadrant pixel. A composition-time offset
preserves a delayed first PTS; an initial empty edit would cause AVAssetReader to
synthesize a black sample. Tests cover mixed timebases, delayed PTS, deterministic
bytes, decoded frame coverage, invalid inputs, size rejection and cancellation.
The separately pinned compressed fixture still exercises native H264 decoding.
Locally, all 282 Mac tests pass through the production reader and real Vision;
an independent FFmpeg decode also matches every generated pixel and exact PTS
for landscape, portrait, mixed-timebase and delayed-start clips. These checks
do not qualify simulator inference or repair production replay latency.

## Runtime evidence

[Baseline CI at `d627f2a`](https://github.com/yongkyuns/HangInThere/actions/runs/38160017838)
predates the fixture repair: 277/277 Mac tests passed, 276/277 simulator mechanics
methods passed with only the eight H264 color assertions failing, and all eight
simulator Vision methods failed to load the model. Previous fixture timeouts and
native replay stalls remain relevant despite that passing replay attempt.

Upgrading has already been tested: the failures reproduced under Xcode 26.3,
26.6 and [27.0](https://github.com/yongkyuns/HangInThere/actions/runs/38134435972).
A fresh local iOS 18.6 simulator with the 18.5 SDK also fails the default request
and attempts to select CPU or GPU using Vision's supported compute-device API.
On that simulator, compute-stage discovery itself cannot load the model, so
this is not evidence of successful CPU/GPU execution. The equivalent Mac
requests detect the pinned human image. No model assets are copied between OSes
and no backend is substituted to make the simulator check pass.

## Reproduce

Run `./scripts/test-apple-host.sh` for the Mac suite and
`./scripts/test-ios.sh mechanics` or `./scripts/test-ios.sh vision` for the
independent simulator partitions. The iOS runner requires a runtime matching
the selected SDK; `./scripts/test-ios.sh` runs all tests and the unsigned Release
device build. New fixture regressions are included automatically in both native
CI suites. The original Vision checks and replay deadlines are unchanged.

For the direct decoder comparison, see the
[strict native probe instructions](../ios/HangInThereTests/Fixtures/decoder/README.md).
