# Public grip expansion — 2026-10-21

> **2026-10-22 reference correction:** the weighted source `irFDiPohB8E` was
> mislabelled rear/underhand; it is front/overhand. Earlier claims of 55 failed
> chin-up patches included 45 incorrectly labelled pull-up patches. Those claims
> are withdrawn. See [the corrected context experiment](GRIP_CONTEXT_EXPERIMENT.md)
> and `public-grip-expansion-corrections-v2.json`. Original raw reports remain historical.

**Historical labels corrected 2026-10-22:** the nominal public underhand sources
were a neutral/hammer demonstration and an assisted one-arm variation. Their
underhand references are withdrawn. See the correction fixture and
[new source-separated evaluation](GRIP_DATA_EXPANSION.md); the old underhand
unavailability counts below do not establish binary underhand performance.

This report covers the earlier landmark MLP. The subsequent
[RGB hand-patch experiment](IMAGE_PATCH_GRIP_CLASSIFIER.md) uses the same frozen
source splits, selects an athlete with a spotter, and reports its image-model
misclassifications separately from unavailable patches.

The 33 pinned original public videos have been restored locally and verified
against the existing byte counts and SHA-256 values. The published annotations
cover exercise/count/timing. New grip labels come from a separate visual review;
they were not inferred from the published `pull_up` name or classifier outputs.

All 33 clips were reviewed at three points inside their published exercise
windows. Twelve received provisional bilateral overhand/underhand labels. The
other 21 remain unresolved or unsupported because hands are small, cropped,
covered, overlapping, on rings/straps, using opposing grips, or not clearly
shown in the sampled segment. They remain in the inventory. The labels are an
agent's visual development annotations, **not independently adjudicated gold
labels**. No physical grip certainty or strict-form verdict is implied.

`Evaluation/fixtures/public-grip-review-v1.json` freezes the media hashes,
review-image hashes, label evidence, exclusions, windows and source splits before
new feature extraction or classifier scoring. The new grip split is separate
from—and retains—the datasets' original published partition. Athlete identities
are unknown and stored as null; no person-independent holdout is asserted.

## Training and evaluation

- Public training: five overhand recordings selected before inference. Four
  provide 127 usable hand samples; one provides none.
- Private training: the existing two owner recordings provide 179 samples,
  including the underhand class. The prior overhand label remains provisional.
- The expanded model trains on **306 samples** across six contributing videos.
  No public validation or test recording enters training.
- Public validation: three recordings, including one underhand source.
- Public test: four recordings, including one underhand source. All 198 sampled
  frames inside the annotated windows contribute **396 expected hand labels**.
  Missing features remain unknown in this denominator.

The model architecture, 0.90 development threshold, feature extractor, hand
identity checks and counting policies were unchanged. There was one expanded
training run, with deterministic reproduction; no test-guided threshold or
hyperparameter tuning was performed.

| Method on the frozen public test split | Correct | Wrong | Unknown | Total hands |
| --- | ---: | ---: | ---: | ---: |
| Existing geometric classifier | 113 | 3 | 280 | 396 |
| Earlier private-only learned prototype | 92 | 3 | 301 | 396 |
| Public-expanded learned prototype | **135** | **0** | **261** | **396** |

This is a useful overhand improvement on these provisional labels. It is **not
reliable binary classification**: all 135 correct predictions are overhand, and
all 68 underhand test observations are unknown. Reporting only accuracy among
accepted predictions would hide that failure. The expanded model's overall
prediction coverage is 34.1%; correct over all expected hands is also 34.1%.
Validation returns 27 correct, zero wrong and 143 unknown of 170 expected hands;
its underhand observations likewise have no usable classifier features.

## Where underhand stops

The underhand test clip contains an athlete and spotter. Native primary body
inference reports two people in all 34 labelled sampled frames, so the existing
single-athlete body/hand preflight issues no hand crop requests. No learned
classifier gets a chance to label either hand.

The side-view underhand validation clip has 18 labelled sampled frames. Shared
preflight issues no crop requests in 17. The remaining frame produces two raw
candidate hands, but no selected identity-valid hand survives into features.
This is upstream availability/association failure, not evidence that changing
the learned classifier's weights or threshold would solve it.

The result motivates evaluating a post-detection classifier that can operate
without the current auxiliary 3D-body requirement, with explicit identity/view
handling and suitable negatives. That path is not implemented or enabled here.
More label-adjudicated underhand sources are also needed to evaluate both classes
when hand inference actually succeeds. No original qualification gate is waived.

## Verification and artifacts

All 12 native exports match their original decoded frame counts, every sampled
timestamp matches the public source-clock inventory, and all exported image
hashes verify. Frozen annotation hashes remain unchanged after scoring.

The new trained weights agree through the actual Apple framework and JVM on
**934** full-sampled-recording hand decisions; maximum score difference is below
`1.2e-16`. Malformed-model rejection passes. Ten Python data-integrity/training/
scoring tests pass, including source leakage rejection and retaining unavailable
hands in the denominator. Training reproduces identical model bytes.

The local evidence is under `build/public-grip-training/`: `holdout-report.json`,
`source-integrity.json`, `underhand-diagnostics.json`, `native-parity.json`,
training/crop/feature logs and model/data hashes. Original media is under ignored
`build/public-grip-inputs/` with the pinned download cache under ignored
`Data/external/public-workout-pilot/`. Neither media nor learned weights are
committed, uploaded or bundled. The phone app continues using its prior policy.

Reproduction tools are `extract-public-grip.py`,
`prepare-binary-grip-training.py`, `train-binary-grip.py` and
`evaluate-binary-grip-holdout.py`. The extraction data bridge uses fresh
`:shared:jvmTest --rerun --tests '*BinaryGripLearningPipelineTests'` executions.
The trainer permits null subject IDs only for train-only development fitting;
its standard validation mode still requires identified, disjoint people. This
separate scorer explicitly verifies source-recording separation without
inventing athlete identities.
