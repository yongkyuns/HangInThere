# Three-grip RGB appearance experiment

Research result, 2026-10-23: neutral/parallel grip belongs in the label set, but
this experiment is **not ready for the phone**. Adding the class improves the
matched offline control overall and recognizes neutral grips with reviewed
orientation; chin-up accuracy regresses and automatic orientation removes most
of the neutral benefit. No production classifier, readiness rule, counter or
phone build was changed by this experiment.

## Labels and independent tasks

Grip labels are **overhand, underhand and neutral/parallel**. Neutral means the
palms face one another on fore-aft handles. Seeing a hand's edge from a side
camera is a separate observation and does not imply neutral grip. Unclear or
unsupported references remain excluded; classifier confidence can remain
unknown. Mixed and deliberately angled intermediate grips are not a fourth
trained class in this experiment.

Each actual RGB crop has directly reviewed surface labels: **palm, dorsum,
side, unclear**, plus contact **closed, open, invalid**. Surface labels are never
generated from grip and camera orientation. Both source-plus-crop and shuffled
crop-only review passes were completed. Review conflicts were inspected again
and explicitly adjudicated. These are two passes by the coding agent, **not
independent human ground truth**. Source equipment geometry can establish the
exercise grip while a particular small crop remains visually unclear.

## Data and leakage controls

- Reused fitting sources only from earlier training/validation, never earlier
  exposed test videos. Propagated 17 earlier source-label corrections to the
  archived fitting frames before this training run.
- Reviewed 120 additional public Kinetics candidates and 20 previously excluded
  candidates. Selected 50 new development recordings, provisionally 30 neutral
  and 20 underhand, with source roles frozen before SDK extraction. Nine source
  labels were corrected or excluded after examining the actual inputs, before
  fitting. One native export failed and remains explicitly failed.
- Fitting contains 297 frames and 363 actual hand crops from 90 training and 21
  validation recordings. There are 284 training and 79 validation crops. Only
  **86 overhand, 47 underhand and 24 neutral training crops** have sufficiently
  clear surface/contact/grip references for grip loss. The remaining training
  inputs still supervise appearance and/or contact where applicable.
- New development recordings initially used fixed 2/4-second samples. Because
  the original SDK produced zero neutral validation crops, development-only
  coverage inspection added an automatic wrist fallback and fixed 6/8-second
  samples for all 50 sources. Both stages are retained. These changes happened
  before any fitting or fresh-test image inspection.
- Reserved 100 new public recordings before model fitting. Reviewed all 100
  source sequences only after freezing both models and crop/detector scripts.
  There were 53 eligible source candidates: 29 overhand, 13 neutral, 11 underhand.
  Selected eight per provisional class by recording SHA, before crop extraction
  or classifier predictions. No selected test video was subsequently replaced.
- Fixed requested test times were 2/4/6/8 seconds. There are 85 unique frames
  from 96 requests; 11 requests reach beyond available native observations.
  Tail frames are not duplicated. The SDK supplies 48 crops and automatic wrist
  fallback adds 86, giving **134 actual test crops**. No test crop was manually
  localized, repaired or selected using model confidence.
- Reviewed all 134 test crops twice before scoring. Four provisional source
  labels were corrected, one source grip could not be verified, and dismount
  frames were marked inactive. The 24 selected sources remain in the audit;
  23 have verifiable grip references: **11 overhand, seven underhand, five
  neutral**. Original provisional annotations and explicit corrections are both
  retained in the test fixture. Test crops include 36 unclear surfaces, 52
  dorsal surfaces, 18 side surfaces and 28 palms.
- Recording hashes and publisher video groups are disjoint across fit and test;
  fit/validation source roles are also checked. This does not establish an
  athlete-identity holdout across unrelated public uploads.

The review corrected a black-tank rear-view source from underhand to overhand,
a gray-shirt front-view source from neutral to underhand, a gloved doorway-bar
source from neutral to overhand, and a blue-shirt machine source from neutral
to overhand. Close hand spacing or a side camera was insufficient evidence for
the provisional labels. These changes happened **before the first test scores**.

## Model and matched control

The initial joint model failed validation: zero correct side-surface references
and zero correctly rejected contact-negative examples at argmax. It is retained
as a failed validation trial, not selected using the fresh test.

The final staged model fine-tunes a MobileNetV3-small RGB encoder using direct
surface supervision, then freezes that encoder. Contact is fitted separately.
Paired binary and three-class grip heads consume the **same RGB embedding,
predicted surface probabilities and camera-view probabilities**. Grip training
does not change the appearance encoder. They share all 250 non-grip state
tensors and appearance/contact calibration; grip calibration differs and is
selected using validation only. The three-class model has 1,112,873 parameters.

Hand input is 128×128 RGB; view input is front/rear/oblique probability. The
paired heads are compared on identical pixels. The binary control masks neutral
grip loss but still uses those images for surface/contact supervision. It is a
newly trained matched control, not the previously deployed or historical model.

The torso orientation model is the frozen previous expanded experiment. Its
torso crops omit the head and retain bounded face support that cannot reverse
the torso's front/rear decision. Reviewed orientation is reported separately as
a privileged diagnostic input. It is not app accuracy. Missing automatic torso
context remains unavailable rather than supplying reviewed orientation.

Model SHAs:

- Binary: `f791298fb8976b1c525b1b5b38b488c7b2ad1e4c71d457c19551a7b39dabc82f`
- Three-class: `ba52da15910afe64f56e5d0f6d84149a6513538f365e8187c7c1f69f363c11f2`
- Frozen torso: `4c0f4f766d1d4db1c8d8fb8446e748a3f9e4ea4dcdc0a7ca556855ece86af6b9`

## Fresh video results

Raw argmax accuracy below measures whether the highest score names the correct
grip on a visually verified gripping-hand crop. It does **not** mean an accepted
classification. Neutral cannot be correctly named by the binary control.

| Actual valid hand crops | Binary, reviewed view | Three-class, reviewed view | Binary, automatic view | Three-class, automatic view |
|---|---:|---:|---:|---:|
| Overhand (53) | 38/53 | 41/53 | 25/53 | 37/53 |
| Underhand (37) | 26/37 | 18/37 | 26/37 | 18/37 |
| Neutral (17) | 0/17 | 17/17 | 0/17 | 2/17 |
| All (107) | 64/107 | 76/107 | 51/107 | 57/107 |

Nine valid neutral crops lack automatic orientation. They remain unknown and
remain in these denominators. The remaining six neutral mistakes are among
eight crops with automatic orientation available.

When missing and invalid hand inputs are included, the three-class automatic
pipeline has **57 correct raw choices out of 140 expected active hand slots**:
37/66 overhand, 18/50 underhand, 2/24 neutral. There are 16 missing hand inputs,
17 invalid active target crops, and ten available active crops without torso
context. Thus the valid-crop table alone substantially overstates coverage.

Acceptance thresholds were fixed before test: at least **0.8 grip score and 0.8
closed-contact score**. With automatic orientation, **all 140 expected hand
slots remain unknown** for both models. With reviewed orientation, the
three-class model accepts four correct neutral crops and two incorrect
overhand crops. It is not safe to treat its raw choices as reliable labels.

The fixed-time clip diagnostic averages hands once per unique frame and then
frames once per source. It includes all inference pixels, including inactive
frames; manual activity/contact labels do not choose inference samples.

| Raw video-level choice | Binary, reviewed view | Three-class, reviewed view | Binary, automatic view | Three-class, automatic view |
|---|---:|---:|---:|---:|
| Overhand (11) | 8/11 | 8/11 | 5/11 | 8/11 |
| Underhand (7) | 4/7 | 3/7 | 3/7 | 2/7 |
| Neutral (5) | 0/5 | 5/5 | 0/5 | 1/5 |
| All (23) | 12/23 | 16/23 | 8/23 | 11/23 |

A clip is accepted only when individually accepted predictions agree on at
least two distinct source times; conflicting accepted labels remain unknown.
Automatic orientation accepts no test clip. Reviewed orientation accepts two
neutral clips correctly and one overhand clip incorrectly. This samples several
times per video; it does not establish continuous frame-by-frame stability,
native-device speed, rep count or readiness behavior.

## Why it still fails

1. **View dependence remains severe.** All 17 verified neutral test patches are
   oblique views after source-label corrections. Giving these same images an
   artificial front view produces ten underhand and seven overhand choices;
   an artificial rear view produces 15 overhand and two underhand choices.
   With an oblique input all 17 become neutral. This sensitivity audit is not
   accuracy on those artificial viewpoints, but it shows why 17/17 with reviewed
   view is insufficient evidence that neutral appearance is learned robustly.
   Front/rear neutral test coverage is now absent and needs a new untouched set.
2. **The appearance classifier still confuses dorsal hands.** On all 134 actual
   crops, surface argmax gets 19/28 palms, 6/52 dorsal hands, 10/18 side surfaces,
   and 23/36 unclear references correct. Twenty-seven dorsal hands are called
   palms. That failure persists independently of automatic orientation and
   explains why simply improving face detection cannot solve the task.
3. **Automatic torso orientation remains weak.** On 59 active eligible frames
   with a crop and usable torso input, it is correct on 25/26 front, 10/24 rear,
   and 3/9 oblique views. The torso crops were visually inspected and all pixel
   reconstructions checked; there is no blanket inverted-coordinate crop bug.
4. **Crop and contact coverage remain limiting.** Fallback crops sometimes
   contain forearms, a head or background. At the fixed contact threshold, 35
   closed references pass, 68 closed references fail, and all 27 negative
   references are rejected. Four overlapping-hand cases are omitted from local
   contact-quality scoring, not from grip/pipeline accounting. The 27
   reference-excluded/nonactive crops yield no accepted grip labels, but this
   also occurs alongside zero automatic accepted active labels; it does not
   establish useful setup-versus-exercise discrimination.

## Artifacts, verification and next step

Durable repository fixtures:

- `Evaluation/fixtures/grip-threeway-protocol-v1.json`
- `Evaluation/fixtures/grip-threeway-source-roles-v1.json`
- `Evaluation/fixtures/grip-threeway-additional-times-v1.json`
- `Evaluation/fixtures/grip-threeway-fitting-v1.json`
- `Evaluation/fixtures/grip-threeway-test-reserve-v1.json`
- `Evaluation/fixtures/grip-threeway-test-source-labels-v1.json`
- `Evaluation/fixtures/grip-threeway-test-patches-v1.json`
- `Evaluation/fixtures/grip-threeway-results-v1.json`

Local native frames, models, all review sheets and first results are under
`build/grip-threeway/`. The human-readable input examples are
`build/grip-threeway/test-comparison/examples.png`; full source and actual-crop
reviews are in `test-first-pass/` and shuffled actual-crop reviews are in
`test-second-pass/`. `pre-test-freeze.json` pins fitting inputs, protocol,
detector/crop scripts, both hand models and torso model before test exposure.
`test-comparison/report.json`, `predictions.jsonl` and `clip-predictions.jsonl`
retain the first scores. `test-comparison/audit.json` contains pixel and
counterfactual checks. `provenance/` snapshots the experiment sources and hashes.

All **497 hand crops** and **266 torso crops** reconstruct exactly from their
source pixels, boxes and bilinear resize; frame and crop hashes are checked.
Paired models share their appearance/contact state, fitting and test source
hashes are disjoint, and eager/TorchScript maximum output difference is zero.
The full Python suite passes **401 tests**, including the **59 grip tests** for
independent surface references,
review conflicts, manual held-out crop prohibition, neutral binary masking,
wrong-target crops, missing orientation and contact rejection.

The next data work should target **dorsal hands and neutral grips across front,
rear and side views**, with independently checked equipment axes and labels.
Collect genuine closed-hand negatives/poor crops separately from identity
mistakes. Qualify a surface model first, then compare grip fusion that cannot
use viewpoint as a class shortcut. Keep this newly exposed set as a regression
set and reserve different videos for the next final test. More near-duplicate
frames from these same sources would not resolve the demonstrated gaps.

Reproduce scoring with the existing frozen local artifacts:

```sh
build/grip-training/venv/bin/python scripts/evaluate-threeway-grip.py \
  build/grip-threeway/test-approved.jsonl \
  Evaluation/fixtures/grip-threeway-test-patches-v1.json \
  Evaluation/fixtures/grip-threeway-test-reserve-v1.json \
  build/grip-threeway/staged-models build/grip-expanded/model \
  build/grip-threeway/pre-test-freeze.json build/grip-threeway/test-reproduction
build/grip-training/venv/bin/python scripts/audit-threeway-grip.py build/grip-threeway
```

Use a fresh scoring output directory; the evaluator refuses to overwrite an
existing report. Reproducing native extraction also requires the local public
archive and Apple Vision/MediaPipe dependencies used by the recorded pipeline.
