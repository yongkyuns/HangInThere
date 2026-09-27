# First native Penn Action diagnostic

**Status: native processing executed; anatomical accuracy remains unqualified.**
No custom training, counting, dip evaluation or phone performance is established.

## Executed data, not a metadata-only inventory

[Private native run 36289611596](https://github.com/yongkyuns/HangInThere/actions/runs/36289611596)
at `367ee7997cc0bd349904a39a23e6852c7aa08c83` processed original sequences
1149–1154: **all 271 JPEG frames**, using the exact production Vision estimator
and batch image path. No native reference labels were passed to inference.
All frames have frame-index timebase; no invented FPS or video timestamps.

The deterministic first-six selection preceded inference. It is not a random,
held-out or six-independent-subject sample. Source/person independence remains
unresolved, and the clips stay `unassigned`. The original native annotations
supply 2,947 visible limb-point references across these frames, not rep-validity
or strict endpoint labels. No frame or sequence was dropped to improve scores.

The publisher archive download was 3,235,203,923 bytes. All 2,326 native annotation
records and frame-name/count correspondence were inspected, finding **199 pullup
sequences / 13,865 frames**. The observed download pin and inventory are in
[the archive snapshot](../fixtures/penn-archive-snapshot.json). The pin is not a
publisher-issued checksum or a licence grant.

## Intake and coordinate corrections

The README's `pull_ups` differs from the archive's actual `pullup`. Both exact
literals are accepted; raw action strings are preserved and fuzzy aliases rejected.

Native sequences 1153 and 1154 declare widths of 481 pixels; their original JPEGs
are 480 pixels wide. All 271 JPEG and six MAT hashes were checked. Representative
first/middle/last images and native-only overlays were inspected before inference.
The converter records exact, reviewed identity geometry resolutions for those
two clips: neither image pixels nor annotation coordinates are rescaled.

The publisher's `CreatePointLightDisplay.m` uses `sub2ind(dims,Y,X,T)` directly,
supporting the reviewed one-based display convention. The conversion subtracts
one from x/y. Origin, geometry and source-frame selection were frozen before
inference output was inspected. See [the importer contract](../IMPORTING.md).

These are compatibility/coordinate reviews, not expert reannotation of all joints.

## Directly observed detection coverage

Vision revision 1 ran with unchanged production settings; scoring confidence was
fixed at 0.3. Exactly one predicted person was present in the following frames:

| Native sequence | Original dimensions | Processed frames | Frames with one person | Frames with no person |
| --- | --- | ---: | ---: | ---: |
| 1149 | 480 x 270 | 39 | 39 | 0 |
| 1150 | 480 x 270 | 40 | 40 | 0 |
| 1151 | 480 x 270 | 38 | 38 | 0 |
| 1152 | 480 x 360 | 44 | 44 | 0 |
| 1153 | 480 x 270 | 38 | 38 | 0 |
| 1154 | 480 x 365 | 72 | 49 | 23 |

No frame contained multiple predicted people. This is detection availability,
not accuracy of every landmark or a replication of the full Penn benchmark.
The low-resolution doorway sequence 1154 is a concrete missing-observation case.

## Laterality: do not interpret raw associations as anatomical accuracy

Initial scoring used the publisher README's left/right joint names literally.
It produced unexpectedly large cross-body distances. A subsequent source-only
review with explicitly named native joints found the documented left chain on
the anatomical right side in representative rear/front views (particularly 1149
and 1151). The six sequences show a consistent bilateral naming mismatch with
Vision. The author has not confirmed a dataset-wide convention correction.

The original labels and default importer mapping are unchanged. The following
**post-hoc sensitivity experiment** exchanges all six left/right limb-name pairs
uniformly, for every frame in all six clips, while retaining coordinates,
visibility, confidence, dimensions and predictions. No per-frame/per-clip search
selects a lower-error mapping and no labels are fitted to model output.

This comparison motivates independent convention adjudication. Neither column
is a qualified anatomical-accuracy score or evidence of generalization. The
experiment was formulated after seeing this run's output, so the candidate
mapping needs a predeclared check on further independent native sequences.

| Sequence | Mean arm disagreement, documented names (px) | Mean arm disagreement, fixed pair-swap sensitivity (px) | Scored arm points / native-visible references under pair swap |
| --- | ---: | ---: | ---: |
| 1149 | 75.4 | 3.6 | 234 / 234 |
| 1150 | 29.9 | 18.4 | 226 / 240 |
| 1151 | 56.4 | 4.3 | 228 / 228 |
| 1152 | 87.7 | 4.1 | 264 / 264 |
| 1153 | 58.0 | 3.2 | 228 / 228 |
| 1154 | 29.8 | 15.9 | 242 / 392 |

These per-sequence pixel means combine shoulder/elbow/wrist errors, weighted by
measured point counts. Missing points reduce coverage rather than contribute zero
error. Do not pool variable-resolution clips into a misleading overall pixel score.

Even the exploratory fixed-swap association does not establish the intended
five-degree angle target. Mean image-plane elbow disagreement ranges from 4.7 to
15.6 degrees by clip/side; corresponding 95th-percentile errors range from 12.8 to
39.2 degrees. Partial detection and uncertain native labels still apply. A small
pixel error can correspond to a large angle error on a short projected segment.
No threshold was adjusted to pass this result.

## Integrity and reproducibility

The downloaded encrypted result archive was checksum-verified, then decrypted
only in the private review environment. Ordered image hashes, annotation hashes,
observation hashes, completion counts and source-code hashes were cross-checked
before the existing scorer ran. The earlier public-source approximate-label pilot
is separate and was not substituted for this native-data run.

| Record | SHA-256 |
| --- | --- |
| Original publisher archive | `e3e41bd99deb7b3beb9785f78b209de272bb0fa60ff4432ffa88118907a797de` |
| Frozen pre-inference review | `38b9a4c61069604cc26e77d77b6454cc1851b4ed60ce579650816074afc9b0cb` |
| Raw native inference report | `3e805a5606303160ce1a1dcd8cd830d2592d7d7e047f1722cd0a8e0267be391f` |
| Existing scorer source | `c6e43c618d61de5afb21635ba92085fde8bdea14517304320aa7080f4f232a33` |
| Encrypted result ZIP | `bba891823689910334f2aedab6b264c08593d282992eea3c408f7ee8c8c9bf4f` |

Execution used Xcode 26.3 / Swift 6.2.4 on native macOS arm64. The compiled source
hashes match the PR's production estimator, analysis and batch entry point.
These are not iPhone or simulator timing results.

To reproduce the naming sensitivity privately after preparing matching reviewed
references and native observations, call the existing scorer twice:

```python
import copy
from evaluation import score_pose

literal = score_pose(reference, observations, confidence=0.3)
sensitivity_reference = copy.deepcopy(reference)
for frame in sensitivity_reference['frames']:
    frame['points'] = {
        ('right' + name[4:] if name.startswith('left') else
         'left' + name[5:] if name.startswith('right') else name): point
        for name, point in frame['points'].items()
    }
sensitivity = score_pose(sensitivity_reference, observations, confidence=0.3)
# Retain both outputs. Never choose a mapping by the lower score.
```

Use the same materialized observation list for both calls, not a consumed generator.
The original frozen references remain untouched.

## Boundaries and next experiment

This is limited private research use of a publisher-offered dataset. No commercial
training, marketing or raw-data redistribution permission is claimed. Only these
non-media aggregate diagnostics are published. Readable source images, native
joint arrays, per-frame predictions and decryption keys are not committed or
public artifacts. Ephemeral encrypted transfer is not a permission grant.

The importer/intake suite has 73 passing local tests. The exact implementation
head `e4cba1af59bf9f480599aa2319b155d786400a09` also passed
[Dataset evaluation run 36289811506](https://github.com/yongkyuns/HangInThere/actions/runs/36289811506).
The original P0 hosted-simulator model-loading blocker remains separate; passing
host tests do not qualify it or establish a usable live workout counter.

Next: freeze and independently check native anatomical joint conventions on new
sequences, then compare Vision and MediaPipe on the same reviewed inputs without
changing labels, selection or thresholds between models. Extend beyond the first
six rather than treating this diagnostic subset as a release benchmark. Separate
parallel-bar-dip footage and endpoint annotations are still required.
