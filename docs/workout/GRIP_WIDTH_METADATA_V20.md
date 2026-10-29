# Shoulder-relative grip width and supported RGB evidence

The subsequent [separate-video v21 validation](GRIP_WIDTH_VALIDATION_V21.md)
fails narrow/medium qualification. It exposes sparse width acquisition and a
shoulder-span discrepancy on provisional medium references. V20's authored
band tests and wide-only video results did not establish narrow/medium accuracy.

Grip width is independent of hand orientation. An unknown pull-up/chin-up label
does not prevent a usable width estimate. Width is metadata; it does not start
a set, count a rep, or change the full-hang counter policy.

## Measurement and stored metadata

The shared `WorkoutGripWidth` component measures bilateral wrist separation
projected along the current support, divided by shoulder span in the image.
It freezes the median shoulder span from a stable starting hang: at least five
frames spanning 0.4 seconds, with no more than 15% span variation. Shoulder
movement during a rep cannot change that denominator.

| Initial ratio band | Display | Stored name |
|---|---|---|
| Below 0.85 | Narrow | `narrow` |
| 0.85 through 1.30 | Medium | `shoulderWidth` |
| Above 1.30 | Wide | `wide` |

These are engineering categories, not a population standard or physical
centimeters. Median filtering, hysteresis and temporal confirmation stabilize
the display around boundaries. Only validated front/rear body context is used;
side views can shorten the projected shoulder span and invalidate the ratio.
Tracking interruptions, source/reference/view changes and new sets require
fresh calibration. Missing or off-support wrists cannot supply width evidence.
No learned hand-surface or RGB contact result is required for this measurement.

Existing per-rep aggregation requires 80% usable width coverage. It retains
independent orientation and width coverage and marks changes within an attempt
as varied. Width policy version 1 and a diagnostic reason are now optional
saved fields. Old records without them still decode. Completed-rep totals group
by grip and width band; raw ratio differences do not split the groups.
Incomplete attempts remain excluded from totals. A new set does not inherit
the previous set's width calibration or assume an alternating pattern.

Live grip text is larger and includes a ratio, such as
`Underhand · Medium · 1.0× shoulders`. Per-attempt review shows the ratio and
width coverage. A manual width correction suppresses the automatic ratio in
that presentation, since the old measurement does not substantiate the edit.

## Frozen video replay

`scripts/evaluate-grip-metadata-v20.py` freezes the v19 source screen, raw image
scores, models and the current shared width/counter code. The native width
exporter replays all 4,599 original body observations using the exact shared
width component. The diagnostic replay supplies the already confirmed source
view and reference support; it does not prove automatic phone acquisition.

All three supported exposed recordings have wide measured spacing: median
ratios 2.51 (owner pull-up), 1.72 (owner chin-up), and 1.94 (rear-view M02).
No new real narrow/medium width qualification is claimed. Authored geometry
tests cover those bands; rendered examples are UI fixtures, not model results.

For RGB orientation, the comparison removes only the readability veto within
the frozen supported scope. It reuses the same images and models without
training, keeps surface/neutral thresholds at 0.8, and retains temporal,
ownership, view and full-hang exercise requirements. Side-surface evidence
alone still does not identify neutral grip.

| Exposed supported recording | Correct labeled exercise frames, before → after |
|---|---|
| Owner pull-up | 29/56 → 29/56 |
| Owner chin-up | 3/48 → 5/48 |
| Rear overhand M02 | 0/38 → 19/38 |

Neither variant makes a wrong new decision on these three recordings. Across
their 461 setup/release frames, neither creates a movement, exercise admission
or visible grip label. Coverage remains incomplete and does not qualify every
rep's 80% metadata coverage. These are exposed regression/development sources,
not fresh accuracy. Twelve newly screened sources remain outside capture scope;
their exclusions are unchanged and earn no accuracy credit. Marked source-cycle
windows in the report are diagnostic intervals, not counter-owned rep records.

The learned RGB path remains offline. The phone's existing geometric hand
classifier is unchanged by this readability experiment.

## Verification and delivery

- Full shared JVM suite: 810 tests passed before the final added width-change
  case; the final targeted run passes all ten width tests.
- Apple host: 15 metadata, persistence, backup and correction tests passed.
- Generic iPhone unsigned build succeeded. Android UI compilation was not run
  because the Android SDK is absent; shared Kotlin logic was tested.
- Actual SwiftUI live/review views rendered and OCR-checked at 320 and 430 points.
- Physical iPhone was unavailable, so this update has not been installed.

Protocol, replay, reports and verification logs are in
`build/grip-metadata-v20/`. The report snapshot is
`Evaluation/fixtures/grip-width-metadata-v20.json`. Original videos, labels,
models and prior experiment outputs are retained.
