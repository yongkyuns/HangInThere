# Public workout repetition diagnostics

Grip training is a separate [public-data experiment](../docs/workout/GRIP_DATA_EXPANSION.md).
Its visual grip annotations, source-separated splits and corrections do not
modify the published repetition/count references documented here.

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

Apple and Android reports with `progress_evidence_version=1` retain a verified source prefix
after caught inference/replay failures (including Apple's operational watchdog).
Each row binds its
observed frame count and last source PTS; events after that endpoint are rejected.
The snapshot reads the production counter and existing shared completed-cycle
eligibility before controller cleanup. An active review can contribute already
closed returns, while an accepted but unreturned movement remains incomplete.
Missing/invalid clocks or changed media produce a failed row without credited
measurements. Interrupted rows cannot claim verified EOF or exact-count success,
even when their partial event count happens to match the published count.

Every row must carry the declared progress contract, and shard merging rejects
mixed versions. Older reports retain their original scoring behavior and make
no claim of this additional observed-prefix validation. Native Mac CI exercises
the collector on actual decoded synthetic frames and the shipping analytics,
including late inference failures and active-review snapshots; Python contracts
cover endpoint bounds, versioning, shard preservation and failed CLI exits.

Android additionally checks each decoded microsecond PTS before native processing,
records it only after the frame completes, and resets the prefix on counting-pass
rewind. A late exception snapshots the shared runtime before pipeline cleanup;
acquisition events cannot enter counting totals. Early EOF or a mismatched/extra
PTS invalidates evidence rather than disguising a source failure as interruption.
The evaluation-only Kotlin collector and its tests are compiled into the JVM and
Android instrumentation test targets, never into either shipping core or app.
API29/API35 CI also injects failures into real decoder/model replay before setup
and immediately after the required positive clip's acceptance. Native execution
and actual corpus accuracy remain separate gates.
That positive clip has no shared-eligible returned cycle. A separate authored
shared-runtime test uses the same capture wrapper, throws before/after return,
resets the runtime and verifies the captured return remains intact. Shared CI
also feeds its actual Kotlin scalar rows through the Python scorer. This checks
the wire contract without presenting authored motion as native accuracy evidence.
Forced process termination cannot manufacture a finished report.

At source `6857c48`, hosted API29 and API35 each execute and pass all eleven
new progress regressions: nine shared collector tests and two injected-failure
tests through the actual decoder/model pipeline. Each native suite passes 59
cases with three optional skips; none of the new tests skips. The hosted Kotlin
rows also pass the Python contract. A fresh production Swift build reproduces
all six native-observation comparisons across 896 source frames, matching the
hosted reports exactly. The [CI validation record](results/public-workout-progress-ci-6857c48.validation.json)
binds the tree-equivalent CI checkout, artifact digests and test outcomes.
This closes the earlier native-execution pending note; it does not rerun the
33-video corpus. Original cropped-pull-up/dip accuracy and Apple simulator
failures remain open, along with independent accuracy and physical-device gates.

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

`pullUpGripAssessmentVersion=1` adds a `pullUpGripFrames` histogram over every
actual first-pass pose, including frames before contour sampling. It uses the
same shared typed assessment as production `AutomaticPullUpBar.grip()`, with the
same check order and arithmetic. Success is `eligible`; other entries describe
the first failed context, athlete, arm, grip-landmark or projected-geometry check.
For example, measurable arms at the existing 0.3 confidence threshold can still
lack the 0.5-confidence hand/shoulder landmarks required for bar search. These
scores are SDK evidence, not calibrated pose accuracy. A dip can normally fail
the overhead pull-up pose check; this histogram does not grade dip suitability.

Every first-pass frame appears once, all statuses/counts and the version are
validated, and eligible frames cannot exceed bilateral-arm frames. Cached
acquisition states and the counting-pass rewind add no assessments. `eligible`
does not mean a bar was found, a set started or a valid rep completed. Older
reports without these fields remain explicitly unassessed; they are not zero
rejections. Evaluation performs this additional assessment outside the measured
native pipeline interval, without feeding results into the app runtime. The
diagnostic exports only counts, not poses, coordinates or inferred physical labels.

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

The local Apple bar audit also records optional adjacent-frame and prior-dwell
scene-registration diagnostics. Adjacent means the previous contour-sampled
source frame; prior-dwell means the stability anchor before the current sample
can replace it. Translation consensus, estimated shift and estimated scale use
the production registration worker. Missing measurements remain missing, and
registration errors are explicit. These observations never feed acquisition or
counting. Their association with edge resets is image evidence, not independently
labelled physical camera or bar movement. Retained public summaries exclude raw
images, poses and geometry; private audit output must remain local.

## Qualification and data use

This is user-authorized internal diagnostic use of publicly distributed research
material. Public availability or a code license does not establish redistribution,
commercial training, participant consent, athlete isolation or unknown pretraining
exposure. The mirror declares its license as `other`; source-video licenses remain
individual/unresolved. Raw sources are kept outside Git and public CI artifacts.
No qualified human review or consent attestation is fabricated.

Published annotations for this batch contain no parallel dips, grip classes, strict-form verdicts,
hang/support phases, automatic set boundaries or gym usability observations.
It does not close those roadmap gates. Native Mac and emulator replay timing does
not establish sustained physical-device camera/sensor, battery or thermal parity.
The original accuracy and Apple simulator failures remain active.

A separate [provisional visual grip review](../docs/workout/PUBLIC_GRIP_EVALUATION.md)
now restores all 33 originals, retains ambiguous cases and compares the learned
classifier on separate source recordings. Its annotations do not replace the
published labels or establish athlete-independent binary grip accuracy.

Sources: [RepCount authors and download page](https://svip-lab.github.io/dataset/RepCount_dataset),
[author open-set annotations](https://github.com/SvipRepetitionCounting/TransRAC/tree/main/open_set),
[RepCount mirror and schema](https://huggingface.co/datasets/lmms-lab-eval/repcounta-lance),
[Countix/RepNet](https://sites.google.com/view/repnet/home),
[CVDF published inputs](https://github.com/cvdfoundation/kinetics-dataset),
[source-video license distinction](https://github.com/google-deepmind/tapnet#license-and-disclaimer).


The [front/rear grip implementation and cohort expansion](../docs/workout/GRIP_FRONT_REAR_IMPLEMENTATION_V9.md)
records official Kinetics 600 intake, directly reviewed publisher hand crops,
whole-athlete validation grouping and fourteen untouched final reserve groups.
Its retrained anatomical model fails qualification and remains offline; raw
public media remains outside Git. The app view prompt does not require this
failed learned candidate.

### Grip full-set validation v27

`Data/external/grip-width-v27/` retains twelve public source groups acquired for
frozen full-set testing. The ledger is
`build/grip-width-v27/run/source-references.json`; the checked-in summary is
`Evaluation/fixtures/grip-full-set-validation-v27.json`. Five source exclusions
and one native VP9 decoding failure remain in all-source accounting. Six
recordings were processed, including two real continuous grip-change sequences.
All twelve groups are now exposed evaluation sources and must not be counted
as fresh holdout footage in subsequent experiments. No footage or patches from
this cohort were added to model fitting. A recorded source-only temporal
correction fixes the close chin-up's final release; original labels remain saved.

### Visible-arm source validation v30

`Data/external/grip-fullsets-v30/` retains eighteen acquired public recordings
from nineteen selected groups; one unavailable source remains in acquisition
accounting. Source-only screening excludes fourteen before any pose/RGB inference.
Two complete-set recordings and two partial-cycle diagnostics are processed;
their roles and references are frozen separately in
`build/grip-fullsets-v30/run/source-references.json`. Two explicit source-only
acquisition expansions are preserved. All nineteen selected groups are now
exposed intake/evaluation sources and must not be presented as untouched holdout
footage. None entered fitting. This cohort does not establish Narrow/Medium width
accuracy or real grip-change qualification. Results and post-inference crop/width
attribution are in `Evaluation/fixtures/grip-visible-arms-validation-v30.json` and
the v30 section of `docs/grip-variations.md`.
