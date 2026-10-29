# Hand + athlete orientation fine-tuning — 2026-10-22
> **Follow-up audit:** two indoor test patches have an unverified, bar-occluded
> grip reference. The other ten supported chin-up patches fail because the
> selected model predicts a rear view for a front-facing athlete. Its hand
> appearance head is also nearly constant on controls. See
> [the complete crop/label/component audit](GRIP_FAILED_PATCH_AUDIT.md).
> The historical 0/12 claim is not a clean labelled benchmark.

Offline research only. No learned grip model was installed or enabled in either
app. Weights include private owner training pixels and remain local under ignored
`build/grip-context/`.

**Reference correction:** the previous claim that all 55 usable older test
chin-up patches failed was overstated. Forty-five of those patches belong to a
front-facing **overhand** pull-up video that was incorrectly labelled rear-view
underhand. That source is corrected by a versioned overlay; its nose/eye body
observations were not evidence of hallucinated face landmarks. The remaining
older chin-up test source has ten usable patches. Historical raw results are
preserved, but must not be interpreted using their erroneous reference labels.

## Data and split boundaries

The existing accepted training pool remains **693 hand patches** (569 overhand,
124 underhand) from **26 contributing videos**. Four other videos supply
validation. All frames/hands from one source stay in the same role. The existing
six held-out source videos remain excluded from fitting and checkpoint selection.

Sixty additional public candidates are entries 100–159 in the same deterministic
SHA256 ordering of the Kinetics archive used in
[GRIP_DATA_EXPANSION.md](GRIP_DATA_EXPANSION.md). Native Apple decoding verified
source hashes, clocks and dimensions. Six provisional binary test references
were frozen before the first scoring pass. Low automatic chin-up patch coverage
prompted one additional oblique chin-up reference before scores were viewed.
An enlarged visual review withdrew one ambiguous reference before scoring;
original annotations and a correction overlay are both retained.

The final fresh binary cohort has **six source videos: four overhand, two
underhand**. A seventh source remains in an unresolved-reference manifest outside
binary accuracy counts. The six sources have **250 expected hand observations**,
but only **62 eligible automatic patches**: 60 overhand and two underhand.
There are 188 upstream missing hands. The two fresh chin-up sources have 104
expected hand observations, of which 102 lack eligible automatic patches; one
source contributes none. This cohort cannot establish chin-up generalization.
No manual test regions or manual final test crops were introduced.

Labels remain provisional agent visual annotations. Public athlete identities
are unknown; source/video separation is verified, athlete independence is not
claimed. The previous six-source holdout is already development-exposed. The
new cohort was unseen by the first two fixed model configurations. The dedicated
face/factorized follow-up was designed after initial results and diagnostic
inspection; that cohort is therefore also development-exposed for the follow-up.
Its videos still never enter fitting or checkpoint selection. No new blind-test
claim is made for the follow-up.

## Image inputs and models

Hand inputs are the existing upright, unmirrored 128×128 RGB hand/short-forearm
patches. The same athlete's head/upper-torso crop is a square of 3.2 shoulder
spans, centered horizontally on the shoulders, beginning 0.43 crop widths above
their midpoint. Original RGB is padded at edges and resized without stretching.
Context is bound to recording, frame hash, timestamp and selected person index.
Another person's face or body is not used to fill a primary-athlete dropout.

Three variants use actual feature fine-tuning:

1. **Hand only:** MobileNetV3-small image features and a 576→128→2 hand head.
2. **Paired context:** shared MobileNet image features for hand and torso, five
   inferred body-pose face confidences plus a context mask, an auxiliary
   front/rear/oblique head, and a 1161→128→2 grip fusion head. Reviewed orientation
   labels supervise training; they are never inference inputs.
3. **Verified-face/factorized:** a dedicated Apple Vision face-rectangle request
   scans original source pixels. Only a unique face in the selected athlete's
   head region is eligible. Cues are actual face confidence, relative face
   width/height, absolute yaw/pi, absolute roll/pi and context mask. A face MLP
   plus a quarter-weight torso-image head predicts orientation. A hand head
   learns camera-facing palm/dorsum-like appearance. Their probabilities combine
   into grip: front-view palm evidence supports overhand, rear-view palm evidence
   supports underhand. Oblique orientation contributes equal front/rear weight,
   reducing confidence. Hand appearance is learned from the original grip loss;
   no new palm/dorsum labels are invented.

No detected face means missing face evidence, not a forced back-view label.
The dedicated detector misses some visible profiles, including frames of the
corrected weighted pull-up source. This is why upper-body image evidence remains
part of orientation. Face direction can also differ from torso direction.

The initial model is 1,077,751 parameters; all 138 CNN parameter tensors change
and all 693 accepted training patches fit. This is feature fine-tuning, rather
than the frozen-backbone head-only experiment described in the earlier report.

All variants use seed 31, 20 epochs, batch size 16, AdamW (CNN learning rate
0.0001, heads 0.001, decay 0.01), gradient clipping 5, and modest affine/color
augmentation without horizontal flips. Sampling balances grip classes and source
videos within each class, reducing correlated owner-frame dominance. All CNN
parameters train; pretrained BatchNorm running statistics stay fixed, with
trainable affine parameters. Auxiliary orientation loss weight is 0.3 for paired
context and 1.0 for the factorized follow-up. These changes form a joint design
comparison, not an isolated estimate of the detector's effect.

Checkpoint selection minimizes class-balanced mean per-video validation NLL.
The training command rejects any manifest containing test rows. Softmax scores
are uncalibrated. The acceptance threshold stays 0.80 without test tuning.
Patch counts within a recording are correlated, not independent trials.

## Corrected reference audit

`public-grip-expansion-corrections-v2.json` changes only the held-out reference
for `k700train_irFDiPohB8E_000017_000027` from rear/underhand to front/overhand.
The chest logo, visible beard/profile and hand views establish the correction.
The original annotation is retained. All 43 earlier source summaries were
re-inspected with full-frame, torso and hand crops; the audit sheets are in
`build/grip-context/label-audit/`. The zero-patch rope-pull source remains unsuitable
for future binary training without further review; it contributes no learned
training pixels here.

No training/validation annotation or accepted training pixel changed. No model
prediction, input crop, source clock, role or threshold changes because of this
correction. The correction helper asserts that every non-annotation field stays
identical. All compared models are rescored against the same corrected reference.

The previous statement that this source demonstrated false face landmarks was
also wrong: the athlete faces the camera with their head turned. Historical
55-patch chin-up-failure claims in the earlier expansion report are superseded.

## Results

All model comparisons below use the **same corrected reference labels** and
identical hand-patch populations. Missing hands remain unknown in the full
denominator.

| Model | Older holdout: raw correct / usable | Fresh cohort: raw correct / usable |
| --- | ---: | ---: |
| Previous frozen-feature hand model | 134/165 (81.2%) | 60/62 (96.8%) |
| Hand-only feature fine-tuning | 130/165 (78.8%) | 55/62 (88.7%) |
| Paired context fine-tuning | 155/165 (93.9%) | 58/62 (93.5%) |
| Verified-face/factorized fine-tuning | 154/165 (93.3%) | 60/62 (96.8%) |

At the unchanged 0.80 score threshold:

| Model | Older: correct / wrong / unknown (274 expected) | Fresh: correct / wrong / unknown (250 expected) |
| --- | ---: | ---: |
| Previous frozen-feature hand model | 128 / 23 / 123 | 57 / 2 / 191 |
| Hand-only feature fine-tuning | 106 / 18 / 150 | 50 / 2 / 198 |
| Paired context fine-tuning | 155 / 10 / 109 | 55 / 3 / 192 |
| Verified-face/factorized fine-tuning | 115 / 8 / 151 | 44 / 2 / 204 |

**None of the four models correctly classifies any of the 12 usable chin-up
patches** (ten older and two fresh, across only two contributing videos). The
overall improvement on the older holdout is overhand recognition. The fresh
comparison does not demonstrate an improvement, and its chin-up coverage is
far too small for a robust estimate. There is no eligible rear-view chin-up
test patch population after correcting the erroneous older reference.

The selected follow-up checkpoint is epoch 1, with validation NLL **0.8761**,
compared with 1.7192 for paired context and 4.2328 for hand-only fine-tuning.
It is selected on validation NLL, not test accuracy. All 138 CNN parameter
tensors change. The follow-up has **1,003,016 trainable parameters**. Later
epochs overfit; `validation-curves.png` records every epoch.

The verified-face/factorized model reduces confident wrong decisions in the
older holdout from 23 to 8, while reducing correct accepted decisions from 128
to 115 and increasing unknowns. This is a confidence/coverage tradeoff, not a
chin-up recognition fix. It does not justify promotion into the phone app.

`heldout-examples.png` shows an actual missed chin-up, the corrected front-facing
weighted pull-up and a fresh missed chin-up, with original frames, context, hand
patches, verified face status and before/after predictions.

Only three underhand training sources provide eligible primary-athlete context:
86 owner patches, one new front-view patch and 21 new rear-view patches. The
other 16 underhand training patches lack selected-athlete context. Thus the
context learning problem is much smaller than the raw 124-patch count suggests.

## Verification and local outputs

Twelve Python provenance/fusion checks and the unchanged shared athlete-selector
checks pass.

The final verified-face/factorized float32 Core ML export is **4,184,891 bytes**.
All **1,062** available patch grip/orientation decisions match TorchScript, with
zero 0.80 threshold mismatches. Maximum grip score difference is **5.25e-6**;
maximum orientation difference is **2.63e-6**. Host CPU model-only mean is about
0.69 ms. These checks verify export mechanics, not recognition accuracy.

The initial paired-context float32 Core ML export is 4,475,310 bytes. All **1,062**
available train/validation/held-out grip and orientation argmax decisions match
TorchScript, with no 0.80 acceptance mismatches. Maximum grip score difference is
1.25e-5 and orientation difference 2.93e-6. Host CPU model-only mean is about
0.69 ms; face/hand detection and an iPhone latency test are not included.

Media, native frames, per-patch predictions, review sheets and learned weights
remain ignored. Training receipts record source IDs/groups, patch hashes, input
hashes, source-code hashes and selected validation checkpoint. The assembly of
training/validation and old test rows reproduces byte-for-byte. Reference-only
corrections also reproduce for both context policies.

Tracked helpers:

- `scripts/prepare-grip-context.py`: original-pixel context and body-face evidence.
- `scripts/assemble-grip-context-experiment.py`: source-role joins, frozen fresh
  labels and pre-inference reference withdrawal.
- `Evaluation/GripContextFaces.swift`: source-hash-checked actual face detection.
- `scripts/train-grip-context.py`: three variants and separate scoring command.
- `scripts/apply-grip-reference-corrections.py`: reference-only held-out correction.
- `scripts/export-grip-context-coreml.py`, `Evaluation/GripContextCoreMLCheck.swift`
  and `scripts/check-grip-context-native.py`: three-input native export/parity.

## Reproduction

Use the isolated runtime `build/grip-training/venv/bin/python` with
`Evaluation/grip-learning-requirements.txt`. No training dependency enters an app.
The existing native `grip-frames` and shared `GripPatchDatasetTests` exporter are
unchanged. Data acquisition/preparation is as in the expansion report, with
`--offset 100 --count 60` on `scripts/acquire-grip-expansion.py`.

```sh
PY=build/grip-training/venv/bin/python
$PY scripts/prepare-grip-context.py build/grip-expansion/reviewed-patches.jsonl \
  build/grip-context/existing-context
$PY scripts/assemble-grip-context-experiment.py \
  build/grip-context/existing-context/context.jsonl \
  build/grip-context/fresh-context/context.jsonl \
  build/grip-context/supplement-context/context.jsonl build/grip-context/assembly
```

Apply the held-out correction to existing and verified-face development manifests
with `scripts/apply-grip-reference-corrections.py`. Create verified face manifests
with the compiled `GripContextFaces.swift` helper, retaining its receipts. Then:

```sh
TORCH_HOME="$PWD/build/grip-patches/torch-cache" $PY scripts/train-grip-context.py \
  train build/grip-context/verified-train-validation.jsonl \
  build/grip-context/oriented-model --variant oriented \
  --patch-review build/grip-context/training-exclusions.json
$PY scripts/train-grip-context.py score \
  build/grip-context/verified-development-test-corrected.jsonl \
  build/grip-context/oriented-development-score \
  --model build/grip-context/oriented-model/GripContextClassifier.pt \
  --patch-review build/grip-context/development-exclusions.json
$PY scripts/export-grip-context-coreml.py build/grip-context/oriented-model
```

For the first two variants, use the original body-face context manifest and
`--variant hand` or `--variant context`. Their original training source snapshot
is preserved in `build/grip-context/train-grip-context-first-run.py` and both
receipts have its exact SHA. The follow-up code and inputs have separate hashes.
Fine-tuning follows the distinction in the official
[PyTorch transfer-learning tutorial](https://docs.pytorch.org/tutorials/beginner/transfer_learning_tutorial.html):
image features update, rather than fitting only a new frozen-feature head.
