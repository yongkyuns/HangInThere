# Grip observability and temporal prototype, v5

Temporal agreement alone does not repair the current pipeline. On 16 new
recordings, the frozen per-frame comparator and both temporal variants each
produce **15 unknowns and one unverifiable accepted label**, with no verified
correct automatic video classification. This is a negative experiment, not a
qualified improvement. No phone model, live app, rep counter, threshold or
trained weight changed.

## Source versus crop audit

Eight deliberately chosen, previously exposed diagnostic cases now have short
clips, original-frame/crop comparisons and four-moment sequence sheets under
`build/grip-temporal-v5/human-review/`. These are diagnostic examples, not an
accuracy sample. The packet hides model guesses and previous labels. Human
verification has been requested and **remains pending**; our inspection is an
agent review, not independent human ground truth.

The examples separate different failures:

- A01 has useful actual hand pixels but an earlier incorrect palm prediction.
  This is a classifier failure.
- A02 loses fingers outside the original camera image. Widening a crop cannot
  restore missing source pixels; framing guidance or unknown is necessary.
- A03 has a clear athlete and hand despite a coach. The presence of another
  person alone must not cause rejection.
- A04 has more useful movement/context across the sequence than the tiny
  individual hand patch. Its source-level interpretation needs human checking;
  no old frozen reference is silently revised.
- A05 contains hanging followed by release. An open/reaching patch is not
  ongoing closed contact.
- A06 shows contact but glove/wrap and viewpoint obscure anatomical surface.
- A07 remains very small, dark and quantized in the inspected source segment.
- A08 contains a visible exercising athlete but a crop on a foreground
  bystander. This is hand ownership failure.

The evidence supports fixing different causes separately. It does not justify
relabeling every ambiguous hand from the apparent exercise being performed.

## Frozen prototype

The prototype reuses the v4 RGB surface/visibility/contact model, the existing
three-way image grip head for neutral evidence, and the existing frozen
torso-view model. It performs **no training** and does not invoke the unqualified
v2 fusion trainer. The binary mapping below is an explicit diagnostic rule,
not a trained or qualified fusion model.

Automatic shoulder-center/span continuity follows one overhead athlete across
the full native pose sequence. Person-list order is not identity. Acquisition
requires two observations; ambiguous tracking clears evidence. This small
tracker still fails in real footage, including incorrect body-wrist ownership
and loss of cropped subjects. It is not production tracking.

Fixed requests at 0.5, 1.5, ..., 8.5 source seconds provide at most one frame per
second. Both hands contribute at most one vote, duplicate RGB hashes cannot
create extra support, and incompatible hands cause unknown. Temporal variants
require at least two agreeing time bins separated by one second, use an
eight-second bank, and abstain on observed target changes or conflicting grip
evidence. Averaging preserves the confidence range of its inputs; it does not
multiply correlated frame likelihoods. Scores are **not calibrated probabilities
of correctness**.

Torso direction is a causal arithmetic average of available torso RGB outputs.
All five face cue inputs are zero in this ablation, isolating torso/time
behavior. Missing context remains uniform, never rear-facing. This does not
re-evaluate a new face detector or establish head-turn robustness on videos.

Palm/back evidence and sufficiently confident front/rear torso direction map
to overhand/underhand. Side appearance alone never implies neutral. A neutral
candidate needs the frozen prior image head to predict neutral at 0.8 for
**all three supplied views** of the same hand patch. This conservative check
prevents supplied orientation from creating neutral confidence; it does not
qualify the old neutral model. Strong incompatible binary/neutral evidence is
rejected. Unknown/oblique torso evidence can leave binary grip unresolved.

Every relevant surface, visibility, orientation, neutral and contact threshold
remains **0.8**. The three predeclared comparisons use identical pixels/models:

| Variant | Decision rule |
| --- | --- |
| Per-frame comparator | Current-frame anatomical/visibility/contact and torso evidence |
| Temporal with contact support | Stable torso and two consistent bins; at least one contact-qualified supporting observation |
| Temporal without required contact | Same temporal rule, without required contact evidence |

The comparator is an offline control using the same semantic mapping, **not a
measurement of the shipping phone classifier**. Fixed one-Hz sampling does not
yet choose the sharpest frame from every full-rate interval. This prototype
tests evidence aggregation, not a complete hand tracker or set detector.

## New recording intake and direct references

The [publisher distribution](https://github.com/cvdfoundation/kinetics-dataset)
supplied the 61,091,021-byte Kinetics-700-2020 validation pull-up archive.
Acquisition excludes 950 previously inventoried recording IDs, including
existing cached clips and fixture references. It selects the first 16 remaining
recordings by SHA-256 of archive-member name before looking at source pixels.
No replacements are selected for difficult footage or failed native decoding.
This is recording-group separation, not verified athlete independence.

Fifteen native exports succeed; one rejects `invalidGeometry` and remains in
the video denominator with no fabricated frames. Fixed source-time requests
produce 127 selected frames and 254 hand slots. There are 108 actual crops and
146 missing slots. Only 21 SDK crops are initially available; automatic
observed-wrist fallback supplies the others on tracked frames. Source labels
never affect target selection or crop geometry.

All source sample sequences and all 108 exact tight/wider pairs receive direct
agent inspection before inference. A shuffled crop-only second pass produces
eight disagreements, reopened against actual pixels. Several are ownership
conflicts: a physically visible bystander hand must not be labeled as usable
target-athlete evidence. References bind source hash, frame hash, crop hash,
box and side. They are agent annotations and remain open to human correction.

There are 25 anatomically readable crops (23 back, two side), 83 unclear crops,
73 closed-contact references, nine open, ten invalid, and 16 uncertain contacts.
Thirteen crops have uncertain target-hand identity, including overlapping
hands. Readable anatomy alone does not establish valid target contact.

Source-level references are two overhand, one underhand, one neutral and twelve
unknown. Unknown includes cropped hands, unreadable source pixels, mixed edited
sets, competing active athletes and the native failure. The only underhand
reference is a cropped close-up of a child, not representative adult chin-up
validation. This unscreened action-class archive is therefore **not a suitable
standalone grip-accuracy qualification set**. Unknown references are retained
and any accepted prediction on them is reported as unverifiable, never correct.

Models, inference/cropping code, protocol, source membership and input manifest
were frozen in 21 hash pins before direct fresh-pixel review or scoring. The
completed reference file was separately hash-frozen before first inference.
All remain unchanged after scoring. No test-based threshold, model or policy
selection follows these results.

## First video-level result

| Automatic variant, all 16 recordings | Correct | Known wrong | Unknown | Accepted but unverifiable |
| --- | ---: | ---: | ---: | ---: |
| Per-frame comparator | 0 | 0 | 15 | 1 |
| Temporal with contact support | 0 | 0 | 15 | 1 |
| Temporal without required contact | 0 | 0 | 15 | 1 |

Zero known wrong decisions is not evidence of reliability: all four recordings
with a known grip reference are rejected. The only accepted recording is the
same source in every variant, a distant formal-shirt athlete with partially
gloved hands whose grip remains unverifiable. Its first accepted source time
is 7.467 seconds for the per-frame control and 8.533 seconds for temporal
agreement. Repetition stabilizes the existing guess without verifying it.
There is no correct automatic decision for which to report time to decision.

A separately marked **privileged reviewed-orientation** diagnostic yields one
correct overhand recording, thirteen unknowns and two unverifiable accepts.
Its correct first-decision time is 7.474 source seconds. This uses reference
view direction and is not app accuracy; it also does not solve oblique-view
fusion or unreliable hand anatomy.

The unchanged anatomical/visibility gate accepts 18 crops: eight match readable
surface references, one has a wrong surface, and nine remain anatomically
unverifiable. These are crop-anatomy counts, not exercise grip accuracy or proof
of target contact. Seven correct dorsal-hand accepts come from one rear-oblique
yellow-shirt recording whose torso evidence remains weak/oblique. A readable
close-up underhand source produces no model crops because body acquisition
never succeeds. Thus smoothing cannot repair either its missing input or the
other recording's low orientation confidence.

![Actual inputs and blocking stages](../../build/grip-temporal-v5/examples.png)

## Decision and next implementation priority

Keep temporal agreement as a small testable component, but do not deploy this
prototype or train another readability head as the automatic next step.
First repair and verify the **hand extraction/ownership contract**: whole hand
inside the source/crop, correct exercising athlete, and hand localization that
can still work when the body is partly outside the image. Then evaluate stable
torso direction and oblique-view handling against independently verified
examples. Contact and readable anatomy remain different observations.

The reviewed failures can now serve as fixed regressions. A subsequent accuracy
test needs new recordings with independently verified grip labels and usable
viewpoint coverage, including adult chin-ups and neutral grips, rather than
assuming that public action labels contain that supervision. Rep counting
should remain useful when grip is unknown; no rep lifecycle or counting logic
was changed in this experiment.

## Checks, retained evidence and storage

All **447 Python tests pass**, including thirteen new behavioral tests for
duplicates, confidence inflation, frame correlation, competing athletes, person
reordering, subject gaps, conflicting hands, neutral semantics, low visibility,
optional contact, non-overhead closed hands and label leakage. The existing
shared JVM hand planner was rerun successfully. `git diff --check` passes.
These checks validate the implementation, not model accuracy or phone latency.

The audit verifies all 21 freeze pins, the separate reference freeze, and exact
RGB reconstruction of all 127 source frames, 108 tight crops and 108 wider
crops. Old v2/v3/v4 evidence and weights are preserved. Inference on the Mac CPU
took 12.81 seconds across the sampled corpus using three frozen models and
three view passes for neutral; device performance has not been measured.

Pruning removed **400 unreferenced native PNGs / 170,022,792 bytes**, retaining
all selected source pixels, original recordings, reviewed crops, poses/clocks,
failed-export logs and first scores. The downloaded archive and selected video
files remain bounded and pinned. The idle Gradle daemon was stopped; compiler
cache inspection found no additional allowlisted caches to remove. Filesystem
free-space changes are not attributed to this pruning alone.

Durable protocol/references/results are `Evaluation/fixtures/grip-temporal-*-v5.json`.
Complete first predictions, source/crop reviews, human-review clips, audit,
provenance and test logs are in `build/grip-temporal-v5/`. Human verification
is pending and the now-exposed cohort is regression evidence for future work.
