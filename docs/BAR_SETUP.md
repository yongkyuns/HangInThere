# Guided bar setup: first implementation

Target: one athlete, fixed phone and straight gripping sections. No multi-person
identity tracker, moving-camera reconstruction, new model or iOS package. This
implements **setup proposals and confirmation**. It does not yet replace the
counter's wrist/arm assumptions or establish reliable automatic bar detection.

## In the replay app

Pause on a clear apparatus frame and tap **Set up bar**. Drag a box around one
straight section including both visible edges, then **Find edges**. Inspect the
ranked proposals and explicitly confirm the correct gripping segment. Yellow
marks the reference silhouette edge; cyan marks its opposite edge. The guided
region is manual input: this is not autonomous whole-scene semantic detection.

When no proposal is suitable, **Mark edge manually** lets the user drag a visible
reference edge. It is recorded as `manualEdge`, not successful automatic detection;
no opposite edge, width or centreline is invented. For a pull-up, mark the upper
image silhouette edge. For a dip, choose the rail for the selected anatomical hand.
Rails are independent: they need not project horizontally or parallel to each other.
This slice stores one selected bar/rail; switching hand requires new setup.

A confirmed reference is bound to its source session, timestamp, role and upright
image dimensions. Stale confirmations after restart/reimport are rejected even
when the new video has the same timestamp. Pause preserves setup. Restart,
reimport, close, hand/exercise change or changed dimensions clears it. It is
**retained but not revalidated** during replay, and labelled accordingly. Move the
phone, change framing or zoom: clear and repeat setup. Automatic movement detection,
partial-occlusion validation and lost-bar states are not implemented yet.

## Native geometric proposal

The exact `VisionBarDetector` is shared by the app and audit. It crops an integral
pixel-space guided region, runs Apple contours in both contrast polarities, and
maps normalized points into upright pixels using the actual crop offsets. Pixel
space is essential for non-square inputs.

`BarFitter` simplifies contours with 1.5-pixel Ramer–Douglas–Peucker tolerance,
extracts sufficiently long observed segments and pairs noncrossing compatible
edges. It retains only their shared support, without extrapolating through hidden
hands or inventing the whole rack. Mild perspective taper is allowed. The image
upper edge is undefined for near-vertical rails. Candidate rank uses shared support
length and directional agreement, not a calibrated semantic confidence score.
Crop-border edges are excluded. Opposite-polarity duplicates are collapsed;
adjacent rack bars remain separate candidates requiring confirmation.

Initial engineering limits: overlap >=24 pixels and >=30% of the guide's longest
side; direction difference <=5 degrees; edge separation 2–40 pixels; overlap >=1
bar width; taper minimum width >=40% of maximum width. These are **not validated
accuracy targets**. Thick, highly foreshortened, curved, heavily wrapped/occluded
bars and confusing rack edges can fail. Input points, segments and simplification
work are bounded; excess complexity fails rather than silently accepting a guess.

## Evidence boundaries

Core tests cover coordinate geometry, partial overlap, reversal, taper, vertical
rails, invalid inputs, crop edges, duplicate contours and manual provenance.
Independent Apple integration tests run actual contour detection on original
analytic pixels of dark/bright/oblique bars and blank images. These tests remain
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

The existing 0/17 temporal result remains unchanged. Next: inspect actual proposals,
validate fixed-reference visibility on controlled footage, then replace the
counter's unsupported projection/contact assumptions with bar-relative evidence.
Chin localization, physical contact and 3D clearance remain separate requirements.

Primary API references:
- https://developer.apple.com/documentation/vision/vndetectcontoursrequest
- https://developer.apple.com/documentation/vision/vncontour

## Initial photograph audit and bounded repair

The first native run (`2272782`) produced no candidate in any of the three images.
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
