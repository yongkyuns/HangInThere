# HangInThere

A lean, native iPhone experiment for accurate pull-up and parallel-bar-dip tracking.

**P0 implementation:** import a local MOV/MP4, decode it sequentially, run real
Apple Vision 2D body-pose estimation, and replay the exact processed images with
an aligned skeleton. Pause, resume, restart and import replacement are supported.
Processing is on-device. There is no server, account or third-party pose dependency.

**Qualification in progress:** Xcode 16.4 / iOS 18.5 simulator compilation succeeded,
but actual Vision execution reported missing `cnn_human_pose.espresso.weights`.
The real-model tests remain strict. CI now checks Xcode 26.3 / iOS 26.2 and also
runs the exact decoder/estimator source on native macOS to isolate runtime support.
See PR #1 and [implementation evidence](docs/P0.md) for measured outcomes. A native
Mac pass is not an iPhone or simulator pass.

**Not implemented/qualified yet:** rep counting, form checks, live camera capture,
MediaPipe comparison, annotated exercise accuracy, and physical-iPhone performance.
The app explicitly labels this as a pose preview, not a workout validator.

## Build and run

Open `HangInThere.xcodeproj`, choose the shared **HangInThere** scheme and an iPhone
destination, then Run. The app targets iOS 17+. CI's exact Xcode/runtime pair is
pinned in `.github/workflows/ci.yml`; local Xcode must support your phone's OS.
No project generator, package manager, API key, or signing secret is needed for
simulator compilation. A simulator whose Vision model is absent reports an error;
there is no fake-pose fallback. Local phone execution remains to be verified.

Import through **Import** and select a locally readable MOV/MP4 from Files.
The app does not request camera or microphone access. File-provider/decoder
failures report errors rather than showing a fake successful result.

Playback is **analysis-paced**, not a real-time FPS benchmark. Every decoded frame
is processed in order, with its source presentation timestamp. Inference may slow
playback. The overlay and preview use the same image; there is no independent
AVPlayer clock. More than one detected person withholds the overlay in P0.

## Tests

```sh
python3 scripts/prepare-fixtures.py
bash scripts/ci.sh
```

The preparation step downloads one specifically attributed CC BY 3.0 pull-up clip
into the ignored test-fixture directory. See
[fixture provenance](HangInThereTests/Fixtures/README.md). Tests themselves are
network-free and fail when the real fixture is missing. This clip is a smoke
fixture, **not** ground truth for joint accuracy or valid repetitions.

Tests cover coordinate origin and aspect-fit mapping, image-plane geometry,
eight video-transform conventions, timestamp rejection/reset, actual Vision
landmarks from video, comparison with source timestamps, reader restart, bad
input, stale-import suppression and real-frame UI-model playback.

CI separately compiles an unsigned physical-device build and a small native Mac
check using the exact app decoder/estimator files. Neither replaces the simulator
integration gate or physical iPhone qualification. Logs retain these distinctions.

## Later: install on your own iPhone

In local Xcode, sign in with your Apple account, select your Personal Team under
Signing & Capabilities, set a unique bundle identifier, pair your phone, enable
Developer Mode when prompted, and Run. Keep team/signing changes local. Apple's
free Personal Team provisioning expires after seven days. A compatible Mac/Xcode
and physical phone are still needed for live camera/performance verification.

## Design and next steps

[POC plan](docs/POC.md) defines the capture profile, architecture, repetition
semantics, model comparison, acceptance targets, privacy and data requirements.
[Implementation evidence](docs/P0.md) distinguishes code, checks and remaining
qualification. Next: finish the real-model execution gate, then reviewed pull-up
and dip measurements, a focused model comparison, and deterministic counting.

Source-code license selection remains open. Third-party fixture media has its own
explicit license and attribution; a public repository does not relicense it.
