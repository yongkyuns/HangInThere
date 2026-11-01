# Public push-up baseline

The current detector transfers poorly to this bounded public-video sample.
The unchanged app rules produced **87 completed returns against 400 published
repetitions**, with **0/31 exact clip totals**. Sixteen videos never reached Ready;
five reached Ready but counted no returns. This is published-count agreement,
**not 21.75% valid-rep recall**: annotation phases, initial held-plank availability
and exercise form have not been independently adjudicated.

## Frozen sample

Source review retained 31 of 132 acquired candidates: 27 RepCount-A and four
Countix originals. Exclusions cover edited exercise views, severe crops,
steep overhead/rotated views, obstructed arms, and compound or assisted variants.
The retained footage includes ordinary clothing/background changes, small
head/foot crops, modest oblique views and one fixed small-handle example.
Screening used five fixed source-image samples before any detector inference;
this does not prove continuity between samples or a sufficient starting hold.

There are 25 side/oblique-side and six front/oblique-front views. Front coverage
is limited. Conservative source-family groups stay together in eight development
and 23 test videos. Athlete identities and hidden cross-dataset duplicates are
unverified; this is not an athlete-independent or population benchmark. Original
author and mirror partitions remain separate from these project roles.

The [frozen corpus](../../Evaluation/fixtures/public-pushup-benchmark-v1.json)
retains all screened decisions, original count/frame references and media hashes.
The [compact baseline](../../Evaluation/fixtures/public-pushup-baseline-v1.json)
contains every included result. No source was removed after predictions.
All sources are now exposed evaluation data; reuse is not a fresh final test.

## Result

| Group | Videos | Reached Ready | Published reps | Counted returns | Exact totals |
|---|---:|---:|---:|---:|---:|
| Development | 8 | 3 | 85 | 27 | 0 |
| Test | 23 | 12 | 315 | 60 | 0 |
| All | 31 | 15 | 400 | 87 | 0 |

The mean absolute clip-count error is 10.10. Every clip undercounts; that does
not establish zero false positives, since misses and extras can cancel within
one clip. Countix is scored in its published repeating interval. RepCount
cycle-end matching at 0.5 seconds is retained only as a timing diagnostic: its
published cycle phases are not verified against our completed-return definition.
Unlabelled setup/release and automatic set-boundary accuracy remain unqualified.

The useful failure groups are readiness/framing, pose/athlete continuity, and
recovery after interruptions. Examples from the retained reports:

- `stu9-49` and `stu6-45` never acquire; their dominant blocker is Move closer.
  This needs a working-range/source audit before changing a count threshold.
- `stu1-43` counts 1/9, then ends with body tracking lost. It illustrates why
  re-calibrating after an interruption can lose subsequent moving repetitions.
- `stu5-45` reaches Ready but both attempts end with hand-support movement.
- `stu8-44` counts 20/22; `stu10-43` counts 16/17. Initial acquisition and later
  observation losses must be separated before attributing every deficit to ROM.

Next: audit the eight development videos for adequate framing/starting holds,
then address false readiness/continuity resets in genuinely supported examples.
Keep the 23 evaluation sources fixed, preserve all original failures, and add
fresh evaluation footage when making another independent generalization claim.

## Framing correction: side policy 3

The old side profile required shoulder-to-leg horizontal span to fill 40% of
image width. It also treated failure of that distance check as positive evidence
of leaving the plank. These are separate questions: yaw and changing visible leg
endpoints shorten the projection without necessarily changing distance or form.

- `stu6-45`: the initial plank spans only about 28% of image width, while its
  visible counting arm spans about 49% of image height. The old distance rule
  prevents acquisition. It now becomes Ready at 1.2 s, but still counts **0/10**:
  projected shoulder–hip–leg geometry repeatedly fails straightness during motion.
  Projection and pose variation remain unresolved; this is not a complete fix.
- `stu1-43`: at 17.392 s the span falls to 39.0%, despite a measured body angle
  of 176.8°. That incorrectly cancels the pending return and then clears calibration.
  The later `bodyTrackingLost` closure is a symptom, not the original interruption.
  Separating these gates improves the clip from **1 to 8/9** published reps.

Policy 3 checks visible arm-chain size only while establishing the starting
plank (at least 25% of landscape image height). It retains the existing plank,
knee, depth, shoulder descent, endpoint dwell, hand stability and source-continuity
requirements. During a calibrated cycle, projected size is not a posture-release
signal or a fresh distance gate. The front profile is unchanged.

Replaying the same 31 exposed pose traces changes completed returns from **87 to
102**, and clips reaching Ready from **15 to 19**. No clip's total decreases;
all 31 still disagree with their published totals. `stu4-44` now counts 10 against
the published 9: source images at 35.45, 36.62 and 37.62 s show another top–bottom–top
cycle beyond the final annotated interval. Frozen labels remain unchanged. These
are count comparisons, not valid-rep accuracy or a new held-out result.

Validation: 1,031 shared/core JVM tests passed, including the ten existing
phone-video coverage variants and new regressions for projected-width changes
and arm foreshortening. Native phone capture was not retested. The before/after
timelines, trace hashes, comparison and local investigation harness are retained
outside Git in `build/pushup-root-cause-v161/`; no new media download was needed.

## Bounded posture recovery: side policy 4

In `stu6-45`, the projected hip angle drops below 150° during otherwise usable
arm motion (149.2° at 3.13 s, then 152.6° at 3.9 s). Previously a single such
observation cancelled the attempt and forced a fresh held-plank calibration.
Policy 4 treats a bent projected hip angle as unusable evidence, preserving
phase only within the existing one-second body gap. It cannot supply a bottom or
return endpoint. Observed knee flexion or excessive body tilt still cancels the
attempt, and missing arms, source/camera interruptions and long body gaps retain
their previous behavior. No endpoint angle, depth or dwell threshold is lowered.

On the same exposed observations, `stu6-45` improves **0 → 3/10**, and `stu1-45`
improves **2 → 4/6**, compared with policy 3. Other clip counts are unchanged:
**107 completed returns**, 19 clips reaching Ready, still no exact published
totals. Oblique-view geometry remains a substantial limitation. All 1,032
shared/core JVM tests pass, including phone-video coverage variants and a new
regression proving short bad hip geometry cannot complete endpoints and long
failures cancel. Evidence is compressed under `build/pushup-posture-v162/`;
this does not establish live-phone or fresh-video accuracy.

## Execution and reproduction

All 31 native replays reached EOF, producing 30,886 original Apple-decoded frames.
Snapshot returns and durable set-record returns agree for every source. Four
Apple decodes include a leading zero-time frame absent from FFmpeg output; after
that prefix, paired clocks differ by at most 33.1 microseconds. Both raw clocks
are retained without repair. Those four cases retain frame-comparison flags;
none would have an exact published count even without that flag.

The pipeline uses production decoding, the current Vision estimator, and common
Kotlin front-policy 1 / side-policy 2 with independent camera readiness **assumed**.
It does not qualify live iPhone inference, Android or camera stability.
No runtime rules or model weights were changed for this experiment. Media,
source-review images, poses, clocks, complete reports and the executable stay
outside Git under `Data/external/public-pushup-v160/` and
`build/public-pushup-benchmark-v160/`. Excluded download caches can be reacquired;
included originals and all model-failure evidence are retained.

```sh
python3 scripts/public_pushup_benchmark.py prepare \
  Evaluation/fixtures/public-pushup-benchmark-v1.json \
  Data/external/public-pushup-v160 NEW_INPUT_DIRECTORY
# Run the existing PushUpCoverageReplay executable with replay.json.
python3 scripts/public_pushup_benchmark.py score \
  NEW_INPUT_DIRECTORY/manifest.json REPORT.json --output NEW_SCORE.json
python3 -m unittest scripts.tests.test_public_pushup_benchmark \
  scripts.tests.test_public_workout_datasets
```

Preparation verifies pinned publisher annotations and original bytes, without
copying videos. Optional `--fetch` uses selective Lance reads for RepCount
(evaluation-only `pylance==14.0.2`) and the single official Countix archive.
It adds no app dependency or CI job. Preparation rejects source-family overlap;
scoring keeps missing/failed sources in the denominator, rejects inconsistent
reports, and never rewards late
readiness by removing earlier reference repetitions.
