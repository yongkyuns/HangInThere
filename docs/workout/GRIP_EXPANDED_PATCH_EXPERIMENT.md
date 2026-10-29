# Expanded hand training and missing-input audit — 2026-10-22

The expanded data, detector audit, retraining and separate-video comparison are
complete. **The new classifier is worse overall and is not suitable for the app.**
On identical reviewed fresh-test hand images, its raw chin-up choices improve
from 0/7 to 2/7, while pull-up choices regress from 60/61 to 51/61. None of the
chin-up predictions reach the unchanged 0.8 acceptance threshold. No model or
detector change from this experiment was enabled on the phone.

The hand detector does improve coverage: 12 additional usable crops, no lost
original crops, and two additional open-hand crops that remain failures. Correct
torso orientation alone does not solve the remaining hand classification errors.

## Sources and completed reference review

All media came from the locally cached public Kinetics-700 archive
`Data/external/grip-expansion-kinetics/k700_train_454.tar.gz`. Its SHA-256 is
`5b082c056828aeb5e3e4e8e7a3abe0262e7cbadf1b8d97e6cef89d274573f9d9`.
The action label “pull ups” is not used as a binary grip label.

Before training, another 80 archive members were reserved by member-name hash
order, starting at offset 220. Training candidates came from a separate 240-member
pool starting at offset 300. All 240 source-review sequences were inspected.
Eighty candidates received fixed train/validation roles before automatic hand
extraction, stratified by provisional grip and viewpoint. Actual hand inspection
subsequently corrected twelve source grip references, excluded ambiguous inputs
and reviewed camera cuts, setup/release frames, torso crops and subject identity.
Earlier exposed test videos stayed outside fitting.

All 128 newly automatic hand crops and 59 training-only crop proposals were
visually inspected alongside their source frames. Twenty-six proposals were
accepted; the other 33 remain rejected references. Proposals never enter
validation or test. Reference exclusions preserve inference pixels, so an invalid
crop cannot disappear from a test simply because its label was reviewed.

The combined fitting set has **381 accepted training patches** (223 overhand,
158 underhand) and **80 validation patches** (40 per class). Chin-up training
now spans 16 recordings, including **12 newly contributing recordings**. The
fitting manifest contains all 563 hand-review records, including 102 exclusions.
These are completed agent visual reviews, not independent human gold labels.

| Training view | Overhand patches | Underhand patches |
| --- | ---: | ---: |
| Front | 101 | 110 |
| Rear | 116 | 36 |
| Oblique | 6 | 12 |

This is still a small, uneven set of independent recording conditions. Thirty
of the forty validation chin-up patches come from one rear-view recording.
Source/class weighting limits its influence on checkpoint selection, but many
nearby frames are not many independent examples.

References and local review artifacts:

- `Evaluation/fixtures/grip-expanded-source-review-v2.json`: all 240 candidates,
  original roles and exclusion reasons.
- `Evaluation/fixtures/grip-expanded-fitting-v2.json`: completed frame/hand
  labels, corrections, boxes, source identity and pixel hashes.
- `build/grip-expanded/amended-audit/new-chinup-training-1.png` and
  `new-chinup-training-2.png`: one actual accepted hand input from each of the
  twelve new chin-up training recordings.

## Detector audit and research policy

The original planner needed two strong raised wrists before trying either hand.
On the previously exposed full-source audit, 207 otherwise plausible failures
had one low-confidence wrist. Both hands therefore disappeared even when one
was usable. Acquisition resets, small projected shoulder spans, forearm-only
crops and incorrect subject selection were additional causes.

`GripPatchSubjectV2` is an explicit offline research policy; the existing default
planner and app bridge keep their previous behavior. V2 runs the original
planner and preserves an accepted original plan. Its fallback can request an
independently observed wrist, uses elbow support for weak wrists, scales hand
separation to projected shoulder span and retains source-time acquisition and
subject continuity. It rejects competing athletes and nearby other-person hands.
It does not invent the missing second wrist or use classifier predictions to
choose a person/crop.

Reviewing all seven initial additions on exposed development videos caught a
spotter's hand. The final fallback adds a positive head-position guard when a
head landmark is observed: hands near a standing observer's face cannot qualify
as overhead grips. Missing face landmarks do not require or imply a rear view.
This removed the spotter regression and left six correct additions: **38 to 44**
crops, none lost. The final policy was frozen before the new test was opened.

The final detector comparison on the fresh fixed frames is **65 to 79** total
automatic crops. All 65 shared crops reconstruct to identical original pixels.
Twelve additions are valid gripping hands; two are open/releasing hands. During
reviewed active grip windows, valid inputs rise from **56 to 68 of 198 expected
hands**. Fourteen sampled binary-reference videos still have no valid hand crop.
The detector remains a coverage improvement with known false-positive limits.

## Training and torso behavior

The model retains separate 128px RGB hand and torso inputs, with a lightweight
MobileNetV3-small backbone and a two-class conditional hand head. Torso-only
geometry excludes the head. Positive face evidence can support an agreeing
front-torso choice; changing face evidence cannot reverse the torso's selected
direction. Missing automatic context returns uniform scores.

Torso training changes 138 CNN feature parameter tensors. During hand training
the torso stays frozen. The revised hand stage uses the reviewed orientation
as its training condition, with smoothing and occasional orientation dropout;
normal inference always uses predicted orientation. No palm/dorsum labels are
derived from exercise names.

Both head warm-up and hand CNN fine-tuning were run. **Validation selected the
epoch-one hand-head checkpoint, so the selected hand CNN features remain at
their initialization.** Later hand fine-tuning epochs had worse validation loss;
they were not substituted after seeing test results. The torso checkpoint is
epoch ten. Temperature 1.5 comes from predicted-view validation loss, with the
existing 0.8 acceptance threshold unchanged.

Validation raw results are 33/40 overhand and 23/40 underhand with predicted
orientation; supplied correct orientation gives 37/40 and 23/40. These results
did not transfer reliably to the fresh video test.

The frozen model has 2,149,829 parameters and SHA-256
`4c0f4f766d1d4db1c8d8fb8446e748a3f9e4ea4dcdc0a7ca556855ece86af6b9`.
The preceding reviewed model is
`6285b77e15070b590ebc656a953d9414e6aa30f107988887f1dafd507c0e4dcd`.

The exposed head-turn regression remains fixed: both models keep front in all
29 observations from 15 unique source frames, with zero face-toggle direction
flips. The new model also chooses the correct grip on all 29. This is a
development regression check, not additional fresh accuracy evidence.

## Fresh test, including a disclosed reference correction

Model, detector, crop scripts and reserve hashes are pinned in
`build/grip-expanded/pre-test-freeze.json`, before test source frames were opened.
All 80 reserved source sequences were reviewed. Thirty-five supported candidate
videos proceeded to automatic extraction; one ended before every fixed sample
time, leaving 34 sampled videos and 116 source frames. The 24 unavailable
requested times are logged rather than replaced with repeated final frames.
Full native four-Hz source sequences drive acquisition before selecting fixed
2/4/6/8-second samples. No test hand is manually localized.

There is no recording-hash or known YouTube-source-group overlap with either
model's fitting sources. This is a video-group holdout, not a proven athlete
identity holdout. After actual crop review, 28 sampled videos are overhand and
six underhand; usable hand crops come from seventeen and three, respectively.

The first blinded reference mistakenly called the red-shirt
`MAWzre6_2jQ` video underhand. A later enlarged full-frame inspection clearly
shows pronated grips and an oblique rear view. That correction occurred **after
the first score**, so both references and both results are preserved:

- Original reference/result: `grip-expanded-test-patches-v2.json` and
  `build/grip-expanded/test-comparison/` (3/11 chin-up, 48/57 pull-up raw choices).
- Amended reference/result: `grip-expanded-test-patches-v3.json` and
  `build/grip-expanded/test-amended-comparison/` (2/7 and 51/61).

The amended review records its timing, prior review hash and exact reason. The
model, crop pixels, detector and thresholds did not change. This correction does
not turn the experiment into a successful result. It demonstrates that agent
manual labeling also needs scrutiny.

## Identical-input comparison and error attribution

The following compares both models on the same 68 valid actual hand inputs from
the revised detector. Raw choices are argmax decisions, even below acceptance.

| Metric | Previous model | Expanded model |
| --- | ---: | ---: |
| Pull-up raw correct | 60/61 | 51/61 |
| Chin-up raw correct | 0/7 | 2/7 |
| Pull-up with correct orientation supplied | 57/61 | 53/61 |
| Chin-up with correct orientation supplied | 2/7 | 4/7 |
| Correct accepted labels at 0.8, valid hands | 25/68 | 4/68 |
| Accepted chin-up labels | 0/7 | 0/7 |

Supplying correct orientation diagnoses the hand component; it is never an
inference input or headline pipeline claim. On the five front-view chin-up
patches, the new hand component gets four correct with correct orientation.
Both rear-view chin-up patches still fail even with correct orientation. The
hand classifier therefore has a real rear-view problem, while orientation also
causes front-view errors. Across reviewed usable torso frames, fresh orientation
accuracy regresses from **34/46 to 30/46**.

Across **198 expected hands**, including missing and invalid inputs:

| Complete pipeline result | Previous detector/model | New detector/model |
| --- | ---: | ---: |
| Available automatic crops | 61 | 74 |
| Correct accepted labels | 18 | 4 |
| Wrong accepted labels, including wrong-target crops | 1 | 1 |
| Unknown | 179 | 193 |

There are six invalid target/contact crops among the new active-window inputs;
they are counted as failures, even if a model guesses the source's grip name.
The new model accepts one invalid crop. Manually excluded or inactive reference
inputs still run through inference and remain in the separate quality inventory.
No post-test fitting or threshold adjustment was done.

All seven fresh chin-up hand images and all new raw overhand failures are saved
with actual source frames, hand/torso RGB and both models' scores in:

- `build/grip-expanded/amended-audit/test-chinup-1.png`
- `build/grip-expanded/amended-audit/test-chinup-2.png`
- `build/grip-expanded/amended-audit/test-overhand-regressions.png`
- `build/grip-expanded/amended-audit/test-invalid-crops.png`

The practical next experiment should isolate rear-view hand learning and verify
labels independently before another fit. Better training loss or more frames
from one recording will not establish generalization. Automatic input coverage
also remains too low, especially at the top of close-grip chin-ups. This newly
exposed reserve cannot serve as a fresh final test for that next experiment.

## Reproduction and verification

The executable stages are source intake/review; fixed role/sample selection;
full native pose export; `GRIP_PATCH_POLICY=1/2` planner export; original and v2
hand SDK extraction; context/actual face extraction; source-bound RGB recropping;
reference assembly; `train-expanded-grip.py`; `evaluate-expanded-grip.py`; and
`audit-expanded-grip.py`. All raw manifests, source clocks, review notes,
training histories and receipts are retained under `build/grip-expanded/`.

The frozen model can be compared again without training using:

```sh
build/grip-training/venv/bin/python scripts/evaluate-expanded-grip.py \
  build/grip-expanded/test-amended.jsonl \
  build/grip-expanded/test-corrected-v1/rows.jsonl \
  Evaluation/fixtures/grip-expanded-test-patches-v3.json \
  build/grip-expanded/model build/grip-reviewed/model \
  Evaluation/fixtures/grip-expanded-test-reserve-v2.json \
  build/grip-expanded/reproduction-comparison
```

Verification passed: 47 Python grip tests; 14 JVM planner tests including
unilateral evidence, competing people, clock/reset handling, old-input
preservation and the spotter/head regression; exact reconstruction of all 707
hand crops and 553 torso crops across fitting and both test pipelines; all frozen
bindings unchanged; and TorchScript/eager scores within 2.81e-6. Image and model
artifacts remain local. This experiment does not qualify iPhone inference,
sustained runtime cost or workout-level classification.
