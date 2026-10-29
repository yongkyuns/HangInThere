# Image-only hand-surface qualification, v2

The retrained appearance model improves raw palm/dorsum/side recognition on
separate videos, but fails qualification. It remains offline. Orientation fusion
was prepared and tested as code, but was not trained because the appearance
validation gate failed. No phone model or rep-counting behavior changed.

## Data and review

The fitting set contains 498 frames and 662 hand crops: 526 training and 136
validation crops. It retains the previous reviewed fitting set and adds 283
automatic crops from 52 selected recordings, plus 16 crops from two publisher
camera angles of one neutral-grip demonstration. The publisher angles share one
training group; they are not counted as independent athletes. The
[Gymkee demonstration](https://gymkee.com/exercises/lats/neutral-grip-pull-up/)
adds front and oblique views of actual parallel handles. Its two downloaded
angles total 4,690,756 bytes. Other clips came from the already cached Kinetics
archive.

All 81 remaining archive candidates were source-reviewed before selecting 52.
New hand labels came from actual RGB crops in a source-context pass and a
shuffled crop-only pass. The two agent passes agreed; they are not independently
annotated human gold labels. Palm, dorsum, side and unclear are direct appearance
labels, never inferred from exercise or athlete orientation. Open hands can have
a known surface; invalid/unclear crops are retained as visibility/contact examples.

Training and validation roles are grouped by publisher video ID. Earlier tests
and the new 80-video test reserve are excluded from fitting and model selection.
This prevents video overlap, not all possible athlete or location overlap.

The fresh test selected up to four videos per provisional grip/view cell by a
fixed hash ordering, before examining test hand pixels or predictions. The 32
selected videos yielded 109 unique frames at fixed 2/4/6/8-second requests.
Nineteen requests beyond video end were not replaced or duplicated. Of 218 hand
slots, 151 yielded automatic crops: 62 SDK detections and 89 observed-wrist
fallback crops. The other 67 slots remain recorded as unavailable.

Both actual-pixel review passes covered all 151 crops before any test prediction.
Seven provisional grip labels were corrected from enlarged hands and equipment
geometry; one source's grip was excluded as unverifiable. These corrections are
explicit in the bound review fixture. They do not alter crops or surface inputs.
There are 107 known-surface test crops (16 palm, 54 dorsum, 37 side) and 44 unclear
crops. Only four crops retain a verified chin-up label, so this holdout cannot
qualify chin-up accuracy. No replacement sources were added after that discovery.

## Training and decision rule

`scripts/train-grip-surface-v2.py` fine-tunes a MobileNetV3-small encoder with
separate three-class anatomical surface, visibility and contact heads. Its only
inference input is the 128×128 RGB hand patch. Athlete orientation cannot change
its output. Unclear labels supervise visibility without assigning an anatomical
class. Training uses class/video weighting and image augmentation.

The predeclared candidates were full encoder fine-tuning and late-block
fine-tuning, after three head-only warmup epochs. Selection and temperature
calibration used validation data only. The full candidate was selected at epoch
6; its validation selection loss was 0.79796. Completed weights were unchanged
when a metadata-only `None` source-group sorting failure was repaired. Recovery
reconstructed receipts from the saved histories and weights, and checked exact
eager/export equality on every validation crop. Original trainer and recovery
script hashes are recorded.

Validation qualification requires at least 65% recall for every surface, 70%
macro recall, and minimum crop/video coverage. The selected model achieved
22/27 palm, 26/42 dorsum and 9/12 side: 72.8% macro recall, but only 61.9% dorsal
recall. The gate therefore failed. The selected model, baseline, scripts,
protocol and receipts were hash-frozen before opening fresh test images.

`scripts/train-grip-fusion-v2.py` guards against training after a failed gate.
Its proposed fusion keeps neutral probability dependent on RGB alone and lets
orientation adjust only overhand/underhand odds through palm/dorsum evidence.
Uniform surface evidence cannot manufacture an orientation-based grip. Unit
tests verify these properties. There is no trained v2 fusion model in this run.

## First fresh-test comparison

Both models receive exactly the same 151 automatic crops. The old model's
four-way surface output is conditioned on its three anatomical classes for the
raw comparison, so both models make a forced three-class choice. These figures
are appearance accuracy on known references, not accepted grip labels.

| Surface | Previous | New |
| --- | ---: | ---: |
| Palm | 8/16 (50.0%) | 11/16 (68.8%) |
| Dorsum | 12/54 (22.2%) | 36/54 (66.7%) |
| Side | 19/37 (51.4%) | 17/37 (45.9%) |
| All known crops | 39/107 (36.4%) | 64/107 (59.8%) |

Equal-video recall tells the same broad story: palm 42%→70%, dorsum 19%→70%,
side 52%→47%. Correlated frames and the small class-specific video counts limit
the strength of these comparisons.

At the frozen 0.8 surface and 0.8 visibility thresholds, the new model accepts
**zero** crops. Forty-one known crops pass surface confidence, but none passes
visibility; the highest known-crop visibility is 0.7715. Validation calibration
selected temperature 4.0 for visibility and contact because their uncalibrated
validation predictions were overconfident. This is an unresolved quality-head
problem, not evidence that every test hand is physically invisible. Thresholds
were not lowered after seeing the test.

The previous model accepts two wrong known-surface predictions and one
unverifiable crop, with no accepted correct known prediction. The new model
accepts no unverifiable crops either. At the separate 0.8 contact threshold,
closed-contact recall regresses from 47/142 to 8/142; both reject all nine
open/invalid references. These weaknesses block deployment even where raw
anatomical classification improved.

![Actual fresh-test hand inputs and predictions](../../build/grip-surface-v2/test-comparison/examples.png)

The gallery includes corrected dorsal recognition, persistent dorsal/palm
confusion, front/rear neutral examples, a missed ring-side appearance, and an
unclear crop. It deliberately shows both raw choices and accepted output.

## Reproduction and next decision

`Evaluation/fixtures/grip-surface-protocol-v2.json` fixes the experiment rules.
Fitting, reserve, source roles, direct patch reviews and results are in the
`grip-surface-*-v2.json` fixtures. Local artifacts and scripts are pinned under
`build/grip-surface-v2/provenance/manifest.json`. The first frozen comparison is
`test-comparison/report.json`; predictions retain every reference and output.

`scripts/audit-grip-surface-v2.py` verifies source hashes and exact bilinear RGB
reconstruction for 607 reviewed frames, 813 hand crops and 415 torso crops. It
also verifies all 20 pre-test freeze pins and disjoint fitting/test video groups.
TorchScript and eager predictions agree exactly. This run does not measure
continuous video, bilateral temporal confirmation, rep counts or phone latency.
All 414 Python tests pass, including appearance target masking, fusion
invariance, confidence accounting and conservative cache-pruning checks.
`git diff --check` also passes. The idle Gradle daemon was stopped after export.

The next fitting experiment should improve side/oblique appearance coverage
and train useful visibility/contact discrimination on difficult and invalid
automatic crops, with validation-only acceptance metrics. Verify true chin-up
sources from hand pixels before allocating a new independent test. Treat this
run's test as exposed regression evidence from now on. Train orientation fusion
only after those gates pass; do not mask the current failure with a lower
test-chosen threshold.

## Storage

Between export batches, the frame-cache pruner preserved every reviewed frame,
patch, label, model, original recording and pose/clock file. It removed 2,639
unreferenced native PNGs totaling 1,455,296,860 bytes, with path/hash receipts for
each eviction. Unsampled times can be regenerated from original video into a
fresh export directory. Earlier compiler-cache cleanup reclaimed an estimated
898,437,120 bytes. See [storage policy](RESEARCH_STORAGE.md).
