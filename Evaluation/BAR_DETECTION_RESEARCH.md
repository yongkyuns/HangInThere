# Bar-detection method screening

This work treats **apparatus localization as independent from human pose**. The
bar benchmark accepts image pixels and a user-guided search region only. No wrist,
elbow, shoulder, person box, pose confidence, or repetition state participates in
bar extraction or ranking.

The production `VisionBarDetector` in PR #6 is therefore provisional. Its presence
in the app does not mean Apple contours have been selected as the final method.

## Candidate methods

The first screening set is intentionally small:

- **OpenCV LineSegmentDetector (LSD)**: mature classical line detector. OpenCV >=4.5
  is Apache-2.0. Host-only benchmark dependency for now.
- **Probabilistic Hough transform (`HoughLinesP`)**: classical baseline on Canny
  edges, also through Apache-2.0 OpenCV.
- **M-LSD tiny 512**: NAVER's mobile-oriented learned line detector. The official
  project and weights are Apache-2.0 and publish mobile/TFLite variants. Research CI
  uses the Apache-licensed PyTorch port pinned to a commit solely to compare line
  evidence; this does not add a runtime dependency to the iOS app.

An existing GitHub pull-up counter with a separate YOLO bar checkpoint was reviewed
as prior art, but its repository has no license file. Its checkpoint is therefore
**not used or redistributed** by HangInThere. A bar-specific learned detector remains
an option if generic line methods fail and we obtain/train a rights-clear model.

DeepLSD is another MIT-licensed generic line detector with line refinement, but is
not in the first mobile-oriented screen because M-LSD is materially smaller and has
published mobile models. It can be added if the first three methods leave a quality
ambiguity.

## Common geometric interpretation

Every generic extractor returns line segments. The benchmark then applies one shared
bar-specific interpretation:

1. merge nearby collinear fragments;
2. form pairs of approximately parallel, separated edges with overlapping visible
   support inside the guided region;
3. rank by observed overlap/support and parallelism, without body landmarks or bar
   reference labels;
4. for a pull-up bar, report the locally upper edge from the first-ranked pair;
5. score finite support, so a short fragment cannot receive whole-bar credit merely
   because one point lies on its extrapolated infinite line.

This is a method-screening policy, not a final semantic bar classifier.

## Frozen development evidence

The research fixture reuses previously exposed source-pilot media. It is **not a
held-out accuracy set**. The fixed-camera pull-up video has 44 visible apparatus
frames plus one opening graphic with no apparatus; the reviewed upper edge from a
clear frame is used as the static development reference. A rear-view pull-up still
has three reviewed upper-edge points. The wrapped dip rail is availability-only.

Before M-LSD execution, the local classical screen produced:

| Method | Pull-up video visible availability | Opening graphic false positive | Mean / p95 finite-edge disagreement | Rear pull-up still | Wrapped dip rail |
| --- | ---: | ---: | ---: | ---: | --- |
| OpenCV LSD | 44/44 | 0/1 | 5.23 / 16.66 px | 0.50 px mean, full long edge | unavailable |
| HoughLinesP | 44/44 | 0/1 | 3.23 / 13.61 px | 28.37 px mean; wrong/partial first pair | candidate, unlabelled |

These values are diagnostics from approximate single-reviewer references. They show
that generic segment extraction can recover the clear pull-up bar where the initial
Vision-contour fitter returned no candidate. They do **not** establish population
accuracy or choose a shipping backend.

## Decision gate

Do not change the app's bar backend until the same benchmark includes M-LSD and the
results have been visually audited. A candidate should have high clear-frame
availability, low wrong-bar selection, accurate finite support, no-bar rejection,
and a credible iOS deployment path. Runtime size/latency is evaluated only after
line quality is adequate.

Pose-model accuracy, repetition counting, and the simulator's missing Vision body
weights are separate gates.

## Completed first method screen

The first host run processed the exact same fixed images with no pose input:

| Method | Pull-up video visible | Opening graphic FP | Video mean / p95 | Rear still mean / max | Wrapped dip |
| --- | ---: | ---: | ---: | ---: | --- |
| OpenCV LSD | 44/44 | 0/1 | 5.23 / 16.66 px | 0.50 / 1.25 px | unavailable |
| HoughLinesP | 44/44 | 0/1 | 3.23 / 13.61 px | 28.37 / 84.38 px | candidate, unlabelled |
| M-LSD tiny 512 | 44/44 | 0/1 | 7.53 / 16.89 px | 32.04 / 79.20 px | candidate, unlabelled |

M-LSD did not improve this development evidence, so adding a learned line model to
shipping iOS is not justified yet. Hough had the lowest average disagreement on the
fixed video but selected a poor partial/wrong pair on the rear still. LSD was the
most consistent of the three on the labelled pull-up examples.

These comparisons still use a **paired-edge interpretation**. The next experiment
asks a different and smaller question: does Apple's existing raw contour request
already contain a sufficiently accurate *single relevant bar edge*? A pull-up
clear-frame probe showed that it does: standard RDP contour simplification plus
collinear fragment merging recovered the labelled edge at about 1 px mean error.
On the rear still, allowing internal gaps between collinear observed fragments
recovered the full labelled upper edge at about 1.3 px mean error. That ranking uses
only contour geometry and the guided ROI; labels are used only afterward for score.

The `native-contours` research job repeats that experiment across all 44 visible
fixed-camera pull-up frames plus the opening no-bar graphic and the two stills.
Only if that full run remains stable should the production fitter change. This is
still guided line refinement, not semantic free-scene bar recognition.