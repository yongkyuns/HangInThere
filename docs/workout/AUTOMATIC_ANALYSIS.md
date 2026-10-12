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

## Lifecycle and evidence

- Start observing after actual apparatus, exercise identity, camera motion and
  scene checks qualify. The new readiness gate uses 0.12 seconds of consecutive
  source frames; a qualified prior frame seeds the counter's existing starting
  baseline. No three-second countdown or leg gesture is required. Frames before
  apparatus/camera qualification cannot retrospectively earn a baseline.
- A rep opens on departure from the acquired start. An accepted pull-up reaches
  its counting milestone at the upper endpoint; it closes its motion record on
  the observed return. A dip is accepted on the return to top support. The count
  policy, geometry gates and confidence thresholds remain version 8.
- Preserve an accepted rep even when its full return is unavailable. Missing
  return/peak measurements are null. Partial and interrupted attempts remain
  distinct records; they do not contribute to accepted counts.
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
recovery and acknowledgement races.
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
