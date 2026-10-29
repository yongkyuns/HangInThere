# Public hand-patch expansion — 2026-10-22

> **2026-10-22 reference correction:** the weighted source `irFDiPohB8E` was
> mislabelled rear/underhand; it is front/overhand. Earlier claims of 55 failed
> chin-up patches included 45 incorrectly labelled pull-up patches. Those claims
> are withdrawn. See [the corrected context experiment](GRIP_CONTEXT_EXPERIMENT.md)
> and `public-grip-expansion-corrections-v2.json`. Original raw reports remain historical.

The requested collection, visual annotation, video-separated retraining and
baseline comparison are complete. **This run did not improve the classifier.**
The expanded frozen-backbone image model is retained for research and is not
enabled in either app. The installed iPhone build is unchanged.

## What was collected and labelled

Two public sources were inspected:

- **UCF101:** 100 pull-up clips across 25 related source groups, retrieved with
  ZIP byte ranges from a pinned mirror. The [UCF authors describe](https://www.crcv.ucf.edu/wp-content/uploads/2019/03/UCF101_CRCV-TR-12-01.pdf)
  related groups and 320×240 source resolution. Native extraction produced only
  five usable automatic patches across these clips. This corpus was screened
  out rather than weakening crop-quality checks or counting blurry upscales as
  useful new hand detail. No UCF clip entered this training run.
- **Kinetics-700-2020:** the original [CVDF-distributed](https://github.com/cvdfoundation/kinetics-dataset)
  pull-up training archive contains 921 recordings. A deterministic first 100
  by SHA-256 of archive member name were selected before labels or predictions.
  Many are 720p, with some 1080p recordings. The archive is 995,566,837 bytes,
  SHA-256 `5b082c056828aeb5e3e4e8e7a3abe0262e7cbadf1b8d97e6cef89d274573f9d9`.

The Kinetics action name was not used as a grip label. Full native frames,
visible palm/dorsum relative to the athlete, wrist crops and three points in each
selected recording were visually reviewed. Original publisher titles/authors
were supplementary evidence. Active windows were tightened to exclude observed
cuts, setup, release or talking. **36 recordings received provisional binary
labels; 64 remain inventoried as unresolved or unsupported.** Neutral/rotating
handles, ropes, one-arm variations and insufficient hand evidence were not
forced into either binary class. These are agent visual annotations, not an
independently adjudicated gold dataset.

The new source split was frozen before baseline scoring or retraining:

| New recordings | Overhand | Underhand | Total |
| --- | ---: | ---: | ---: |
| Training | 22 | 4 | 26 |
| Validation | 3 | 1 | 4 |
| Test | 4 | 2 | 6 |

All frames and hands from an original YouTube source stay together. The test
contains both front and rear views; training also includes oblique views. The
new grip split is a custom split within the publisher's action-training archive,
not the publisher's action-recognition benchmark. Public athlete identities are
unknown; no person-independent accuracy claim is made. Visual review found no
obvious source duplicates with the earlier corpus, and exact source/group and
known-owner split checks pass.

The existing seven training recordings remain training. No old validation/test
recording was reassigned. This gives **33 labelled training recordings**, of
which **26 actually contribute usable training patches**. The final training
set has **693 patches: 569 overhand and 124 underhand**, up from 345 patches.
Underhand training now comes from five recordings instead of one; the owner
still contributes 86 of the 124 underhand patches. This remains a small and
imbalanced development set despite the larger candidate inventory.

## Training pixels and annotation boundaries

The classifier receives only 128×128 RGB hand/short-forearm patches. Its existing
MobileNetV3-small ImageNet feature extractor stays frozen and the same two-output
head is retrained with unchanged seed, augmentation, class balancing, optimizer,
300 head steps and fixed 0.80 abstention threshold. This controls the experiment
to data expansion; it does not evaluate end-to-end CNN fine-tuning.

The automatic subject selector, hand SDK, ownership checks, minimum original
hand extent and final crop rule were unchanged. A primary body-pose failure
still prevents automatic hand work in some visibly clear shots. To recover
training pixels, four reviewed **training-only** sources received annotated
hand search regions. The same hand detector and final crop rule yielded 26
additional patches missing from the automatic pass. One release patch was
excluded, leaving **25 manual-search/SDK training patches** and 668 automatic
training patches. No artificial body pose, depth or predicted grip was inserted.

All 631 new automatic labelled-window crops and 38 candidate manual-search/SDK
crops were visually checked as contact sheets. Two open-hand/release outliers
were excluded by exact source/image/side/patch hashes, alongside the previous
owner open-hand exclusion. A source-window correction tightened only a training
clip after its actual short duration was checked. These changes preceded model
scoring and did not alter video splits. Test inputs remained automatic. Python
guards reject manual regions or patches in validation/test data.

Missing and excluded hands remain unknown in the full denominator. A manifest
records all **1,916 expected labelled-window hand observations**, with source
clock, frame and patch hashes, side, label/split, viewpoint, origin and exclusion
status. It does not silently discard difficult test frames.

## Same-video baseline comparison

The previous pixel model and retrained model score the identical frozen test
population: **274 expected hands across six new videos**, with **165 available
patches**. The remaining 109 are missing or visually excluded patches for both
models. No test video enters training or checkpoint selection.

| Model, fixed score threshold 0.80 | Correct | Wrong | Unknown | Total |
| --- | ---: | ---: | ---: | ---: |
| Previous pixel model | 110 | 55 | 109 | 274 |
| Expanded training | 85 | 66 | 123 | 274 |

Without abstention, the available patches change from **110/165 correct (66.7%)**
to **89/165 (53.9%)**. Both models get **0/55 available underhand test patches**
correct. The expanded model also introduces overhand errors. Validation changes
from 79 correct / 31 wrong / 54 unknown to 80 / 39 / 45 across 164 expected hands;
its underhand source is also not recognized correctly. Per-video and per-class
counts are retained in `build/grip-expansion/comparison.json`.

The expanded model correctly fits all 693 training patches. That training fit,
alongside poor source-separated performance, supports an overfitting concern.
It does **not** demonstrate that more data or image models generally cannot
work. In this experiment, extra data with a frozen generic feature extractor
does not solve viewpoint-dependent grip recognition. Useful next comparisons
would separate feature fine-tuning, athlete-view context and a more balanced
underhand collection, with a fresh blind test before release qualification.

## Correction to the previous evaluation

The earlier public binary labels contained a material error:

- `countix_XNFGt0WPASQ` is the publisher's [HAMMER PULL UPS](https://www.youtube.com/watch?v=XNFGt0WPASQ)
  demonstration. Closer review indicates neutral handles. Its prior underhand
  label is withdrawn; the former 0/58 "chin-up failure" claim was unsupported.
- `countix_15ypy7AMAsc` is an [assisted one-arm variation](https://www.youtube.com/watch?v=15ypy7AMAsc)
  with overlapping hands. A bilateral underhand reference was not justified.

The v1 labels and old results remain historical evidence, with a separate
correction fixture. They no longer serve as valid underhand benchmark evidence.
The new comparison above uses different, reviewed front/rear underhand sources;
its negative result is independent of those two old annotation errors.

## Verification and local outputs

- Native Apple decoding preserves source clocks and dimensions. All UCF local
  H.264 decode copies additionally match every original decoded YUV frame and
  presentation time; Kinetics uses its original MP4 bytes directly.
- The reviewed input manifest reproduces byte-for-byte. A deterministic second
  training run gives bit-identical parameter tensors and prediction JSONL; this
  was one model configuration, with no test-guided threshold/hyperparameter search.
- The 3,789,518-byte float32 Core ML export matches all **1,000 available reviewed
  train/validation/test patches** in class and threshold decisions. Maximum score
  difference is `1.57e-5`. This verifies export mechanics, not grip correctness.
- Five Python provenance/crop checks pass, including rejection of manual holdout
  patches. The unchanged shared subject tests and real-data plan exporter pass
  through JVM. No app policy, rep counter, signing setting or workout storage changed.

Tracked annotations are `Evaluation/fixtures/public-grip-expansion-v1.json`,
`grip-manual-training-regions-v1.json`, `grip-patch-expansion-exclusions-v1.json`
and `public-grip-review-corrections-v2.json`. Runtime dependencies remain isolated
in `Evaluation/grip-learning-requirements.txt`; no training dependency enters an app.

Media, native frames, per-patch labels, reviewed crop sheets, learned weights and
all detailed results stay ignored under `Data/external/grip-expansion-*` and
`build/grip-expansion/`. Private-derived weights are not bundled or uploaded.
Useful local files:

- `patch-labels.jsonl`, `patch-review/`, `manual-review.jpg`: inspect the labels.
- `heldout-examples.png`: three full-frame/hand-patch test examples.
- `comparison.json`: before/after counts on exactly the same inputs.
- `reproduced-model/report.json`, `training-receipt.json`, `native-parity.json`:
  final model, training and native checks.
- `frozen-expansion-annotations.sha256`, `frozen-training-input.sha256` and
  `reproduction-receipt.json`: frozen data and reproducibility evidence.

## Reproduction

The acquisition and preparation helpers use explicit fresh output paths. The
existing original-training exports remain required, as in the first prototype.
Use the isolated venv with the pinned requirements, JDK 17 and the previously
built native `grip-frames` exporter.

```sh
PY=build/grip-training/venv/bin/python
$PY scripts/acquire-grip-expansion.py kinetics \
  Data/external/grip-expansion-kinetics build/grip-expansion/new-intake.json --fetch
$PY scripts/prepare-public-grip-expansion.py build/grip-expansion/new-intake.json \
  build/grip-training/exporter/.build/release/grip-frames \
  build/grip-expansion/new-native --native-media

JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home \
GRIP_PATCH_INPUT="$PWD/build/grip-expansion/new-native/frame-plan-input.jsonl" \
GRIP_PATCH_OUTPUT="$PWD/build/grip-expansion/new-plans.jsonl" \
./gradlew -Pworkout.appleOnly=true :shared:jvmTest --rerun \
  --tests '*GripPatch*' --max-workers=2 --no-parallel
$PY scripts/extract-grip-patches.py build/grip-expansion/new-plans.jsonl \
  build/grip-expansion/new-patches

$PY scripts/prepare-grip-expansion-training.py \
  build/grip-expansion/new-patches/patches.jsonl build/grip-expansion/new-manual-input.jsonl \
  --manual-input-regions Evaluation/fixtures/grip-manual-training-regions-v1.json
$PY scripts/extract-grip-patches.py build/grip-expansion/new-manual-input.jsonl \
  build/grip-expansion/new-manual-patches \
  --manual-training-regions Evaluation/fixtures/grip-manual-training-regions-v1.json
$PY scripts/prepare-grip-expansion-training.py \
  build/grip-expansion/new-patches/patches.jsonl build/grip-expansion/new-reviewed.jsonl \
  --manual-patches build/grip-expansion/new-manual-patches/patches.jsonl

TORCH_HOME="$PWD/build/grip-patches/torch-cache" $PY scripts/train-grip-patches.py \
  build/grip-expansion/new-reviewed.jsonl build/grip-expansion/new-model \
  --patch-review Evaluation/fixtures/grip-patch-expansion-exclusions-v1.json
$PY scripts/score-grip-patches.py \
  build/grip-patches/reviewed-model/GripPatchClassifier.pt \
  build/grip-expansion/new-reviewed.jsonl build/grip-expansion/new-baseline \
  --patch-review Evaluation/fixtures/grip-patch-expansion-exclusions-v1.json
$PY scripts/export-grip-patch-coreml.py build/grip-expansion/new-model --precision float32
```

Run the native helper and parity checker as documented in
[the pixel-prototype report](IMAGE_PATCH_GRIP_CLASSIFIER.md). If regenerating
native pixels or SDK results changes hashes, review new crops and bind a new
annotation version; do not bypass the hash-bound exclusions.
