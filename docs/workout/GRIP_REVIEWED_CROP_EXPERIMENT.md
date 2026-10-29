# Reviewed hand crops and torso orientation — 2026-10-22

The crop repair and manual reference review are implemented, and a new model was
fine-tuned and compared with the preceding head-context model on reserved videos.
**This model is not suitable for the app: it chose overhand for all 38 available
fresh test patches.** More correct overhand outputs do not establish a better
binary classifier. No new model was installed or enabled on the phone.

## Crop and reference changes

`scripts/prepare-reviewed-grip.py` reconstructs every revised crop from the
hash-verified original frame. It expands the previous SDK hand square by 1.7 and
shifts its center 0.2 old side lengths away from the elbow, toward hand/bar
contact. It clamps the square to the image and rejects excessive size. No grip
or viewpoint label participates in geometry. The old crop, coordinates and hash
remain attached to each new observation.

The seven previously exposed wrist-dominant failures in `fzPBhZBluR8` were
recropped and all fourteen resulting hand images inspected. The revised left
crops include the bar/contact area that was cut off; some fingers remain hidden
behind the bar in the original source. Enlarging a crop cannot reveal occluded
pixels. This is a crop diagnostic, not a new accuracy test.

All **376 fitting hand patches** were individually inspected on 42 sheets,
alongside their source frames and old crops. There are 327 approved direct grip
references and 49 exclusions. Examples of rejected inputs include a hanging red
strap mistaken for a hand, frames after a cut to back squats, gloves/straps that
hide grip contact, open fingerboard crimps, and unverified angled/parallel handles.
A clear athlete with a spotter or background people remains eligible.

The revised target is the directly reviewed binary grip. It does **not** derive a
palm/dorsum target from exercise name and torso direction. Review notes may
describe visible surfaces, but those notes do not become training features or
auxiliary targets. Reference exclusions preserve inference pixels: a gold review
cannot silently remove an input before the test model sees it.

The fitting inventory covers 26 training and four validation recordings. Of 262
approved training patches, 16 lack automatic torso context, leaving **246 training
patches: 139 overhand and 107 underhand**. All 65 validation patches have context:
35 overhand and 30 underhand. Inference uses automatic geometry; no test hand was
manually localized. These are agent visual references, not independent gold
adjudication.

## Orientation and training

The new context image contains the selected athlete's torso, excluding the head:
its square spans two shoulder widths and starts 0.08 shoulder widths above the
shoulders. A separate MobileNetV3-small learns front/rear/oblique orientation.
Positive face evidence may strengthen front only when the torso already chooses
front. Face absence supplies no rear vote. Changing face cues cannot reverse the
torso's selected direction. This guards the fusion rule; it does not guarantee
the torso CNN itself is accurate.

Torso training precedes grip training. Its weights remain exactly frozen during
grip fitting, and orientation is detached from the grip loss. The hand CNN and a
small two-output head learn grip from the expanded RGB patch, conditioned on
torso probabilities. There is no fixed appearance-to-grip reversal rule. Missing
context returns uniform scores instead of a forced direction or grip.

Training uses ImageNet initialization, source/class-balanced sampling, mild color
and affine augmentation, fixed BatchNorm statistics, head warm-up and hand CNN
fine-tuning. It selects checkpoints and temperature using validation only, with
the existing 0.8 acceptance threshold. The selected hand checkpoint changes 138
CNN feature parameter tensors. Temperature is 2.0; no test threshold was tuned.
The model has 2,149,829 parameters; its local TorchScript artifact is 9,460,655 bytes.

Validation already warned against deployment: overhand 32/35, underhand 7/30.
Torso validation is also weak: front 1/6 and rear 25/28. The selected torso
checkpoint predates successful feature fine-tuning. This run retains that
validation-selected checkpoint rather than selecting weights on test results.

## Separate test videos

Before revised fitting, 60 unused Kinetics archive members were reserved by
SHA-256 ordering, starting at offset 160. They share no recording hash or known
YouTube source group with either model's fitting sources. The new model was
frozen **before any of these source review images were opened**.

All 60 source candidates were reviewed with predictions hidden. Twenty-three
had a supported binary bar-grip reference: 18 overhand and five underhand. The
other 37 remain in the inventory with reasons, including rings, neutral handles,
rows, other activities, clipped hands and unverified grip contact. The inventory
does not promote the public action label “pull ups” into a binary grip label.

The prespecified sample times are 2, 4, 6 and 8 seconds. Subject acquisition runs
on the complete native four-Hz source sequence **before** selecting those frames.
Selection uses source time, not hand availability or classifier score. Fifteen
requested observations fall beyond short source endings; they are recorded, not
replaced by duplicate last frames. Seventy-seven source frames remain.

All 77 frames and all 38 automatically available test hand crops were reviewed
before inference. Activity windows exclude seven sampled setup, release or outro
frames without deleting them from the inventory. A front-to-rear camera cut has
explicit per-frame view references. The binary scoring denominator is **140
expected hands**, including missing detections. No reference review suppresses
a model input. All 38 available crops lie inside reviewed grip windows.

The comparison preserves each model's input contract: the preceding model gets
its original tight hand crop plus shoulder-anchored head crop; the revised model
gets the expanded hand crop plus torso-only image. Both receive the same actual
selected-athlete face detector evidence. Both use the same frame/side references
and threshold. This compares the complete revisions, not an isolated crop ablation.

| Fresh test measure | Previous head model | Reviewed crop/torso model |
| --- | ---: | ---: |
| Overhand raw correct | 30/33 | 33/33 |
| Underhand raw correct | 0/5 | 0/5 |
| Raw class-balanced accuracy | 45.5% | 50.0% |
| Correct among accepted predictions | 27/34 (79.4%) | 12/16 (75.0%) |
| Confident wrong predictions | 7 | 4 |
| Unknown, including missing inputs | 106/140 | 124/140 |
| Available hand crops | 38/140 (27.1%) | 38/140 (27.1%) |

Only nine of the 23 eligible videos produce any hand crop. Fourteen zero-crop
videos remain in coverage. Just one of five underhand videos produces crops;
the five underhand patches are correlated observations from that one video, not
five independent athletes. This is too little underhand coverage for a robust
generalization estimate. The apparent overall raw increase, 30/38 to 33/38, is
consistent with always choosing the majority class.

On the 23 frames with both context and an available hand score, orientation
accuracy also regresses: 21/23 to 13/23. Fixing the known head-turn mechanism has
not established better general orientation accuracy.

## Failure attribution and head turns

All five fresh underhand failures have a correct front-view argmax in both
models. A post-freeze diagnostic supplies perfect front orientation to the new
grip head while keeping hand pixels and weights fixed. **All five remain wrong.**
These failures therefore cannot be repaired just by improving face/orientation
inference. The saved sheet shows every failed crop, torso input and score.

The usable underhand fitting set contains only three videos: owner-chinup (86
patches), `d8RA0_YBltQ` (20), and `ZyBUAy8auRY` (one). Sampling balances source
contributions; repeated frames still cannot provide independent visual diversity.
The sole underhand validation video is rear-facing. The dataset lacks a useful
independent front-underhand validation cohort. More independent reviewed chin-up
sources and better automatic subject/hand coverage are the next priorities.

Separately, the already exposed `irFDiPohB8E` head-turn regression was replayed
as a **development diagnostic**. Its 29 previously failing hand observations
from 15 frames in one video now all
choose front torso (previously 0/29), and 28/29 choose the correct raw grip.
Face evidence toggled absent/present produces zero direction reversals. These
29 selected prior failures are excluded from fresh accuracy claims; they are not
29 independent frames or a representative test population.

## Frozen references and local artifacts

- Model SHA-256: `6285b77e15070b590ebc656a953d9414e6aa30f107988887f1dafd507c0e4dcd`.
- Previous model: `0a32afafa8bcd4f834b7f4723d919c62cb4c704bdb09f076a090e0b4f5a7645f`.
- Fitting review: `Evaluation/fixtures/grip-reviewed-fitting-v1.json`.
- Pre-fitting reservation: `Evaluation/fixtures/grip-reviewed-test-reserve-v1.json`.
- Complete source review: `Evaluation/fixtures/grip-reviewed-test-source-labels-v1.json`.
- Every test crop reference: `Evaluation/fixtures/grip-reviewed-test-patches-v1.json`.
- Prior crop diagnostic: `Evaluation/fixtures/grip-reviewed-crop-coverage-v1.json`.
- Weights and exact trainer snapshots: `build/grip-reviewed/model/`.
- Fresh metrics/predictions: `build/grip-reviewed/fresh/comparison/`.
- All five failures: `build/grip-reviewed/audit/chinup-failures.png`.
- Head-turn examples: `build/grip-reviewed/audit/head-turn-diagnostic.png`.
- Compact crop/failure examples: `build/grip-reviewed/audit/crop-and-test-examples.png`.

Media, private owner pixels, weights and generated sheets remain local/ignored.
Fixtures store source identity, timestamps, coordinates, hashes and review notes.
The model was not retuned after fresh test inspection. These sources are now
development-exposed and must not be advertised as untouched in a later run.

## Reproduction and checks

Use the local `build/grip-training/venv/bin/python` executable without resolving
its symlink; cached PyTorch weights are under `build/grip-patches/torch-cache`.
Use new output directories; completed fits/evaluations refuse overwrites.

1. `prepare-reviewed-grip.py` prepares the fitting review; `apply-grip-patch-review.py`
   joins the immutable fitting fixture, without changing roles or crop pixels.
2. `train-reviewed-grip.py INPUT OUTPUT --review FITTING_REVIEW --test-reserve RESERVE`
   rejects test rows before model construction, verifies review identity and
   reserved source separation, and freezes weights and trainer snapshots.
3. `review-grip-test-sources.py RESERVE MODEL OUTPUT` verifies the frozen receipt
   before producing source review images. The source-label fixture records the
   subsequent blind review and complete exclusions.
4. Prepare full native frame exports with `prepare-public-grip-expansion.py`, then
   run `GripPatchDatasetTests` with `GRIP_PATCH_INPUT`/`GRIP_PATCH_OUTPUT` to plan
   the entire sequence. `select-reviewed-grip-test.py` selects fixed times.
5. Run automatic `extract-grip-patches.py`, `prepare-grip-context.py`, native
   `GripContextFaces`, and `prepare-reviewed-grip.py` without training selection.
6. `apply-grip-test-windows.py` joins the final blind activity-window review;
   `apply-grip-patch-review.py` joins all per-patch test reviews. Window replay
   exactly reproduces the manifest hash bound by the test patch fixture.
7. `evaluate-reviewed-grip.py MANIFEST TEST_REVIEW MODEL PREVIOUS_MODEL RESERVE OUTPUT`
   checks video separation, frozen weights and references before comparing.
8. `audit-reviewed-grip.py` creates post-freeze oracle-view and prior-regression
   diagnostics; it neither fits weights nor changes test accuracy.

The grip Python suite passes **42 tests**, including crop coverage, no face-driven
direction reversal, detached torso gradients, missing-context behavior, test-data
rejection, reference exclusion preserving input pixels, and short-source sampling.
The JVM grip tests and full-sequence subject-plan export pass. No Core ML or
physical-device qualification is claimed for this failed research candidate.

Final reconstruction verifies all **414 hand crops and 235 torso crops** against
their original source pixels, exactly. Eager and frozen TorchScript inference
match with maximum difference zero on all 38 fresh hand inputs. The model and
trainer hashes still match the training receipt. These checks are saved in
`build/grip-reviewed/verification.json`; evaluator/preparation scripts and
fixtures are snapshotted under `build/grip-reviewed/provenance/`. They verify
reproducibility and identity, not classification accuracy.
