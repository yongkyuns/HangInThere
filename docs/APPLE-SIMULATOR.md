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

[CI at `6857c48`](https://github.com/yongkyuns/HangInThere/actions/runs/38829240479)
passes all 317 native Mac tests: 306 Swift Testing cases and 11 XCTest cases.
Simulator mechanics passes 13 XCTest cases and 299 of 300 Swift Testing
methods, including the new interrupted-progress regression. Its single failed
method retains the eight original pinned H264 color assertions. All eight
simulator Vision methods still fail; logs retain Code 9 and the missing model
weights. The [source-bound CI record](../Evaluation/results/public-workout-progress-ci-6857c48.validation.json)
verifies that the actual CI merge checkout has the same entire Git tree as this
source. These results preserve the existing failures and do not qualify a repair
for intermittent replay latency.

[CI after the fixture repair at `2bf303c`](https://github.com/yongkyuns/HangInThere/actions/runs/38161936276)
passes all 282 Mac tests and the unsigned Release iPhone build. Simulator
mechanics passes 281/282 methods; its only failure is the original eight H264
color assertions. All eight simulator Vision methods still fail model startup.
The first native read in the automatic replay takes 15.28 seconds and its
first render takes 3.85 seconds, approaching the unchanged 20-second opening
deadline. This successful attempt does not close the intermittent latency gate.

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

Additional local decoder trials preserve the original/control bytes and all
four native frames, PTS and color checks. Setting VideoToolbox thread count to
one, disabling real-time priority, or combining non-real-time priority with
maximum power efficiency is accepted by both Mac and simulator sessions but
does not change the result: each original clip still has eight Cr failures,
while every control passes. No production setting is changed on this evidence.

The newer Swift `DetectHumanBodyPoseRequest` revision 2 also fails on the local
Mac with Vision Code 12, "No available compute device for VNComputeStageMain,"
with hands enabled and disabled. Its fresh simulator trials time out during
boot, so there is no simulator inference result for that request. The revision
1 production backend remains unchanged.

## Independent reports and interpretation

A [December 2021 first-hand report](https://stackoverflow.com/questions/70473725/ios-vision-framework-unable-to-setup-request-in-vndetecthumanbodyposerequest)
contains the same missing `cnn_human_pose.espresso.weights`, status `-2`, and
Vision Code 9 while analyzing a still image. Its later community answer recommends
a physical device; the proposed hardware explanation has no Apple confirmation.
The [Kinetix developers](https://devpost.com/software/kinetix-1mfxtc) independently
report body-pose Code 9 in Simulator while the same request succeeds on macOS.
They precompute Mac pose sidecars for simulator demonstrations and describe a
live-inference path for physical hardware. Their future-work list still includes
live camera capture when hardware ships, so this account does not establish
completed physical-device testing. Sidecars can support UI development, but
cannot qualify the iOS inference path.

These accounts corroborate our standalone generated-image reproduction and
historical CI logs. They support a simulator model-loading problem, without
establishing whether the missing assets reflect an intended limitation or a
runtime defect. No verified workaround or Apple acknowledgment was found in
this research. In particular, a missing Neural Engine has not been established
as the cause. Apple's [human-action sample](https://developer.apple.com/documentation/createml/detecting-human-actions-in-a-live-video-feed)
requires a physical device because it uses a camera; that camera restriction
does not establish a general support contract for still-image pose inference.
Retain native Mac inference, simulator mechanics and physical iOS inference as
separate evidence, preserving the simulator failure status.

## Capture interpretation

The opt-in replay profiler stops the sampler when the selected test's explicit
completion marker is delivered, including failure cleanup. If entry and
completion arrive in the same log poll, it refuses to attach to the next test.
The full mechanics suite continues independently with its original exit status.
Stage and test-boundary timestamps are recorded at their source; sampler launch
and stop requests have separate observer timestamps. Log delivery and sampler
startup can still lag. Compare those timestamps before attributing aggregate
stacks to a replay stage; a sample report's header is not proof of capture start
or exact scope. This diagnostic change does not repair a native wait.

## Physical-device and rendering evidence

At source `69ceaa4`, the full unfiltered Debug scheme on an iPhone 14 Pro with
iOS 26.6.2, Xcode 16.4 and SDK 18.5 passes **320/320 tests**, with zero failures,
skips or expected failures: 307 Swift Testing cases and 13 XCTest cases. All
eight real-Vision smoke/corpus cases and the original pinned H264 color test
pass, along with automatic pull-up/dip replay, identity clock boundaries,
camera-selection policy, grip SDK and storage integration. The
[source-bound validation record](../Evaluation/results/iphone-integration-69ceaa4.validation.json)
binds the source trees, binaries and local result/log hashes. Three initial
attempts stopped before test execution: missing provisioning, a bootstrap exit
after locked-device preflight, and a device trust/security refusal. They remain
recorded separately. After the user unlocked the phone and completed developer
trust, the already-built products passed the full suite. The bootstrap exit's
cause is not independently established.

This is physical-device fixture/integration evidence. Sustained live-camera,
sensor timing, battery/thermal behavior, independent exercise accuracy and
physical Android qualification remain required. The simulator failures and
intermittent replay-latency qualification remain unresolved.

At source `3de67b2`, an iPhone 14 Pro on iOS 26.6.2 built with Xcode 16.4/SDK
18.5 passes all 290 tests with no skips in a full followup. Real revision 1
Vision smoke/corpus, pinned H264 VFR/portrait colors and both automatic dip
replays pass. The first full run passed 288/290 with two runner exits reported
as code 0; both methods then passed alone and in the full rerun. The initial
exit cause remains unknown. These are native fixture engineering checks,
not live camera/sensor, sustained throughput, thermal/battery, physical Android
or strict-form accuracy qualification.

The default and temporary Mac copy requesting software rendering each pass
all 282 tests. Neither reproduces the hosted stall, and those concurrent functional
trials do not qualify a speed comparison or a production repair.

The optional CI `replay_render_comparison` dispatch runs each renderer on a
fresh SDK-matched simulator and committed source copy. Only the software
copy changes the one Core Image context option; source hashes verify that all
tests and fixtures remain unchanged. The full mechanics selection, original
color failures and replay deadlines stay active. Native failures propagate to
the job, even when the other variant succeeds. Sampling is disabled in both
trials. Apple documents that [`useSoftwareRenderer`](https://developer.apple.com/documentation/coreimage/cicontextoption/usesoftwarerenderer)
has no effect on platforms without OpenCL. The trial records the requested
option; it does not verify which renderer actually executes. Separate hosted
runs can expose associated failures but their timings alone do not establish
a cause or device performance parity.

[Hosted trials at `42a2196`](https://github.com/yongkyuns/HangInThere/actions/runs/38195510298)
each run all 282 mechanics tests and pass 281, retaining the same eight H264
color assertions. Both replay opening tests and unsigned Release builds pass;
neither trial reproduces the intermittent stall. All 82 copied source hashes
match the committed baseline, with only the requested option changed in the
software variant. These passing replay attempts do not qualify a repair.

A separate 64×64 synthetic context probe on local iOS 18.6/SDK 18.5
reports a Metal context and Metal program graph with either setting. The
equivalent Mac probe reports Metal for the default and CPU for the software
request. This rules out the flag as a CPU workaround on that simulator; it
does not verify the hosted runtime or the renderer used in the full suite.

## Reproduce

Run `./scripts/test-apple-host.sh` for the Mac suite and
`./scripts/test-ios.sh mechanics` or `./scripts/test-ios.sh vision` for the
independent simulator partitions. The iOS runner requires a runtime matching
the selected SDK; `./scripts/test-ios.sh` runs all tests and the unsigned Release
device build. New fixture regressions are included automatically in both native
CI suites. The original Vision checks and replay deadlines are unchanged.

Run `python3 scripts/diagnose-replay-render.py --renderer automatic` or
`--renderer software` for the isolated full-mechanics trial. `--prepare-only`
verifies the committed source copy without claiming native execution. The
driver copies only Git-tracked iOS files and test runners, preserves results,
and cleans up its own simulator on completion, failure or cancellation.

For the direct decoder comparison, see the
[strict native probe instructions](../ios/HangInThereTests/Fixtures/decoder/README.md).
