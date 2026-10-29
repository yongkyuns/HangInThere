# RGB hand-patch grip prototype — 2026-10-21

> **2026-10-22 reference correction:** the weighted source `irFDiPohB8E` was
> mislabelled rear/underhand; it is front/overhand. Earlier claims of 55 failed
> chin-up patches included 45 incorrectly labelled pull-up patches. Those claims
> are withdrawn. See [the corrected context experiment](GRIP_CONTEXT_EXPERIMENT.md)
> and `public-grip-expansion-corrections-v2.json`. Original raw reports remain historical.

**Correction, 2026-10-22:** the old spotter-source underhand label and the
assisted-one-arm validation label were not valid binary references. The former
0/58 chin-up failure claim below is withdrawn; its recorded counts remain history.
The [new public-data expansion](GRIP_DATA_EXPANSION.md) corrects that error and
compares both models on six different, source-separated test videos. That new
evaluation still finds no improvement from this retraining configuration.

The requested pixel-based path is implemented and trained locally. The model
receives only a **128 × 128 RGB image of the hand and short forearm** and returns
two scores, ordered `[overhand, underhand]`. Landmarks locate the hand and select
its owner; landmark coordinates, depth, SDK handedness, pose features and person
count are **not classifier inputs**. This supersedes the landmark MLP as the
current development approach. Neither experimental model is enabled in the app.

The prototype retrieves the athlete's hands in the athlete-and-spotter recording,
but its learned underhand prediction fails on that held-out recording. This is
not a release model or evidence of reliable binary accuracy.

## Subject selection and patches

`GripPatchSubject` consumes the original upright, unmirrored primary 2D poses.
It selects a unique person whose visible wrists are raised above the shoulders,
then acquires stable wrists/shoulder span for three observations and 0.25 source
seconds. Another person is allowed. Two raised-hands candidates, overlapping
wrists, or a competing person's wrist inside a hand search region are explicit
unavailable reasons. Missing current joints never borrow a previous observation.
Source-clock reversal, a gap over 0.35 seconds, image/backend/revision changes,
and an athlete change require acquisition again. This is a conservative exercise
selector, not a general multi-person identity tracker. It currently needs both
visible wrists and shoulders to establish the athlete; one hidden wrist can
still prevent extraction even when a human can identify the subject.

The original people list is retained with the selected index. An isolated view
of that original person is used solely to call the existing pure-2D hand crop
planner. It does not change the workout counter's single-person policy.

`extract-grip-patches.py` runs the pinned hand detector on those search regions.
Only 2D detected wrist proximity associates a hand with its target; auxiliary
body depth and SDK chirality are unnecessary. Multiple candidate hands at one
target, ambiguous ownership, duplicated/overlapping detected hands, or a hand
extent below 16 original pixels are unavailable. A square crop at 1.5 times
the detected hand extent includes short forearm context, stays inside the image,
and is resized to 128 pixels without flipping or stretching its aspect ratio.
The selector and extractor do not consult a grip label.

Every row retains source video identity, exact presentation timestamp, original
frame SHA-256, original person index, search region, patch rectangle, patch
SHA-256 and failure reason. The frame bytes and pinned detector bundle are
verified before extraction. These are real extracted pixels, not generated
hands or crops chosen from a desired prediction.

## Labels and training

The [existing visual annotations](../../Evaluation/fixtures/public-grip-review-v1.json)
and their source splits are unchanged: five public overhand training recordings,
three public validation recordings, four public test recordings, plus two private
owner training recordings. Published exercise names are not grip labels. The
21 unresolved public recordings remain unresolved. Public athlete identities
remain unknown; only recording separation and known-owner separation are checked.

The first run used 346 training patches. Visual inspection found one open left
hand beside the bar in the owner's pull-up video (`frame-0017`), before that hand
was gripping. A source/image/patch-hash-bound review excludes that one training
patch. The final run uses **345 training patches: 259 overhand and 86 underhand**
across seven recordings. All 86 underhand patches come from one owner video.
No held-out image, label, source window or split was moved into training. The
first and reviewed runs use identical settings. These are repeatedly inspected
development holdouts, not a new blind benchmark or independent human patch labels.

The network is the [official Torchvision MobileNetV3-small](https://docs.pytorch.org/vision/master/models/generated/torchvision.models.mobilenet_v3_small.html)
ImageNet1K V1 feature extractor, with its original classifier removed. Frozen
features and global average pooling feed a trained 576-to-2 linear head:
**928,162 total parameters; 1,154 trainable parameters**. This is transfer learning
from pixels, not a hand landmark classifier or a CNN trained from scratch.
The training seed is 31, with 300 AdamW head steps, class-balanced cross entropy,
and four train-only affine/color variants per original training patch. There is
no validation/test augmentation, frame-random split, test-guided threshold tuning,
or camera-view input. The optional development abstention threshold is fixed at
0.80; the underlying model always produces two scores for an available patch.
Scores are uncalibrated, and two-class output does not identify neutral grips.

## Results

Every labelled-window frame contributes two expected hands, even if no patch
could be obtained. Counts are correlated frame samples, not independent trials.

| Reviewed RGB model | Expected hands | Available patches | Correct at 0.80 | Wrong at 0.80 | Unknown |
| --- | ---: | ---: | ---: | ---: | ---: |
| Public validation | 170 | 34 | 34 | 0 | 136 |
| Public test | 396 | 267 | 197 | 57 | 142 |
| Test overhand | 328 | 209 | 197 | 0 | 131 |
| Test underhand | 68 | 58 | 0 | 57 | 11 |

Without abstention, the available test patches give **208 correct / 59 wrong**.
The 142 unknown test outcomes are 129 missing patches plus 13 low-score outputs.
The previous expanded landmark model had 135 correct / 0 wrong / 261 unknown on
the same 396 expected hands. More available patches and fewer unknowns are **not
an accuracy improvement** when the new model misclassifies the underhand source.

In the yellow-shirt athlete / black-shirt spotter source, the old auxiliary-body
gate attempted no hand inference. The new selector chooses the athlete in 35/38
full-recording sampled frames. Inside the frozen label window, all 34 frames
contain two original people: 2 acquire the athlete and 32 authorize hand crops.
The hand stage yields **58/68 expected patches**, all belonging to the athlete in
the inspected examples. The image classifier calls all 58 patches overhand by
argmax, incorrectly; 57 pass the 0.80 threshold. Subject extraction and grip
recognition are therefore separate, measurable outcomes.

Validation supplies no usable underhand patches because that source has hidden
or overlapping hands. It cannot establish underhand calibration. The training
data lacks underhand variation across people and camera views. The current
failure is consistent with that missing coverage; it does not establish that
pixel classification is impossible. Palm/back-of-hand appearance changes with
viewpoint, so the next training collection must cover front, rear and side views
for both grips rather than adding more adjacent frames of the same owner clip.
New sources should get patch review and fresh held-out evaluation before any
model or view-context choice is promoted.

## Native verification

The export follows [Apple's image-input conversion contract](https://apple.github.io/coremltools/docs-guides/source/image-inputs.html):
RGB image input, scaling by 1/255, with ImageNet channel normalization embedded in
the network. The float32 Core ML package is **3,789,518 bytes**. Native Swift
loads the original 128-pixel PNG into a Core Video image buffer and evaluates
Core ML directly; an RGB byte comparison confirms channel order and orientation.

All **646 available reviewed training/validation/test patches** agree between
Torch and actual native CPU Core ML in argmax and 0.80 acceptance. Maximum score
difference is `6.56e-6`. This verifies conversion, not grip correctness. Native
Mac CPU warm median model inference is approximately 0.33 ms on this host; it
excludes decoding, body/hand detection and crop creation and is not iPhone timing.

A smaller float16 export was also measured and **failed parity**: maximum score
difference 0.0872, one argmax mismatch and two threshold mismatches. It is retained
as a rejected development artifact; float32 is the verified conversion.

Seven subject-selection tests pass on both JVM and Apple native, including a
spotter, reordered people, competing athletes/hands, missing observations,
overlap, invalid context and source discontinuity. Four Python tests cover split
leakage, changed pixels/foreign plans, source-bound visual exclusions and crop
geometry. The iPhone Kotlin target compiles. Production counter behavior and the
installed iPhone app remain unchanged.

## Local artifacts and reproduction

All images, private-derived learned weights and runtime artifacts remain ignored
under `build/grip-patches/`; none are bundled or uploaded. Key outputs:

- `images/patches.jsonl`, `images/extraction.json`, `selection-diagnostics.json`:
  source-bound RGB patches and explicit failure reasons.
- `review.jpg`, `examples/spotter-patches.png`, `visual-review.json`: visual review.
- `reviewed-model/report.json`, `training-receipt.json`, `predictions.jsonl`:
  all per-hand outcomes, source lists and training inputs.
- `reviewed-model/GripPatchClassifier-float32.mlpackage` and
  `native-float32-parity.json`: verified native image artifact and receipt.

The input rows join existing native `frames.jsonl` exports with their explicit
recording annotations and frame directory, then retain those fields unchanged.
The preparer creates a new file; preserve previous inputs for their receipts:

```sh
build/grip-training/venv/bin/python scripts/prepare-grip-patch-inputs.py \
  build/grip-patches/new-input.jsonl \
  --private-annotations build/grip-training/annotations.json

export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
GRIP_PATCH_INPUT="$PWD/build/grip-patches/new-input.jsonl" \
GRIP_PATCH_OUTPUT="$PWD/build/grip-patches/new-plans.jsonl" \
./gradlew -Pworkout.appleOnly=true :shared:jvmTest --rerun \
  --tests '*GripPatch*' --max-workers=2 --no-parallel

# The extractor creates a new output manifest; preserve previous runs.
build/grip-training/venv/bin/python scripts/extract-grip-patches.py \
  build/grip-patches/new-plans.jsonl build/grip-patches/new-images
TORCH_HOME="$PWD/build/grip-patches/torch-cache" \
build/grip-training/venv/bin/python scripts/train-grip-patches.py \
  build/grip-patches/new-images/patches.jsonl build/grip-patches/new-model \
  --patch-review build/grip-patches/visual-review.json
build/grip-training/venv/bin/python scripts/export-grip-patch-coreml.py \
  build/grip-patches/new-model --precision float32

xcrun swiftc Evaluation/GripPatchCoreMLCheck.swift \
  -o build/grip-patches/coreml-check -framework CoreML -framework CoreGraphics \
  -framework ImageIO -framework CoreVideo \
  -module-cache-path build/grip-patches/swift-cache
build/grip-patches/coreml-check \
  build/grip-patches/new-model/GripPatchClassifier-float32.mlpackage \
  build/grip-patches/new-model/predictions.jsonl \
  build/grip-patches/new-model/native-predictions.jsonl
build/grip-training/venv/bin/python scripts/check-grip-patch-native.py \
  build/grip-patches/new-model/predictions.jsonl \
  build/grip-patches/new-model/native-predictions.jsonl \
  build/grip-patches/new-model/native-parity.json
```

Pinned training/extraction versions are in `Evaluation/grip-learning-requirements.txt`.
The Python executable must remain inside the venv; resolving its symlink bypasses
the environment. Coremltools warns that Torch 2.9 is newer than its tested 2.7
range; the measured full native prediction comparison above verifies this export.
