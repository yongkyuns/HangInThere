# P0 implementation and evidence

**Updated:** 2026-09-26

**Scope:** video replay and real Vision integration, not rep counting or live capture.

[PR #2](https://github.com/yongkyuns/HangInThere/pull/2) is the canonical change.
Use its exact-head checks and linked Actions logs for current execution results.
P0 remains awaiting qualification; a successful host test is not a successful
simulator test, iPhone test, or accuracy benchmark.

## Implemented

One Xcode project, SwiftUI app, hosted test target, and shared scheme. Local video
is copied into temporary storage, decoded sequentially, oriented once, scaled to
bounded image dimensions, analyzed with Vision body-pose revision 1, and shown
using that exact image. Invalid timestamps, geometry, decoding and missing bodies
do not generate placeholder observations.

The replay actor exclusively owns the decoder and inference state. AVFoundation
preparation uses a nonisolated async factory returning a fresh graph with Swift 6
`sending`. Cancellation and generation checks precede decoder startup. There is
no `@unchecked Sendable`, `@preconcurrency`, or relaxed language mode. The
[Swift SE-0430 proposal](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0430-transferring-parameters-and-results.md)
describes the ownership-transfer mechanism.

The main-actor controller owns presentation. Pause preserves a consumed frame;
restart, close and source replacement reject stale results. Source timestamps
control pacing. No full-video cache, unbounded queue, second production package,
third-party runtime, server or paid signing requirement has been added.

## Verified execution history

| Exact run / scope | Result |
| --- | --- |
| Original Linux core tests, Swift 6.2.1 | 21 tests passed; not Apple SDK execution. |
| [Run 36268170256](https://github.com/yongkyuns/HangInThere/actions/runs/36268170256), `085f8a1` | Core tests and source acquisition passed. Device build failed on non-Sendable `AVAssetTrack` crossing; fixed in `0ad2f43` using checked ownership transfer. |
| [Run 36273681747](https://github.com/yongkyuns/HangInThere/actions/runs/36273681747), `095d001` | **All 32 native macOS tests passed**, including real Vision, decoder, controller and 40-frame replay. Unsigned Release iPhone build and simulator compilation passed. iOS 26.2 simulator execution failed with 12 issues: missing Vision weights and test-video writer readiness timeouts. |
| [Run 36279923261](https://github.com/yongkyuns/HangInThere/actions/runs/36279923261), `0384fd7` | **All 32 native macOS tests passed** again; unsigned iPhone build passed. Simulator-only CPU inference still failed with `Missing weights path cnn_human_pose.espresso.weights`, Vision Code 9. The 32-test simulator suite reported 20 issues. The ineffective CPU override was removed rather than retained as a speculative workaround. |

These native runs used macOS 15.7.9 arm64 and Xcode 26.3. The older iOS 18.5
simulator had also failed to load the body-pose weights. This is evidence about
these tested hosted runtimes, not a claim that every simulator is unsupported.
The canceled/superseded CPU-probe run is not counted as a complete qualification.
No model files were copied between operating systems and no request failures
were converted into successful empty observations.

## Real exercise fixture correction

The earlier 0-4-second fixture showed two people introducing the exercise, not
pull-ups. Its successful inference was only a real-human integration check.

The complete pinned source has now been acquired, checksum-verified and visually
reviewed. Source seconds **29-33** show one continuous pull-up movement: hang,
ascent, peak, descent, returned hang. All 40 selected frames were inspected
before running the new model checks. The head/chin is cropped at the peak:
**strict top clearance remains ungradable**, not passed or failed.

The manifest records the trim, source pin and single-reviewer limitations. Local
preparation/verification yielded 40 ordered timestamps. The new smoke test checks
body-root movement at three preselected frames and logs actual frame-by-frame
pose observations tied to the derivative hash. These are broad motion sanity
checks, not rep counting or anatomical ground truth. The temporary full-source
artifact step was removed after review; only the named small derivative remains.
See [fixture provenance](../HangInThereTests/Fixtures/README.md).

The old introduction's passing results must not be attributed to this new motion
interval. Consult the new exact-head run for its execution and inspect the
reported landmarks before advancing measurement claims.

## Outstanding gates

The declared Apple-platform gate still requires the unsigned device build and
all simulator tests, including actual Vision inference. The simulator gate is
**unresolved**, not skipped or silently replaced by native host results. Keep
native-model, simulator-model, compile-only and physical-device evidence separate.

Physical iPhone installation, live capture, acceleration, heat and sustained
performance are untested. Pull-up/dip counting and form validation are not yet
implemented. There is no independent joint-error or rep-validity test set and
no reviewed parallel-bar dip corpus. These remain subsequent milestones; the
motion smoke fixture cannot satisfy the POC accuracy/coverage targets.

## Reproduce

Run `./scripts/test-core.sh`. On macOS with test-only ffmpeg installed, run:

```sh
python3 scripts/prepare-fixtures.py
./scripts/test-apple-host.sh  # exact non-UI production pipeline on macOS
./scripts/test-ios.sh        # unsigned device compile + real simulator tests
```

The app itself needs no fixture download. Later physical-device installation
uses local Xcode and a Personal Team. Keep source/configuration, fixture hashes,
target, OS and run result together. Never infer phone FPS from host/simulator
timing or use model predictions as independent labels. The original
[POC plan](POC.md) remains the product and accuracy contract.
