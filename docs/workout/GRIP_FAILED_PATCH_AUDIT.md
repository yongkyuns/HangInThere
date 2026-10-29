# Failed hand-patch audit — 2026-10-22

Follow-up: [separately supervised components](GRIP_COMPONENT_EXPERIMENT.md)
fix the ten supported chin-up patches, but regress on overhand development
controls and are not deployed. The diagnosis below describes the preserved
previous model.

The immediate failure in the selected verified-face/factorized model is wrong
athlete orientation. Every one of the 12 previously reported test patches is
from a front-facing athlete, but the model assigns rear-view probability
**73.0%–95.4%**. This reverses the final grip mapping.

A control audit also shows a nearly collapsed hand-appearance head: its intended
back-of-hand channel wins **1,037/1,062** available train/validation/test patches.
It wins on **92/93 known front-facing overhand test controls**, where the physical
appearance mapping expects palm evidence. These internal channels were never
directly supervised as independent palm/dorsum classes. Their intended semantic
meaning did not become a reliable visual classifier.

The face cue MLP supplies weak, near-uniform scores (front approximately
36%–39% on these cases), while the torso-image logits strongly favor rear. Actual
faces are detected on 11 of the 12 frames/hand observations. The fusion therefore
overrides visible-face evidence. The selected checkpoint is epoch 1 of 20,
chosen by grip validation NLL; that criterion did not establish orientation or
appearance correctness.

**This is a concrete model/training-design failure.** Low-resolution crops and
limited data also matter, but it was premature to interpret the aggregate
results as a general inability to recognize chin-ups from hand images.

## Crop and label checks

Every PNG hash matches its recorded patch identity, every source-frame hash
matches its recording manifest, and reconstructing the saved crop from its
original frame/box with the original bilinear resize gives **pixel-identical
128×128 RGB**. No frame substitution, mirror, wrong-person crop or image/label
join mismatch was found in these 12 observations. Visual review confirms the
boxes belong to the active athlete, not a background person.

Original crop squares range from **26 to 47 source pixels**; resizing them to
128 pixels does not recover finger detail. Many are very tight and blurry.

| Source | Observations | Crop/label finding |
| --- | ---: | --- |
| Outdoor athlete, `VUoItcXFPZ4` | 10 patches in eight frames | Intended gripping hands; tight low-detail crops. Whole-frame hands and torso support front-facing underhand/chin-up. |
| Indoor athlete, `uSzYBHbjLFI` | 2 patches | Foreground crossbar hides fingers/contact; patches contain partial hand/wrist. Front view is supported, but the binary grip reference is not sufficiently established. Exclude from a clean labelled test until independently resolved. |

Consequently the earlier **“0/12 chin-up failures” is not a clean benchmark**:
there are ten visually supported chin-up patches and two unverified references.
They come from only two videos, not 12 independent examples. The ten supported
patches still fail in the final model (eight accepted wrong predictions and two
low-score unknowns; every raw choice is overhand). No new overhand/neutral label
is forced onto the two obscured indoor observations.

The original predictions, weights and frozen benchmark manifests are preserved.
The audit is a separate, hash-bound review overlay in
`Evaluation/fixtures/grip-patch-failure-audit-v1.json`. Historical fresh-cohort
accuracy still includes those provisional references and must not be presented
as a clean chin-up accuracy estimate.

## Controlled diagnostic

Keeping each hand patch unchanged and replacing only predicted orientation with
manual front-view orientation makes **12/12 outputs underhand**, with minimum
conditional underhand score 0.99945. This isolates the arithmetic flip, but is
**not an improved accuracy result or proof that the hand classifier works**:
the near-constant hand channel also makes the same intervention wrong for many
known front-view overhand controls. No model was retrained or deployed here.

For example, outdoor patch #4 has rear score 0.887 despite a verified visible
face. The intended dorsum appearance channel is approximately 1.0. The fusion
therefore emits overhand score 0.898. Its weighted torso logits favor rear
strongly enough to overpower the weak face branch.

The complete frozen TorchScript graph was decomposed into hand/face/body/fusion
components on all **1,062** available patches. Reconstructed grip and orientation
scores match the frozen graph exactly (maximum absolute difference **0.0**).
This inspection uses the actual saved graph, without fitting weights.

## Every previously reported failure

| # | Source / frame | Side | Native square | Predicted rear | Reference status |
| ---: | --- | --- | ---: | ---: | --- |
| 1 | older / frame-0015.png | left | 29 px | 94.7% | underhand supported |
| 2 | older / frame-0016.png | left | 32 px | 81.6% | underhand supported |
| 3 | older / frame-0017.png | left | 34 px | 88.7% | underhand supported |
| 4 | older / frame-0017.png | right | 47 px | 88.7% | underhand supported |
| 5 | older / frame-0018.png | left | 33 px | 88.2% | underhand supported |
| 6 | older / frame-0019.png | left | 27 px | 88.5% | underhand supported |
| 7 | older / frame-0020.png | left | 26 px | 95.4% | underhand supported |
| 8 | older / frame-0020.png | right | 43 px | 95.4% | underhand supported |
| 9 | older / frame-0021.png | left | 42 px | 77.2% | underhand supported |
| 10 | older / frame-0022.png | left | 40 px | 73.0% | underhand supported |
| 11 | fresh / frame-0016.png | left | 35 px | 86.6% | unverified / occluded |
| 12 | fresh / frame-0020.png | left | 31 px | 86.8% | unverified / occluded |

## Artifacts and reproduction

Local visual artifacts under `build/grip-failure-audit/`:

- `all-12-patches.png`: exact saved classifier inputs, enlarged without added detail.
- `source-context-1.png` through `source-context-3.png`: every source frame, actual crop box, wider hand/bar view and exact patch.
- `label-evidence.png`: both hands/upper body for checking the two source labels.
- `model-components.json`: all component scores and controls, bound to the frozen model hash.
- `review.json`, `cases.json`: per-observation crop identity and visual review.

```sh
PY=build/grip-training/venv/bin/python
$PY scripts/audit-failed-grip-patches.py build/grip-failure-audit \
  --review Evaluation/fixtures/grip-patch-failure-audit-v1.json
$PY scripts/audit-grip-model-components.py build/grip-failure-audit
```

The next training design should validate orientation separately and directly
supervise/review the hand-appearance target, with front-view overhand controls.
The fusion should be tested for contradictory cues and nearly constant hand
outputs before any aggregate accuracy is treated as grip recognition. The
obscured references also need resolution before another clean test.
