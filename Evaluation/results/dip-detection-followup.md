# Dip detection follow-up — 2026-10-18

Production accuracy remains **1/26 matched events, 25 missed dips, zero extras**.
All 1,866 original frames are processed. The original event windows, timing
tolerance, model, acquisition rules and counter policy remain unchanged. This
checkpoint records development evidence; it does not qualify a detection fix.

The [audit](dip-detection-followup.audit.json) records the tested `dcd5217`
checkout, unchanged compiled production inputs, local suite results and completed
CI. Android API 29/35 instrumentation, shared analysis and native host integration
pass. The original Android and selected-video accuracy checks still fail. A
passing API 29 run does not establish the earlier library timeout's cause.

## Rejected approaches

- A permissive camera-registration screen accepted 286/300 controlled frames,
  but also accepted contradictory synthetic motion planes. Requiring every
  sufficiently tracked region to agree reduces that screen to 13/300 frames.
  Native person masks improve coverage to 152/300, with only 14 consecutive
  accepted frames. None establishes continuous camera or cycle tracking.
- An offline RepNet screen overestimates all four development totals. A frozen
  person's photo under periodic camera transforms produces an estimated 5.05
  repetition mass despite zero anatomical cycles. No completed events are
  derived. The screen restores all 226 weights exactly once, explicitly corrects
  one upstream checkpoint-mapping typo, and binds the executed notebook cells.
  Its batch size 1 changes padded stride-scoring semantics from the official
  batch size 20; this is a disclosed inference-policy variant.

## Finite equipment proposals

On the 300 controlled native PNGs, the unchanged selector returns 132 left-arm
proposals from native contours. Reviewed examples select fingers. OpenCV LSD
proposals with the unchanged fitter, plus a predeclared person-mask exclusion
screen at 0.5, return 298 proposals; 287 are near horizontal. Eight predeclared
frames visibly locate the metal rail. These totals measure proposal availability,
not semantic accuracy, ambiguity rejection, temporal identity or acquisition.
The mask scores are uncalibrated; other mask settings and failures remain in the
audit. No extractor is promoted.

A separate measurement screen tests shoulder distance normal to a fresh rail,
divided by observed torso length. Nine authored checks cover common 2D similarity
invariance, endpoint order and limitations. Real geometry is available on only
181/300 frames; 106 frames lack the required torso observation. Missing values
remain missing. This scalar neither estimates along-rail grip slip nor replaces
the counter's frozen shoulder–wrist travel evidence.

The next capability needs actual, unambiguous apparatus identity and usable
motion geometry before counting. It must preserve source clocks, missing-data
boundaries, opposite-arm contradictions and completed-return evidence, then pass
the original event checks, camera/occlusion negatives and native performance
evaluation. Raw public experiment artifacts remain local; their hashes identify
retained evidence rather than independent reproduction. PR #29 stays draft and
the detection goal remains active.
