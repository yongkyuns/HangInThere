# Hand-anatomy regression diagnosis — 2026-10-23

The next experiment should preserve the existing image model's useful anatomy
features, verify the ambiguous surface references, and isolate anatomy learning
from visibility/contact optimization. Another larger video intake is not yet
justified by the current failure evidence. Multitask gradient interference is a
hypothesis to test, not an established cause.

This step audits the already exposed v9 validation set and runs two frozen
training-scope controls. It changes no app policy, confidence threshold, original
label, existing model, final-test role or qualification requirement. The fourteen
new final reserve videos remain undecoded. It does not establish fresh accuracy.

## Direct pixel review

On the same 218 validation crops, 137 have stored readable anatomical labels.
The previous model and v9 candidate agree correctly on 79, both disagree with
the reference on 25, v9 recovers 12, and v9 loses 21 previously correct crops.
Thus the known-surface total correct changes from 100/137 to 91/137. Class-macro
recalls are separately reported; aggregate totals are not macro accuracy.

The review packet shows all 21 regressions, all 25 shared failures and all 12
recoveries against the actual source RGB and crop boxes. Source-group examples
were also inspected on shuffled crop-only sheets. Twelve regression references
remain supported on review; six have insufficient anatomical detail and three
are palm/edge or dorsal/edge boundary cases needing explicit adjudication.
These are diagnostic flags, not nine proven wrong labels. Original scores and
references remain intact. Pixel review notes saw disagreement sheets and are
not independent gold annotation.

Examples:

- `R059`/`R061`: only a blurred 27-pixel crop around a hand and red vertical bar;
  the existing readable-dorsum reference is too strong without adjudication.
- `R020`/`R022`: genuine palmar hand regions that v9 calls dorsal. Label uncertainty
  does not explain away this regression.
- `R168`/`R170`/`R172`/`R175`: recovered neutral-source hand-edge crops, now confused
  with dorsal/palm by v9. Existing edge classification was useful and regressed.
- `R149`/`R150`: palm plus edge/thenar around an angled handle. The annotation
  convention needs a consistent policy for mixed visible surfaces.
- `R062`: an inherited rear-view reference appears strongly oblique/side in the
  source. This remains explicitly flagged, not silently removed from scoring.

An additional model-independent sampling selected six training source groups
per surface in hash order, then the first crop hash per group: 18 samples.
Source and crop review flags mixed surfaces, blur or occlusion in six examples.
This small stratified review is not a prevalence estimate or a full label audit.
Its selection and exact input hashes are retained in `training-label-sample.json`.

## Controlled scope check

V9 removed 117 of 526 original training patches because their camera reference
was oblique: 10 palm, 20 dorsal, 26 edge and 61 unclear crops. The app can reject
a side camera while an offline anatomical hand model still learns from those
images; camera angle and visible hand surface are separate supervision tasks.
New v9 sources mostly filled **validation** coverage. They did not add gradients
from new training examples, and narrowing the cohort reduced training diversity.

The A/B control uses the untouched v2 recipe, one prespecified late-block variant,
seed 3104, three head epochs, up to thirty fine-tune epochs, patience eight, and
identical expanded v9 validation images/references. All labels, augmentation,
loss, sampler, initialization and calibration are fixed between A/B. Only the
original oblique training examples are retained or omitted. The protocol and
source/script hashes were frozen before fitting. With fixed epochs the larger
dataset also receives more gradient updates, so this is a cohort-admission
comparison, not an equal-step optimizer study. The two runs choose checkpoints
separately using validation loss. Neither is deployable.

| Model / controlled cohort | Palm | Dorsal | Edge | Macro |
| --- | ---: | ---: | ---: | ---: |
| Previous surface model, same expanded validation | 86.0% | 54.8% | 90.6% | 77.2% |
| V9 changed recipe, front/rear training | 74.4% | 56.5% | 75.0% | 68.6% |
| V2 recipe, front/rear training only (B) | 55.8% | 82.3% | 59.4% | 65.8% |
| V2 recipe, all original training views (A) | 46.5% | 85.5% | 81.3% | 71.1% |

Every model misses the unchanged 65% per-class qualification requirement on this
cohort. Restoring the discarded anatomy data helps edge recall under the control
recipe, but palm recognition collapses. The v9 importance-corrected objective
also changes the tradeoff; it is not a controlled attribution of all v9 failures
to data filtering. The current evidence does not justify claiming one root cause
or proceeding to grip/orientation fusion.

## Next bounded experiment

1. Freeze an explicit annotation rule for dominant palm, dorsal and edge views;
   use mixed/unclear flags where the crop cannot support one hard target. Review
   training and validation with the same rule. Keep old results and corrections
   separately bound; never relabel to agree with a classifier.
2. Start from the previous trained image weights rather than resetting the
   encoder to ImageNet. Compare surface-only adaptation with an identical
   warm-start shared-head control to isolate whether quality/contact gradients
   damage anatomy features. Keep the same reviewed corpus, optimizer budget,
   validation selection and confidence thresholds in both arms.
3. Qualify anatomy first. Then freeze its encoder and train quality/contact
   heads separately, with readable and unreadable/noncontact examples balanced.
   Final grip mapping remains front/rear only; neutral stays image-dependent.
4. Freeze a qualified model before reading a new final reserve. Failed models
   remain offline; rejecting difficult source frames cannot become an accuracy
   improvement by omission.

## Reproduction and storage

Commands use the existing `build/grip-training/venv/bin/python` and local
`TORCH_HOME=build/grip-training/torch-cache`.

- `scripts/audit-grip-regression-v10.py`: score frozen old/v9 models and generate
  source/crop sheets on the exposed validation inputs.
- `scripts/control-grip-training-scope-v10.py freeze`, then `allViews` and
  `supportedOnly`: execute the bound development controls in fresh directories.
- `scripts/summarize-grip-regression-v10.py`: verify model/freeze hashes and bind
  results, review flags and limits.

Artifacts and review sheets are in `build/grip-regression-v10/`. This step reuses
all source RGB and crops, downloads no video or initialization weights, and adds
about 28 MB of retained models/review evidence. Temporary bytecode/compiler caches
are inspected separately; original media, crops, labels, weights and receipts
are preserved. No phone install or Android/iOS app build was needed for this
research-only diagnostic.

Follow-up: [complete review and gradient-isolation results](GRIP_ANATOMY_ISOLATION_V11.md).
