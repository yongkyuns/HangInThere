# HangInThere

A lean, on-device iPhone app in development for pull-up and parallel-bar dip
counting and camera-view-specific range-of-motion analysis.

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

There is **no rep counting, form verdict, camera capture, or qualified accuracy
claim yet**. These are subsequent milestones, not hidden behind placeholder UI.

The app uses SwiftUI, AVFoundation, Core Image, and Vision. There are no third-party
runtime packages, backend services, accounts, model downloads, or analytics.
Imported files are copied into app-local temporary storage and removed on close
or replacement; recordings are never uploaded by the app.

## Open the app

Until the implementation PRs merge, check out the evaluation/measurement branch:

```sh
git clone --branch feat/p1-batch-evaluation https://github.com/yongkyuns/HangInThere.git
cd HangInThere
open HangInThere.xcodeproj
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

The iOS tests exercise actual AVFoundation decoding, variable timestamps,
portrait orientation, decoder replacement/recovery, controller lifecycle,
and real Vision requests. Prepare their pinned public-source smoke media first:

```sh
# macOS; test preparation only, not an app dependency
brew install ffmpeg
python3 scripts/prepare-fixtures.py
./scripts/test-ios.sh
```

Preparation verifies the original source's digest and size, derives a small
video and still, and records exact timestamps, derivative checksums, and encoder
provenance. See [fixture provenance and limits](HangInThereTests/Fixtures/README.md).
Missing media, integrity failures, missing expected body landmarks, and backend
errors are failures, not skipped checks or successful accuracy reports.
This is a backend smoke check, **not** a pull-up/dip accuracy set.

The single [GitHub workflow](.github/workflows/ci.yml) compiles an unsigned Release
device target and runs the simulator tests using a pinned Xcode. It uses read-only
repository permissions and no Apple credentials. Retained artifacts contain
results/provenance and a separately named, approved smoke clip/still for review;
private app imports are never collected. Unsigned compilation does not produce
an installable phone app. Normal local signing is not disabled in the project.

## Scope and next steps

Read [the POC implementation and validation plan](docs/POC.md). First clear the
real build/backend/video gates. Then measure Vision against MediaPipe Heavy on
independently reviewed pull-up **and dip** footage; implement deterministic
counting and uncertain outcomes; add live capture; qualify endpoints and sustained
phone performance. A skeleton alone does not establish chin-over-bar clearance
or accurate 3D joint angles. Keep one app and small components, not services or a
cross-platform architecture.

## Data and licensing

No workout recordings or model weights are committed. The test-preparation
manifest records its public source, rights basis, and credit; generated media are
ignored and are not app resources. No source-code licence has been selected.
Public access does not replace checking media/model permissions, and smoke
fixtures are not independent anatomical or exercise-form ground truth.
