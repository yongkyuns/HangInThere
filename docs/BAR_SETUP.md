# Guided bar setup: first implementation

Target: one athlete, fixed phone and straight gripping sections. No multi-person
identity tracker, moving-camera reconstruction, new model or iOS package. This
implements **setup proposals and confirmation** and supplies the fixed reference used
by counter policy v2. It does not establish reliable autonomous whole-scene bar detection.

## In the replay app

Pause on a clear apparatus frame and tap **Set up bar**. Drag a box tightly around
one visible straight reference edge, then **Find edge**. Inspect the ranked
observed-line proposals and explicitly confirm the correct gripping edge. Yellow
marks the confirmed reference edge; no opposite silhouette edge is invented. The
guided region is manual input: this is not autonomous whole-scene semantic detection.

When no proposal is suitable, **Mark edge manually** lets the user drag a visible
reference edge. It is recorded as `manualEdge`, not successful automatic detection;
no opposite edge, width or centreline is invented. For a pull-up, mark the upper
image silhouette edge. For a dip, choose the rail for the selected anatomical hand.
Rails are independent: they need not project horizontally or parallel to each other.
This slice stores one selected bar/rail; switching hand requires new setup.

A confirmed reference is bound to its source session, timestamp, role and upright
image dimensions. Stale confirmations after restart/reimport are rejected even
when the new video has the same timestamp. Pause preserves setup. Restart,
reimport, close, hand/exercise change or changed dimensions clears it. A normal
restart of the same source preserves it. Confirming a reference rewinds the video so
bar-relative counting begins from source time zero. The edge is **retained but not
revalidated** during replay. Move the phone, change framing or zoom: clear and repeat
setup. Automatic partial-occlusion validation and lost-bar states are not implemented yet.

## Native geometric proposal

The exact `VisionBarDetector` is shared by the app and audit. It crops an integral
pixel-space guided region, runs Apple contours in both contrast polarities, and
maps normalized points into upright pixels using the actual crop offsets. Pixel
space is essential for non-square inputs.

`BarLineFitter` follows the bar-only research result rather than requiring a
complete two-edge silhouette. It simplifies contours with a 1-pixel
Ramer–Douglas–Peucker tolerance, extracts observed line fragments, and merges
fragments that remain collinear (<=8 degrees, <=5 pixels normal offset) across a
short internal gap. The gap is bounded by min(45 px, 20% of the guide's longest
side). The merged line never extends beyond the outermost observed endpoints.
Crop-border artifacts are rejected; opposite-polarity duplicate evidence is
collapsed; distinct rack lines remain separate proposals requiring confirmation.
Candidate score is observed finite line support, not semantic bar probability.

A retained edge must cover at least 25% of the guide's longest side. These are
**development geometry limits**, not exercise accuracy targets. Curved bars,
severe foreshortening, wrapped/texture-dominated rails and a guide containing a
longer competing rack edge can still fail. Input points, segments and
simplification work remain bounded; excess complexity fails rather than silently
accepting a guess.

## Evidence boundaries

Core tests cover the legacy paired-edge geometry plus single-edge fragment
merging, large-gap separation, crop-edge rejection, duplicate evidence, distinct
rack lines and confirmation provenance. Independent Apple integration tests run
actual contour detection on original analytic pixels of dark/bright/oblique bars,
short occlusion and blank images. These tests remain
fatal on native macOS and the iOS simulator mechanics suite; they do not depend on
the separately failing human-pose model test.

`Evaluation/BarSetupAudit.swift` runs the exact detector on three already-permitted
source-pilot images. Two pull-up regions have six approximate visible upper-edge
points, chosen visually before bar inference. A wrapped dip rail has no quantitative
reference points and reports proposal availability only. This is guided,
prior-source-exposed diagnostic data, not an independent bar benchmark. No result
uses the best candidate as if the system had chosen it: errors describe the first
ranked proposal. No candidate means unavailable, not zero error. No thresholds or
reference points should be retuned to pass this tiny audit.

The counter now consumes this confirmed edge under policy v2; see `COUNTING.md`.
The original policy-v1 0/17 temporal result remains historical evidence. A fixed-
camera pull-up replay now reproduces its one reviewed movement after replacing the
unsupported wrist/projected-arm continuity guards. Controlled dip qualification,
chin localization, physical contact and 3D clearance remain separate requirements.

Primary API references:
- https://developer.apple.com/documentation/vision/vndetectcontoursrequest
- https://developer.apple.com/documentation/vision/vncontour

## Initial photograph audit and bounded repair

The original paired-edge native run (`2272782`) produced no candidate in any of the three images.
An isolated raw-contour probe found normalized Float samples a few ULPs beyond the
crop border, which made the fitter discard entire otherwise usable contours.
The adapter now corrects only boundary rounding within four Float ULPs; truly
out-of-range/nonfinite samples still fail. This is not a looser landmark gate.

A separate defect was applying a whole-bar aspect-ratio assumption to a local
cropped contour segment. The old six-width minimum is removed: the visible overlap
must still exceed its thickness and the unchanged absolute/ROI-relative minimum.
Synthetic crop-length invariance and crop-border tests cover this change. This is
a development revision prompted by observed failure, not held-out validation.
References and guides are unchanged. Missing/fragmented edges remain a real failure,
not evidence that an entire bar was localized. New native audit results must be
reported separately from the original zero-candidate run.


## Single-edge promotion

The bar-only method screen showed that raw Apple contour evidence already contains accurate local bar edges even when the paired-silhouette fitter rejects the image. The research single-edge reconstruction detected the bar on all 44 visible frames of the fixed pull-up sequence with zero proposal on its opening no-bar graphic, and recovered the labelled rear-view pull-up edge at roughly 1.3 px mean disagreement. OpenCV LSD remains a strong host-only reference, but adding OpenCV to the iOS app is not justified merely to perform this one-time guided setup.

The production guided path now follows that single-edge reconstruction and records a confirmed guidedContours reference with oppositeEdge absent. Manual marking stays separately identified as manualEdge. A successful proposal is still **not semantic whole-scene bar recognition**: the user-supplied guide and confirmation are part of the supported controlled-setting workflow.