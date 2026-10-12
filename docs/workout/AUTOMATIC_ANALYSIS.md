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
- A rep opens on departure from the acquired start. An accepted pull-up reaches
  its counting milestone at the upper endpoint; it closes its motion record on
  the observed return. A dip is accepted on the return to top support. The count
  policy, geometry gates and confidence thresholds remain version 8.
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

The camera remains the live workout surface. The analysis button opens a read-only
review that does not pause tracking. Native Swift Charts/Compose plots show rep
phase times, independent left/right angle curves, gaps and coverage. Historical
rep selection is independent of the active workout. Expired curves show an
unavailable message while retaining the timing/angle summary.

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
