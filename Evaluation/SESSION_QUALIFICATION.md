# Session-level workout qualification

This is the next qualification layer after the frame/clip-level real-video work.

The existing pose, counter, bar, camera-stability and device tests remain useful,
but product reliability is ultimately a **whole-session** property. A real user
cares whether setup succeeds, the set count is correct, tracking stays usable,
and the app avoids false interruptions.

## Unit of evidence

One record represents one complete workout session or deliberately terminated
setup attempt. Session IDs must be unique. Repeated sessions from one participant
share `participant_group`; media captured from one source session shares
`source_group`.

The first field campaign should vary:

- participant;
- phone/device generation;
- camera distance and front/side/oblique view;
- pull-up and parallel-bar apparatus;
- indoor/outdoor and lighting;
- clothing/body proportions;
- clean and cluttered backgrounds;
- intentional phone movement;
- background people crossing the frame.

Do not tune runtime thresholds from population-eligible sessions. If evidence is
used to change the algorithm, reclassify it as development evidence and qualify
the changed policy on fresh sessions.

## Metrics

`scripts/session_qualification.py` validates the portable JSON contract and
computes:

- exact-count set fraction;
- movement recall;
- extra movements per 100 expected movements;
- sets with interruptions;
- weighted/median/minimum tracking coverage;
- bar-calibration success and mean attempts;
- false camera-stability interruptions;
- deliberate camera-movement detection coverage.

Metrics are reported both across all evidence and across only
`population_eligible=true` field sessions. Development and consumed held-out
clips can therefore exercise the tooling without being misrepresented as
population evidence.

The tool is deliberately descriptive and does not emit a release verdict.

## Seed evidence

`Evaluation/fixtures/session-qualification-seed.json` contains the current v6
dip evidence:

- JULLIAN controlled development: 9/9;
- consumed Romina development: 3/3;
- consumed Pavel holdout v2: 3/3 exact count, while its separate frozen temporal
  endpoint criterion remains failed.

All three are marked **not population eligible**. They validate the aggregation
pipeline only.

## Field-data target

Before making a broad reliability claim, collect roughly 100 independent
sessions across 20–30 participants and multiple phones/setups, targeting at least
1,000 labelled movements. Evaluate by session and participant, not by treating
correlated video frames as independent samples.

Physical-iPhone qualification remains a later gate; this framework is ready to
ingest those reports when the phone campaign begins.

## Intake from live device reports

Future physical-device exports should not be hand-transcribed into aggregate
metrics. New device reports include set-specific analyzed/usable frame counts and
partial/interrupted attempt totals in addition to the existing movement count and
tracking coverage.

Keep ground truth separate from runtime output. Copy
`Evaluation/fixtures/session-review-example.json` for each field session, review
the video/session independently, and fill in expected count, setup attempts,
camera-motion truth and grouping metadata **without looking at the app result**.
Population-eligible sessions require `reviewed_without_runtime_output=true`.

Then build a sanitized session manifest:

```sh
python3 scripts/session_intake.py \\
  --session review-001.json HangInThere-live-qualification-001.json \\
  --session review-002.json HangInThere-live-qualification-002.json \\
  --output build/field-sessions.json

python3 scripts/session_qualification.py build/field-sessions.json \\
  --output build/session-report.json \\
  --markdown build/session-summary.md
```

The intake output contains no device-report samples, media, filenames, landmarks,
location, or device identifiers. Those remain in their original evidence files.
