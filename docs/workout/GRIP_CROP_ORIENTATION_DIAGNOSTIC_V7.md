# Correct-crop and orientation diagnostic — 2026-10-23

Correcting hand crops alone does not resolve classification. On nine previously
exposed adult recordings, correcting crops recovers two valid overhand inputs;
it changes no raw decisions on the 27 already correct automatic hand crops.
Reviewed orientation restores eight neutral hand decisions with missing torso
context. Two chin-up errors persist with both corrected crops and front-view
input. Most predictions still fail the unchanged acceptance thresholds.

This is a **privileged diagnostic**, not a fresh test, an accuracy improvement,
or a phone update. References are coding-agent pixel annotations, not independent
human gold. No weights, thresholds, rep counters or production inputs changed.

## Population and review

The source is the already exposed three-grip experiment's test inventory,
`build/grip-threeway/test-approved.jsonl`. Before viewing selected pixels or
running new predictions, selection chose the first three recording SHA-256s per
final reviewed grip with at least two active sampled frames, then the earliest
two active fixed-time frames. Selection used neither hand availability nor
classifier scores. This yields nine recordings, 18 frames and 36 expected hand
slots. Every slot remains in the main denominator; no recording was replaced.

All selected subjects are adults. Six slots have hands clipped by the original
image edge; another hand is hidden behind the athlete's head. They cannot receive
a valid manual crop. The source activity reference for the recording with four
clipped hands cannot be independently verified from these frames; it remains
provisional and earns no correctness credit. All original video hashes and known
publisher groups are disjoint from the staged grip model's fitting inventory,
but these videos were already exposed during earlier experiments. This is not
an athlete-identity holdout across unrelated uploads.

Source and crop review ran before the first new scores. It identified two
forearm crops and one duplicate-hand crop. Direct hand bounds enclose the
available fingers/knuckles, palm or dorsum, and wrist junction. A fixed square
with 1.6 times the longest bound dimension supplies the primary 128×128 RGB
crop; 2.0 padding is a prespecified sensitivity comparison. Crops preserve source
orientation and use real pixels with bilinear resizing. They are never mirrored,
rotated, sharpened or synthesized. Tiny/blurred hand surfaces remain unclear;
two neighboring hands of the same athlete in doorway footage remain flagged.

The initial box review caught two draft errors: a small second hand's box needed
to move down, and an alleged hand box contained a handle/head because the actual
hand was occluded. Draft pixels/references are retained; corrections preceded
all predictions. The final references and 58 exact reconstructed manual crops
are hash-bound. Rear versus oblique is uncertain for two frames; a stricter
subset excludes those frames and the two source-clipped frames with unverifiable
orientation. No earlier experiment's reference was changed.

## Frozen comparison

All variants use the **last trained staged three-class grip model** from
`build/grip-threeway/staged-models/threeway/GripHandClassifier.pt`. The automatic
crop comparator is its retained earlier SDK/fallback/expanded crop, rather than
the v6 hand-first prototype that failed coverage. This diagnoses the existing
trained head; it is not a replay of the app or a replacement end-to-end pipeline.

Automatic orientation uses the frozen torso model with the torso-only cue path
from v5: no missing-face rear vote and no face/head-turn reversal. Missing torso
context remains unavailable. Reviewed orientation is a privileged one-hot input.
The full crop × view comparison includes automatic crops with reviewed view so
crop and orientation effects can be separated. Grip and closed-contact scores
must both reach **0.8**. The v4 appearance/quality model is an additional component
probe only; its outputs are not substituted into this grip head.

| Inputs | Correct raw hand choices / 36 slots | Wrong or invalid raw choices | Missing raw choices | Accepted correct / wrong |
| --- | ---: | ---: | ---: | ---: |
| Automatic crop + automatic view | 17 | 7 | 12 | 0 / 0 |
| Corrected crop + automatic view | 19 | 2 | 15 | 0 / 0 |
| Automatic crop + reviewed view | 25 | 7 | 4 | 4 / 0 |
| Corrected crop + reviewed view | 27 | 2 | 7 | 2 / 0 |

These raw choices are argmax labels, **not accepted classifications**. A matching
source grip cannot make a forearm, duplicate, clipped or missing hand correct.
Corrected crops remove invalid inputs rather than turning them into confident
outputs. A complete hand can still have unclear anatomical surface pixels.

The corrected-crop/reviewed-view result comprises 11/11 available overhand hands,
4/6 underhand hands and 12/12 neutral hands. Seven unavailable source hands remain
in the 36-slot denominator. All four correctly named underhand crops come from
one recording. Both remaining errors are frames of another recording, not two
independent failure videos. The two accepted corrected predictions also come
from one neutral recording and have unclear surface references; they do not
establish reliable anatomical recognition.

On the stricter 26 complete hands with confidently reviewed coarse orientation,
the four rows above give 14, 16, 22 and 24 raw correct choices respectively. On
the 27 hands where the automatic crop was already correct, manual cropping
changes neither the 17 raw correct automatic-view choices nor the 25 raw correct
reviewed-view choices. With 2.0 padding, every corrected-crop raw label remains
the same, while accepted corrected/reviewed predictions fall from two to zero.
Thus the raw crop conclusion survives the prespecified padding check, while
acceptance remains sensitive to input scale.

At video level, averaging hands once per frame then frames once per recording
gives six correct raw labels, one wrong and two unavailable with either crop and
automatic view. Reviewed view gives seven correct, one wrong and one unavailable.
These are diagnostic raw aggregates, not confirmed set classifications.

## What is actually failing

**Localization:** the two forearm inputs are repairable, but most accepted hand
locations were already adequate. Seven source hands cannot be recovered by any
cropper. The manually repaired examples establish an upper-bound input experiment,
not a new automatic hand detector.

**Orientation availability and accuracy:** among 14 frames with confident coarse
view references, torso context is unavailable in four. Of ten available torso
outputs, six have the correct view argmax and four are wrong. All eight extra
raw correct hand decisions with reviewed view come from neutral frames with
missing context. Better localization did not restore their torso input.

**Appearance/grip head:** the two remaining chin-up failures use real, readable
dorsal hand patches and a supplied front view. The staged model still chooses
overhand. On 16 directly readable manual 1.6 crops, its surface argmax is correct
in 5 cases, versus 15 for the separately frozen v4 appearance model. This small,
agent-labelled exposed subset cannot qualify the latter model or override its
earlier failed validation gate. It does show that the old grip model's appearance
representation is substantially weaker on these particular inputs. No guarded
fusion trainer was run.

**View/class confounding:** all selected neutral recordings are oblique views.
A post-primary-score sensitivity probe keeps each of the 29 actual corrected
hand images fixed and supplies front, rear and oblique inputs in turn. Supplying
oblique makes the staged head choose **neutral for all 29**, including known
overhand and underhand hands. This supports concern about excessive view
dependence; 12/12 reviewed-view neutral choices cannot be interpreted as evidence
of robust neutral hand recognition. Counterfactual views can be physically
inconsistent with a real image, so this is a dependency probe, not another
accuracy test. Constant mean-color controls produce different predictions,
confirming that the network also depends on texture; it is not literally a
view-only classifier. No probe result changes the primary scores or model.

## Next experiment

Prioritize the appearance/grip training problem and orientation coverage, with
a fitting/validation table that crosses **overhand, underhand and neutral with
front, rear and side views**. Audit independent recording groups in each cell
before training, directly verify actual hand surfaces, and add wider wrist/handle
RGB context where a hand-only image cannot resolve handle direction. Do not use
an oblique camera label as evidence for neutral grip.

Repair and qualify the stronger appearance model on fitting/validation data
before training a replacement grip head. The existing surface-validation guard
remains binding. Include matched image-only, view-only and combined controls;
the combined model must improve on both, and preserve the existing 0.8 acceptance
requirements and invalid-input checks. Keep body orientation independent of
head turns. Reserve new recording groups for the eventual final test; this
diagnostic and all earlier tests are now development/regression evidence.

## Evidence and checks

- References: `Evaluation/fixtures/grip-oracle-references-v7.json`.
- Protocol: `Evaluation/fixtures/grip-oracle-protocol-v7.json`.
- Results: `Evaluation/fixtures/grip-oracle-results-v7.json`.
- First scores/predictions: `build/grip-oracle-v7/final/report.json` and
  `predictions.jsonl`; post-score probes are separate files.
- Audit: `build/grip-oracle-v7/final/audit.json`. It verifies all 147 pre-score
  pins, all prior v2–v6 frozen pins, nine original videos, exact reconstruction
  of all 58 manual RGB crops and paired score accounting.
- All 466 Python tests pass, including four new tests for clipping, missing
  orientation, contact gating and source-slot accounting. `git diff --check`
  passes. Expected failure-path fixture logs are not native build results.
- Existing local source frames and models were reused; no new download,
  native export, training cache or compiler cache was created. Only this run's
  disposable Python bytecode is removed; reviewed pixels and first outputs stay.

![Source, automatic crop, corrected crop and torso examples](../../build/grip-oracle-v7/final/examples.png)
