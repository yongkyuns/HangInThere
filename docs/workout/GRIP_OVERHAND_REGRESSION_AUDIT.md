# Overhand regression audit — 2026-10-22

Follow-up: [reviewed crops, torso cues and separate-video retraining](GRIP_REVIEWED_CROP_EXPERIMENT.md).
The revised model fixes the selected head-turn diagnostic but still fails fresh
chin-up patches and remains experimental.

Both components contribute. Under the existing source references, **32 failures
come from orientation direction and 25 from hand output plus the appearance
mapping**. Another seven combine oblique view with an insufficient grip crop.
The 25 hand-attributed cases are not 25 independently verified palm/dorsum
classifier errors: 22 come from one angled-handle source with questionable
derived appearance targets.

This is a read-only audit of the frozen head-crop research model. No weights,
benchmark labels, source roles, thresholds or phone code changed.

## Complete population

Of 215 available reference-overhand patches in the two previously inspected
development cohorts, 64 have a wrong raw choice. These represent 45 frames from
six videos. **37 are confidently wrong** at the unchanged 0.8 threshold;
**27 return unknown**, not chin-up. There are 63 new raw regressions; one
weighted-source patch was already wrong at low confidence in the previous
model. All 37 confident overhand errors are new.

| Attribution under existing references | Wrong raw choices | Confident wrong | Unknown |
| --- | ---: | ---: | ---: |
| Wrong orientation direction | 32 | 23 | 9 |
| Hand output / fixed appearance mapping | 25 | 14 | 11 |
| Oblique view with wrist-dominant crop | 7 | 0 | 7 |
| Total | 64 | 37 | 27 |

The 22 angled-handle cases account for 11 confident errors and 11 unknowns in
the hand/mapping row. The remaining three hand-attributed cases are confident
errors in two other sources. These counts preserve the original provisional
references; they do not silently turn ambiguous patches into verified labels.

## What the images show

### Head turn mistaken for torso turn: 29 cases

In `k700train_irFDiPohB8E_000017_000027`, the chest/logo faces the camera while
the head turns sideways. The selected head crop excludes the chest evidence.
All 29 failed patches lack a verified face detection. The head-image fallback
assigns rear view despite front-facing torso context.

Example #44, frame-0016 left hand: palm score is approximately 1.000, consistent
with the existing front-overhand reference, but rear-view score is 0.997. Fusion
therefore emits underhand at 0.997. Keeping the hand fixed and substituting
reviewed front-torso orientation recovers an overhand raw choice.

The other three orientation-direction failures come from rear-view source
`Rxt-xkISpNY`. Its camera/head angle is oblique; one observation has a visible
profile face despite a rear-facing torso. The top predicted class is oblique,
but residual front probability exceeds rear probability, flipping the raw grip
direction. All three remain unknown, with underhand scores roughly 0.61–0.70.

The tighter-crop experiment made a design mistake: **head direction is not
torso direction**. Better validation on one front-view video did not justify
discarding chest/back evidence. There is no explicit rear vote for missing
faces in the new fusion, but the head-image fallback still learns that proxy.

### Clear hand appearance mistakes

In `k700train_PFISl6mVwtk_000045_000055`, the torso is visibly rear-facing and
orientation correctly assigns rear view. Both patches include the gripping
hands; the back of the hand/knuckles are visible.

Example #57: the hand classifier assigns palm 0.922 and dorsum 0.078, with rear
view approximately 1.000. This produces underhand 0.922. Changing orientation
alone cannot fix it. These two failures are visually supported appearance errors.

The single failure in front-view `AfNDzAalcSg` has correct front direction and
an inconsistent hand output: palm 0.091, dorsum 0.909. Its palm expectation
remains source-derived, rather than independent adjudication of a complete
visible palm surface.

### Angled-handle targets need review: 22 cases

In `k700train__R7UzIvJP58_000005_000015`, the athlete is clearly rear-facing.
Orientation is correct. The crop includes the intended hand gripping an angled
handle near its end cap, with mixed side/dorsal surfaces visible.

The experiment derives dorsum from “rear + overhand.” It did not independently
label the actual visible surface of every patch. At this bar/hand angle, that
rule is too coarse to establish all 22 targets. The binary source exercise label
also remains provisional on these angled handles; no forced replacement with
underhand or neutral is made here.

Example #6: palm is 0.985 and rear view approximately 1.000. This localizes the
disagreement to **hand output plus the fixed mapping**. It does not prove 22
unambiguous visual palm/dorsum mistakes. I treated the derived targets as stronger
evidence than they were. The same target-construction rule is used in training,
although this particular source stays held out.

### Grip contact partly outside the crop: seven cases

In oblique wide-grip source `k700train_fzPBhZBluR8_000007_000017`, all seven
failed crops are only **25–27 source pixels square**. They mostly show wrist
and forearm; gripping fingers/bar contact lie partly beyond the top edge.
These are the correct athlete's limbs, but insufficient standalone grip images.

All seven return unknown. Replacing the predicted view with the reviewed
oblique view also makes the physical fusion neutral (0.5/0.5), regardless of
hand appearance. That coarse view label supplies no defined binary appearance
target, so these are not seven verified hand-classifier mistakes.

## Why the previous score hid the problem

Under the intended component semantics, 92 previously correct front-view
overhand controls combined the opposite appearance channel with incorrect rear
orientation. Those intermediate reversals yielded overhand. The old latent
appearance channels were not directly supervised; this is evidence of an
inconsistent representation, not independent human adjudication of 92 surfaces.

Separately supervising appearance and changing orientation broke some apparently
successful outputs. The earlier 214/215 overhand score did not establish reliable
hand/orientation recognition; that model also failed every supported chin-up patch.

## Source breakdown

| Source ID | Available patches | Raw failures | Confident errors | Finding |
| --- | ---: | ---: | ---: | --- |
| `AfNDzAalcSg` | 37 | 1 | 1 | Hand output disagrees with derived front/palm expectation |
| `Rxt-xkISpNY` | 21 | 3 | 0 | Oblique head/camera angle; wrong front/rear bias, unknown outputs |
| `_R7UzIvJP58` | 50 | 22 | 11 | Rear view correct; angled-handle appearance targets unverified |
| `irFDiPohB8E` | 45 | 29 | 23 | Front torso, sideways head, wrong rear orientation |
| `PFISl6mVwtk` | 2 | 2 | 2 | Back of hand called palm; rear view correct |
| `fzPBhZBluR8` | 24 | 7 | 0 | Oblique view and clipped/wrist-dominant crop |

The other three overhand sources supply 36 patches without a raw failure.
These are correlated hands/frames, not independent people or videos.

## Verification and artifacts

All 64 hand PNGs and selected-head PNGs match their recorded hashes and are
**pixel-identical** to crops reconstructed from hash-verified original frames.
This confirms identity, not useful grip coverage; the wrist crops demonstrate
that distinction. Every saved hand/head input was visually inspected. Full-frame
and wider-hand examples were reviewed for all six sources, with additional
original-resolution angled-handle frames. This is agent review, not gold labeling.

Frozen-model replay reproduces every raw choice and threshold decision. Maximum
score difference from the original batch evaluation is 0.00000310. Attribution
uses the fusion's front-minus-rear direction, which can differ from the top
three-way view class. Replacing a component is diagnostic, not improved accuracy.

`Evaluation/fixtures/grip-overhand-regression-audit-v1.json` binds every review
to source/frame/patch hashes, side and timestamp. It flags the 22 unverified
derived appearance targets and seven insufficient crops without altering the
original references or model. The phone remains unchanged.

Artifacts in `build/grip-overhand-audit/`:

- `failure-examples.png`: four mechanisms with source context and exact model inputs.
- `all-failures.md`, `all-failures-1.png` through `all-failures-4.png`: all 64 inputs, component scores and final decisions.
- `representative-context-1.png`, `representative-context-2.png`: every failed source, preferring new regressions.
- `crop-coverage-review.png`: wider context versus saved angled-handle and clipped crops.
- `cases.json`, `receipt.json`: all cases, diagnostic replacements, hashes, review flags and replay checks.

```sh
MPLCONFIGDIR="$PWD/build/grip-patches/matplotlib" \
build/grip-training/venv/bin/python scripts/audit-overhand-regressions.py \
  build/grip-overhand-audit \
  --review Evaluation/fixtures/grip-overhand-regression-audit-v1.json
```

The fixes need torso evidence alongside head cues, actual patch/grip review on
angled handles, and crop checks that require visible gripping contact.

All 24 grip Python checks pass, including six new checks for component
attribution, oblique targets, cancelling errors and pixel-level crop provenance.
