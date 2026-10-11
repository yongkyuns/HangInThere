# HangInThere

A lean, on-device iPhone app in development for pull-up and dip
counting and camera-view-specific range-of-motion analysis.

An Android client is under qualification on `codex/android-parity`: native
Compose/CameraX with a framework-free Kotlin Multiplatform workout core. See
[Android architecture and CI evidence](docs/ANDROID.md) and [build instructions](android/README.md).

## Repository layout

| Directory | Responsibility |
| --- | --- |
| [`ios/`](ios/README.md) | Swift app, Xcode project, and iOS core/integration tests |
| [`android/`](android/README.md) | Native Android app, platform adapters, and Android tests |
| [`shared/`](shared/README.md) | Platform-independent Kotlin workout policies used by Android, with Swift parity tests |
| [`Evaluation/`](Evaluation/README.md) | Offline evaluators, reviewed fixture specifications, research, and historical evidence |
| `scripts/` | Build, fixture preparation, evaluation, and qualification commands |
| `docs/` | Product design, counting rules, architecture, and qualification limits |
| `.github/workflows/` | Platform builds, policy parity, and native evaluation pipelines |

iOS currently uses its own Swift workout core; it does not link the Kotlin
framework. Camera capture, pose inference, and UI remain platform-specific.
Private recordings and downloaded datasets stay in ignored `Data/` directories;
generated build results stay in ignored `build/` directories.

**P0: video import/replay and real Apple Vision integration.** The source and
GitHub qualification workflow are published in [PR #2](https://github.com/yongkyuns/HangInThere/pull/2).
Use that PR's exact-head checks and [the evidence record](docs/P0_STATUS.md) for
build/test status. A source change or passing core test does not establish that
the iOS app runs, that exercise tracking is accurate, or that a phone keeps up.

## What this version does

Import a local MP4 or MOV from Files, inspect the first processed frame, then
play, pause, restart, or close the video. The preview displays the **same oriented
image that Vision analyzed**, with separate skeleton overlays, landmark counts,
source timestamps, and processing-time diagnostics. The measurement
extension adds left/right **image-plane elbow estimates** with explicit reasons
when an arm cannot be measured. These are raw per-frame diagnostics, not checked
reps or form scores. Inference may slow replay;
frames are processed sequentially instead of silently skipped. Audio is not played.

The replay screen now adds **bar-relative timestamp-based movement counting** for
pull-ups and dips on parallel rails or a shared straight bar. Pull-up setup finds
a visible gripping edge near both raised hands and locks it after at least four consistent observations over
0.9 seconds. Live workouts default to **Auto** exercise selection: a stable
starting hang identifies pull-ups and top support identifies dips, with actual
bar/rail contours required before the countdown. The header shows the detected
exercise; it remains fixed until the set ends and is detected again after
**Next set**. Explicit exercise selection is available in settings.
Both live exercises default to **Hands-free start**: hang with extended
arms for pull-ups, or hold top support with steady hands for dips, through the
spoken **3, 2, 1, go** countdown. Crossing the legs is optional. Counting starts
automatically on a fresh frame; lost grip, unstable setup, or missing frames cancel
the countdown. Disable the toggle to use **Start set** manually. Video review automatically
rewinds and resumes so earlier movements are counted too. Dip setup observes
separate rails beside both hands or a paired straight bar shared by both grips,
then uses the same countdown. Manual setup remains available for unclear views.
Pull-ups use both measurable arms,
each with its own extended-hang baseline; one arm can continue when the other is
obscured. Both available arms must meet the endpoint gates. Dips retain an
anatomical arm selector for the confirmed grip, with opposite-arm evidence checked
when it has a usable starting baseline. Dip lowering follows a frozen
grip-to-shoulder direction rather than the rail's silhouette direction; the count
requires a return near the acquired top with arm recovery and retained grip.
The counter combines the fixed apparatus reference with Apple Vision body landmarks.
Missing bar setup cannot count. Partial/interrupted outcomes and explicit **Form unverified**
status remain; chin clearance and strict dip depth are not yet acceptance criteria.
Live video and recorded review show a compact **rep progress meter** at the video
edge. Pull-ups follow both arms' angle and shoulder-travel evidence, with a separate
reset meter for returning to the hang. Dips fill the first half through lowering
and the bottom hold, then the second half through returning to top support and
holding it. Both meters reserve completion for an actual count and turn green
only then; missing tracking clears progress. They describe the counting criteria,
not strict form.
See [the counter policy](docs/COUNTING.md) and [bar setup](docs/BAR_SETUP.md).

Live Workout is a **full-screen video canvas** with rep count, progress, brief
setup cues, and start/finish controls over the feed. The settings button opens
Workout options, detailed results, placement help, and Diagnostics in a separate
panel. Opening it pauses the hands-free countdown until the panel closes.
A **camera-switch button inside the preview** toggles rear/front cameras before a
set. Changing
cameras clears framing and the fixed bar reference, so reacquire the bar before
starting. Camera selection is locked during a set or debug recording. The front
preview is unmirrored, matching analysis and recorded debug video.

The iOS app uses SwiftUI, AVFoundation, Core Image, and Vision. There are no third-party
runtime packages, backend services, accounts, model downloads, or analytics.
Imported files are copied into app-local temporary storage and removed on close
or replacement; recordings are never uploaded by the app.

## Open the app

From a checkout containing the iOS client:

```sh
open ios/HangInThere.xcodeproj
```

Choose the shared **HangInThere** scheme and an iPhone simulator, then Run.
The app has a provisional iOS 17 deployment target and Swift 6 language mode.
Use Xcode 16 or newer; the current workflow selects Xcode 26.3 explicitly. The project
has no code-generation or dependency-install step. Test-fixture preparation is
not needed to build/run the app and import your own video.

For a physical phone, use a local Xcode version that supports its installed iOS,
select your Personal Team under Signing & Capabilities, and use your own unique
bundle identifier. There is no TestFlight pipeline. Do not commit your team or
signing credentials. See [the local-device checklist](docs/POC.md#10-local-iphone-verification-with-a-free-account)
and its Apple references for account and provisioning restrictions.

## Tests

Run the exact framework-free application sources and their Swift Testing tests
on Linux or macOS with Swift 6. A disposable harness is created outside the repo;
there is no second production package.

```sh
./scripts/test-core.sh
```

The iOS mechanics tests exercise actual AVFoundation decoding, variable
timestamps, portrait orientation, decoder replacement/recovery, and controller
lifecycle with an explicitly injected, test-only pose estimator. They also assert
that inference errors propagate instead of becoming successful empty frames.
Runtime synthetic fixtures directly write bounded, deterministic QuickTime
containers with uncompressed BGRA samples, avoiding native writer startup and
finalization in test preparation. Cancellation and a ten-second deadline cover
queued generation. A small committed H.264/BT.709 fixture separately checks compressed decoding, rotation, every
quadrant's color and variable timestamps without requiring a runtime encoder.
Production reader/controller defaults remain real Apple Vision; there is no
runtime fallback. The separate `VisionSmokeTests` use the production defaults
for real-human still/video inference, negative video, and controller reimport.
Prepare their pinned public-source smoke media first:

```sh
# macOS; test preparation only, not an app dependency
brew install ffmpeg
python3 scripts/prepare-fixtures.py
./scripts/test-ios.sh                 # device build + ALL simulator tests
# Independent diagnostics; mechanics success does not qualify real Vision:
./scripts/test-ios.sh mechanics       # device build + replay/core tests; no model media
./scripts/test-ios.sh vision          # actual simulator Vision, failures remain fatal
./scripts/test-apple-host.sh          # native Mac tests, not iPhone evidence
```

Preparation verifies the original source's digest and size, derives a small
video and still, and records exact timestamps, derivative checksums, and encoder
provenance. See [fixture provenance and limits](ios/HangInThereTests/Fixtures/README.md).
Missing media, integrity failures, missing expected body landmarks, and backend
errors are failures, not skipped checks or successful accuracy reports.
This is a backend smoke check, **not** a pull-up/dip accuracy set.

The [P0 GitHub workflow](.github/workflows/ci.yml) reports three independent jobs:
native Mac integration, iOS simulator mechanics (including the unsigned Release
device build), and actual iOS simulator Vision. A failing job does not cancel
the others. **All are required for the declared P0 qualification:** a green
mechanics or native-Mac job does not resolve a red simulator-Vision job.
See [simulator blockers and repair scope](docs/APPLE-SIMULATOR.md) for fixture,
model, compressed-decoder and replay-latency evidence.
The runner rejects an empty test selection and keeps separate result bundles.
It uses a pinned Xcode, read-only repository permissions and no Apple credentials. Retained artifacts contain
results/provenance and a separately named, approved smoke clip/still for review;
private app imports are never collected. Unsigned compilation does not produce
an installable phone app. Normal local signing is not disabled in the project.

The [dataset-evaluation workflow](.github/workflows/evaluation.yml) also has an
independent Ubuntu host-tools job on every pull request and push to `main`. It
runs all script, evaluator/importer, and research-tool contract tests with pinned
Python dependencies, plus shell syntax checks. These tests use synthetic inputs
and do not depend on downloading footage or initializing a pose model. Native
pipeline and accuracy checks remain separate jobs; their failures remain visible.

The [Android workflow](.github/workflows/android.yml) runs shared-policy tests,
Swift/Kotlin parity, an iOS framework build, Android unit tests and lint, and
native API29/API35 instrumentation with counting, setup, UI, and pipeline-budget
checks. [Private recording evaluation](.github/workflows/android-private.yml)
is explicitly dispatched using temporary encrypted inputs. Physical-camera,
thermal, and battery qualification still require devices.

## Real-video diversity qualification

The Apple Vision qualification path is no longer limited to one four-second clip.
A pinned test-only corpus covers standard indoor/outdoor pull-ups, rear/oblique and
portrait geometry, multiple people, one-arm movement, nonstandard tree-branch
apparatus, large swing/inversion, foliage/high-contrast background, and blur.

Two views carry reviewed movement-count expectations; the harder clips use explicit
tracking/stress tiers rather than invented rep-validity labels. Run:

```sh
python3 scripts/prepare-fixtures.py
python3 scripts/prepare-video-corpus.py
./scripts/test-apple-host.sh
```

See [fixture provenance and corpus scope](ios/HangInThereTests/Fixtures/README.md).

## Physical-device qualification reports

Live Workout can export a content-free JSON engineering report for physical-iPhone
runtime qualification. Analyze one or more exported reports locally with:

```sh
python3 scripts/analyze_device_qualification.py report.json
python3 scripts/analyze_device_qualification.py \
  --profile stationary stationary-*.json
python3 scripts/analyze_device_qualification.py \
  --profile thermal thermal-soak.json
```

The analyzer uses only the Python standard library. It validates the report schema
and privacy boundary, reports stationary threshold usage/headroom, and compares
early-vs-late runtime/thermal behavior. It does not auto-tune thresholds or emit an
automatic release verdict. See [the physical-device protocol](docs/DEVICE_QUALIFICATION.md).

## Scope and next steps

Read [the POC implementation and validation plan](docs/POC.md). First clear the
real build/backend/video gates. Continue controlled fixed-camera pull-up **and dip** qualification with bar references
frozen before counting, then add chin/depth endpoint evidence, live capture, and
sustained physical-iPhone performance qualification. A skeleton alone does not establish chin-over-bar clearance
or accurate 3D joint angles. Keep one app and small components, not services or a
cross-platform architecture.

## Data and licensing

No workout recordings or model weights are committed. The test-preparation
manifest records its public source, rights basis, and credit; generated media are
ignored and are not app resources. No source-code licence has been selected.
Public access does not replace checking media/model permissions, and smoke
fixtures are not independent anatomical or exercise-form ground truth.
