# Rejection audit: contact is the largest immediate blocker

The fixed v11 frozen-quality model correctly identifies anatomy on 83 of the
100 reviewed readable, closed-grip, front/rear validation patches. It accepts
only 12. The contact gate rejects **70 of those 83 correct predictions**; it is
the only failing gate on 23. Raising confidence without fixing this head's
supervision is not supported by the remaining false accepts.

This is an exposed development diagnostic, not a new model, a fresh test or
live phone grip qualification. The 100-patch subset uses agent reference labels;
it is not an executable input filter. All 218 validation inputs remain in the
counterfactual analysis. Existing references, models and source roles are
unchanged.

## Exact blockers on correctly classified readable hands

| Failing gates | Correct anatomical predictions |
|---|---:|
| None: accepted | 12 |
| Contact only | 23 |
| Readability + contact | 28 |
| Anatomy confidence + contact | 11 |
| Anatomy confidence + readability + contact | 8 |
| Anatomy confidence only | 1 |
| Total | 83 |

Counting overlapping failures, anatomy confidence rejects 20/83, readability
rejects 36/83, and contact rejects 70/83. Another 17/100 readable hands have
wrong raw anatomical predictions; all are currently rejected. Sixteen of those
17 are also rejected by anatomy confidence. Fixing contact alone therefore
cannot establish reliable grip recognition or recover every correct patch.

For example, H327 visibly grips a bar and is correctly called dorsal with
0.961 anatomy confidence and 0.801 readability, but contact is 0.630. H512
visibly wraps its fingers around a handle, with anatomy/readability at
0.994/0.951; contact 0.791 prevents acceptance. The source frame and actual
patches are preserved in `contact-review-01.jpg`.

## Bypassing checks is not the implementation fix

The diagnostic keeps the fixed model and 0.8 thresholds. Hypothetical gate
bypasses are calculated separately, never installed or saved as a candidate.

| Gates on all 218 inputs | Accepted | Correct closed hands | Wrong readable anatomy | Unclear/mixed/noncontact |
|---|---:|---:|---:|---:|
| Current | 14 | 12 | 0 | 2 |
| Bypass anatomy confidence | 17 | 13 | 0 | 4 |
| Bypass readability | 14 | 12 | 0 | 2 |
| Bypass contact | 45 | 35 | 1 | 9 |

Readability has no immediate single-gate gain because contact is also blocking
those examples. That does not make readability unnecessary. Bypassing contact
adds 23 correct hands but also one wrong readable anatomy and seven additional
unusable/noncontact accepts. These are hand components, not final grip labels.

The current temperatures are `[1, 1, 4]` for anatomy, readability and contact.
The contact calibration selected T=4 using the original balanced validation
NLL: **0.656 at T=4 versus 0.879 at T=1**. Forcing contact back to T=1 would
accept 44/218: 35 correct closed hands, one wrong anatomy and eight
unclear/mixed/noncontact cases. That confidence inflation is not a verified
calibration repair. No gate or temperature is changed here.

## Supervision needs a separate contact audit

The inherited contact targets are substantially weaker than the anatomical
readability review:

- Training has 424 eligible `closed` targets. Only 193 have a readable dominant
  anatomical surface; 184 are anatomically unclear and 47 mixed.
- Training has only **four readable `open` crops from three source groups**.
  Validation has four, from two groups. There are also 62 eligible training
  `invalid` crops treated as negative contact targets by the existing recipe.
- Anatomical ambiguity alone does not invalidate contact. A glove or a mixed
  palm/dorsal view may still show fingers enclosing the bar. Conversely, a source
  scene suggesting a pull-up does not make closure observable in a blurred
  patch. Those cases must be reviewed separately.

Five diagnostic sheets contain 26 source/patch pairs: six source-diverse
contact-only correct rejections, six multiple-quality-gate correct rejections,
all eight existing readable open controls, and six unclear/mixed-anatomy
training positives. Every sheet was visually inspected. This review sees
predictions and old labels, so it is diagnostic, not independent gold.

The versioned observations in `contact-review.json` describe 14 visible grasps,
four clear open/empty hands and eight uncertain cases. Only four of the eight
inherited readable-open controls are judged unambiguous no-grasp examples in
this review: three training crops from two groups and one validation crop from
one group. These observations are not applied to the fitting input.

H570's `open` reference is questionable because the fingers appear to curl
over a handle. H198/H478/H677 show partial or hooked hands beside hanging
handles; these views do not justify a hard no-grasp target. H162/H382, inherited
closed examples from the same athlete, also need source-sequence adjudication.
They occur at different source times: this is **not an identical-pixel label
contradiction**. Hook grasps must not require a fully closed thumb or fist.

The six training positives demonstrate why simply masking every anatomically
unclear input would also be wrong. H172's gloved hand and the mixed surfaces in
H502/H089 still show enclosure; H244/H202 are too blurred for strong patch-only
grasp supervision. H244 is an actual hand crop, not an established localization
failure. Original contact references and review history are retained throughout.

## Next implementation direction

Repair the visible-grasp supervision before another contact fit:

1. Apply a consistent rubric across the full development cohort: object visibly
   enclosed or hooked; clearly open/empty; or unobservable. Review source
   sequences for the ambiguous controls. Keep contact observability separate
   from anatomical-surface visibility, and preserve the old references.
2. Fill the clear open/empty control gap within the supported front/rear setup,
   preferably from existing development video setup/release intervals. Keep
   source roles fixed and count source groups, not correlated frames. Clear
   controls should include hands near apparatus, rather than mainly background
   or a different body posture.
3. Freeze the anatomy encoder/head and compare one contact-only adaptation on
   the corrected observable targets. Unobservable contact masks contact loss;
   it does not disappear from input/admission accounting. Select/calibrate with
   separate source videos and retain the unchanged 0.8 gate for the comparison.
4. Require a gain in correct accepted hands without more wrong or unusable
   accepts. Report all-input, supported-capture and per-source results. Do not
   infer final pull-up/chin-up/neutral accuracy from these component scores.

No separate RGB quality encoder, additional blur experiment, threshold reduction
or app model swap follows this diagnostic. Full contact relabeling, gap filling
and retraining remain; the 26-case error-selected packet is not sufficient to
fit or qualify a replacement. Final reserve recordings remain unread.

## Reproduction and storage

Run `build/grip-training/venv/bin/python scripts/audit-grip-rejections-v14.py`
with a fresh `build/grip-rejection-v14/` output directory. It writes a protocol
before inference, verifies the dataset and exported/weight-model agreement,
records per-patch probabilities and exact gate combinations, and generates the
source/patch review sheets. The script never modifies existing evidence.

`contact-review-notes.txt` and `contact-review.json` retain the subsequent visual
observations with source/patch hashes. Verification checks the existing v7,
v9 and v11 pins, the fixed model/input hashes, each counterfactual partition and
the review's source roles. Reuse the existing environment, weights and RGB;
only a few diagnostic sheets and JSON/text receipts are added. No media, model
or embedding cache is duplicated.
