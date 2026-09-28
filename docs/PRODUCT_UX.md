# HangInThere product experience

This document defines the customer-facing experience for the current iPhone POC.
It deliberately separates **workout UX** from engineering diagnostics while preserving
the evidence needed to understand tracking failures.

## Product promise for the current POC

HangInThere supports live pull-up / parallel-bar-dip movement counting and recorded-video review on device.

The current customer-facing promise is deliberately narrow:

- show the athlete's detected body joints over the video;
- let the athlete choose pull-up or parallel-bar dip;
- let the athlete choose the anatomical arm that is clearest in the camera view;
- establish one fixed gripping bar/rail reference;
- count observed movement cycles when the supported evidence is available;
- clearly distinguish movement counting from form validation.

The app must **not** imply that chin clearance, dip depth, lockout, safety, or full
3D technique has been verified until those measurements are implemented and qualified.

## Experience principles

### 1. Video is the primary surface

The athlete should understand the session from the video itself. The preview therefore
owns the most important live information:

- selected exercise;
- movement count;
- visible skeleton;
- selected tracking arm emphasized over the rest of the skeleton;
- confirmed bar/rail edge;
- setup/tracking state.

Raw timestamps, backend names, frame numbers, and processing milliseconds are useful
engineering evidence but not primary workout information.

### 2. Progressive disclosure

The default screen should answer:

1. What exercise am I reviewing?
2. Is tracking ready?
3. What does the app currently see?
4. How many movement cycles has it observed?
5. What do I need to do next?

Technical details remain available under a disclosure section instead of competing
with workout information.

### 3. Honest capability language

Use:

- **Movement count**
- **Movement only**
- **Form scoring is not enabled yet**
- **Set bar to count**
- **Tracking paused**

Avoid wording such as "good rep", "bad rep", "correct form", or "verified rep" while
the required evidence is unavailable.

### 4. One obvious next action

The primary action changes with session state:

- home -> **Live workout**, with **Review recorded video** as the secondary path
- recorded video without bar -> **Preview**
- bar confirmed -> **Analyze**
- while running -> **Pause**

Bar setup is visually prominent until complete, but the athlete can preview the video
first to find a clear setup frame.

### 5. Tracking should be inspectable

The skeleton overlay is on by default because it gives immediate feedback about what
the app sees. The selected arm is emphasized; unrelated body segments are subdued.
The overlay can be hidden without affecting inference.

The fixed bar reference remains visible during analysis. If measurements become
unavailable, the UI reports a human-readable tracking state instead of silently
continuing with stale geometry.

## Current screen structure

### Home / empty state

The main entry now presents the two actual product workflows instead of treating
recorded replay as the whole app:

1. **Live workout** — primary action; opens the camera-based setup/workout flow.
2. **Review recorded video** — secondary action; imports an existing local video.

Camera permission is still contextual: simply opening the app does not request it.
The permission request occurs only after the athlete explicitly enters Live Workout.
The home screen states once that live and recorded analysis stays on device and that
the current result is movement-only rather than form scoring.

### Workout review

Order of information:

1. workout title and readiness badge;
2. video with skeleton/bar/count HUD;
3. playback controls and progress;
4. movement summary;
5. workout setup;
6. errors, when present;
7. collapsed tracking details.

This keeps the workout readable with one hand on a phone while preserving the
engineering evidence needed during development.

### Workout setup

The current setup consists of:

- exercise selection;
- tracking side selection;
- bar/rail setup;
- explicit bar status.

Selecting exercise or arm resets incompatible state. Confirming a bar rewinds the
source so every counted frame uses the same reference.

Bar selection is a short guided flow:

1. pause where the bar is clear;
2. drag a tight region over one gripping edge;
3. inspect the proposed observed edge;
4. use **Use this bar** as the primary action;
5. fall back to manual edge marking only when guided detection is unsuitable.

### Movement summary

The main summary shows the count prominently and keeps partial/interrupted attempts
secondary. These are diagnostic categories today, not coaching judgements.

At completion, a bar-calibrated analysis transitions into the dedicated results
state instead of exposing the counter's internal "Sequence finished" phase.

## Joint overlay policy

All detected skeleton geometry may be shown, but visual hierarchy matters:

- selected shoulder-elbow-wrist chain: strong emphasis;
- rest of skeleton: lower contrast;
- visible joints: small markers;
- no confidence numbers overlaid on the workout video;
- no labels covering the athlete unless needed for a specific coaching feature.

Future form features should add overlays only when they answer an athlete question,
for example an elbow-angle arc or chin/bar clearance indicator. Avoid turning the
video into a computer-vision debug display.

## Results experience

A completed bar-calibrated analysis now transitions into a dedicated result state.
The result surface contains:

- total observed movements;
- exercise;
- source duration;
- tracking coverage, defined as analyzed frames with a usable selected-arm measurement
  and confirmed bar reference divided by all analyzed frames while the reference exists;
- a disclosure timeline containing the source-relative timestamp of each counted movement;
- **Analyze again** and **Another video** actions;
- workout setup behind secondary disclosure.

This result remains explicitly **movement only**. Incomplete/interrupted attempts stay
under tracking diagnostics rather than being presented as coaching outcomes.

The movement timeline is evidence from the existing deterministic counter; it does not
add seeking, clip extraction, or a new acceptance rule. Individual movement replay can
be added later once seeking is introduced without compromising source-timestamp semantics.

Form-specific results should appear only after their corresponding measurement is
qualified.

The intended hierarchy remains:

**Count -> range/endpoint evidence -> consistency -> technique insights**

not a single opaque "form score."

## Live workout mode

Recorded-video review remains the qualified workout path, but the first live-camera
setup slice now exists as a separate reusable screen. It:

1. requests camera permission only when setup opens;
2. starts the rear wide-angle camera preview;
3. lets the athlete choose exercise and anatomical tracking side;
4. overlays a simple framing guide;
5. gives exercise-specific reminders for the selected arm, apparatus, body position,
   and keeping the phone stationary;
6. handles denied permission and missing rear-camera states without breaking replay.

The next live-analysis slice now runs Apple Vision directly on the camera stream with
one serial inference queue and AVFoundation late-frame discard enabled. It reports a
narrow automatic framing state:

- exactly one athlete is visible;
- the user-selected shoulder, elbow, and wrist are measurable;
- no opposite-arm substitution is allowed.

Framing readiness remains narrower than workout readiness, but live setup can now
freeze the latest analyzed camera frame and reuse the same guided/manual
`BarSetupView` used by recorded replay. The user explicitly confirms the gripping
bar or selected dip rail; the confirmed fixed line is then overlaid on the live
preview.

The live controller binds that calibration to the current exercise/arm setup.
Changing exercise or anatomical side invalidates the reference, as does an
incompatible analyzed image geometry. Setup reaches **Ready to start** only when:

- the rear camera is active;
- exactly one athlete and the selected arm are measurable;
- the matching fixed bar/rail reference has been confirmed.

This is still not a claim of automatic apparatus recognition: guided detection
proposes observed image edges inside the user-selected region, and the user chooses
the intended one. Phone-motion detection and form validity remain unimplemented, but the live
set lifecycle is now functional. Once setup is ready, **Start set** creates a fresh
source-timestamped `LiveSetSession` using the same production `ExerciseCounter`
as recorded review. Each analyzed live pose is consumed with the confirmed fixed
bar edge; inference failures interrupt the active attempt and late camera frames
are never fabricated or interpolated.

During a running set the camera remains the primary surface with a large movement
count and human-readable tracking state. **Stop set** freezes the counter and shows
movement-only results with duration, tracking coverage, and a movement timeline.
**New set** clears the previous result while preserving the current exercise/arm
selection and confirmed bar when it is still geometrically compatible.

Live lifecycle interruptions are handled conservatively because the fixed apparatus
reference assumes a stationary, continuous camera:

- leaving the foreground ends a running set, preserves already observed movements,
  and labels the result **Set interrupted**;
- foreground loss invalidates the frozen bar calibration and current framing;
- returning to the app may resume the camera, but never silently restores the old
  bar reference;
- a camera-session interruption or unexpected stop is detected by the live capture
  watchdog and ends a running set with an explicit interruption reason;
- incompatible incoming image geometry ends the set rather than continuing with a
  stale bar line.

These rules prefer an incomplete/interrupted result over a plausible but geometrically
invalid count.

Live bar calibration now also records a Core Motion attitude baseline from the same
instant as the frozen calibration frame. While the bar reference exists, the app
polls the latest fused device attitude and invalidates calibration after a
**provisional 1.5° orientation change sustained for 0.25 s**. A running set ends as
**Set interrupted** with a phone-moved reason; setup then requires a new bar
calibration. Brief threshold crossings reset if orientation returns before the dwell
time so sensor noise or a very short vibration does not immediately destroy setup.

Core Motion still cannot establish that the phone did not translate while returning
to the same attitude. Live setup now supplements it with a **static-background
image-registration guard** tied to the same frozen bar-calibration frame.

The calibration frame contributes four peripheral corner patches. During live
capture, Apple Vision translational image registration compares the current
peripheral patches against those references at a throttled rate. The framework-free
policy requires at least two patch translations to agree, so one corner contaminated
by a moving athlete can be rejected as an outlier. **Start set stays blocked until a
valid background consensus has been observed after calibration.**

The static-scene policy now separates two signals from the same four peripheral
reference patches:

- a **translational registration** component, used for lateral/image-plane movement;
- a **local homographic scale** component, used for toward/away or zoom-like change.

Each patch is registered independently. Translation remains the cheaper affine
measurement already used by the lateral guard. Scale comes from a homographic
registration of the same current/reference patch and therefore does not depend on
translation registration succeeding for that patch.

A provisional image-space gate invalidates calibration when common translation
exceeds **0.8% of the image short side for at least 0.25 s**. A separate provisional
gate invalidates calibration when the consensus radial scale term exceeds **1.2% for
at least 0.25 s**. Radial scale requires at least three agreeing peripheral patches,
which lets one athlete-contaminated corner remain an outlier. Brief threshold
crossings reset instead of immediately destroying setup.

The homographic scale term reduces the most obvious toward/away or zoom-like blind
spot. It does add homographic registration work, so the device-qualification path
must measure its latency and thermal cost separately from body-pose inference.
A running set records **scene shifted** versus **scene scaled** separately so
physical-device tuning can distinguish which guard fired.

This is still not a full camera-pose estimator. Depth-dependent parallax, lens
switches, nonuniform perspective changes, low-texture backgrounds, and independently
moving scene content can make the simple translation + radial-scale model ambiguous.
The UI therefore uses **Camera position / background alignment** language rather
than claiming 6-DoF camera localization. All Core Motion and image-registration
thresholds remain engineering defaults pending physical iPhone qualification.

The qualified live workflow is now exposed from the main customer entry screen as
the primary action. It is presented full-screen so setup, the running set, and
results form one focused task; closing it returns to the home/recorded-review flow.

The intended complete live flow remains:

1. choose exercise;
2. place the phone;
3. show a framing guide;
4. confirm that the athlete/selected arm is measurable and visually check the apparatus;
5. acquire and explicitly confirm the fixed bar reference;
6. start set (implemented);
7. provide restrained live movement count/tracking feedback (implemented);
8. end set manually; automatic stop remains future work;
9. show movement-only results (implemented).

The live workout screen should stay substantially simpler than the review/debug
screen: large count, clear tracking state, and minimal controls.

## Camera guidance

Supported viewpoints need to become product concepts, not hidden model assumptions.

The app should eventually provide:

- an example silhouette/framing guide;
- "move farther back" / "keep selected arm visible" guidance;
- confirmation that the selected athlete/arm is measurable (implemented for live setup);
- confirmation that the bar/rail is visible (not yet automatic);
- warning/invalidation for sustained phone orientation change plus multi-patch background translation/radial scale after calibration (implemented; full 6-DoF stability remains unverified);
- exercise-specific camera recommendations.

Do not expose arbitrary CV thresholds to customers.

## Accessibility

Use native SwiftUI controls whenever possible. Primary controls need comfortable
touch targets and meaningful VoiceOver labels. Do not rely on overlay color alone to
communicate ready/error state.

The skeleton itself is decorative for accessibility; meaningful tracking state is
provided as text. Dynamic Type must not make workout controls overlap the video.
The screen should remain usable in portrait first, with landscape review considered
once the analysis flow is stable.

Apple recommends at least 44x44 pt interactive hit regions and clear hierarchy for
primary actions. The implementation keeps the main playback/import/setup actions at
large system-control sizes.

## Privacy and trust

For the POC:

- imported videos remain on device;
- there is no account;
- no analytics/backend is required;
- failure reports exclude video, filenames, landmarks, and device identifiers.

The customer UI should mention privacy once where it increases trust, not repeat
legalistic text throughout the workout.

If cloud features are added later, upload state and retention must be explicit.

## Failure design

Failures should map to actionable language:

- bar missing -> "Set the bar reference"
- selected arm hidden -> "Selected arm is not clear"
- multiple people -> "Keep one athlete in frame"
- timing/source interruption -> "Tracking paused"
- inference/backend failure -> a concise error plus optional technical report

Never silently produce a count from stale or fabricated measurements.

## Visual language

Use system typography, materials, SF Symbols, and adaptive light/dark appearance.
The visual identity should feel athletic and precise rather than clinical:

- video dominates;
- large rounded movement numerals;
- restrained accent use;
- compact status badges;
- minimal borders;
- no dashboard grid of low-value diagnostics.

A custom brand palette/icon can come later without changing information architecture.

## Product-quality gates before customer release

The UI can look polished before the underlying measurement is release-ready. Treat
these as separate gates.

Experience gates:

- physical iPhone interaction review;
- Dynamic Type and VoiceOver review;
- portrait/landscape checks;
- light/dark appearance;
- long filenames and localization;
- interruption/background/resume behavior (implemented conservatively; physical-device review pending);
- video import/cancel/error flows;
- bar setup usability with real users.

Measurement gates:

- source-separated controlled pull-up and dip counting;
- multiple athletes/body types;
- supported camera viewpoints;
- apparatus variation;
- physical-device performance/thermal behavior;
- qualified chin/bar and dip-depth evidence before form verdicts.

The engineering diagnostics remain valuable throughout development, but they should
stay behind progressive disclosure in the customer experience.
