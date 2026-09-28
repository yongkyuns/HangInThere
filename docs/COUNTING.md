# Timestamp-based movement counter (P2 prototype)

One framework-free `ExerciseCounter` value is used by the actual replay controller,
core tests and saved-observation diagnostic. No extra package, model, background
service or app target is added. Vision remains the provisional production backend.

**This counts observed image-plane movement patterns, not valid exercise reps.**
The UI explicitly displays **Form unverified**. There is no accepted-rep counter,
chin/bar measurement, calibrated dip-depth check or exercise classifier yet.
The user selects pull-up or parallel-bar dip and an anatomical left/right arm.
Bench dips are not automatically identified or rejected by this counter: selecting
an exercise is a user input, not classifier evidence.

## State and counting convention

- Acquire a sustained extended-arm start before attempting any count.
- Observe departure, then a sustained bent-arm endpoint with shoulder travel in
  the expected image-vertical direction. A confirmed fixed bar supplies an
  independent hand/bar compatibility reference; the moving wrist is not the
  body-motion origin.
- Pull-up: increment on the sustained bent endpoint, then require extension before
  another count. A final hold at the top keeps its movement count without requiring
  a descent. Chin clearance remains unverified.
- Dip: mark the bent endpoint, then increment only on sustained return to extension.
  Depth and form remain unverified.

Returning to extension before the bent endpoint produces one **partial attempt**
under this provisional policy, not a medical/coaching verdict. Missing/ambiguous
observations, discontinuous geometry, source-time gaps or inference failures
interrupt an active attempt once. Re-establish the extended start to proceed.
EOF interrupts an unfinished attempt; it never manufactures a completion.

Only displayed source frames advance the controller's counter. An inference result
waiting in the replay queue does not count early. Pausing preserves state because
no source frames elapsed; resuming cannot count a pending frame twice. Restart,
source replacement, close, or changing exercise/arm clears counting state. Changing
exercise/arm also rewinds the video rather than mixing policies in one set.

## Fixed provisional policy v2

These engineering constants were set before running the new counter on retained
real model predictions. They are not learned from annotations or validated exercise
acceptance criteria, and are not exposed as per-video tuning controls.

| Parameter | Initial value |
| --- | ---: |
| Extended interior elbow angle | >=155 degrees |
| Departure angle (hysteresis) | <140 degrees |
| Bent interior elbow angle | <=100 degrees |
| Continuous endpoint evidence | >=0.12 source seconds, >=2 distinct samples |
| Largest source-time gap | 0.35 seconds |
| Required signed shoulder travel | 0.20 starting arm lengths |
| Maximum change in wrist-to-bar normal offset (when a bar is confirmed) | 0.25 starting arm lengths |

An arm length is the sum of its projected shoulder–elbow and elbow–wrist lengths
at the extended start and is used only as a scale. Policy v2 **does not require
the current projected arm length to remain close to that starting value**.
That removes the foreshortening failure found in the real pull-up diagnostic.

With a confirmed bar, the wrist's signed perpendicular offset from the fixed
observed bar line is compared with its start offset. Motion along the bar is
allowed; a large normal-offset change interrupts the attempt. This is 2D
compatibility evidence, not proof that the hand physically grips the bar.
Shoulder travel uses the fixed camera/bar reference rather than wrist displacement.
Without a confirmed bar, the diagnostic falls back to fixed-camera shoulder Y and
reports `referenceMode: fixedCameraOnly`.

Existing `ArmMeasurement` availability checks remain in force (0.3 joint scores,
visible unique joints, bounded coordinates, minimum segment lengths). Scores are
not calibrated reliability probabilities.

This assumes one person, a fixed camera, steady hand contacts and a suitable view
of the selected arm. No automatic athlete identity or arm switching is performed.
Anatomical identity errors, occlusion with confidently wrong landmarks, camera
motion and foreshortening can still produce incorrect results. No temporal filter
or plausible-angle clamp hides those errors. Do not infer 3D joint measurements.

## Evidence and execution

Core tests use original analytical landmark sequences to cover both conventions,
partial attempts, holds, jitter, separate endpoint dwell, duplicate/backward/invalid
timestamps, source gaps, missing or low-confidence arms, ambiguous people, contact
jumps, wrong movement direction, EOF and reset. These are logic tests, not model
accuracy tests. Decoder/controller tests use real AVFoundation-generated video
with a named test estimator; actual Vision tests remain separate and fatal.

The existing real-video integration test now feeds its actual Vision observations
into the counter and logs the unverified summary. The dataset workflow also runs:

```sh
./scripts/count-replay.sh \
  Evaluation/output/ci-smoke/pullup_smoke/observations.jsonl \
  pullUp left build/pullup-movement-diagnostic.json
```

This compiles the exact production analysis code on Linux or macOS. Inputs must
have ordered frame indices and real `source_pts` timestamps. Still-image sequences
are rejected: no assumed frame rate or invented timestamps. The diagnostic records
input/source/executable hashes, source revision/dirty state and toolchain. It
refuses to overwrite previous reports. Saved-prediction replay is not new inference.
The pose-only evaluator retains `rep_metrics: not_implemented`; temporal scores
now come from the separate, hash-bound evaluator linked below, not pose labels.

A preliminary run on 40 retained Vision predictions from the reviewed four-second
pull-up smoke video produced **one unverified movement at source time 2.2 seconds**
with the left arm. That is a single-source diagnostic, not held-out counting
precision/recall. The recording crops the head at the top, so it cannot establish
chin clearance. Thresholds were not changed after this run.

## Remaining qualification

A freshly compiled Apple-platform run is required for every code revision. The
known simulator missing-Vision-weights check remains in CI, without skipping,
`continue-on-error`, runtime asset copying or test-only fallback in the app.
Physical iPhone inference/performance is still untested.

Full parallel-bar-dip videos, independent temporal annotations, athlete/viewpoint
coverage and endpoint measurements are required before claiming accurate counts
or valid reps. Photographs and analytical trajectories cannot close those gates.

## Continuous-video temporal diagnostic

[Evaluation/TEMPORAL.md](../Evaluation/TEMPORAL.md) defines the first independently
marked event comparison on complete decoded clips. Observed cycles, count errors
and excluded initial portions are reported separately from form acceptance. The
production counter and its provisional thresholds are unchanged by that tooling.

## Controlled setup scope / bar work

The user's target is **one athlete and a fixed phone**. Spectator-heavy or moving-camera
clips above remain stress diagnostics, not requirements for adding identity tracking.
[Guided bar setup](BAR_SETUP.md) now provides confirmed apparatus references in the
replay UI. Policy v2 consumes that reference for wrist/bar compatibility and resets
the counter whenever the reference is confirmed or cleared so evidence modes are
never mixed. Bar setup still does **not** establish chin clearance, dip depth, lockout
or valid form.
