# Automatic workout analysis

The first implementation covers pull-ups and dips. Camera and pose inference
remain native (Apple Vision and MediaPipe). Both shipping apps now use the Kotlin
module for **new motion features, rep metrics, automatic readiness, set boundaries,
recovery policy and storage projections**. Xcode links its static `WorkoutCore`
framework. There is no second Swift implementation of these new algorithms.

The established Swift counting/progress implementation remains the iOS policy-v8
reference; Android uses the matching Kotlin counter. Common analysis accepts that
counter's phase/event stream. Existing per-frame parity oracles continue protecting
this interface. Moving the remaining counter/setup facade to Kotlin is a separate
migration; this change does not claim that either pose SDK is shared.

## Attempt evidence and uncertainty

Live setup uses shared `WorkoutFramingGuidance` in the existing video caption.
It distinguishes clipped/missing arm joints, a poor view of one arm, weak pose
visibility, and malformed SDK evidence. A 3% margin of the image's shorter side
suggests more room around measurable arms; it is a presentation default, not an
eligibility threshold. Native Apple transport and Android use the same raw-joint
policy, preserving duplicate joints and scores rather than sanitizing them.
For unconfirmed pull-up or Auto setup, measurable arms can still leave too little
space above the hands for the bar search. Guidance policy 2 uses that existing
search reach (the greater of 12 pixels or 12% of the shorter image side) to suggest
leaving overhead room. Arm visibility advice takes priority; the equipment hint
uses the same debounce and caption, including while the bar edge is unclear.
It does not assert that a bar exists, change the search crop or delay readiness.
Advice clears on fresh usable framing and camera/selection invalidation, and is
hidden during running or finished sets. A confirmed manual dip still permits
the selected arm; automatic acquisition still needs both arms. Advice never
changes setup, count, phase-credit or camera-stability gates and cannot repair
an obscured arm in a saved video. The original failed evaluation clips remain
fatal accuracy checks. Common tests, shipping Apple FFI contracts/performance,
and narrow/wide overlay renders cover this path in CI.

Shared `WorkoutFramingFeedback` waits for 0.30 seconds of one consistent camera
instruction on consecutive source frames. Clear framing, athlete identity and
malformed pose errors take effect immediately. Changed instructions, duplicate
or regressing timestamps, gaps beyond the existing 0.35-second frame continuity
limit, configuration/resolution changes and lifecycle invalidation reset this
presentation state. Apple passes the original source clock through FFI and
clears its observable hint synchronously on bar/camera/set invalidation, without
waiting for another pose. This engineering default reduces transient direction
changes; it does not delay set readiness or credit elapsed wall-clock time.

Roadmap [#31](https://github.com/yongkyuns/HangInThere/issues/31) begins with
`WorkoutEvidence`, a shared, versioned review projection. Both apps distinguish
completed cycles, active attempts, counter-observed partial movements, acceptance
without a recorded return, voluntary endings and interrupted observations.
An interruption describes recording evidence, never a strength/form failure.
Counter acceptance and completed-cycle eligibility remain unchanged.

Observed-time and each arm's coverage are independent source-time fractions, not
calibrated confidence scores. Missing/invalid values stay unavailable. Assessment
uses full-resolution summary integrals and close reasons; downsampled or expired
curves cannot invent return evidence. It is recomputed for older summaries and
recovered journals without a storage migration. Apple transfers only the bounded
rep summary on review actions; Android calls the same policy directly.

Range comparisons require both arm ranges and the established 80% arm-time
coverage gate. Active attempts and interrupted prefixes are excluded from best
range, while their observed measurements remain inspectable. Camera projections
do not verify chin clearance, dip depth or anatomical form. The coverage gate is
an engineering rule pending held-out measurement validation, not a quality score.
Native goal persistence/FFI belongs in `App/`; Swift `Analysis/` remains free of
`WorkoutCore` imports for the production policy/replay tools on Linux and macOS.

## Lifecycle and evidence

Setup and active tracking require current background registration evidence.
Both scene monitors qualify only observations with translation consensus and a
valid scale measurement. Missing measurements revoke readiness immediately;
duplicate, regressing or nonfinite source clocks and observation gaps beyond
0.8 seconds also reset qualification and movement dwell. The 0.8-second scene
limit accommodates the native 0.33-second registration cadence; body-frame
continuity retains its separate 0.35-second limit. Translation and scale can
independently prove camera movement, but missing one signal breaks its own dwell.
Confirmed movement stays latched until explicit recalibration.

Pose frames can carry the latest cached scene observation between registrations.
The shared runtime delivers each exact timestamp/patch/scale observation once;
reusing that cache cannot renew its source age or advance movement dwell. Changed
evidence at the same registration time still reaches the clock-fault check.
Calibration, setup invalidation and replay rewind clear the delivery cache.

Each running frame receives reference geometry only while scene evidence is
qualified at that frame's source time. Unavailable reference evidence interrupts
the live counter before a cached dip support axis can be reused. Analysis retains
the actual pose and interruption, while completed-rep and hold credit stop through
the unknown interval. The observed apparatus remains available for reacquisition.
Loss and recovery contracts cover both exercises in common tests, the independent
Swift setup oracle and the shipping Apple bridge. Counting thresholds are unchanged;
these guards cannot restore an obscured arm or compensate for camera zoom.

Phase direction uses shared source-time stabilization (activity metric version 3).
A bounded 64-sample, 0.24-second window fits the observed piecewise-linear elbow
trajectory with time weighting, so redundant samples do not change a linear
motion's rate. Motion/hold thresholds have hysteresis, and reversals require
0.08 seconds plus 3 degrees of coherent observed travel. Confirmation anchors
are independent of the window so slow movement can eventually qualify.
Fitted confirmation starts at its first observed candidate, never retroactively
at the preceding frame; one low-frame-rate outlier cannot earn a hold/reversal.
Brief observed stationary intervals can establish holds without waiting for an
older moving window to decay. Changing visible arms, unusable input, release,
timestamp faults and source gaps reset this evidence.

Version 3 keeps an observed bilateral endpoint separate from direction-filter
restarts. Losing/reacquiring an arm midway through a return cannot turn the new
angle into a top/bottom hold. A known endpoint can be reacquired with both arms;
an endpoint hidden before bilateral observation remains unknown for that cycle.

Rep metric version 2 publishes joint peak and outbound/return split timings only
for a continuously comparable bilateral trajectory. A missing arm can make the
lowest remaining paired sample occur during the return; that sample must not be
reported as the cycle peak. Such records keep measured counts, completed-return
evidence, available per-arm angles and coverage, but their joint peak/split timings
and bilateral peak-offset timings stay null. High overall coverage cannot recover
an arm's hidden endpoint. Existing metric versions remain immutable and separate comparison keys.

These are descriptive engineering defaults pending annotated phase validation.
Transitions can lag while evidence accumulates; the timeline is not backdated
across that delay. Raw angles, policy-v8 counter inputs and
completed-cycle eligibility are unchanged. Hang/top-support credit still uses
the existing bilateral endpoint checks on fresh source intervals, independently
of the direction fit. Saved activity version 1 remains readable; its measurements
are preserved rather than silently reclassified.

After automatic identity qualifies, shared `WorkoutSetupRecovery` can retain the
observed setup through one-arm loss for at most 0.35 source seconds from the last
bilateral pose. The current arm must still match the exercise, its prior wrist
within 2% of the image short side, and shoulder within 8%. Missing frames cannot
renew this deadline. Crowds, no person, changed resolution, contradictory geometry,
invalid/reversed/duplicate clocks, expiry and independently detected camera motion
invalidate recovery. Both apps reset readiness during the dropout and require
fresh bilateral evidence before starting. No missing frame earns identity dwell,
apparatus observations, practice time or a rep. Android calls the common policy;
iOS uses its source-tick/raw-joint bridge rather than a second recovery algorithm.

- Start observing after actual apparatus, exercise identity, camera motion and
  scene checks qualify. The new readiness gate uses 0.12 seconds of consecutive
  source frames; a qualified prior frame seeds the counter's existing starting
  baseline. No three-second countdown or leg gesture is required. Frames before
  apparatus/camera qualification cannot retrospectively earn a baseline.
- On the actual live-set start, show a large **SET STARTED** overlay for two
  seconds and speak "Set started" once. A persistent tracking indicator remains
  during the set. Shared feedback identity/timing policy prevents duplicate cues;
  native adapters own drawing, speech and cancellation. This UI monotonic clock
  never advances counting/readiness/set boundaries, and feedback never delays the
  first rep. Replay does not announce live starts; ended/background sets cancel
  feedback, including late Android speech initialization.
  Apple visibility reads the shared remaining UI-time budget even while its
  timer callback is queued. An absolute monotonic deadline schedules drawing
  invalidation, and cancellation/set identity prevent an old callback from
  hiding a newer set's cue. The clock is injectable for deterministic native
  tests and never substitutes for source presentation time.
- A rep opens on departure from the acquired start. An accepted pull-up reaches
  its counting milestone at the upper endpoint; it closes its motion record on
  the observed return. A dip is accepted on the return to top support. The count
  policy, geometry gates and confidence thresholds remain version 8.
  Dip return witnesses are revoked by a reliable angle that contradicts top
  support. A later missing elbow cannot restore that witness or complete its
  dwell; fresh observed extension is required. This applies to both completed
  cycles and partial returns. An uncontradicted top may still be sustained by
  the existing shoulder/wrist evidence during an elbow-confidence gap.
  CI runs these common regressions on the JVM and the host Kotlin Native target,
  alongside the independent Swift counter oracle and native Apple integration.
  Counter dwell, per-arm baseline acquisition, observation outages and frame-gap
  checks retain source timestamps and subtract source ticks before converting
  to seconds. Exact 120 ms endpoints and 350 ms allowed gaps therefore use the
  same eligibility at different source origins, without rounding up shorter
  holds or tolerating longer gaps. Invalid, non-increasing and overflowing
  timestamp differences interrupt evidence. Mixed timescales retain the existing
  elapsed-time helper's floating-seconds fallback; exact cross-clock boundaries
  are not promised. Published event seconds and the version 8 thresholds remain
  unchanged.
- Preserve an accepted rep even when its full return is unavailable. Missing
  return/peak measurements are null. Partial and interrupted attempts remain
  distinct records; they do not contribute to accepted counts. Analytics use the
  shared `WorkoutAnalytics` rule: a rep must be accepted and close with an
  observed return to its starting position. Unfinished attempts, including an
  accepted pull-up without its return, are excluded from completed-rep charts and metrics,
  saved-set rep totals and future aggregates. Raw counter evidence remains in the
  journal; older saved sets use the same rule without a schema migration.
- A set completes after one second of continuous **bilateral visible grip
  release**, measured against the fixed observed apparatus and each hand's
  observed grip at the acquired start. A hand remaining on its separate dip rail
  cannot be treated as released merely because it is far from the selected rail.
  This is conservative
  disengagement evidence, not a claim that a static posture means rest. Long
  holds do not split sets.
- Three seconds of unavailable tracking or missing observations ends a set as
  an interruption. Watchdogs can interrupt but cannot earn movement/dwell or
  declare successful completion. Resume setup on new observations after release
  or tracking loss. Camera/scene faults remain explicit interruptions.
- Auto exercise identity stays locked during a set and is reacquired for the
  next set. Unknown/ambiguous identity and multiple people prevent acquisition.
  The current one-person policy does not provide biometric athlete identity.

## Shared contracts

`MotionFeatureExtractor` converts current upright, unmirrored poses and genuine
reference geometry into nullable measurements. `WorkoutAnalysis` consumes these
with counter phase/events. `AutomaticWorkoutStart`, `AutomaticSetBoundary` and
`WorkoutLifecycle` contain the new app decisions. `WorkoutAnalysisBridge` is the
small Apple ABI; native pose joint names are checked against its schema before
index mapping. Kotlin instances stay on the native session's serial owner.

Immutable `RepRecord` and `WorkoutSetRecord` carry stable IDs, source timestamps,
backend/model revision, selected side, source image/reference geometry, acceptance
reason, count policy version, metric version, schema version and
lifecycle version. Native views/storage receive values, not a mutable engine.
`trackedAtEpochMilliseconds` captures tracking start once from an injected native
wall clock, for dated history. Source times describe the recording/camera timeline;
the wall clock never advances counting, readiness or duration metrics. This additive
nullable field preserves older records. Replay tracking time does not claim the
original video's capture date.

One shared source timeline retains the first finite timestamp and the latest
increasing timestamp for session bounds and the Android source-clock watchdog.
Out-of-order, duplicate and invalid timestamps still reach the existing counting
error path; they cannot shorten retained rep evidence or move the watchdog behind
a newer frame. The automatic boundary clears dwell on rejected timestamps while
preserving its latest source time, so older frames cannot rebase release/outage
dwell. Within-set inference interruptions also break continuity while retaining
that time. Replay rewind and a new session explicitly reset their timelines.

Metrics include:

- Observed start, angle extreme, acceptance, completed return and close times.
  The extreme uses simultaneous bilateral angle samples rather than the counting
  threshold. Missing bilateral evidence yields an unavailable global peak.
- Outward/return duration, each arm's min/max and time-weighted mean, per-arm
  observed duration/coverage, usable duration coverage, normalized vertical shoulder
  excursion and bilateral observed minimum-angle timing difference.
- Trapezoidal integration only over valid adjacent source intervals of at most
  0.35 seconds. Sampling frequency does not change a linear trajectory's mean.
  Per-arm gaps remain gaps, including in display-curve downsampling.

These are projected 2D observations. They do not qualify depth, lockout, force,
power, fatigue, calories or anatomical form. Perspective changes affect angles
and projected excursion; comparisons need consistent camera placement.

## Persistence and bounded work

Native stores own file I/O. `WorkoutRecordCodec` owns common summary, journal
merge and recovery rules. `WorkoutWriteQueue` is the shared serial-owner outbox;
only successful atomic writes acknowledge its delivery token. Failed writes stay
queued across automatic set transitions and retry at a bounded cadence. Native
stores serialize all queue/file access (Swift actor, Android reentrant monitor).
Accepted and closed rep deltas are atomically journaled;
newer records can complete a return but cannot erase accepted evidence. A launch
can recover an unfinished set with its counts and metrics as an interruption.
Reordered deltas union distinct reps; terminal evidence dominates queued in-progress
updates. Idempotent final retries still finish journal cleanup and feature pruning.
Android restores committed atomic backups before merging, sorting or paginating
history, including a backup-only latest journal after older-device power loss.
Completed summaries are immutable/idempotent; finalized evidence wins over stale
queued drafts. Imported replay evaluation does not automatically enter the live
workout history.

Keep at most 256 display points per rep; integration still includes the full
observed motion. Only record changes cross the JSON bridge, with constant-size
rep deltas during tracking. The active set retains one compact record per attempt.
No video/frame pixels are retained by analytics. Completed detailed curves expire
at 50 sets or 50 MiB; compact rep summaries remain durable. The review loads the
most recent 50 sets. A database/index with pagination and calendar grouping can
replace the native store without changing analysis or the versioned JSON records.

## Review UI

The camera remains the live workout surface. Analysis is a read-only hierarchy:
**28-day history → workout → set → rep**. A set opens with its completed result,
an observed-consistency rating, key metrics and one automatic coaching
focus. Phase timelines, grip review, arm curves and recording evidence appear
only when requested. Context forms, manual plans, corrections and storage are
secondary menu destinations; none is required to see progress or a next step.
History stays in the set-summary toolbar so long rep lists cannot bury the overview.
Read-only replay review can switch between supplied recordings through **Sets**,
without mixing a current hold with timing from an older set.

`WorkoutJourney` owns the projections and coaching in common Kotlin. Native DTOs
and SwiftUI/Compose views format and navigate those projections. The projections
do not change counts or saved measurements and never run on the camera frame path.
Both historical summaries and drills apply the same correction ledger. Original
measurements remain available under recording details.

Ratings describe consistency, not anatomical form or effort. Two or more completed
reps with sufficient bilateral/time coverage are needed for consistency analysis.
Range differences greater than 20% of the median or total-duration differences
greater than 35% produce a specific focus. Missing coverage is unknown; coverage
below 80% produces **Limited view**, not a poor movement grade. Holds and attempts
remain **Practice**; unfinished attempts never contribute to completed timing or
range statistics. Unknown timing stays unavailable rather than becoming zero.

Calendar activity uses each source set's local day, including workouts crossing
midnight. Presentation merges cannot move activity to another date. Unique workout
totals do not double-count a session spanning two days. The first 100 workout
summaries are bounded; older and selected-day history use revision-bound cursors.
Expired curves retain timing summaries and an explicit unavailable state.

## Validation and qualification

Run:

```sh
python3 scripts/prepare-workout-oracle.py
python3 scripts/prepare-setup-oracle.py
./gradlew -Pworkout.appleOnly=true :shared:jvmTest
./scripts/test-workout-bridge.sh
./scripts/render-workout-review.sh
./scripts/test-apple-host.sh
./scripts/test-ios.sh
```

JDK 17 is required for Apple shared-engine builds. Apple builds do not require an
Android SDK. Xcode's build phase selects the correct framework architecture and
configuration; a generic simulator build combines both supported architectures.

Shared tests cover milestones, incomplete returns, actual extrema after acceptance,
time weighting, occlusion/gaps, long holds, bounded curves, automatic start→rep→
release→next set for both exercises, missing motion, watchdogs, reordered journal
recovery, rejected-frame source bounds and acknowledgement races.
The shipping Apple bridge checks both counters, active rep deltas, serialization,
atomic recovery/idempotence/feature retention, and a **5 ms synthetic host p95**
budget. This excludes camera/model/device performance. Android CI additionally
runs native storage/UI tests (including overlapping owners, write failures,
post-commit retries and watchdog saves without another frame) and tests production natural readiness plus the
unchanged legacy 3/2/1 countdown on identical real-image observations.

The two authorized phone clips are local development regressions: unchanged saved
Vision predictions and fixed references retain 2 pull-ups/4 dips over all 690/1124
source frames. This is not fresh inference, independent motion-metric annotation,
held-out accuracy or physical-device performance qualification. No private media,
observations or credentials are added to public CI/artifacts.

The temporal diagnostic has two independent replay modes. The original raw-pose
counter check retains its frozen apparatus references, including unavailable
references. The selected-exercise check runs the production Apple
`ReplayController`, automatic contour acquisition and shared counter/analytics
with each frozen clip's explicit exercise and side. Legacy repetition manifests
still default to pull-up/left; new manifests select dips explicitly. This mode
does not exercise live Auto identity or readiness and cannot qualify them.

Selected replay records unchanged raw observations by source PTS, including
acquisition/rewind, with people-count histograms, per-arm rejection reasons,
bilateral coverage, apparatus-search eligibility and consecutive starting-pose
candidate duration. These diagnostics distinguish missing pose support from
apparatus acquisition failures without selecting people or altering confidence.
Full source-clock coverage and compatible raw totals are required. Interrupted
prefixes cannot publish future returns; unavailable setup retains every missed
movement. Reports bind the compiled sources, framework, executable, media and
frozen reference hashes; a local modified checkout is marked explicitly.

CI separates selected-replay integrity from movement accuracy. The accuracy job
re-scores every original event with `--require-exact-events` and fails on misses,
extras or unavailable setup. Successful compilation, completed decoding and
diagnostic output never waive those failures. Artifacts contain scalar evidence
and public references, without videos. Foreground selection or focused-pose
experiments require demonstrated counting benefit and athlete-continuity/negative
validation before changing the production detector.

`ForegroundAssociation` is a shared, experimental spatial policy; neither native
detector calls it yet. It accepts source-pixel person rectangles rather than arm
angles, exercise identity or rep counts. Four consecutive observations spanning
0.6 source seconds must identify a uniquely dominant region. Subsequent matches
must stay within both the preceding region and the acquired size/movement
envelope. Missing or overlapping candidates supply no selected index and cannot
renew the 0.35-second continuity deadline. Expiry stays lost until an explicit new
source generation. These version 1 limits are engineering proposals awaiting
athlete-annotated validation; identical geometry cannot prove personal identity.

The serial native owner must advance that generation together with setup,
counter and grip-cache resets on rewind, camera/coordinate or model changes.
Stale generations cannot mutate the current association. Exact rational source
comparisons support native timescale changes without absolute-time rounding at
the continuity/acquisition limits, including large source origins. This bounded
comparison helper does not change existing counter or analysis timing policies.
Associated decisions retain immutable source, timestamp, candidate index and
region values for future crop requests. An adapter must additionally validate
returned inference provenance and the person within the crop; a region alone
provides neither identity nor apparatus evidence. `PersonInferenceRegion.restore`
accepts body-only predictions, preserves native scores/people and rejects attached
grip metadata whose coordinates would otherwise remain in the crop frame.
Auxiliary hand inference follows restoration. Android same-PTS refinement also
requires a separate native estimator because VIDEO inference rejects repeated
millisecond timestamps. Raw, cropped and restored observations must stay distinct.

A development screen sampled eight predetermined frames from each of the four
frozen public temporal clips. Native person-region crop re-inference recovered
no bilateral arm chain in either rear or side dip sample; the controlled dip
changed from two to three bilateral frames out of eight without establishing
starting-pose eligibility. This sparse screen does not establish a counting or
latency benefit and does not promote a crop adapter. A separate full-source
side-dip rectangle diagnostic passed all 597 original frames through the shared
policy, including the decoder's 60000-to-90000 timescale transition: 152 acquiring,
313 associated and 132 ambiguous decisions, with no clock faults or expired
continuity. These are geometric statuses without athlete identity annotations,
body selection or rep scoring. The existing 1/26 production selected-replay result
and its 25 dip misses remain unresolved.

Existing public Android accuracy failures and Apple simulator decoder/model
blockers remain separate visible gates. Synthetic/shared tests cannot turn those
into qualified accuracy. Physical Android camera, thermal, memory and energy parity
remain unqualified without hardware. Further releases need frozen full-session
annotations, athlete continuity/crowd tests, deliberate exercise switches, cold
partial starts, user corrections, richer history/trend views and device profiling.


## Practice phases and progressive goals

The shared journal now preserves practice even with zero completed reps. Each set
includes an optional versioned `activity` record: a bounded phase timeline,
full-duration phase totals, longest bilateral hang/top-support hold, observed
seconds, elapsed source time and a timeline-retention flag. Old records decode
with `activity = null`; an absent measurement is not interpreted as a zero hold.

Phases describe hang/top support, pulling, lowering, rising, top/bottom or
mid-range holds, release, acquisition and tracking gaps. Movement labels use
current/previous projected elbow angles and the established counter phase; they
are descriptive measurements, not a form/depth assessment. Counter policy and
completed-rep eligibility are unchanged. An accepted pull-up without an observed
return remains an incomplete practice attempt and never earns a completed-rep
metric, target or milestone.

Only increasing, consecutive usable source observations within the existing
counter gap budget earn time. Hangs/support additionally require both visible
arm chains and the existing endpoint angle threshold. Missing arms, invalid or
out-of-order timestamps, interruption, source gaps and observed release split
continuous holds. Disengaging ends the set; holding still does not. The live
feed shows a compact current-hold timer; it is updated on source-time journal
checkpoints rather than a wall-clock timer that could count invisible motion.

The display timeline retains 256 spans, while totals and longest holds include
all observed intervals. Drafts are checkpointed at phase changes and at one
source-second intervals during holds. Pre-frame/no-practice drafts are omitted,
so immutable camera metadata is initialized before a journal can be published.
Live deltas include only the latest three changed spans; shared journal merging
preserves the full bounded timeline. The Apple adapter reads only the bounded current timeline/active attempt
once when review opens and reuses its cached closed reps. Summary projection preserves phase metrics when
angle curves expire.

`WorkoutProgression` owns compact history projection, suggested targets,
measurement-based coaching, progress calculations and milestones for both apps.
Native apps persist the target separately per exercise, render charts and own
background reads. Targets default to an adjustable hold goal for beginners or
a completed-rep goal after a completed cycle. A saved target remains fixed until
edited; its progress uses only sets with a calendar start at/after target
creation. Suggested target increments are app defaults, not a training
prescription. Hold, movement-attempt and completed-rep milestones are independent.
Projected elbow range is shown only with bilateral observed coverage; range is
not a percentage of rep validity.

All-time progress loads compact evidence from every immutable saved summary and non-active recovered journal,
independently of the 50-set detail window and motion-sidecar retention. Reports
retain only the latest 20 dated trend points, while totals, personal bests,
first-achievement dates and goal achievements use all available summaries.
Undated legacy records can contribute observed evidence but never invent dates
or earn targets created at a known calendar time. Recovered journals retain
practice, while replay review does not create/edit live goals. History/goal
review never sends pause/reset commands to camera analysis.

Validation covers zero-rep hanging/support, direction and holds, missing-arm
and timestamp continuity, curve/timeline retention, legacy decoding, compact
all-time history, target validation/fixed ownership, incomplete-rep exclusions,
native journal merges/reload, and rendered beginner/active/history review states.
Recovered journals are distinguished from currently active journals by each
store's process-local active-set registry (plus the review's current set ID).
Active journal bytes never become completed history evidence.
The existing shared/Apple and Android CI workflows execute these checks.
