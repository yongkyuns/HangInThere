# Workout library implementation

The existing immutable measurement archive and shared camera analysis stay authoritative.
The library adds a separately versioned review/application layer, invoked outside the
frame path. Both native apps persist the same validated state atomically and use the
same JSON bridge for projections and commands.

## Contracts

- Exercise/context corrections, attempt classifications and complete set partitions
  are append-only review events. Undo appends a replacement. Measured counts, source
  times, angles, camera geometry and original files are never rewritten. User-reported
  completions are shown separately and never invent measured rep metrics/milestones.
- Split partitions cover every original attempt exactly once. Only one partition owns
  a set's hold totals. Merging requires the same exercise and comparison context; raw
  source clocks from separate recordings never become one continuous timeline.
- Comparable trends require explicit exercise, assistance/load, motion intent and
  camera/equipment context plus matching metric versions. Missing context is shown as
  unclassified. Recent multi-session medians drive suggestions; chosen goals stay fixed.
- Automatic workout grouping uses actual live calendar timestamps with a bounded rest gap.
  Actual recorded finish clocks support between-set intervals; historical/replay finish
  clocks stay unknown. Replays never receive an evaluation date as their workout date.
  Explicit workout assignment overrides grouping. Planned work is user-selected;
  completion and coaching expose measured evidence and uncertainty. Weekly habits count
  practice days, not uploaded frames. Plateau is a descriptive stable observed metric,
  not a diagnosis or instruction to train harder.
- History uses a durable compact index, stable cursor pages, direct record lookup and
  bounded native detail/media windows. Initial migration scans immutable summaries
  once; normal cold loads use the compact journal and pending publication markers.
  Interrupted drafts remain practice; durable deletion markers retry physical erasure. Delete tombstones prevent queued writes from resurrecting deleted sets.
- Backup is versioned and validated in full before restore, with immutable conflict
  rejection and idempotent replay. Phase spans, their credited interval starts, measured
  grip summaries and independent grip-correction ledgers survive a backup. Divergent
  review histories are rejected without replacing either version. Media is never included automatically. Exports label
  measured, corrected and user-reported values separately.
- Optional local media retention is off by default, explicitly attached/imported for
  review, bounded by bytes/count/age and separately deletable. No upload or training
  consent is inferred. Diagnostics are opt-in and content-free; benchmark artifacts
  require separately authorized recordings and frozen labels.

## Validation

Shared regressions cover correction/reload/undo, partitions, measured exclusions,
source-clock separation, context isolation, recent robust baselines, calendar habits,
planning, cursor stability, migration/conflicts, retention, consent and bounds. Native
storage/UI tests and actual Swift FFI protect parity. Existing count/setup oracles,
performance budgets, real-video accuracy failures and simulator partitions stay active.

Independent annotated athlete/session data, sustained physical-device energy/thermal
measurements, equipment-distance usability trials and qualified coach review require
external evidence. Implementation of collection/scoring/export interfaces does not
complete those qualification tasks or establish release accuracy.


## Feature and interface map

| Capability | Common policy | Native presentation / persistence |
| --- | --- | --- |
| Immutable measurements, attempt evidence, phase stability | Existing analysis/evidence/phase engines; profiles in `WorkoutContext.kt` | Native body/hand inference; shared phase and evidence projections |
| Exercise/attempt review, split/merge, undo | `WorkoutCorrections.kt`, `WorkoutLibrary.kt` | Library History → Measurements/Edit; atomic native commands |
| Comparable progress, recent baselines, fixed chosen goals | `WorkoutContext.kt`, `WorkoutLibraryInsights.kt` | Progress → hold, cycle, excursion and return charts; source-evidence links |
| Effort/readiness/recovery, assistance/load/variation | Per-set context; explicit timestamped future context | History editor, optional carry-forward; per-set effort never carried forward |
| Workout grouping, plans, consistency, milestones | `WorkoutLibraryInsights.kt` | History grouping/transitions, Plans and Progress sections |
| Equipment cues and concise after-set feedback | `WorkoutFeedback.kt`, existing start feedback | Debounced spoken start/end/pause/resume; after-set chosen-goal progress |
| Local media and deletion | Shared age/count/byte retention policy | Explicit video import/play/delete, editable limits, off by default |
| Indexed history, recovery and backup | Versioned state/commands/cursor/backup contracts | Compact index + recovery journal + publication/deletion/restore intent files |
| Benchmark normalization / predeclared gates | `WorkoutBenchmarkExport.kt` | Actual Apple FFI → independent Python scorer; common schema for Android |
| Diagnostics / representative performance matrix | `WorkoutLibraryBackup.kt`, `workout_release_readiness.py` | Opt-in local content-free export; physical/host/emulator scopes stay separate |

History merges are presentation groups. Analytics still use each original source
partition, so merging recordings cannot erase practice days, turn two one-rep sets
into one two-rep planned set, or splice source clocks. Workouts are grouped with a
45-minute start gap unless explicitly assigned. Planned completion requires an
explicit workout link. Goals and plans use observed cycles or holds and do not
verify anatomical form or prescribe training.

Chosen-goal feedback aggregates comparable eligible sets in the current workout.
Future context is an explicit user setting with a selection timestamp; delayed
older publications and undated replays cannot inherit it. Changing equipment,
assistance or camera framing requires the user to update the declared context.
Measured grip estimates remain optional; no missing hand, video gap or manually
reported completion manufactures a measured cycle.

## Storage and migration limits

The application layer, correction ledger, after-set/insight policy and backup each
have explicit version 1 contracts; raw count policy 8 and phase metric 2 remain
unchanged. New optional fields are backward readable. `observedStartedAt` records
the credited suffix of a phase without altering its measured duration. Historical
partial-credit spans without that timing remain readable but cannot be fabricated
into a full benchmark phase interval.

The compact index supports 100,000 sources; pages contain 25 entries (maximum 100).
Motion details retain the existing 50-set/50-MiB window. Local review media defaults
to off, with 20 clips/200 MiB/30 days when enabled, adjustable within validated limits.
Backups are summary-only and limited to 32 MiB; oversize exports fail explicitly.
Media, diagnostic consent and raw frame traces are excluded. Concurrent/divergent
backup histories are rejected rather than implicitly picking a winner.

Native stores publish authoritative journals first. Publication intent precedes a
new immutable summary, deletion intent precedes its tombstone, and restore intent
precedes state/file restoration. Recovery is idempotent. Corrupt authoritative data
fails visibly; an intact journal can repair the index. Neither test cleanup nor
migration touches external recordings or credentials.

## CI and evidence scope

Shared tests retain the production Swift counting/setup oracles and add library,
feedback, backup, context, phase-credit and source-normalization regressions. Native
macOS/iOS storage tests and API29/API35 instrumentation cover persistence, recovery,
retention and grip/phase backup. Android exercises all four shipping library tabs;
Apple renders narrow/wide light/dark charts, history, plans, storage and live chrome.
The Apple FFI normalizer is scored by `check-native-workout-export.py` using an
explicit synthetic contract. It is schema/interoperability evidence, not an
independently annotated athlete benchmark.

For release gates, first freeze labels/splits with `workout_benchmark.py`; declare
accuracy/cohort/device thresholds and the frozen benchmark hash before tuning, then
freeze that policy with `workout_release_readiness.py freeze`. Score the fresh test
split and assess it with an audited content-free device matrix. Every supported
recording-condition subgroup must independently meet cohort and accuracy limits;
pooled scores cannot hide an unsupported condition. Matrix rows bind report hashes,
public device/model families, scope, sustained duration, pipeline p95, drop fraction,
battery rate, maximum thermal state, consent and camera/sensor timing qualification.
Missing measurements fail. A host or emulator row cannot establish physical parity.

Still external: independent collection/adjudication and exposure audit, fresh
participant/session holdout accuracy, sustained physical iOS/Android energy/thermal
and camera/sensor tests, coach approval of training rules, and equipment-distance /
gym-noise usability. These remain open in #31 and existing release failures remain
unwaived. This implementation adds descriptive coaching and evaluation interfaces;
it does not claim those field results or permission to upload private media.
