# Posture and motion replace learned contact admission

The new **offline** path establishes exercise from arm motion without requiring
the RGB contact score. It is implemented and tested, but it does **not** yet
provide usable automatic grip recognition: all 30 recording-held-out regression
videos still produce unknown labels. The live phone uses a different geometric
classifier; no RGB model or rep-policy change was installed on the phone.

## Exercise evidence

Each visible shoulder–elbow–wrist chain earns its own source-timed evidence:

1. Observe an overhead, extended arm for at least three observations spanning
   0.3 seconds. A reach or static overhead pose cannot activate the gate.
2. Observe shoulder rise of at least 12% of arm length and elbow flexion of at
   least 25 degrees, to 140 degrees or less, while the wrist stays within 12%
   of the original arm length. Require 0.12 seconds of this motion.
3. Admit hand evidence only from an arm that established exercise. The other
   arm and hips may be outside the image.
4. Revoke that arm's permission immediately on missing joints, release posture,
   wrist displacement, a camera/scale discontinuity, a source gap over 0.4
   seconds or a subject change. Regressed source time never earns dwell.

This is an exercise-admission heuristic, not a rep counter, direct grasp
measurement or proof that the athlete's feet are unsupported. It assumes an
upright, fixed camera. Motion never supplies an anatomical or grip label.
Losing eligibility clears cached hand support; a later regrasp earns new
evidence. Current qualifying RGB and two distinct source images at least one
second apart remain necessary. Two hands in one frame are one temporal vote.

The first candidate required both hips and both arms throughout. Its negative
result is preserved in `build/grip-motion-v15/`. The per-arm revision in
`build/grip-motion-v15b/` removes that structural requirement. It was developed
after the first result, so it is an **exposed regression**, not a second blind
test. No model was fitted or confidence threshold lowered in either run.

## Fixed comparison and separate recordings

All four comparisons use identical pixels, automatic crops and model outputs:

| Comparison | Learned contact required | Exercise motion required |
|---|---|---|
| Image-contact control | Yes | No |
| Ungated removal control | No | No |
| Motion with contact control | Yes | Yes |
| Requested motion admission | **No** | **Yes** |

The v11 anatomy/readability model, earlier neutral image head and torso-only
orientation model remain frozen. All retained confidence thresholds remain
0.8. Front/rear orientation is required even for neutral; side appearance alone
never means neutral. Face absence is not a rear vote. The contact output is
retained only for comparison: replacing every contact score with zero or one
leaves the new path's complete decision sequence identical. Scores are model
evidence, not calibrated probabilities of correctness.

The cohort combines all 16 earlier temporal-test recordings, five localization
test recordings and nine previously exposed crop/orientation diagnostic
recordings. Every source group and recording hash is disjoint from fitting
**and calibration** sources of all three models, including every v11 role.
This is recording separation, not verified athlete independence. Prior exposure
makes this regression evidence, not fresh qualification. Final reserves remain
unopened. No new media was downloaded or decoded.

There are **969 native body observations** and **214 scheduled RGB frames**
across 29 decoded recordings. The earlier native failure stays in the 30-video
denominator. Thirteen source grips are known: five overhand, four underhand,
four neutral. Seventeen remain unknown. Eight known references are front/rear;
five are oblique and outside the supported scope. Difficult and assisted-child
footage means this cannot establish representative adult accuracy.

All source sequences were directly inspected before new predictions. Existing
grip references are retained; localization-only grips remain unknown. New
lifecycle labels are agent annotations, not independent human ground truth:

| Sampled phase | Frames | Recordings with setup/release samples |
|---|---:|---:|
| Exercise | 125 | — |
| Setup/reaching | 29 | 8 |
| Release/rest | 16 | 8 |
| Other exercise | 11 | — |
| Uncertain/edited/competing target | 33 | — |

Uncertain frames stay in accounting. Setup and release cannot earn correct
exercise-grip credit merely by matching a recording's grip label. These are
sparse source-frame annotations, **not continuous transition-time truth** or
proof that every instant of a release was recognized. The new gate cannot
count a rep; existing rep counting is unchanged.

## Results and remaining blockers

All four automatic comparisons return **0 correct, 0 known wrong, 30 unknown**
video labels, including unknown on all 13 known-grip recordings. Zero wrong
decisions does not establish reliability when every grip is rejected.

| Body-motion candidate | Eligible exercise RGB frames | Eligible setup frames | Eligible release frames |
|---|---:|---:|---:|
| Initial paired-arm/hip gate | 3 / 125 | 0 / 29 | 0 / 16 |
| Per-arm revision | 6 / 125 | 0 / 29 | 0 / 16 |

The revision observes exercise in 43 native pose observations across five
recordings; only six scheduled RGB samples across three recordings coincide
with eligibility. Continuous posture/motion acquisition still fails too often
for deployment. Five eligible RGB samples stop at the automatic view/admission
stage; one stops at remaining hand evidence. Low readability also affects some
hands. The inherited overhead-wrist heuristic can reject a hand at the top of
a chin-up; this remains an explicit limitation. Image contact is no longer the
immediate blocker in the new path.

A **post-score privileged diagnostic** supplies reviewed front/rear orientation
with the same automatic crops, motion gate and hand thresholds. It produces
one correct underhand recording and 29 unknowns. This is not automatic
performance. The underhand video's motion and dorsal-hand/readability evidence
work, while weak torso orientation prevents the automatic chin-up label.

![Actual automatic crops and motion admission](../../build/grip-motion-v15b/examples.jpg)

These selected examples explain failures; they are not an accuracy sample.
Red boxes are exact automatic crops. The shown torso score is the current
frame's output; actual decisions use causal orientation history. The chin-up
has readable dorsal hands, the rear overhand example also has weak readability,
and setup/release controls earn no exercise permission.

## Verification and storage

Twenty behavioral tests pass, covering setup holds/reaches, rise/flexion,
partial-body visibility, per-arm permission, immediate release, source gaps,
target changes, duplicates, clock regression, label leakage, unchanged image
thresholds and contact independence. The audit verifies both protocol freezes,
identical RGB outputs across revisions, all contact counterfactuals, source
separation and 152 earlier v7/v9 pins. The v11 model and fitting inputs are
also pinned in both new freezes.

Scripts under `scripts/` are `grip-exercise-motion-v15.py`,
`grip-exercise-motion-v15b.py`, `evaluate-grip-motion-v15.py`,
`evaluate-grip-motion-v15b.py` and `audit-grip-motion-v15.py`. Evaluators create
their protocol in `prepare` before `evaluate` and refuse output overwrites.
The revision copies the first run's exact frozen lifecycle labels. Retain both
first scores instead of rerunning into their existing directories.

Detailed protocols, references, predictions, trajectories and verification
live in `build/grip-motion-v15{,b}/`; durable summaries/references are
`Evaluation/fixtures/grip-motion-*-v15.json`. CPU inference took about 20
seconds per run, which is not phone latency. Existing videos, crops, weights
and environment are reused. Cache cleanup found no remaining allowlisted
compiler caches; about **197 GB decimal** was free. Original evidence is kept.

Next, establish a repeatable supported-view recording where posture/motion
and torso direction remain observable throughout a set, then test that fixed
pipeline on new videos with setup/release intervals. This result does not
justify a phone model swap, lower confidence thresholds or another contact-head
training run.
