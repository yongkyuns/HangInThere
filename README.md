# HangInThere

A lean, on-device iPhone app in development for pull-up and parallel-bar dip
counting and camera-view-specific range-of-motion analysis.

**P0 implementation source is present; iOS execution is not yet verified.** This
change adds video import/replay and a real Apple Vision pose-estimation path. The
framework-free tests have run locally. Xcode compilation, simulator integration
tests, real-media smoke tests, and physical-iPhone qualification have **not** run.
See [the exact evidence and remaining gates](docs/P0_STATUS.md).

## What this version does

Import a local MP4 or MOV from Files, inspect the first processed frame, then
play, pause, restart, or close the video. The preview displays the **same oriented
image that Vision analyzed**, with separate skeleton overlays, landmark counts,
source timestamps, and processing-time diagnostics. Inference may slow replay;
frames are processed sequentially instead of silently skipped. Audio is not played.

There is **no rep counting, form verdict, camera capture, or qualified accuracy
claim yet**. These are subsequent milestones, not hidden behind placeholder UI.

The app uses SwiftUI, AVFoundation, Core Image, and Vision. There are no third-party
runtime packages, backend services, accounts, model downloads, or analytics.
Imported files are copied into app-local temporary storage and removed on close
or replacement; recordings are never uploaded by the app.

## Open the app

```sh
git clone https://github.com/yongkyuns/HangInThere.git
cd HangInThere
open HangInThere.xcodeproj
```

Choose the shared **HangInThere** scheme and an iPhone simulator, then Run.
The app has a provisional iOS 17 deployment target and Swift 6 language mode.
Use Xcode 16 or newer; the workflow selects Xcode 16.4 explicitly. The project
has no code-generation or dependency-install step. Test-fixture preparation is
not needed to build/run the app and import your own video.

For a physical phone, use a local Xcode version that supports its installed iOS,
select your Personal Team under Signing & Capabilities, and use your own unique
bundle identifier. There is no paid-account requirement in this development
plan and no TestFlight pipeline. Do not commit your team or signing credentials.
See [the local-device checklist](docs/POC.md#10-local-iphone-verification-with-a-free-account)
and its Apple references for account and provisioning restrictions.

## Tests

Run the exact framework-free application sources and their Swift Testing tests
on Linux or macOS with Swift 6. A disposable harness is created outside the repo;
there is no second production package.

```sh
./scripts/test-core.sh
```

The iOS integration tests additionally exercise real AVFoundation decoding,
variable presentation timestamps, portrait orientation, replay state transitions,
and actual Vision requests. Prepare their pinned public-source smoke media first:

```sh
# macOS; test preparation only, not an app dependency
brew install ffmpeg
python3 scripts/prepare-fixtures.py
./scripts/test-ios.sh
```

Preparation verifies the original source's published digest and size, derives a
small video and still, and records exact timestamps, derivative checksums, and
encoder provenance. See [fixture provenance and limits](HangInThereTests/Fixtures/README.md).
Missing media, integrity failures, missing expected body landmarks, and backend
errors are **failures**, not skipped checks or successful accuracy reports.
The source metadata has been reviewed; the actual footage and tests remain to
be inspected/run. This is a backend smoke check, **not** a pull-up accuracy set.

`scripts/test-ios.sh` compiles an unsigned Release device target, discovers an
available iPhone simulator for the selected SDK, and executes all app tests.
Unsigned compilation does not produce an installable phone app. Normal local
signing is not disabled in the project.

The single [GitHub Actions workflow](.github/workflows/ci.yml) uses a macOS runner,
pinned action commits, read-only repository permissions, no Apple secrets, and
bounded artifact retention. It has been authored but has not run for this change.

## Scope and next steps

Read [the POC implementation and validation plan](docs/POC.md). Keep one app and
small components. Camera and video must eventually share pose normalization and
exercise logic. Do not add services or a cross-platform architecture.

First clear P0's real build/backend/video gates. Then measure Vision against
MediaPipe Heavy on independently reviewed pull-up **and dip** footage; implement
deterministic counting and uncertain outcomes; add the live workout UI; qualify
endpoint measurements and sustained phone performance. A skeleton alone does
not establish chin-over-bar clearance or accurate 3D joint angles.

## Data and licensing

No workout recordings or model weights are committed. The test-preparation
manifest identifies a public source with its recorded rights basis and credit;
derived files and downloaded originals are ignored and are not app resources.
No source-code licence has been selected. Public access is not a substitute for
reviewing the applicable media/model permissions, and smoke fixtures are not
independent anatomical or exercise-form ground truth.