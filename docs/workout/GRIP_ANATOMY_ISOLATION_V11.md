# Complete anatomy review and gradient isolation — 2026-10-23

The anatomy-only experiment did not fix the remaining dorsal weakness. Joint
warm-start training barely meets the anatomical gate on revised development
references, but neither it nor subsequent frozen-encoder quality training is
usable grip qualification. The next data/quality work should target sharp,
readable training hands and explicit blur/exposure negatives. Keep the app's
front/rear gate; no learned candidate from this experiment is enabled.

## Review before fitting

All 744 automatic crops were reviewed under one written rubric: the 526 original
training crops across all camera views, plus the 218 expanded supported-view
validation crops. Source roles and the 14 new final reserve videos remain
unchanged. No final reserve video was decoded. Source RGB/crops are reused;
there is no new download or manual validation crop.

The first pass used 16 shuffled crop-only sheets, hiding original labels and
model predictions. A second pass used a different shuffle over all 744 crops.
Nine source/crop sheets verified all 72 proposed new or changed dominant-surface
assignments. Earlier model disagreements have been seen by this same agent:
this is complete development re-adjudication, **not independent human gold**.

The rubric distinguishes dominant palm, dorsal and edge appearance from a
mixed surface with no defensible dominant class, and from blur/clipping/
occlusion/invalid localization. It does not infer hand surface from a video
exercise title, torso direction or bar axis. Mixed crops and unclear crops mask
anatomical-class loss; the visibility head learns whether the anatomy supports
an unambiguous class. It is not a generic hand-presence probability.

The versioned review records 248 annotation updates, including new mixed flags,
not 248 proven historical mistakes. Original references remain nested verbatim
in each row, old fitting/evaluation files stay unchanged, and every crop remains
in the input denominator. Contact, grip and coarse source-view labels are
inherited here; the previously flagged source-view uncertainty is not magically
resolved by anatomical review.

| Reference category | Training crops | Validation crops |
| --- | ---: | ---: |
| Palm | 111 | 35 |
| Dorsal | 71 | 40 |
| Edge | 19 | 29 |
| Mixed | 64 | 20 |
| Unclear | 261 | 94 |

The prior coverage claim was too optimistic under this consistent stricter
rubric. The three-independent-readable-group floor reopens these gaps:

| Fitting cell | Readable groups | Missing to reach three |
| --- | ---: | ---: |
| Training rear-neutral | 1 | 2 |
| Validation front-underhand | 1 | 2 |
| Validation front-neutral | 2 | 1 |
| Validation rear-neutral | 2 | 1 |

`build/grip-anatomy-v11/cohort-audit.json` binds these counts. The full grip cohort
is no longer declared ready. This does not prevent the prespecified component
anatomy experiment; its palm/dorsal/edge validation classes each retain the
required number of crops and source groups. No new grip fusion is fit.

## Frozen comparison

Both arms start from the previously trained surface-v2 weights, never from
fresh ImageNet initialization. They use the same reviewed data, seed, sampled
batch IDs, augmentation schedule, twelve-epoch budget, class/source-group losses,
late-block encoder learning rate 0.00003, and head learning rate 0.0001. BatchNorm
stays in evaluation mode. Both select by validation anatomy NLL, including the
unchanged baseline at epoch zero. The full sample-plan hashes match.

The anatomy-only arm freezes quality-head parameters and excludes their losses;
the joint arm includes visibility/contact losses at 0.25/0.2. The anatomy-only
quality-head parameter freeze is asserted after training. Encoder gradients
still affect the embeddings seen by frozen heads; this arm alone is not a
promise of unchanged quality predictions.

The gate remains 65% recall per anatomical class, 70% macro recall, at least six
validation crops and three groups per class. All confidence thresholds remain
0.8. Reference updates were frozen before either arm ran.

On the same revised validation references (104 readable out of 218 inputs):

| Model | Palm | Dorsal | Edge | Macro | Reviewed anatomy gate |
| --- | ---: | ---: | ---: | ---: | --- |
| Previous model | 31/35 (88.6%) | 25/40 (62.5%) | 27/29 (93.1%) | 81.4% | Fail |
| Anatomy-only, selected epoch 6 | 33/35 (94.3%) | 25/40 (62.5%) | 27/29 (93.1%) | 83.3% | Fail |
| Joint, selected epoch 4 | 33/35 (94.3%) | 27/40 (67.5%) | 25/29 (86.2%) | 82.7% | Pass, development only |

The original-reference macro scores remain 77.2%, 76.8%, and 76.1%, respectively,
against the original 137 readable references. Reference revision alone raises
the unchanged baseline's apparent macro from 77.2% to 81.4%; that is not a model
improvement. Both arms use validation for selection, and two additional correct
dorsal crops are not evidence of statistical reliability or fresh-test gain.

This trial does not support the earlier hypothesis that removing quality
optimization necessarily improves dorsal anatomy. Joint training performs
slightly better on dorsal references; anatomy-only has higher macro but still
misses the required dorsal minimum. Source-group mean recalls and full counts
are retained in the comparison JSON.

## Separate quality adaptation

After the joint arm passed the reviewed component gate, a second freeze bound
its exact encoder/surface weights and reviewed data. Only its two existing
quality heads were adapted independently, with balanced class/source-group
sampling. Each head's checkpoint selection includes its starting state;
visibility chooses epoch 17 and contact epoch 4. Calibration uses the existing
1/1.25/1.5/2/3/4 temperature grid. No threshold is reduced.

All encoder and surface parameters remain exactly unchanged. Anatomical
probabilities on every validation crop are asserted bit-identical before/after
quality adaptation. This improves acceptance coverage, but also admits another
unclear crop:

| Model | Accepted crops | Correct accepted closed hands | Accepted mixed/unclear/noncontact |
| --- | ---: | ---: | ---: |
| Previous model | 0/218 | 0 | 0 |
| Anatomy-only | 3/218 | 3 | 0 |
| Joint | 9/218 | 8 | 1 |
| Joint encoder frozen, quality adapted | 14/218 | 12 | 2 |

Correct accepted anatomy/contact coverage is only 12/218 (5.5%). These are hand
components, not final grip decisions or bilateral/video performance. All
unreadable/mixed/missing cases stay in the denominator. Rejection cannot silently
be counted as correct exercise classification.

Both quality false accepts occur in the same exposed Kinetics frame of
`ID5_dnNIiks`: washed-out hands with weak anatomical detail. One is confidently
called palm and the other dorsal. The two-pass review says the RGB does not
support those distinctions. Their source/crop pixels, reference history,
confidences and boxes are retained in `quality-accepted-review.json` and
`quality-false-accepts.png`. They are not relabelled to match the classifier.

No fresh final test is run and no grip fusion, Core ML export, app model swap or
phone install follows. The app positioning prompt and front/rear policy remain
as previously built.

## Next bounded direction

Add targeted **training** examples with large, sharp closed hands, especially
true hand-edge/rear-neutral and dorsal variations, preserving all validation
and final-test roles. Restore the reopened validation gaps with new independent
groups rather than counting the same weak crops again. Use explicit real and
paired blur/exposure examples for the quality head so confident washed-out
predictions are rejected for lack of detail. Then compare the same frozen
quality baseline before reconsidering final grip training. A new anatomical
architecture or more unfocused archive intake is not established as necessary
by this trial.

## Reproduction and storage

Artifacts live in `build/grip-anatomy-v11/`. The existing research venv and local
weights are reused. Scripts:

- `review-grip-anatomy-v11.py` prepares the whole-cohort, prediction-hidden packet.
- `assemble-grip-anatomy-v11.py` applies the versioned reviewed annotations while
  preserving original references, source roles and every patch.
- `train-grip-anatomy-v11.py freeze`, then `anatomyOnly` and `joint`, execute the
  frozen warm-start comparison.
- `evaluate-grip-anatomy-v11.py` compares original and revised references.
- `train-grip-quality-v11.py` freezes the qualified development encoder and fits
  quality heads only.
- `evaluate-grip-anatomy-v11.py --with-quality` writes the separate final-stage
  comparison without replacing the earlier comparison.

Fixtures point to or include the versioned review and freeze. About 85 MB of
review evidence and candidate weights are retained. No full-frame export or
video is duplicated. Cache inspection is recorded separately; no app compiler
cache is created by this research-only step.


Final provenance verification caught source-row merging across identical RGB
frames that carried distinct historical annotation windows. Three crop records
lost inherited start/end/evidence metadata in the experimental JSONL. The
original experimental input and all bound model receipts are preserved.
`fitting-restored-row-metadata.jsonl` restores exact original row provenance;
use it for further cohort work. Its repair receipt asserts bit-identical RGB,
patch order, roles, views, targets and sampling weights in both data splits.
There is no fitting or metric change. The assembler now keeps distinct complete
source-row identities. `verify-grip-anatomy-v11.py` passes against the restored
snapshot, verifies all 744 original reference/role bindings, and verifies the
147 historical v7 pins and v9 result hashes remain unchanged. Failed initial
verification logs and the successful repair/verification receipts are retained.
