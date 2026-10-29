# Quality-gate repair, v3

The quality model no longer rejects every crop. This fixes a training/calibration
mismatch without changing the anatomical surface classifier or lowering any
threshold. It is an offline repair candidate, not a qualified phone grip model.

## Why correct classifications were rejected

An anatomical choice and a quality score answer different questions. A crop can
have a correct winning class while the separate visibility or contact head
rejects it. The previous experiment had a systematic quality-head problem:

- The shared sampler combined 70% surface, 20% visibility and 10% contact
  sampling weights. Surface training excludes unclear crops, so this delivered
  84.85% visible and 84.89% closed-contact positive mass to the quality losses.
  Those losses did not correct for that sampling distribution.
- Calibration then evaluated each quality head with 50% positive class weight.
  Its only available correction was temperature scaling. That changes score
  spread but has no intercept to repair a biased decision offset. Both quality
  temperatures reached the largest allowed value, 4.0.
- The visibility head still ranked examples somewhat usefully (validation AUC
  0.749), but contact was weak (AUC 0.618). They were attached to an encoder
  selected primarily for surface recognition; contact NLL did not participate
  in checkpoint selection.

The resulting model's highest visibility score on a known crop in the previous
32-video test was 0.7715, below the unchanged 0.8 gate. For example, E0107 had a
correct dorsal-hand score of 0.997, but visibility 0.734 caused rejection.
This is a model-training problem; changing the surface label cannot fix it.
`build/grip-quality-v3/diagnosis.json` contains the measured distributions.

## Changes

`scripts/train-grip-quality-v3.py` freezes the entire v2 appearance model and
trains visibility and contact independently, each with its own exact binary
class/video-balanced full-batch loss. It removes the appearance-biased sampler.
Each head selects its own checkpoint using quality NLL.

Two declared candidates use either final encoder features or pooled features
from encoder stages 3, 8 and 12. The latter retains finer spatial-scale image
information for quality discrimination and was selected using validation only.
It reuses the same encoder pass and adds two small 32-unit heads; it introduces
no face, orientation or scene input. Feature normalization uses training data
only. Original anatomical outputs are bit-for-bit identical.

The 29 existing validation video groups are deterministically split into 15
quality-selection groups (79 crops) and 14 calibration groups (57 crops). They
are disjoint from the 122 training video groups (123 recordings, including two
angles of one publisher demonstration). The original encoder had
already used all original validation groups for v2 selection, so the calibration
partition is independent of the new quality-head selection, not a newly unseen
encoder-validation set.

Calibration can fit a positive-slope affine logit transform on its separate
groups, using per-video natural prevalence weights. A validation-only diagnostic
exposed a dangerous proposal for contact: with only two negative contact crops
versus 52 positives in the calibration partition, the affine fit nearly erased
ranking and accepted both negatives. That candidate run is preserved. Before
any fresh hand review or test inference, an explicit calibration guard was
added: reject an affine mapping if it increases false accepts on reviewed
calibration negatives at 0.8. The selected visibility calibration passes this
guard; contact uses its balanced-trained raw head instead. This guard protects
that small calibration sample; it is not a population safety guarantee.

Finalization changed only the calibration buffers of the completed candidate
weights. It did not retrain, change checkpoint selection or use test scores.
The original trainer, protocol, candidate receipts and finalization script are
retained. Final models and scripts were hash-frozen before fresh hand review
and before either test comparison.

Surface, visibility and contact thresholds all remain **0.8**. No gate is
bypassed or replaced with the raw class. A full accepted crop needs all three.

## Results

| Population | Before: full-gate accepted | After: full-gate accepted | Correct | Wrong / unverifiable |
| --- | ---: | ---: | ---: | ---: |
| Previous 32 videos, 151 crops | 0 | 22 | 20 | 2 / 0 |
| Previously unscored 16 videos, 88 crops | 0 | 2 | 2 | 0 / 0 |

The previous test is exposed regression evidence. Its 22 accepted crops come
from eight videos. The two new-test accepts are correlated frames from **one
video**, so they are a demonstration of repaired behavior, not evidence of
general reliability. All tested anatomical probabilities are unchanged; raw
anatomical correctness remains 64/107 known crops on the previous test and
10/22 on the new test.

Visibility passes 64/107 known crops and 12/44 unclear crops on regression,
and 11/22 known crops and 3/66 unclear crops on the new test. Visibility alone
is therefore insufficient. Adding the unchanged surface-confidence gate yields
41 regression accepts (29 correct, four wrong, eight unverifiable) and six new
accepts (three correct, three wrong). The separate contact gate reduces those
to the full-gate counts in the table.

The contact gate still rejects many real closed hands: it accepts 33/142 on
regression and 3/72 eligible closed-hand new crops. It rejects all nine
regression and all 12 new open/invalid references. Four new overlapping-hand
crops have contact identity masked; none is accepted. These small negative
samples do not establish a production false-accept rate.

![Actual inputs, scores and remaining failure](../../build/grip-quality-v3/examples.png)

The gallery shows newly accepted correct dorsal hands, the unchanged wrong
anatomical choice at E0119 that now passes quality, a still-rejected palm,
empty-bar and clipped-hand rejects, a glove, and overlapping rope hands.
Quality training cannot correct a confidently wrong anatomical classifier.

## Test discipline and remaining limits

The new reserve is the 16 lowest recording hashes among 35 supported sources
left unsampled in the original 80-video reserve. Membership was fixed before
training. Earlier source thumbnails had been reviewed, but these hand patches
had not been scored. No original test source entered fitting or calibration.

Fixed 2/4/6/8-second requests yielded 59 unique frames; five requests beyond
video end were not replaced. Automatic extraction produced eight SDK and 80
observed-wrist fallback crops from 118 hand slots, retaining 30 missing slots.
Both actual-pixel review passes covered all 88 crops before scoring: 11 dorsal,
seven palm, four side and 66 unclear. They are agent reviews, not independent
human gold labels. No provisional exercise-grip labels are credited in this
quality-only test. Difficult inputs are retained rather than removed to improve
the accepted-accuracy figures.

The blanket-rejection failure is repaired. Broad coverage, contact recall,
side-hand recognition and confident anatomical errors remain unresolved. The
original surface-qualification failure still blocks trained orientation fusion
and phone deployment. No rep counter or live app behavior changed. These tests
do not measure continuous-video confirmation or device latency.

## Verification and storage

- All **425 Python tests pass**, including own-head class/video balancing,
  masked contact labels, ranking-preserving calibration, rejection of unsafe
  majority-prior calibration, unchanged anatomy and acceptance accounting.
- Both comparisons have exact TorchScript/eager parity and zero anatomical
  output difference. The audit verifies all 255 original appearance state
  tensors and 17 pre-test freeze pins.
- All 168 evaluated source frames and 239 hand crops hash-match and reconstruct
  exactly from their reviewed source boxes, including after cleanup.
- `git diff --check` passes. The existing shared JVM planner export passed with
  `-Pworkout.appleOnly=true`; initial attempts without that repository flag
  failed because the local Android SDK is absent. No app code was changed.
- Unreferenced frame-cache cleanup removed 492 PNGs totaling **193,633,826 bytes**,
  retaining all 59 reviewed frames, source videos, poses, labels and models.
  The Gradle daemon was stopped. Compiler-cache inspection found no additional
  allowlisted caches to remove.

The durable summary is `Evaluation/fixtures/grip-quality-results-v3.json`.
Protocol, test reserve and direct patch reviews are adjacent fixtures. Complete
local logs, original diagnostic candidates, frozen repaired model, first test
scores and provenance are under `build/grip-quality-v3/`.
