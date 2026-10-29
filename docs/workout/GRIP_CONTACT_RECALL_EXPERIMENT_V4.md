# Contact-recall experiment, v4

Contact detection improves on the remaining 19 unscored supported videos, but
the full grip pipeline remains unreliable. This candidate stays offline. No
phone model, orientation fusion or rep counter changed; every threshold remains
0.8, and the anatomical and visibility outputs are exactly unchanged.

## Audit and data repair

Direct inspection of 24 fixed-seed rejected contact crops from the exposed
16-video test found seven adequate contact inputs, nine blurred/occluded inputs,
four gloved hands, three overlapping masked hands and one case where widening
recovers clipped contact. Most failures therefore could not be solved by
widening alone. Source/tight/wider galleries and findings are retained under
`build/grip-contact-v4/contact-audit/`.

The contact crop is an automatic symmetric 1.6× expansion of the existing hand
box, capped at half the short frame dimension without shrinking an existing
larger box. It is clipped inside the source and resized to 192×192. The original
128×128 hand input stays unchanged. No exercise, grip or viewpoint label enters
crop geometry. Wider patches can include nearby wrist, equipment and background;
they do not constitute a general bar detector.

All 91 eligible existing open/invalid fitting examples received two direct
reviews using the actual wider pixels. Eight became verified closed contact,
seven became uncertain and were masked, and the remainder supplied 23
open/released and 53 invalid/empty-input negatives. No original label file was
overwritten. Already masked identities stay masked. The 543 prior closed labels
are inherited from their prior direct tight-pixel reviews, with the original
hand region retained inside the wider crop. They were not independently
reannotated in this run; label quality remains a limitation.

Additional fixed transition-time samples came from eight existing training
videos with open/release examples and four existing rear-view training videos.
Requests at 0.5/1/1.5/3/5/7/9 seconds yielded 81 frames and 119 automatic crops.
All received source-context and shuffled crop-only contact reviews. Two
disagreements about a hand partly hidden behind a vertical post were reopened
and adjudicated uncertain. Final additional labels are 91 closed, 22 open/non-
contact, four invalid and two uncertain. Open/non-contact includes hands clasped
together away from equipment: a closed fist alone is not apparatus contact.

The final fitting set has 579 frames and 781 crops. Training has 645 crops in
the same 122 video groups as before; these extra samples are not new independent
videos. Its eligible labels are 522 closed, 37 open and 52 invalid, with 34
masked crops. Validation has 136 crops: 120 closed, eight open, five invalid and
three masked. All earlier tests and all 19 new test videos remain excluded from
fitting.

## Controlled training

Three candidates share the frozen v3 anatomical and visibility model:

| Candidate | Contact inputs | Selected contact validation NLL |
| --- | --- | ---: |
| Tight control | Frozen pooled tight-hand features | 0.6801 |
| Tight + wider, frozen encoder | Frozen pooled features of both RGB patches | **0.5814** |
| Tight + wider, encoder fine-tuning | Same inputs, separate wider encoder late blocks trainable | 0.5814, falls back to frozen checkpoint |

The contact head has 32 hidden units. Training gives closed examples half the
loss mass, and open/non-contact and invalid/empty examples one quarter each,
with equal-video weighting within each subtype. This prevents abundant empty
crops from replacing release supervision. Ambiguous identities and contacts do
not become guessed negatives.

A deterministic label-only partition of the 29 original validation groups
balances closed/open/invalid examples into 15 selection and 14 calibration
groups. Selection has 60 closed and six negatives; calibration has 60 closed
and seven negatives. The new quality partitions are disjoint, but the original
appearance encoder had already used these videos for earlier validation.

Candidates and checkpoints are selected by balanced contact NLL. Separate
calibration uses a regularized positive-slope affine transform, rejected if it
increases false accepts on calibration negatives at 0.8. The selected wider
model keeps its encoder frozen; its calibrated contact recall is 20/60 on
selection and 25/60 on calibration, with no negative accepted in either tiny
negative sample. Fine-tuning overfits: selection NLL rises from 0.5814 to above
1.17 in the first fine-tuning epoch, so none of its encoder updates is selected.

The full wider candidate needs a second encoder pass. Its phone latency,
thermal behavior and native export have not been qualified.

## First result on the 19-video reserve

These are all remaining supported videos from the original 80-video reserve,
fixed before training. Earlier source thumbnails were exposed; these hand
patches had not been scored. Models, protocol, cropper and evaluator were frozen
before direct test-hand review and inference. No replacement sources or
test-based threshold/candidate changes were made.

Fixed 2/4/6/8-second requests yielded 72 unique frames from 76 requests, retaining
four end-of-video misses. Automatic extraction supplied 18 SDK and 90
observed-wrist fallback crops from 144 hand slots, with 36 unavailable slots.
Two direct review passes covered all 108 crops before scoring. Contact labels
are 89 closed, seven open/non-contact, three invalid and nine uncertain/masked.
The nine masked inputs include eight extremely quantized/occluded inputs and
one crop on the wrong foreground person. They remain in the inventory and are
counted as unverified if accepted.

| Contact-only result, threshold 0.8 | Previous v3 | Selected wider v4 |
| --- | ---: | ---: |
| Closed-contact detections | 9/89 (10.1%) | **27/89 (30.3%)** |
| False accepts on open/invalid crops | 2/10 | **1/10** |
| Videos with a correct contact detection | 4/15 eligible | **6/15 eligible** |

The retrained tight-only control detects only 2/89 closed contacts and accepts
0/10 negatives. Wider RGB features help this particular repair; extra training
examples alone do not establish an improvement. The rejected fine-tuning
candidate falls back to the same frozen checkpoint and has identical outputs.
The small, correlated samples and agent annotations do not establish population
accuracy or a dependable false-accept rate.

Contact improvement does **not** qualify full grip evidence:

| Full surface + visibility + contact gates, all at 0.8 | Previous v3 | Wider v4 |
| --- | ---: | ---: |
| Total accepted crops | 9 | 11 |
| Verified correct surface with closed contact | 5 | 6 |
| Closed contact, anatomical surface unreadable | 3 | 4 |
| Invalid contact input accepted | 1 | 1 |
| Videos contributing verified correct full accepts | 1 | 1 |

All six new correct full accepts come from one video. Raw anatomical correctness
is unchanged at 17/23 readable surface crops; the other 85 surface references
are unclear. This contact-only holdout has no verified side-surface examples,
so it cannot qualify the three anatomical classes or exercise grip types.

The wider model correctly accepts more real contacts, including contacts whose
grip orientation cannot be read. The frozen visibility/surface heads sometimes
wrongly treat those as usable anatomy. One clipped forearm/strap input also
passes every gate. More permissive contact evidence therefore exposes existing
anatomical-quality failures. It cannot be adopted as a dependable grip estimate.

![Actual fresh-test successes and failures](../../build/grip-contact-v4/examples.png)

The gallery includes a recovered dorsal-hand observation, the clipped invalid
accept, newly accepted unreadable anatomy, a rejected known palm, open hands,
a masked wrong-person crop, and missed contacts. The tight and wider images
and before/after scores are displayed together.

## Earlier-video regression

The previous 32- and 16-video tests are exposed regression evidence, not new
qualification. Their original tight-patch references are retained for every
model, even when the wider crop might reveal more contact, so reference changes
cannot improve the measured result.

Across 239 automatic crops, contact recall rises from 36/214 to 72/214, but
contact false accepts rise from 0/21 to 2/21. Full gates accept 32 instead of 24:
verified correct surface/contact accepts rise only from 22 to 24, known wrong
surface accepts fall from two to one, and unverifiable anatomy accepts rise from
zero to seven. This regression is another reason to retain the previous model.

## Verification, provenance and storage

All **434 Python tests pass**. New tests cover wider-box containment, preservation
of a large original crop, separate negative-subtype weighting, ambiguous-label
masking/adjudication, deterministic group partitioning and exact invariance of
anatomical/visibility outputs to the contact branch. `git diff --check` passes.
The existing shared JVM planner was rerun to export the new source-bound plans;
its initial cached task did not notice the changed environment paths, so no
missing/failed export was consumed. No shared or native app code changed.

An initial regression membership assertion omitted old videos having zero ready
hand crops because its allowlist came from crop references. Evaluation stopped
before model inference. The allowlist was repaired from the original reserve,
with every model/script/protocol hash unchanged, before fresh hand review and
first scoring. Both freeze inventories and the assertion log are preserved.

The audit checks every tight and wider crop against its source hash and exact
bilinear reconstruction: 819 frames, 1,128 tight crops and 1,128 wider crops.
All 29 freeze pins remain unchanged. Both scored populations have zero
anatomical/visibility output difference and exact eager/TorchScript parity.
The old base-model tensors are unchanged; the selected wider encoder is also
unchanged. Fitting, regression and fresh test video groups remain disjoint.

Between batches, pruning removed 965 unreferenced native PNGs totaling
**473,830,805 bytes**, preserving all 81 additional fitting and 72 fresh test
frames, original videos, clocks/poses, reviewed crops, labels, models and first
scores. All clips were already cached; no new videos were downloaded. The idle
Gradle daemon was stopped, and compiler-cache inspection found no further
allowlisted caches to remove. APFS free-space changes need not equal removed
file bytes.

Durable references and results are `Evaluation/fixtures/grip-contact-*-v4.json`.
Complete local model candidates, logs, actual images, first predictions and
hash-pinned provenance are under `build/grip-contact-v4/`. This test is now
exposed regression data. No orientation fusion or phone deployment is justified
by this experiment; the next model work must address falsely accepted
anatomical evidence and independently verified labels before broader coverage.
