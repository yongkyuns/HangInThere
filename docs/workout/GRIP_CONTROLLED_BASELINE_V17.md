# Confirmed-view grip baseline and separate-video test (v17)

The two previously exposed owner recordings produce correct overhand/underhand
labels during each marked full-cycle interval when the camera view is explicitly
confirmed. The separate-video test fails: omitting readability admits a wrong
underhand label on a front-view pull-up, and the clear rear-view performer never
acquires the counter's extended-arm start. The readability omission is retained
as an offline experiment and is **not promoted** to the phone.

This establishes that the pipeline can produce labels under controlled
conditions. It does not establish reliable complete-set coverage or independent
chin-up accuracy. No training, model export, threshold adjustment, shipping
counter change or phone installation occurs in v17.

## Frozen comparison

Both variants use identical automatic wrist crops, raw model outputs, observed
body tracks and the actual shared pull-up counter. Front/rear is supplied from
source review as a diagnostic setup parameter; automatic orientation is outside
this test. Neither variant requires learned image contact or an image-detected
bar. The offline replay seeds its support reference from observed extended
wrists, which remains an acquisition assumption rather than verified apparatus
detection.

- A requires the frozen readability score to reach 0.80.
- B omits readability. Surface and neutral classifier thresholds stay at 0.80,
  and confidence remains the original model score.

Two distinct image observations, at most one vote per source second and spanning
at least one second, must agree. Two hands in one image count as one vote.
Exercise eligibility, current wrist support and source/track/exercise identity
are required. Current conflicting hands reset confirmation. A retained label
within an active exercise is reported separately from new image evidence.
Release, unusable context or missing confirmed view clears it. Side appearance
alone is not treated as neutral grip.

The models are the existing v11 quality/anatomy checkpoint and the existing
three-way neutral checkpoint. This is an ablation, not retraining or calibration.
The crop is the original 0.9-forearm wrist fallback resized to 128×128; it is not
a complete-hand detector. All automatic crops, including bad crops and empty
tracked frames, remain in the accounting.

## Controlled development recordings

The full source sequences were reviewed before predictions. They are the same
athlete and setting used earlier; the pull-up recording also has v11 fitting
exposure. These are development controls, not held-out accuracy measurements.

| Recording | First correct A | First correct B | Exercise-frame label coverage A / B | Marked full-cycle intervals with a correct label |
| --- | ---: | ---: | ---: | ---: |
| Pull-up, front | 12.02 s | 12.02 s | 51.8% / 51.8% | 2 / 2 |
| Chin-up, front | 16.54 s | 16.00 s | 6.3% / 10.4% | 1 / 1 |

Times are relative to the clip, not model compute latency. The active windows
begin at 4.8 s and 4.5 s, respectively. Both variants have zero wrong confirmed
decisions and zero visible labels in definite setup/release frames. B adds four
correct new image decisions across the two clips; these correlated frames are
not four independent successes.

`completeSetPassed` in the frozen experiment means at least one correct label
inside **every marked full-cycle window**, with no wrong confirmation or
setup/release label. It does not require continuous coverage, validate rep form,
or meet the shipping 80% attempt-summary requirement. Early partial excursions
remain in the exercise denominator but are not called full cycles. The late,
sparse chin-up result is still a material failure for useful capture.

## Separate recordings: no successful qualification

Six recordings were selected by source hash from fourteen previously unread
reserve groups before development inference. After the baseline passed, B and
its inputs/models were frozen before decoding those six sources. Source-only
scope and references were saved before hand inference. All six source groups
and recording hashes are disjoint from both RGB models' fitting and calibration
roles. No replacements or test-driven crop/label/threshold changes were made.

| Source suffix | Source-only scope/reference | A outcome | B outcome |
| --- | --- | --- | --- |
| EVO-6Z8j3Dk | Front, overhand; small source hands | Unknown | **Wrong underhand** |
| J5i4GcGt2oY | Stable rear prefix, overhand | Unknown | Unknown |
| HhiuKOZrLX0 | Oblique, gloves/wraps; reference grip uncertain | Unverifiable overhand acceptance | Same |
| ZLzPbmPVNv0 | Rotating image, distant hands; no confirmed view | Abstained | Abstained |
| c8QJc5EsThw | Ring instruction/reaching setup; no confirmed view | Abstained | Abstained |
| ncjx7j-GM0Q | Ring tuck hold, outside straight-bar scope | Abstained | Abstained |

Among the two sources with a usable front/rear segment and known grip, A yields
zero correct and two unknown video outcomes; B yields zero correct, one wrong
and one unknown. Neither passes any marked complete set. Across all six, the
remaining outcomes are one unverifiable acceptance and three unverifiable
abstentions. They do not earn correctness credit. The new test contains **no
verified chin-up or neutral reference**, so it cannot establish either class's
independent accuracy.

The front-view error is a surface-classification error with the view fixed
correctly. At 9.08 s, the right crop predicts dorsum 0.809 with readability
0.139. B confirms underhand after two agreeing image votes; the wrong label is
visible for four sampled exercise frames, one with fresh evidence. A abstains.
Removing readability is therefore not a justified general fix.

The rear-view failure precedes hand classification. All 21 retained native
samples report no acquired support reference. The clip starts mid-exercise;
its sampled arm geometry never satisfies the unchanged extended-arm start.
For example, the hand model correctly favors dorsum on the 1.40 s crops, but
those images are not exercise-eligible. This exposes a counter/acquisition and
sampling limitation, not evidence that the rear hand classifier always fails.

The rear source's camera rotates after 5.6 s. Its first 21 samples were selected
as a fixed-view segment **before** hand inference; 15 later samples remain
explicitly outside scope. All original 209 decoded frames are retained;
194 enter this fixed-view experiment. Four unsuitable sources remain in the
all-source denominator rather than disappearing as outliers.

## Lifecycle controls and verification

Across development and test there are 77 definite setup samples and 26 definite
release samples. Both variants show zero exercise admissions and zero visible
grip labels in those 103 samples. The shared replay also adds no observed
movements during those negative phases. There are only three separate-test
release samples, so this is sparse evidence rather than a deployment guarantee.
Uncertain boundary samples are reported separately.

All 362 evaluated source images, original timestamps/selected body poses and
493 exact automatic crops were independently reconstructed and checked. The
31 distinct protocol/candidate/pre-inference pins pass, including unchanged
models, policy and original baseline results. The original test result files
are preserved. A post-test accounting addendum guards an original reporting
edge case: `firstCorrectSeconds` matched unknown predictions to unknown
reference labels. The corrected accounting gives those sources no correct
time or correctness credit; predictions and original scores are unchanged.

Twenty-four targeted Python policy/context tests pass, including raw-confidence
preservation, view semantics, side-vs-neutral, duplicate image voting, conflicts,
setup/release and identity resets. Two authorized JVM replays execute the actual
shared counter, one for development and one for test. These are offline
replays of approximately four sampled source observations per second, not
full-frame-rate native or physical-phone performance tests.

Six new reserves are now exposed tests. The other eight of the fourteen new
groups remain unread; an exposure ledger records this without modifying
historical reserve manifests. Original media, labels, models, crops and logs
are retained. Cache cleanup is limited to the explicit reproducible-cache
allowlist.

## Evidence and continuation

- Machine summary: `Evaluation/fixtures/grip-controlled-baseline-v17.json`.
- Protocol, candidate freeze, both first-run results, source/crop sheets,
  post-test verification, exposure ledger and actual-pixel examples:
  `build/grip-controlled-v17/`.
- Experiment: `scripts/grip-controlled-v17.py` and
  `scripts/evaluate-grip-controlled-v17.py`.
- Reconstruction audit: `scripts/audit-grip-controlled-v17.py`.

The next useful capture is a genuinely separate upright front or rear recording
that begins with a visible extended-arm hang, contains clear hands and upper
body, and includes setup and release. Include both grips. Keep readability
while fixing acquisition/coverage on that bounded capture condition. Reuse
these now-exposed tests as regressions, and qualify any further candidate on
new recordings. Do not claim success by disabling a gate that rejected the
observed wrong hand prediction.
