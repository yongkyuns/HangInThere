# HangInThere

A lean, native iPhone experiment for accurate pull-up and parallel-bar-dip tracking.

**P0 implementation:** import a local MOV/MP4, decode it sequentially, run real
Apple Vision 2D body-pose estimation, and replay the exact processed images with
an aligned skeleton. Pause, resume, restart and import replacement are supported.
Processing is on-device. There is no server, account, third-party pose dependency,
or paid Apple developer membership requirement for simulator development.

**Not implemented/qualified yet:** rep counting, form checks, live camera capture,
MediaPipe comparison, annotated exercise accuracy, and physical-iPhone performance.
The app explicitly labels this as a pose preview, not a workout validator.

## Build and run

Open `HangInThere.xcodeproj`, choose the shared **HangInThere** scheme and an iPhone
simulator, then Run. The app targets iOS 17+. CI pins Xcode 16.4 and the iOS 18.5
simulator on `macos-15`; local Xcode must also support your phone's installed OS.
No project generator, package manager, API key, or signing secret is needed.

Import through the app's **Import** button. On the simulator, put a MOV/MP4 into
Files (for example through Safari or Finder drag/drop as appropriate) and select
it using the document picker. The app does not request camera or microphone access.
The file provider must make the selected movie locally readable. Slow/unsupported
or damaged assets report errors rather than showing a fake successful result.

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
input, and stale-import suppression. GitHub Actions additionally compiles an
unsigned physical-device build; that does not install or run on an iPhone.

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
qualification. Next: reviewed pull-up/dip measurements and a focused Vision versus
MediaPipe Heavy comparison, then deterministic counting. Do not grow a framework
before those experiments establish a need.

Source-code license selection remains open. Third-party fixture media has its own
explicit license and attribution; a public repository does not relicense it.
