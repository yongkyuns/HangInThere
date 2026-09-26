# HangInThere: implementation and validation plan

**Written:** 2026-09-26  
**Target:** a usable iPhone POC for pull-ups and parallel-bar dips  
**Status:** proposed design; implementation and qualification have not started

This document is the implementation contract, not a claim that tracking accuracy
has already been achieved. Numerical thresholds below are initial hypotheses or
release targets, explicitly labelled as such. External facts have source links;
architecture, algorithms, milestones, and acceptance criteria are proposed here.

## 1. Decisions and product scope

| Decision | POC choice |
| --- | --- |
| Platform | Native iOS; provisional minimum deployment target iOS 17. No Android or browser architecture. |
| UI | SwiftUI, one workout screen, a setup sheet, and a compact results view. |
| Input | AVFoundation rear-camera capture and local video replay. |
| Initial pose backend | Apple Vision 2D, to establish the simplest executable baseline. |
| Accuracy comparison | MediaPipe Pose Landmarker Heavy on the same eligible footage; Full only as a performance trade-off. |
| Shipping backend | One measured winner. No permanent ensemble or runtime model picker for normal users. |
| Exercise analysis | Explicit per-exercise state machines and inspectable measurements. |
| Distribution for now | GitHub source and simulator testing; later local Xcode installation using a free Personal Team. |
| Storage | Local session summaries and optional user-initiated diagnostic export. |
| Excluded | Accounts, server, cloud inference, HealthKit, Watch app, subscriptions, social features, training infrastructure, AR scene, and generic plugin framework. |

### The usable workflow

The user chooses Pull-up or Dip, positions a fixed phone using the setup guide,
checks the framing indicator, and starts after a short countdown. The screen
shows the camera preview, a restrained skeleton overlay, checked rep count,
current phase, and a tracking-quality message. A sound or haptic can acknowledge
a checked rep. Stop displays the result and a short per-rep explanation.

Video import is not a developer-only afterthought. It supports replay, pause,
restart, a phase timeline, and exporting the same measurements used in live mode.
A small diagnostic panel can run an evaluation set and export a report. Do not
build a separate dashboard application.

### Supported capture envelope

Initially support one athlete, the rear 1x camera, a stationary phone, landscape
live capture, sufficient lighting, and a mostly unobstructed near-side arm. Keep
the head, wrists, shoulders, elbows, hips, and relevant apparatus visible through
the entire movement. Include legs when evaluating swing. Do not require a
particular phone distance before testing the framing and landmark-resolution
requirements; favour moving the phone over digital zoom.

Use a side-oriented view for dip depth and near-arm angles. For pull-ups,
experiment with side and modestly oblique views, then publish the specific view
that passes both arm and face visibility tests. There is no assumption that one
arbitrary viewpoint supports every metric. A side view useful for arm geometry
may be poor for face detection; this is an early feasibility gate, not a detail
to postpone until release.

Bench dips, ring dips, assisted-machine dips, muscle-ups, deliberately kipping
pull-ups, crowded/mirrored tracking, and handheld moving-camera operation are
outside the first qualified profile. Capture such footage as rejection/stress
cases; do not silently treat it as supported input.

## 2. Accuracy means evidence, not a plausible overlay

Separate the following quantities:

- **Movement event:** an observed exercise-like excursion or cycle.
- **Checked rep:** all required observations passed the named camera-view profile.
- **Partial attempt:** adequate evidence demonstrates a missed required endpoint.
- **Unverified attempt/interval:** occlusion, cropping, ambiguous identity, or a
  missing endpoint prevents a decision.

The normal UI can say `8 checked reps · 1 partial · 1 unverified`. An estimate
mode may show movement counts, but must label them estimated and must not add
them to the checked count. Optional form observations use pass/fail/unknown;
missing data is not a failing joint angle and is not a passing rep.

Do not present a monocular profile as competition judging, medical assessment,
injury prediction, or motion-capture-grade 3D analysis. Do not infer scapular
motion or shoulder axial rotation from an elbow/shoulder/wrist skeleton.

### Model-selection experiment

Apple Vision exposes up to 19 body landmarks, including shoulders, elbows, and
wrists, with confidence values. It is available without adding a third-party
pose package. Its body landmarks do not include a dedicated chin or apparatus
reference. [A1]

MediaPipe provides 33 landmarks, downloadable Lite/Full/Heavy bundles, and an
official iOS implementation for image, video, and live input. It is the first
independent comparator, not an assumed accuracy winner. [M1] [M2]

Implement and measure Vision first, then compare Heavy before selecting the
qualified backend. First inspect a small reviewed pull-up AND dip sample; do
not spend weeks polishing the UI around an untested estimator. Compare visible
joint errors, endpoint failures, rep decisions, uncertainty coverage, memory,
and device latency. A higher generic mAP score or a smoother skeleton is not
sufficient evidence to switch.

Keep the comparison in a focused branch or evaluation build configuration.
Share the observation representation and exercise engine. If Vision passes,
retain it and remove comparison-only runtime code. If Heavy materially improves
accuracy and meets sustained device targets, ship it instead. If Heavy is too
slow, measure Full rather than assume it has the same accuracy. Package/runtime
availability and simulator slices must be proved for the exact dependency
revision. Google's current setup guide describes 64-bit simulator support and
SPM integration; pin a validated revision and asset checksum, never `master` or
an unversioned `latest` download. [M3]

RTMPose fine-tuning, custom Core ML conversion, Vision 3D, generic learned rep
counters, and larger 3D reconstruction models are deferred. Revisit only after
a concrete failure remains after coordinate, framing, and annotation checks.
Do not ship a conversion/training toolchain simply because it may be useful later.
No assumption is made that a chosen third-party delegate uses the Neural Engine;
record the actual backend and qualify it on-device.

## 3. One app, small components

Use one committed Xcode project, one app target, one unit/integration test target,
and at most one small UI-test target. Commit the shared scheme. Start with plain
Swift files inside the app target, not a workspace full of tiny packages.

The following is a **planned** layout; these application files do not exist in
the documentation-only seed:

```text
HangInThere.xcodeproj/          # committed project + shared scheme
HangInThere/
  App/                         # SwiftUI entry point and session UI state
  Capture/                     # camera input and sequential video reader
  Pose/                        # selected estimator + coordinate normalization
  Analysis/                    # value types, geometry, filtering, rep logic
  UI/                          # preview, setup, overlay, results
  Evaluation/                  # replay runner and JSON/CSV report writer
HangInThereTests/
  Fixtures/                    # synthetic data + approved small real fixtures
HangInThereUITests/             # only when useful UI behaviour is exercised
Evaluation/
  manifest.json                # dataset/clip provenance and split definitions
  annotations/                 # reviewed labels with permitted provenance
scripts/                       # a few build/evaluation commands, when needed
.github/workflows/ci.yml        # added with real buildable targets
```

Both adapters call the same processing entry point:

```text
Camera sample buffer ─┐
                      ├─> Frame -> Pose estimator -> Normalized observation
Video sample buffer ──┘                                 |
                                         Quality and geometry checks
                                                       |
                                         Exercise engine -> Session result
                                                       |
                                              UI / evaluation report
```

`WorkoutEngine` is ordinary Swift operating on timestamped value types. It does
not import AVFoundation, Vision, SwiftUI, or a third-party pose SDK. Test it
through the app's test target; extract a package only when a real additional
consumer justifies doing so.

A small `PoseEstimator` boundary is justified by the planned comparison and
synthetic tests. Do not create service locators, registries, a DI container,
reactive event buses, a cross-platform HAL, or generalized frame-source hierarchies.
Two concrete input adapters and a narrow processing method are sufficient.

### Ownership and scheduling

Use one serial inference execution context and one owner of the mutable analysis
state. Camera configuration/capture runs off the main thread. Publish immutable
UI snapshots on the main actor. Keep SDK objects and pixel buffers within their
well-defined lifetime and isolation rules; do not disable Swift concurrency
checking or scatter `@unchecked Sendable` over wrappers to silence errors.

Live capture has at most one frame in flight and one replaceable latest frame.
Never enqueue an unbounded `Task` for every capture callback. Release discarded
buffers promptly. AVFoundation's late-frame discard option helps at its delegate
queue, but does not bound a second queue created by the application. [A2]

Sequential evaluation uses a different scheduling policy, not different analysis:
process every selected presentation timestamp in order without wall-clock frame
drops. Separately run real-time replay with the live admission policy and recorded
or simulated drop schedules. Report both results; all-frame replay is not proof
that a device keeps up with capture.

## 4. Observation and numerical contract

### Minimal records

| Record | Required content |
| --- | --- |
| Frame | Presentation timestamp, session generation, oriented image dimensions, orientation/crop transforms, and pixel buffer. |
| Pose observation | Timestamp, selected-person identity token, backend/revision, per-joint coordinates, confidence and available visibility information, quality flags. |
| Measurements | Selected arm, valid joint angles, body/apparatus displacement, optional chin clearance, timestamp, and reasons each metric is unavailable. |
| Rep event | Exercise/profile version, start and event timestamps, outcome, endpoint evidence, and stable reason codes. |
| Evaluation report | Commit, config, source/annotation checksums, model/runtime identity, device/OS/build, outcomes, timing distributions, and exclusions. |

Do not pretend confidence values are calibrated probabilities or average
incompatible confidence scores across backends. Configure and validate required
joint gates per backend. Zero-confidence Vision points are invalid. [A1]

### Coordinates

Normalize observations to unmirrored, correctly oriented image pixels with a
single documented origin (top-left in the engine). Convert Vision's lower-left
normalized output explicitly. Retain a reversible transform for ROI and preview
mapping. Preview mirroring is a presentation decision, not a left/right-label
swap in the analysis. Apple documents Vision's original coordinate convention.
[A1]

Apply video preferred transforms, camera orientation, crop/letterbox transforms,
and aspect-fill preview mapping exactly once. Test rotation, reflection, ROI
round trips, non-square images, and scaling with known points. A skeleton aligned
on one landscape screenshot is not sufficient validation.

For shoulder S, elbow E, and wrist W, the interior elbow angle is:

```text
u = S - E; v = W - E
angle = acos(clamp(dot(u, v) / (length(u) * length(v)), -1, 1))
```

Reject degenerate or very short projected segments. Use pixels or a common-scale
coordinate system, not independently normalized x/y axes in a non-square image.
Straight is approximately 180 degrees under this convention. Call it an
**image-plane angle**; foreshortening can invalidate its physical interpretation.

### Time, filtering, and continuity

Use source presentation timestamps, never callback arrival time or `frameIndex / 30`
unless the source actually guarantees that time base. Offline playback speed must
not change detected events. Reject duplicate or out-of-order timestamps; reset
analysis on seeks, source replacement, or session restart. Discard late callbacks
whose session generation no longer matches.

Start with one causal time-aware filter, such as an exponential smoother whose
coefficient is `1 - exp(-dt/tau)`. Tune tau against endpoint error and delay. Retain
raw and filtered signals in diagnostic exports. Avoid stacking smoothing layers,
using future frames in a purportedly live algorithm, or treating display
interpolation as observation evidence.

A brief missing observation may preserve the phase latch, but cannot earn a new
endpoint or checked rep. A long gap invalidates the in-progress attempt and
requires rearming from a visible starting endpoint. Measure gaps in seconds.
Starting values of 150 ms dwell, 300 ms long-gap handling, and roughly 80 ms
filter tau are hypotheses to tune, not defaults certified for human movement.
Test slow, fast, paused, and irregular repetitions; do not assume a fixed cadence.

Use simple spatial continuity to select the athlete, not a full multi-object
tracker. If candidates become ambiguous or a plausible identity jump occurs,
pause qualification. Keep the chosen near-side arm stable during a rep. If it
becomes unusable, mark uncertainty; do not silently splice the far-side elbow
into the near-side chain. Require rearming after an intentional arm switch.

## 5. Repetition definitions and form checks

Keep one small configuration per exercise, with documented units and a version.
Thresholds are tuned only on development clips and frozen for the held-out test.
Calibration establishes framing, scale, apparatus position, and visible start
pose. Do not learn the definition of a valid rep from the first two unverified
repetitions: both could be partial.

### Pull-ups

The checked profile needs an observed extended-arm starting position and an
observed top criterion. A sensible state sequence is:

```text
waiting -> bottomReady -> ascending -> topConfirmed -> descending -> bottomReady
```

Emit a checked rep once at `topConfirmed` when the start and top evidence both
pass. Latch it until a newly observed bottom rearms the next repetition. This
counts a last rep held at the top without requiring the athlete to descend again.
Do not add a second count when the athlete returns to the bottom.

An ascent that reverses before a supported top criterion is a partial attempt
when sufficiently observed, or unverified when the endpoint was hidden. Starting
mid-rep does not invent the missing starting endpoint. A static hang, small sway,
repeated threshold crossings, and grabbing or releasing the bar must not count.
Use hysteresis, minimum meaningful body travel, and time-based persistence rather
than a single elbow-angle threshold. Holds do not emit repeated events.

An interior elbow angle around 160 degrees can be an initial *projected extension*
hypothesis, not a universal anatomical rule. Confirm body translation relative
to the fixed apparatus where visible; body-relative coordinates alone would
remove that useful translation.

**Chin-over-bar is a separate measurement gate.** The body estimator's nose/head
is not a chin. In setup, let the user tap two points along the visible bar once.
Store the bar line in analysis coordinates and require recalibration after the
camera, zoom, framing, or source changes.

Investigate a native face-landmark request near the predicted head region. Vision
provides a face-contour region spanning the cheeks and chin. Associate the face
with the selected athlete and convert its face-box-relative coordinates correctly.
Do not assume the lowest screen-space contour point is always the chin under
head tilt or partial visibility. [A3] Validate the chin proxy against manually
labelled chin points, then measure signed clearance from the calibrated bar line,
with an uncertainty margin and consecutive evidence.

The metric remains **observed image-plane clearance** under an approved camera
view. Camera tilt, depth separation, face resolution, beard/occlusion, and profile
view can invalidate it. When it fails, preserve labelled movement counts but mark
top verification unknown. The app must not claim strict pull-up verification
until this combined capture-and-measurement gate passes held-out tests. Avoid
adding a new detector or 3D reconstruction stack to hide the failure.

### Parallel-bar dips

Use the visible near-side shoulder, elbow, wrist, and torso with a side-oriented
camera profile:

```text
waiting -> topReady -> descending -> bottomConfirmed -> ascending -> topConfirmed
```

Emit once on return to the extended-arm top after a verified bottom. Reuse that
top as the ready state, but require a new descent and bottom before another count.
A partial descent, repeated bouncing around the bottom, a top hold, or stepping
onto the equipment must not count as an additional checked rep.

Define the depth rule explicitly. An initial `dip_side_v1` experiment may require
near-side shoulder height to reach the elbow level or below in the approved image
view, combined with elbow flexion and torso travel. This is a testable projected
criterion, not a claim of universally correct or safe dip depth. Angle-only and
height-only rules must each face adversarial examples. Do not infer hidden-side
symmetry or label unilateral measurements as whole-body symmetry.

### Feedback budget

Initially report endpoint extension, projected depth/clearance where qualified,
concentric/eccentric duration when timestamps support it, and visible torso/swing
measurements. Quantify measurements before imposing additional pass/fail thresholds.
Do not add an arbitrary aggregate form score. Feedback should explain observations
such as `top not visible`, not speculate about injury or tell the user to force
an uncomfortable joint position.

## 6. Footage, annotations, and evaluation provenance

### Start with existing evidence, but audit permission first

| Source | Established usefulness | POC treatment |
| --- | --- | --- |
| Penn Action | Contains `pull_ups`, 13 annotated 2D joints, visibility, coarse viewpoint, and per-sequence labels. [D1] | Candidate for pull-up landmark evaluation. The public page is not itself evidence of permission for this project's commercial development or redistribution. Audit before acquisition/use. |
| RepCount-A | Includes pull-up videos from YouTube, counts, and action-period locations. [D2] | Candidate for counting and interruptions; not joint or form ground truth. Check media rights independently of repository code licence. |
| RepCount-B | Authors explicitly state that original Part-B videos cannot be released. [D3] | Do not plan a download or qualification gate around it. |
| Real dip footage | A sufficiently labelled, rights-cleared source is not established by this plan. | Review publicly offered clips or obtain consented recordings, distinguish apparatus types, and annotate the selected material. Treat availability as an unresolved input. |
| Generated landmark sequences | Entirely controlled synthetic input to the exercise engine. | Use extensively for logic and geometry tests, never as evidence of real-image pose accuracy. |

Do not bundle unreviewed Kaggle/YouTube clips merely because they are downloadable.
Do not treat AI-generated workout videos as anatomical ground truth. No dataset
archive or model asset is acquired by this documentation package.

The practical first experiment is a few rights-cleared complete sets per exercise
covering a clean case and an obvious failure case. It is not a qualification
sample. For final POC evaluation, aim initially for at least 50 held-out complete
sets and 300 labelled attempted reps per exercise across at least 10 adults and
multiple sessions, plus explicit negative clips. These are collection goals, not
a statistical guarantee. Report smaller samples honestly rather than manufacture
confidence from many adjacent frames of one person.

If public dip data cannot be used on acceptable terms, continue camera plumbing,
synthetic tests, and approved footage evaluation; mark dip accuracy blocked on
independent real data. Do not mark the app qualified while that gap remains.

### Minimal annotation format

Use a simple versioned JSON manifest and JSON/CSV annotations, not a dataset
service. For each clip record source identifier, source and annotation checksums,
exercise/apparatus/view, pseudonymous subject/session group, split, trim range,
image transform, timestamp provenance, permission/attribution status, and allowed
artifact uses. Keep consent records and personal names outside the public repo.

Annotate starting endpoints, top/bottom events, attempted-rep intervals, checked
outcome under the named profile, visibility gaps, and exclusion reasons. Label
shoulder/elbow/wrist positions at all endpoints and selected intermediate/hard
frames. For the pull-up top gate, independently label chin and bar where visible.
Add dense joint labels only when needed to investigate instability.

At least two independent reviewers should check the qualification outcomes and
ambiguous endpoints; record disagreements and adjudication. Joint definitions
must be consistent across datasets and models. A shoulder landmark definition
mismatch is not automatically model error. Annotation uncertainty belongs in
the report, especially when comparing small angular errors.

Public action-period boundaries may use a different event convention from this
app's top-of-pull-up count. Map or independently annotate the event convention;
do not score the same label as two different events. Penn Action provides image
sequences: when the true frame timing is unavailable, use frame-index landmark
metrics and mark time-based metrics unavailable. Do not invent a nominal FPS.

### Development/test discipline

Split by person and session, deduplicate original videos across sources, and
retain official dataset splits where practical. Tune filters and thresholds on
development data only. Freeze an independent test set and record every exclusion
before running the comparison. Report supported-view results separately from
stress/OOD results, but never remove hard supported examples after seeing errors.

Pretrained model exposure to public benchmarks may be unknown. State that limit;
new, independently recorded phone footage is still needed for generalization.
A model's generated labels are not independent truth for evaluating that model.

### Public CI privacy

Commit only approved small fixtures and permitted annotations. Keep large or
restricted datasets in ignored local/external directories. Public workflow logs,
issues, screenshots, exports, and artifacts must not contain private footage,
faces, filenames with personal information, or signing material. Model and data
licences are reviewed separately. No automatic upload of workout recordings.
Publicly releasable aggregate metrics do not imply that source frames can be
published. Prefer JSON summaries over video artifacts.

## 7. Acceptance targets and how to calculate them

**These are proposed targets, not measured results.** Apply each independently to
pull-ups and dips in the predeclared capture envelope; do not average a weak dip
result away with stronger pull-ups. Compare backends using the same eligible
frames, joint definitions, and event matching.

| Area | Initial POC target and reporting rule |
| --- | --- |
| Rep events | Precision and recall each at least 98% for observable movement events; one-to-one matching with a predeclared time window. |
| Set counts | Exact-count rate at least 95% on held-out supported complete sets. Report movement counts and checked-rep counts separately. |
| Incorrect acceptance | At most 2% of independently labelled invalid attempts accepted as checked. Publish numerator and denominator, not just a percentage. |
| Incorrect rejection | At most 5% of independently labelled valid, gradable attempts explicitly rejected. Unknown outcomes are reported separately, not hidden here. |
| Decision coverage | At least 90% of supported, reference-gradable attempts receive a decision, and at least 90% of reference-valid attempts are actually accepted. Publish coverage and accepted precision together. |
| Joint accuracy | Report per-joint pixel error and torso-scale-normalized error, endpoint subsets, side swaps, and missing predictions. No single aggregate pose score substitutes for these. |
| Angle accuracy | Where independently annotated 2D landmarks support it: mean absolute elbow-angle error at most 5 degrees and 95th percentile at most 10 degrees. Not a 3D-angle claim. |
| Negative cases | No counted reps in the committed smoke set of hangs, setup/repositioning, pauses, and unrelated movement. Expand the corpus when failures occur. |
| Device responsiveness | Aim for at least 20 processed pose updates/s sustained, with p95 capture-to-result latency at most 150 ms on each declared supported phone. |
| Sustained operation | A 15-minute physical-device session has bounded memory, no crash or ever-growing queue, and continued useful tracking. Record thermal state, input drops, and time-series latency. |

Use a tentative matching tolerance of 250 ms around the defined event, then freeze
it before testing. Annotated frame-only sources use an explicit frame tolerance
and do not contribute millisecond claims. Event timestamps are the source event;
also measure notification delay separately so backdating an event cannot hide UI
latency. Unmatched predictions are false positives; unmatched reference events
are false negatives. Report duplicate and missed counts explicitly.

Use per-set/per-subject results and confidence intervals or subject-clustered
resampling; correlated adjacent frames are not independent statistical samples.
Report the sample sizes, unknowns, failure examples, and incomplete coverage.
The targets can block qualification. Do not lower them silently to make a
scorecard green or satisfy them by refusing nearly every rep.

Device measurements must identify phone model, OS, app configuration, backend and
asset revision, capture resolution/rate, elapsed session time, and profiling
conditions. Evaluate optimized builds; compare key results with and without the
debugger. CPU/simulator timings are useful engineering diagnostics, not a measured
iPhone FPS claim. Apple's simulator documentation explicitly distinguishes
simulator and physical-device behaviour. [A4]

Do not derive energy accuracy from a brief battery-percentage observation. Battery
and sustained acceleration behaviour remain device-only measurements.

## 8. GitHub-based implementation and testing

GitHub's standard hosted macOS runners can run Xcode; standard hosted runners for
public repositories are currently free. Runner images and installed SDKs change,
so select a tested explicit runner label and Xcode path in the actual workflow,
record them in reports, and qualify upgrades. [G1]

### First real CI workflow

Create CI together with the first real Xcode project, not in this documentation
seed. Use one macOS job initially, containing:

1. Record `xcodebuild -version`, available SDKs, simulator runtime, and architecture.
2. Build the iOS app for an installed simulator and run actual geometry, state,
   coordinate-transform, and recorded-input tests.
3. Execute a real Vision request on an approved human-image fixture. Fail if the
   backend errors or required expected landmarks are absent; a mocked observation
   is not a backend smoke test.
4. Run at least one rights-cleared short video through the same app pipeline,
   asserting actual timestamps and expected outcomes. Expand to both exercises
   before claiming two-exercise regression coverage.
5. Compile a generic physical-device build with signing disabled to catch device
   SDK/architecture packaging errors. This does not install or run on a phone.
6. Retain test results and approved metric summaries, including failures.

Xcode supports command-line builds and test destinations. [A5] Once the project
and shared scheme exist, the intended command shape is below. It is a template,
not a command that currently succeeds in this documentation-only package:

```sh
# Discover the actual installed runtime/device; do not guess an iPhone name.
xcrun simctl list devices available

# SIMULATOR_UDID must be selected from the discovered available devices.
xcodebuild test \
  -project HangInThere.xcodeproj \
  -scheme HangInThere \
  -destination "platform=iOS Simulator,id=${SIMULATOR_UDID}" \
  -resultBundlePath build/SimulatorTests.xcresult

# No development profile or Apple login is needed for this compile-only check.
xcodebuild build \
  -project HangInThere.xcodeproj \
  -scheme HangInThere \
  -configuration Release \
  -destination 'generic/platform=iOS' \
  CODE_SIGNING_ALLOWED=NO
```

Simulator tests must not depend on a paid certificate; retain any ordinary local
simulator/ad-hoc signing Xcode requires. Never commit the device build's
`CODE_SIGNING_ALLOWED=NO` as the universal project setting, because local device
installation needs signing.

Run the small reviewed regression set per relevant pull request. A manually
invoked larger evaluation may consume an explicitly supplied local corpus. A
missing/unauthorized corpus yields `not evaluated`, not a successful accuracy
check. Keep synthetic logic, real-model smoke, real-video accuracy, and physical
device qualification as separate statuses.

Use minimal workflow permissions, no Apple secrets, no `pull_request_target`
execution of untrusted code, and no persistent self-hosted runner for public fork
PRs. Pin actions/dependencies and constrain artifact retention. Avoid a matrix of
many devices/backends until a real regression warrants it. Cache versioned
immutable dependencies, not mutable downloaded `latest` model assets.

### Essential logic and integration tests

Cover full reps, short excursions, pauses at every phase, slow negatives, jitter,
bottom/top bouncing, first/last partial reps, stopping at the final pull-up top,
changing view, arm occlusion, identity jumps, lost frames, session interruption,
nonmonotonic timestamps, seeks, and stale asynchronous results.

Property-style checks should assert that translation and uniform scaling do not
change angles/counts, replay speed does not change events, a static valid pose
never produces repeated counts, and an invalid interval cannot produce a checked
endpoint. Inject realistic timing gaps in addition to smooth synthetic traces.
UI tests need only cover core interactions: exercise choice, import, start/stop,
restart, visible counter, unavailable-camera handling, and export.

## 9. Small implementation milestones

| Milestone | Deliverable | Exit evidence |
| --- | --- | --- |
| P0: executable foundation | Committed Xcode project, shared scheme, SwiftUI screen, local video reader, Vision adapter, first CI job. | Simulator build/test and real landmark extraction from a reviewed fixture; an imported clip reaches the UI through actual processing. |
| P1: measurement feasibility | Audited sample manifest, coordinate/angle tests, raw and filtered observation exports, near-arm continuity, a focused Vision/Heavy comparison. | Inspectable results on both exercises, known hard cases, and a provisional backend choice. No final performance claim without a device. |
| P2: deterministic counting | Per-exercise state machines, stable reason codes, partial/unknown handling, event annotations, regression tests. | Expected counts and transitions on synthetic cases and approved real clips; final-top pull-up and interrupted-set cases covered. |
| P3: usable live workout | Camera preview, guide/calibration, readiness, countdown, bounded processing, overlay, feedback, summary. | Simulator replay/UI checks; live path compiles, with hardware verification explicitly pending. |
| P4: qualified endpoint checks | Tested bar/face association and chin-clearance proxy, projected dip depth, backend decision finalized when device evidence exists. | Real labelled endpoint evaluation meets the defined profile, or unsupported checks remain visibly unknown and qualification stays incomplete. |
| P5: local iPhone qualification | Free-account installation, on-device replay comparison, live complete sets and sustained sessions. | Device reports plus held-out accuracy/coverage gates for both exercises. README accurately names tested devices/views and limitations. |

Each milestone should be a small reviewable change, or a few focused PRs, not a
large speculative framework. Update measured evidence in the repository after
actual runs. Keep the app runnable after P0. Do not substitute additional design
documents for implementing and exercising the critical pipeline.

When a gate fails, first verify coordinate handling, timestamps, annotation
agreement, identity continuity, and capture conditions. Then compare the model.
Only introduce custom training or a new runtime after documenting a residual
model failure and a feasible deployment path. No per-video threshold hacks,
post-hoc test exclusions, or benchmark-specific overrides.

## 10. Local iPhone verification with a free account

No paid Apple Developer Program membership is assumed. Apple supports personal
device testing through an Xcode Personal Team; its current documentation says
profiles expire after seven days, after which rebuilding/reinstalling is needed.
[A6] There is no TestFlight release path in this POC.

Once application code exists:

1. Use a Mac/macOS version capable of running Xcode with support for the phone's
   installed iOS version. Verify this combination before relying on an older Mac.
   An iOS deployment target alone does not establish Xcode/device compatibility.
2. Clone the repository and open `HangInThere.xcodeproj`. Run the simulator tests
   and bundled approved replay fixture first.
3. Sign in to the Apple account in Xcode. Select the app target, enable automatic
   signing, choose the Personal Team, and use a unique bundle identifier.
4. Pair/connect the iPhone, trust the Mac, and enable Developer Mode as prompted.
   Select the physical phone as the run destination and build/run. Apple's device
   and Developer Mode instructions are linked below. [A4] [A7]
5. Grant camera permission. The app should request only camera access for live
   capture, not microphone access. Local file import does not require a cloud
   account. A denied camera must leave video replay usable.
6. Run the diagnostic replay on the phone with the same source/config/checksums
   as CI, export JSON/CSV through the share sheet, and compare events and metrics.
7. Test complete pull-up and dip sets, partials, long holds, occlusion, and
   repositioning in each supported view. Record known reference counts separately.
8. Run sustained sessions, including foreground interruption/recovery and repeated
   set resets. Record heat/thermal state, drops, latency, and memory behaviour.
9. Repeat the important performance checks with an optimized build without an
   attached debugger. Reinstall when the free provisioning profile expires.

Do not commit the user's development team, provisioning profile, certificates,
private recordings, or local configuration. Keep team and bundle overrides local
or in an ignored `Local.xcconfig` when the project introduces that mechanism.
The app must need no restricted capabilities for camera-based counting.

A cloud-generated simulator app is not an iPhone binary. An unsigned device build
is not a normally installable iPhone app. The local Xcode workflow performs the
necessary personal signing and installation; cloud CI does not bypass it.

## 11. Completion checklist and present evidence

POC completion requires a usable live app **and** validated replay, with a declared
capture envelope and independently checked results for both exercises. Imported
videos alone cannot qualify live camera operation or sustained phone performance.

- [ ] Real Xcode app and shared scheme committed; no paid account needed for CI.
- [ ] Camera and replay share pose normalization, filtering, and exercise logic.
- [ ] Both exercise definitions and uncertain outcomes have regression coverage.
- [ ] Model selection uses exercise-specific evidence, not only general benchmarks.
- [ ] Chin/bar and dip endpoint claims are tested or visibly unavailable.
- [ ] Held-out data, provenance, permissions, sample sizes, and exclusions recorded.
- [ ] Accuracy and coverage gates met independently for each supported exercise.
- [ ] Local Personal Team installation and actual phone sessions verified.
- [ ] Sustained performance qualified on declared phone/OS combinations.
- [ ] No private media, signing secrets, or unapproved model/data assets published.
- [ ] Comparison-only code and unused dependencies removed from the normal app.

**At creation of this document:** every implementation/qualification box is open.
No dataset has been downloaded, no iOS test has run, no phone has been profiled,
and no model is claimed to meet these targets. Remaining practical inputs are
rights-cleared real dip/pull-up evaluation material and later access to the owner's
local Xcode/iPhone environment. Source-code licence selection also remains open.

## 12. Primary references

Sources checked on 2026-09-26. Re-check SDK packaging, runner images, and account
rules at implementation time. These references support platform/dataset facts;
they do not establish this app's exercise accuracy.

- [A1] Apple: Detecting human body poses in images.
- [A2] Apple: AVCaptureVideoDataOutput late-frame discard behaviour.
- [A3] Apple: Face-contour region and face landmark coordinate conventions.
- [A4] Apple: Running apps on simulated or physical devices.
- [A5] Apple: Building and testing from the Xcode command line (archived reference).
- [A6] Apple: Developer account overview and Personal Team restrictions.
- [A7] Apple: Enabling Developer Mode.
- [M1] Google: Pose Landmarker models and landmarks.
- [M2] Google: Pose Landmarker iOS implementation guide.
- [M3] Google: MediaPipe iOS setup and platform support.
- [D1] Penn Action: Official dataset description and annotation format.
- [D2] RepCount: Official dataset description.
- [D3] TransRAC authors: Part-B release limitation.
- [G1] GitHub: Hosted-runner specifications and public-repository availability.

[A1]: https://developer.apple.com/documentation/vision/detecting-human-body-poses-in-images
[A2]: https://developer.apple.com/documentation/avfoundation/avcapturevideodataoutput/alwaysdiscardslatevideoframes
[A3]: https://developer.apple.com/documentation/vision/vnfacelandmarks2d
[A4]: https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices
[A5]: https://developer.apple.com/library/archive/technotes/tn2339/_index.html
[A6]: https://developer.apple.com/help/account/basics/about-your-developer-account
[A7]: https://developer.apple.com/documentation/xcode/enabling-developer-mode-on-a-device
[M1]: https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker
[M2]: https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/ios
[M3]: https://developers.google.com/edge/mediapipe/solutions/setup_ios
[D1]: https://dreamdragon.github.io/PennAction/
[D2]: https://svip-lab.github.io/dataset/RepCount_dataset.html
[D3]: https://github.com/SvipRepetitionCounting/TransRAC
[G1]: https://docs.github.com/en/actions/reference/runners/github-hosted-runners
