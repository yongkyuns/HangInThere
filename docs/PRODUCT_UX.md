# HangInThere product experience

This document defines the customer-facing experience for the current iPhone POC.
It deliberately separates **workout UX** from engineering diagnostics while preserving
the evidence needed to understand tracking failures.

## Product promise for the current POC

HangInThere supports live pull-up and dip movement counting on parallel rails or a shared straight bar, plus recorded-video review on device.

The current customer-facing promise is deliberately narrow:

- show the athlete's detected body joints over the video;
- let the athlete choose pull-up or dip;
- use both measurable arms for pull-ups, with one-arm fallback when obscured;
- let the athlete choose the anatomical grip for dips;
- establish fixed grip references automatically for pull-ups and dips, with manual fallback;
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
- both usable arms emphasized equally over the skeleton;
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
- **Looking for bar** / **Set bar manually**
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
the app sees. Both usable arms are emphasized equally in either exercise; unrelated body segments are subdued.
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
- tracking side selection for dips;
- bar/rail setup;
- explicit bar status.

Selecting exercise or arm resets incompatible state. Confirming a bar rewinds the
source so every counted frame uses the same reference.

Pull-up setup runs automatically during playback. One athlete with both raised
hands guides image-edge detection; a stable observed gripping edge locks and the
video rewinds once, then continues counting from the start. Dip setup also acquires
observed support automatically. Views without clear automatic apparatus evidence
use the short manual flow:

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

### Per-pull-up video progress

A compact meter at the bottom of live video and recorded review shows the current
rep number, ascent progress, and a short cue: **Keep pulling**, **Pull higher**, or
**Hold here**. Both usable arms constrain it. The weaker unmet angle/travel gate
controls the fill; it reserves the final 10% for the same source-time endpoint
hold as counting. Progress can fall when the athlete lowers before reaching the
endpoint. It never displays 100% until the counter emits the movement.

At a count the meter turns green and says **Counted**. A separate thin reset meter
fills as the athlete extends back to a hang and holds it. The next rep starts at
zero after the counter rearms. Starting-position acquisition and tracking loss
have explicit states; missing evidence clears the ascent meter. This visual does
not qualify chin clearance or form.

### Per-dip video progress

Dips use the same compact video-edge meter in live workouts and replay. The first
half follows lowering and the witnessed bottom hold; the second follows returning
to the acquired top and its hold. Fixed grip-to-shoulder travel and the available
arms constrain progress using the counter's thresholds. Cues switch from **Keep
lowering** / **Lower further** to **Push back up** / **Extend your arms at the top**.
The middle mark separates descent from return.

Only a counted movement displays 100% and green **Counted**, briefly retained on
source time while the athlete stays at the top. The next descent starts a new rep;
a shallow return cannot earn second-half progress. Missing or invalid evidence
clears the meter and shows a recovery cue. This describes movement evidence, not
anatomical dip depth, lockout, or form qualification.

## Joint overlay policy

All detected skeleton geometry may be shown, but visual hierarchy matters:

- both usable shoulder-elbow-wrist chains in either exercise: equal strong emphasis;
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
- tracking coverage, defined as analyzed frames with usable exercise-specific arm measurements
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

The main live screen is a full-screen, aspect-fitted camera canvas with no
navigation title, introduction, cards below the video, or scrolling. Controls
respect the screen safe areas. The detected pose and fixed bar share the camera's
full viewport mapping; the feed stays uncropped so overlay geometry remains aligned.

The top edge holds close, exercise, rep count during/after a set, and a settings
button. The lower edge holds a short setup cue and camera switch before a set,
progress and **Finish set** while running, and **Results / New set** at completion.
A countdown appears over the video only during hands-free acquisition.

The settings button opens **Workout / Diagnostics** tabs in a dismissible sheet.
Exercise selection, hands-free preference, manual bar adjustment, and placement
help live under Workout; results include the movement timeline and interruptions.
Qualification metrics and opt-in debug recording/export live under Diagnostics.
Opening this sheet cancels and pauses the automatic countdown; dismissing it
requires a fresh countdown. A running set continues tracking while details are open.
Manual calibration and file exports are presented by the details sheet itself.


Recorded-video review remains the qualified workout path, but the first live-camera
setup slice now exists as a separate reusable screen. It:

1. requests camera permission only when setup opens;
2. starts the selected rear or front wide-angle camera preview;
3. defaults to Auto exercise detection, with manual exercise selection and anatomical tracking side for dips in settings;
4. overlays body joints and framing status, without a fixed rectangle over the athlete;
5. gives exercise-specific reminders for tracked arms, apparatus, body position,
   and keeping the phone stationary;
6. handles denied permission and missing selected-camera states without breaking replay.

The next live-analysis slice now runs Apple Vision directly on the camera stream with
one serial inference queue and AVFoundation late-frame discard enabled. It reports a
narrow automatic framing state:

- exactly one athlete is visible;
- pull-ups have at least one measurable shoulder/elbow/wrist chain;
- dips require the user-selected chain and cannot substitute the opposite arm.

Auto requires one athlete with both usable arms in a starting pose for at least
0.6 seconds across four fresh analyzed frames. Raised hands with extended arms
identify a pull-up hang; hands below shoulders in extended top support identify
a dip. Contour acquisition runs alongside starting-pose confirmation, so a
settling hang can supply a bar search before its extended-arm dwell completes.
The header shows **Auto · detecting**, then **Pull-up detected** or
**Dips detected**. Identity alone supplies no apparatus: existing contour and
camera-stability checks must still qualify the setup before the three-second
countdown. A changed/unclear pose or source-frame outage clears the pre-set
identity, reference, and countdown. The chosen exercise is locked when a set
starts and is reconsidered after **Next set**. Manual exercise selection remains
available; recorded review retains its explicit exercise selection. Opt-in debug
recording freezes its already detected configuration for consistent exports.

Framing readiness remains narrower than workout readiness. Live pull-up setup
automatically looks for a stable observed bar near both raised hands and overlays
the locked edge. Hands-free start is on by default for both exercises: the athlete
holds an extended hang for pull-ups or steady top support for dips through a
visible and spoken **3, 2, 1, go** countdown, then begins moving. The dip countdown
shows **Hold your top support**. Crossed legs are optional; unavailable leg joints
never prevent starting. Grip loss, shoulder movement during dip acquisition,
missing frames, unstable calibration, manual setup,
or camera changes cancel the countdown. Turning off **Hands-free start** restores
the **Start set** button. Finished sets require **Next set** before arming again.
**Set bar manually** freezes the latest analyzed frame and opens the same guided/manual
`BarSetupView` used by recorded replay. Dips first attempt automatic support setup.

A compact camera-switch button sits inside the lower-right of the live preview,
beside the setup cue, without adding a row above the video. It toggles rear
and front cameras before a set, remains available if the selected camera is
unavailable, and is locked during active debug recording. It is hidden during a
set and on the results screen. Both cameras use portrait, unmirrored preview, analyzed frames,
bar snapshots and debug movies. The preview uses aspect fit, matching the bar
overlay even if a camera produces a different aspect ratio. A camera change stops
capture, rebuilds its input/output connections, and resets framing and calibration;
frames queued by the old camera cannot enter the new session.

The live controller binds that calibration to the current camera/exercise/arm setup.
Changing camera, exercise or anatomical side invalidates the reference, as does an
incompatible analyzed image geometry. Setup reaches **Ready to start** only when:

- the selected camera is active;
- exactly one athlete and usable exercise-specific arm chains are measurable;
- the matching fixed bar/rail reference has been confirmed.

Pull-up setup associates observed image edges with the athlete's raised hands;
guided/manual setup remains available for unclear views. Phone-motion
checks invalidate changed calibration; form validity remains unverified. Once setup
is ready, the hands-free countdown or manual **Start set** creates a fresh
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

The static-scene policy now separates two registration signals:

- **four peripheral translational registrations**, used for robust lateral/image-plane
  movement with corner consensus;
- **one full-frame homographic registration**, used for toward/away or zoom-like
  scale change.

The first scale implementation tried to infer radial scale from corner translations,
then tried local homographies on each corner. Synthetic qualification showed both
approaches could leave only two usable scale witnesses. The current design keeps
corner translations for robust lateral movement but uses one globally constrained
homography for scale.

A provisional image-space gate invalidates calibration when common translation
exceeds **0.8% of the image short side for at least 0.25 s**. A separate provisional
gate invalidates calibration when the global homographic scale term exceeds **1.2%
for at least 0.25 s**. Start remains blocked until both a valid corner-translation
consensus and a valid low-scale homography have been observed after calibration.
Brief threshold crossings reset instead of immediately destroying setup.

The global homographic scale term reduces the most obvious toward/away or zoom-like
blind spot. It does add homographic registration work, so the device-qualification
path must measure its latency and thermal cost separately from body-pose inference.
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
3. show framing status and detected body joints;
4. confirm that the athlete/selected arm is measurable and visually check the apparatus;
5. acquire the fixed bar automatically or confirm it manually;
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

## Physical-device qualification evidence

Live Workout exposes a collapsed **Device qualification** disclosure for engineering
use. It records at most one bounded metrics snapshot per second and can share a
local JSON report containing:

- body-pose Vision latency;
- full static-scene registration latency/failures;
- analyzed and dropped frame counters;
- Core Motion orientation delta;
- peripheral background translation plus global homographic-scale metrics;
- thermal state;
- set phase/count/tracking outcome;
- the exact compiled stability thresholds.

It does **not** export video, images, pose landmarks, imported filenames, location,
account data, or device identifiers. The engineering disclosure exports the report
as an actual timestamped JSON file through the system file exporter so the result
can be saved to Files/AirDrop and passed directly to the offline analyzer.

The repeatable physical-iPhone procedure is documented in
`docs/DEVICE_QUALIFICATION.md`. Until those runs are collected, the current
1.5° orientation, 0.8% translation, and 1.2% global homographic-scale thresholds
remain engineering defaults rather than validated limits.

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

### Automatic dip support

Holding top support with both arms and hands visible triggers local contour
searches beside each hand. The policy first requires two distinct observed
silhouette edges for each handle. Through partial occlusion, only a fresh edge
matching an already witnessed handle can maintain acquisition. Both grip/rail
observations must stay stable for at least four observations across 0.9 seconds.
It retains the selected anatomical rail as a fixed reference; it never draws a
rail between wrists or uses a rack bounding box. Phone/scene calibration and
freshness checks are shared with automatic pull-up setup. A three-second top-hold
countdown starts after setup becomes ready. A separate common crop also permits
a shared straight bar: every sample must observe a fresh paired silhouette with
finite support near both hands, its center on/below both wrists, and no distinct
eligible alternative. Losing that pair restarts dwell. Independent rail acquisition
keeps priority; both modes retain an actual observed edge for the selected grip.
No manual confirmation is required when acquisition succeeds; unclear views
retain the manual settings fallback.
