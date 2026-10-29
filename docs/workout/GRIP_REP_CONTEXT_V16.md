# Reuse exercise state and evaluate torso direction independently

The RGB research pipeline now reuses the **actual shared pull-up counter's
phases**, instead of running the additional motion detector introduced in v15.
Torso direction is measured independently of hand ownership. This makes the
failure stages clearer, but **automatic grip labels remain unknown on all 30
recordings**. No model, confidence threshold, rep policy or live phone default
was changed. The new shared helpers are consumed by the offline experiment;
the phone still uses its existing geometric grip classifier.

## Changes

`WorkoutGripExerciseContext` consumes the counter's phase, usable-frame state
and observed release. The initial ready/hanging position cannot admit grip
evidence. Outbound/returning movement activates the context; a ready position
between reps may retain it. Release, tracking loss, seeking-start, finish,
source discontinuity or an owner/subject change clears it. The original source
clock and activation cutoff prevent old pending RGB from acquiring new credit.
There are no new elbow, travel or posture thresholds in this helper.

The replay invokes `ExerciseCounter` policy 8, `WorkoutMotionFeatures` and
existing torso geometry in Kotlin/JVM. It does not reproduce them in Python.
Because these saved poses lack a verified apparatus reference, the replay seeds
a support line from the first observed extended-arm wrist(s), then uses current
wrist/support association to remove released hands. **This is a wrist-reference
counter diagnostic, not a complete replay of the app's native setup or physical
bar detector.** It does not qualify exercise form or load.

The first run used the existing phone `bodyBasis` view gate. That check also
requires usable wrists and hips to establish the full hand/athlete frame.
The subsequent `WorkoutGripTorsoView` separates torso direction from that hand
contract: shoulders retain the same primary/auxiliary identity match, 0.5
landmark scores and 0.85 camera-facing alignment. Strong contradictory hips
still reject identity. No face or hand points supply direction. A scoped
upright-camera shoulder-axis branch is available when hips are unobservable;
it requires the observed shoulder line within 30 degrees of horizontal. All
supported outputs in this real-video run used the full shoulder/hip branch;
the fallback has unit coverage only and is not field-qualified.

Frozen v11 hand anatomy/readability outputs and the prior neutral head are
reused byte for byte. Surface/readability/neutral thresholds stay 0.8. Side
appearance alone never means neutral. Learned contact is unused. The redundant
wrist-above-shoulder check is removed from RGB admission: current support and
exercise state already supply that context. Two distinct source-second RGB
images at least one second apart must agree. A confirmed label can be retained
within the same active context; retained labels are distinguished from new
hand observations and earn no new classification credit. Qualified conflicting
hands, view loss/change, release or tracking loss revoke the label.

## Recording separation and controls

The same exposed v15 cohort remains fixed: 30 recordings, including one native
failure, 969 native body observations and 214 scheduled RGB frames. Source
groups and recording hashes remain disjoint from fitting and calibration of
all frozen models. This is regression evidence, not a fresh qualification or
verified athlete-independent test. Existing source-frame lifecycle and grip
references remain unchanged agent annotations, not independent human gold.
No final reserve was opened and no footage moved into training.

| Sampled source phase | Frames | Counter exercise eligible | Grip labels visible |
|---|---:|---:|---:|
| Exercise | 125 | 10 | 0 |
| Setup | 29 | 0 | 0 |
| Release | 16 | 0 | 0 |
| Other exercise | 11 | 0 | 0 |
| Uncertain/edited/competing target | 33 | 0 | 0 |

The v15 per-arm gate admitted 6/125 exercise RGB samples; reusing the counter
admits 10/125. This still represents very poor exercise acquisition coverage.
Neither the RGB-view comparator nor the independent torso-view revision yields
a new accepted grip video label: **0 correct, 0 known wrong, 30 unknown**,
including unknown on all 13 known-grip videos. Sparse sampling, difficult views
and unobservable inputs remain in the denominator.

An additional **12 isolated negative segments / 96 native observations** cover
seven setup and five release windows. Each counter/context starts empty; the
original automatic subject tracking is retained. Windows are conservative spans
between consecutive same-phase source samples. All 12 produce **zero counted
movements and zero exercise-admission frames**. They are not continuous
transition-time ground truth or an exhaustive false-start qualification.
These controls were added after the main scores without changing either policy.

## Torso direction measured separately

These are source-frame output counts over the same 214 sampled frames, not
grip accuracy or independent trials:

| Torso stage | Basis available | Correct front/rear | Wrong front/rear | Unverifiable accepts | Unknown |
|---|---:|---:|---:|---:|---:|
| Existing coupled phone geometry | 34 | 14 | 0 | 5 | 195 |
| Independent torso assessment | 65 | 28 | 1 | 7 | 178 |

The additional correct outputs come with an error: a rear-facing two-athlete
scene receives a front output. That frame does not receive exercise admission
or a grip label. No oblique-reference frame is accepted in this run. Greater
view coverage is **not** sufficient evidence for deployment, especially with
the newly exposed wrong direction.

![Actual crops and remaining failure stages](../../build/grip-rep-context-v16b/examples.jpg)

The rear overhand example has active counter context and a supported rear view
(alignment 0.913), but hand readability scores 0.48/0.57 reject both patches.
The chin-up example has active counter context and a readable dorsal hand
(surface/readability approximately 0.94/0.94), but estimated torso alignment
0.828 fails the unchanged 0.85 view boundary. The third row shows the wrong
front/rear output in the two-athlete scene. The setup reach in the last row
does not activate exercise. These selected examples explain stages; they are
not an accuracy sample.

This rules out learned contact as the current immediate cause. The remaining
work is to establish a repeatable supported capture with reliable continuous
body context, torso direction and readable hands. These results do not justify
another broad training run or lowering thresholds to turn unknowns into labels.

## Verification and reproduction

All **798 shared JVM tests** and **nine Python policy tests** pass. New tests
cover phase-only admission, setup, release, regrasp, timestamps, retained labels,
conflicting hands, unchanged confidence thresholds, no contact requirement,
torso identity and missing wrists/hips. The opt-in JVM bridges also execute the
actual full-rate replay and isolated negative controls. No Apple UI or device
performance test is claimed.

`audit-grip-rep-context-v16.py` verifies the 277/284 protocol pins, original
native poses and automatic target selection, identical counter fields across
the torso revision, unchanged frozen hand outputs, source-clock joins and
zero-count negative controls. Both first scores are retained. The initial
sandboxed MediaPipe attempt failed at macOS graphics initialization; the
authorized CPU-delegate run completed using the pinned phone pose model with
Python SDK 0.10.32. This differs from the iPhone SDK/runtime.

Reproduction uses `evaluate-grip-rep-context-v16.py freeze`, then `body`, the
`GripRepContextV16Tests` opt-in exporter and `score`. The torso revision uses
`evaluate-grip-rep-context-v16b.py freeze`, `GripTorsoViewV16Tests` and `score`.
Each exporter takes explicit input/output environment paths; original protocols
pin every relevant source and model. Outputs refuse overwrites. Revision inputs
are symlinks to the first run, so no pixels or SDK observations are duplicated.
The negative-window protocol and input/output files retain their own hashes.

Evidence is under `build/grip-rep-context-v16{,b}/`; the durable summary is
`Evaluation/fixtures/grip-rep-context-v16.json`. No media, models or environments
were downloaded or copied. Cache cleanup found no remaining allowlisted
compiler caches; about 195.8 GB decimal was free. Original data, models and
earlier experiments remain intact. Nothing was installed on the phone.
