# P0 implementation and evidence

**Prepared:** 2026-09-26

**Base:** `42162fdcfc31101039da8718d974962847064634`

**Scope:** video replay and real Vision integration, not rep counting or live capture

## Implemented source

One committed Xcode project, app target, hosted test target, and shared scheme.
The app imports a security-scoped file into temporary storage, decodes one frame
at a time, applies its preferred orientation, scales it to a bounded processing
size, runs `VNDetectHumanBodyPoseRequest` revision 1, and displays that exact image
with its landmarks. It reports invalid timestamps, unsupported geometry, decode
failures, and absent bodies rather than generating placeholder observations.

The reader owns decoder/inference state in one actor. The main-actor controller
owns presentation. Pause preserves an in-flight consumed frame; restart, close,
and source replacement invalidate stale results. Source presentation timestamps
control replay; neither a nominal 30 FPS clock nor UI interpolation supplies
observation evidence. No full-video frame cache or unbounded task queue is used.

Only the small pose, geometry, and timeline value types are framework-free. The
real app uses Apple frameworks directly. A temporary SwiftPM test harness does
not introduce a parallel production library. There are no third-party runtime
dependencies, analytics, or paid signing requirements in the workflow.

## Verification performed

| Check | Actual outcome |
| --- | --- |
| Exact app `Analysis/` sources and checked-in core tests | **21 Swift Testing tests passed**, in three suites, using Swift 6.2.1 on Linux x86_64. Parameterized cases cover angles and confidence values. |
| Swift source syntax parsing | Passed for app and test files; parsing does not resolve Apple SDK symbols or prove an iOS build. |
| Xcode project | OpenStep plist lint, referenced-file membership, and shared-scheme XML validation passed. Not compiled by Xcode. |
| Shell, Python, JSON, workflow | Shell syntax, Python byte-compilation, JSON parsing, and workflow YAML validation passed. Not a workflow execution. |
| Fixture preparation mechanics | Prepared/replaced a 40-frame clip in an isolated temporary test using original synthetic pixels; checked digests/timestamps and rejection against the real-source pin. This proves script mechanics only, not real-footage availability or pose inference. |
| Controller cancellation logic, auxiliary check | Four isolated checks passed using a temporary decoder stub and a copy without Observation macros. The unmodified Observation harness hit a Linux runtime linker error. Neither run qualifies SwiftUI, AVFoundation, or iOS execution; actual controller integration tests are checked in for Xcode. |

## Required checks not yet performed

| Gate | Current status |
| --- | --- |
| Xcode app and unsigned device compilation | **Not run**; the working environment is Linux without the Apple SDK. |
| iOS simulator tests | **Not run**, including actual decoder orientation, controller lifecycle, and real Vision extraction. |
| Real pull-up fixture | Source metadata and published integrity pin recorded; footage not downloaded or visually reviewed here. Preparation and body assertions must run on a networked host. |
| GitHub publication and CI | **Not pushed; no PR or Actions run created.** This session exposes only read actions for GitHub, and the shell cannot reach GitHub. Source and an apply-ready patch are provided instead. |
| Physical iPhone | **Not run**; later local Xcode/Personal Team testing remains required. |
| Pull-up/dip counting, form accuracy, performance | **Not implemented or qualified in P0.** No dataset-level accuracy or phone latency is claimed. |

The real-human test is deliberately not replaced by a generated silhouette or
mocked landmarks. Missing fixture files and failure to find a visible arm make
that test fail. A first run may reveal fixture, SDK, orientation, or backend
problems that still need repair. Until the required build and model/video tests
pass, this change is **P0 implementation awaiting qualification**, not completed P0.

## Reproduce and finish the gate

From the repository root, run `./scripts/test-core.sh`. On a compatible Mac,
install the test-only ffmpeg tools, run `python3 scripts/prepare-fixtures.py`, and
then `./scripts/test-ios.sh`. The app itself can be opened and run without the
fixture downloader. The CI job performs the same checks after publication.

Review the source and derived still/clip before accepting the smoke fixture.
A detected arm is only an integration assertion. Keep review and any later
independent joint/rep labels separate; do not treat model predictions as labels.
If the source starts with a title or unusable view, change the documented trim
only after inspecting the footage, preserve provenance, and requalify the test.

Retain the actual build/test results and replace these pending statuses with
measured evidence. Do not add a passing badge or advance the model/counting
qualification solely because the project parses or the core tests pass.

The original [POC plan](POC.md) remains the product and accuracy contract. Its
historical “at creation” checklist has not been retroactively marked complete.