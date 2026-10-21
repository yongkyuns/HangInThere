# Estimated body motion screen — 2026-10-18

The production movement-accuracy gate remains **1/26 matched, 25 missed dips,
zero extras**, over all 1,866 original frames. The experiments below do not fix
that gate and are not enabled as counters. PR #29 remains draft.

## Findings

A native full-body-rectangle/crop screen completed the 180 pull-up, 789 rear-dip
and 597 side-dip frames. Both the combined process and a fresh controlled-only
process stopped after frame 144 with exit 138. Those partial controlled results
are excluded from complete-source claims. Matching original body observations
to independently associated rectangles improves foreground availability; a
fixed, uniformly spaced visual audit confirms some correct athlete matches.
It does not recover the missing opposite arm. Cropped inference does not improve
the required bilateral availability and remains outside the app.

A projective background-registration screen passes ten authored SIFT/homography
falsifications, including conflicting background planes. Only 14 of the 300
controlled-video frames qualify against its fixed anchor. That coverage cannot
support a workout counter. Neither its availability limits nor the original
accuracy labels were relaxed.

An initial estimated-world cycle screen matched seven of nine controlled-video
dips but also counted an authored biceps curl. Review also exposed an unstable
starting dwell, partial-rebound endpoint drift, ignored opposite-arm evidence,
and a regressed-time fence. That candidate was rejected.

A revised, development-only screen requires bounded starting geometry, actual
upper-arm motion relative to the torso, source-time dwell, bounded skeletal
scale, available opposite-arm consistency and an observed return. Shallow
rebounds cannot move its acquired endpoint; an unfinished return supplies no
completed event. Its 28 authored controls pass. Applied causally to all 1,686
original dip frames with the pinned Heavy model through Python CPU VIDEO
inference, it yields:

| Clip | Original reference events | Left-side matches / misses / extras | Right-side matches / misses / extras |
| --- | ---: | ---: | ---: |
| Rear dip | 10 | 0 / 10 / 0 | 0 / 10 / 0 |
| Side dip | 6 | 0 / 6 / 0 | 0 / 6 / 0 |
| Controlled dip | 9 | 5 / 4 / 0 | 0 / 9 / 0 |

Both sides are retained; no side is chosen from its scored count. The original
references, event windows and whole-source coverage are unchanged. These are
**Python model development results**, not native SDK, automatic apparatus,
production rep-count, anatomical extension, load or form qualification. In
particular, a supported squat can share the same observed upper-body geometry.
Common rigid-transform invariance of supplied 3D points does not prove that a
monocular model preserves those points when the real camera moves.

## Shared measurement foundation

The native Android estimator now preserves its already-produced, same-request
world landmarks at the matching person index. A missing person match or malformed
33-point result leaves depth unavailable. This adds mapping and payload work,
without another inference pass. The primary iOS Vision 2D provider continues to
supply no depth; separately scheduled grip observations cannot fill it.

`EstimatedBodyGeometry` owns per-frame elbow, upper-arm/torso, arm-length,
torso-length, hip/wrist-distance and axial measurements in the common module.
It requires one body, unique joints, matching visible in-bounds image evidence,
finite world coordinates and the declared coordinate convention. Unit directions
protect angle intermediates from overflow. Pixel-crop restoration discards depth
and its convention rather than inventing a camera transform. Optional fields
preserve legacy pose decoding in Kotlin and Swift.

MediaPipe describes its world output relative to the midpoint of the hips.
The application treats its coordinates as model estimates, without assuming
calibrated physical distances. See the [official result documentation](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker/python#handle_and_display_results).

The retained [audit](dip-estimated-world-screen.audit.json) records source pins,
complete/partial processing, rejected experiments and local validation. Shared
JVM/Native contracts, person-specific native-adapter mapping tests and the actual
Swift-to-Kotlin geometry ABI are part of the existing CI suites. Their scope is
measurement transport and math, not detection accuracy.

Local validation passes 466 JVM, 447 Kotlin Native, 34 Android unit and 273 Swift
core tests, Android lint (zero errors), and instrumentation-test compilation.
Fresh unchanged counting/setup oracles retain 98 cases / 951 frames and 127
cases / 7,022 frames. The shipping Swift framework passes 11 authored geometry
checks and 2,000 queries at p95 0.025039 ms, within the existing 5 ms host budget.
That timing excludes payload construction, inference and device/camera work.
The compiled common implementation agrees with the research measurements for
all 1,818 available arms across the 1,686 saved dip frames (maximum angular
difference 5.69e-14 degrees). This is saved-input measurement agreement, not
native SDK prediction parity or detection recovery.

## Reproduce shared geometry from saved observations

The test-classpath diagnostic accepts pinned saved observations and exact source
frame metadata. It does not load a model or produce rep events. A fresh output
directory is required; missing frames, changed file pins, timestamps or source
pixels fail the run. Model metadata in the manifest remains a declaration from
the inference producer, not independent SDK authentication.

```sh
./gradlew -Pworkout.appleOnly=true \
  --init-script Evaluation/estimated-body-screen.init.gradle \
  -PestimatedBody.input=/absolute/path/to/pinned-inputs.json \
  -PestimatedBody.output=/absolute/path/to/fresh-output \
  :shared:estimatedBodyGeometryScreen
```

Input schema 1 has a `clips` array. Each item declares `id`, `frames`,
`observations`, `observationsSha256`, `metadata`, `metadataSha256`, `backend`
and `modelSha256`. Metadata is the ordered native-frame JSONL with `frameIndex`,
`timestamp`, `imageSize` and the PNG `path`. Each frame also requires a PNG hash
receipt, supplied by `pngSha256` in the pinned metadata or `input_png_sha256` in
the pinned observations. If both exist, both must match. Observations can contain the common
optional world fields or the retained MediaPipe Python `worldPeople` format.
The diagnostic retains per-frame unavailable measurements and whole-source
coverage; it cannot upgrade the producer's qualification.
It checks the supplied reference sequence, without independently decoding an
original video or authenticating which pixels an SDK consumed.
