# HangInThere

A lean, on-device iPhone app in development for pull-up and dip
counting and camera-view-specific range-of-motion analysis.

The Android client remains under qualification: native
Compose/CameraX with a framework-free Kotlin Multiplatform workout core. See
[Android architecture and CI evidence](docs/ANDROID.md) and [build instructions](android/README.md).

## Repository layout

| Directory | Responsibility |
| --- | --- |
| [`ios/`](ios/README.md) | Swift app, Xcode project, and iOS core/integration tests |
| [`android/`](android/README.md) | Native Android app, platform adapters, and Android tests |
| [`shared/`](shared/README.md) | Common workout analysis/app policy used by both apps, plus Android counting/setup and Swift parity tests |
| [`Evaluation/`](Evaluation/README.md) | Offline evaluators, reviewed fixture specifications, research, and historical evidence |
| `scripts/` | Build, fixture preparation, evaluation, and qualification commands |
| `docs/` | Product design, counting rules, architecture, and qualification limits |
| `.github/workflows/` | Platform builds, policy parity, and native evaluation pipelines |

Both apps use the Kotlin core for motion analysis, automatic readiness/set
boundaries and recovery/storage policy. iOS links the static `WorkoutCore`
framework; its established Swift counter/setup remains the policy-v8 reference.
Camera capture, pose inference and UI remain native. See
[automatic workout analysis](docs/workout/AUTOMATIC_ANALYSIS.md).
Private recordings and downloaded datasets stay in ignored `Data/` directories;
generated build results stay in ignored `build/` directories.

**Current status (2026-10-29):** build 123 is installed on the development iPhone.
This is a development baseline, not a production-qualified release. Independent
full-set accuracy and sustained physical-device qualification remain open; see
[the detection handoff](docs/workout/DETECTION_HANDOFF.md).

## What this version does

- Automatically starts and counts pull-up/chin-up and dip image movement cycles
  from posture and motion. Live grip classification, rail detection and crossed
  legs are optional; they do not gate the workout.
- Supports a controlled camera setup: front/rear view, approximately level at
  chest/shoulder height, one athlete reasonably close with both arms visible.
  Camera movement and unavailable observations remain explicit interruptions.
- Shows a compact count/progress overlay. Skeleton and reference geometry are
  off by default and available through Diagnostics.
- Saves silent local video from the first completed rep to the last return,
  excluding setup/release/rest outside the set. Saved video fits within
  480 × 854 (either orientation), without reducing detection input resolution.
  New clips retain aligned, tappable start/turnaround/return keyframes.
  By default, the newest 20 clips share a 200 MB budget and expire after 30 days.
  Foreground/library access removes expired clips and thumbnails while keeping
  workout history; recording failures never block counting.
- Groups history by date and exercise, with rep phase-time bars, comparable-rep
  tempo changes, optional grip/width metadata and original measurement review.
  Muscle illustrations describe the exercise; they do not measure activation.
  Tempo change is not a physiological fatigue or calorie measurement.
- Logs any named exercise with editable reps and lb/kg loads, recent-value
  prefill and a large tap counter. Logged and camera sets share workout history
  and the calendar while retaining their source. Timed tap sets record rest;
  typed results leave it unknown. Logged results are included in local backups.
- Estimates relative demand live and plots it in set analysis using observed
  phase time: pull/press/bent-arm hold ×1, lowering ×0.5, hang/support ×0.25.
  Each set starts at zero; tracking gaps receive no load or recovery credit.
  A dashed 30-second rest preview uses `D(t) = D(0) × exp(-t / 60)`.
  Weights and recovery are prototype assumptions, not personalized muscle
  capacity, fatigue resistance or calorie measurements. Partial/old phase
  histories are marked or left without an estimate. No model training is used.
- Optional joint bands use the same phase-load colors for pull-ups, dips and
  push-ups: cool for support, amber for lowering, warm for pull/press or bent-arm
  holds. Unknown phases stay neutral; the vertical gauge still shows rep depth.
- Follows the system light/dark appearance with background-free exercise artwork.
  Imported-video review remains local and retains the pixels used for analysis.
- Offers optional, one-screen coaching setup, an editable Today workout and
  movable practice days. Recent comparable sets and one-tap effort feedback
  shape suggestions; a local rest timer continues after closing feedback.
  See [coaching rules and limits](docs/workout/COACHING.md).

Only observed completed returns enter completed-rep analytics. User-reported
corrections and practice attempts remain separate. Counting does not establish
chin clearance, dip depth, anatomical lockout or coaching/form quality.

The native iOS and Android clients own capture, inference and UI. Shared Kotlin
owns workout policy and versioned storage contracts; platform pose predictions
and physical performance require separate qualification. No recording is uploaded
by the app. See [product experience](docs/PRODUCT_UX.md),
[shared architecture](shared/README.md) and [evaluation tools](Evaluation/README.md).

## Open the app

From a checkout containing the iOS client:

```sh
open ios/HangInThere.xcodeproj
```

Choose the shared **HangInThere** scheme and an iPhone simulator, then Run.
The app has a provisional iOS 17 deployment target and Swift 6 language mode.
Use Xcode 16 or newer; the current workflow selects Xcode 26.3 explicitly. The project
requires JDK 17 available to Xcode (`JAVA_HOME` for command-line builds). Its build
phase uses the checked-in Gradle wrapper to compile/link the shared framework;
the first build downloads pinned dependencies and needs network access. No
Android SDK is needed for the iOS build. Test-fixture preparation is not needed
to build/run the app and import your own video.

For a physical phone, use a local Xcode version that supports its installed iOS,
select your Personal Team under Signing & Capabilities, and use your own unique
bundle identifier. There is no TestFlight pipeline. Do not commit your team or
signing credentials. See [the local-device checklist](docs/POC.md#10-local-iphone-verification-with-a-free-account)
and its Apple references for account and provisioning restrictions.

## Tests

Run the production Swift core/transport tests on macOS with Swift 6 and JDK 17.
The script builds the shared framework and uses a disposable test harness. Shared
JVM contracts also run on other hosts through Gradle (after generating the Swift
parity oracles on macOS).

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

Continue independently labelled full-set qualification for both exercises and
front/rear views, then sustained physical-device recording/performance tests.
Keep qualification failures and unsupported camera views explicit; source merges
and passing contract tests do not qualify a release.

## Data and licensing

Private recordings and learned research checkpoints stay local. The public
MediaPipe model bundles in `models/grip/` are checksum-pinned with their licence
and provenance. The test-preparation
manifest records its public source, rights basis, and credit; generated media are
ignored and are not app resources. No source-code licence has been selected.
Public access does not replace checking media/model permissions, and smoke
fixtures are not independent anatomical or exercise-form ground truth.
