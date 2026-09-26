# P0 implementation and evidence

**Updated:** 2026-09-26

**Scope:** video replay and real Vision integration, not rep counting or live capture.

[PR #2](https://github.com/yongkyuns/HangInThere/pull/2) is the canonical change.
Use its exact-head check and linked Actions logs for the current execution result.
Run-specific qualification updates belong in the PR discussion; this document
records the implementation, evidence history, and limits rather than presenting a
moving CI badge as an accuracy result.

## Implemented

One Xcode project, SwiftUI app, hosted test target, and shared scheme. Local video
is imported into temporary storage, decoded sequentially, oriented once, scaled
to bounded image dimensions, analyzed with Vision body-pose revision 1, and shown
using the exact analyzed image. Invalid timestamps, geometry, decoding, and
missing bodies do not generate placeholder observations.

The replay actor exclusively owns the decoder and inference state. AVFoundation
preparation now happens in a nonisolated async factory, which returns its fresh
object graph with Swift 6 `sending`. After transfer, only the replay actor uses it.
Cancellation and session-generation checks run before starting the decoder.
No `@preconcurrency` import, `@unchecked Sendable` wrapper, or relaxed language
mode was added. The ownership-transfer mechanism is described in
[Swift SE-0430](https://github.com/swiftlang/swift-evolution/blob/main/proposals/0430-transferring-parameters-and-results.md).

The main-actor controller owns presentation. Pause preserves an in-flight
consumed frame; restart, close, and source replacement invalidate stale results.
Source timestamps control pacing. There is no full-video image cache, unbounded
frame queue, additional production package, or third-party runtime dependency.

## Evidence history

| Evidence | Observed outcome |
| --- | --- |
| Original local core checks | 21 Swift Testing tests passed on Linux with Swift 6.2.1. Source/project/script parsing also passed, but did not exercise Apple frameworks. |
| First published CI: [run 36268170256](https://github.com/yongkyuns/HangInThere/actions/runs/36268170256), head `085f8a1` | All 21 core tests passed on macOS. The real source downloaded and its integrity pin passed; a 40-frame derivative was prepared. The unsigned device compile then failed at `loadTracks(withMediaType:)`: non-Sendable `AVAssetTrack` crossed an actor boundary. No simulator or model result was established by that run. |
| Repair `0ad2f43` | Replaced that crossing with an exclusively owned decoder graph and a compiler-checked `sending` transfer. This is a source fix; its effectiveness must be established by subsequent Apple CI. |
| Regression additions | Actual-decoder tests now cover replacement of geometry/timestamps and recovery from malformed media, as well as prior orientation, rewind, pause/resume, and stale-result checks. The workflow retains only the specifically named approved smoke derivative for visual review. Test definitions alone are not passing results. |
| Subsequent runs | See the exact-head checks and qualification notes in PR #2. Cancelled or superseded runs are not counted as complete qualification. |

## Qualification gates and limits

The Apple-platform gate must pass the unsigned Release device build and all
simulator tests, including actual Vision extraction from the real still and
40-frame replay. No missing-media skip or model stub substitutes for that gate.
The generated four-colour videos test decoding/orientation only, not human pose.

The smoke fixture also requires direct visual review of its still and clip.
The `p0-smoke-fixture` artifact contains the derivative, source manifest, and
prepared hashes. It is distinct from `p0-test-results`, which holds execution
logs and the Xcode result bundle. Do not publish private videos, app imports,
or broad simulator directories as artifacts.

Even successful P0 CI establishes **integration**, not exercise accuracy:

- Physical iPhone capture, acceleration, heat, and sustained performance remain untested.
- Pull-up/dip counting and form validation are not implemented in P0.
- The smoke source has no independent joint-error or rep-validity labels.
- Model selection and accuracy/coverage targets still require reviewed data for both exercises.

## Reproduce

Run `./scripts/test-core.sh`. On a compatible Mac, install test-only ffmpeg,
run `python3 scripts/prepare-fixtures.py`, then `./scripts/test-ios.sh`.
The app itself opens without fixture downloads. No Apple signing credentials
are used by CI; device installation later uses local Xcode and a Personal Team.

Keep the source/configuration, fixture hashes, target, OS, and run result together
when comparing outputs. Do not infer phone FPS from simulator timing or treat
model-generated landmarks as independent labels. The original [POC plan](POC.md)
remains the product and accuracy contract; its historical checklist is not a
claim that these later gates have passed.
