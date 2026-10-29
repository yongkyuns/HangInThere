# Learned overhand / underhand classifier

> **2026-10-22 reference correction:** the weighted source `irFDiPohB8E` was
> mislabelled rear/underhand; it is front/overhand. Earlier claims of 55 failed
> chin-up patches included 45 incorrectly labelled pull-up patches. Those claims
> are withdrawn. See [the corrected context experiment](GRIP_CONTEXT_EXPERIMENT.md)
> and `public-grip-expansion-corrections-v2.json`. Original raw reports remain historical.

**Historical landmark prototype.** Current development uses the requested
[RGB hand-patch classifier](IMAGE_PATCH_GRIP_CLASSIFIER.md), which also allows a
clear raised-hands athlete with a spotter. Its measured underhand failure is
documented; neither prototype is enabled in the app.

The [2026-10-22 data expansion](GRIP_DATA_EXPANSION.md) also corrects two earlier
public grip labels and records a fresh before/after pixel-model comparison.

A first trained post-hand-detection prototype is implemented in shared Kotlin.
Its only learned classes are **overhand (pull-up)** and **underhand (chin-up)**.
Unknown is an abstention, not a third trained class. It is not enabled in either
app, and improved real-world accuracy has not yet been established.

The subsequent [public-data expansion](PUBLIC_GRIP_EVALUATION.md) restores all
33 videos, adds provisional visual grip annotations and measures separate
recordings. It improves overhand results but exposes complete underhand
unavailability upstream of the classifier; it does not qualify binary accuracy.

## Implementation

`BinaryGripFeatures` reuses the six hand joints already supplied by both native
adapters: wrist, index/middle/pinky MCPs, thumb CMC and thumb tip. Wrist-relative
3D vectors are normalized by hand span and expressed in the athlete's estimated
body frame. Camera axes alone must not decide whether a palm faces the athlete.
The 19 features include these vectors, the palm normal and anatomical side.
Shared body/hand identity checks still reject missing, contradictory, duplicate,
out-of-crop or invalid observations. Feature extraction does not use labels.

The network has **19 inputs → 12 ReLU units → one sigmoid**, with **253 learned
parameters** and 38 normalization values. It adds no new app inference dependency
or image model. The current model is 8,335 bytes as JSON (1,164 bytes for a
hypothetical float32 representation of its numeric parameters/normalization).
This describes model size, not measured phone latency or battery cost.

A fixed development decision score of 0.90 permits either label; uncertain or
out-of-range features remain unknown. Scores are not calibrated probabilities.
Two-class confidence alone cannot establish reliable rejection of neutral/mixed
grips, closed/occluded hands or other unsupported conditions; those need separate
evaluation and calibration. Raw hand classifications also need the existing
source freshness, bilateral temporal confirmation and coverage policy before
becoming workout labels. Rep counting does not depend on them.

Artifacts validate feature/label versions, dimensions, finite bounded weights and
normalization. The only accepted qualification is `developmentOnly`; promotion
and automatic model selection are deliberately not implemented. The Apple bridge
is opt-in, rejects malformed artifacts with an error and does not activate it in
`WorkoutGripAnalysis`. No learned weights are committed or bundled.

## First local training run

Two existing recordings supply 179 eligible hand samples from one athlete:

| Label | Source evidence | Labelled contact frames | Eligible hands | Training fit: correct / unknown / wrong |
| --- | --- | ---: | ---: | ---: |
| Overhand | Source images visually inspected; provisional label pending owner confirmation | 51 | 102 | 99 / 3 / 0 |
| Underhand | Preserved owner-declared chin-up intake and visual development inspection | 43 | 77 | 76 / 1 / 0 |

Both recordings are training data. These figures are **resubstitution**, not test
accuracy. Adjacent frames are not independent examples. No recording/person-
disjoint validation or test set exists here, and no improvement claim follows.
The previous chin-up probe's low per-attempt coverage is not shown to be fixed:
this training fit is conditional on eligible detected-hand features and does not
measure full-session credited coverage or native phone performance.

The development run uses native Mac production decoding/orientation/body pose,
then the checksum-pinned production MediaPipe body/hand model bundles through
Python 0.10.32. Shared Kotlin selects crop requests, first identity-valid hand
candidates and features. This host extractor runs the ordered candidate crops
offline; it is not a measurement of native asynchronous hand-stage cost.
The initially installed host 1.0.1 package crashed in its Metal calculator even
with CPU selected; the local learning environment pins compatible 0.10.32.

Validation on the final implementation:

- 87 selected JVM grip-related checks pass, plus six native Apple classifier
  tests and five Python training/data-split tests. The iPhone Kotlin target
  compiles. Analytic test models validate mechanics only.
- All **336** per-hand decisions on the two full sampled recordings agree
  through JVM and actual Apple FFI with the real trained model. Maximum score
  difference is below `3e-17`; malformed-model rejection also passes.
- Reassembling the labelled data from explicit source windows reproduces its
  SHA-256 exactly. Source/data/model hashes and logs remain under ignored
  `build/grip-training/`; no media, samples or weights were uploaded.

## Reproduce or extend

Use JDK 17, Xcode and an isolated Python environment with
`Evaluation/grip-learning-requirements.txt`. The stages are intentionally
separate so labels and dataset splits can be reviewed before training:

1. Compile `Evaluation/GripTrainingFrames.swift` with the production replay
   sources in place of the public-replay entry point. Run `grip-frames VIDEO
   NEW_DIRECTORY`. It exports unlabelled upright samples at most four per source
   second, their original body poses, pixel hashes and source provenance.
2. Run `extract-binary-grip-observations.py body FRAMES OUTPUT.jsonl`.
3. Run `BinaryGripLearningPipelineTests` with `BINARY_GRIP_MODE=plan`,
   `BINARY_GRIP_INPUT` and `BINARY_GRIP_OUTPUT` to generate shared crop requests.
4. Run `extract-binary-grip-observations.py hands PLANNED.jsonl FRAMES OUTPUT.jsonl`.
5. Run the same Kotlin data bridge with mode `features` to select hands and
   extract real shared features. No desired label influences hand selection.
6. Provide explicit annotations with source hashes, contact windows, grip labels,
   subject IDs, splits and evidence. Run `prepare-binary-grip-training.py
   ANNOTATIONS.json NEW_DATA.jsonl`.
7. Run `train-binary-grip.py DATA.jsonl NEW_MODEL_DIRECTORY`. It requires both
   classes in train/validation/test with disjoint recordings **and subjects**.
   `--development-only` permits train-only prototyping and prevents test claims.
8. Replay mode `score` with `BINARY_GRIP_MODEL` and verify actual Apple FFI using
   `BinaryGripClassifierBridgeCheck.swift`. All Kotlin local data-bridge runs use
   `:shared:jvmTest --rerun --tests '*BinaryGripLearningPipelineTests'` so changing
   environment paths cannot reuse a prior report.

The trainer does not split adjacent frames randomly or use automatic grip
predictions as target labels. Independent recordings of both grips, different
athletes/views and explicit unsupported-condition negatives are the next inputs
needed for an accuracy comparison, rejection calibration and eventual app
integration. Use image-crop features as a later candidate if detected landmarks
cannot carry enough reliable information.

The post-landmark learned-classifier approach has precedent in Google's
[custom gesture-recognition workflow](https://developers.google.com/edge/mediapipe/solutions/customization/gesture_recognizer).
This implementation uses its own small trainer and shared inference rather than
the deprecated Model Maker package.
