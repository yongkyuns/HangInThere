# Public workout repetition diagnostics

This extends the production replay evaluation to public labelled videos. It
reuses the existing Countix inventory, native Apple `ReplayController` and
shipping analysis bridge, Android `NativeReplayEvaluator`/`AnalysisPipeline`,
and independent source-time event matcher. It adds no app dependency, alternative
counter, predicted reference joints or training system.

## Frozen first batch

[`fixtures/public-workout-pilot.json`](fixtures/public-workout-pilot.json) pins
33 original videos, 149 published repetitions, all metadata and provider archive
bytes/SHA-256 values. Selection was fixed before inference:

- All 29 explicitly labelled Countix validation pull-up videos in CVDF's public
  `k700_val_454.tar.gz`. Every candidate exists; none was selected for model success.
- Four native-ID-sorted pull-ups from the first 100 rows of the RepCount-A LLSP
  mirror's validation preview. This is a bounded acquisition sample, not a random
  population sample or the entire RepCount validation set.

The hosted Countix archive contains 8,581 rows, including 2,642 without action
classes. Its 115 training and 29 validation pull-up entries are labelled
**segments**, not necessarily distinct original videos: some training videos have
two separate labelled periods. Unclassified rows are never guessed or treated as
negative examples. Inventory retains them and the original published split.

The authors' pinned RepCount **open-set** CSVs contain 99 pull-up entries, all
assigned to their training partition. The mirror uses a different LLSP partition:
our four videos are mirror-validation but author-open-set-training. Both assignments
remain explicit; neither becomes an athlete holdout. Counts and all cycle-frame
markers for these four media files match the authors' annotations exactly. The
mirror's `countix_compat` alias is RepCount data and is not accepted as official
Countix. Part B's unavailable source videos are not included.

Raw videos are not re-encoded, cropped, repeated, resampled or repaired. `ffprobe`
reads actual container ticks; Apple checks its replay counting clock against every
source PTS, and Android checks the exact truncated microsecond clock inside the
production evaluator. RepCount frame markers are mapped through that PTS array,
never a guessed FPS. Countix's absolute YouTube intervals are offset by the
published Kinetics start and the actual container's first PTS. Shortened videos
cannot silently acquire replacement labels.

## Run and expand

```sh
# Optional explicit acquisition. Offline reuse verifies the same immutable pins.
python3 scripts/public_workout_datasets.py prepare --fetch \
  --cache Data/external/public-workout-pilot --output build/public-workout-inputs

# macOS: actual production native replay and common completed-rep analysis.
bash scripts/evaluate-public-workouts-apple.sh build/public-workout-inputs/manifest.json \
  build/public-workout-inputs build/public-workout-apple.json
python3 scripts/public_workout_datasets.py score build/public-workout-inputs/manifest.json \
  build/public-workout-apple.json --output build/public-workout-apple-score.json

# The same unchanged originals and manifest can be embedded in the TEST APK.
python3 scripts/public_workout_datasets.py android-assets build/public-workout-inputs/manifest.json \
  --root build/public-workout-inputs \
  --output android/app/src/androidTest/assets/generated/public-workout

# Hosted API29/API35 and native Apple evaluation, no physical Android required.
gh workflow run public-workout.yml --ref codex/android-parity
```

Use fresh preparation/output directories. Source/media/output pins are checked;
existing audit reports are never overwritten. A changed or unavailable mirror
snapshot fails acquisition rather than silently replacing a frozen video. To
expand, create a new versioned specification from the catalog's retained raw
source rows, establish actual video hashes and preserve the original split and
label contract. Record unavailable candidates, duplicate sources and unresolved
subjects. Preserve an untouched evaluation group before tuning.

## What is scored

The common scorer reports **acceptance events** and **returned completed cycles**
separately. A successful acceptance without an observed return cannot enter the
completed-cycle count. Every requested case remains in the count-error denominator,
including unavailable setup, interrupted replay and native failures. Interruptions
retain already measured events but cannot claim full-source or exact-count success.
Wrong exercise identity cannot pass merely by matching a number.

Countix is scored only within its published repeating interval and has no invented
per-rep timing reference. RepCount also provides a cycle-end timing diagnostic using
the existing one-to-one event matcher and a predeclared 0.5-second tolerance. These
published cycle boundaries have not been adjudicated against our
`start_extreme_return` convention. Timing differences may reflect label convention
as well as runtime errors; this is not strict-form accuracy.

Apple uses the app's selected pull-up replay path; Android uses the app's automatic
exercise acquisition and replay path. Their exercise selection/lifecycle modes
are declared. Running both is evidence about their actual behavior, not an
assertion that their acquisition paths or outputs are identical.

Apple additionally observes unchanged production pose results to report single
person, bilateral arm and automatic apparatus-search support per unique source
timestamp. Acquisition/rewind duplicates do not inflate this denominator. Android's
existing native people counters include both acquisition and counting inference
calls and are labelled as native pass counters. These diagnostics explain missing
setup; they do not filter cases out of accuracy scoring or certify pose accuracy.

The optional native workflow saves counts, source-clock manifests, metadata,
model/build provenance and scores. It does not upload source videos, images,
landmarks, test APKs or user phone data. Scoring preserves failed/interrupted cases
and exits unsuccessfully when a source was not fully processed; this fails its native
CI job. The separate accuracy job retains count mismatches as failures. A successful
contract/preparation job establishes no accuracy result.

Apple CI uses two deterministic interleaved shards of the original manifest.
Each worker reads and verifies the full immutable manifest and reports its actual
assignment. The merger requires every shard and original case exactly once,
identical source/backend/counter/request/selection contracts and matching media,
EOF and event clocks before preserving a combined report. Individual native
report hashes remain attached. A missing, duplicate, changed or misassigned shard
cannot claim full-corpus success. Failed cases remain failed.

The initial hosted Apple attempt completed 31 clips before the 45-minute job
limit stopped it; it has no complete native report or qualified full-batch score.
Hosted VM inference was substantially slower than the local Mac. Sharding bounds
each worker's workload without changing source frames or evaluation rules. The
replay operation watchdog allows 900 wall-clock seconds per clip on these VMs;
loading retains its 300-second watchdog. These are operational hang limits,
separate from source-time gates and physical-device latency/battery/thermal
qualification. Count rules, EOF/PTS integrity, accuracy tolerances and existing
app performance gates are unchanged.

Android reserves an exclusive directory for each run nonce. Media copy/hash errors
retain a failed row and processing continues through the remaining cases. Report
files use exclusive creation; collection rejects a different nonce or app revision.
Failed instrumentation cannot be converted into success by collecting an old report.
Raw native error descriptions that might contain serialized poses are not published.

Android's `acquisitionEvidence` reports counts from the first acquisition pass:
usable bilateral arms, resolved exercise, actual contour sampling/evidence/errors,
acquisition states, setup issues and confirmed-reference frames. Its frame and
histogram bounds are checked on completed processing. `none` in the setup-issue
histogram means no current setup issue, not a valid starting position or counted
rep. A rewind/counting pass does not inflate these counters. They observe production
outputs without changing policy, exposing raw poses or establishing labelled pose
accuracy. Diagnostics run outside the measured decode-to-display-copy interval;
the full harness wall time still includes them.

`referenceStabilitySamples` counts only actual eligible-edge observations whose
diagnostic source timestamp matches the current frame. Cached states and the
counting-pass rewind add no samples. Statuses describe the first failed dwell
condition (source gap, context, edge or hand drift), a newly selected candidate,
continued accumulation or qualification. These counts cover the selected apparatus
path after edge eligibility, not all earlier grip/contour rejections. Their sum
cannot exceed actual contour samples. Swift/Kotlin setup parity compares the same
counts/timing diagnostic frame by frame; the local Apple bar audit additionally
contains geometry and must remain local for private footage.

Fixed-reference dwell subtracts same-timescale source ticks before converting
to seconds, preserving the existing 0.9-second hold and 0.8-second gap boundaries.
Invalid scales or signed subtraction overflow fail qualification. Mixed-timescale
observations retain floating-point subtraction; they do not have the same exact
boundary guarantee. No hold duration, required observations or geometry tolerance
has been relaxed.

## Qualification and data use

This is user-authorized internal diagnostic use of publicly distributed research
material. Public availability or a code license does not establish redistribution,
commercial training, participant consent, athlete isolation or unknown pretraining
exposure. The mirror declares its license as `other`; source-video licenses remain
individual/unresolved. Raw sources are kept outside Git and public CI artifacts.
No qualified human review or consent attestation is fabricated.

This batch contains no annotated parallel dips, grip classes, strict-form verdicts,
hang/support phases, automatic set boundaries or gym usability observations.
It does not close those roadmap gates. Native Mac and emulator replay timing does
not establish sustained physical-device camera/sensor, battery or thermal parity.
The original accuracy and Apple simulator failures remain active.

Sources: [RepCount authors and download page](https://svip-lab.github.io/dataset/RepCount_dataset),
[author open-set annotations](https://github.com/SvipRepetitionCounting/TransRAC/tree/main/open_set),
[RepCount mirror and schema](https://huggingface.co/datasets/lmms-lab-eval/repcounta-lance),
[Countix/RepNet](https://sites.google.com/view/repnet/home),
[CVDF published inputs](https://github.com/cvdfoundation/kinetics-dataset),
[source-video license distinction](https://github.com/google-deepmind/tapnet#license-and-disclaimer).
