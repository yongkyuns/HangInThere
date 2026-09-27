# Vision / MediaPipe Heavy: public-source pilot and comparison audit

## Completed historical pilot

The first completed comparison is [workflow 36327727545](https://github.com/yongkyuns/HangInThere/actions/runs/36327727545)
on source head `b745431d7e10844dcf719752f213639b650d7b72`. Both models processed
the same 100 source PNGs: three intervals from one approved source video, not
three independent subjects. Nine frames have 46 frozen, approximate visible-joint
references established before the original Vision pilot. No references were
changed after inspecting MediaPipe.

The historical configuration requested one MediaPipe pose. These results are
integration evidence and descriptive disagreement measurements, **not model
selection or exercise-accuracy qualification**. The audit corrections below need
their own real-model CI run; the historical values are not silently relabelled as
results of corrected code.

| Interval | Frames | Vision measured/reference | Vision mean pixel disagreement | MediaPipe measured/reference | MediaPipe mean pixel disagreement |
| --- | ---: | ---: | ---: | ---: | ---: |
| Hang/setup | 30 | 18/18 | 7.42 | 18/18 | 5.50 |
| Upper position/descent | 30 | 16/16 | 5.91 | 16/16 | 6.29 |
| Hang/ascent/return | 40 | 12/12 | 8.03 | 11/12 | 13.42 |

Means are weighted by measured points in 960x540 images, not an average of joint
means. The last row has unequal measured support. No joint p95 values were
averaged. The head/upper extremities are cropped in part of the footage; absence
of wrist labels means the last interval has **no reference elbow-angle score**.
The other intervals provide only ten reference arm/frame angles in total.

The nine labelled source frames and the two model outputs were visually reviewed
without changing the approximate labels. Shoulder centres under clothing and
occlusion remain annotation uncertainties. Small differences must not be treated
as significant. Neither model wins consistently across these intervals/joints.
There is no parallel-bar-dip data, calibrated 3D reference, multiple independent
participants, trained model, rep counter, or strict chin-over-bar result here.

## Audit corrections

The host-only MediaPipe adapter now requests two poses and preserves all returned
people. The common scorer rejects frames with zero or multiple predictions; it
never uses the labels to select a person. Capping the detector at one pose had
hidden the opportunity to detect ambiguity. A two-pose cap is enough for this
rejection rule, not a claim to detect every bystander.

The earlier adapter also borrowed the remaining valid score when visibility or
presence was nonfinite. Policy v2 requires **both** scores to be finite in [0,1]
and uses their minimum; otherwise confidence is zero. It preserves raw scores
and does not clamp out-of-image coordinates. The numerical scorer threshold
remains 0.3 for both models, but their confidence scales are not calibrated to one
another. Model detector thresholds and reference labels are unchanged.

The runner itself enforces the model hash, rechecks input/model integrity, rejects
non-upright EXIF, writes per-clip completion records, preserves failure status,
and records source/runtime provenance. Report comparison rejects mismatched
source revisions, scorer code, thresholds, files, labels, frame counts and
reference support. Synthetic tests cover these contracts; only CI execution of
the real model qualifies the corrected adapter's inference path.

Linux CPU MediaPipe and macOS Vision timing measurements are not comparable:
different hardware/runtime, accelerators and measured sections were used. No
speedup or iPhone latency claim follows. The iOS app and its Vision default are
unchanged. Its separate simulator real-Vision gate remains failing until a
working runtime/device path is verified; host MediaPipe does not replace it.

## Reproducibility

Downloaded artifacts were checked against GitHub's archive digests before review:

- Vision/data artifact 10934726056: `bfe6b428bd244c41fc333467132750072be2c2270f0081be59c82b5956b85ce6`.
- MediaPipe artifact 10934422984: `be6a050c060ebe2eaf22c99ca8abdd8973b1d998be8450deedc545b2bd4c1823`.
- Source artifact 10934447727: `9abd5ab4b152ac333e99d419f1333bfd778e411af98191d4bcaa20879a83f953`.

The extracted source reproduces Git tree `59203adb4ea8fea8b6e166a92c14c6e4efea7919`.
Historical report SHA-256 values:

- Vision: `f2ad2b5d1ffbf3ddccb4f9737f8407f6d7c7065e8e843408de1295a556757fa6`.
- MediaPipe: `6d873e9cd1ed2a09b33c2b49d9b5ee96cdc7a3a357da306f292cec2284fc164d`.
- Comparison: `77119336e6009314c2898b54ef7be95f79ee6c749c0a034d84bacfcfac269de7`.

The official Heavy asset remains pinned to `pose_landmarker_heavy/float16/1`,
SHA-256 `64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b`.
References: [task options](https://ai.google.dev/edge/api/mediapipe/python/mp/tasks/vision/PoseLandmarkerOptions),
[score semantics](https://ai.google.dev/edge/api/mediapipe/python/mp/tasks/components/containers/NormalizedLandmark).

Corrected runs retain their own full reports/observations and qualification notes
on PR #3. The next model-selection evidence should be reviewed multi-person
pull-up and parallel-bar-dip footage, not more thresholds fitted to this pilot.
