# Separately supervised grip components — 2026-10-22

The audited front-facing chin-up clip now produces **10/10 correct accepted
hand predictions**, compared with 0/10 before. The broader overhand comparison
regresses substantially, so this is a research improvement in the failed
component design, **not a model suitable for the phone**. The app is unchanged.

The [overhand regression audit](GRIP_OVERHAND_REGRESSION_AUDIT.md) shows every
failed patch, separates orientation from hand-output/mapping errors, and flags
questionable derived appearance targets and insufficient crops.

## Why the face signal failed

The earlier model detected faces on 11/12 audited hand observations (nine of ten
unique frames), including every frame of the supported outdoor chin-up clip.
Nevertheless it called every athlete rear-facing. Its face branch supplied only
about 36–39% front-view score, while unbounded torso logits dominated the fusion.
Joint grip training also allowed an almost constant hand-appearance channel.
Those were model/training failures, not eleven failed face detections.

For the one genuine missing face, a separate native audit repeats Apple Vision
face-rectangle revision 3 on the original, hash-verified frame and saves **all
raw detections before athlete association**. It returns zero detections. The
ownership filter did not discard a detected face in this case. The indoor frame
is blurry and viewed from below; the audit establishes a detector miss but does
not establish which visual factor caused it.

More generally, a visible face is positive orientation evidence. A missing face
is not a rear-view label: front-facing athletes can turn their head, look up or
be obscured. Face direction also differs from torso direction. The detector's
confidence is not itself a calibrated probability of front-facing torso view.

## Changed training and inference

`train-grip-components.py` independently trains three components:

- Hand RGB → palm/back-of-hand appearance, with an explicit appearance loss.
- Selected-athlete RGB → front/rear/oblique view, with an orientation loss.
- Six verified face/context measurements → view, with a separate orientation loss.

Each image component has its own MobileNetV3-small feature extractor. No grip
loss trains either orientation component. Appearance targets are derived from
the existing reviewed grip/view references: front-overhand and rear-underhand
map to palm; front-underhand and rear-overhand map to dorsum. These are **derived
targets, not independent human palm annotations**. Oblique/unknown views are
excluded from appearance supervision.

The fusion mixes normalized orientation probabilities rather than unbounded
logits. Its face weight is fixed before evaluation at `0.75 × detectorConfidence`
when verified selected-athlete face evidence exists. Missing faces add no view
vote. Missing selected-athlete context produces equal grip scores. Oblique view
contributes equal front/rear weight to the physical grip mapping.

The first independently supervised torso run still generalized poorly. A second
run replaces only its torso-image component with a closer head crop taken from
the original frame, using the same athlete's shoulders. This crop preserves
more head detail than the broad torso image resized to 128 pixels, and includes
the back of the head without requiring a face detection. It keeps the first
run's appearance and face weights frozen. Head versus torso selection uses
orientation validation NLL only: **0.0405 versus 0.4814**. Test images are not
used in fitting or checkpoint selection.

Training uses 624 appearance patches from 21 videos and 581 orientation frames
from 30 videos. Components select checkpoints independently using equal-class,
equal-video validation NLL. Four separate validation videos provide 142 hand
patches and 78 orientation frames. CNN fine-tuning was attempted for both image
tasks. Validation selected the appearance head before CNN fine-tuning; it
selected head-view epoch 18 with all 138 feature parameter tensors changed.
Thus this run does **not** claim improved fine-tuned hand features.

The selected appearance component gets 53/69 derived palm and 73/73 derived
dorsum validation patches right. The combined head/face orientation gets 72/72
validation frames with an available hand right (the independently trained head
component is evaluated on all 78 validation frames). Neither result establishes
general reliability: validation has no oblique-view video and only one front-view
video. Classifier confidence remains uncalibrated.

## Development comparison

Both evaluation cohorts were inspected during previous development; these are
**video-disjoint development comparisons, not fresh blind tests**. Sources and
source groups remain separated from training/checkpoint selection. Public
athlete identities are not verified to be disjoint.

The previously disputed indoor source remains visible in prediction artifacts
but is excluded from binary accuracy, including its 52 expected-hand denominator
and two available patches. The supported chin-up population is ten patches in
eight frames from **one video**. The additional cohort supplies zero evaluable
verified underhand patches, so no new-video chin-up reliability is established.

| Available patches, raw choice | Previous factorized model | Separate appearance + head/face |
| --- | ---: | ---: |
| Validation overhand | 111/111 | 110/111 |
| Validation underhand | 0/31 | 16/31 |
| Older development overhand | 154/155 | 100/155 |
| Older development underhand | 0/10 | 10/10 |
| Additional development overhand | 60/60 | 51/60 |
| Both development cohorts, total | 214/225 | 161/225 |

Combined class-balanced raw accuracy rises from 49.8% to 85.1% because the
previous model fails the rare underhand class. Aggregate accuracy falls from
95.1% to 71.6%; neither number should hide the other. At the unchanged 0.8
threshold, accepted correct/wrong predictions change from **159/8 to 116/37**.
This regression prevents deployment. Across all 472 expected hands with eligible
references, the new model also returns 319 unknowns, including 247 missing crops.

Orientation improvements do not eliminate the remaining errors: older-cohort
front frames improve from 0/50 to 35/50, while rear frames regress from 39/39 to
30/39. Additional-cohort front frames improve from 0/10 to 10/10 and rear frames
remain 24/24. Both models miss all 12 oblique frames. Orientation counts use
unique source frames with an available hand, rather than counting two hands as
two independent view decisions. Orientation can be scored on the disputed
indoor source because its front view is supported independently of its grip.

The next data need is front views with missing/profile faces, oblique views in
validation, and independently reviewed palm/dorsum patches across more sources,
especially rear-view underhand examples. More repeated frames from the one
successful chin-up clip would not establish this coverage.

## Reproduction and artifacts

The original manifests and previous weights are preserved. New artifacts are
ignored under `build/grip-supervised/`:

- `model/`: first independent torso experiment and per-component histories.
- `head-model/`: selected research model, receipts, weights and frozen source snapshots.
- `heads/`: source-bound head images/manifests, with original torso context retained.
- `evaluation/`, `head-evaluation/`: complete predictions, source/class metrics and twelve audited cases.
- `face-miss-input.json`, `face-miss-audit.json`: original miss and all raw face detections.
- `head-crop-review.png`: original torso versus closer head crops.

The selected TorchScript SHA-256 is
`0a32afafa8bcd4f834b7f4723d919c62cb4c704bdb09f076a090e0b4f5a7645f`.
Decomposing its saved graph reproduces grip/orientation outputs exactly (maximum
absolute difference 0.0). Frozen trainer sources match their run-receipt hashes.

The float32 Core ML research export is 8,178,286 bytes. Native CPU inference was
checked on all 1,062 available train/validation/development patches. Maximum grip
and orientation score differences are 0.0000164 and 0.0000140; all threshold
decisions and orientation argmaxes match. **The strict parity check remains
failed** because one already-unknown oblique training example changes raw grip
argmax from a 0.5/0.5 tie to 0.49999997/0.5. The gate was not weakened to hide
this discrepancy. Its receipt is retained in `head-model/native-parity.json`.
This numerical issue does not change an accepted prediction, and the separate
accuracy regression already prevents deployment. Host timing is not iPhone
end-to-end timing.

```sh
PY=build/grip-training/venv/bin/python
$PY scripts/train-grip-components.py \
  build/grip-context/verified-train-validation.jsonl NEW_COMPONENT_DIRECTORY \
  --patch-review build/grip-context/training-exclusions.json
$PY scripts/prepare-grip-head-context.py \
  build/grip-context/verified-train-validation.jsonl NEW_HEAD_MANIFEST NEW_HEAD_IMAGES
$PY scripts/train-grip-head-view.py NEW_HEAD_MANIFEST NEW_COMPONENT_DIRECTORY \
  NEW_HEAD_MODEL --patch-review build/grip-context/training-exclusions.json
# After freezing weights, prepare the two development manifests with the same
# head geometry, retain the original exclusion reviews, then score:
$PY scripts/evaluate-grip-components.py NEW_HEAD_MODEL NEW_EVALUATION \
  --data HEAD_MANIFEST_DIRECTORY
```

Regression checks cover independent task gradients, rejection of test inputs in
fitting, selected-athlete head ownership, unknown-view target exclusion, absence
of a rear vote for missing faces, missing-context abstention, and grip changes
when hand appearance changes. Existing provenance/crop/clock tests also pass.
All 18 grip Python checks pass; the native face-audit Swift utility compiles and
runs on the original missing-face frame. The strict export parity limitation
above remains explicit.
